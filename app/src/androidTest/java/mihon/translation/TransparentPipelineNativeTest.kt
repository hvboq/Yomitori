package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.crash.TraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.Renderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.Translator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Explicit, local ARM64 regression. The translator is scripted; Detector/OCR/AOT are real.
 * Inputs and all intermediate images remain in RAM. No UI, SAF, HTTP or preference writes.
 */
@RunWith(AndroidJUnit4::class)
class TransparentPipelineNativeTest {
    @Test
    fun nativePipelinePairsWhiteAnalysisAndPreservesOriginalArgbOutsideActualMasks() =
        nativeTest("public-pipeline-and-mask-only-aot") { scope ->
            val opaque = scope.keep(readablePipelineGlyphPage())
            val alpha = scope.keep(alphaCoverageOf(opaque))
            val composed = scope.keep(whiteComposite(alpha))
            assertArrayEquals("Independent Canvas control must be pixel-identical", pixels(opaque), pixels(composed))
            scope.guard(opaque)
            scope.guard(alpha)
            scope.guard(composed)
            val translator = ScriptedTranslator()
            val paintConfig = fixedRender()
            val inpaintConfig = InpainterConfig(method = "aot", tileSize = 768, maskDilate = 8f, bboxPad = 8)
            val detector = Detector(scope.models.file("dbnet_detect.ncnn.param").path)
            try {
                val ocr = Ocr(
                    scope.models.file("ocr_48px_ctc.ncnn.param").path,
                    scope.models.dictionary,
                    OcrConfig(concurrency = 1),
                )
                try {
                    val inpainter = Inpainter(scope.models.file("mit_aot_fixed512.ncnn.param").path, inpaintConfig)
                    try {
                        val pipeline = Pipeline(
                            detector,
                            ocr,
                            translator,
                            inpainter,
                            EngineConfig(
                                ocr = OcrConfig(concurrency = 1),
                                inpainter = inpaintConfig,
                                render = paintConfig,
                            ),
                            Typeface.DEFAULT,
                        )
                        try {
                            // Preserve independent raw Detector masks before the public Pipeline owns
                            // its own detection result. This catches mutation hidden by returned analysis.
                            val opaqueDetection = detector.detect(opaque)
                            val originalMask = scope.keep(opaqueDetection.textMask)
                            val alphaMask = scope.keep(detector.detect(alpha).textMask)
                            scope.guard(originalMask)
                            scope.guard(alphaMask)
                            assertTrue(
                                "The opaque control must have real detector support",
                                pixels(originalMask).any(::maskOn),
                            )
                            assertArrayEquals(pixels(originalMask), pixels(alphaMask))

                            // Unlike the separate manual-quad OCR control, the public Pipeline
                            // consumes actual Detector geometry. Prove this synthetic figure is
                            // readable through that same public OCR entry point before expecting AOT.
                            val excluded = ocr.recognize(opaque, opaqueDetection.lines)
                            val readyLines = opaqueDetection.lines.count { it !in excluded && it.text.isNotBlank() }
                            val precondition = JSONObject().put("detectedLineCount", opaqueDetection.lines.size)
                                .put("nonblankNonexcludedLineCount", readyLines)
                                .put("intentionallyExcludedLineCount", excluded.size)
                                .put(
                                    "passed",
                                    opaqueDetection.lines.isNotEmpty() && readyLines == opaqueDetection.lines.size,
                                )
                            scope.row.put("directOpaqueOcrPreconditionCalls", 1)
                                .put("opaqueOcrPrecondition", precondition)
                            InstrumentationRegistry.getInstrumentation().sendStatus(
                                2,
                                Bundle().apply {
                                    putString(
                                        "transparentPipelineProgress",
                                        JSONObject().put("stage", "opaque-detector-public-ocr-precondition")
                                            .put("precondition", precondition).toString(),
                                    )
                                },
                            )
                            assertTrue(
                                "Fixture precondition: actual Detector lines must all produce nonblank, nonexcluded public OCR",
                                opaqueDetection.lines.isNotEmpty() && readyLines == opaqueDetection.lines.size,
                            )

                            val first = scope.translated(scope.aot(1) { pipeline.translatePage(opaque) })
                            val second = scope.translated(scope.aot(1) { pipeline.translatePage(alpha) })
                            val firstAnalysis = completeHorizontalAnalysis(first)
                            val secondAnalysis = completeHorizontalAnalysis(second)
                            assertTrue(
                                "The actual opaque OCR control must be nonblank",
                                firstAnalysis.regions.single().sourceText.isNotBlank(),
                            )
                            assertEquals(
                                "Scripted translation must make one logical batch per page",
                                2,
                                translator.batches.size,
                            )
                            assertEquals(1, translator.batches[0].size)
                            assertEquals(translator.batches[0], translator.batches[1])
                            assertTrue(translator.batches.flatten().all { it.isNotBlank() })
                            assertRegionsEquivalent(firstAnalysis, secondAnalysis)
                            assertArrayEquals(
                                "Pipeline must retain its raw detector mask",
                                pixels(originalMask),
                                pixels(firstAnalysis.mask),
                            )
                            assertArrayEquals(pixels(alphaMask), pixels(secondAnalysis.mask))
                            scope.guard(firstAnalysis.mask)
                            scope.guard(secondAnalysis.mask)

                            val removal = scope.keep(
                                inpainter.buildMask(opaque, firstAnalysis.regions, firstAnalysis.mask),
                            )
                            val alphaRemoval = scope.keep(
                                inpainter.buildMask(alpha, secondAnalysis.regions, secondAnalysis.mask),
                            )
                            assertArrayEquals(pixels(removal), pixels(alphaRemoval))
                            val removalPixels = pixels(removal)
                            assertTrue(removalPixels.any(::maskOn))
                            assertTrue(
                                "Gradient fixture must exercise AOT rather than opaque flat-fill",
                                firstAnalysis.regions.single().onArt,
                            )
                            assertTrue(secondAnalysis.regions.single().onArt)

                            // Mask-only replay uses the returned real analysis and the actual public
                            // removal mask, established before replay. Every OFF pixel is exact ARGB.
                            val clean = scope.keep(
                                scope.aot(1) {
                                    inpainter.inpaint(opaque, firstAnalysis.regions, firstAnalysis.mask)
                                },
                            )
                            val alphaClean = scope.keep(
                                scope.aot(1) {
                                    inpainter.inpaint(alpha, secondAnalysis.regions, secondAnalysis.mask)
                                },
                            )
                            assertOffMaskUnchanged(opaque, clean, removalPixels)
                            val transparentOutside = assertOffMaskUnchanged(alpha, alphaClean, removalPixels)
                            assertTrue(
                                "Transparent source pixels outside removal must really be exercised",
                                transparentOutside > 0,
                            )
                            val cleanPixels = pixels(clean)
                            val alphaCleanPixels = pixels(alphaClean)
                            for (index in removalPixels.indices) {
                                if (maskOn(removalPixels[index])) {
                                    assertEquals(
                                        "Same white RGB input must produce identical AOT replacement",
                                        cleanPixels[index],
                                        alphaCleanPixels[index],
                                    )
                                    assertEquals(
                                        "Actual AOT replacement must be opaque",
                                        255,
                                        Color.alpha(cleanPixels[index]),
                                    )
                                    assertEquals(
                                        "Actual alpha-page AOT replacement must be opaque",
                                        255,
                                        Color.alpha(alphaCleanPixels[index]),
                                    )
                                }
                            }

                            val glyphSupport = scope.keep(
                                independentGlyphSupport(opaque, firstAnalysis.regions.single()),
                            )
                            assertFinalChangesBounded(opaque, first.page, removalPixels, pixels(glyphSupport))
                            assertFinalChangesBounded(alpha, second.page, removalPixels, pixels(glyphSupport))
                            val alphaWhiteOutput = scope.keep(whiteComposite(second.page))
                            val glyphPixels = pixels(glyphSupport)
                            val bitmapCoverageMaximum = exhaustiveBlackBitmapCoverageControl(scope)
                            val opaqueControl = scope.keep(
                                independentBlackTextOver(opaque, firstAnalysis.regions.single()),
                            )
                            val alphaControl = scope.keep(
                                independentBlackTextOver(alpha, firstAnalysis.regions.single()),
                            )
                            val alphaControlWhite = scope.keep(whiteComposite(alphaControl))
                            val alphaCleanWhite = scope.keep(whiteComposite(alphaClean))
                            val originalAlpha = pixels(alpha)
                            check(originalAlpha.all { (it and 0x00FFFFFF) == 0 })
                            val controlOpaque = pixels(opaqueControl)
                            val controlAlphaWhite = pixels(alphaControlWhite)
                            val actualOpaque = pixels(first.page)
                            val actualAlphaWhite = pixels(alphaWhiteOutput)
                            val controlStats = safeRgbDiffMetrics(
                                "independent-canvas-black-text",
                                controlOpaque,
                                controlAlphaWhite,
                                opaque.width,
                                removalPixels,
                                glyphPixels,
                            )
                            val finalStats = safeRgbDiffMetrics(
                                "full-pipeline-render",
                                actualOpaque,
                                actualAlphaWhite,
                                opaque.width,
                                removalPixels,
                                glyphPixels,
                            )
                            val maskOnlyStats = safeRgbDiffMetrics(
                                "mask-only-aot",
                                cleanPixels,
                                pixels(alphaCleanWhite),
                                opaque.width,
                                removalPixels,
                                glyphPixels,
                            )
                            val originalStats = safeRgbDiffMetrics(
                                "original-source",
                                pixels(opaque),
                                pixels(composed),
                                opaque.width,
                                removalPixels,
                                glyphPixels,
                            )
                            val controlMaximum = controlStats.getInt("maximumRgbDelta")
                            val violations = canvasOrderViolations(
                                actualOpaque,
                                actualAlphaWhite,
                                controlOpaque,
                                controlAlphaWhite,
                                removalPixels,
                                glyphPixels,
                            )

                            // Select from independent source/support, never from product differences.
                            val negativeIndex = originalAlpha.indices.firstOrNull { index ->
                                !maskOn(removalPixels[index]) && Color.alpha(originalAlpha[index]) in 1..254 &&
                                    Color.alpha(glyphPixels[index]) in 1..254
                            } ?: error("Fixture needs a fractional-coverage transparent pixel for its negative control")
                            val validDomainControl = controlAlphaWhite.copyOf()
                            for (index in removalPixels.indices) {
                                if (maskOn(removalPixels[index])) validDomainControl[index] = controlOpaque[index]
                            }
                            val positiveControlViolations = canvasOrderViolations(
                                controlOpaque,
                                validDomainControl,
                                controlOpaque,
                                controlAlphaWhite,
                                removalPixels,
                                glyphPixels,
                            )
                            val negative = validDomainControl.copyOf()
                            val shift = max(bitmapCoverageMaximum, controlMaximum) + 1
                            val old = negative[negativeIndex]
                            val red = if (Color.red(old) + shift <=
                                255
                            ) {
                                Color.red(old) + shift
                            } else {
                                Color.red(old) - shift
                            }
                            negative[negativeIndex] = Color.rgb(red, Color.green(old), Color.blue(old))
                            val negativeViolations = canvasOrderViolations(
                                controlOpaque,
                                negative,
                                controlOpaque,
                                controlAlphaWhite,
                                removalPixels,
                                glyphPixels,
                            )
                            val stages = JSONArray().put(
                                originalStats,
                            ).put(maskOnlyStats).put(controlStats).put(finalStats)
                            scope.row.put("whiteViewStages", stages)
                                .put("whiteViewMaxDelta", finalStats.getInt("maximumRgbDelta"))
                                .put("whiteViewChangedPixels", finalStats.getInt("changedPixels"))
                                .put("whiteViewBitwiseEqual", finalStats.getInt("changedPixels") == 0)
                                .put("whiteViewCanvasOracleMaxDelta", controlMaximum)
                                .put("whiteViewCanvasOracleViolations", violations)
                                .put("independentOraclePositiveControlAccepted", positiveControlViolations == 0)
                                .put(
                                    "largerRgbShiftNegativeControl",
                                    JSONObject().put("channelShift", shift)
                                        .put("rejected", negativeViolations > 0),
                                )
                            InstrumentationRegistry.getInstrumentation().sendStatus(
                                2,
                                Bundle().apply {
                                    putString(
                                        "transparentPipelineProgress",
                                        JSONObject().put("stage", "white-view-canvas-order-oracle")
                                            .put(
                                                "bitmapCoverageControl",
                                                scope.row.getJSONObject("exhaustiveBlackBitmapCoverageControl"),
                                            )
                                            .put("stages", stages).put("oracleViolations", violations)
                                            .put("positiveControlViolations", positiveControlViolations)
                                            .put(
                                                "negativeControlViolations",
                                                negativeViolations,
                                            ).put("negativeShift", shift)
                                            .toString(),
                                    )
                                },
                            )
                            assertEquals(0, originalStats.getInt("changedPixels"))
                            assertEquals(0, maskOnlyStats.getInt("changedPixels"))
                            assertEquals(0, controlStats.getInt("changedOutsideRemovalAndGlyph"))
                            // Bitmap overlay and glyph coverage use different raster primitives.
                            // Measure each independently; the exact text-control vector is the oracle.
                            assertTrue(
                                "Independent Canvas black-text control must stay within one RGB quantum",
                                controlMaximum in 0..1,
                            )
                            assertEquals(0, positiveControlViolations)
                            assertTrue(negativeViolations > 0)
                            assertEquals(
                                "White-view differences must exactly match the independent per-pixel Canvas-order oracle",
                                0,
                                violations,
                            )
                            scope.row.put("publicPipelineCalls", 2).put("directDetectorMaskControls", 2)
                                .put("logicalTranslatorBatches", translator.batches.size)
                                .put("sourceQueriesEquivalent", true).put("nonblankOcrControl", true)
                                .put("nonblankKoreanOutput", true).put("detectorMaskParity", true)
                                .put("rawMaskPreserved", true).put("opaqueReplacementMaxDelta", 0)
                                .put("outsideRemovalArgbChanges", 0)
                                .put("outsideAllowedRenderArgbChanges", 0)
                                .put("transparentOutsidePixelsRetained", transparentOutside)
                        } finally {
                            pipeline.close()
                            assertClosed(detector)
                            assertClosed(ocr)
                            assertClosed(inpainter)
                        }
                    } finally {
                        inpainter.close()
                    }
                } finally {
                    ocr.close()
                }
            } finally {
                detector.close()
            }
            assertClosed(detector)
            scope.row.put("ownedHandlesClosed", true)
        }

