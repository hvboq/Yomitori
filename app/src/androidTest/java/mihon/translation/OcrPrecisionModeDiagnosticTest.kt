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
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.OcrSelectionDiagnostics
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

/**
 * Supported mixed/base-fp32 public OCR modes on pinned neutral inputs, with separate bounded observers.
 * Collection integrity and candidate accuracy are separate outcomes; no mode is promoted by this test.
 * Render strings are independent truth, and nominal preprocessing alone is not selection evidence.
 */
@OptIn(InternalEngineApi::class)
@RunWith(AndroidJUnit4::class)
class OcrPrecisionModeDiagnosticTest {
    @Test
    fun supportedMixedAndFp32CollectPublicResultsWithoutPromotion() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "ocr-precision-mode") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(15 * 60 * 1000L) { collect() }
                }
            }
    }

    private suspend fun collect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("ocrPrecisionMode") == "true") {
            "Explicit ocrPrecisionMode=true is required; missing opt-in is not a pass or skip"
        }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation is required" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Actual ARM64 process is required" }
        val inputName = checkNotNull(arguments.getString("ocrRightContextInput"))
        val input = OcrRightContextInputs(
            context,
            inputName,
            instrumentation.context.assets.open("ocr-right-context/input-manifest.json").use { it.readBytes() },
        )
        check(bytesSha(input.manifestBytes) == INPUT_MANIFEST_SHA)
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val reference = HistoricalReference(
            external,
            checkNotNull(arguments.getString("ocrPrecisionModeReference")),
        )
        val cases = input.fixedCases() + rightContextGeneratedCases()
        check(cases.size == 120 && cases.map { it.id }.distinct().size == 120)
        check(cases.map { it.id }.toSet() == reference.rows.keys)
        check(cases.all { reference.rows.getValue(it.id).getString("expected") == it.expected })
        val config = OcrConfig()
        check(config.horizontalContextPolicy == HorizontalOcrContextPolicy.GUARDED_32)
        check(config.horizontalCropPolicy == HorizontalOcrCropPolicy.UNIFORM)
        check(config.ocrUnsharp && config.useBicubic && config.stripPad == 4 && config.textHeight == 48)
        check(config.ignoreBubble == 0 && config.concurrent && config.concurrency == 8)
        check(config.minProb == 0.5f && config.minTextLength == 0)
        check(config.ncnnMixed && config.ncnnFp16Storage && config.ncnnFp16Arith)
        val candidateConfig = config.copy(ncnnMixed = false, ncnnFp16Storage = false, ncnnFp16Arith = false)
        check(!candidateConfig.ncnnMixed && !candidateConfig.ncnnFp16Storage && !candidateConfig.ncnnFp16Arith)
        val shared = Injekt.get<TranslationEngineService>()
        withTimeout(10_000) { Injekt.get<TranslationManager>().ensureRestored() }
        requireIdle(shared)
        val saved = OnlineReaderSettings(context)
        val output = File(external, "ocr-precision-mode-${UUID.randomUUID()}")
        check(output.mkdir() && output.canonicalFile == output.absoluteFile && output.parentFile == external)
        val reportFile = File(output, "result.json")
        val samples = JSONArray()
        val preflightRows = JSONArray()
        val started = SystemClock.elapsedRealtimeNanos()
        val threadId = Thread.currentThread().id
        val report = JSONObject().put("schemaVersion", 1).put("passed", false).put("diagnosticOnly", true)
            .put("collectionValidationPassed", false).put("baselineHistoricalGatePassed", false)
            .put("preservationGate", JSONObject.NULL).put("promotionEligible", false)
            .put("qualityImprovementClaimed", false).put("candidateAccuracyIsCollectionPassRequirement", false)
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("process64Bit", true)
            .put("javaThreadId", threadId).put("javaThreadName", Thread.currentThread().name)
            .put("androidTid", Process.myTid()).put("sameThreadSerialPublicCalls", true)
            .put("expectedSamples", 120).put("expectedPublicRecognizeCalls", 240)
            .put("inputBasename", inputName).put("pinnedInputFiles", input.sourceManifest)
            .put("inputManifestSha256", bytesSha(input.manifestBytes))
            .put("historicalReference", reference.description())
            .put("baselineConfig", config.toString()).put("candidateConfig", candidateConfig.toString())
            .put("conditionOrder", JSONArray(listOf("baselineMixed", "candidateFp32")))
            .put("observerMaxLinesPerMode", 128).put("observerMaxEventsPerMode", 4096)
            .put("sameCallSelectionEvidence", "Separate actual-call events for each mode")
            .put("preflightNominalChwIsActualSelectionEvidence", false)
            .put("traceQuadHashScope", "Actual ordered expanded quad x,y float32 raw bits, little-endian SHA-256")
            .put("directRawInferCalls", 0).put("extraForwardsRequestedByTest", 0)
            .put("defaultPolicyOrFingerprintChangedByTest", false)
            .put("historicalIncorrectPredictionsAreGroundTruth", false)
            .put("testTextNormalization", "None; exact Unicode strings, direction and intentional-keep result")
            .put("sourcePixelHashLayout", "Row-major bytes A,R,G,B; independent of PNG encoding")
            .put("timingScope", "Fixed-order public duration includes tracing; not a speed benchmark")
            .put("newImagesWritten", 0).put("ownedOutputFiles", JSONArray(listOf("result.json")))
            .put("modelFilesCopiedOrDownloaded", false).put("llmOrOtherNetworkCalls", false)
            .put("actualReaderActivity", false).put("signedRelease", false).put("sharedEngineShutdownCalled", false)
            .put("nativeNetOptionsReadBack", false).put("actualKernelDtypesMeasured", false)
            .put("precisionAndSupportedGraphChangeTogether", true).put("singleKernelCausalityClaimed", false)
            .put("preflightRows", preflightRows).put("samples", samples)
        var failure: Throwable? = null
        var beforeModels = JSONArray()
        var baselineOcr: Ocr? = null
        var candidateOcr: Ocr? = null
        var baselineDiagnostics: OcrSelectionDiagnostics? = null
        var candidateDiagnostics: OcrSelectionDiagnostics? = null
        var baselineCalls = 0
        var candidateCalls = 0
        var completedSamples = 0
        fun checkpoint() {
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            check(bytes.size.toLong() in 1..MAX_REPORT_BYTES) { "Bounded trace report exceeded its byte cap" }
            val atomic = AtomicFile(reportFile)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
        fun status(phase: String) {
            val progress = JSONObject().put("phase", phase).put("samples", samples.length())
                .put("completedSamples", completedSamples)
                .put(
                    "passed",
                    if (phase == "COMPLETED") {
                        true
                    } else if (phase == "FAILED") {
                        false
                    } else {
                        JSONObject.NULL
                    },
                )
                .put("publicCallsCompleted", baselineCalls + candidateCalls).put("report", reportFile.absolutePath)
                .put("terminalReportSealed", phase == "COMPLETED" || phase == "FAILED")
            if (phase == "COMPLETED" || phase == "FAILED") {
                check(output.list()?.toSet() == setOf("result.json"))
                check(reportFile.isFile && reportFile.canonicalFile == reportFile.absoluteFile)
                check(reportFile.parentFile == output && reportFile.length() in 1..MAX_REPORT_BYTES)
                progress.put("ownedDirectory", output.name).put("canonicalBoundaryVerified", true)
                    .put(
                        "ownedFiles",
                        JSONArray().put(
                            JSONObject().put("name", "result.json").put("bytes", reportFile.length())
                                .put("sha256", diagnosticSha(reportFile)),
                        ),
                    )
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "OCR_PRECISION_MODE=$progress") })
        }
        try {
            saved.set(Injekt.get<TranslationPreferences>().liveTranslate, false)
            requireIdle(shared)
            report.put("temporaryLiveTranslateDisabled", true)
            val models = modelDirectory(context)
            beforeModels = fingerprintModels(models)
            check(beforeModels.toString() == reference.report.getJSONArray("modelsAfter").toString())
            report.put("modelsBefore", beforeModels).put("sameSevenModelsAsHistoricalReference", true)
            checkpoint()
            status("STARTED")
            val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
            check(bytesSha(dictionaryBytes) == DICTIONARY_SHA)
            val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
            check(dictionary.size == 19_264)
            report.put("dictionaryRows", dictionary.size).put("dictionarySha256", bytesSha(dictionaryBytes))
            val param = File(models, "ocr_48px_ctc.ncnn.param")
            val bin = File(models, "ocr_48px_ctc.ncnn.bin")
            val pinnedBin = (0 until beforeModels.length()).map { beforeModels.getJSONObject(it) }
                .single { it.getString("name") == bin.name }
            check(bin.length() == pinnedBin.getLong("bytes") && diagnosticSha(bin) == pinnedBin.getString("sha256"))
            report.put("commonWeights", pinnedBin).put("sameWeightsFileForBothModes", true)
                .put("weightsPathDerivation", "Both original model arguments map to original-param stem plus .bin")
            val baselineTrace = OcrSelectionDiagnostics(maxLines = 128, maxEvents = 4096)
            val trace = OcrSelectionDiagnostics(maxLines = 128, maxEvents = 4096)
            baselineDiagnostics = baselineTrace
            candidateDiagnostics = trace
            Ocr(param.path, dictionary, config, diagnostics = baselineTrace).use { off ->
                baselineOcr = off
                val baselineProbe = OcrDiagnosticProbe(off, dictionary)
                Ocr(param.path, dictionary, candidateConfig, diagnostics = trace).use { on ->
                    candidateOcr = on
                    val candidateProbe = OcrDiagnosticProbe(on, dictionary)
                    val backend = baselineProbe.metadata()
                    val candidateBackend = candidateProbe.metadata()
                    val historicalBackend = reference.report.getJSONObject("candidateBackend")
                    val backendKeys = listOf(
                        "backend",
                        "nativeThreads",
                        "serialize",
                        "peElements",
                        "peFloat32LittleEndianSha256",
                    )
                    for (key in backendKeys) {
                        check(backend.get(key) == historicalBackend.get(key))
                    }
                    for (key in backendKeys.filter { it != "backend" }) {
                        check(backend.get(key) == candidateBackend.get(key))
                    }
                    check(backend.getInt("nativeThreads") == 1 && !backend.getBoolean("serialize"))
                    check(backend.getString("backend") == "NCNN-mixed")
                    check(candidateBackend.getInt("nativeThreads") == 1 && !candidateBackend.getBoolean("serialize"))
                    check(candidateBackend.getString("backend") == "NCNN")
                    val selected = baselineProbe.selectedParam(param.path)
                    val candidateSelected = candidateProbe.selectedParam(param.path)
                    check(File(selected.first).name == "ocr_48px_ctc_mixed.ncnn.param" && selected.second)
                    check(File(candidateSelected.first) == param && !candidateSelected.second)
                    check(File(selected.first).name == reference.report.getString("selectedParam"))
                    check(reference.report.getBoolean("selectedMixed"))
                    val expectedContext = TranslationContext.forOcrPolicy(0, HorizontalOcrContextPolicy.GUARDED_32)
                    check(off.intentionalFilterContext == expectedContext)
                    check(on.intentionalFilterContext == expectedContext)
                    check(expectedContext.semanticFingerprint == GUARDED32_CONTEXT_SHA)
                    report.put(
                        "modes",
                        JSONObject().put("baselineMixed", modeMetadata(config, backend, selected))
                            .put("candidateFp32", modeMetadata(candidateConfig, candidateBackend, candidateSelected)),
                    ).put("twoPrivateOcrInstances", true)
                        .put("probeUsedOnlyForMetadataAndPreprocessing", true)
                        .put("actualOcrContext", expectedContext.semanticFingerprint)
                    for (case in cases) {
                        currentCoroutineContext().ensureActive()
                        requireIdle(shared)
                        check(Thread.currentThread().id == threadId)
                        val drawing = case.open()
                        try {
                            val prior = reference.rows.getValue(case.id)
                            verifySource(case, drawing, prior)
                            val pixels = diagnosticPixels(drawing.page)
                            val quad = drawing.quad.map { Pt(it.x, it.y) }
                            val variant = OcrDiagnosticVariant("historical32", 4, 32, true)
                            val (strip, expanded) = baselineProbe.crop(drawing.page, quad, variant)
                            try {
                                val tensor16 = baselineProbe.tensor(
                                    strip,
                                    variant.copy(name = "historical16", rightMargin = 16),
                                )
                                val tensor32 = baselineProbe.tensor(strip, variant)
                                verifyHistoricalTensor(tensor16, expanded, rawReference(prior, 16))
                                verifyHistoricalTensor(tensor32, expanded, rawReference(prior, 32))
                                check(pixels.contentEquals(diagnosticPixels(drawing.page)) && drawing.quad == quad)
                                preflightRows.put(
                                    JSONObject().put("id", case.id).put("sourcePixelsArgbSha256", pixelsSha(pixels))
                                        .put("quad", quadJson(quad)).put("expandedQuad", quadJson(expanded))
                                        .put("nominal16", tensor16.json()).put("nominal32", tensor32.json())
                                        .put("sourceAndGeometryUnchanged", true),
                                )
                            } finally {
                                strip.recycle()
                            }
                        } finally {
                            drawing.page.recycle()
                        }
                    }
                    check(preflightRows.length() == 120 && baselineCalls == 0 && candidateCalls == 0)
                    check(baselineProbe.explicitNativeCalls == 0 && baselineProbe.privateInferParityCalls == 0)
                    check(candidateProbe.explicitNativeCalls == 0 && candidateProbe.privateInferParityCalls == 0)
                    check(trace.snapshot().events.isEmpty() && trace.snapshot().callCount == 0L)
                    check(baselineTrace.snapshot().events.isEmpty() && baselineTrace.snapshot().callCount == 0L)
                    report.put("all120ArgbAndQuadPreflightPassed", true)
                        .put("all240Historical16And32ChwHashesPreflightPassed", true)
                        .put("preflightHadNoModelForwards", true)
                    checkpoint()
                    status("TENSOR_PREFLIGHT_COMPLETED")
                    for ((index, case) in cases.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        requireIdle(shared)
                        check(Thread.currentThread().id == threadId)
                        val drawing = case.open()
                        try {
                            val prior = reference.rows.getValue(case.id)
                            verifySource(case, drawing, prior)
                            val pixels = diagnosticPixels(drawing.page)
                            val quad = drawing.quad.map { Pt(it.x, it.y) }
                            val row = JSONObject().put("sample", index).put("id", case.id).put("group", case.group)
                                .put("expected", case.expected).put("metadata", case.metadata)
                                .put("sourceWidth", drawing.page.width).put("sourceHeight", drawing.page.height)
                                .put("sourcePixelsArgbSha256", pixelsSha(pixels)).put("quad", quadJson(quad))
                                .put(
                                    "historicalCorrect",
                                    prior.getJSONObject("candidatePublic").getString("text") == case.expected,
                                )
                            samples.put(row)
                            report.put("currentSample", index).put("currentId", case.id)
                            val baselineLine = TextLine(quad.map { Pt(it.x, it.y) }, 1f)
                            var callStarted = SystemClock.elapsedRealtimeNanos()
                            val baselineKept = off.recognize(drawing.page, listOf(baselineLine))
                            baselineCalls++
                            val baselineResult = publicJson(baselineLine, baselineKept, case.expected, callStarted)
                            row.put("baselineMixed", baselineResult)
                            captureCall(baselineResult, baselineTrace, baselineCalls, baselineLine, baselineKept, prior)
                            val historicalPublic = prior.getJSONObject("candidatePublic")
                            check(baselineLine.text == historicalPublic.getString("text"))
                            check(baselineLine.direction == historicalPublic.getString("direction"))
                            check((baselineLine in baselineKept) == historicalPublic.getBoolean("intentionallyKept"))
                            row.put("historicalPublicTextDirectionAndKeepReproduced", true)
                            check(baselineLine.quad == quad && pixels.contentEquals(diagnosticPixels(drawing.page)))
                            val candidateLine = TextLine(quad.map { Pt(it.x, it.y) }, 1f)
                            callStarted = SystemClock.elapsedRealtimeNanos()
                            val candidateKept = on.recognize(drawing.page, listOf(candidateLine))
                            candidateCalls++
                            val candidateResult = publicJson(candidateLine, candidateKept, case.expected, callStarted)
                            row.put("candidateFp32", candidateResult)
                            captureCall(candidateResult, trace, candidateCalls, candidateLine, candidateKept, prior)
                            val historicalCorrect = row.getBoolean("historicalCorrect")
                            val candidateCorrect = candidateLine.text == case.expected
                            row.put("candidatePublicTextChanged", baselineLine.text != candidateLine.text)
                                .put("candidateDirectionChanged", baselineLine.direction != candidateLine.direction)
                                .put(
                                    "candidateIntentionalKeepChanged",
                                    (baselineLine in baselineKept) != (candidateLine in candidateKept),
                                )
                                .put("candidateImprovedHistoricalError", !historicalCorrect && candidateCorrect)
                                .put("candidateRegressedHistoricalCorrect", historicalCorrect && !candidateCorrect)
                            check(candidateLine.quad == quad && drawing.quad == quad)
                            check(pixels.contentEquals(diagnosticPixels(drawing.page)))
                            row.put("sourcePixelsAndQuadUnchanged", true)
                                .put("sampleValidationPassed", true)
                            completedSamples++
                            checkpoint()
                        } finally {
                            drawing.page.recycle()
                        }
                    }
                    check(baselineCalls == 120 && candidateCalls == 120 && completedSamples == 120)
                    check(samples.length() == 120)
                    check(baselineProbe.explicitNativeCalls == 0 && baselineProbe.privateInferParityCalls == 0)
                    check(candidateProbe.explicitNativeCalls == 0 && candidateProbe.privateInferParityCalls == 0)
                    val snapshot = trace.snapshot()
                    val baselineSnapshot = baselineTrace.snapshot()
                    requireCompleteTrace(snapshot, 120)
                    requireCompleteTrace(baselineSnapshot, 120)
                    report.put(
                        "observerSnapshots",
                        JSONObject().put("baselineMixed", snapshotJson(baselineSnapshot))
                            .put("candidateFp32", snapshotJson(snapshot)),
                    )
                    val summary = summarize(samples, baselineSnapshot.events, snapshot.events)
                    report.put("summary", summary)
                    check(summary.getInt("historicalCorrect") == 107 && summary.getInt("baselineCorrect") == 107)
                    check(summary.getInt("historicalPublicReproduced") == 120)
                    check(summary.getInt("historicalErrors") == 13 && summary.getInt("historicalErrorUniqueArgb") == 11)
                    report.put("preservationGate", summary.getInt("historicalCorrectRegressions") == 0)
                    report.put("baselineHistoricalGatePassed", true).put("collectionValidationPassed", true)
                }
            }
        } catch (error: Throwable) {
            failure = error
            report.put("failureClass", error.javaClass.name)
            val collectors = listOf("baselineMixed" to baselineDiagnostics, "candidateFp32" to candidateDiagnostics)
            for ((name, collector) in collectors) {
                if (collector == null) continue
                try {
                    val snapshot = collector.snapshot()
                    val assigned = (0 until samples.length()).mapNotNull { index ->
                        samples.getJSONObject(index).optJSONObject(name)?.takeIf { row -> row.has("events") }
                            ?.getLong("publicCallOrdinal")
                    }.toSet()
                    report.put("${name}SnapshotAtFailure", snapshotJson(snapshot))
                        .put(
                            "${name}UnassignedEventsAtFailure",
                            JSONArray(
                                snapshot.events.filter { event -> event.callOrdinal !in assigned }.map(::eventJson),
                            ),
                        )
                } catch (captureError: Throwable) {
                    error.addSuppressed(captureError)
                    report.put("${name}FailureTraceCaptureFailureClass", captureError.javaClass.name)
                }
            }
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
                val instances = listOfNotNull(baselineOcr, candidateOcr)
                report.put("privateOcrInstancesCreated", instances.size)
                for (ocr in instances) {
                    val handle = Ocr::class.java.getDeclaredField("ncnnHandle").apply { isAccessible = true }
                        .getLong(ocr)
                    check(handle == 0L)
                }
            }
            baselineOcr = null
            candidateOcr = null
            baselineDiagnostics = null
            candidateDiagnostics = null
            report.put("collectorOwnedReferencesReleased", true)
                .put(
                    "collectorLifecycleScope",
                    "Two memory-only bounded collectors; no close API/global registration; no GC proof",
                )
            cleanup("pinnedInputFilesUnchanged") { input.verifyUnchanged() }
            cleanup("historicalReferenceUnchanged") { reference.verifyUnchanged() }
            cleanup("existingSevenModelsUnchanged") {
                val after = fingerprintModels(modelDirectory(context))
                report.put("modelsAfter", after)
                check(beforeModels.length() == 7 && beforeModels.toString() == after.toString())
            }
            cleanup("sharedEngineStillAbsentAndQueuesIdle") { requireIdle(shared) }
            cleanup("temporaryLiveTranslateRestoredIncludingPresenceAndDisk") { saved.close() }
            report.put("publicBaselineCallsCompleted", baselineCalls)
                .put("publicCandidateCallsCompleted", candidateCalls)
                .put("completedSamples", completedSamples)
                .put("passed", failure == null).put("wallMs", elapsed(started))
                .put("collectionValidationPassed", failure == null && report.optBoolean("collectionValidationPassed"))
                .put(
                    "baselineHistoricalGatePassed",
                    failure == null && report.optBoolean("baselineHistoricalGatePassed"),
                )
                .put("finalReportWritten", true)
            cleanup("finalReportWritten") { checkpoint() }
            if (failure != null) {
                report.put("passed", false).put("collectionValidationPassed", false).put("failureReportRewritten", true)
                cleanup("failureReportRewritten") { checkpoint() }
            }
            try {
                status(if (failure == null) "COMPLETED" else "FAILED")
            } catch (statusError: Throwable) {
                failure?.addSuppressed(statusError) ?: run { failure = statusError }
            }
        }
        failure?.let { throw it }
        check(report.getBoolean("collectionValidationPassed") && report.getBoolean("baselineHistoricalGatePassed"))
    }

    private fun modeMetadata(config: OcrConfig, backend: JSONObject, selected: Pair<String, Boolean>) = JSONObject()
        .put("requestedConfig", config.toString()).put("requestedNcnnMixed", config.ncnnMixed)
        .put("requestedFp16Storage", config.ncnnFp16Storage).put("requestedFp16Arith", config.ncnnFp16Arith)
        .put("backendMetadata", backend).put("selectedParam", File(selected.first).name)
        .put("selectedMixed", selected.second).put("nativeNetOptionsReadBack", false)
        .put("actualKernelDtypesMeasured", false)

    private fun captureCall(
        result: JSONObject,
        trace: OcrSelectionDiagnostics,
        calls: Int,
        line: TextLine,
        kept: Set<TextLine>,
        prior: JSONObject,
    ) {
        val snapshot = trace.snapshot()
        requireCompleteTrace(snapshot, calls)
        val events = snapshot.events.filter { it.callOrdinal == calls.toLong() }
        result.put("publicCallOrdinal", calls).put("events", JSONArray(events.map(::eventJson)))
        verifyCall(events, calls.toLong(), line, line in kept, prior)
        result.put("selectionSummary", selectionSummary(events)).put("sameCallFinalPublicResultVerified", true)
    }

    private fun publicJson(line: TextLine, kept: Set<TextLine>, expected: String, started: Long) =
        JSONObject().put("text", line.text).put("direction", line.direction)
            .put("matchesGroundTruth", line.text == expected).put("intentionallyKept", line in kept)
            .put("publicDurationMsIncludingTrace", elapsed(started))

    private fun requireCompleteTrace(snapshot: OcrSelectionDiagnostics.Snapshot, calls: Int) {
        check(snapshot.callCount == calls.toLong() && snapshot.lineCount == calls.toLong())
        check(snapshot.recordedLines == calls.toLong())
        check(snapshot.droppedLines == 0L && snapshot.droppedEvents == 0L && snapshot.recordingFailureCount == 0L)
        check(!snapshot.truncated && snapshot.events.size <= 4096)
        check(snapshot.events.all { it.callOrdinal in 1L..calls.toLong() })
    }

    private fun verifyCall(
        events: List<OcrSelectionDiagnostics.Event>,
        ordinal: Long,
        line: TextLine,
        intentionallyKept: Boolean,
        historical: JSONObject,
    ) {
        check(events.isNotEmpty() && events.all { it.callOrdinal == ordinal })
        check(events.count { it.type.name == "CALL_START" } == 1)
        check(events.count { it.type.name == "CALL_END" } == 1)
        val ends = events.filter { it.type.name == "LINE_END" }
        check(ends.size == 1)
        val end = ends.single()
        check(end.lineIndex == 0 && end.text == line.text && end.direction == line.direction)
        check(end.intentionallyKept == intentionallyKept && end.errorClass == null)
        check(end.accepted == line.text.isNotBlank())
        val inferred = events.filter { it.type.name == "INFER" }
        check(inferred.isNotEmpty() && inferred.size <= 9)
        check(
            inferred.all {
                it.lineIndex == 0 && it.role != null && it.errorClass == null &&
                    it.actualMargin in listOf(16, 32) && it.inputHeight == 48 &&
                    (it.inputWidth ?: 0) > 0 && it.probabilityBits != null &&
                    it.chwSha256?.matches(Regex("[a-f0-9]{64}")) == true
            },
        )
        check(events.none { it.errorClass != null })
        check(events.first().type.name == "CALL_START" && events.last().type.name == "CALL_END")
        inferred.forEachIndexed { index, event ->
            check(event.nativeCallsStarted?.toLong() == index + 1L)
            check(event.nativeCallsCompleted?.toLong() == index + 1L)
        }
        check(end.nativeCallsStarted?.toLong() == inferred.size.toLong())
        check(end.nativeCallsCompleted?.toLong() == inferred.size.toLong())
        val firstPrimary = inferred.single { it.attemptIndex == 0 && it.role?.name == "PRIMARY" }
        val firstTensor = rawReference(historical, checkNotNull(firstPrimary.actualMargin)).getJSONObject("tensor")
        check(firstPrimary.inputWidth == firstTensor.getInt("width"))
        check(firstPrimary.inputHeight == firstTensor.getInt("height"))
        check(firstPrimary.chwSha256 == firstTensor.getString("float32LittleEndianSha256"))
        val ordered = rawReference(historical, checkNotNull(firstPrimary.actualMargin)).getJSONArray("expandedQuad")
        val orderedBits = FloatArray(ordered.length() * 2) { index ->
            ordered.getJSONArray(index / 2).getDouble(index % 2).toFloat()
        }
        val initialCrop = events.single { it.type.name == "CROP" && it.attemptIndex == 0 }
        check(initialCrop.quadSha256 == diagnosticFloatSha(orderedBits))
        check(events.filter { it.type.name == "CONTEXT" }.all { it.selectedMargin in listOf(16, 32) })
        val starts = events.filter { it.type.name == "ATTEMPT_START" }
        check(starts.isNotEmpty() && starts.size <= 3 && starts.all { it.attemptIndex in 0..2 })
        check(starts.map { it.attemptIndex }.distinct().size == starts.size)
        check(
            events.filter { it.type.name == "ATTEMPT_END" }.map { it.attemptIndex }.toSet() ==
                starts.map { it.attemptIndex }.toSet(),
        )
    }

    private fun eventJson(event: OcrSelectionDiagnostics.Event) = JSONObject()
        .put("callOrdinal", event.callOrdinal).put("lineIndex", event.lineIndex).put("attemptIndex", event.attemptIndex)
        .put("type", event.type.name).put("role", event.role?.name ?: JSONObject.NULL)
        .put("reason", event.reason ?: JSONObject.NULL).put("stripPad", event.stripPad ?: JSONObject.NULL)
        .put("bicubic", event.bicubic ?: JSONObject.NULL).put("direction", event.direction ?: JSONObject.NULL)
        .put("stripWidth", event.stripWidth ?: JSONObject.NULL).put("stripHeight", event.stripHeight ?: JSONObject.NULL)
        .put("requestedMargin", event.requestedMargin ?: JSONObject.NULL)
        .put("actualMargin", event.actualMargin ?: JSONObject.NULL)
        .put("selectedMargin", event.selectedMargin ?: JSONObject.NULL)
        .put("stemEvaluation", event.stemEvaluation?.name ?: JSONObject.NULL).put("text", event.text ?: JSONObject.NULL)
        .put("probabilityBits", event.probabilityBits ?: JSONObject.NULL)
        .put("inputWidth", event.inputWidth ?: JSONObject.NULL).put("inputHeight", event.inputHeight ?: JSONObject.NULL)
        .put("chwSha256", event.chwSha256 ?: JSONObject.NULL).put("quadSha256", event.quadSha256 ?: JSONObject.NULL)
        .put("bodyMode", event.bodyMode ?: JSONObject.NULL).put("bodyEnd", event.bodyEnd ?: JSONObject.NULL)
        .put("originalText", event.originalText ?: JSONObject.NULL).put("bodyText", event.bodyText ?: JSONObject.NULL)
        .put("correctedText", event.correctedText ?: JSONObject.NULL).put("accepted", event.accepted ?: JSONObject.NULL)
        .put("intentionallyKept", event.intentionallyKept ?: JSONObject.NULL)
        .put("nativeCallsStarted", event.nativeCallsStarted ?: JSONObject.NULL)
        .put("nativeCallsCompleted", event.nativeCallsCompleted ?: JSONObject.NULL)
        .put("errorClass", event.errorClass ?: JSONObject.NULL)

    private fun snapshotJson(snapshot: OcrSelectionDiagnostics.Snapshot) = JSONObject()
        .put("callCount", snapshot.callCount).put("lineCount", snapshot.lineCount)
        .put("recordedLines", snapshot.recordedLines).put("droppedLines", snapshot.droppedLines)
        .put("droppedEvents", snapshot.droppedEvents).put("recordingFailureCount", snapshot.recordingFailureCount)
        .put("truncated", snapshot.truncated).put("eventCount", snapshot.events.size)

    private fun selectionSummary(events: List<OcrSelectionDiagnostics.Event>): JSONObject {
        val starts = events.filter { it.type.name == "ATTEMPT_START" }
        val accepted = events.filter { it.type.name == "ATTEMPT_END" && it.accepted == true }.lastOrNull()
        val lastAttempt = starts.last().attemptIndex
        val finalContext = events.lastOrNull { it.type.name == "CONTEXT" && it.attemptIndex == lastAttempt }
        val acceptedContext = accepted?.let { end ->
            events.lastOrNull { it.type.name == "CONTEXT" && it.attemptIndex == end.attemptIndex }
        }
        return JSONObject().put("attempts", starts.size).put("recoveryAttempts", starts.count { it.attemptIndex > 0 })
            .put("acceptedAttemptIndex", accepted?.attemptIndex ?: JSONObject.NULL)
            .put("acceptedAttemptSelectedMargin", acceptedContext?.selectedMargin ?: JSONObject.NULL)
            .put("lastAttemptIndex", lastAttempt)
            .put("lastAttemptSelectedMargin", finalContext?.selectedMargin ?: JSONObject.NULL)
            .put("lastContextStemEvaluation", finalContext?.stemEvaluation?.name ?: JSONObject.NULL)
            .put("inferBoundaryCalls", events.count { it.type.name == "INFER" })
            .put("bodyInferBoundaryCalls", events.count { it.type.name == "INFER" && it.role?.name == "BODY" })
            .put("terminalReasons", JSONArray(events.filter { it.type.name == "TERMINAL" }.map { it.reason }))
            .put("scope", "Actual mode call events; accepted crop and all attempted crops are separate")
    }

    private fun summarize(
        samples: JSONArray,
        baselineEvents: List<OcrSelectionDiagnostics.Event>,
        candidateEvents: List<OcrSelectionDiagnostics.Event>,
    ): JSONObject {
        val rows = (0 until samples.length()).map { samples.getJSONObject(it) }
        val errors = rows.filter { !it.getBoolean("historicalCorrect") }
        val regressions = rows.count { it.getBoolean("candidateRegressedHistoricalCorrect") }
        val improved = rows.count { it.getBoolean("candidateImprovedHistoricalError") }
        val groups = JSONObject()
        rows.groupBy { it.getString("group") }.forEach { (name, group) ->
            groups.put(
                name,
                JSONObject().put("samples", group.size)
                    .put("baselineCorrect", correctCount(group, "baselineMixed"))
                    .put("candidateCorrect", correctCount(group, "candidateFp32")),
            )
        }
        return JSONObject().put("samples", rows.size)
            .put("historicalCorrect", rows.count { it.getBoolean("historicalCorrect") })
            .put("baselineCorrect", correctCount(rows, "baselineMixed"))
            .put("candidateCorrect", correctCount(rows, "candidateFp32"))
            .put(
                "historicalPublicReproduced",
                rows.count { it.getBoolean("historicalPublicTextDirectionAndKeepReproduced") },
            )
            .put("historicalCorrectRegressions", regressions).put("historicalCorrectRetained", 107 - regressions)
            .put("historicalErrorsImproved", improved).put("historicalErrorsRemaining", errors.size - improved)
            .put("historicalErrors", errors.size)
            .put("historicalErrorUniqueArgb", errors.map { it.getString("sourcePixelsArgbSha256") }.distinct().size)
            .put("changedPublicText", rows.count { it.getBoolean("candidatePublicTextChanged") })
            .put("unchangedPublicText", rows.count { !it.getBoolean("candidatePublicTextChanged") })
            .put("changedDirection", rows.count { it.getBoolean("candidateDirectionChanged") })
            .put("changedIntentionalKeep", rows.count { it.getBoolean("candidateIntentionalKeepChanged") })
            .put("groups", groups)
            .put("baselineMixed", modeCounts(rows, "baselineMixed", baselineEvents))
            .put("candidateFp32", modeCounts(rows, "candidateFp32", candidateEvents))
            .put(
                "qualityGateScope",
                "Preservation is outcome only; candidate accuracy does not determine collection pass",
            )
    }

    private fun correctCount(rows: List<JSONObject>, mode: String) =
        rows.count { it.getJSONObject(mode).getBoolean("matchesGroundTruth") }

    private fun modeCounts(
        rows: List<JSONObject>,
        name: String,
        events: List<OcrSelectionDiagnostics.Event>,
    ): JSONObject {
        val inferred = events.filter { it.type.name == "INFER" }
        val contexts = events.filter { it.type.name == "CONTEXT" }
        fun firstPrimary(row: JSONObject): String {
            val rowEvents = row.getJSONObject(name).getJSONArray("events")
            val first = (0 until rowEvents.length()).map { rowEvents.getJSONObject(it) }.single {
                it.getString("type") == "INFER" && it.optString("role") == "PRIMARY" && it.getInt("attemptIndex") == 0
            }
            return first.getString("text")
        }
        val primaryExact = rows.count { firstPrimary(it) == it.getString("expected") }
        val primaryPublicChanges = rows.count { firstPrimary(it) != it.getJSONObject(name).getString("text") }
        return JSONObject().put("primaryRawCorrect", primaryExact)
            .put("primaryTextDiffersFromFinalPublic", primaryPublicChanges)
            .put("inferBoundaryCalls", inferred.size)
            .put("primaryCalls", inferred.count { it.role?.name == "PRIMARY" })
            .put("legacyFallbackCalls", inferred.count { it.role?.name == "LEGACY_FALLBACK" })
            .put("bodyCalls", inferred.count { it.role?.name == "BODY" })
            .put("contextSelections", contexts.size)
            .put("contextSelected16", contexts.count { it.selectedMargin == 16 })
            .put("contextSelected32", contexts.count { it.selectedMargin == 32 })
            .put("recoveryAttempts", events.count { it.type.name == "ATTEMPT_START" && it.attemptIndex > 0 })
            .put(
                "terminalReasons",
                JSONObject(
                    events.filter { it.type.name == "TERMINAL" }
                        .groupingBy { it.reason ?: "UNSPECIFIED" }.eachCount(),
                ),
            )
            .put("countScope", "Kotlin infer-to-ocrCtc entry/return boundaries; not internal encoder/decoder counts")
    }

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

    private class HistoricalReference(private val parent: File, basename: String) {
        private val directory = File(parent, basename)
        private val file = File(directory, "result.json")
        val report: JSONObject
        val rows: Map<String, JSONObject>

        init {
            check(basename.matches(Regex("ocr-precision-mode-reference-$UUID_PATTERN")))
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
        const val MAX_REPORT_BYTES = 8_388_608L
        const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        const val INPUT_MANIFEST_SHA = "3178bfe3563ea01ce40423d7f9fb7579160797b42c190d416b29cb0c36ec015a"
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        const val REFERENCE_BYTES = 903_253L
        const val REFERENCE_SHA = "ed39996cafe9225ed8bc365390e78e3413c99da909668655e2c079e25d9dad66"
        const val GUARDED32_CONTEXT_SHA = "db1c82f4adad3c48d2755998063c4c7ffc4db906dc1887aa694e40bfa2d0f437"
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
