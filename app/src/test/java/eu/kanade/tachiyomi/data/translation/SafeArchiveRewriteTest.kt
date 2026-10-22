package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile

class SafeArchiveRewriteTest {
    @Test
    fun `unsafe nested duplicate and reserved names fail before extracting any entry`() = temporary { root ->
        val invalid = listOf(
            listOf("../page.jpg"), listOf("/page.jpg"), listOf("C:/page.jpg"),
            listOf("folder\\page.jpg"), listOf("folder/page.jpg"), listOf("page.jpg", "page.jpg"),
            listOf("page.jpg", "PAGE.JPG"), listOf("page.jpg", "page.png"),
            listOf(".yakuyomi"), listOf(".YAKUYOMI/page.orig.jpg"), listOf(".yakuyomi/../page.jpg"),
        )
        for (names in invalid) {
            var opened = false
            shouldThrow<IllegalArgumentException> {
                SafeArchiveRewrite.extract(names.map { it to true }, root, {
                    opened = true
                    byteArrayOf(1).inputStream()
                }) {
                    false
                }
            }
            opened shouldBe false
        }
        root.listFiles()!!.isEmpty() shouldBe true
    }

    @Test
    fun `archive retains original bytes materials and completion checkpoints`() = temporary { root ->
        val pages = File(root, "pages").apply { mkdir() }
        val expected = mapOf(
            "001.jpg" to byteArrayOf(1, 2, 3),
            ".yakuyomi/001.orig.jpg" to byteArrayOf(9, 8, 7),
            ".yakuyomi/001.json" to "regions".toByteArray(),
            ".yakuyomi_translated.checkpoint-00000000-0000-0000-0000-000000000000" to "checkpoint".toByteArray(),
        )
        SafeArchiveRewrite.extract(
            expected.keys.map {
                it to true
            },
            pages,
            { expected.getValue(it).inputStream() },
        ) { false }
        val zip = File(root, "translated.zip")
        SafeArchiveRewrite.packAndVerify(pages, zip) { false }
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().map { it.name }.toSet() shouldBe expected.keys
            expected.forEach { (name, bytes) ->
                archive.getInputStream(archive.getEntry(name)).use { it.readBytes().toList() } shouldBe bytes.toList()
            }
        }
    }

    @Test
    fun `partial OCR journal survives CBZ roundtrip and can commit its remaining progress`() = temporary { root ->
        val pages = File(root, "pages").apply { mkdir() }
        val fixture = PartialFixture(pages)
        val partial = fixture.commit("first successful group")
        // Ordinary original/render/night materials must survive alongside opaque journal files.
        File(pages, ".yakuyomi/001.json").writeText("existing render materials")
        File(pages, ".yakuyomi/001.mask.png").writeBytes(mask())
        File(pages, ".yakuyomi/001.png.night.std.webp").writeText("existing standard night pixels")
        File(pages, ".yakuyomi/001.png.night.more.webp").writeText("existing stronger night pixels")
        val checkpoint = ".yakuyomi_translated.checkpoint-00000000-0000-0000-0000-000000000000"
        File(pages, checkpoint).writeBytes(CompletionManifestCodec.encode(setOf(PAGE, "other.png")))

        val reopened = roundtrip(pages, root, "partial")
        val store = PartialPageStore(DirectoryPartialIo(reopened))
        store.load(PAGE) shouldBe partial
        store.readOutput(partial).decodeToString() shouldBe "first successful group"
        store.readRawMask(partial).toList() shouldBe mask().toList()
        val staleDone = CompletionManifestCodec.resolve(mapOf(checkpoint to File(reopened, checkpoint).readBytes()))
        staleDone shouldBe setOf(PAGE, "other.png")
        store.applyDoneOverlay(staleDone) shouldBe setOf("other.png")
        File(reopened, ".yakuyomi/${fixture.original.name}").readBytes().toList() shouldBe
            fixture.originalBytes.toList()

        val complete = store.commit(
            pageName = PAGE,
            original = fixture.original,
            width = 4,
            height = 3,
            progressPayload = "{\"successful\":[\"kept first translation\",\"new second translation\"],\"pending\":[]}",
            outputBytes = "both successful groups".toByteArray(),
            rawMaskBytes = store.readRawMask(partial),
            baseRevision = partial.revision,
            complete = true,
        )
        val completedArchive = roundtrip(reopened, root, "complete")
        val completedStore = PartialPageStore(DirectoryPartialIo(completedArchive))
        completedStore.load(PAGE) shouldBe complete
        complete.generation shouldBe 2
        complete.baseRevision shouldBe partial.revision
        completedStore.applyDoneOverlay(emptySet()) shouldBe setOf(PAGE)
        File(completedArchive, ".yakuyomi/${fixture.original.name}").readBytes().toList() shouldBe
            fixture.originalBytes.toList()
    }

    @Test
    fun `CBZ retains unfinished WIP authority and restores original or last committed snapshot`() = temporary { root ->
        for (hasCommittedParent in listOf(false, true)) {
            val pages = File(root, "pages-$hasCommittedParent").apply { mkdir() }
            val fixture = PartialFixture(pages)
            val previous = if (hasCommittedParent) fixture.commit("committed partial pixels") else null
            fixture.io.crashNextReplacement = true
            shouldThrow<SimulatedProcessDeath> {
                fixture.commit("interrupted new pixels", previous?.revision)
            }
            val interruptedBytes = File(pages, PAGE).readBytes()
            interruptedBytes.size shouldBe 1
            fixture.io.materialNames().any { it.endsWith(".wip") } shouldBe true

            val reopened = roundtrip(pages, root, "interrupted-$hasCommittedParent")
            val io = DirectoryPartialIo(reopened)
            val store = PartialPageStore(io)
            shouldThrow<IOException> { store.load(PAGE, recover = false) }
            File(reopened, PAGE).readBytes().toList() shouldBe interruptedBytes.toList()
            io.replacements shouldBe 0
            store.applyDoneOverlay(setOf(PAGE)) shouldBe emptySet()

            store.load(PAGE) shouldBe previous
            val expected = previous?.let(store::readOutput) ?: fixture.originalBytes
            File(reopened, PAGE).readBytes().toList() shouldBe expected.toList()
            File(reopened, ".yakuyomi/${fixture.original.name}").readBytes().toList() shouldBe
                fixture.originalBytes.toList()
            io.materialNames().any { it.endsWith(".wip") } shouldBe false
        }
    }

    @Test
    fun `cancellation during backup hashing closes input before touching the target`() = temporary { root ->
        val (original, replacement) = files(root)
        val target = MemoryStorage(original.readBytes())
        val saved = MemoryStorage()
        var stopped = false
        var inputClosed = false
        val backup = object : SafeArchiveRewrite.Storage {
            override fun write() = saved.write()

            override fun read() = object : FilterInputStream(saved.read()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, length).also { if (it > 0) stopped = true }

                override fun close() {
                    inputClosed = true
                    super.close()
                }
            }
        }

        shouldThrow<IOException> { SafeArchiveRewrite.commit(original, replacement, target, backup) { stopped } }

        inputClosed shouldBe true
        target.writes shouldBe 0
        target.bytes.toList() shouldBe original.readBytes().toList()
        saved.bytes.toList() shouldBe original.readBytes().toList()
    }

    @Test
    fun `replacement cancellation cannot cancel original rollback or its verification`() = temporary { root ->
        val (original, replacement) = files(root)
        val savedTarget = MemoryStorage(original.readBytes())
        val backup = MemoryStorage()
        var stopped = false
        val target = object : SafeArchiveRewrite.Storage {
            override fun read() = savedTarget.read()

            override fun write(): OutputStream {
                val output = savedTarget.write()
                val replacementWrite = savedTarget.writes == 1
                return object : OutputStream() {
                    override fun write(value: Int) {
                        output.write(value)
                        if (replacementWrite) stopped = true
                    }

                    override fun close() = output.close()
                }
            }
        }

        val error = shouldThrow<IOException> {
            SafeArchiveRewrite.commit(original, replacement, target, backup) { stopped }
        }

        stopped shouldBe true
        error.cause!!.suppressed.size shouldBe 0
        savedTarget.writes shouldBe 2
        savedTarget.bytes.toList() shouldBe original.readBytes().toList()
        backup.bytes.toList() shouldBe original.readBytes().toList()
    }

    @Test
    fun `successful commit retains verified original backup`() = temporary { root ->
        val (original, replacement) = files(root)
        val target = MemoryStorage(original.readBytes())
        val backup = MemoryStorage()
        SafeArchiveRewrite.commit(original, replacement, target, backup) { false }
        target.bytes.toList() shouldBe replacement.readBytes().toList()
        backup.bytes.toList() shouldBe original.readBytes().toList()
    }

    @Test
    fun `partial replacement failure restores original and retains backup`() = temporary { root ->
        val (original, replacement) = files(root)
        val target = MemoryStorage(original.readBytes(), failWrites = setOf(1))
        val backup = MemoryStorage()
        shouldThrow<IOException> { SafeArchiveRewrite.commit(original, replacement, target, backup) { false } }
        target.bytes.toList() shouldBe original.readBytes().toList()
        backup.bytes.toList() shouldBe original.readBytes().toList()
        target.writes shouldBe 2
    }

    @Test
    fun `failed rollback still preserves verified backup and reports failure`() = temporary { root ->
        val (original, replacement) = files(root)
        val target = MemoryStorage(original.readBytes(), failWrites = setOf(1, 2))
        val backup = MemoryStorage()
        val error = shouldThrow<IOException> {
            SafeArchiveRewrite.commit(original, replacement, target, backup) { false }
        }
        error.cause!!.suppressed.size shouldBe 1
        backup.bytes.toList() shouldBe original.readBytes().toList()
    }

    @Test
    fun `unverified backup prevents any change to original`() = temporary { root ->
        val (original, replacement) = files(root)
        val target = MemoryStorage(original.readBytes())
        val backup = MemoryStorage(truncateWrites = true)
        shouldThrow<IllegalArgumentException> {
            SafeArchiveRewrite.commit(original, replacement, target, backup) { false }
        }
        target.writes shouldBe 0
        target.bytes.toList() shouldBe original.readBytes().toList()
    }

    @Test
    fun `silent truncated replacement is detected and restored`() = temporary { root ->
        val (original, replacement) = files(root)
        val target = MemoryStorage(original.readBytes(), truncateFirst = true)
        val backup = MemoryStorage()
        shouldThrow<IOException> { SafeArchiveRewrite.commit(original, replacement, target, backup) { false } }
        target.bytes.toList() shouldBe original.readBytes().toList()
    }

    @Test
    fun `concurrent source change prevents commit`() = temporary { root ->
        val (original, replacement) = files(root)
        val target = MemoryStorage("edited elsewhere".toByteArray())
        val backup = MemoryStorage()
        shouldThrow<IllegalArgumentException> {
            SafeArchiveRewrite.commit(original, replacement, target, backup) { false }
        }
        target.writes shouldBe 0
        backup.writes shouldBe 0
    }

    /** Storage-contract fixtures only; these bytes do not claim native image or reader decoding. */
    private class PartialFixture(directory: File) {
        val originalBytes = "exact original page bytes".toByteArray()
        val io = DirectoryPartialIo(directory)
        val store = PartialPageStore(io)
        val original = PartialPageStore.Original(
            OriginalMaterial.exactName(PAGE),
            PartialPageStore.sha256(originalBytes),
            originalBytes.size.toLong(),
        )

        init {
            File(directory, PAGE).writeBytes(originalBytes)
            io.createMaterial(original.name, originalBytes) shouldBe true
        }

        fun commit(output: String, baseRevision: String? = null): PartialPageStore.State = store.commit(
            pageName = PAGE,
            original = original,
            width = 4,
            height = 3,
            progressPayload = "{\"successful\":[\"kept translation\"],\"pending\":[\"unread group\"]}",
            outputBytes = output.toByteArray(),
            rawMaskBytes = mask(),
            baseRevision = baseRevision,
            complete = false,
        )
    }

    private class SimulatedProcessDeath : Error("Interrupted between page write and final commit")

    private class DirectoryPartialIo(private val directory: File) : PartialPageStore.Io {
        private val materials = File(directory, ".yakuyomi").apply { check(isDirectory || mkdir()) }
        var crashNextReplacement = false
        var replacements = 0

        override fun materialNames(): Set<String> = checkNotNull(materials.list()).toSet()

        override fun readMaterial(name: String, maxBytes: Int): ByteArray? = read(File(materials, name), maxBytes)

        override fun readPage(name: String, maxBytes: Int): ByteArray? = read(File(directory, name), maxBytes)

        override fun createMaterial(name: String, bytes: ByteArray): Boolean {
            val file = File(materials, name)
            if (file.exists()) return false
            file.writeBytes(bytes)
            return true
        }

        override fun deleteMaterial(name: String): Boolean = File(materials, name).delete()

        override fun replacePage(name: String, bytes: ByteArray): Boolean {
            replacements++
            if (crashNextReplacement) {
                crashNextReplacement = false
                File(directory, name).writeBytes(bytes.copyOf(1))
                throw SimulatedProcessDeath()
            }
            File(directory, name).writeBytes(bytes)
            return true
        }

        private fun read(file: File, maxBytes: Int): ByteArray? {
            if (!file.exists()) return null
            check(file.isFile && file.length() <= maxBytes)
            return file.readBytes()
        }
    }

    private fun roundtrip(directory: File, root: File, name: String): File {
        val expected = directory.walkTopDown().filter(File::isFile).associate {
            it.relativeTo(directory).invariantSeparatorsPath to it.readBytes()
        }
        val zip = File(root, "$name.cbz")
        SafeArchiveRewrite.packAndVerify(directory, zip) { false }
        val extracted = File(root, "$name-reopened").apply { check(mkdir()) }
        ZipFile(zip).use { archive ->
            val entries = archive.entries().asSequence().toList()
            entries.map { it.name }.toSet() shouldBe expected.keys
            expected.forEach { (path, bytes) ->
                val entry = checkNotNull(archive.getEntry(path))
                entry.size shouldBe bytes.size.toLong()
                entry.crc shouldBe CRC32().apply { update(bytes) }.value
                archive.getInputStream(entry).use { it.readBytes().toList() } shouldBe bytes.toList()
            }
            SafeArchiveRewrite.extract(
                entries.map { it.name to !it.isDirectory },
                extracted,
                { path -> archive.getInputStream(checkNotNull(archive.getEntry(path))) },
            ) { false }
        }
        return extracted
    }

    private fun files(root: File) = File(root, "original.cbz").apply { writeText("original archive bytes") } to
        File(root, "replacement.cbz").apply { writeText("translated archive bytes") }

    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("archive-safety").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class MemoryStorage(
        var bytes: ByteArray = byteArrayOf(),
        private val failWrites: Set<Int> = emptySet(),
        private val truncateWrites: Boolean = false,
        private val truncateFirst: Boolean = false,
    ) : SafeArchiveRewrite.Storage {
        var writes = 0

        override fun read() = bytes.inputStream()

        override fun write(): OutputStream {
            writes++
            bytes = byteArrayOf()
            val number = writes
            val output = ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(value: Int) {
                    output.write(value)
                    bytes = output.toByteArray()
                    if (number in failWrites) throw IOException("Injected storage failure")
                }

                override fun close() {
                    bytes = output.toByteArray().let {
                        if (truncateWrites || (truncateFirst && number == 1)) it.copyOf(it.size / 2) else it
                    }
                }
            }
        }
    }

    private companion object {
        const val PAGE = "001.png"

        // Only the store's PNG header/dimensions contract is exercised here; no codec is involved.
        fun mask(): ByteArray = ByteArray(33).apply {
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10).copyInto(this)
            this[11] = 13
            "IHDR".toByteArray().copyInto(this, 12)
            fun writeInt(offset: Int, value: Int) {
                for (index in 0..3) this[offset + index] = (value ushr (24 - index * 8)).toByte()
            }
            writeInt(16, 4)
            writeInt(20, 3)
        }
    }
}
