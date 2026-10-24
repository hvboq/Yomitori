package mihon.translation

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.Renderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TextRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic page-edge cases: a fitting smaller font must be tried before rejecting the page. */
@RunWith(AndroidJUnit4::class)
class RenderInkFitTest {
    private val config = RenderConfig(
        orientation = TextOrientation.HORIZONTAL,
        fontSizeMin = 9,
        fontSizeMax = 40,
        fontScale = 1f,
        rowTrim = 0,
        colTrim = 0,
    )

    private fun region(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String,
        angle: Float = 0f,
        onArt: Boolean = false,
    ) = TextRegion(
        listOf(TextLine(listOf(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom)), 1f)),
        "h",
        angle,
        (left + right) / 2f,
        (top + bottom) / 2f,
        right - left,
        bottom - top,
    ).apply {
        translatedText = text
        this.onArt = onArt
    }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun assertFitsByChoosingFont(item: TextRegion, cfg: RenderConfig) {
        // A gray canvas makes both black fill and white outlines visible. No models, settings,
        // network, user files or Activity state are needed for these real Paint/Canvas checks.
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE0E0E0.toInt()) }
        val before = source.pixels()
        try {
            // Prove this exact text, outline and rotation can be safely drawn at the original
            // location. The requested range must find such a size rather than report clipping.
            val small = Renderer.render(source, listOf(item), cfg.copy(fontSizeMax = cfg.fontSizeMin))
            try {
                assertFalse("The minimum-size edge control must contain visible ink", source.sameAs(small))
            } finally {
                small.recycle()
            }

            // The larger layout also fits when centered. This distinguishes page/ink fitting
            // from text overflow, unsupported glyphs or an impossible region.
            val dx = 128f - item.cx
            val dy = 128f - item.cy
            val centered = region(
                item.x0 + dx,
                item.y0 + dy,
                item.x1 + dx,
                item.y1 + dy,
                item.translatedText,
                item.angle,
                item.onArt,
            )
            val control = Renderer.render(source, listOf(centered), cfg)
            try {
                assertFalse("The centered control must contain visible ink", source.sameAs(control))
            } finally {
                control.recycle()
            }

            val rendered = Renderer.render(source, listOf(item), cfg)
            try {
                assertFalse(
                    "The edge translation must render instead of failing its final ink guard",
                    source.sameAs(rendered),
                )
            } finally {
                rendered.recycle()
            }
            assertArrayEquals("Rendering must not mutate the input page", before, source.pixels())
        } finally {
            source.recycle()
        }
    }

    @Test
    fun horizontalKoreanOutlineAtLeftEdgeUsesAFittingSmallerFont() {
        assertFitsByChoosingFont(
            region(0f, 70f, 16f, 186f, "한", onArt = true),
            config.copy(expandW = 2f, artStrokeRatio = 0.5f),
        )
    }

    @Test
    fun mixedKoreanAndBitmapEmojiAtRightEdgeUsesTheWholeRunBounds() {
        assertFitsByChoosingFont(
            region(224f, 70f, 256f, 186f, "가😀", onArt = true),
            config.copy(expandW = 2f, artStrokeRatio = 0.5f),
        )
    }

    @Test
    fun rotatedKoreanRowsAtLeftEdgeFitTheirTransformedInk() {
        assertFitsByChoosingFont(region(0f, 80f, 40f, 176f, "한\n글", angle = 60f), config)
    }

    @Test
    fun verticalTateChuYokoAtRightEdgeFitsScaledOutlinedInk() {
        assertFitsByChoosingFont(
            region(240f, 70f, 256f, 186f, "12", onArt = true),
            config.copy(orientation = TextOrientation.VERTICAL, expandW = 2f, artStrokeRatio = 0.5f),
        )
    }
}
