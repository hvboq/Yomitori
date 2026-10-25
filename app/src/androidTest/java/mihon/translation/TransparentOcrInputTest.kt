package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.DetectorInput
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/** Analysis-only white-matte contract. Source bitmaps and encoded PNGs must remain unchanged. */
@RunWith(AndroidJUnit4::class)
class TransparentOcrInputTest {
    /** No model constructor, native forward, network, files, activity or preference write. */
    @Test
    fun transparentGlyphsMatchWhiteCompositedDetectorInput() = bitmaps { owned ->
        auditContext()
        val opaque = owned.addBitmap(glyphPage())
        val transparent = owned.addBitmap(alphaCoverageOf(opaque))
        val composed = owned.addBitmap(whiteComposite(transparent))
        assertArrayEquals(
            "Independent white composite must reconstruct every neutral source pixel",
            pixels(opaque),
            pixels(composed),
        )
        val guards = listOf(opaque, transparent, composed).map(::SourceGuard)
        val expected = Detector.preprocess(opaque, size = 1000)
        val actual = Detector.preprocess(transparent, size = 1000)
        val mismatch = tensorDifferences(expected, actual)
        guards.forEach { it.verify() }
        evidence(
            "detector-transparent-glyphs",
            JSONObject().put("tensorDifferences", mismatch)
                .put("sourceAndPngBytesPreserved", true).put("modelConstructorsByTest", 0),
        )
        assertEquals("Transparent ink must be analyzed as its white-composited equivalent", 0, mismatch)
    }

    /** Encoded alpha-zero hidden RGB and alpha128 black/red are independent analytic controls. */
    @Test
    fun alphaZeroHiddenRgbAndHalfAlphaColorsUseWhiteMatte() = bitmaps { owned ->
        auditContext()
        val rgba = intArrayOf(
            0x00115B8F,
            0x80000000.toInt(),
            0x80FF0000.toInt(),
            0xFF0A141E.toInt(),
            Color.WHITE,
            Color.BLACK,
        )
        val otherHiddenRgb = rgba.copyOf().apply { this[0] = 0x00FD0BDD }
        val encoded = rgbaPng(3, 2, rgba)
        val secondEncoded = rgbaPng(3, 2, otherHiddenRgb)
        val encodedBefore = encoded.copyOf()
        val secondBefore = secondEncoded.copyOf()
        assertFalse(
            "The source PNGs must really contain different hidden RGB bytes",
            encoded.contentEquals(secondEncoded),
        )
        val first = owned.addBitmap(decodeArgb(encoded))
        val second = owned.addBitmap(decodeArgb(secondEncoded))
        val guards = listOf(first, second).map(::SourceGuard)
        val composed = owned.addBitmap(whiteComposite(first))
        // SRC_OVER in Android's eight-bit premultiplied canvas: black128 over white is127;
        // red128 over white is(255,127,127). These colors avoid unpremultiply rounding ambiguity.
        val expected = intArrayOf(
            Color.WHITE,
            0xFF7F7F7F.toInt(),
            0xFFFF7F7F.toInt(),
            0xFF0A141E.toInt(),
            Color.WHITE,
            Color.BLACK,
        )
        assertArrayEquals(
            "Canvas white-matte oracle must agree with analytic alpha endpoints",
            expected,
            pixels(composed),
        )
        val a = Detector.preprocess(first, size = 3)
        val b = Detector.preprocess(second, size = 3)
        val failures = expected.indices.count { index -> !pixelMatches(a, index % 3, index / 3, expected[index]) }
        val hiddenDifferences = tensorDifferences(a, b)
        guards.forEach { it.verify() }
        assertArrayEquals(encodedBefore, encoded)
        assertArrayEquals(secondBefore, secondEncoded)
        evidence(
            "detector-alpha-endpoints",
            JSONObject().put("analyticPixelMismatches", failures)
                .put("hiddenRgbTensorDifferences", hiddenDifferences).put("sourceAndPngBytesPreserved", true)
                .put("modelConstructorsByTest", 0),
        )
        assertEquals("Invisible RGB must not influence analysis", 0, hiddenDifferences)
        assertEquals("Transparent and half-alpha source colors must use a white analysis matte", 0, failures)
        assertBlackPadding(a, validWidth = 3, validHeight = 2)
    }