    @Test
    fun nativeBoxfillAndAutomaticTextColorUseWhiteAnalysisWithoutAotForward() =
        nativeTest("boxfill-and-auto-color") { scope ->
            val alpha = scope.keep(
                Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888).apply {
                    density = 241
                    eraseColor(Color.TRANSPARENT)
                    Canvas(this).drawRect(110f, 70f, 190f, 91f, Paint().apply { color = Color.BLACK })
                },
            )
            val opaque = scope.keep(whiteComposite(alpha))
            val rawMask = scope.keep(
                Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.BLACK)
                    Canvas(this).drawRect(110f, 70f, 190f, 91f, Paint().apply { color = Color.WHITE })
                },
            )
            scope.guard(alpha)
            scope.guard(opaque)
            scope.guard(rawMask)
            val whiteRegion = manualRegion()
            val alphaRegion = manualRegion()
            val inpainter = Inpainter(
                scope.models.file("mit_aot_fixed512.ncnn.param").path,
                InpainterConfig(method = "boxfill", maskDilate = 4f, bboxPad = 4),
            )
            try {
                val removal = scope.keep(inpainter.buildMask(alpha, listOf(alphaRegion), rawMask))
                val maskPixels = pixels(removal)
                assertTrue(maskPixels.any(::maskOn))
                val whiteClean = scope.keep(scope.aot(0) { inpainter.inpaint(opaque, listOf(whiteRegion), rawMask) })
                val alphaClean = scope.keep(scope.aot(0) { inpainter.inpaint(alpha, listOf(alphaRegion), rawMask) })
                assertFalse(whiteRegion.onArt)
                assertFalse(alphaRegion.onArt)
                assertOffMaskUnchanged(alpha, alphaClean, maskPixels)
                assertOffMaskUnchanged(opaque, whiteClean, maskPixels)
                val whitePixels = pixels(whiteClean)
                val alphaPixels = pixels(alphaClean)
                for (index in maskPixels.indices) {
                    if (maskOn(maskPixels[index])) {
                        assertEquals("White analysis must select a white box fill", Color.WHITE, alphaPixels[index])
                        assertEquals(whitePixels[index], alphaPixels[index])
                    }
                }

                // Separate Renderer-only controls ensure onArt=true cannot bypass bgLuminance.
                val clear = scope.keep(
                    Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.TRANSPARENT)
                    },
                )
                val white = scope.keep(whiteComposite(clear))
                val dark = scope.keep(
                    Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.BLACK)
                    },
                )
                listOf(clear, white, dark).forEach(scope::guard)
                val renderRegion = manualRegion()
                assertFalse(renderRegion.onArt)
                val support = scope.keep(independentGlyphSupport(clear, renderRegion))
                val clearRendered = scope.keep(
                    Renderer.render(clear, listOf(renderRegion), fixedRender(), Typeface.DEFAULT),
                )
                val whiteRendered = scope.keep(
                    Renderer.render(white, listOf(manualRegion()), fixedRender(), Typeface.DEFAULT),
                )
                val darkRendered = scope.keep(
                    Renderer.render(dark, listOf(manualRegion()), fixedRender(), Typeface.DEFAULT),
                )
                val clearWhite = scope.keep(whiteComposite(clearRendered))
                assertArrayEquals(pixels(whiteRendered), pixels(clearWhite))
                assertTrue(
                    "Transparent background's white analysis must render visible black Korean ink",
                    pixels(clearRendered).any {
                        it ==
                            Color.BLACK
                    },
                )
                assertTrue(
                    "Opaque dark control must still render white ink",
                    pixels(darkRendered).any {
                        it ==
                            Color.WHITE
                    },
                )
                val emptyRemoval = IntArray(clear.width * clear.height)
                assertFinalChangesBounded(clear, clearRendered, emptyRemoval, pixels(support))
                assertFinalChangesBounded(white, whiteRendered, emptyRemoval, pixels(support))
                assertFinalChangesBounded(dark, darkRendered, emptyRemoval, pixels(support))
                scope.row.put("publicPipelineCalls", 0).put("boxfillCalls", 2)
                    .put("boxfillMaskOnlyPreserved", true).put("autoColorOnArtFalse", true)
                    .put("whiteViewMaxDelta", 0).put("darkOpaqueWhiteInkControl", true)
                    .put("outsideAllowedRenderArgbChanges", 0)
            } finally {
                inpainter.close()
                assertClosed(inpainter)
            }
            scope.row.put("ownedHandlesClosed", true)
        }

    @Test
    fun nativeAotExitCancellationReachesCallerWithoutReturningAnOutputBitmap() =
        nativeTest("public-inpainter-cancellation-at-native-exit") { scope ->
            val opaque = scope.keep(gradientGlyphPage())
            val alpha = scope.keep(alphaCoverageOf(opaque))
            val white = scope.keep(whiteComposite(alpha))
            assertArrayEquals(pixels(opaque), pixels(white))
            listOf(opaque, alpha, white).forEach(scope::guard)
            // Synthetic component mask: this tests cancellation at the real native boundary,
            // rather than Detector/OCR segmentation accuracy or translation quality.
            val rawMask = scope.keep(
                Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888).apply {
                    density = alpha.density
                    eraseColor(Color.BLACK)
                    Canvas(this).drawRect(30f, 55f, 360f, 120f, Paint().apply { color = Color.WHITE })
                },
            )
            scope.guard(rawMask)
            fun region(): TextRegion {
                val line = TextLine(listOf(Pt(20f, 40f), Pt(420f, 40f), Pt(420f, 125f), Pt(20f, 125f)), 1f).apply {
                    direction = "h"
                    text = "こんにちは。"
                }
                return TextRegion(listOf(line), "h", cx = 220f, cy = 82.5f, boxW = 400f, boxH = 85f).apply {
                    translatedText = "안녕"
                }
            }
            val inpainter = Inpainter(
                scope.models.file("mit_aot_fixed512.ncnn.param").path,
                InpainterConfig(method = "aot", tileSize = 768, maskDilate = 8f, bboxPad = 8),
            )
            try {
                val controlRegion = region()
                val removal = scope.keep(inpainter.buildMask(alpha, listOf(controlRegion), rawMask))
                val removalPixels = pixels(removal)
                assertTrue(removalPixels.any(::maskOn))
                val control = scope.keep(scope.aot(1) { inpainter.inpaint(alpha, listOf(controlRegion), rawMask) })
                assertTrue("The alpha/gradient control must actually use AOT", controlRegion.onArt)
                assertTrue(assertOffMaskUnchanged(alpha, control, removalPixels) > 0)

                val parentJob = checkNotNull(currentCoroutineContext()[Job])
                val childJob = Job(parentJob)
                val cancellation = CancellationException("neutral AOT-exit cancellation")
                var exitCallbacks = 0
                var returnedInsideChild = false
                var observedCancellation: CancellationException? = null
                try {
                    scope.aot(
                        expectedCalls = 1,
                        onSuccessfulExit = {
                            exitCallbacks++
                            // EngineTrace invokes this synchronously after native rc0 and before
                            // Inpainter can cross its structured scope/output ownership boundary.
                            childJob.cancel(cancellation)
                        },
                    ) {
                        try {
                            withContext(childJob) {
                                val unexpected = inpainter.inpaint(alpha, listOf(region()), rawMask)
                                scope.keep(unexpected)
                                returnedInsideChild = true
                            }
                        } catch (caught: CancellationException) {
                            observedCancellation = caught
                        }
                    }
                    assertEquals("Cancellation must be injected at one actual rc0 exit", 1, exitCallbacks)
                    assertTrue("Only the private operation Job is cancelled", childJob.isCancelled)
                    assertTrue("The public Inpainter caller must receive cancellation", observedCancellation != null)
                    assertFalse(
                        "Inpainter may not successfully return a Bitmap after cancellation",
                        returnedInsideChild,
                    )
                    assertTrue(parentJob.isActive)
                    currentCoroutineContext().ensureActive()
                } finally {
                    childJob.cancel()
                    childJob.join()
                }
                scope.row.put("publicInpainterCalls", 2)
                    .put("cancellationAtActualAotRc0Exit", true).put("successfulExitCallbacks", exitCallbacks)
                    .put("cancellationReachedCaller", true).put("successfulBitmapReturnedAfterCancellation", false)
                    .put("parentOperationStillActive", true).put("rawMaskPreserved", true)
                    // The unreturned output is private engine state. No recycle-count claim is
                    // possible through this public API; its finally ownership is source-reviewed.
                    .put("unreturnedBitmapRecycleCountMeasured", false)
            } finally {
                inpainter.close()
                assertClosed(inpainter)
            }
            scope.row.put("ownedHandlesClosed", true)
        }

    private fun nativeTest(stage: String, block: suspend (NativeScope) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("transparentPipelineNative") == "true") {
            "Explicit transparentPipelineNative=true is required"
        }
        Executors.newSingleThreadExecutor { task -> Thread(task, "transparent-pipeline-native") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(240_000) {
                        val context = InstrumentationRegistry.getInstrumentation().targetContext
                        check(context.packageName == "app.yomitori.yakuyomi.audit")
                        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
                        NativeDiagnosticIdleGuard.requireIdle(context)
                        check(!TraceLog.isEnabled && EngineTrace.sink == null) {
                            "Native alpha test requires diagnostics already off; it never stops the user's trace"
                        }
                        val models = Models(context)
                        val scope = NativeScope(models)
                        var failure: Throwable? = null
                        try {
                            block(scope)
                            scope.row.put("passed", true)
                        } catch (caught: Throwable) {
                            failure = caught
                            throw caught
                        } finally {
                            val cleanupErrors = mutableListOf<Throwable>()
                            fun verify(action: () -> Unit) {
                                try {
                                    action()
                                } catch (caught: Throwable) {
                                    cleanupErrors += caught
                                }
                            }
                            verify { scope.verifySources() }
                            verify { scope.closeBitmaps() }
                            verify { models.verifyUnchanged() }
                            verify { check(!TraceLog.isEnabled && EngineTrace.sink == null) }
                            verify { NativeDiagnosticIdleGuard.requireIdle(context) }
                            scope.row.put("stage", stage).put("networkCallsByTest", 0)
                                .put("preferenceWritesByTest", 0).put("imageFilesByTest", 0)
                                .put("ownedBitmapCount", scope.bitmapCount)
                                .put("sourceArgbMetadataAndPngPreserved", cleanupErrors.isEmpty())
                                .put("modelHashesPreserved", models.unchanged)
                                .put("cleanupFailures", cleanupErrors.size)
                                .put("passed", failure == null && cleanupErrors.isEmpty())
                            verify {
                                InstrumentationRegistry.getInstrumentation().sendStatus(
                                    2,
                                    Bundle().apply { putString("transparentPipelineEvidence", scope.row.toString()) },
                                )
                            }
                            val primary = failure
                            if (primary != null) {
                                cleanupErrors.forEach(primary::addSuppressed)
                            } else if (cleanupErrors.isNotEmpty()) {
                                cleanupErrors.drop(1).forEach(cleanupErrors[0]::addSuppressed)
                                throw cleanupErrors[0]
                            }
                        }
                    }
                }
            }
    }

    private class NativeScope(val models: Models) {
        val row = JSONObject().put("translator", "neutral-scripted-korean-no-network")
        private val bitmaps = mutableListOf<Bitmap>()
        private val sources = mutableListOf<SourceSnapshot>()
        private var completedAotPairs = 0
        val bitmapCount: Int get() = bitmaps.size

        fun keep(bitmap: Bitmap): Bitmap = bitmap.also {
            if (bitmaps.none { existing -> existing === bitmap }) bitmaps += bitmap
        }

        fun guard(bitmap: Bitmap) {
            sources += SourceSnapshot(bitmap)
        }

        private fun keepResult(result: PageResult) {
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
        }

        fun translated(result: PageResult): PageResult.Translated {
            keepResult(result)
            check(result is PageResult.Translated) {
                "Opaque/alpha fixture must complete through the public Pipeline: ${result.javaClass.simpleName}"
            }
            return result
        }

        suspend fun <T> aot(
            expectedCalls: Int,
            onSuccessfulExit: (() -> Unit)? = null,
            operation: suspend () -> T,
        ): T {
            check(!TraceLog.isEnabled && EngineTrace.sink == null)
            var enter = 0
            var calls = 0
            var exits = 0
            var badRc = 0
            val observer: (String) -> Unit = { message ->
                // Count only Kotlin backend boundaries. No trace text/source/query is retained.
                if (message.startsWith("ncnn.inpaint.enter ")) enter++
                if (message.startsWith("ncnn.inpaint.call ")) calls++
                if (message.startsWith("ncnn.inpaint.exit rc=")) {
                    exits++
                    if (message != "ncnn.inpaint.exit rc=0") badRc++ else onSuccessfulExit?.invoke()
                }
            }
            EngineTrace.sink = observer
            var failure: Throwable? = null
            try {
                val result = operation()
                // Acquire returned ownership before any trace assertion can fail.
                when (result) {
                    is Bitmap -> keep(result)
                    is PageResult -> {
                        keepResult(result)
                        // Record safe result metadata before native-count assertions can fail.
                        row.put("lastPageResultBeforeAotAssertion", safePageResultObservation(result))
                    }
                }
                val nativeObservation = JSONObject().put("expectedCalls", expectedCalls).put("enter", enter)
                    .put("call", calls).put("exit", exits).put("nonzeroRc", badRc)
                row.put("lastAotObservationBeforeAssertion", nativeObservation)
                if (result is PageResult) {
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        2,
                        Bundle().apply {
                            putString(
                                "transparentPipelineProgress",
                                JSONObject().put("stage", "before-aot-count-assertion")
                                    .put("pageResult", row.getJSONObject("lastPageResultBeforeAotAssertion"))
                                    .put("native", nativeObservation).toString(),
                            )
                        },
                    )
                }
                assertEquals("Fixture must exercise the declared actual AOT call count", expectedCalls, calls)
                assertEquals(expectedCalls, enter)
                assertEquals(expectedCalls, exits)
                assertEquals(0, badRc)
                completedAotPairs += exits
                row.put("completedAotBoundaryPairs", completedAotPairs)
                return result
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                val owned = EngineTrace.sink === observer
                if (owned) EngineTrace.sink = null
                if (!owned || EngineTrace.sink != null || TraceLog.isEnabled) {
                    val error = IllegalStateException("Scoped native trace ownership changed")
                    val primary = failure
                    if (primary == null) throw error else primary.addSuppressed(error)
                }
            }
        }

        /** No OCR/target strings, exception messages, URLs, credentials or pixel arrays. */
        private fun safePageResultObservation(result: PageResult): JSONObject {
            val observation = JSONObject()
            val analysis: PageAnalysis?
            when (result) {
                is PageResult.Translated -> {
                    observation.put("class", "Translated").put("state", "RENDER_COMPLETE")
                        .put("reasonCode", "NOT_APPLICABLE")
                    analysis = result.analysis
                    observation.put("stats", safeStats(result.stats))
                }
                is PageResult.Partial -> {
                    observation.put("class", "Partial").put("state", "OCR_PENDING")
                        .put("reasonCode", "PENDING_REGIONS_REMAIN")
                    analysis = result.analysis
                    observation.put("stats", safeStats(result.stats))
                }
                is PageResult.Skipped -> {
                    // Only exact engine-owned fixed reasons may cross the diagnostic boundary.
                    val reasonCode = when (result.reason) {
                        "No text detected" -> "NO_TEXT_DETECTED"
                        "All regions intentionally kept" -> "ALL_REGIONS_INTENTIONALLY_KEPT"
                        else -> "OTHER_SKIPPED_REASON_REDACTED"
                    }
                    observation.put("class", "Skipped").put("state", "SKIPPED")
                        .put("reasonCode", reasonCode)
                    analysis = result.analysis
                    observation.put("stats", safeStats(result.stats))
                }
                is PageResult.Failed -> {
                    // Failed.reason incorporates a cause message. Never export it verbatim.
                    observation.put("class", "Failed").put("state", "FAILED")
                        .put("reasonCode", "FAILED_MESSAGE_REDACTED")
                        .put("failureStage", result.failure?.stage?.name ?: "UNCLASSIFIED")
                        .put("failureKind", result.failure?.kind?.name ?: "UNCLASSIFIED")
                    analysis = null
                }
            }
            observation.put("analysisPresent", analysis != null)
            analysis?.let { pageAnalysis ->
                val regions = pageAnalysis.regions
                val lines = regions.flatMap { it.lines }
                val progress = pageAnalysis.progress
                observation.put("regionCount", regions.size).put("lineCount", lines.size)
                    .put("nonblankSourceLineCount", lines.count { it.text.isNotBlank() })
                    .put("translatedTargetRegionCount", regions.count { it.translatedText.isNotBlank() })
                    .put("preserveOriginalRegionCount", regions.count { it.preserveOriginal })
                    .put("onArtRegionCount", regions.count { it.onArt })
                    .put("horizontalRegionCount", regions.count { it.direction == "h" })
                    .put("nonrotatedRegionCount", regions.count { abs(it.angle) < 1f })
                    .put("rawMaskOnPixelCount", pixels(pageAnalysis.mask).count(::maskOn))
                    .put("progressPresent", progress != null)
                progress?.let { saved ->
                    observation.put("hasPending", saved.hasPending)
                        .put("noTextDetected", saved.noTextDetected)
                        .put("translatedRegionCount", saved.regions.count { it.state == RegionState.TRANSLATED })
                        .put("pendingRegionCount", saved.regions.count { it.state == RegionState.OCR_PENDING })
                        .put(
                            "intentionallyKeptRegionCount",
                            saved.regions.count {
                                it.state ==
                                    RegionState.INTENTIONALLY_KEPT
                            },
                        )
                        .put(
                            "intentionallyKeptLineCount",
                            saved.regions.sumOf {
                                it.lines.count { line -> line.intentionallyKept }
                            },
                        )
                }
            }
            return observation
        }

        private fun safeStats(stats: li.joye.yakuyomi.engine.PageStats): JSONObject = JSONObject()
            .put("lines", stats.lines).put("regions", stats.regions).put("translatedKept", stats.kept)
            .put("detectMs", stats.detectMs).put("ocrMs", stats.ocrMs).put("translateMs", stats.translateMs)
            .put("inpaintMs", stats.inpaintMs).put("renderMs", stats.renderMs)

        fun verifySources() = sources.forEach { it.verify() }

        fun closeBitmaps() = bitmaps.asReversed().forEach { if (!it.isRecycled) it.recycle() }
    }

    private class ScriptedTranslator : Translator {
        val batches = mutableListOf<List<String>>()
        override val translationContext = TranslationContext("6".repeat(64))

        override suspend fun translate(queries: List<String>): List<String> {
            check(queries.isNotEmpty() && queries.all { it.isNotBlank() })
            batches += queries.toList()
            return queries.map { "안녕" }
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
                "Required native model does not match its fixed role: ${expected.name}"
            }
            expected.name to file
        }
        private val before = files.mapValues { (_, file) -> file.length() to digest(file) }
        val dictionary: List<String> = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use {
            it.readLines()
        }
        var unchanged = false
            private set

        fun file(name: String): File = checkNotNull(files[name])

        fun verifyUnchanged() {
            assertEquals(
                "Every required model's bytes must survive the native test",
                before,
                files.mapValues { (_, file) ->
                    file.length() to
                        digest(file)
                },
            )
            unchanged = true
        }
    }

    private class SourceSnapshot(private val bitmap: Bitmap) {
        private val before = pixels(bitmap)
        private val encoded = png(bitmap)
        private val config = bitmap.config
        private val density = bitmap.density
        private val alpha = bitmap.hasAlpha()
        private val premultiplied = bitmap.isPremultiplied

        fun verify() {
            assertFalse(bitmap.isRecycled)
            assertEquals(config, bitmap.config)
            assertEquals(density, bitmap.density)
            assertEquals(alpha, bitmap.hasAlpha())
            assertEquals(premultiplied, bitmap.isPremultiplied)
            assertArrayEquals(before, pixels(bitmap))
            assertArrayEquals(encoded, png(bitmap))
        }
    }

    private data class ExpectedModel(val name: String, val bytes: Long, val sha256: String)

    companion object {
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

        private fun fixedRender() = RenderConfig(
            orientation = TextOrientation.HORIZONTAL, fontBorder = false,
            fontSizeMin = 30, fontSizeMax = 30, expandW = 1f, expandH = 1f,
            rowTrim = 0, colTrim = 0, fontScale = 1f, colorMode = "auto",
        )

        private fun completeHorizontalAnalysis(result: PageResult.Translated): PageAnalysis {
            val analysis = checkNotNull(result.analysis)
            val progress = checkNotNull(analysis.progress)
            assertFalse("Opaque control must not be an unresolved/intentional exclusion", progress.hasPending)
            assertTrue(progress.regions.isNotEmpty() && progress.regions.all { it.state == RegionState.TRANSLATED })
            assertEquals("Fixture must have one horizontal region", 1, analysis.regions.size)
            val region = analysis.regions.single()
            assertEquals("h", region.direction)
            assertTrue(abs(region.angle) < 1f)
            assertFalse(region.preserveOriginal)
            assertEquals("안녕", region.translatedText)
            assertTrue(region.lines.isNotEmpty() && region.lines.all { it.text.isNotBlank() })
            return analysis
        }

        private fun assertRegionsEquivalent(first: PageAnalysis, second: PageAnalysis) {
            assertEquals(first.regions.size, second.regions.size)
            for ((a, b) in first.regions.zip(second.regions)) {
                assertEquals(a.sourceText, b.sourceText)
                assertEquals(a.translatedText, b.translatedText)
                assertEquals(a.direction, b.direction)
                assertEquals(a.angle, b.angle, 0f)
                assertEquals(a.lines.size, b.lines.size)
                for ((one, two) in a.lines.zip(b.lines)) {
                    assertEquals(one.text, two.text)
                    assertEquals(one.direction, two.direction)
                    assertEquals(one.quad.size, two.quad.size)
                    for ((p, q) in one.quad.zip(two.quad)) {
                        assertEquals(p.x, q.x, 0f)
                        assertEquals(p.y, q.y, 0f)
                    }
                }
            }
        }

        /** Exhaustive A8 bitmap-layer control; this raster primitive does not bound Canvas text. */
        private fun exhaustiveBlackBitmapCoverageControl(scope: NativeScope): Int {
            val side = 256
            val source = scope.keep(
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
                    density = Bitmap.DENSITY_NONE
                    setPixels(IntArray(side * side) { (it / side) shl 24 }, 0, side, 0, 0, side, side)
                },
            )
            val opaqueBase = scope.keep(whiteComposite(source))
            val coverage = scope.keep(
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
                    density = Bitmap.DENSITY_NONE
                    setPixels(IntArray(side * side) { (it % side) shl 24 }, 0, side, 0, 0, side, side)
                },
            )
            val opaqueOver = scope.keep(checkNotNull(opaqueBase.copy(Bitmap.Config.ARGB_8888, true)))
            val alphaOver = scope.keep(checkNotNull(source.copy(Bitmap.Config.ARGB_8888, true)))
            Canvas(opaqueOver).drawBitmap(coverage, null, Rect(0, 0, side, side), null)
            Canvas(alphaOver).drawBitmap(coverage, null, Rect(0, 0, side, side), null)
            val alphaWhite = scope.keep(whiteComposite(alphaOver))
            val a = pixels(opaqueOver)
            val b = pixels(alphaWhite)
            val maximum = a.indices.maxOf { index -> maxRgbDelta(a[index], b[index]) }
            val changed = a.indices.count { a[it] != b[it] }
            val alphaOpaque = a.all { Color.alpha(it) == 255 } && b.all { Color.alpha(it) == 255 }
            scope.row.put(
                "exhaustiveBlackBitmapCoverageControl",
                JSONObject().put("primitive", "drawBitmap-black-layer")
                    .put("sourceAlphaCases", side).put("blackCoverageCases", side)
                    .put("pairs", side * side).put("maximumRgbDelta", maximum).put("changedPixels", changed)
                    .put("allWhiteViewAlphaOpaque", alphaOpaque),
            )
            assertTrue(alphaOpaque)
            assertTrue("Exhaustive black bitmap-overlay control must stay within one RGB quantum", maximum in 0..1)
            return maximum
        }

        /** One direct public Canvas draw. It never invokes Renderer or observes a product diff. */
        private fun independentBlackTextOver(source: Bitmap, region: TextRegion): Bitmap {
            check(region.direction == "h" && abs(region.angle) < 1f && region.translatedText == "안녕")
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.DEFAULT
                textSize = 30f
            }
            val fm = paint.fontMetrics
            val height = max(paint.textSize * 1.18f, fm.descent - fm.ascent + fm.leading)
            check(paint.measureText(region.translatedText) <= region.x1 - region.x0)
            check(height <= region.y1 - region.y0)
            val x = (region.x0 + region.x1) / 2f - paint.measureText(region.translatedText) / 2f
            val baseline = (region.y0 + region.y1) / 2f - height / 2f + (height - fm.descent - fm.ascent) / 2f
            val result = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
            var transferred = false
            try {
                Canvas(result).drawText(region.translatedText, x, baseline, paint)
                transferred = true
                return result
            } finally {
                if (!transferred) result.recycle()
            }
        }

        private fun maxRgbDelta(a: Int, b: Int): Int = maxOf(
            abs(Color.red(a) - Color.red(b)),
            abs(Color.green(a) - Color.green(b)),
            abs(Color.blue(a) - Color.blue(b)),
        )

        private fun safeRgbDiffMetrics(
            stage: String,
            a: IntArray,
            b: IntArray,
            width: Int,
            removal: IntArray,
            glyphs: IntArray,
        ): JSONObject {
            check(a.size == b.size && a.size == removal.size && a.size == glyphs.size)
            var changed = 0
            var maximumRgb = 0
            var maximumAlpha = 0
            var first = -1
            var insideRemoval = 0
            var glyphOnly = 0
            var outsideSupport = 0
            for (index in a.indices) {
                maximumRgb = max(maximumRgb, maxRgbDelta(a[index], b[index]))
                maximumAlpha = max(maximumAlpha, abs(Color.alpha(a[index]) - Color.alpha(b[index])))
                if (a[index] == b[index]) continue
                changed++
                if (first < 0) first = index
                when {
                    maskOn(removal[index]) -> insideRemoval++
                    Color.alpha(glyphs[index]) > 0 -> glyphOnly++
                    else -> outsideSupport++
                }
            }
            return JSONObject().put("stage", stage).put("changedPixels", changed)
                .put("maximumRgbDelta", maximumRgb).put("maximumAlphaDelta", maximumAlpha)
                .put("changedInsideActualRemoval", insideRemoval)
                .put("changedGlyphOutsideRemoval", glyphOnly).put("changedOutsideRemovalAndGlyph", outsideSupport)
                .put("firstDifferenceIndex", first)
                .put("firstDifferenceX", if (first >= 0) first % width else -1)
                .put("firstDifferenceY", if (first >= 0) first / width else -1)
                .put("firstExpectedArgb", if (first >= 0) a[first] else JSONObject.NULL)
                .put("firstActualArgb", if (first >= 0) b[first] else JSONObject.NULL)
        }

        /** Exact signed Canvas-order vector outside removal; every other pixel stays bitwise equal. */
        private fun canvasOrderViolations(
            actualOpaque: IntArray,
            actualAlphaWhite: IntArray,
            controlOpaque: IntArray,
            controlAlphaWhite: IntArray,
            removal: IntArray,
            glyphs: IntArray,
        ): Int {
            check(
                listOf(actualAlphaWhite, controlOpaque, controlAlphaWhite, removal, glyphs).all {
                    it.size == actualOpaque.size
                },
            )
            return actualOpaque.indices.count { index ->
                val allowCanvasOrder = !maskOn(removal[index]) && Color.alpha(glyphs[index]) > 0
                val expectedR = if (allowCanvasOrder) {
                    Color.red(controlOpaque[index]) -
                        Color.red(controlAlphaWhite[index])
                } else {
                    0
                }
                val expectedG = if (allowCanvasOrder) {
                    Color.green(controlOpaque[index]) -
                        Color.green(controlAlphaWhite[index])
                } else {
                    0
                }
                val expectedB = if (allowCanvasOrder) {
                    Color.blue(controlOpaque[index]) -
                        Color.blue(controlAlphaWhite[index])
                } else {
                    0
                }
                Color.alpha(actualOpaque[index]) != 255 || Color.alpha(actualAlphaWhite[index]) != 255 ||
                    Color.red(actualOpaque[index]) - Color.red(actualAlphaWhite[index]) != expectedR ||
                    Color.green(actualOpaque[index]) - Color.green(actualAlphaWhite[index]) != expectedG ||
                    Color.blue(actualOpaque[index]) - Color.blue(actualAlphaWhite[index]) != expectedB
            }
        }

        /** Independent one-line Canvas glyph support, fixed before observing rendered differences. */
        private fun independentGlyphSupport(page: Bitmap, region: TextRegion): Bitmap {
            check(region.direction == "h" && abs(region.angle) < 1f && !region.preserveOriginal)
            check(region.translatedText == "안녕")
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.DEFAULT
                textSize = 30f
            }
            val fm = paint.fontMetrics
            val lineHeight = max(paint.textSize * 1.18f, fm.descent - fm.ascent + fm.leading)
            check(paint.measureText(region.translatedText) <= region.x1 - region.x0)
            check(lineHeight <= region.y1 - region.y0)
            val x = (region.x0 + region.x1) / 2f - paint.measureText(region.translatedText) / 2f
            val baseline = (region.y0 + region.y1) / 2f - lineHeight / 2f + (lineHeight - fm.descent - fm.ascent) / 2f
            return Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawText(region.translatedText, x, baseline, paint)
                check(pixels(this).any { Color.alpha(it) > 0 })
            }
        }

        private fun assertFinalChangesBounded(source: Bitmap, output: Bitmap, removal: IntArray, glyphs: IntArray) {
            val before = pixels(source)
            val after = pixels(output)
            assertEquals(before.size, removal.size)
            assertEquals(before.size, glyphs.size)
            for (index in before.indices) {
                if (!maskOn(removal[index]) && Color.alpha(glyphs[index]) == 0) {
                    assertEquals(
                        "Final output may only change the actual removal or independently rasterized glyph support",
                        before[index],
                        after[index],
                    )
                }
            }
        }

        private fun assertOffMaskUnchanged(source: Bitmap, output: Bitmap, mask: IntArray): Int {
            val before = pixels(source)
            val after = pixels(output)
            var transparentOutside = 0
            for (index in before.indices) {
                if (!maskOn(mask[index])) {
                    assertEquals(
                        "AOT/boxfill may not flatten or change an OFF source pixel",
                        before[index],
                        after[index],
                    )
                    if (Color.alpha(before[index]) < 255) transparentOutside++
                }
            }
            return transparentOutside
        }

        private fun manualRegion(): TextRegion {
            val line = TextLine(listOf(Pt(70f, 45f), Pt(250f, 45f), Pt(250f, 115f), Pt(70f, 115f)), 1f).apply {
                direction = "h"
                text = "こんにちは。"
            }
            return TextRegion(listOf(line), "h", cx = 160f, cy = 80f, boxW = 180f, boxH = 70f).apply {
                translatedText =
                    "안녕"
            }
        }

        /**
         * Local glyph/contrast geometry from TranslationReliabilityTest's native Pipeline fixture,
         * recorded passing on A33 in the October22/23 audits. Keep the first line's full margins;
         * crop only the distant empty bottom and omit its second, unrelated line.
         * The former short, dark-gradient figure was correctly returned as OCR-pending.
         */
        private fun readablePipelineGlyphPage(): Bitmap =
            Bitmap.createBitmap(1200, 480, Bitmap.Config.ARGB_8888).apply {
                density = 241
                Canvas(this).apply {
                    drawColor(Color.WHITE)
                    drawRect(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        Paint().apply {
                            shader = LinearGradient(
                                0f,
                                0f,
                                1200f,
                                0f,
                                Color.WHITE,
                                Color.rgb(215, 215, 215),
                                Shader.TileMode.CLAMP,
                            )
                        },
                    )
                    drawText(
                        "こんにちは。",
                        150f,
                        250f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 72f
                            typeface = Typeface.DEFAULT
                        },
                    )
                }
            }

        private fun gradientGlyphPage(): Bitmap = Bitmap.createBitmap(1000, 160, Bitmap.Config.ARGB_8888).apply {
            density = 241
            val background = IntArray(width * height) { index ->
                val x = index % width
                val gray = if (x >= width - 16) 255 else 170 + 85 * x / (width - 17)
                Color.rgb(gray, gray, gray)
            }
            setPixels(background, 0, width, 0, 0, width, height)
            Canvas(this).drawText(
                "こんにちは。",
                20f,
                110f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 60f
                    typeface = Typeface.DEFAULT
                },
            )
        }

        private fun alphaCoverageOf(opaque: Bitmap): Bitmap {
            val source = pixels(opaque)
            val alpha = source.map { color ->
                check(
                    Color.alpha(color) == 255 && Color.red(color) == Color.green(color) &&
                        Color.red(color) == Color.blue(color),
                )
                (255 - Color.red(color)) shl 24
            }.toIntArray()
            return Bitmap.createBitmap(opaque.width, opaque.height, Bitmap.Config.ARGB_8888).apply {
                density = opaque.density
                setPixels(alpha, 0, width, 0, 0, width, height)
            }
        }

        private fun whiteComposite(source: Bitmap): Bitmap =
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
                density = source.density
                Canvas(this).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(source, 0f, 0f, null)
                }
            }

        private fun maskOn(color: Int): Boolean = (color and 0xFF) > 127

        private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

        private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }

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
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun assertClosed(owner: Any) {
            val field = owner.javaClass.getDeclaredField("ncnnHandle").apply { isAccessible = true }
            assertEquals("All test-owned native handles must close", 0L, field.getLong(owner))
        }
    }
}
