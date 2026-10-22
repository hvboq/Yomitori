package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class ArchiveTranslationStateTest {
    @Test
    fun `legacy manifest still covers all top level pages`() {
        val fixture = Fixture()
        fixture.files[CompletionManifestCodec.PRIMARY] = CompletionManifestCodec.encode(setOf(PAGE))
        fixture.isComplete() shouldBe true
        fixture.files["002.png"] = byteArrayOf(1)
        fixture.isComplete() shouldBe false
    }

    @Test
    fun `pending journal overrides old completed checkpoints`() {
        val fixture = Fixture()
        fixture.files[CompletionManifestCodec.PRIMARY] = CompletionManifestCodec.encode(setOf(PAGE))
        fixture.files[CompletionManifestCodec.CHECKPOINT_PREFIX + "11111111-1111-1111-1111-111111111111"] =
            CompletionManifestCodec.encode(setOf(PAGE))
        fixture.commit()
        fixture.isComplete() shouldBe false
    }

    @Test
    fun `missing commit and WIP cannot revive stale completion over partial pixels`() {
        val fixture = Fixture()
        fixture.files[CompletionManifestCodec.PRIMARY] = CompletionManifestCodec.encode(setOf(PAGE))
        fixture.commit()
        fixture.files.keys.removeAll { it.endsWith(".commit") || it.endsWith(".wip") }
        val before = fixture.files.mapValues { it.value.copyOf() }
        fixture.isComplete() shouldBe false
        fixture.assertUnchanged(before)
    }

    @Test
    fun `aborted preparation with unchanged source does not erase prior legacy completion`() {
        val fixture = Fixture()
        fixture.files[CompletionManifestCodec.PRIMARY] = CompletionManifestCodec.encode(setOf(PAGE))
        fixture.crashBeforeWip = true
        shouldThrow<AssertionError> { fixture.commit() }
        val before = fixture.files.mapValues { it.value.copyOf() }
        fixture.isComplete() shouldBe true
        fixture.assertUnchanged(before)
    }

    @Test
    fun `verified completion tombstone survives missing manifest`() {
        val fixture = Fixture()
        val pending = fixture.commit()
        fixture.commit(base = pending.revision, complete = true)
        fixture.isComplete() shouldBe true
    }

    @Test
    fun `completed journal must verify original output mask and visible page`() {
        listOf("original", "snapshot", "mask", "page").forEach { damaged ->
            withClue(damaged) {
                val fixture = Fixture()
                val state = fixture.commit(complete = true)
                val path = when (damaged) {
                    "original" -> ".yakuyomi/${state.original.name}"
                    "snapshot" -> ".yakuyomi/${state.outputSnapshotName}"
                    "mask" -> ".yakuyomi/${state.rawMaskSnapshotName}"
                    else -> PAGE
                }
                fixture.files[path] = fixture.files.getValue(path) + byteArrayOf(1)
                fixture.isComplete() shouldBe false
            }
        }
    }

    @Test
    fun `complete marker cannot override unresolved OCR payload`() {
        val fixture = Fixture()
        fixture.commit(complete = true, pendingPayload = true)
        fixture.isComplete() shouldBe false
    }

    @Test
    fun `unknown OCR schema fails completion without mutating archive`() {
        val fixture = Fixture()
        fixture.commit(complete = true, payload = "{\"schemaVersion\":999}")
        val before = fixture.files.mapValues { it.value.copyOf() }
        fixture.isComplete() shouldBe false
        fixture.assertUnchanged(before)
    }

    @Test
    fun `committed WIP is validated without attempting read only cleanup`() {
        val fixture = Fixture()
        fixture.commit(complete = true)
        val (name, bytes) = checkNotNull(fixture.lastWip)
        fixture.files[name] = bytes
        val before = fixture.files.mapValues { it.value.copyOf() }
        fixture.isComplete() shouldBe true
        fixture.assertUnchanged(before)
    }

    @Test
    fun `unfinished WIP never rolls back an archive during completion query`() {
        val fixture = Fixture()
        fixture.files[CompletionManifestCodec.PRIMARY] = CompletionManifestCodec.encode(setOf(PAGE))
        fixture.crashBeforeCommit = true
        shouldThrow<AssertionError> { fixture.commit(complete = true) }
        val before = fixture.files.mapValues { it.value.copyOf() }
        fixture.isComplete() shouldBe false
        fixture.assertUnchanged(before)
    }

    @Test
    fun `empty archive is never complete`() {
        ArchiveTranslationState.isComplete(emptySet(), emptySet()) { null } shouldBe false
    }

    @Test
    fun `completed migration verifies its captured baseline without modifying the archive`() {
        val fixture = Fixture()
        val previousOutput = "previous successful translation".toByteArray()
        fixture.files[PAGE] = previousOutput
        val baseline = PartialPageStore.InitialBaseline(previousOutput, PartialPageStore.sha256(previousOutput))
        val state = fixture.commit(complete = true, baseline = baseline)
        fixture.isComplete() shouldBe true
        val baselinePath = ".yakuyomi/${state.initialBaselineSnapshotName}"
        fixture.files[baselinePath] = fixture.files.getValue(baselinePath) + byteArrayOf(1)
        val before = fixture.files.mapValues { it.value.copyOf() }
        fixture.isComplete() shouldBe false
        fixture.assertUnchanged(before)
    }

    @Test
    fun `unknown stored translation context cannot validate archive completion`() {
        val fixture = Fixture()
        fixture.commit(complete = true, safeContext = "{\"schemaVersion\":999}")
        val before = fixture.files.mapValues { it.value.copyOf() }
        fixture.isComplete() shouldBe false
        fixture.assertUnchanged(before)
    }

    private class Fixture {
        val files = mutableMapOf(PAGE to "exact source bytes".toByteArray())
        var lastWip: Pair<String, ByteArray>? = null
        var crashBeforeCommit = false
        var crashBeforeWip = false
        private val original = PartialPageStore.Original(
            OriginalMaterial.exactName(PAGE),
            PartialPageStore.sha256(files.getValue(PAGE)),
            files.getValue(PAGE).size.toLong(),
        )
        private val store = PartialPageStore(object : PartialPageStore.Io {
            override fun materialNames(): Set<String> = files.keys.filter { it.startsWith(".yakuyomi/") }
                .mapTo(HashSet()) { it.removePrefix(".yakuyomi/") }
            override fun readMaterial(name: String, maxBytes: Int): ByteArray? = files[".yakuyomi/$name"]
            override fun readPage(name: String, maxBytes: Int): ByteArray? = files[name]
            override fun replacePage(name: String, bytes: ByteArray): Boolean {
                files[name] = bytes.copyOf()
                return true
            }
            override fun createMaterial(name: String, bytes: ByteArray): Boolean {
                if (crashBeforeCommit && name.endsWith(".commit")) throw AssertionError("Simulated process death")
                if (crashBeforeWip &&
                    name.endsWith(".wip")
                ) {
                    throw AssertionError("Simulated process death before page write")
                }
                val path = ".yakuyomi/$name"
                if (path in files) return false
                files[path] = bytes.copyOf()
                if (name.endsWith(".wip")) lastWip = path to bytes.copyOf()
                return true
            }
            override fun deleteMaterial(name: String): Boolean = files.remove(".yakuyomi/$name") != null
        })

        init {
            files[".yakuyomi/${original.name}"] = files.getValue(PAGE).copyOf()
        }

        fun commit(
            base: String? = null,
            complete: Boolean = false,
            pendingPayload: Boolean = !complete,
            payload: String = progress(pendingPayload),
            baseline: PartialPageStore.InitialBaseline? = null,
            safeContext: String? = null,
        ): PartialPageStore.State = store.commit(
            PAGE, original, WIDTH, HEIGHT, payload,
            (if (complete) "complete output" else "partial output").toByteArray(), mask(), base, complete,
            initialBaseline = baseline,
            safeContextPayload = safeContext,
        )

        fun isComplete(): Boolean = ArchiveTranslationState.isComplete(
            files.keys.filter { '/' !in it && it.endsWith(".png") }.toSet(),
            files.keys.toSet(),
        ) { files[it]?.inputStream() }

        fun assertUnchanged(before: Map<String, ByteArray>) {
            files.keys shouldBe before.keys
            before.forEach { (name, bytes) ->
                withClue(name) { files.getValue(name).contentEquals(bytes) shouldBe true }
            }
        }
    }

    companion object {
        private const val PAGE = "001.png"
        private const val WIDTH = 4
        private const val HEIGHT = 6

        private fun progress(pending: Boolean): String = OcrProgressCodec.encode(
            PageProgress(
                WIDTH,
                HEIGHT,
                listOf(
                    RegionProgress(
                        0,
                        if (pending) RegionState.OCR_PENDING else RegionState.TRANSLATED,
                        listOf(
                            OcrLineProgress(
                                listOf(Pt(0f, 0f), Pt(4f, 0f), Pt(4f, 6f), Pt(0f, 6f)),
                                1f,
                                "v",
                                if (pending) "" else "source",
                            ),
                        ),
                        "v",
                        translatedText = if (pending) "" else "번역",
                    ),
                ),
            ),
        )

        // Storage-format fixture only. These tests do not assert native PNG decoding.
        private fun mask(): ByteArray = ByteArray(33).also { bytes ->
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10).copyInto(bytes)
            ByteBuffer.wrap(bytes).apply {
                position(8)
                putInt(13)
                put("IHDR".toByteArray())
                putInt(WIDTH)
                putInt(HEIGHT)
            }
        }
    }
}
