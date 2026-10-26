package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.crash.TraceLog
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.EngineTrace
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.Translator
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Real local DBNet/OCR and public Pipeline; scripted translation and boxfill, without HTTP or UI. */
@RunWith(AndroidJUnit4::class)
class VerticalBalloonAlphaInputTest {
    @Test
    fun nativeTransparentClosedBalloonsKeepTheSameTwoGroupsAndFourColumnsAsWhitePaper() {
        check(InstrumentationRegistry.getArguments().getString("verticalBalloonAlphaNative") == "true") {
            "Explicit verticalBalloonAlphaNative=true is required; missing preparation is not a pass or skip"
        }
        Executors.newSingleThreadExecutor { task -> Thread(task, "vertical-balloon-alpha-native") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(180_000) {
                        pairedClosedBalloons()
                    }
                }
            }
    }

    private suspend fun pairedClosedBalloons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Actual ARM64 process required" }
        NativeDiagnosticIdleGuard.requireIdle(context)
        check(!TraceLog.isEnabled && EngineTrace.sink == null) { "Existing diagnostic ownership must remain untouched" }
        val models = Models(context)
        val owned = mutableListOf<Bitmap>()
        val guarded = mutableListOf<Snapshot>()
        fun keep(bitmap: Bitmap): Bitmap = bitmap.also {
            if (owned.none { existing -> existing === it }) owned += it
        }
        fun guard(bitmap: Bitmap) {
            guarded += Snapshot(bitmap)
        }
        fun translated(result: PageResult): PageResult.Translated {
            // Accept result ownership before an assertion can fail, including unexpected partial/skip.
            when (result) {
                is PageResult.Translated -> {
                    keep(result.page)
                    result.analysis?.mask?.let(::keep)
                }
                is PageResult.Partial -> {
                    keep(result.page)
                    keep(result.analysis.mask)
                }
                is PageResult.Skipped -> result.analysis?.mask?.let(::keep)
                is PageResult.Failed -> Unit
            }
            check(result is PageResult.Translated) {
                "The native closed-balloon control must complete: ${result.javaClass.simpleName}"
            }
            return result
        }

        val evidence = JSONObject().put("schemaVersion", 1).put("passed", false)
            .put("actualNativeDetectorAndOcr", true).put("publicPipelineCalls", 0)
            .put("translator", "in-memory-neutral-scripted-korean")
            .put("inpaintMethod", "boxfill").put("aotForwardClaimed", false)
            .put("networkCallsByTest", 0).put("preferencesWritten", false)
            .put("actualReaderActivity", false).put("performanceMeasurement", false)
            .put("wireIdsObserved", false).put("renderedPixelToleranceUsed", false)
        var detector: Detector? = null
        var ocr: Ocr? = null
        var inpainter: Inpainter? = null
        var pipeline: Pipeline? = null
        var failure: Throwable? = null
        try {
            val fixtureBytes = instrumentation.context.assets.open(FIXTURE).use { it.readBytes() }
            assertEquals("Existing neutral fixture bytes must remain exact", FIXTURE_SHA, sha(fixtureBytes))
            val reference = keep(checkNotNull(BitmapFactory.decodeByteArray(fixtureBytes, 0, fixtureBytes.size)))
            assertEquals(1200, reference.width)
            assertEquals(1600, reference.height)
            val referencePixels = pixels(reference)
            assertTrue(
                "The known opaque control must actually be opaque",
                referencePixels.all { Color.alpha(it) == 255 },
            )
            val alphaPixels = referencePixels.map { if (it == Color.WHITE) Color.TRANSPARENT else it }.toIntArray()
            assertEquals(
                "The regression must include the same real transparent paper",
                1_222_465,
                alphaPixels.count { it == 0 },
            )
            val alpha = keep(
                Bitmap.createBitmap(reference.width, reference.height, Bitmap.Config.ARGB_8888).apply {
                    density = reference.density
                    setPixels(alphaPixels, 0, width, 0, 0, width, height)
                },
            )
            val white = keep(
                Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888).apply {
                    density = alpha.density
                    Canvas(this).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(alpha, null, Rect(0, 0, width, height), null)
                    }
                },
            )
            assertArrayEquals(
                "Independent Canvas white composition must retain every reference pixel",
                referencePixels,
                pixels(white),
            )
            guard(reference)
            guard(alpha)
            guard(white)

            val nativeDetector = Detector(models.file("dbnet_detect.ncnn.param").path).also { detector = it }
            val nativeOcr = Ocr(
                models.file("ocr_48px_ctc.ncnn.param").path,
                models.alphabet,
                OcrConfig(concurrency = 1),
            ).also { ocr = it }
            val removal = InpainterConfig(method = "boxfill", maskDilate = 8f, bboxPad = 8)
            val nativeInpainter = Inpainter(models.file("mit_aot_fixed512.ncnn.param").path, removal)
                .also { inpainter = it }
            assertNativeHandleLive(nativeDetector)
            assertNativeHandleLive(nativeOcr)
            assertNativeHandleLive(nativeInpainter)
            val translator = ScriptedTranslator(evidence)
            val nativePipeline = Pipeline(
                nativeDetector,
                nativeOcr,
                translator,
                nativeInpainter,
                EngineConfig(
                    ocr = OcrConfig(concurrency = 1),
                    inpainter = removal,
                    render = RenderConfig(
                        orientation = TextOrientation.HORIZONTAL,
                        fontSizeMin = 30,
                        fontSizeMax = 30,
                        fontBorder = false,
                        expandW = 1f,
                        expandH = 1f,
                        rowTrim = 0,
                        colTrim = 0,
                    ),
                ),
                Typeface.DEFAULT,
            ).also { pipeline = it }

            // These masks are independently owned controls, never supplied to or recycled by Pipeline.
            val detectedWhite = nativeDetector.detect(white)
            val whiteMask = keep(detectedWhite.textMask)
            val detectedAlpha = nativeDetector.detect(alpha)
            val alphaMask = keep(detectedAlpha.textMask)
            guard(whiteMask)
            guard(alphaMask)
            val expectedMask = pixels(whiteMask)
            assertTrue("Real detector support is required", expectedMask.any { (it and 255) > 127 })
            assertArrayEquals("Transparency must not change the raw detector mask", expectedMask, pixels(alphaMask))
            assertEquals("Known opaque source must retain four real vertical columns", 4, detectedWhite.lines.size)
            val excluded = nativeOcr.recognize(white, detectedWhite.lines)
            assertTrue("The positive OCR control must not exclude any of the four columns", excluded.isEmpty())
            assertTrue(
                "Every native OCR control column must be nonblank",
                detectedWhite.lines.all { it.text.isNotBlank() },
            )
            evidence.put("positiveOpaqueOcrColumns", detectedWhite.lines.size)

            evidence.put("publicPipelineCalls", 1)
            val first = translated(nativePipeline.translatePage(white))
            evidence.put("whiteRegions", first.analysis?.regions?.size ?: -1)
            val firstAnalysis = complete(first, "opaque-white")
            assertEquals("One complete page must be one logical translator call", 1, translator.batches.size)
            assertEquals(firstAnalysis.regions.map { it.sourceText }, translator.batches[0])
            assertArrayEquals(
                "Pipeline must return its untouched raw detector mask",
                expectedMask,
                pixels(firstAnalysis.mask),
            )
            assertNotSame(whiteMask, firstAnalysis.mask)
            assertNotSame(white, first.page)

            evidence.put("publicPipelineCalls", 2)
            val second = translated(nativePipeline.translatePage(alpha))
            evidence.put("alphaRegions", second.analysis?.regions?.size ?: -1)
            val secondAnalysis = complete(second, "transparent")
            assertEquals("Each page must issue one complete two-group logical batch", 2, translator.batches.size)
            assertEquals(translator.batches[0], translator.batches[1])
            assertEquals(secondAnalysis.regions.map { it.sourceText }, translator.batches[1])
            assertArrayEquals(expectedMask, pixels(secondAnalysis.mask))
            assertNotSame(alphaMask, secondAnalysis.mask)
            assertNotSame(firstAnalysis.mask, secondAnalysis.mask)
            assertNotSame(alpha, second.page)
            assertEquals(
                "Actual stable group IDs, OCR text, geometry and targets must match the white control",
                firstAnalysis.progress,
                secondAnalysis.progress,
            )
            assertOutsideBalloonFrameUnchanged(alpha, second.page)
            assertEquals("No caller asset bytes are rewritten", FIXTURE_SHA, sha(fixtureBytes))
            evidence.put("regionsPerLogicalBatch", 2).put("lineCountPerPage", 4)
                .put("actualProgressRegionIds", "0,1").put("logicalBatches", translator.batches.size)
                .put("pairedProgressAndRawMasksExact", true).put("originalFrameArgbPreserved", true)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val cleanup = mutableListOf<Throwable>()
            fun verify(action: () -> Unit) {
                try {
                    action()
                } catch (caught: Throwable) {
                    cleanup += caught
                }
            }
            guarded.forEach { snapshot -> verify { snapshot.verify() } }
            val completedPipeline = pipeline
            if (completedPipeline != null) {
                verify { completedPipeline.close() }
            }
            // Finish releasing constructed owners even if construction or Pipeline.close failed.
            inpainter?.let { owner -> verify { if (nativeHandle(owner) != 0L) owner.close() } }
            ocr?.let { owner -> verify { if (nativeHandle(owner) != 0L) owner.close() } }
            detector?.let { owner -> verify { if (nativeHandle(owner) != 0L) owner.close() } }
            listOfNotNull(detector, ocr, inpainter).forEach { owner -> verify { assertNativeHandleClosed(owner) } }
            guarded.forEach { snapshot -> verify { snapshot.verify() } }
            owned.asReversed().forEach { bitmap -> verify { if (!bitmap.isRecycled) bitmap.recycle() } }
            verify { models.verifyUnchanged() }
            verify { check(!TraceLog.isEnabled && EngineTrace.sink == null) }
            verify { NativeDiagnosticIdleGuard.requireIdle(context) }
            evidence.put("callerArgbMetadataAndPngPreserved", guarded.isNotEmpty() && cleanup.isEmpty())
                .put("ownedHandlesClosed", listOfNotNull(detector, ocr, inpainter).size == 3 && cleanup.isEmpty())
                .put("modelHashesPreserved", models.unchanged).put("cleanupFailures", cleanup.size)
                .put("passed", failure == null && cleanup.isEmpty())
            verify {
                instrumentation.sendStatus(
                    2,
                    Bundle().apply { putString("verticalBalloonAlphaEvidence", evidence.toString()) },
                )
            }
            val primary = failure
            if (primary != null) {
                cleanup.forEach(primary::addSuppressed)
            } else if (cleanup.isNotEmpty()) {
                cleanup.drop(1).forEach(cleanup[0]::addSuppressed)
                throw cleanup[0]
            }
        }
    }

    private fun complete(result: PageResult.Translated, name: String): PageAnalysis {
        val analysis = checkNotNull(result.analysis)
        val progress = checkNotNull(analysis.progress)
        assertFalse("$name must not hide unresolved OCR", progress.hasPending)
        assertEquals("$name must preserve the two closed balloon groups", 2, analysis.regions.size)
        assertEquals(listOf(0, 1), progress.regions.map { it.id })
        assertTrue(progress.regions.all { it.state == RegionState.TRANSLATED && it.lines.size == 2 })
        assertEquals(4, analysis.regions.sumOf { it.lines.size })
        assertEquals(EXPECTED_SOURCES, analysis.regions.map { it.sourceText }.toSet())
        assertTrue(
            analysis.regions.all { region ->
                region.lines.all { it.text.isNotBlank() && it.direction == "v" } &&
                    !region.preserveOriginal && region.translatedText == "문장 전체 번역"
            },
        )
        return analysis
    }

    private class ScriptedTranslator(private val evidence: JSONObject) : Translator {
        val batches = mutableListOf<List<String>>()
        override val translationContext = TranslationContext("7".repeat(64))

        override suspend fun translate(queries: List<String>): List<String> {
            batches += queries.toList()
            evidence.put("logicalBatches", batches.size).put("lastLogicalBatchSize", queries.size)
            assertEquals("Both complete balloons must arrive together", 2, queries.size)
            assertEquals(EXPECTED_SOURCES, queries.toSet())
            return queries.map { "문장 전체 번역" }
        }
    }

    private class Snapshot(private val source: Bitmap) {
        private val argb = pixels(source)
        private val encoded = png(source)
        private val config = source.config
        private val density = source.density
        private val alpha = source.hasAlpha()
        private val premultiplied = source.isPremultiplied

        fun verify() {
            assertFalse("A borrowed source or mask must remain live", source.isRecycled)
            assertEquals(config, source.config)
            assertEquals(density, source.density)
            assertEquals(alpha, source.hasAlpha())
            assertEquals(premultiplied, source.isPremultiplied)
            assertArrayEquals(argb, pixels(source))
            assertArrayEquals(encoded, png(source))
        }
    }

    private class Models(context: Context) {
        private val directory = File(context.filesDir.canonicalFile, "models").canonicalFile.also {
            check(it.parentFile == context.filesDir.canonicalFile && it.isDirectory)
        }
        private val files = REQUIRED.associate { expected ->
            val file = File(directory, expected.name)
            check(file.canonicalFile == file.absoluteFile && file.parentFile == directory && file.isFile)
            check(file.length() == expected.bytes && digest(file) == expected.sha256) {
                "A required native model is unavailable or differs: ${expected.name}"
            }
            expected.name to file
        }
        private val before = files.mapValues { (_, file) -> file.length() to digest(file) }
        val alphabet: List<String> = context.assets.open("models/alphabet-all-v5.txt").bufferedReader()
            .use { it.readLines() }
            .also { check(it.size == 19_264 && listOf("。", "、", "「", "」").all(it::contains)) }
        var unchanged = false
            private set

        fun file(name: String): File = checkNotNull(files[name])

        fun verifyUnchanged() {
            assertEquals(before, files.mapValues { (_, file) -> file.length() to digest(file) })
            unchanged = true
        }
    }

    private data class ExpectedModel(val name: String, val bytes: Long, val sha256: String)

    companion object {
        private const val FIXTURE = "native-quality/vertical-punctuation.png"
        private const val FIXTURE_SHA = "281ffb1cc92aa692bddf6ea1429ecbb32a3ea6cfc974c09329154abd628010f3"
        private val EXPECTED_SOURCES = setOf("少し待って、ください。", "「明日もよろしくお願いします。」")
        private val REQUIRED = listOf(
            ExpectedModel(
                "dbnet_detect.ncnn.param",
                13_392,
                "9e6db2f8c6b0662ab00eb2100b3373d3c984a235eaac0e61c0b2a484ee1ff7b5",
            ),
            ExpectedModel(
                "dbnet_detect.ncnn.bin",
                153_010_556,
                "f57bdbede7764a534c56e88be0269602259a7fcd47e54e8b7d954fd0fcc55c3d",
            ),
            ExpectedModel(
                "ocr_48px_ctc.ncnn.param",
                18_133,
                "e701cfc5df9d3c55c9fd0a36725499d01d45c18e32946271fc26e580cd9901bd",
            ),
            ExpectedModel(
                "ocr_48px_ctc_mixed.ncnn.param",
                18_438,
                "32e298deca3acb8ba95897ca8cccbd028582ae42a697c3c05b4eaefe198309e3",
            ),
            ExpectedModel(
                "ocr_48px_ctc.ncnn.bin",
                83_037_664,
                "3e0a809441f5284871d18a3d757a7ec098c4b9ee64bdfd7ac776a84aa057c7a9",
            ),
            ExpectedModel(
                "mit_aot_fixed512.ncnn.param",
                33_810,
                "f21ef860d21a6cdf60dfb1742c08d1c0d98837bceeb2ee3fe9c2dbbeee7d32b5",
            ),
            ExpectedModel(
                "mit_aot_fixed512.ncnn.bin",
                11_366_088,
                "a52db45eafc1dd2aa4ce9a339c711917fa98fefb31ce4506d4c95e8b5e3560b6",
            ),
        )

        private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

        private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }

        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        private fun assertNativeHandleLive(owner: Any) {
            assertTrue("Each owned component must have an actual native handle", nativeHandle(owner) != 0L)
        }

        private fun assertNativeHandleClosed(owner: Any) {
            assertEquals("All owned native handles must close", 0L, nativeHandle(owner))
        }

        private fun nativeHandle(owner: Any): Long = owner.javaClass.getDeclaredField("ncnnHandle")
            .apply { isAccessible = true }.getLong(owner)

        private fun assertOutsideBalloonFrameUnchanged(source: Bitmap, output: Bitmap) {
            val before = pixels(source)
            val after = pixels(output)
            assertEquals(before.size, after.size)
            for (index in before.indices) {
                val x = index % source.width
                val y = index / source.width
                // Independent known fixture frame, far outside either text/removal/render region.
                if (x < 60 || x >= 1140 || y < 70 || y >= 1490) {
                    assertEquals("Off-balloon source ARGB changed at ($x,$y)", before[index], after[index])
                }
            }
        }
    }
}
