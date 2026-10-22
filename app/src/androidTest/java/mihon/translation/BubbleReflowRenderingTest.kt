package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class BubbleReflowRenderingTest {
    private fun region(box: RectF, text: String, direction: String = "v") = TextRegion(
        listOf(
            TextLine(
                listOf(
                    Pt(box.left, box.top),
                    Pt(box.right, box.top),
                    Pt(box.right, box.bottom),
                    Pt(box.left, box.bottom),
                ),
                1f,
            ),
        ),
        direction,
    ).apply {
        translatedText = text
        onArt = true
    }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    @Test fun verifiedBandFitsActualGlyphOverhangAndOutlineAtUnscaledFontSize() {
        val page = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        Canvas(page).drawRect(40f, 30f, 260f, 260f, Paint().apply { color = Color.WHITE })
        val before = page.pixels()
        val permitted = Rect(48, 68, 252, 202)
        try {
            for (border in listOf(false, true)) {
                for (text in listOf("가나다라마바사아자차", "WAVE 가나다라마 👩‍💻")) {
                    val result = Renderer.render(
                        page,
                        listOf(region(RectF(135f, 60f, 165f, 210f), text)),
                        RenderConfig(
                            orientation = TextOrientation.HORIZONTAL,
                            fontScale = 1f,
                            rowTrim = 0,
                            fontBorder = border,
                        ),
                        Typeface.create("sans-serif", Typeface.ITALIC),
                    )
                    try {
                        val after = result.pixels()
                        var changed = 0
                        for (y in 0 until page.height) {
                            for (x in 0 until page.width) {
                                if (before[y * page.width + x] == after[y * page.width + x]) continue
                                changed++
                                assertTrue(
                                    "Ink escaped verified band at $x,$y border=$border",
                                    permitted.contains(x, y),
                                )
                            }
                        }
                        assertTrue(changed > 0)
                        assertArrayEquals(before, page.pixels())
                    } finally {
                        result.recycle()
                    }
                }
            }
        } finally {
            page.recycle()
        }
    }

    @Test fun groupedVerticalTextAndTallSingleColumnUseCompactLinesInsideOvalBalloons() {
        val page = Bitmap.createBitmap(340, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        val bubbles = listOf(RectF(40f, 40f, 260f, 240f), RectF(50f, 300f, 280f, 560f))
        val paint = Paint().apply { color = Color.WHITE }
        bubbles.forEach { Canvas(page).drawOval(it, paint) }
        // The first region already groups multiple Japanese columns and is not twice as tall as wide.
        val regions = listOf(
            region(RectF(114f, 91f, 187f, 189f), "네, 아버님"),
            region(RectF(151f, 361f, 180f, 499f), "……너는,"),
        )
        val before = page.pixels()
        try {
            val rendered = Renderer.render(page, regions, RenderConfig(orientation = TextOrientation.HORIZONTAL))
            try {
                val after = rendered.pixels()
                val ink = listOf(Rect(340, 600, 0, 0), Rect(340, 600, 0, 0))
                for (y in 0 until page.height) {
                    for (x in 0 until page.width) {
                        if (before[y * page.width + x] == after[y * page.width + x]) continue
                        assertEquals("Artwork changed at $x,$y", Color.WHITE, before[y * page.width + x])
                        val index = if (y < 270) 0 else 1
                        assertTrue(bubbles[index].contains(x.toFloat(), y.toFloat()))
                        ink[index].apply {
                            left = minOf(left, x)
                            top = minOf(top, y)
                            right = maxOf(right, x + 1)
                            bottom = maxOf(bottom, y + 1)
                        }
                    }
                }
                ink.forEach {
                    assertTrue(
                        "Short reply still breaks into a vertical stack: $it",
                        it.width() > 70 && it.height() < 45,
                    )
                }
                assertArrayEquals(before, page.pixels())
                assertEquals(listOf("네, 아버님", "……너는,"), regions.map { it.translatedText })
            } finally {
                rendered.recycle()
            }
        } finally {
            page.recycle()
        }
    }

    @Test fun outlineOutsideAnotherRegionsBoxCannotInvadeReflowEvenAfterRotation() {
        for (angle in listOf(0f, 25f)) {
            val page = Bitmap.createBitmap(1200, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
            Canvas(page).drawRect(80f, 100f, 550f, 900f, Paint().apply { color = Color.WHITE })
            val radians = Math.toRadians(angle.toDouble())
            val quad = listOf(-35f to -30f, 35f to -30f, 35f to 30f, -35f to 30f).map { (x, y) ->
                Pt(
                    630f + (x * cos(radians) - y * sin(radians)).toFloat(),
                    480f + (x * sin(radians) + y * cos(radians)).toFloat(),
                )
            }
            val outside = TextRegion(listOf(TextLine(quad, 1f)), "h", angle, 630f, 480f, 70f, 60f).apply {
                translatedText = "한"
                onArt = true
            }
            // Even its expanded OCR box is outside the white balloon. Actual outline ink is not.
            assertTrue(outside.x0 - (outside.x1 - outside.x0) * 0.15f > 550f)
            val regions = listOf(
                region(RectF(401f, 288f, 480f, 678f), "잠시만 기다려"),
                region(RectF(283f, 290f, 361f, 600f), "주세요"),
                outside,
            )
            val before = page.pixels()
            try {
                val failure = assertThrows(IllegalStateException::class.java) {
                    Renderer.render(
                        page,
                        regions,
                        RenderConfig(
                            orientation = TextOrientation.HORIZONTAL,
                            fontSizeMin = 40,
                            fontSizeMax = 40,
                            fontScale = 1f,
                            rowTrim = 0,
                            artStrokeRatio = 5f,
                        ),
                    ).recycle()
                }
                assertTrue(failure.message.orEmpty().contains("overlap another reflow area"))
                assertArrayEquals(before, page.pixels())
            } finally {
                page.recycle()
            }
        }
    }

    @Test fun fourNarrowVerticalColumnsBecomeReadableHorizontalTextInsideTwoBalloons() {
        val page = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val canvas = Canvas(page)
        val paint = Paint()
        for (left in listOf(80f, 650f)) {
            paint.color = Color.DKGRAY
            canvas.drawRect(left - 8, 92f, left + 478, 1478f, paint)
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(left, 100f, left + 470f, 1470f), 70f, 70f, paint)
        }
        val protectedBox = RectF(120f, 1200f, 220f, 1300f)
        paint.color = Color.BLUE
        canvas.drawRect(protectedBox, paint)
        val regions = listOf(
            region(RectF(401.225f, 287.917f, 479.794f, 677.711f), "잠시만 기다려"),
            region(RectF(283.166f, 289.416f, 360.584f, 599.647f), "주세요"),
            region(RectF(974.339f, 289.964f, 1044.412f, 836.599f), "내일도 잘"),
            region(RectF(856.36f, 291.913f, 927.246f, 759.647f), "부탁드립니다"),
            region(protectedBox, "보존", "h").apply { preserveOriginal = true },
        )
        val before = page.pixels()
        val originalText = regions.map { it.translatedText }
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "bubble-reflow").apply {
            mkdirs()
        }
        var rendered: Bitmap? = null
        try {
            val result = Renderer.render(page, regions, RenderConfig(orientation = TextOrientation.HORIZONTAL))
            rendered = result
            val after = result.pixels()
            val permitted = listOf(Rect(88, 295, 542, 670), Rect(658, 297, 1112, 829))
            val ink = arrayOf(Rect(1200, 1600, 0, 0), Rect(1200, 1600, 0, 0))
            for (y in 0 until page.height) {
                for (x in 0 until page.width) {
                    if (before[y * page.width + x] == after[y * page.width + x]) continue
                    val index = permitted.indexOfFirst { it.contains(x, y) }
                    assertTrue("Translation changed unverified pixel ($x,$y)", index >= 0)
                    ink[index].apply {
                        left = minOf(left, x)
                        top = minOf(top, y)
                        right = maxOf(right, x + 1)
                        bottom = maxOf(bottom, y + 1)
                    }
                }
            }
            ink.forEach {
                assertTrue("Text still occupies a single narrow glyph column", it.width() > 200)
                assertTrue("Text should form compact horizontal lines", it.height() < 240)
            }
            assertEquals(originalText, regions.map { it.translatedText })
            assertArrayEquals(before, page.pixels())
            File(output, "cleaned-input.png").outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output, "horizontal-result.png").outputStream().use {
                result.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val measurements = JSONArray()
            ink.forEachIndexed { index, bounds ->
                measurements.put(
                    JSONObject().put("balloon", index).put("inkWidth", bounds.width()).put("inkHeight", bounds.height())
                        .put(
                            "left",
                            bounds.left,
                        ).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom),
                )
            }
            File(output, "measurements.json").writeText(
                JSONObject().put("sourceRegions", 4).put("balloons", measurements)
                    .put("allChangedPixelsInsideVerifiedBands", true).put("originalRegionsUnchanged", true).toString(2),
            )
        } finally {
            rendered?.recycle()
            page.recycle()
        }
    }
}
