package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.DetectorInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Physical-pixel Detector input contract. No model constructor, JNI, network or preference write. */
@RunWith(AndroidJUnit4::class)
class DetectorDensityInputTest {
    @Test
    fun opaquePixelsAndBlackPaddingDoNotDependOnBitmapDensity() = withBitmaps { owned, guards ->
        requireAudit()
        val densityProbe = owned.keep(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        val runtimeDefaultDensity = densityProbe.density
        val original = owned.keep(asymmetricOpaque().apply { density = Bitmap.DENSITY_NONE })
        guards += Snapshot(original)
        val cases = densityCases(runtimeDefaultDensity).map { (label, density) ->
            val source = owned.keep(
                checkNotNull(original.copy(Bitmap.Config.ARGB_8888, true)).apply {
                    this.density = density
                    setHasAlpha(true) // Actual pixels remain opaque; this also exercises borrowed analysis.
                },
            )
            guards += Snapshot(source)
            DensityInput(label, density, source, source)
        }
        verifyInputs("opaque", runtimeDefaultDensity, cases, original, owned)
    }

    @Test
    fun transparentAndHalfAlphaPixelsMatchWhiteMatteAcrossBitmapDensities() = withBitmaps { owned, guards ->
        requireAudit()
        val densityProbe = owned.keep(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        val runtimeDefaultDensity = densityProbe.density
        val original = owned.keep(
            asymmetricOpaque().apply {
                density = Bitmap.DENSITY_NONE
                setPixel(11, 7, Color.TRANSPARENT)
                setPixel(84, 52, 0x80000000.toInt())
                setPixel(283, 72, 0x80FF0000.toInt())
                setPixel(319, 82, Color.TRANSPARENT)
            },
        )
        guards += Snapshot(original)
        val referenceMatte = owned.keep(whiteMatte(original))
        guards += Snapshot(referenceMatte)
        assertEquals(Color.WHITE, referenceMatte.getPixel(11, 7))
        assertEquals(0xFF7F7F7F.toInt(), referenceMatte.getPixel(84, 52))
        assertEquals(0xFFFF7F7F.toInt(), referenceMatte.getPixel(283, 72))
        val cases = densityCases(runtimeDefaultDensity).map { (label, density) ->
            val source = owned.keep(
                checkNotNull(original.copy(Bitmap.Config.ARGB_8888, true)).apply {
                    this.density =
                        density
                },
            )
            val matte = owned.keep(whiteMatte(source))
            guards += Snapshot(source)
            guards += Snapshot(matte)
            assertArrayEquals(
                "Independent physical-pixel white matte must not vary with metadata",
                pixels(referenceMatte),
                pixels(matte),
            )
            DensityInput(label, density, source, matte)
        }
        verifyInputs("transparent-white-matte", runtimeDefaultDensity, cases, referenceMatte, owned)
    }

    private fun verifyInputs(
        stage: String,
        runtimeDefaultDensity: Int,
        cases: List<DensityInput>,
        referencePixels: Bitmap,
        owned: MutableList<Bitmap>,
    ) {
        val observations = JSONArray()
        val checks = mutableListOf<Check>()
        for (shape in SHAPES) {
            // Read Android's explicitly resized pixels directly, without another density-aware
            // Canvas placement. The no-resize case must retain the original Bitmap instance.
            val scaledReference = Bitmap.createScaledBitmap(referencePixels, shape.validW, shape.validH, true)
            if (scaledReference !== referencePixels) owned.keep(scaledReference)
            val oracle = oracleChw(pixels(scaledReference), shape)
            val reference = Detector.preprocess(cases.first().source, size = shape.size)
            for (case in cases) {
                val actual = Detector.preprocess(case.source, size = shape.size)
                val shapeMatches = actual.w == shape.inputW && actual.h == shape.inputH &&
                    actual.ratio.toRawBits() == shape.ratio.toRawBits() && actual.chw.size == oracle.size
                val pixelDifferences = if (shapeMatches) differences(oracle, actual.chw) else -1
                val densityDifferences = tensorDifferences(reference, actual)
                observations.put(
                    JSONObject().put("densityRole", case.label).put("sourceDensity", case.density)
                        .put("matteDensity", case.matte.density).put("requestedSize", shape.size)
                        .put("inputWidth", actual.w).put("inputHeight", actual.h)
                        .put("ratioBits", actual.ratio.toRawBits()).put("shapeMatches", shapeMatches)
                        .put("physicalPixelAndBlackPaddingDifferences", pixelDifferences)
                        .put("densityZeroTensorDifferences", densityDifferences),
                )
                checks +=
                    Check(case.label, case.density, shape.size, shapeMatches, pixelDifferences, densityDifferences)
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString(
                    "detectorDensityInputEvidence",
                    JSONObject().put("stage", stage).put("runtimeCanvasDefaultDensity", runtimeDefaultDensity)
                        .put("sourceWidth", SOURCE_W).put("sourceHeight", SOURCE_H)
                        .put("modelConstructorsByTest", 0).put("cases", observations).toString(),
                )
            },
        )
        for (check in checks) {
            val label = "${check.label} density=${check.density} size=${check.size}"
            assertEquals("Physical-pixel dimensions and ratio: $label", true, check.shapeMatches)
            assertEquals("Physical-pixel RGB values and exact black padding: $label", 0, check.pixelDifferences)
            assertEquals(
                "Same pixels must have exactly the same tensor across densities: $label",
                0,
                check.densityDifferences,
            )
        }
    }

    private fun requireAudit() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.yomitori.yakuyomi.audit")
    }

    private data class DensityInput(val label: String, val density: Int, val source: Bitmap, val matte: Bitmap)
    private data class Shape(
        val size: Int,
        val validW: Int,
        val validH: Int,
        val inputW: Int,
        val inputH: Int,
        val ratio: Float,
    )
    private data class Check(
        val label: String,
        val density: Int,
        val size: Int,
        val shapeMatches: Boolean,
        val pixelDifferences: Int,
        val densityDifferences: Int,
    )

    private class Snapshot(private val source: Bitmap) {
        private val before = pixels(source)
        private val encoded = png(source)
        private val width = source.width
        private val height = source.height
        private val config = source.config
        private val density = source.density
        private val alpha = source.hasAlpha()
        private val premultiplied = source.isPremultiplied

        fun verify() {
            assertFalse("Preprocessing may not recycle a caller-owned bitmap", source.isRecycled)
            assertEquals(width, source.width)
            assertEquals(height, source.height)
            assertEquals(config, source.config)
            assertEquals(density, source.density)
            assertEquals(alpha, source.hasAlpha())
            assertEquals(premultiplied, source.isPremultiplied)
            assertArrayEquals(before, pixels(source))
            assertArrayEquals(encoded, png(source))
        }
    }

    private inline fun withBitmaps(block: (MutableList<Bitmap>, MutableList<Snapshot>) -> Unit) {
        val owned = mutableListOf<Bitmap>()
        val guards = mutableListOf<Snapshot>()
        var failure: Throwable? = null
        try {
            block(owned, guards)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            guards.forEach { guard ->
                try {
                    guard.verify()
                } catch (caught: Throwable) {
                    cleanupErrors += caught
                }
            }
            owned.asReversed().forEach { if (!it.isRecycled) it.recycle() }
            val primary = failure
            if (primary != null) {
                cleanupErrors.forEach(primary::addSuppressed)
            } else if (cleanupErrors.isNotEmpty()) {
                cleanupErrors.drop(1).forEach(cleanupErrors[0]::addSuppressed)
                throw cleanupErrors[0]
            }
        }
    }

    private fun MutableList<Bitmap>.keep(bitmap: Bitmap): Bitmap = bitmap.also { add(it) }

    companion object {
        private const val SOURCE_W = 320
        private const val SOURCE_H = 83
        private val SHAPES = listOf(
            Shape(size = 320, validW = 320, validH = 83, inputW = 512, inputH = 256, ratio = 1f),
            Shape(size = 160, validW = 160, validH = 42, inputW = 256, inputH = 256, ratio = 0.5f),
        )

        private fun densityCases(runtimeDefault: Int) = listOf(
            "none" to Bitmap.DENSITY_NONE,
            "mdpi" to 160,
            "custom" to 241,
            "runtime-default" to runtimeDefault,
        )

        private fun asymmetricOpaque(): Bitmap = Bitmap.createBitmap(
            SOURCE_W,
            SOURCE_H,
            Bitmap.Config.ARGB_8888,
        ).apply {
            eraseColor(Color.WHITE)
            val samples = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                when {
                    x in 23..47 && y in 11..28 -> Color.rgb(19, 83, 157)
                    x in 213..231 && y in 45..69 -> Color.rgb(227, 41, 73)
                    x == 309 && y in 2..17 -> Color.BLACK
                    x == 3 && y == 79 -> Color.BLUE
                    else -> Color.WHITE
                }
            }
            setPixels(samples, 0, width, 0, 0, width, height)
        }

        private fun whiteMatte(source: Bitmap): Bitmap =
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
                density = source.density
                Canvas(this).apply {
                    drawColor(Color.WHITE)
                    // Explicit source/destination pixel rectangles independently exclude density scaling.
                    drawBitmap(source, null, Rect(0, 0, source.width, source.height), null)
                }
            }

