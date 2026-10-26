package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.joye.yakuyomi.engine.Detection
import li.joye.yakuyomi.engine.NightReadRenderer
import li.joye.yakuyomi.engine.NightReadStats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Opt-in larger allocation, no JNI/HTTP/settings. Run separately with nightReadAlphaScaled=true.
 * Stops after fake inference, before the large pure nightread allocation. Missing opt-in is SKIP,
 * never evidence of a passing scaled-input test. Peak memory/latency has not been measured.
 */
@RunWith(AndroidJUnit4::class)
class NightReadAlphaBudgetScaleTest {
    @Test
    fun whiteMatteMustPrecedeActualBudgetInterpolation() {
        assumeTrue(
            "Explicit larger allocation requires nightReadAlphaScaled=true",
            InstrumentationRegistry.getArguments().getString("nightReadAlphaScaled") == "true",
        )
        val w = 4096
        val h = 1024
        assertTrue(w.toLong() * h > NightReadRenderer.MAX_PIXELS)
        val alpha = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var reference: Bitmap? = null
        var expected: Bitmap? = null
        var actualWork: Bitmap? = null
        var textMask: Bitmap? = null
        try {
            val paint = Paint().apply { color = Color.argb(96, 8, 24, 48) }
            Canvas(alpha).drawRect(1024f, 100f, 3072f, 924f, paint)
            val rawBefore = pixels(alpha)
            val alphaFlag = alpha.hasAlpha()
            reference = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.density = alpha.density
                Canvas(it).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(alpha, 0f, 0f, null)
                }
            }
            val scale = sqrt(NightReadRenderer.MAX_PIXELS.toDouble() / (w.toLong() * h))
            val nw = floor(w * scale).toInt().coerceAtLeast(1)
            val nh = floor(h * scale).toInt().coerceAtLeast(1)
            expected = Bitmap.createScaledBitmap(reference, nw, nh, true)
            reference.recycle()
            reference = null
            val expectedPixels = pixels(checkNotNull(expected))
            val stats = NightReadStats()
            val result = NightReadRenderer.render(
                alpha,
                detect = {
                    actualWork = it
                    assertEquals(nw, it.width)
                    assertEquals(nh, it.height)
                    assertFalse(it.hasAlpha())
                    assertArrayEquals(expectedPixels, pixels(it))
                    Detection(
                        emptyList(),
                        Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888).also { mask ->
                            textMask =
                                mask
                        },
                    )
                },
                segment = {
                    assertSame(actualWork, it)
                    BooleanArray(nw * nh)
                },
                stats = stats,
                beforeRender = { false },
            )
            assertNull(result)
            assertEquals(nw to nh, stats.scaledTo)
            assertTrue(checkNotNull(actualWork).isRecycled)
            assertTrue(checkNotNull(textMask).isRecycled)
            assertFalse(alpha.isRecycled)
            assertEquals(alphaFlag, alpha.hasAlpha())
            assertArrayEquals(rawBefore, pixels(alpha))
        } finally {
            expected?.recycle()
            reference?.recycle()
            alpha.recycle()
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