    /** Opaque ARGB and RGB565 are controls; the DBNet right/bottom padding stays black. */
    @Test
    fun opaqueArgbAndRgb565KeepDetectorValuesAndBlackPadding() = bitmaps { owned ->
        auditContext()
        val argb = owned.addBitmap(
            Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).apply {
                    drawColor(Color.WHITE)
                    drawRect(8f, 8f, 80f, 80f, Paint().apply { color = Color.rgb(48, 96, 160) })
                }
            },
        )
        val rgb565 = owned.addBitmap(checkNotNull(argb.copy(Bitmap.Config.RGB_565, true)))
        assertFalse(rgb565.hasAlpha())
        val argbFrom565 = owned.addBitmap(
            Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawBitmap(rgb565, 0f, 0f, null)
            },
        )
        val guards = listOf(argb, rgb565, argbFrom565).map(::SourceGuard)
        val converted = Detector.preprocess(argb, size = 256)
        val converted565 = Detector.preprocess(rgb565, size = 256)
        val reference565 = Detector.preprocess(argbFrom565, size = 256)
        assertTrue(pixelMatches(converted, 0, 0, Color.WHITE))
        assertTrue(pixelMatches(converted, 16, 16, argb.getPixel(16, 16)))
        assertEquals(0, tensorDifferences(converted565, reference565))
        assertTrue(pixelMatches(converted565, 16, 16, rgb565.getPixel(16, 16)))
        assertBlackPadding(converted, validWidth = 256, validHeight = 128)
        assertBlackPadding(converted565, validWidth = 256, validHeight = 128)
        guards.forEach { it.verify() }
        evidence(
            "detector-opaque-controls",
            JSONObject().put("argbAndRgb565Passed", true)
                .put("blackModelPaddingPreserved", true).put("sourceAndPngBytesPreserved", true)
                .put("modelConstructorsByTest", 0),
        )
    }

    /** Explicit local native test; not a translation, throughput or remaining-punctuation test. */
    @Test
    fun nativeTransparentGlyphsMatchOpaqueDetectionAndRecognition() {
        check(InstrumentationRegistry.getArguments().getString("transparentOcrNative") == "true") {
            "Explicit transparentOcrNative=true is required"
        }
        Executors.newSingleThreadExecutor { task -> Thread(task, "transparent-ocr-input") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(180_000) { nativeParity() }
                }
            }
    }

    private suspend fun nativeParity() = bitmaps { owned ->
        val context = auditContext()
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
        NativeDiagnosticIdleGuard.requireIdle(context)
        val opaque = owned.addBitmap(glyphPage())
        val transparent = owned.addBitmap(alphaCoverageOf(opaque))
        val composed = owned.addBitmap(whiteComposite(transparent))
        assertArrayEquals(pixels(opaque), pixels(composed))
        val guards = listOf(opaque, transparent, composed).map(::SourceGuard)
        val directory = File(context.filesDir.canonicalFile, "models").canonicalFile
        check(directory.parentFile == context.filesDir.canonicalFile && directory.isDirectory)
        fun model(name: String): File = File(directory, name).also {
            check(it.canonicalFile == it.absoluteFile && it.parentFile == directory && it.isFile && it.length() > 0)
        }
        val detectorParam = model("dbnet_detect.ncnn.param")
        val detectorBin = model("dbnet_detect.ncnn.bin")
        val ocrParam = model("ocr_48px_ctc.ncnn.param")
        val ocrBin = model("ocr_48px_ctc.ncnn.bin")
        val modelFiles = listOf(detectorParam, detectorBin, ocrParam, ocrBin) +
            if (File(directory, "ocr_48px_ctc_mixed.ncnn.param").exists()) {
                listOf(model("ocr_48px_ctc_mixed.ncnn.param"))
            } else {
                emptyList()
            }
        val modelBefore = modelFiles.associate { it.name to fileDigest(it) }
        val dictionary = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        var detector: Detector? = null
        var ocr: Ocr? = null
        var opaqueHasDetection = false
        var detectorParity = false
        var maskParity = false
        var opaqueRecognized = false
        var recognizeParity = false
        var publicDetectorCalls = 0
        var publicRecognizeCalls = 0
        try {
            Detector(detectorParam.path).use { localDetector ->
                detector = localDetector
                val whiteResult = localDetector.detect(opaque).also { owned += it.textMask }
                publicDetectorCalls++
                val alphaResult = localDetector.detect(transparent).also { owned += it.textMask }
                publicDetectorCalls++
                opaqueHasDetection = whiteResult.lines.isNotEmpty()
                detectorParity = sameQuads(whiteResult.lines, alphaResult.lines)
                maskParity = pixels(whiteResult.textMask).contentEquals(pixels(alphaResult.textMask))
                Ocr(ocrParam.path, dictionary, OcrConfig(concurrency = 1)).use { localOcr ->
                    ocr = localOcr
                    val quad = glyphQuad()
                    val whiteLine = TextLine(quad.map { Pt(it.x, it.y) }, 1f)
                    val alphaLine = TextLine(quad.map { Pt(it.x, it.y) }, 1f)
                    val whiteExcluded = localOcr.recognize(opaque, listOf(whiteLine))
                    publicRecognizeCalls++
                    val alphaExcluded = localOcr.recognize(transparent, listOf(alphaLine))
                    publicRecognizeCalls++
                    // The API returns intentionally excluded lines, not successful recognitions.
                    opaqueRecognized = whiteLine !in whiteExcluded && whiteLine.text.isNotBlank()
                    recognizeParity = alphaLine !in alphaExcluded &&
                        whiteLine.text == alphaLine.text && whiteLine.direction == alphaLine.direction
                    assertEquals(quad, whiteLine.quad)
                    assertEquals(quad, alphaLine.quad)
                }
            }
        } finally {
            val handlesReleased = listOfNotNull(ocr, detector).all { net ->
                net.javaClass.getDeclaredField("ncnnHandle").apply { isAccessible = true }.getLong(net) == 0L
            }
            val sourcesPreserved = guards.all { it.matches() }
            val modelBytesPreserved = modelBefore == modelFiles.associate { it.name to fileDigest(it) }
            NativeDiagnosticIdleGuard.requireIdle(context)
            evidence(
                "native-parity",
                JSONObject()
                    .put("publicDetectorCalls", publicDetectorCalls)
                    .put("publicRecognizeCalls", publicRecognizeCalls)
                    .put("nativeForwardCountObserved", false)
                    .put("opaqueDetectionControlNonempty", opaqueHasDetection)
                    .put("detectorQuadsEquivalent", detectorParity)
                    .put("detectorMasksEqual", maskParity)
                    .put("opaqueRecognitionControlNonblank", opaqueRecognized)
                    .put("publicRecognitionEquivalent", recognizeParity)
                    .put("sourceAndPngBytesPreserved", sourcesPreserved)
                    .put("modelBytesPreserved", modelBytesPreserved)
                    .put("ownedNativeHandlesReleased", handlesReleased)
                    .put("networkCallsByTest", 0)
                    .put("preferenceWritesByTest", 0)
                    .put("imageFilesWritten", 0),
            )
            assertTrue("Owned native handles must close", handlesReleased)
            assertTrue("Source bitmap pixels and PNG encodings must remain unchanged", sourcesPreserved)
            assertTrue("Existing model bytes must remain unchanged", modelBytesPreserved)
        }
        assertTrue("Opaque native detection control must find the neutral glyphs", opaqueHasDetection)
        assertTrue("Opaque public OCR control must keep nonblank neutral text", opaqueRecognized)
        assertTrue("Transparent and opaque equivalents must have matching detected quads", detectorParity)
        assertTrue("Transparent and opaque equivalents must have matching detector masks", maskParity)
        assertTrue("Transparent and opaque equivalents must have matching public OCR results", recognizeParity)
    }

    private fun auditContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit package required" }
    }

    private fun glyphPage() = Bitmap.createBitmap(1000, 160, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawColor(Color.WHITE)
            drawText("こんにちは。", 20f, 110f, glyphPaint())
        }
    }

    private fun glyphPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 60f
    }

    private fun glyphQuad(): List<Pt> {
        val bounds = android.graphics.Rect().also { glyphPaint().getTextBounds("こんにちは。", 0, 6, it) }
        return listOf(
            Pt(20f + bounds.left, 110f + bounds.top),
            Pt(20f + bounds.right, 110f + bounds.top),
            Pt(20f + bounds.right, 110f + bounds.bottom),
            Pt(20f + bounds.left, 110f + bounds.bottom),
        )
    }

    private fun alphaCoverageOf(opaque: Bitmap): Bitmap {
        val source = pixels(opaque)
        val alpha = source.map { color ->
            val red = color ushr 16 and 255
            check((color ushr 24) == 255 && (color ushr 8 and 255) == red && (color and 255) == red)
            (255 - red) shl 24
        }.toIntArray()
        return Bitmap.createBitmap(alpha, opaque.width, opaque.height, Bitmap.Config.ARGB_8888)
    }

    private fun whiteComposite(source: Bitmap) =
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, 0f, 0f, null)
            }
        }

    private fun decodeArgb(encoded: ByteArray) = checkNotNull(
        BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            },
        ),
    ).also {
        check(it.config == Bitmap.Config.ARGB_8888)
    }

    private fun tensorDifferences(a: DetectorInput, b: DetectorInput): Int {
        assertEquals(a.w, b.w)
        assertEquals(a.h, b.h)
        assertEquals(a.ratio.toRawBits(), b.ratio.toRawBits())
        assertEquals(a.chw.size, b.chw.size)
        return a.chw.indices.count { a.chw[it].toRawBits() != b.chw[it].toRawBits() }
    }

    private fun pixelMatches(input: DetectorInput, x: Int, y: Int, rgb: Int): Boolean {
        val area = input.w * input.h
        val index = y * input.w + x
        for (plane in 0..2) {
            val shift = 16 - plane * 8
            if (
                input.chw[plane * area + index].toRawBits() !=
                (((rgb ushr shift and 255) / 127.5f) - 1f).toRawBits()
            ) {
                return false
            }
        }
        return true
    }

    private fun assertBlackPadding(input: DetectorInput, validWidth: Int, validHeight: Int) {
        for (y in 0 until input.h) {
            for (x in 0 until input.w) {
                if (x >= validWidth || y >= validHeight) {
                    assertTrue("DBNet padding must remain black", pixelMatches(input, x, y, Color.BLACK))
                }
            }
        }
    }

    private fun sameQuads(a: List<TextLine>, b: List<TextLine>): Boolean = a.size == b.size && a.indices.all { index ->
        a[index].quad.size == b[index].quad.size && a[index].quad.indices.all { point ->
            abs(a[index].quad[point].x - b[index].quad[point].x) < 0.5f &&
                abs(a[index].quad[point].y - b[index].quad[point].y) < 0.5f
        }
    }

    private class SourceGuard(private val bitmap: Bitmap) {
        private val config = bitmap.config
        private val before = pixels(bitmap)
        private val encoded = png(bitmap)
        fun matches() = !bitmap.isRecycled && bitmap.config == config &&
            before.contentEquals(pixels(bitmap)) && encoded.contentEquals(png(bitmap))
        fun verify() = assertTrue("Source bitmap pixels/config and PNG bytes must be preserved", matches())
    }

    private fun evidence(stage: String, values: JSONObject) {
        values.put("stage", stage).put("syntheticInputsOnly", true).put("sourceImagesOrTextExported", false)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("transparentOcrInputEvidence", values.toString())
            },
        )
    }

    private fun MutableList<Bitmap>.addBitmap(bitmap: Bitmap): Bitmap = bitmap.also { add(it) }

    private inline fun <T> bitmaps(block: (MutableList<Bitmap>) -> T): T {
        val owned = ArrayList<Bitmap>()
        return try {
            block(owned)
        } finally {
            owned.asReversed().forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    companion object {
        private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

        private fun png(bitmap: Bitmap) = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }

        private fun fileDigest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        /** Tiny RGBA PNG encoder keeps deliberately invisible RGB in the encoded source fixture. */
        private fun rgbaPng(width: Int, height: Int, rgba: IntArray): ByteArray {
            check(rgba.size == width * height)
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { png ->
                png.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
                fun chunk(type: String, bytes: ByteArray) {
                    val tag = type.toByteArray(Charsets.US_ASCII)
                    val crc = CRC32().apply {
                        update(tag)
                        update(bytes)
                    }
                    png.writeInt(bytes.size)
                    png.write(tag)
                    png.write(bytes)
                    png.writeInt(crc.value.toInt())
                }
                val header = ByteArrayOutputStream().also { bytes ->
                    DataOutputStream(bytes).use { data ->
                        data.writeInt(width)
                        data.writeInt(height)
                        data.write(byteArrayOf(8, 6, 0, 0, 0))
                    }
                }.toByteArray()
                chunk("IHDR", header)
                val compressed = ByteArrayOutputStream().also { bytes ->
                    DeflaterOutputStream(bytes).use { data ->
                        for (y in 0 until height) {
                            data.write(0)
                            for (x in 0 until width) {
                                val color = rgba[y * width + x]
                                data.write(color ushr 16 and 255)
                                data.write(color ushr 8 and 255)
                                data.write(color and 255)
                                data.write(color ushr 24 and 255)
                            }
                        }
                    }
                }.toByteArray()
                chunk("IDAT", compressed)
                chunk("IEND", byteArrayOf())
            }
            return output.toByteArray()
        }
    }
}