        private fun oracleChw(validPixels: IntArray, shape: Shape): FloatArray {
            val area = shape.inputW * shape.inputH
            val expected = FloatArray(3 * area) { -1f }
            for (y in 0 until shape.validH) {
                for (x in 0 until shape.validW) {
                    val color = validPixels[y * shape.validW + x]
                    val target = y * shape.inputW + x
                    expected[target] = ((color ushr 16 and 255) / 127.5f) - 1f
                    expected[area + target] = ((color ushr 8 and 255) / 127.5f) - 1f
                    expected[2 * area + target] = ((color and 255) / 127.5f) - 1f
                }
            }
            return expected
        }

        private fun tensorDifferences(a: DetectorInput, b: DetectorInput): Int =
            if (a.w != b.w || a.h != b.h ||
                a.ratio.toRawBits() != b.ratio.toRawBits()
            ) {
                -1
            } else {
                differences(a.chw, b.chw)
            }

        private fun differences(a: FloatArray, b: FloatArray): Int =
            if (a.size != b.size) -1 else a.indices.count { a[it].toRawBits() != b[it].toRawBits() }

        private fun pixels(source: Bitmap): IntArray = IntArray(source.width * source.height).also {
            source.getPixels(it, 0, source.width, 0, 0, source.width, source.height)
        }

        private fun png(source: Bitmap): ByteArray = ByteArrayOutputStream().use {
            check(source.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
    }
}
