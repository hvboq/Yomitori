package eu.kanade.tachiyomi.data.translation

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
}
