@file:OptIn(li.joye.yakuyomi.engine.InternalEngineApi::class)

package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import li.joye.yakuyomi.engine.AnalysisRgb
import li.joye.yakuyomi.engine.CsegPost
import li.joye.yakuyomi.engine.Detection
import li.joye.yakuyomi.engine.NightReadRenderer
import li.joye.yakuyomi.engine.YoloSegPost
import li.joye.yakuyomi.nightread.NightReadInput
import li.joye.yakuyomi.nightread.NightReadParams
import li.joye.yakuyomi.nightread.NightTier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Android Bitmap/tensor/night-render synthetic regression; no models, JNI, HTTP or settings.
 * Reflects the actual internal production tensor preparation to avoid public test-only engine API.
 * This is not proof of CSEG/YOLO inference quality, translation quality or real reader behavior.
 */
@RunWith(AndroidJUnit4::class)
class NightReadAlphaInputTest {
    @Test
    fun actualToInputKeepsTransparentBlackMaskPixelsOff() {
        PairFixture().use { fixture ->
            val mask = sparseMask()
            val before = Snapshot(mask)
            try {
                AnalysisRgb.acquire(fixture.alpha).use { input ->
                    val method = NightReadRenderer::class.java.declaredMethods.single {
                        it.name == "toInput" && it.parameterTypes.size == 5 &&
                            it.parameterTypes[0] == AnalysisRgb::class.java
                    }.apply { isAccessible = true }
                    val value = method.invoke(
                        NightReadRenderer,
                        input,
                        Detection(emptyList(), mask),
                        BooleanArray(W * H),
                        IntArray(W * H),
                        mask,
                    ) as NightReadInput
                    val expectedMask = BooleanArray(W * H).apply { this[24 * W + 20] = true }
                    assertArrayEquals(expectedMask, value.seg.data)
                    assertArrayEquals(expectedMask, checkNotNull(value.inpaintMask).data)
                    assertEquals(255, value.gray.data[0])
                    assertEquals(0, checkNotNull(value.chroma).data[0])
                }
                before.assertPreserved(mask)
                fixture.assertSourcesPreserved()
            } finally {
                mask.recycle()
            }
        }
    }

    @Test
    fun actualCsegAndYoloTensorBoundariesEqualCanvasWhiteReference() {
        PairFixture().use { fixture ->
            val opaqueCseg = fixture.opaque.tensor("csegPreprocessAnalysis") as CsegPost.Pre
            val alphaCseg = fixture.alpha.tensor("csegPreprocessAnalysis") as CsegPost.Pre
            assertEquals(opaqueCseg.nw, alphaCseg.nw)
            assertEquals(opaqueCseg.nh, alphaCseg.nh)
            assertArrayEquals(opaqueCseg.chw, alphaCseg.chw, 0f)

            val opaqueYolo = fixture.opaque.tensor("yoloPreprocessAnalysis") as YoloSegPost.Pre
            val alphaYolo = fixture.alpha.tensor("yoloPreprocessAnalysis") as YoloSegPost.Pre
            assertEquals(opaqueYolo.nw, alphaYolo.nw)
            assertEquals(opaqueYolo.nh, alphaYolo.nh)
            assertEquals(opaqueYolo.top, alphaYolo.top)
            assertEquals(opaqueYolo.left, alphaYolo.left)
            assertArrayEquals(opaqueYolo.chw, alphaYolo.chw, 0f)

            // A failing negative control catches a vacuous fixture: the original raw RGB path is
            // observably different. Alpha=0 in a packed IntArray alone is not a transparency API.
            assertFalse(CsegPost.preprocess(fixture.alpha.pixels(), W, H).chw.contentEquals(opaqueCseg.chw))
            assertFalse(YoloSegPost.preprocess(fixture.alpha.pixels(), W, H).chw.contentEquals(opaqueYolo.chw))
            fixture.assertSourcesPreserved()
        }
    }

