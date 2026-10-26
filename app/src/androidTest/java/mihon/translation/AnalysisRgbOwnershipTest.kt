package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.joye.yakuyomi.engine.AnalysisRgb
import li.joye.yakuyomi.engine.InternalEngineApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

/** Pure bitmap ownership/read contracts. No models, network, files, preferences or activities. */
@OptIn(InternalEngineApi::class)
@RunWith(AndroidJUnit4::class)
class AnalysisRgbOwnershipTest {
    @Test
    fun actualOpaqueArgbAndRgb565AreBorrowedWithoutChangingSourceMetadata() = withBitmaps { owned ->
        requireAudit()
        val opaque = owned.keep(
            Bitmap.createBitmap(173, 97, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(18, 91, 157))
                setHasAlpha(true) // The flag alone is not evidence of a transparent pixel.
                density = 321
            },
        )
        val rgb565 = owned.keep(checkNotNull(opaque.copy(Bitmap.Config.RGB_565, true)).apply { density = 213 })
        assertTrue(opaque.hasAlpha())
        assertFalse(rgb565.hasAlpha())
        for (source in listOf(opaque, rgb565)) {
            val before = SourceSnapshot(source)
            val lease = AnalysisRgb.acquire(source)
            try {
                assertSame("Actually opaque storage must remain borrowed", source, lease.bitmap)
            } finally {
                lease.close()
            }
            lease.close()
            assertFalse("Closing a borrowed lease must not recycle its source", source.isRecycled)
            expectFailure<IllegalStateException> { lease.bitmap }
            before.verify()
        }
    }

    @Test
    fun transparencyOnLastRowOwnsWhiteCopyAndCloseIsIdempotent() = withBitmaps { owned ->
        requireAudit()
        // Cross the bounded-reader column boundary and put the sole alpha pixel last.
        val source = owned.keep(
            Bitmap.createBitmap(8_207, 3, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(11, 61, 133))
                density = 287
                setPixel(width - 1, height - 1, 0x80FF0000.toInt())
            },
        )
        val expected = owned.keep(whiteComposite(source))
        val before = SourceSnapshot(source)
        val lease = AnalysisRgb.acquire(source)
        val analysis = lease.bitmap
        try {
            assertNotSame(source, analysis)
            assertEquals(source.density, analysis.density)
            assertFalse(analysis.hasAlpha())
            assertArrayEquals(
                "Owned analysis must use Android Canvas white-matte semantics",
                pixels(expected),
                pixels(analysis),
            )
            assertEquals(0xFFFF7F7F.toInt(), analysis.getPixel(source.width - 1, source.height - 1))
        } finally {
            lease.close()
        }
        lease.close()
        assertTrue("Only the owned white analysis bitmap is recycled", analysis.isRecycled)
        expectFailure<IllegalStateException> { lease.bitmap }
        before.verify()
    }

    @Test
    fun longOpaqueScanCanBeCancelledAndTheSourceCanStillBeAcquired() = withBitmaps { owned ->
        requireAudit()
        val source = owned.keep(
            Bitmap.createBitmap(17_011, 5, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(47, 83, 109))
                setHasAlpha(true)
                density = 179
            },
        )
        val before = SourceSnapshot(source)
        val cancelled = CancellationException("neutral scan cancellation")
        var checks = 0
        try {
            AnalysisRgb.acquire(source) {
                if (++checks >= 3) throw cancelled
            }.close()
            error("A multi-tile scan must observe caller cancellation")
        } catch (caught: CancellationException) {
            assertSame("Cancellation must propagate without wrapping", cancelled, caught)
        }
        assertTrue("Cancellation must be checked beyond the entry point", checks >= 3)
        before.verify()
        AnalysisRgb.acquire(source).use { assertSame(source, it.bitmap) }
        before.verify()
    }

    @Test
    fun transparentAcquisitionPropagatesCancellationAtEveryObservedCheckpoint() = withBitmaps { owned ->
        requireAudit()
        val source = owned.keep(
            Bitmap.createBitmap(97, 53, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0x80000000.toInt())
                density = 197
            },
        )
        val before = SourceSnapshot(source)
        var checkpoints = 0
        val successful = AnalysisRgb.acquire(source) { checkpoints++ }
        val copied = successful.bitmap
        try {
            assertNotSame(source, copied)
            assertTrue("Owned conversion must support cancellation after entry", checkpoints > 1)
        } finally {
            successful.close()
        }
        assertTrue(copied.isRecycled)
        // Enumerate actual cancellation opportunities rather than depending on a hardcoded
        // scan/allocation callback count. This includes the post-composite checkpoint.
        for (stopAt in 1..checkpoints) {
            val cancellation = CancellationException("neutral owned-conversion cancellation")
            var visits = 0
            try {
                AnalysisRgb.acquire(source) {
                    if (++visits == stopAt) throw cancellation
                }.use { error("Injected cancellation must prevent ownership transfer") }
            } catch (caught: CancellationException) {
                assertSame(cancellation, caught)
            }
            before.verify()
        }
        AnalysisRgb.acquire(source).use { assertNotSame(source, it.bitmap) }
        before.verify()
    }

    @Test
    fun tiledNonSquareReadHonorsStrideOffsetAndCanvasAlphaOracle() = withBitmaps { owned ->
        requireAudit()
        val source = owned.keep(
            Bitmap.createBitmap(8_213, 7, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(27, 63, 101))
                density = 217
                setPixel(3, 1, Color.TRANSPARENT)
                setPixel(8_194, 2, 0x80000000.toInt())
                setPixel(8_195, 3, 0x80FF0000.toInt())
                setPixel(8_203, 4, Color.TRANSPARENT)
            },
        )
        val before = SourceSnapshot(source)
        val white = owned.keep(whiteComposite(source))
        val x = 3
        val y = 1
        val width = 8_201
        val height = 4
        val offset = 11
        val stride = width + 9
        val sentinel = 0x13579BDF
        val actual = IntArray(offset + stride * height + 13) { sentinel }
        val expected = actual.copyOf()
        white.getPixels(expected, offset, stride, x, y, width, height)
        AnalysisRgb.readWhitePixels(source, actual, offset, stride, x, y, width, height)
        assertArrayEquals("Every sampled color and every unused target slot must agree", expected, actual)
        assertEquals(Color.WHITE, actual[offset])
        assertEquals(0xFF7F7F7F.toInt(), actual[offset + stride + 8_194 - x])
        assertEquals(0xFFFF7F7F.toInt(), actual[offset + 2 * stride + 8_195 - x])
        before.verify()
    }

    @Test
    fun cancelledAndRejectedReadsPreserveSourceAndNeverWriteOutsideTargetWindow() = withBitmaps { owned ->
        requireAudit()
        val source = owned.keep(
            Bitmap.createBitmap(8_207, 3, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0x80000000.toInt())
                density = 231
            },
        )
        val before = SourceSnapshot(source)
        val offset = 7
        val stride = source.width + 5
        val sentinel = 0x2468ACE
        val target = IntArray(offset + stride * source.height + 9) { sentinel }
        val cancellation = CancellationException("neutral tiled-read cancellation")
        var checks = 0
        try {
            AnalysisRgb.readWhitePixels(source, target, offset, stride, 0, 0, source.width, source.height) {
                if (++checks >= 3) throw cancellation
            }
            error("The tiled read must observe cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        for (index in target.indices) {
            val rowOffset = index - offset
            val inWindow = rowOffset >= 0 && rowOffset / stride < source.height && rowOffset % stride < source.width
            if (!inWindow) assertEquals("No stride padding or outer sentinel may be written", sentinel, target[index])
        }
        val rejected = IntArray(20) { sentinel }
        expectFailure<IllegalArgumentException> {
            AnalysisRgb.readWhitePixels(source, rejected, 0, 5, source.width - 2, 0, 5, 2)
        }
        assertTrue(rejected.all { it == sentinel })
        before.verify()
    }

    private fun requireAudit() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.yomitori.yakuyomi.audit") {
            "Analysis RGB regression requires the isolated audit package"
        }
    }

    private class SourceSnapshot(private val source: Bitmap) {
        private val before = pixels(source)
        private val encoded = png(source)
        private val config = source.config
        private val density = source.density
        private val alpha = source.hasAlpha()
        private val premultiplied = source.isPremultiplied

        fun verify() {
            assertFalse(source.isRecycled)
            assertEquals(config, source.config)
            assertEquals(density, source.density)
            assertEquals(alpha, source.hasAlpha())
            assertEquals(premultiplied, source.isPremultiplied)
            assertArrayEquals(before, pixels(source))
            assertArrayEquals(encoded, png(source))
        }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (caught: Throwable) {
            if (caught is T) return
            throw caught
        }
        error("Expected ${T::class.java.simpleName}")
    }

    private inline fun withBitmaps(block: (MutableList<Bitmap>) -> Unit) {
        val owned = mutableListOf<Bitmap>()
        try {
            block(owned)
        } finally {
            owned.asReversed().forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun MutableList<Bitmap>.keep(bitmap: Bitmap): Bitmap = bitmap.also { add(it) }

    companion object {
        private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

        private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }

        private fun whiteComposite(source: Bitmap): Bitmap =
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
                density = source.density
                Canvas(this).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(source, 0f, 0f, null)
                }
            }
    }
}
