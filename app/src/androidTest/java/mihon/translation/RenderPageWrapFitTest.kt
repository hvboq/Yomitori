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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A page-safe narrower wrap can fit when every original-width font candidate fails. */
@RunWith(AndroidJUnit4::class)
class RenderPageWrapFitTest {
    private val config = RenderConfig(
        orientation = TextOrientation.HORIZONTAL,
        fontSizeMin = 9,
        fontSizeMax = 40,
        // Use the product default scale: the preferred size can already equal the minimum.
        fontScale = 0.85f,
        artStrokeRatio = 0.5f,
    )

    private fun region(left: Float, right: Float, angle: Float) = TextRegion(
        listOf(TextLine(listOf(Pt(left, 80f), Pt(right, 80f), Pt(right, 170f), Pt(left, 170f)), 1f)),
        "h",
        angle,
        (left + right) / 2f,
        125f,
        right - left,
        90f,
    ).apply {
        translatedText = "한글한글한글한글한글"
        onArt = true
    }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun assertNarrowerWrapFits(left: Float, right: Float, angle: Float) {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE0E0E0.toInt()) }
        val before = source.pixels()
        val item = region(left, right, angle)
        var narrowControl: Bitmap? = null
        try {
            // Neither reducing below the configured minimum nor dropping any glyph is needed:
            // this same minimum-size text, center, outline and rotation fits a narrower wrap.
            val narrow = Renderer.render(
                source,
                listOf(item),
                config.copy(fontSizeMax = config.fontSizeMin, expandW = 0.8f),
            )
            narrowControl = narrow
            assertFalse("The narrower fixed-minimum control must contain visible ink", source.sameAs(narrow))

            // A deliberately fixed font retains the existing failure contract. The new fallback
            // is restricted to automatic font ranges and cannot weaken this boundary guard.
            val fixedFailure = assertThrows(IllegalStateException::class.java) {
                Renderer.render(source, listOf(item), config.copy(fontSizeMax = config.fontSizeMin)).recycle()
            }
            assertTrue(fixedFailure.message.orEmpty(), fixedFailure.message.orEmpty().contains("beyond the page"))
            assertArrayEquals("The rejected fixed-font draw must preserve its source", before, source.pixels())

            val centered = region(117.9f, 138.1f, angle)
            val control = Renderer.render(source, listOf(centered), config)
            try {
                assertFalse("The unchanged original-width center control must render", source.sameAs(control))
            } finally {
                control.recycle()
            }

            val rendered = Renderer.render(source, listOf(item), config)
            try {
                assertFalse("Automatic fitting must try a page-safe narrower wrap", source.sameAs(rendered))
                assertTrue("Every glyph must match the complete same-font narrow-wrap control", narrow.sameAs(rendered))
            } finally {
                rendered.recycle()
            }
            assertArrayEquals("Rendering must preserve the source bitmap", before, source.pixels())
        } finally {
            narrowControl?.recycle()
            source.recycle()
        }
    }

    @Test
    fun minimumSizeOutlinedRowsAtLeftEdgeFitWithNarrowerWrap() {
        assertNarrowerWrapFits(0f, 20.2f, 0f)
    }

    @Test
    fun slightlyRotatedRowsAtRightEdgeFitWithSameCenterAndNarrowerWrap() {
        assertNarrowerWrapFits(235.8f, 256f, -2f)
    }
}
