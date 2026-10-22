package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import org.junit.jupiter.api.Test

class OcrProgressCodecTest {
    @Test
    fun `explicit no text detection round trips as final empty progress`() {
        val original = PageProgress(1200, 1600, emptyList(), noTextDetected = true)
        val decoded = OcrProgressCodec.decode(OcrProgressCodec.encode(original), 1200, 1600)
        decoded shouldBe original
        decoded.hasPending shouldBe false
        decoded.toTextRegions() shouldBe emptyList()
    }

    @Test
    fun `empty progress without detection proof and no text with regions are rejected`() {
        val noText = PageProgress(1200, 1600, emptyList(), noTextDetected = true)
        shouldThrow<IllegalArgumentException> { OcrProgressCodec.encode(noText.copy(noTextDetected = false)) }
        shouldThrow<IllegalArgumentException> { OcrProgressCodec.encode(progress().copy(noTextDetected = true)) }
        rejected(OcrProgressCodec.encode(noText).replace("\"noTextDetected\":true", "\"noTextDetected\":false"))
    }

    @Test
    fun `existing nonempty schema one payload defaults missing no text flag to false`() {
        val payload = OcrProgressCodec.encode(progress()).replace(",\"noTextDetected\":false", "")
        OcrProgressCodec.decode(payload, 1200, 1600) shouldBe progress()
    }

    @Test
    fun `all states geometry line metadata targets and unicode round trip losslessly`() {
        val original = progress()
        val payload = OcrProgressCodec.encode(original)
        val decoded = OcrProgressCodec.decode(payload, 1200, 1600)
        decoded shouldBe original
        decoded.pendingRegionIds shouldBe listOf(1)
        decoded.regions[0].translatedText shouldBe "성공한 번역\n\"인용\" \\ 🙂"
        decoded.regions[2].lines[0].intentionallyKept shouldBe true
        decoded.regions[0].lines[0].quad[0].x.toRawBits() shouldBe (-0.0f).toRawBits()
    }

    @Test
    fun `resolving a pending group preserves earlier targets and stable ids`() {
        val saved = OcrProgressCodec.decode(OcrProgressCodec.encode(progress()), 1200, 1600)
        val pending = saved.regions[1]
        val resolved = saved.copy(
            regions = saved.regions.toMutableList().apply {
                this[1] = pending.copy(
                    state = RegionState.TRANSLATED,
                    lines = pending.lines.map { if (it.text.isBlank()) it.copy(text = "読めた") else it },
                    translatedText = "새 번역",
                )
            },
        )
        val decoded = OcrProgressCodec.decode(OcrProgressCodec.encode(resolved), 1200, 1600)
        decoded.hasPending shouldBe false
        decoded.regions.map { it.id } shouldBe listOf(0, 1, 2)
        decoded.regions[0] shouldBe saved.regions[0]
        decoded.regions[2] shouldBe saved.regions[2]
    }

    @Test
    fun `unsupported schema and unknown fields are explicit failures`() {
        val payload = OcrProgressCodec.encode(progress())
        rejected(payload.replace("\"schemaVersion\":1", "\"schemaVersion\":9"))
        rejected(payload.dropLast(1) + ",\"future\":true}")
    }

    @Test
    fun `missing fields malformed and truncated payloads cannot acquire defaults`() {
        val payload = OcrProgressCodec.encode(progress())
        rejected(payload.replace("\"schemaVersion\":1,", ""))
        rejected(payload.replace(",\"intentionallyKept\":false", ""))
        rejected(payload.dropLast(2))
        rejected("[]")
        rejected("")
    }

    @Test
    fun `mismatched original dimensions are rejected`() {
        val payload = OcrProgressCodec.encode(progress())
        shouldThrow<IllegalArgumentException> { OcrProgressCodec.decode(payload, 1199, 1600) }
        shouldThrow<IllegalArgumentException> { OcrProgressCodec.decode(payload, 1200, 1599) }
    }

    @Test
    fun `duplicate reordered and unknown region ids or states are rejected`() {
        val payload = OcrProgressCodec.encode(progress())
        rejected(payload.replace("\"id\":1", "\"id\":0"))
        rejected(payload.replace("\"id\":0", "\"id\":4"))
        rejected(payload.replace("OCR_PENDING", "UNRECOGNIZED_STATE"))
    }