    @Test
    fun callbackRenderSharesOneOpaqueViewAndMatchesStandaloneAndCanvasReference() {
        PairFixture().use { fixture ->
            val mask = sparseMask()
            val maskBefore = Snapshot(mask)
            try {
                fun render(page: Bitmap): Bitmap {
                    var detectedPage: Bitmap? = null
                    var detectedMask: Bitmap? = null
                    val output = NightReadRenderer.render(
                        page,
                        detect = { input ->
                            assertArrayEquals(fixture.opaque.pixels(), input.pixels())
                            detectedPage = input
                            Detection(emptyList(), sparseMask().also { detectedMask = it })
                        },
                        segment = { input ->
                            assertSame(detectedPage, input)
                            BooleanArray(input.width * input.height)
                        },
                        inpaintMask = mask,
                    )
                    assertTrue(checkNotNull(detectedMask).isRecycled)
                    if (page === fixture.alpha) assertTrue(checkNotNull(detectedPage).isRecycled)
                    if (page === fixture.opaque) {
                        assertSame(page, detectedPage)
                        assertFalse(page.isRecycled)
                    }
                    return checkNotNull(output)
                }

                val alphaOut = render(fixture.alpha)
                val opaqueOut = render(fixture.opaque)
                val standaloneMask = sparseMask()
                try {
                    val standalone = NightReadRenderer.render(
                        fixture.alpha,
                        Detection(emptyList(), standaloneMask),
                        BooleanArray(W * H),
                        inpaintMask = mask,
                    )
                    try {
                        assertArrayEquals(opaqueOut.pixels(), alphaOut.pixels())
                        assertArrayEquals(opaqueOut.pixels(), standalone.pixels())
                        assertTrue(alphaOut.pixels().all { (it ushr 24) == 255 })
                        assertFalse(standaloneMask.isRecycled)
                    } finally {
                        standalone.recycle()
                    }
                } finally {
                    standaloneMask.recycle()
                    alphaOut.recycle()
                    opaqueOut.recycle()
                }
                maskBefore.assertPreserved(mask)
                fixture.assertSourcesPreserved()
            } finally {
                mask.recycle()
            }
        }
    }

    @Test
    fun renderTiersPairedOutputsAndSynchronousSinkOwnershipMatch() {
        PairFixture().use { fixture ->
            fun tiers(page: Bitmap): List<IntArray?> {
                val values = ArrayList<IntArray?>()
                val delivered = ArrayList<Bitmap>()
                val rendered = NightReadRenderer.renderTiers(
                    page,
                    detect = { Detection(emptyList(), sparseMask()) },
                    segment = { BooleanArray(it.width * it.height) },
                    base = NightReadParams(),
                    tiers = listOf(NightTier.L2, NightTier.L3),
                ) { _, bitmap ->
                    bitmap?.let {
                        assertFalse(it.isRecycled)
                        delivered += it
                    }
                    values += bitmap?.pixels()
                }
                assertTrue(rendered)
                assertEquals(2, values.size)
                assertNotNull(values.first())
                assertTrue(delivered.all { it.isRecycled })
                return values
            }
            val reference = tiers(fixture.opaque)
            val actual = tiers(fixture.alpha)
            reference.zip(actual).forEach { (expected, observed) ->
                if (expected == null) assertNull(observed) else assertArrayEquals(expected, observed)
            }
            fixture.assertSourcesPreserved()
        }
    }

    @Test
    fun beforeRenderFalseAndSegmentFailureReleaseOnlyOwnedResources() {
        PairFixture().use { fixture ->
            for (throwInSegment in listOf(false, true)) {
                var analysis: Bitmap? = null
                var detectedMask: Bitmap? = null
                var beforeRenderCalls = 0
                val sentinel = IllegalStateException("synthetic-segment-failure")
                try {
                    val result = NightReadRenderer.render(
                        fixture.alpha,
                        detect = {
                            analysis = it
                            Detection(emptyList(), sparseMask().also { mask -> detectedMask = mask })
                        },
                        segment = {
                            assertSame(analysis, it)
                            if (throwInSegment) throw sentinel
                            BooleanArray(it.width * it.height)
                        },
                        beforeRender = {
                            beforeRenderCalls++
                            false
                        },
                    )
                    assertFalse(throwInSegment)
                    assertNull(result)
                } catch (failure: IllegalStateException) {
                    assertTrue(throwInSegment)
                    assertSame(sentinel, failure)
                }
                assertEquals(if (throwInSegment) 0 else 1, beforeRenderCalls)
                assertTrue(checkNotNull(analysis).isRecycled)
                assertTrue(checkNotNull(detectedMask).isRecycled)
                fixture.assertSourcesPreserved()
            }
        }
    }

