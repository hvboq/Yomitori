package mihon.translation

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.HorizontalOcrContextPolicy
import li.joye.yakuyomi.engine.HorizontalOcrCropPolicy
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TranslationContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Opt-in, paired native accuracy collection. Every existing source runs actual public recognize twice:
 * Uniform4, then diagnostic horizontal X4/Y2; both keep guarded32 and unsharp on.
 * A collection pass never means the candidate preserves accuracy or is ready for production.
 * The pinned earlier report identifies the already-correct 107; its errors are not ground truth.
 * No default change, extra controls, expected-dependent selection, or model selection.
 */
@RunWith(AndroidJUnit4::class)
class OcrAxisCropHoldoutDiagnosticTest {
    @Test
    fun fixedUniform4AndX4Y2CollectPublicResultsWithoutPromotion() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "ocr-axis-crop-holdout") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(15 * 60 * 1000L) { collect() }
                }
            }
    }

    private suspend fun collect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("ocrAxisCropHoldout") == "true") {
            "Explicit ocrAxisCropHoldout=true is required; missing opt-in is not a pass or skip"
        }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation is required" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Actual ARM64 process is required" }
        val inputName = checkNotNull(arguments.getString("ocrRightContextInput")) {
            "A pinned 40-file UUID input basename is required"
        }
        val input = OcrRightContextInputs(
            context,
            inputName,
            instrumentation.context.assets.open("ocr-right-context/input-manifest.json").use { it.readBytes() },
        )
        check(bytesSha(input.manifestBytes) == INPUT_MANIFEST_SHA)
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val reference = HistoricalReference(
            external,
            checkNotNull(arguments.getString("ocrAxisCropReference")) {
                "A pinned reference UUID basename is required"
            },
        )
        val cases = input.fixedCases() + rightContextGeneratedCases()
        check(cases.size == 120 && cases.map { it.id }.distinct().size == 120)
        check(cases.map { it.id }.toSet() == reference.rows.keys)
        check(cases.all { reference.rows.getValue(it.id).getString("expected") == it.expected })
        val baselineConfig = OcrConfig(
            horizontalContextPolicy = HorizontalOcrContextPolicy.GUARDED_32,
            horizontalCropPolicy = HorizontalOcrCropPolicy.UNIFORM,
            ocrUnsharp = true,
        )
        check(baselineConfig.horizontalContextPolicy == HorizontalOcrContextPolicy.GUARDED_32)
        check(OcrConfig().horizontalContextPolicy == HorizontalOcrContextPolicy.GUARDED_32)
        check(OcrConfig().horizontalCropPolicy == HorizontalOcrCropPolicy.UNIFORM)
        check(baselineConfig.ocrUnsharp && baselineConfig.useBicubic && baselineConfig.stripPad == 4)
        check(baselineConfig.textHeight == 48 && baselineConfig.ignoreBubble == 0)
        check(baselineConfig.concurrent && baselineConfig.concurrency == 8)
        check(baselineConfig.minProb == 0.5f && baselineConfig.minTextLength == 0)
        check(baselineConfig.ncnnMixed && baselineConfig.ncnnFp16Storage && baselineConfig.ncnnFp16Arith)
        val candidateConfig = baselineConfig.copy(
            horizontalCropPolicy = HorizontalOcrCropPolicy.X4_Y2_DIAGNOSTIC,
        )
        check(
            candidateConfig.copy(horizontalCropPolicy = baselineConfig.horizontalCropPolicy) == baselineConfig &&
                candidateConfig.ocrUnsharp &&
                candidateConfig.horizontalCropPolicy != baselineConfig.horizontalCropPolicy,
        ) {
            "Horizontal crop policy must be the only configuration intervention"
        }
        val shared = Injekt.get<TranslationEngineService>()
        val translations = Injekt.get<TranslationManager>()
        withTimeout(10_000) { translations.ensureRestored() }
        requireIdle(shared)
        val saved = OnlineReaderSettings(context)
        val output = File(external, "ocr-axis-crop-holdout-${UUID.randomUUID()}")
        check(output.mkdir() && output.canonicalFile == output.absoluteFile && output.parentFile == external)
        val reportFile = File(output, "result.json")
        val rows = JSONArray()
        val preflightRows = JSONArray()
        val started = SystemClock.elapsedRealtimeNanos()
        val threadId = Thread.currentThread().id
        val report = JSONObject().put("passed", false).put("diagnosticOnly", true)
            .put("collectionValidationPassed", false).put("accuracyGateIsPassCriterion", false)
            .put("boundedAccuracyGatePassed", JSONObject.NULL).put("promotionEligible", false)
            .put(
                "promotionScope",
                "Independent new neutral quote/comma/music controls are absent; promotion is always false",
            )
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("process64Bit", true)
            .put("javaThreadId", threadId).put("javaThreadName", Thread.currentThread().name)
            .put("androidTid", Process.myTid()).put("sameThreadSerialPublicCalls", true)
            .put("expectedSamples", 120).put("expectedPublicRecognizeCalls", 240)
            .put("inputBasename", inputName).put("pinnedInputFiles", input.sourceManifest)
            .put("inputManifestSha256", bytesSha(input.manifestBytes))
            .put("historicalReference", reference.description())
            .put("baselineConfig", configJson(baselineConfig)).put("candidateConfig", configJson(candidateConfig))
            .put("onlyConfigDifference", "horizontalCropPolicy: UNIFORM -> X4_Y2_DIAGNOSTIC")
            .put("conditionOrder", JSONArray(listOf("uniform4", "axisX4Y2")))
            .put("perSampleCandidateSelection", false).put("modelOutputRewrittenByTest", false)
            .put("testTextNormalization", "None; exact Unicode strings, no trim or whitespace replacement")
            .put("publicScope", "Actual Ocr.recognize including guarded context, correction, filtering and recovery")
            .put("rawInferenceObservations", false).put("directRawInferCalls", 0)
            .put("defaultPolicyChanged", false).put("newNeutralQuoteCommaMusicControlsIncluded", false)
            .put("actualSelectedMarginPerPublicCall", "Unmeasured; guarded32 may select legacy16 fallback")
            .put("actualRecoveryAttemptPerPublicCall", "Unmeasured; initial crop is independently preflighted")
            .put("initialCandidateCrop", "Horizontal X4/Y2 only when configured pad4 and current attempt pad4")
            .put(
                "unchangedCrops",
                "Vertical and recovery pads8/12 remain uniform; configured pad0 recovery4 is uniform",
            )
            .put("candidateTensorPrefixComparedWithBaseline", false)
            .put("staticMaximumForwardsPerSingleLineCall", 9)
            .put("staticMaximumForwardsFor240Calls", 2160)
            .put("staticBoundIsMeasuredCount", false)
            .put("staticBoundBasis", "At most 3 context/body forwards per crop, at most 3 recovery crops")
            .put("preflightRows", preflightRows)
            .put("totalNativeForwardCountKnown", false)
            .put("historicalIncorrectPredictionsAreGroundTruth", false)
            .put("clipTruthScope", "Original rendering text remains the truth for clipped controls")
            .put("sourcePixelHashLayout", "Row-major bytes A,R,G,B; independent of PNG encoding")
            .put("timingScope", "Fixed-order paired accuracy collection with checks; not a speed benchmark")
            .put("newImagesWritten", 0).put("ownedOutputFiles", JSONArray(listOf("result.json")))
            .put("modelFilesCopiedOrDownloaded", false).put("llmOrOtherNetworkCalls", false)
            .put("actualReaderActivity", false).put("signedRelease", false)
            .put("sharedEngineShutdownCalled", false).put("samples", rows)
        var failure: Throwable? = null
        var beforeModels = JSONArray()
        var baselineOcr: Ocr? = null
        var candidateOcr: Ocr? = null
        var baselineCalls = 0
        var candidateCalls = 0
        fun checkpoint() {
            val atomic = AtomicFile(reportFile)
            val stream = atomic.startWrite()
            try {
                stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
        fun status(phase: String) {
            val progress = JSONObject().put("phase", phase).put("samples", rows.length())
                .put("publicCallsCompleted", baselineCalls + candidateCalls).put("report", reportFile.absolutePath)
                .put("terminalReportSealed", phase == "COMPLETED" || phase == "FAILED")
            if (phase == "COMPLETED" || phase == "FAILED") {
                check(output.list()?.toSet() == setOf("result.json"))
                check(reportFile.isFile && reportFile.canonicalFile == reportFile.absoluteFile)
                check(reportFile.canonicalFile.parentFile == output && reportFile.length() in 1..2_097_152L)
                progress.put("ownedDirectory", output.name).put("canonicalBoundaryVerified", true)
                    .put(
                        "ownedFiles",
                        JSONArray().put(
                            JSONObject().put("name", "result.json").put("bytes", reportFile.length())
                                .put("sha256", diagnosticSha(reportFile)),
                        ),
                    )
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "OCR_AXIS_CROP_HOLDOUT=$progress",
                    )
                },
            )
        }
        try {
            saved.set(Injekt.get<TranslationPreferences>().liveTranslate, false)
            requireIdle(shared)
            report.put("temporaryLiveTranslateDisabled", true)
            val models = modelDirectory(context)
            beforeModels = fingerprintModels(models)
            check(beforeModels.toString() == reference.report.getJSONArray("modelsAfter").toString()) {
                "Pinned historical model fingerprints differ"
            }
            report.put("modelsBefore", beforeModels).put("sameSevenModelsAsHistoricalReference", true)
            checkpoint()
            status("STARTED")
            val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
            check(bytesSha(dictionaryBytes) == DICTIONARY_SHA)
            val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
            check(dictionary.size == 19_264)
            report.put("dictionaryRows", dictionary.size).put("dictionarySha256", bytesSha(dictionaryBytes))
            val param = File(models, "ocr_48px_ctc.ncnn.param")
            Ocr(param.path, dictionary, baselineConfig).use { baseline ->
                baselineOcr = baseline
                val baselineProbe = OcrDiagnosticProbe(baseline, dictionary)
                Ocr(param.path, dictionary, candidateConfig).use { candidate ->
                    candidateOcr = candidate
                    val candidateProbe = OcrDiagnosticProbe(candidate, dictionary)
                    val baselineBackend = baselineProbe.metadata()
                    val candidateBackend = candidateProbe.metadata()
                    check(baselineBackend.toString() == candidateBackend.toString())
                    val historicalBackend = reference.report.getJSONObject("candidateBackend")
                    val backendKeys = listOf(
                        "backend",
                        "nativeThreads",
                        "serialize",
                        "peElements",
                        "peFloat32LittleEndianSha256",
                    )
                    for (key in backendKeys) {
                        check(baselineBackend.get(key) == historicalBackend.get(key))
                    }
                    check(baselineBackend.getInt("nativeThreads") == 1)
                    check(baselineBackend.getString("backend") == "NCNN-mixed")
                    val selected = baselineProbe.selectedParam(param.path)
                    check(selected == candidateProbe.selectedParam(param.path))
                    check(File(selected.first).name == reference.report.getString("selectedParam"))
                    check(selected.second && reference.report.getBoolean("selectedMixed"))
                    check(File(selected.first).name == "ocr_48px_ctc_mixed.ncnn.param")
                    report.put("baselineBackend", baselineBackend).put("candidateBackend", candidateBackend)
                        .put("selectedParam", File(selected.first).name).put("selectedMixed", selected.second)
                        .put("twoPrivateOcrInstances", true).put("probeUsedOnlyForMetadataAndPreprocessing", true)
                        .put("backendAndPeMatchHistoricalReference", true)
                    val baselineContext = baseline.intentionalFilterContext
                    val candidateContext = candidate.intentionalFilterContext
                    check(
                        baselineContext == TranslationContext.forOcrPolicy(
                            baselineConfig.ignoreBubble,
                            baselineConfig.horizontalContextPolicy,
                            baselineConfig.horizontalCropPolicy,
                        ),
                    )
                    check(
                        candidateContext == TranslationContext.forOcrPolicy(
                            candidateConfig.ignoreBubble,
                            candidateConfig.horizontalContextPolicy,
                            candidateConfig.horizontalCropPolicy,
                        ),
                    )
                    val legacyContext = TranslationContext.forOcrPolicy(
                        0,
                        HorizontalOcrContextPolicy.LEGACY_16,
                    )
                    check(baselineContext != candidateContext)
                    check(baselineContext != legacyContext && candidateContext != legacyContext)
                    check(baselineContext.semanticFingerprint == GUARDED32_CONTEXT_SHA)
                    check(candidateContext.semanticFingerprint == AXIS_CROP_CONTEXT_SHA)
                    report.put("actualBaselineOcrContext", baselineContext.semanticFingerprint)
                        .put("actualCandidateOcrContext", candidateContext.semanticFingerprint)
                        .put("actualOcrContextsMatchExplicitPolicies", true)
                        .put("candidateContextDistinctFromBaselineAndLegacy", true)
                    // Observe the actual private crop method used by public recognize, then check
                    // candidate geometry against independent bounds (118 axis-aligned, 2 rotated).
                    // The rotated expectations were computed and frozen before candidate inference.
                    // Nominal32 CHW does not identify the public fallback/recovery attempt selected.
                    for (case in cases) {
                        currentCoroutineContext().ensureActive()
                        requireIdle(shared)
                        check(Thread.currentThread().id == threadId)
                        val drawing = case.open()
                        try {
                            val historical = reference.rows.getValue(case.id)
                            verifySource(case, drawing, historical)
                            val sourcePixels = diagnosticPixels(drawing.page)
                            val originalQuad = drawing.quad.map { Pt(it.x, it.y) }
                            val variant = OcrDiagnosticVariant("initialUniform4", 4, 32, true)
                            val axisVariant = variant.copy(name = "initialX4Y2")
                            val (baselineStrip, expandedUniform) = baselineProbe.crop(
                                drawing.page,
                                originalQuad,
                                variant,
                            )
                            try {
                                val (axisStrip, expandedAxis) = candidateProbe.crop(
                                    drawing.page,
                                    originalQuad,
                                    axisVariant,
                                )
                                try {
                                    val (expectedAxis, geometryBasis) = verifyCropGeometry(
                                        case.id,
                                        originalQuad,
                                        expandedAxis,
                                        drawing.page.width,
                                        drawing.page.height,
                                    )
                                    check(baselineStrip.height == 48 && axisStrip.height == 48)
                                    val baselinePixels = diagnosticPixels(baselineStrip)
                                    val axisPixels = diagnosticPixels(axisStrip)
                                    val tensor16 = baselineProbe.tensor(
                                        baselineStrip,
                                        variant.copy(name = "historical16", rightMargin = 16),
                                    )
                                    val tensor32 = baselineProbe.tensor(baselineStrip, variant)
                                    verifyHistoricalTensor(tensor16, expandedUniform, rawReference(historical, 16))
                                    verifyHistoricalTensor(tensor32, expandedUniform, rawReference(historical, 32))
                                    val axisTensor = candidateProbe.tensor(axisStrip, axisVariant)
                                    check(axisTensor.json().getBoolean("productionPreprocessParity"))
                                    check(axisTensor.width == axisStrip.width + 32 && axisTensor.height == 48)
                                    check(axisTensor.width / 4 - 1 in 1..2048)
                                    val (repeatStrip, repeatExpanded) = candidateProbe.crop(
                                        drawing.page,
                                        originalQuad,
                                        axisVariant,
                                    )
                                    try {
                                        check(repeatExpanded == expandedAxis)
                                        check(repeatStrip.width == axisStrip.width && repeatStrip.height == 48)
                                        check(axisPixels.contentEquals(diagnosticPixels(repeatStrip)))
                                        val repeated = candidateProbe.tensor(repeatStrip, axisVariant)
                                        check(axisTensor.chw.contentEquals(repeated.chw))
                                        check(diagnosticFloatSha(axisTensor.chw) == diagnosticFloatSha(repeated.chw))
                                    } finally {
                                        repeatStrip.recycle()
                                    }
                                    check(baselinePixels.contentEquals(diagnosticPixels(baselineStrip)))
                                    check(axisPixels.contentEquals(diagnosticPixels(axisStrip)))
                                    check(sourcePixels.contentEquals(diagnosticPixels(drawing.page)))
                                    check(drawing.quad == originalQuad)
                                    preflightRows.put(
                                        JSONObject().put("id", case.id)
                                            .put("sourcePixelsArgbSha256", pixelsSha(sourcePixels))
                                            .put("quad", quadJson(originalQuad))
                                            .put("uniformExpandedQuad", quadJson(expandedUniform))
                                            .put("axisExpandedQuad", quadJson(expandedAxis))
                                            .put("independentExpectedAxisQuad", quadJson(expectedAxis))
                                            .put("geometryExpectationBasis", geometryBasis)
                                            .put("geometryTolerancePixels", GEOMETRY_FLOAT_TOLERANCE.toDouble())
                                            .put("historical16", tensor16.json()).put("historical32", tensor32.json())
                                            .put("axisInitialNominal32", axisTensor.json())
                                            .put("uniformStripWidth", baselineStrip.width)
                                            .put("axisStripWidth", axisStrip.width).put("stripHeight", 48)
                                            .put("axisStripArgbSha256", pixelsSha(axisPixels))
                                            .put("independentCandidateGeometryVerified", true)
                                            .put("repeatAxisCropAndTensorExact", true)
                                            .put("sourcePixelsAndGeometryUnchanged", true)
                                            .put("candidateComparedToBaselinePrefix", false)
                                            .put("selectedPublicMarginOrRecoveryMeasured", false),
                                    )
                                } finally {
                                    axisStrip.recycle()
                                }
                            } finally {
                                baselineStrip.recycle()
                            }
                        } finally {
                            drawing.page.recycle()
                        }
                    }
                    check(preflightRows.length() == 120 && baselineCalls == 0 && candidateCalls == 0)
                    val geometryBases = (0 until preflightRows.length()).map {
                        preflightRows.getJSONObject(it).getString("geometryExpectationBasis")
                    }
                    check(geometryBases.count { it == "axis-aligned-minmax-x4-y2" } == 118)
                    check(geometryBases.count { it == "frozen-rotated-double-projection-x4-y2" } == 2)
                    check(baselineProbe.explicitNativeCalls == 0 && candidateProbe.explicitNativeCalls == 0)
                    check(baselineProbe.privateInferParityCalls == 0 && candidateProbe.privateInferParityCalls == 0)
                    report.put("all120ArgbAndQuadPreflightPassed", true)
                        .put("all240Historical16And32ChwHashesPreflightPassed", true)
                        .put("all120IndependentCandidateGeometryPreflightPassed", true)
                        .put("axisAlignedGeometrySamples", 118).put("rotatedGeometrySamples", 2)
                        .put("all120AxisCropAndChwReproductionPassed", true)
                        .put("preflightHadNoModelForwards", true)
                    checkpoint()
                    status("TENSOR_PREFLIGHT_COMPLETED")
                    for ((index, case) in cases.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        requireIdle(shared)
                        check(Thread.currentThread().id == threadId)
                        val historical = reference.rows.getValue(case.id)
                        val drawing = case.open()
                        try {
                            val sourcePixels = diagnosticPixels(drawing.page)
                            val sourceHash = pixelsSha(sourcePixels)
                            val quad = drawing.quad.map { Pt(it.x, it.y) }
                            check(historical.getString("group") == case.group)
                            check(historical.getInt("sourceWidth") == drawing.page.width)
                            check(historical.getInt("sourceHeight") == drawing.page.height)
                            check(historical.getString("sourcePixelsArgbSha256") == sourceHash)
                            check(historical.getJSONArray("quad").toString() == quadJson(quad).toString())
                            val historicalText = historical.getJSONObject("candidatePublic").getString("text")
                            val row = JSONObject().put("sample", index).put("id", case.id).put("group", case.group)
                                .put("expected", case.expected).put("metadata", case.metadata)
                                .put("sourceWidth", drawing.page.width).put("sourceHeight", drawing.page.height)
                                .put("sourcePixelsArgbSha256", sourceHash).put("quad", quadJson(quad))
                                .put("historicalUniform4Text", historicalText)
                                .put("historicalCorrect", historicalText == case.expected)
                                .put("historicalSourceAndGeometryMatch", true)
                            rows.put(row)
                            val baselineLine = TextLine(quad.map { Pt(it.x, it.y) }, 1f)
                            val baselineStart = SystemClock.elapsedRealtimeNanos()
                            val baselineKept = baseline.recognize(drawing.page, listOf(baselineLine))
                            baselineCalls++
                            row.put("uniform4", publicJson(baselineLine, baselineKept, case.expected, baselineStart))
                            check(
                                baselineLine.quad == quad && sourcePixels.contentEquals(diagnosticPixels(drawing.page)),
                            )
                            val candidateLine = TextLine(quad.map { Pt(it.x, it.y) }, 1f)
                            val candidateStart = SystemClock.elapsedRealtimeNanos()
                            val candidateKept = candidate.recognize(drawing.page, listOf(candidateLine))
                            candidateCalls++
                            row.put(
                                "axisX4Y2",
                                publicJson(candidateLine, candidateKept, case.expected, candidateStart),
                            )
                            check(baselineKept.isEmpty() && candidateKept.isEmpty()) {
                                "Intentional filtering is disabled"
                            }
                            check(candidateLine.quad == quad && drawing.quad == quad)
                            check(sourcePixels.contentEquals(diagnosticPixels(drawing.page)))
                            val historicalDirection = historical.getJSONObject("candidatePublic").getString("direction")
                            row.put("baselineHistoricalPredictionReproduced", baselineLine.text == historicalText)
                                .put(
                                    "baselineHistoricalDirectionReproduced",
                                    baselineLine.direction == historicalDirection,
                                )
                                .put("sourcePixelsAndGeometryUnchanged", true)
                                .put(
                                    "historicalCorrectRegressed",
                                    historicalText == case.expected && candidateLine.text != case.expected,
                                )
                                .put(
                                    "historicalErrorImproved",
                                    historicalText != case.expected && candidateLine.text == case.expected,
                                )
                                .put(
                                    "incorrectOutputChanged",
                                    baselineLine.text != case.expected && candidateLine.text != case.expected &&
                                        baselineLine.text != candidateLine.text,
                                )
                            checkpoint()
                            if ((index + 1) % 10 == 0) status("COLLECTING")
                        } finally {
                            drawing.page.recycle()
                        }
                    }
                    check(baselineProbe.explicitNativeCalls == 0 && candidateProbe.explicitNativeCalls == 0)
                    check(baselineProbe.privateInferParityCalls == 0 && candidateProbe.privateInferParityCalls == 0)
                }
            }
            check(rows.length() == 120 && baselineCalls == 120 && candidateCalls == 120)
            val summary = summarize(rows)
            report.put("summary", summary)
            val predictionsReproduced = (0 until rows.length()).all {
                rows.getJSONObject(it).getBoolean("baselineHistoricalPredictionReproduced")
            }
            check(predictionsReproduced) {
                "Guarded32 historical public predictions changed; retain the collected rows"
            }
            val directionsReproduced = (0 until rows.length()).all {
                rows.getJSONObject(it).getBoolean("baselineHistoricalDirectionReproduced")
            }
            check(directionsReproduced)
            check(summary.getInt("uniform4Exact") == 107 && summary.getInt("historicalCorrect") == 107)
            check(summary.getJSONObject("historicalErrors").getInt("samples") == 13)
            check(summary.getJSONObject("historicalErrors").getInt("uniqueSourcePixelHashes") == 11)
            check(summary.getJSONObject("historicalErrors").getInt("clippedSamples") == 3)
            val gate = summary.getInt("historicalCorrectRegressions") == 0 &&
                summary.getInt("historicalErrorsImproved") > 0
            report.put("boundedAccuracyGatePassed", gate)
                .put(
                    "boundedAccuracyGateDefinition",
                    "Preserve all 107 historical correct strings and improve at least one error",
                )
                .put("promotionEligible", false)
                .put("promotionBlockedByMissingIndependentNewNeutralControls", true)
                .put("collectionValidationPassed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failureClass", error.javaClass.name)
        } finally {
            fun cleanup(label: String, action: () -> Unit) {
                try {
                    action()
                    report.put(label, true)
                } catch (error: Throwable) {
                    report.put(label, false).put("${label}FailureClass", error.javaClass.name)
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            cleanup("privateNativeOcrInstancesClosed") {
                listOfNotNull(baselineOcr, candidateOcr).forEach { ocr ->
                    val handle = Ocr::class.java.getDeclaredField("ncnnHandle").apply { isAccessible = true }
                        .getLong(ocr)
                    check(handle == 0L)
                }
            }
            cleanup("pinnedInputFilesUnchanged") { input.verifyUnchanged() }
            cleanup("historicalReferenceUnchanged") { reference.verifyUnchanged() }
            cleanup("existingSevenModelsUnchanged") {
                val after = fingerprintModels(modelDirectory(context))
                report.put("modelsAfter", after)
                check(beforeModels.length() == 7 && beforeModels.toString() == after.toString())
            }
            cleanup("sharedEngineStillAbsentAndQueuesIdle") { requireIdle(shared) }
            cleanup("temporaryLiveTranslateRestoredIncludingPresenceAndDisk") { saved.close() }
            report.put("publicUniform4CallsCompleted", baselineCalls)
                .put("publicX4Y2CallsCompleted", candidateCalls)
                .put("collectionValidationPassed", failure == null && report.optBoolean("collectionValidationPassed"))
                .put("passed", failure == null).put("wallMs", elapsed(started))
            report.put("finalReportWritten", true)
            cleanup("finalReportWritten") { checkpoint() }
            if (failure != null) {
                report.put("passed", false).put("collectionValidationPassed", false).put("failureReportRewritten", true)
                cleanup("failureReportRewritten") { checkpoint() }
            }
            // Last action: publish the SHA of the final bytes, with no later report mutation/write.
            // A status transport failure still fails JUnit; report.passed describes data/cleanup.
            status(if (failure == null) "COMPLETED" else "FAILED")
        }
        failure?.let { throw it }
        check(report.getBoolean("collectionValidationPassed"))
    }

    private fun publicJson(line: TextLine, kept: Set<TextLine>, expected: String, started: Long) =
        JSONObject().put("text", line.text).put("direction", line.direction)
            .put("matchesGroundTruth", line.text == expected).put("intentionallyKept", line in kept)
            .put("errorShape", errorShape(expected, line.text)).put("callWallMs", elapsed(started))

    private fun verifySource(case: OcrRightContextCase, drawing: OcrRightContextDrawing, prior: JSONObject) {
        check(prior.getString("group") == case.group && prior.getString("expected") == case.expected)
        check(prior.getInt("sourceWidth") == drawing.page.width && prior.getInt("sourceHeight") == drawing.page.height)
        check(prior.getString("sourcePixelsArgbSha256") == pixelsSha(diagnosticPixels(drawing.page)))
        check(prior.getJSONArray("quad").toString() == quadJson(drawing.quad).toString())
    }

    private fun rawReference(prior: JSONObject, margin: Int): JSONObject {
        val observations = prior.getJSONArray("rawObservations")
        val rows = (0 until observations.length()).map { observations.getJSONObject(it) }
            .filter { it.getInt("rightMargin") == margin }
        check(rows.size == 1)
        return rows.single()
    }

    private fun verifyHistoricalTensor(tensor: OcrDiagnosticTensor, expanded: List<Pt>, prior: JSONObject) {
        check(quadJson(expanded).toString() == prior.getJSONArray("expandedQuad").toString())
        val expected = prior.getJSONObject("tensor")
        check(tensor.width == expected.getInt("width") && tensor.height == expected.getInt("height"))
        check(tensor.height == 48 && tensor.chw.size == tensor.width * tensor.height * 3)
        check(tensor.json().getBoolean("productionPreprocessParity"))
        check(diagnosticFloatSha(tensor.chw) == expected.getString("float32LittleEndianSha256"))
    }

    private fun verifyCropGeometry(
        id: String,
        source: List<Pt>,
        expanded: List<Pt>,
        width: Int,
        height: Int,
    ): Pair<List<Pt>, String> {
        val frozenSource = ROTATED_SOURCE_QUADS[id]
        return if (frozenSource != null) {
            check(width == 1200 && height == 1600 && source == frozenSource)
            val expected = ROTATED_EXPECTED_QUADS.getValue(id)
            requireSameCorners(expanded, expected)
            expected to "frozen-rotated-double-projection-x4-y2"
        } else {
            verifyAxisCropGeometry(source, expanded, width, height) to "axis-aligned-minmax-x4-y2"
        }
    }

    /** Independent bounds oracle for the 118 axis-aligned inputs. Rotated inputs never use min/max. */
    private fun verifyAxisCropGeometry(source: List<Pt>, expanded: List<Pt>, width: Int, height: Int): List<Pt> {
        check(source.size == 4 && expanded.size == 4 && width > 0 && height > 0)
        check(source.all { it.x.isFinite() && it.y.isFinite() })
        val left = source.minOf { it.x }
        val right = source.maxOf { it.x }
        val top = source.minOf { it.y }
        val bottom = source.maxOf { it.y }
        check(right > left && bottom > top && right - left > bottom - top)
        requireSameCorners(source, rectangle(left, top, right, bottom))
        val expected = rectangle(
            (left - 4f).coerceIn(0f, (width - 1).toFloat()),
            (top - 2f).coerceIn(0f, (height - 1).toFloat()),
            (right + 4f).coerceIn(0f, (width - 1).toFloat()),
            (bottom + 2f).coerceIn(0f, (height - 1).toFloat()),
        )
        requireSameCorners(expanded, expected)
        return expected
    }

    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float): List<Pt> =
        listOf(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom))

    private fun requireSameCorners(actual: List<Pt>, expected: List<Pt>) {
        check(actual.size == 4 && expected.size == 4)
        val remaining = expected.toMutableList()
        for (point in actual) {
            val index = remaining.indexOfFirst {
                abs(point.x - it.x) <= GEOMETRY_FLOAT_TOLERANCE && abs(point.y - it.y) <= GEOMETRY_FLOAT_TOLERANCE
            }
            check(index >= 0) { "Initial crop differs from the independent geometry expectation" }
            remaining.removeAt(index)
        }
        check(remaining.isEmpty())
    }

    private fun errorShape(expected: String, actual: String): JSONObject {
        val punctuation = JSONArray()
        for (glyph in listOf('。', '、', '♪', '♫', '「', '」')) {
            val delta = actual.count { it == glyph } - expected.count { it == glyph }
            if (delta != 0) punctuation.put(JSONObject().put("glyph", glyph.toString()).put("extraMinusMissing", delta))
        }
        val kind = when {
            expected == actual -> "exact"
            actual.isEmpty() -> "empty"
            actual.length < expected.length -> "shorter-different"
            actual.length > expected.length -> "longer-different"
            else -> "same-length-different"
        }
        return JSONObject().put("kind", kind).put("expectedUtf16Length", expected.length)
            .put("actualUtf16Length", actual.length).put("punctuationCountDeltas", punctuation)
            .put("classificationScope", "Length and glyph-count observations; not an aligned edit or inferred cause")
    }

    private fun summarize(rows: JSONArray): JSONObject {
        val all = (0 until rows.length()).map { rows.getJSONObject(it) }
        fun counts(selected: List<JSONObject>): JSONObject = JSONObject().put("samples", selected.size)
            .put("historicalCorrect", selected.count { it.getBoolean("historicalCorrect") })
            .put("uniform4Exact", selected.count { it.getJSONObject("uniform4").getBoolean("matchesGroundTruth") })
            .put("axisX4Y2Exact", selected.count { it.getJSONObject("axisX4Y2").getBoolean("matchesGroundTruth") })
            .put("historicalCorrectRegressions", selected.count { it.getBoolean("historicalCorrectRegressed") })
            .put("historicalErrorsImproved", selected.count { it.getBoolean("historicalErrorImproved") })
            .put("incorrectOutputsChanged", selected.count { it.getBoolean("incorrectOutputChanged") })
        val historicalErrors = all.filter { !it.getBoolean("historicalCorrect") }
        val groups = JSONObject()
        all.groupBy { it.getString("group") }.forEach { (name, selected) -> groups.put(name, counts(selected)) }
        val duplicateErrors = JSONArray()
        historicalErrors.groupBy { it.getString("sourcePixelsArgbSha256") }.values.filter { it.size > 1 }.forEach {
            duplicateErrors.put(JSONArray(it.map { row -> row.getString("id") }))
        }
        val errorShapes = JSONObject()
        for (condition in listOf("uniform4", "axisX4Y2")) {
            val selected = all.filter { !it.getJSONObject(condition).getBoolean("matchesGroundTruth") }
            val kinds = selected.groupingBy {
                it.getJSONObject(condition).getJSONObject("errorShape").getString("kind")
            }.eachCount()
            errorShapes.put(
                condition,
                JSONObject(kinds),
            )
        }
        val clippedErrors = historicalErrors.filter { it.getJSONObject("metadata").optInt("rightClipPixels") > 0 }
        return counts(all).put("groups", groups).put("errorShapeCounts", errorShapes)
            .put("clippedControls", counts(all.filter { it.getJSONObject("metadata").optInt("rightClipPixels") > 0 }))
            .put(
                "historicalErrors",
                counts(historicalErrors)
                    .put(
                        "uniqueSourcePixelHashes",
                        historicalErrors.map { it.getString("sourcePixelsArgbSha256") }.toSet().size,
                    )
                    .put("clippedSamples", clippedErrors.size)
                    .put("clippedIds", JSONArray(clippedErrors.map { it.getString("id") }))
                    .put("duplicatePixelGroups", duplicateErrors),
            )
    }

    private fun requireIdle(shared: TranslationEngineService) {
        check(!shared.loading.value && !shared.warm.value) { "Shared audit engine must remain absent and idle" }
        check(TranslationBusy.active.value == 0 && Injekt.get<TranslationManager>().queueState.value.isEmpty())
        val downloads = Injekt.get<DownloadManager>()
        check(downloads.queueState.value.isEmpty() && !downloads.isRunning)
        val engine = TranslationEngineService::class.java.getDeclaredField("engine").apply { isAccessible = true }
            .get(shared)
        val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight").apply { isAccessible = true }
            .get(shared) as AtomicInteger
        check(engine == null && inFlight.get() == 0) { "Do not close or compete with a shared native engine" }
    }

    /** Context's /data/user alias may canonicalize to /data/data; only literal descendants are accepted. */
    private fun modelDirectory(context: Context): File {
        val files = context.filesDir.canonicalFile
        val data = File(context.applicationInfo.dataDir).canonicalFile
        check(files.isDirectory && files.name == "files" && files.parentFile == data)
        val directory = File(files, "models")
        check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile)
        return directory
    }

    private fun fingerprintModels(directory: File) = JSONArray(
        MODEL_NAMES.map { name ->
            val file = File(directory, name)
            check(file.canonicalFile == file.absoluteFile && file.parentFile == directory)
            check(file.isFile && file.length() > 0)
            JSONObject().put("name", name).put("bytes", file.length()).put("sha256", diagnosticSha(file))
        },
    )

    private fun configJson(config: OcrConfig) = JSONObject().put("dataClassValue", config.toString())
        .put("ocrUnsharp", config.ocrUnsharp).put("horizontalContextPolicy", config.horizontalContextPolicy.name)
        .put("horizontalCropPolicy", config.horizontalCropPolicy.name)
        .put("stripPad", config.stripPad).put("textHeight", config.textHeight).put("useBicubic", config.useBicubic)
        .put("minProb", config.minProb.toDouble()).put("minTextLength", config.minTextLength)
        .put("ignoreBubble", config.ignoreBubble).put("concurrent", config.concurrent)
        .put("concurrency", config.concurrency).put("ncnnFp16Storage", config.ncnnFp16Storage)
        .put("ncnnFp16Arith", config.ncnnFp16Arith).put("ncnnMixed", config.ncnnMixed)

    private class HistoricalReference(private val parent: File, basename: String) {
        private val directory = File(parent, basename)
        private val file = File(directory, "result.json")
        val report: JSONObject
        val rows: Map<String, JSONObject>

        init {
            check(basename.matches(Regex("ocr-axis-crop-reference-$UUID_PATTERN")))
            verifyUnchanged()
            report = JSONObject(file.readText(Charsets.UTF_8))
            check(report.getBoolean("passed") && report.getBoolean("accuracyGatePassed"))
            check(report.getString("candidatePolicy") == HorizontalOcrContextPolicy.GUARDED_32.name)
            val samples = report.getJSONArray("samples")
            check(samples.length() == 120)
            rows = (0 until samples.length()).map { samples.getJSONObject(it) }.associateBy { it.getString("id") }
            check(rows.size == 120)
            check(
                rows.values.count {
                    it.getJSONObject("candidatePublic").getString("text") == it.getString("expected")
                } == 107,
            )
        }

        fun verifyUnchanged() {
            check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile)
            check(directory.canonicalFile.parentFile == parent)
            check(directory.list()?.toSet() == setOf("result.json"))
            check(file.isFile && file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == directory)
            check(file.length() == REFERENCE_BYTES && diagnosticSha(file) == REFERENCE_SHA)
        }

        fun description() = JSONObject().put("basename", directory.name).put("name", "result.json")
            .put("bytes", REFERENCE_BYTES).put("sha256", REFERENCE_SHA).put("publicPolicy", "GUARDED_32")
            .put("historicalSamples", 120).put("historicalCorrect", 107).put("historicalErrors", 13)
    }

    private fun quadJson(quad: List<Pt>) = JSONArray(quad.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) })

    private fun pixelsSha(pixels: IntArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4096)
        var used = 0
        for (pixel in pixels) {
            bytes[used++] = (pixel ushr 24).toByte()
            bytes[used++] = (pixel ushr 16).toByte()
            bytes[used++] = (pixel ushr 8).toByte()
            bytes[used++] = pixel.toByte()
            if (used == bytes.size) {
                digest.update(bytes)
                used = 0
            }
        }
        digest.update(bytes, 0, used)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bytesSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun elapsed(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    private companion object {
        const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        const val INPUT_MANIFEST_SHA = "3178bfe3563ea01ce40423d7f9fb7579160797b42c190d416b29cb0c36ec015a"
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        const val REFERENCE_BYTES = 903_253L
        const val REFERENCE_SHA = "ed39996cafe9225ed8bc365390e78e3413c99da909668655e2c079e25d9dad66"
        const val GUARDED32_CONTEXT_SHA = "db1c82f4adad3c48d2755998063c4c7ffc4db906dc1887aa694e40bfa2d0f437"
        const val AXIS_CROP_CONTEXT_SHA = "4440b17da4808aa8ef94ba2a2185779c81fa19f1dc55d8ba7e956cc5c60a6186"

        // Fixed before any candidate observation; accommodates Float geometry rounding, not pixel errors.
        const val GEOMETRY_FLOAT_TOLERANCE = 0.001f
        val ROTATED_SOURCE_QUADS = mapOf(
            "saved-001-0" to listOf(
                Pt(201.72496032714844f, 285.36566162109375f),
                Pt(522.1810913085938f, 287.6383972167969f),
                Pt(521.7579345703125f, 347.3031311035156f),
                Pt(201.30181884765625f, 345.0303955078125f),
            ),
            "saved-001-1" to listOf(
                Pt(154.26983642578125f, 416.6173095703125f),
                Pt(942.7608032226562f, 418.7775573730469f),
                Pt(942.6085815429688f, 474.3501892089844f),
                Pt(154.11758422851562f, 472.1899108886719f),
            ),
        )

        // Pure host Double edge-projection minimum-area calculation, recorded in experiment-plan.json.
        // Local horizontal half-padding4 and perpendicular half-padding2; order is irrelevant.
        val ROTATED_EXPECTED_QUADS = mapOf(
            "saved-001-0" to listOf(
                Pt(525.7436530738637f, 349.33144887416125f),
                Pt(197.28772328222084f, 347.0019770787055f),
                Pt(197.7392449453184f, 283.33734376436985f),
                Pt(526.1951747369612f, 285.6668155598256f),
            ),
            "saved-001-1" to listOf(
                Pt(150.27533086575937f, 414.606358220589f),
                Pt(946.7662996441945f, 416.78852382255036f),
                Pt(946.6030871029905f, 476.3611405587078f),
                Pt(150.11211832455524f, 474.17897495674646f),
            ),
        )
        val MODEL_NAMES = listOf(
            "dbnet_detect.ncnn.param",
            "dbnet_detect.ncnn.bin",
            "ocr_48px_ctc.ncnn.param",
            "ocr_48px_ctc_mixed.ncnn.param",
            "ocr_48px_ctc.ncnn.bin",
            "mit_aot_fixed512.ncnn.param",
            "mit_aot_fixed512.ncnn.bin",
        )
    }
}
