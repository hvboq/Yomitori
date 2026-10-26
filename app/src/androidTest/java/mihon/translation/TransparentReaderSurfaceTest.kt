package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.util.system.ImageUtil
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual offscreen ReaderPageImageView draws and display transforms; no Activity, OCR or settings. */
@RunWith(AndroidJUnit4::class)
class TransparentReaderSurfaceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun pagerTransparentPngShowsWhitePaperInsideBlackMarginsWithoutChangingSource() = withSource { source, png ->
        displayBitmap(decode(png), webtoon = false) { frame, ssiv ->
            assertPaperAndInk(frame, ssiv)
            assertEquals(Color.BLACK, frame.getPixel(4, 4))
            assertEquals(Color.BLACK, frame.getPixel(4, frame.height - 5))
        }
        assertTrue(source.hasAlpha())
    }

    @Test
    fun webtoonAndMergedPagesUseTheSameWhiteImageBacking() = withSource { _, png ->
        displayBitmap(decode(png), webtoon = true) { frame, ssiv ->
            assertPaperAndInk(frame, ssiv)
            assertEquals(Color.BLACK, frame.getPixel(4, 4))
        }
        val merged = Buffer().write(png).use { left ->
            Buffer().write(png).use { right -> ImageUtil.mergeHorizontally(left, right) }
        }
        assertTrue("Merge keeps its alpha; the view supplies paper", merged.hasAlpha())
        displayBitmap(merged, webtoon = true) { frame, ssiv ->
            assertPaperAndInk(frame, ssiv)
            assertPaperAndInk(frame, ssiv, offsetX = SOURCE_WIDTH.toFloat())
            assertEquals(Color.BLACK, frame.getPixel(4, 4))
        }
    }

    @Test
    fun animatedPagerAndWebtoonPaperFollowsDrawableBoundsAndKeepsAnimation() {
        requireAudit()
        for (webtoon in listOf(false, true)) {
            val drawable = MovingInk()
            val mounted = main {
                Mounted(webtoon).also { it.reader.setImage(drawable, config) }
            }
            try {
                val deadline = SystemClock.uptimeMillis() + 10_000
                var ready = false
                while (SystemClock.uptimeMillis() < deadline && !ready) {
                    main {
                        mounted.layout()
                        mounted.frame().recycle()
                        mounted.error?.let { throw AssertionError("Animated image load failed", it) }
                        ready = mounted.loaded
                    }
                    if (!ready) SystemClock.sleep(10)
                }
                assertTrue("The real Coil animated target must complete", ready)
                main {
                    assertTrue("The original drawable must be started", drawable.isRunning())
                    val image = mounted.reader.getChildAt(0) as AppCompatImageView
                    assertEquals(SOURCE_WIDTH, image.drawable.intrinsicWidth)
                    assertEquals(SOURCE_HEIGHT, image.drawable.intrinsicHeight)
                    assertTrue("The backing preserves the animated drawable contract", image.drawable is Animatable)
                    image.drawable.setVisible(false, false)
                    assertFalse("Layer visibility is forwarded to the animation", drawable.isVisible)
                    image.drawable.setVisible(true, false)
                    assertTrue(drawable.isVisible)
                    (image.drawable as Animatable).stop()
                    assertFalse(drawable.isRunning())
                    (image.drawable as Animatable).start()
                    assertTrue(drawable.isRunning())
                    mounted.frame().useBitmap { frame ->
                        assertEquals(Color.BLACK, frame.getPixel(4, 4))
                        assertEquals(Color.WHITE, frame.getPixel(120, 95))
                        assertEquals(Color.BLACK, frame.getPixel(56, 120))
                    }
                    drawable.secondFrame = true
                    mounted.frame().useBitmap { frame ->
                        assertEquals("Subsequent drawable frames remain visible", Color.BLUE, frame.getPixel(56, 120))
                        assertEquals(Color.WHITE, frame.getPixel(120, 95))
                        assertEquals(Color.BLACK, frame.getPixel(4, 4))
                    }
                    if (webtoon) {
                        mounted.reader.setImage(BitmapDrawable(context.resources, fixture(opaque = false)), config)
                        assertFalse(
                            "Replacing animated content with static content stops its original animation",
                            drawable.isRunning(),
                        )
                    }
                }
            } finally {
                main {
                    try {
                        mounted.reader.recycle()
                        assertFalse(
                            "Recycle stops the original animation through its paper wrapper",
                            drawable.isRunning(),
                        )
                    } finally {
                        drawable.stop()
                        mounted.parent.removeAllViews()
                    }
                }
            }
        }
    }

    @Test
    fun splitMergeAndRotationCompositeTransparentPixelsBeforeJpeg() = withSource { source, png ->
        for (side in ImageUtil.Side.entries) {
            val actualHalf = transform(png) { ImageUtil.splitInHalf(it, side) }
            val expectedHalf = legacyTransform(source, side = side, merge = false, whitePaper = true)
            assertJpegPixels(expectedHalf, actualHalf)
            val actualStack = transform(png) { ImageUtil.splitAndMerge(it, side) }
            val expectedStack = legacyTransform(source, side = side, merge = true, whitePaper = true)
            assertJpegPixels(expectedStack, actualStack)
        }
        for (degrees in listOf(0F, 90F, 45F)) {
            val actual = transform(png) { ImageUtil.rotateImage(it, degrees) }
            val expected = legacyTransform(source, degrees = degrees, whitePaper = true)
            assertJpegPixels(expected, actual)
            decode(actual).useBitmap { result ->
                assertTrue("JPEG cannot retain alpha", !result.hasAlpha())
                assertTrue("White paper is not an all-black failed decode", Color.red(result.getPixel(2, 2)) >= 245)
            }
        }
    }

    @Test
    fun opaqueTransformControlsRetainExistingJpegGeometryAndColors() {
        requireAudit()
        val source = fixture(opaque = true)
        try {
            val png = png(source)
            for (side in ImageUtil.Side.entries) {
                assertArrayEquals(
                    legacyTransform(source, side = side, merge = false, whitePaper = false),
                    transform(png) { ImageUtil.splitInHalf(it, side) },
                )
                assertArrayEquals(
                    legacyTransform(source, side = side, merge = true, whitePaper = false),
                    transform(png) { ImageUtil.splitAndMerge(it, side) },
                )
            }
            for (degrees in listOf(0F, 90F)) {
                assertArrayEquals(
                    legacyTransform(source, degrees = degrees, whitePaper = false),
                    transform(png) { ImageUtil.rotateImage(it, degrees) },
                )
            }
        } finally {
            source.recycle()
        }
    }

    private fun withSource(block: (Bitmap, ByteArray) -> Unit) {
        requireAudit()
        val source = fixture(opaque = false)
        try {
            val bytes = png(source)
            val originalBytes = bytes.copyOf()
            val originalPixels = pixels(source)
            val density = source.density
            val premultiplied = source.isPremultiplied
            block(source, bytes)
            assertFalse(source.isRecycled)
            assertArrayEquals(originalBytes, bytes)
            assertArrayEquals(originalBytes, png(source))
            assertArrayEquals(originalPixels, pixels(source))
            assertEquals(density, source.density)
            assertEquals(premultiplied, source.isPremultiplied)
            assertTrue(source.hasAlpha())
        } finally {
            source.recycle()
        }
    }

    private fun fixture(opaque: Boolean): Bitmap = Bitmap.createBitmap(
        SOURCE_WIDTH,
        SOURCE_HEIGHT,
        Bitmap.Config.ARGB_8888,
    ).apply {
        density = Bitmap.DENSITY_NONE
        eraseColor(if (opaque) Color.rgb(32, 95, 157) else Color.TRANSPARENT)
        Canvas(this).apply {
            drawRect(16F, 24F, 40F, 56F, Paint().apply { color = Color.BLACK })
            drawRect(72F, 24F, 104F, 56F, Paint().apply { color = 0x80FF0000.toInt() })
        }
        if (opaque) setHasAlpha(false)
    }

    /** BitmapDrawable is the actual shared merged/Coil result entry into Reader SSIV. */
    private fun displayBitmap(bitmap: Bitmap, webtoon: Boolean, check: (Bitmap, SubsamplingScaleImageView) -> Unit) {
        val mounted = main {
            Mounted(webtoon).also {
                bitmap.density = Bitmap.DENSITY_NONE
                it.reader.setImage(BitmapDrawable(context.resources, bitmap), config)
            }
        }
        try {
            main {
                mounted.layout()
                // SSIV initializes its real scale/translation during draw, including onReady.
                mounted.frame().recycle()
                mounted.error?.let { throw AssertionError("SSIV image load failed", it) }
                val ssiv = mounted.reader.getChildAt(0) as SubsamplingScaleImageView
                assertTrue("Actual SSIV must be ready, not pendingRestore", ssiv.isReady)
                assertTrue(ssiv.isImageLoaded)
                assertTrue(ssiv.scale > 0)
                mounted.frame().useBitmap { check(it, ssiv) }
            }
        } finally {
            main {
                mounted.reader.recycle()
                mounted.parent.removeAllViews()
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun assertPaperAndInk(frame: Bitmap, ssiv: SubsamplingScaleImageView, offsetX: Float = 0F) {
        fun color(x: Float, y: Float): Int {
            val point = checkNotNull(ssiv.sourceToViewCoord(offsetX + x, y))
            return frame.getPixel(point.x.roundToInt(), point.y.roundToInt())
        }
        assertEquals("Transparent paper is white inside image bounds", Color.WHITE, color(60F, 8F))
        assertEquals("Opaque black ink remains visible", Color.BLACK, color(28F, 40F))
        val red = color(88F, 40F)
        assertEquals(255, Color.red(red))
        assertTrue(abs(Color.green(red) - 127) <= 1 && abs(Color.blue(red) - 127) <= 1)
    }

    private inner class Mounted(webtoon: Boolean) {
        private val themed = ContextThemeWrapper(context, eu.kanade.tachiyomi.R.style.Theme_Tachiyomi)
        val parent = FrameLayout(themed).apply { setBackgroundColor(Color.BLACK) }
        val reader = ReaderPageImageView(themed, isWebtoon = webtoon)
        var loaded = false
        var error: Throwable? = null

        init {
            reader.onImageLoaded = { loaded = true }
            reader.onImageLoadError = { error = it ?: AssertionError("Image error without cause") }
            parent.addView(reader, FrameLayout.LayoutParams(SURFACE_SIZE, SURFACE_SIZE))
            layout()
        }

        fun layout() {
            val spec = View.MeasureSpec.makeMeasureSpec(SURFACE_SIZE, View.MeasureSpec.EXACTLY)
            parent.measure(spec, spec)
            parent.layout(0, 0, SURFACE_SIZE, SURFACE_SIZE)
        }

        fun frame(): Bitmap = Bitmap.createBitmap(SURFACE_SIZE, SURFACE_SIZE, Bitmap.Config.ARGB_8888).also {
            parent.draw(Canvas(it))
        }
    }

    private class MovingInk : Drawable(), Animatable {
        private var running = false
        var secondFrame = false
        override fun getIntrinsicWidth() = SOURCE_WIDTH
        override fun getIntrinsicHeight() = SOURCE_HEIGHT
        override fun draw(canvas: Canvas) {
            val checkpoint = canvas.save()
            try {
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width().toFloat() / SOURCE_WIDTH, bounds.height().toFloat() / SOURCE_HEIGHT)
                canvas.drawRect(
                    16F,
                    24F,
                    40F,
                    56F,
                    Paint().apply {
                        color = if (secondFrame) Color.BLUE else Color.BLACK
                    },
                )
            } finally {
                canvas.restoreToCount(checkpoint)
            }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun start() {
            running = true
        }
        override fun stop() {
            running = false
        }
        override fun isRunning() = running
    }

    private fun legacyTransform(
        source: Bitmap,
        side: ImageUtil.Side = ImageUtil.Side.RIGHT,
        merge: Boolean = false,
        degrees: Float? = null,
        whitePaper: Boolean,
    ): ByteArray {
        val transformed = if (degrees != null) {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees) }, true)
        } else {
            Bitmap.createBitmap(source.width / 2, source.height * if (merge) 2 else 1, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).apply {
                    val firstLeft = if (side == ImageUtil.Side.RIGHT) source.width - source.width / 2 else 0
                    drawBitmap(
                        source,
                        Rect(firstLeft, 0, firstLeft + source.width / 2, source.height),
                        Rect(0, 0, width, source.height),
                        null,
                    )
                    if (merge) {
                        val nextLeft = if (side == ImageUtil.Side.LEFT) source.width - source.width / 2 else 0
                        drawBitmap(
                            source,
                            Rect(nextLeft, 0, nextLeft + source.width / 2, source.height),
                            Rect(0, source.height, width, height),
                            null,
                        )
                    }
                }
            }
        }
        try {
            val output = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(output).apply {
                    if (whitePaper) drawColor(Color.WHITE)
                    drawBitmap(transformed, 0F, 0F, null)
                }
                return ByteArrayOutputStream().use {
                    check(output.compress(Bitmap.CompressFormat.JPEG, 100, it))
                    it.toByteArray()
                }
            } finally {
                output.recycle()
            }
        } finally {
            if (transformed !== source) transformed.recycle()
        }
    }

    private fun transform(bytes: ByteArray, operation: (BufferedSource) -> BufferedSource): ByteArray =
        Buffer().write(bytes).use { input -> operation(input).use { it.readByteArray() } }

    private fun assertJpegPixels(expected: ByteArray, actual: ByteArray) {
        decode(expected).useBitmap { left ->
            decode(actual).useBitmap { right ->
                assertEquals(left.width, right.width)
                assertEquals(left.height, right.height)
                assertArrayEquals(pixels(left), pixels(right))
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        it.toByteArray()
    }
    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try {
        block(this)
    } finally {
        recycle()
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun requireAudit() {
        assertEquals(
            "Image fixtures must target the isolated audit app",
            "app.yomitori.yakuyomi.audit",
            context.packageName,
        )
    }

    companion object {
        private const val SOURCE_WIDTH = 120
        private const val SOURCE_HEIGHT = 80
        private const val SURFACE_SIZE = 240
        private val config = ReaderPageImageView.Config(zoomDuration = 1, landscapeZoom = false)
    }
}
