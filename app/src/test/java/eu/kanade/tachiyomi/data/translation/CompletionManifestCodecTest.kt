package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CompletionManifestCodecTest {
    @Test
    fun `complete snapshots round trip including empty and escaped file names`() {
        for (pages in listOf(emptySet(), setOf("001.png", "002.png"), setOf("line\nbreak.png", "한글.png"))) {
            CompletionManifestCodec.decode(CompletionManifestCodec.encode(pages)) shouldBe pages
        }
    }

    @Test
    fun `every truncated snapshot is rejected including a valid page name prefix`() {
        val complete = CompletionManifestCodec.encode(setOf("001.png", "001.png-more.png"))
        for (length in 0 until complete.size) {
            CompletionManifestCodec.decode(complete.copyOf(length)) shouldBe null
        }
    }

    @Test
    fun `changed payload trailing garbage and plain legacy lists are not checkpoints`() {
        val bytes = CompletionManifestCodec.encode(setOf("001.png"))
        val damaged = bytes.copyOf().apply { this[lastIndex - 3] = '2'.code.toByte() }
        CompletionManifestCodec.decode(damaged) shouldBe null
        CompletionManifestCodec.decode(bytes + "extra".toByteArray()) shouldBe null
        CompletionManifestCodec.decode("001.png\n002.png".toByteArray()) shouldBe null
    }

    @Test
    fun `verified empty and legacy primary or previous snapshots remain usable`() {
        val checkpoint = CompletionManifestCodec.CHECKPOINT_PREFIX + "11111111-1111-1111-1111-111111111111"
        for (name in listOf(CompletionManifestCodec.PRIMARY, CompletionManifestCodec.PREVIOUS, checkpoint)) {
            withClue("Verified empty snapshot at $name") {
                val files = mapOf(name to CompletionManifestCodec.encode(emptySet()))
                CompletionManifestCodec.hasUsableSnapshot(files) shouldBe true
                CompletionManifestCodec.resolve(files) shouldBe emptySet()
            }
        }
        val legacyCases = listOf(
            byteArrayOf() to emptySet(),
            "001.png\n002.png\n".toByteArray() to setOf("001.png", "002.png"),
        )
        for (name in listOf(CompletionManifestCodec.PRIMARY, CompletionManifestCodec.PREVIOUS)) {
            for ((bytes, pages) in legacyCases) {
                withClue("Legacy snapshot at $name with ${pages.size} pages") {
                    val files = mapOf(name to bytes)
                    CompletionManifestCodec.hasUsableSnapshot(files) shouldBe true
                    CompletionManifestCodec.resolve(files) shouldBe pages
                }
            }
        }
    }

    @Test
    fun `valid checkpoint keeps recovery usable beside corrupt or oversized primary`() {
        val pages = setOf("001.png", "002.png")
        val verified = CompletionManifestCodec.encode(pages)
        val corrupt = verified.copyOf().apply { this[indexOf('\n'.code.toByte()) + 1] = 'g'.code.toByte() }
        val oversized = ByteArray(CompletionManifestCodec.MAX_BYTES + 1).apply { verified.copyInto(this) }
        val checkpoint = CompletionManifestCodec.CHECKPOINT_PREFIX + "11111111-1111-1111-1111-111111111111"
        for ((label, primary) in listOf("invalid checksum" to corrupt, "oversized" to oversized)) {
            withClue(label) {
                CompletionManifestCodec.decode(primary) shouldBe null
                val files = mapOf(CompletionManifestCodec.PRIMARY to primary, checkpoint to verified)
                CompletionManifestCodec.hasUsableSnapshot(files) shouldBe true
                CompletionManifestCodec.resolve(files) shouldBe pages
            }
        }
    }

    @Test
    fun `absent unrelated and wholly invalid snapshots do not authorize recovery`() {
        val verified = CompletionManifestCodec.encode(setOf("001.png"))
        val corrupt = verified.copyOf().apply { this[indexOf('\n'.code.toByte()) + 1] = 'g'.code.toByte() }
        val unknownVersion = "YOMITORI-MANIFEST-999\nsynthetic unsupported snapshot".toByteArray()
        val oversized = ByteArray(CompletionManifestCodec.MAX_BYTES + 1)
        val checkpoint = CompletionManifestCodec.CHECKPOINT_PREFIX + "11111111-1111-1111-1111-111111111111"
        val cases = mapOf(
            "absent files" to emptyMap(),
            "unrelated verified file" to mapOf("unrelated.json" to verified),
            "unrecognized checkpoint name" to mapOf(CompletionManifestCodec.CHECKPOINT_PREFIX + "invalid" to verified),
            "legacy bytes are not a checkpoint" to mapOf(checkpoint to "001.png\n".toByteArray()),
            "all recognized snapshots corrupt" to mapOf(
                CompletionManifestCodec.PRIMARY to corrupt,
                CompletionManifestCodec.PREVIOUS to unknownVersion,
                checkpoint to corrupt,
            ),
            "all recognized snapshots oversized" to mapOf(
                CompletionManifestCodec.PRIMARY to oversized,
                CompletionManifestCodec.PREVIOUS to oversized,
                checkpoint to oversized,
            ),
        )
        for ((label, files) in cases) {
            withClue(label) {
                CompletionManifestCodec.hasUsableSnapshot(files) shouldBe false
            }
        }
    }
}