    @Test
    fun `pending blank lines cannot be silently declared translated or deliberately kept`() {
        val payload = OcrProgressCodec.encode(progress())
        rejected(payload.replace("OCR_PENDING", "TRANSLATED"))
        rejected(payload.replace("OCR_PENDING", "INTENTIONALLY_KEPT"))
        val bad = progress().let { original ->
            original.copy(
                regions = original.regions.map {
                    if (it.id ==
                        1
                    ) {
                        it.copy(translatedText = "false done")
                    } else {
                        it
                    }
                },
            )
        }
        shouldThrow<IllegalArgumentException> { OcrProgressCodec.encode(bad) }
    }

    @Test
    fun `nonfinite and out of page geometry are rejected on encode and decode`() {
        val original = progress()
        val bad = original.copy(regions = original.regions.map { if (it.id == 1) it.copy(angle = Float.NaN) else it })
        shouldThrow<IllegalArgumentException> { OcrProgressCodec.encode(bad) }
        val payload = OcrProgressCodec.encode(original)
        rejected(payload.replace("\"angle\":7.25", "\"angle\":NaN"))
        rejected(payload.replace("\"x\":400.0", "\"x\":1400.0"))
        rejected(payload.replace("\"direction\":\"h\"", "\"direction\":\"invalid\""))
    }

    @Test
    fun `payload bounds apply before parsing and count utf8 bytes`() {
        val limit = OcrProgressCodec.MAX_PAYLOAD_BYTES
        shouldThrow<IllegalArgumentException> { OcrProgressCodec.decode(" ".repeat(limit + 1), 1200, 1600) }
        shouldThrow<IllegalArgumentException> { OcrProgressCodec.decode("가".repeat(limit / 3 + 1), 1200, 1600) }
    }

    @Test
    fun `oversized strings are rejected before or after json escaping without truncation`() {
        val original = progress()
        fun oversized(text: String): PageProgress = original.copy(
            regions = original.regions.map { if (it.id == 0) it.copy(translatedText = text) else it },
        )
        shouldThrow<IllegalArgumentException> {
            OcrProgressCodec.encode(oversized("x".repeat(OcrProgressCodec.MAX_PAYLOAD_BYTES + 1)))
        }
        shouldThrow<IllegalArgumentException> {
            OcrProgressCodec.encode(oversized("가".repeat(OcrProgressCodec.MAX_PAYLOAD_BYTES / 3 + 1)))
        }
    }

    private fun rejected(payload: String) {
        shouldThrow<IllegalArgumentException> { OcrProgressCodec.decode(payload, 1200, 1600) }
    }

    private fun progress(): PageProgress = PageProgress(
        width = 1200,
        height = 1600,
        regions = listOf(
            RegionProgress(
                id = 0,
                state = RegionState.TRANSLATED,
                lines = listOf(line(-0.0f, "読めた\n\"引用\" \\ 🙂")),
                direction = "h",
                angle = 7.25f,
                cx = 100.125f,
                cy = 44.625f,
                boxW = 180.75f,
                boxH = 40.125f,
                translatedText = "성공한 번역\n\"인용\" \\ 🙂",
                onArt = true,
            ),
            RegionProgress(
                id = 1,
                state = RegionState.OCR_PENDING,
                lines = listOf(line(400f, "一部"), line(400f, "")),
                direction = "v",
                angle = -4.5f,
                cx = 440f,
                cy = 42f,
                boxW = 100f,
                boxH = 200f,
            ),
            RegionProgress(
                id = 2,
                state = RegionState.INTENTIONALLY_KEPT,
                lines = listOf(line(800f, "").copy(intentionallyKept = true)),
                direction = "h",
            ),
        ),
    )

    private fun line(x: Float, text: String): OcrLineProgress = OcrLineProgress(
        quad = listOf(Pt(x, 20.125f), Pt(x + 160.375f, 20.125f), Pt(x + 160.375f, 60.5f), Pt(x, 60.5f)),
        score = 0.87525f,
        direction = "h",
        text = text,
    )
}
