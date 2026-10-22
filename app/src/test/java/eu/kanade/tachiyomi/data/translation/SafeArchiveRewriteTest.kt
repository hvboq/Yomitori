package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
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
}
