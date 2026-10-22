package mihon.translation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.Renderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TextRegion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RenderingQualityTest {
    private fun region(text: String, angle: Float = 0f) = TextRegion(
        listOf(TextLine(listOf(Pt(180f, 90f), Pt(250f, 90f), Pt(250f, 240f), Pt(180f, 240f)), 1f)),
        "v",
        angle,
        215f,
        165f,
        70f,
        150f,
    ).apply { translatedText = text }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun changedBounds(before: IntArray, rendered: Bitmap): Rect {
        val after = rendered.pixels()
        val bounds = Rect(rendered.width, rendered.height, 0, 0)
        for (y in 0 until rendered.height) {
            for (x in 0 until rendered.width) {
                if (before[y * rendered.width + x] == after[y * rendered.width + x]) continue
                bounds.left = minOf(bounds.left, x)
                bounds.top = minOf(bounds.top, y)
                bounds.right = maxOf(bounds.right, x + 1)
                bounds.bottom = maxOf(bounds.bottom, y + 1)
            }
        }
        return bounds
    }

    @Test
    fun narrowKoreanBubbleUsesReadableHorizontalLayoutAndExportsMeasuredFixtures() {
        val source = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 300 until 340) for (x in 360 until 430) setPixel(x, y, Color.BLUE)
        }
        val protected = TextRegion(
            listOf(TextLine(listOf(Pt(360f, 300f), Pt(430f, 300f), Pt(430f, 340f), Pt(360f, 340f)), 1f)),
            "h",
        ).apply { preserveOriginal = true }
        val before = source.pixels()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "rendering-quality")
            .apply { mkdirs() }
        val measurements = JSONArray()
        val text = "오늘은 정말 좋은 날이에요."
        val cfg = RenderConfig(fontSizeMin = 20, fontSizeMax = 20, fontScale = 1f)
        val bitmaps = ArrayList<Bitmap>()
        fun render(name: String, item: TextRegion, config: RenderConfig): Bitmap {
            val rendered = Renderer.render(source, listOf(item, protected), config)
            bitmaps.add(rendered)
            val bounds = changedBounds(before, rendered)
            assertFalse("Fixture must contain visible translated ink", bounds.isEmpty)
            assertTrue(
                bounds.left > 0 && bounds.top > 0 && bounds.right < source.width && bounds.bottom < source.height,
            )
            for (y in 300 until 340) {
                for (x in 360 until 430) {
                    assertTrue("Protected lettering changed", rendered.getPixel(x, y) == Color.BLUE)
                }
            }
            File(output, "$name.png").outputStream().use {
                assertTrue(rendered.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            measurements.put(
                JSONObject().put("fixture", name).put("text", item.translatedText)
                    .put("fontSize", 20).put("orientation", config.orientation.name).put("angle", item.angle)
                    .put("inkLeft", bounds.left).put("inkTop", bounds.top)
                    .put("inkRight", bounds.right).put("inkBottom", bounds.bottom)
                    .put("protectedPixelsUnchanged", true),
            )
            assertArrayEquals(before, source.pixels())
            return rendered
        }
        try {
            File(output, "source.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Previously rowTrim=3 forced nearly one glyph per row and rejected this fixed 20 px size.
            val auto = render("narrow-korean-auto", region(text), cfg)
            val horizontal =
                render("narrow-korean-horizontal", region(text), cfg.copy(orientation = TextOrientation.HORIZONTAL))
            assertTrue("AUTO must use horizontal Korean even when the source is vertical", auto.sameAs(horizontal))
            val vertical =
                render(
                    "narrow-korean-explicit-vertical",
                    region(text),
                    cfg.copy(orientation = TextOrientation.VERTICAL),
                )
            assertFalse("Explicit vertical remains supported", auto.sameAs(vertical))
            val ink = changedBounds(before, auto)
            assertTrue("Layout should span multiple readable rows", ink.height() > 40)
            assertTrue(
                "Horizontal layout remains centered in the bubble",
                kotlin.math.abs(ink.exactCenterX() - 215f) < 10f,
            )
            render("rotated-korean", region(text, 25f), cfg)
            render("paragraphs-and-unicode", region("안녕!\n\n가족 👩‍💻\n좋아요 ❤️"), cfg)
            render(
                "vertical-unicode-paragraphs",
                region("안녕👩‍💻\n좋아❤️"),
                cfg.copy(orientation = TextOrientation.VERTICAL),
            )
            File(output, "measurements.json").writeText(measurements.toString(2))
            assertTrue(File(output, "measurements.json").length() > 0)
        } finally {
            bitmaps.forEach(Bitmap::recycle)
            source.recycle()
        }
    }
}