    @Test
    fun opaqueAlphaMetadataAndRgb565ControlsBorrowWithoutChangingPixels() {
        PairFixture().use { fixture ->
            assertTrue(fixture.opaque.hasAlpha())
            AnalysisRgb.acquire(fixture.opaque).use { assertSame(fixture.opaque, it.bitmap) }
            val rgb565 = checkNotNull(fixture.opaque.copy(Bitmap.Config.RGB_565, false))
            val before = Snapshot(rgb565)
            try {
                assertFalse(rgb565.hasAlpha())
                AnalysisRgb.acquire(rgb565).use { assertSame(rgb565, it.bitmap) }
                val cseg = rgb565.tensor("csegPreprocessAnalysis") as CsegPost.Pre
                assertArrayEquals(CsegPost.preprocess(rgb565.pixels(), W, H).chw, cseg.chw, 0f)
                val yolo = rgb565.tensor("yoloPreprocessAnalysis") as YoloSegPost.Pre
                assertArrayEquals(YoloSegPost.preprocess(rgb565.pixels(), W, H).chw, yolo.chw, 0f)
                before.assertPreserved(rgb565)
                fixture.assertSourcesPreserved()
            } finally {
                rgb565.recycle()
            }
        }
    }

    private fun Bitmap.tensor(name: String): Any = AnalysisRgb.acquire(this).use { input ->
        val methods = Class.forName("li.joye.yakuyomi.engine.CharSegKt").declaredMethods.filter {
            it.name.substringBefore('$') == name && it.parameterTypes.contentEquals(arrayOf(AnalysisRgb::class.java))
        }
        assertEquals("Exactly one real internal tensor helper must be selected", 1, methods.size)
        methods.single().apply { isAccessible = true }.invoke(null, input)
    }

    private class Snapshot(bitmap: Bitmap) {
        private val pixels = bitmap.pixels()
        private val png = bitmap.png()
        private val alpha = bitmap.hasAlpha()
        private val density = bitmap.density
        private val config = bitmap.config

        fun assertPreserved(bitmap: Bitmap) {
            assertFalse(bitmap.isRecycled)
            assertEquals(alpha, bitmap.hasAlpha())
            assertEquals(density, bitmap.density)
            assertEquals(config, bitmap.config)
            assertArrayEquals(pixels, bitmap.pixels())
            assertArrayEquals(png, bitmap.png())
        }
    }

    private class PairFixture : AutoCloseable {
        val alpha = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply {
            density = 240
            val paint = Paint().apply { isAntiAlias = false }
            Canvas(this).apply {
                drawColor(Color.TRANSPARENT)
                paint.color = Color.argb(128, 16, 80, 144)
                drawRect(8f, 8f, 88f, 120f, paint)
                paint.color = Color.BLACK
                drawRect(16f, 24f, 32f, 80f, paint)
                paint.color = Color.WHITE
                drawRect(48f, 24f, 80f, 104f, paint)
            }
        }
        val opaque = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply {
            density = alpha.density
            Canvas(this).apply {
                drawColor(Color.WHITE)
                drawBitmap(alpha, 0f, 0f, null)
            }
            // Keep hasAlpha=true intentionally: an opaque PNG must be borrowed, not mutated.
            setHasAlpha(true)
        }
        private val alphaBefore = Snapshot(alpha)
        private val opaqueBefore = Snapshot(opaque)

        fun assertSourcesPreserved() {
            alphaBefore.assertPreserved(alpha)
            opaqueBefore.assertPreserved(opaque)
        }

        override fun close() {
            alpha.recycle()
            opaque.recycle()
        }
    }

    companion object {
        private const val W = 96
        private const val H = 128

        private fun Bitmap.pixels(): IntArray = IntArray(width * height).also {
            getPixels(it, 0, width, 0, 0, width, height)
        }

        private fun Bitmap.png(): ByteArray = ByteArrayOutputStream().use {
            assertTrue(compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }

        private fun sparseMask(): Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply {
            // This transparent black mask is intentionally NOT matted. Its blue channel is false.
            setPixel(20, 24, Color.WHITE)
        }
    }
}
