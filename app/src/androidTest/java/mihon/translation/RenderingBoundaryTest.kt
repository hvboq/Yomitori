package mihon.translation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.Renderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TextRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderingBoundaryTest {
    private fun locatedRegion(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String,
        angle: Float = 0f,
        boxWidth: Float = right - left,
        boxHeight: Float = bottom - top,
    ) = TextRegion(
        listOf(TextLine(listOf(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom)), 1f)),
        "h",
        angle,
        (left + right) / 2f,
        (top + bottom) / 2f,
        boxWidth,
        boxHeight,
    ).apply { translatedText = text }

    private fun region(width: Float, text: String) = TextRegion(
        listOf(TextLine(listOf(Pt(20f, 20f), Pt(20f + width, 20f), Pt(20f + width, 100f), Pt(20f, 100f)), 1f)),
        "h",
    ).apply { translatedText = text }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    @Test
    fun pageEdgesRejectClippedHorizontalVerticalAndRotatedGlyphs() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = source.pixels()
        val horizontal = RenderConfig(
            orientation = TextOrientation.HORIZONTAL,
            fontSizeMin = 20,
            fontSizeMax = 20,
            fontScale = 1f,
            rowTrim = 0,
            fontBorder = false,
        )
        val cases = listOf(
            // Room must fit the full run even with the device's Korean fallback font metrics.
            // Expansion changes available layout room, not the center used for drawing.
            locatedRegion(0f, 0f, 80f, 80f, "가\n나\n다\n라\n마") to horizontal.copy(expandH = 3f),
            locatedRegion(0f, 80f, 8f, 160f, "한") to horizontal.copy(expandW = 4f),
            locatedRegion(50f, 240f, 150f, 256f, "가\n나") to horizontal.copy(expandH = 6f),
            locatedRegion(248f, 80f, 256f, 160f, "한") to horizontal.copy(
                orientation = TextOrientation.VERTICAL,
                colTrim = 0,
                expandW = 3f,
            ),
            locatedRegion(0f, 100f, 80f, 120f, "가\n나\n다\n라\n마", 90f, 20f, 80f) to horizontal.copy(expandH = 3f),
        )
        try {
            for ((index, pair) in cases.withIndex()) {
                val (region, config) = pair
                // A centered control must actually render with identical font, text, box size,
                // expansion and rotation. Thus the edge failure cannot be a font-fit rejection.
                val dx = 128f - region.cx
                val dy = 128f - region.cy
                val centered = locatedRegion(
                    region.x0 + dx,
                    region.y0 + dy,
                    region.x1 + dx,
                    region.y1 + dy,
                    region.translatedText,
                    region.angle,
                    region.boxW,
                    region.boxH,
                )
                val control = Renderer.render(source, listOf(centered), config)
                try {
                    assertFalse("Case $index must fit and draw at page center", source.sameAs(control))
                } finally {
                    control.recycle()
                }
                assertArrayEquals(before, source.pixels())
                val failure = assertThrows(IllegalStateException::class.java) {
                    Renderer.render(source, listOf(region), config).recycle()
                }
                assertTrue("Case $index: ${failure.message}", failure.message.orEmpty().contains("beyond the page"))
                assertArrayEquals(before, source.pixels())
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun outlineCannotExtendBeyondPageWhenFillFits() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val region = locatedRegion(1f, 80f, 21f, 160f, "한").apply { onArt = true }
        val config = RenderConfig(
            orientation = TextOrientation.HORIZONTAL,
            fontSizeMin = 20,
            fontSizeMax = 20,
            fontScale = 1f,
            rowTrim = 0,
            artStrokeRatio = 1f,
        )
        try {
            // An expanded layout rectangle may cross the edge; fitting ink must remain accepted.
            Renderer.render(source, listOf(region), config.copy(fontBorder = false)).recycle()
            val failure = assertThrows(IllegalStateException::class.java) {
                Renderer.render(source, listOf(region), config)
            }
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("beyond the page"))
        } finally {
            source.recycle()
        }
    }

    @Test
    fun mixedKoreanAndEmojiCannotClipOnlyTheBitmapGlyphAtPageEdges() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = source.pixels()
        val config = RenderConfig(
            orientation = TextOrientation.HORIZONTAL,
            fontSizeMin = 20,
            fontSizeMax = 20,
            fontScale = 1f,
            rowTrim = 0,
            expandW = 3f,
            fontBorder = false,
        )
        val regions = listOf(
            locatedRegion(0f, 80f, 20f, 160f, "😀가"),
            locatedRegion(236f, 80f, 256f, 160f, "가😀"),
        )
        try {
            for (region in regions) {
                val mixedText = region.translatedText
                region.translatedText = "가"
                Renderer.render(source, listOf(region), config).recycle()
                region.translatedText = mixedText
                val failure = assertThrows(IllegalStateException::class.java) {
                    Renderer.render(source, listOf(region), config)
                }
                assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("beyond the page"))
                assertArrayEquals(before, source.pixels())
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun oversizedHorizontalAndVerticalTranslationsFailWithoutChangingInput() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = source.pixels()
        try {
            for (orientation in listOf(TextOrientation.HORIZONTAL, TextOrientation.VERTICAL)) {
                assertThrows(IllegalStateException::class.java) {
                    Renderer.render(
                        source,
                        listOf(region(80f, "긴 번역문입니다. ".repeat(200))),
                        RenderConfig(orientation = orientation),
                    )
                }
                assertArrayEquals(before, source.pixels())
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun tinyRegionFailsInsteadOfSilentlyDroppingTranslation() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = source.pixels()
        try {
            assertThrows(IllegalStateException::class.java) {
                Renderer.render(source, listOf(region(7f, "안녕")))
            }
            assertArrayEquals(before, source.pixels())
        } finally {
            source.recycle()
        }
    }

    @Test
    fun drawingOverProtectedLetteringFailsWithoutChangingInput() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = source.pixels()
        try {
            val protected = region(160f, "원문").apply { preserveOriginal = true }
            assertThrows(IllegalStateException::class.java) {
                Renderer.render(
                    source,
                    listOf(region(160f, "안녕하세요"), protected),
                    RenderConfig(orientation = TextOrientation.HORIZONTAL),
                )
            }
            assertArrayEquals(before, source.pixels())
        } finally {
            source.recycle()
        }
    }

    @Test
    fun fittingKoreanTranslationStillRenders() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val rendered = Renderer.render(
                source,
                listOf(region(160f, "안녕하세요")),
                RenderConfig(orientation = TextOrientation.HORIZONTAL),
            )
            try {
                assertFalse(source.sameAs(rendered))
            } finally {
                rendered.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
