package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.EngineTrace
import li.joye.yakuyomi.engine.HorizontalOcrContextPolicy
import li.joye.yakuyomi.engine.HorizontalOcrCropPolicy
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.OcrSelectionDiagnostics
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import mihon.core.migration.Migrator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Original-source sampling experiment. Ground truth scores results; it never selects an input or output. */
@OptIn(InternalEngineApi::class)
@RunWith(AndroidJUnit4::class)
class OcrSamplingPhaseDiagnosticTest {
    @Test
    fun comparesFixedGeometryDestinationSamplingPhasesWithoutPromotion() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("ocrSamplingPhase") == "true") { "Explicit diagnostic opt-in required" }
        val id = args.getString("ocrSamplingPhaseRunId").orEmpty()
        check(id.matches(Regex(UUID_PATTERN)) && UUID.fromString(id).toString() == id)
        Executors.newSingleThreadExecutor { task -> Thread(task, "ocr-sampling-phase") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(15 * 60 * 1000L) {
                        Migrator.await()
                        collect(id)
                    }
                }
            }
    }

    private suspend fun collect(id: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit")
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
        val parent = context.cacheDir.canonicalFile
        val output = File(parent, "ocr-sampling-phase-$id")
        check(parent.isDirectory && !output.exists() && output.mkdir()) { "Never reuse a diagnostic UUID" }
        plain(output, parent, true)
        val file = File(output, "result.json")
        val baseline = JSONArray()
        val samples = JSONArray()
        val primaryWitnesses = ArrayList<OcrSelectionDiagnostics.Event>(120)
        val report = JSONObject().put("schemaVersion", 1).put("runId", id).put("passed", false)
            .put("finalized", false).put("collectionValidationPassed", false).put("diagnosticOnly", true)
            .put("targetPackage", context.packageName).put("sdk", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("process64Bit", true)
            .put("productionOptionsPromoted", false).put("qualityImprovementClaimed", false)
            .put("publicCandidatePipelineRerun", false).put("public107ImprovementMeasured", false)
            .put("expectedPublicBaselineCalls", 120).put("expectedDirectRawNativeCalls", 600)
            .put("maxDecodedTextFields", 600).put("maxDecodedTextCodeUnitsPerField", 256)
            .put("baselineTextEvidence", "SHA-256 only; exact text equality checked against pinned reference in memory")
            .put("conditions", JSONArray(PHASES.map { it.json() })).put("baseline", baseline).put("samples", samples)
            .put("samplingCoordinates", "Integer destination dx/dy before inverse homography; phase adds pixels")
            .put("phaseAxes", "Horizontal output strip x/y; all 120 inputs must be horizontal")
            .put("phaseZeroImplementation", "Local source warp; never production delegation or CHW resampling")
            .put("fixedPreprocessing", "Quad expansion4, original bbox+2 clamp, bicubic a=-0.75, H48, unsharp, right32")
            .put("leftTensorPadding", 0).put("rightTensorPadding", 32).put("sourceQuadExpansionPixels", 4)
            .put("modelInputDefinition", "Production unsharp, RGB NCHW Float32 (byte-127.5)/127.5; white right32")
            .put("groundTruthUsage", "Evaluation only; no selection, correction, search or fallback")
            .put("conditionOrder", "All baseline120 gates first; then input order with phase 0, x-, x+, y-, y+")
            .put("timingScope", "Sequential diagnostic including tracing; not a latency benchmark")
            .put("networkCallsByTest", 0).put("settingsWrittenByTest", 0).put("credentialApisAccessedByTest", false)
            .put("modelCopiesOrDownloadsByTest", 0).put("imageFilesWritten", 0).put("automaticRetries", 0)
            .put("explicitWarmUpCalls", 0).put("newModels", 0).put("serviceOrManagerFactoriesCalledByTest", 0)
            .put("nativeInternalOcrTraceAvailable", false)
            .put("evidenceScope", "Actual OCR native API invocation/return; no internal native trace claimed")
            .put("rawNativeEvidence", "Test boundary around real NcnnBackend.ocrCtc; exact returned step count")
            .put("publicNativeCountScope", "Existing diagnostic private-infer entries/returns; no internal JNI trace")
            .put("bootstrapMayRunTinyRelu", true).put("appMigrationAwaitedBeforeProtectedSnapshots", true)
        var failure: Throwable? = null
        var input: OcrRightContextInputs? = null
        var reference: HistoricalReference? = null
        var modelsBefore: JSONArray? = null
        var settingsBefore: JSONArray? = null
        var ownedOcr: Ocr? = null
        var collector: OcrSelectionDiagnostics? = null
        var rawObserver: RawNative? = null
        var publicCalls = 0
        var publicNativeCalls = 0
        var rawCalls = 0
        val threadId = Thread.currentThread().id
        val started = SystemClock.elapsedRealtimeNanos()
        fun checkpoint() {
            val bytes = report.toString().toByteArray(Charsets.UTF_8)
            check(bytes.size.toLong() in 1..MAX_REPORT_BYTES)
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
        fun stage(name: String) {
            report.put("stage", name)
            checkpoint()
            sendStatus(
                "OCR_SAMPLING_PHASE_STAGE",
                JSONObject().put("runId", id).put("stage", name)
                    .put("publicCallsCompleted", publicCalls).put("rawCallsCompleted", rawCalls),
            )
        }
        fun cleanup(name: String, block: () -> Unit) {
            try {
                block()
                report.put(name, true)
            } catch (error: Throwable) {
                report.put(name, false)
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        try {
            stage("PREFLIGHT")
            report.put("idleBefore", NativeDiagnosticIdleGuard.requireIdle(context))
            settingsBefore = awaitStableStartupSettings(context, report, ::checkpoint)
            report.put("settingsBefore", settingsBefore)
            val liveInput = OcrRightContextInputs(
                context,
                checkNotNull(args.getString("ocrSamplingPhaseInput")),
                instrumentation.context.assets.open("ocr-right-context/input-manifest.json").use { it.readBytes() },
            )
            input = liveInput
            check(bytesSha(liveInput.manifestBytes) == INPUT_MANIFEST_SHA)
            val historical = HistoricalReference(
                checkNotNull(context.getExternalFilesDir(null)).canonicalFile,
                checkNotNull(args.getString("ocrSamplingPhaseReference")),
            )
            reference = historical
            val cases = liveInput.fixedCases() + rightContextGeneratedCases()
            check(cases.size == 120 && cases.map { it.id } == historical.ids)
            check(cases.all { historical.rows.getValue(it.id).getString("expected") == it.expected })
            val models = modelDirectory(context)
            modelsBefore = fingerprintModels(models)
            check(modelsBefore.toString() == historical.report.getJSONArray("modelsAfter").toString())
            report.put("modelsBefore", modelsBefore).put("historicalReference", historical.description())
                .put("inputManifestSha256", INPUT_MANIFEST_SHA).put("sameThreadSerialCalls", true)
                .put("javaThreadId", threadId).put("fixedInputIdOrder", JSONArray(cases.map { it.id }))
            val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
            check(bytesSha(dictionaryBytes) == DICTIONARY_SHA)
            val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
            check(dictionary.size == 19_264)
            val config = productionConfig()
            report.put("requestedConfig", config.toString()).put("dictionarySha256", DICTIONARY_SHA)
            check(NcnnBackend.available && !poolReady()) { "Fresh process with cold native pool required" }
            report.put("nativePoolColdBeforeConstruction", true)
            val trace = OcrSelectionDiagnostics(maxLines = 120, maxEvents = 4096)
            collector = trace
            stage("MODEL_LOAD")
            val ocr = loadColdOcr(File(models, "ocr_48px_ctc.ncnn.param"), dictionary, config, trace, report)
            ownedOcr = ocr
            check(poolReady())
            val probe = OcrDiagnosticProbe(ocr, dictionary)
            val metadata = probe.metadata()
            val historicalBackend = historical.report.getJSONObject("candidateBackend")
            for (key in listOf("backend", "nativeThreads", "serialize", "peElements", "peFloat32LittleEndianSha256")) {
                check(metadata.get(key) == historicalBackend.get(key))
            }
            check(metadata.getString("backend") == "NCNN-mixed")
            check(metadata.getInt("nativeThreads") == 1 && !metadata.getBoolean("serialize"))
            val selected = probe.selectedParam(File(models, "ocr_48px_ctc.ncnn.param").path)
            check(selected.second && File(selected.first).name == historical.report.getString("selectedParam"))
            check(ocr.intentionalFilterContext.semanticFingerprint == GUARDED32_CONTEXT_SHA)
            report.put("backend", metadata).put("selectedParam", File(selected.first).name)
                .put("nativePoolReadyAfterConstruction", true)
            val sampler = SourceSampler(ocr)
            stage("BASELINE_120")
            for (case in cases) {
                currentCoroutineContext().ensureActive()
                check(Thread.currentThread().id == threadId)
                NativeDiagnosticIdleGuard.requireIdle(context)
                val drawing = case.open()
                try {
                    val prior = historical.rows.getValue(case.id)
                    verifySource(case, drawing, prior)
                    val before = diagnosticPixels(drawing.page)
                    val quad = drawing.quad.map { Pt(it.x, it.y) }
                    val line = TextLine(quad, 1f)
                    val kept = ocr.recognize(drawing.page, listOf(line))
                    publicCalls++
                    val snapshot = trace.snapshot()
                    completeTrace(snapshot, publicCalls)
                    val events = snapshot.events.filter { it.callOrdinal == publicCalls.toLong() }
                    val primary = verifyPublic(events, line, line in kept, prior)
                    publicNativeCalls += events.count { it.type.name == "INFER" }
                    val plan = sampler.plan(drawing.page, quad)
                    val local = sampler.sample(drawing.page, plan, PHASES.first())
                    try {
                        val (production, expanded) = probe.crop(drawing.page, quad, VARIANT)
                        try {
                            check(expanded == plan.expanded)
                            check(local.width == production.width && local.height == production.height)
                            check(diagnosticPixels(local).contentEquals(diagnosticPixels(production))) {
                                "Local phase0 ARGB differs from production crop: ${case.id}"
                            }
                        } finally {
                            production.recycle()
                        }
                        val tensor = probe.tensor(local, VARIANT)
                        verifyTensor(tensor, plan)
                        check(tensor.width == primary.inputWidth && tensor.height == primary.inputHeight)
                        check(diagnosticFloatSha(tensor.chw) == primary.chwSha256) {
                            "Local phase0 differs from actual same-call PRIMARY CHW: ${case.id}"
                        }
                    } finally {
                        local.recycle()
                    }
                    check(before.contentEquals(diagnosticPixels(drawing.page)) && drawing.quad == quad)
                    baseline.put(
                        JSONObject().put("id", case.id)
                            .put("sourceWidth", drawing.page.width).put("sourceHeight", drawing.page.height)
                            .put("sourceArgbSha256", pixelsSha(before)).put("quad", quadJson(quad))
                            .put("geometry", plan.json()).put("phaseZeroArgbAndChwBitParity", true)
                            .put(
                                "public",
                                JSONObject().put("textUtf8Sha256", textSha(line.text))
                                    .put("direction", line.direction)
                                    .put("intentionallyKept", line in kept)
                                    .put("matchesGroundTruth", line.text == case.expected),
                            )
                            .put("actualPrimary", primaryJson(primary)).put(
                                "publicNativeCalls",
                                events.count {
                                    it.type.name == "INFER"
                                },
                            ).put("sourceAndQuadUnchanged", true),
                    )
                    primaryWitnesses += primary
                    checkpoint()
                } finally {
                    drawing.page.recycle()
                }
            }
            check(publicCalls == 120 && baseline.length() == 120)
            check(
                (0 until 120).count {
                    baseline.getJSONObject(it).getJSONObject("public").getBoolean("matchesGroundTruth")
                } == 107,
            )
            completeTrace(trace.snapshot(), 120)
            report.put("baselineHistoricalPublic120Parity", true).put("baselinePublicCorrect", 107)
                .put("baseline120ActualPrimaryChwHistoricalPinsMatched", true)
                .put("phaseZero120ProductionCropArgbAndPrimaryChwBitParity", true)
            stage("RAW_600")
            val raw = RawNative(ocr, dictionary)
            rawObserver = raw
            for ((index, case) in cases.withIndex()) {
                currentCoroutineContext().ensureActive()
                check(Thread.currentThread().id == threadId)
                NativeDiagnosticIdleGuard.requireIdle(context)
                val drawing = case.open()
                try {
                    verifySource(case, drawing, historical.rows.getValue(case.id))
                    val before = diagnosticPixels(drawing.page)
                    val quad = drawing.quad.map { Pt(it.x, it.y) }
                    val plan = sampler.plan(drawing.page, quad)
                    val base = baseline.getJSONObject(index)
                    check(base.getString("id") == case.id)
                    check(base.getJSONObject("geometry").toString() == plan.json().toString())
                    val conditions = JSONArray()
                    val row = JSONObject().put("id", case.id)
                        .put("conditions", conditions).put("sourceAndQuadUnchanged", false)
                    samples.put(row)
                    for (phase in PHASES) {
                        currentCoroutineContext().ensureActive()
                        val strip = sampler.sample(drawing.page, plan, phase)
                        try {
                            val tensor = probe.tensor(strip, VARIANT)
                            verifyTensor(tensor, plan)
                            val result = JSONObject().put("phase", phase.name).put("completed", false)
                                .put("inputWidth", tensor.width).put("inputHeight", tensor.height)
                                .put("chwSha256", diagnosticFloatSha(tensor.chw))
                                .put("stripArgbSha256", pixelsSha(diagnosticPixels(strip)))
                            conditions.put(result)
                            raw.infer(tensor, result)
                            rawCalls++
                            result.put("matchesGroundTruth", result.getString("text") == case.expected)
                            if (phase == PHASES.first()) {
                                val primary = base.getJSONObject("actualPrimary")
                                val keys = listOf("inputWidth", "inputHeight", "chwSha256", "probabilityBits")
                                for (key in keys) {
                                    check(result.get(key) == primary.get(key)) {
                                        "Raw phase0 differs from actual PRIMARY $key: ${case.id}"
                                    }
                                }
                                check(result.getString("text") == primaryWitnesses[index].text) {
                                    "Raw phase0 text differs from actual PRIMARY: ${case.id}"
                                }
                                result.put("sameCallPrimaryRawTextAndProbabilityBitParity", true)
                            }
                        } finally {
                            strip.recycle()
                        }
                    }
                    check(conditions.length() == 5)
                    check(before.contentEquals(diagnosticPixels(drawing.page)) && drawing.quad == quad)
                    row.put("sourceAndQuadUnchanged", true)
                    checkpoint()
                } finally {
                    drawing.page.recycle()
                }
            }
            check(rawCalls == 600 && raw.completed == 600 && raw.started == 600 && samples.length() == 120)
            completeTrace(trace.snapshot(), 120)
            check(probe.explicitNativeCalls == 0 && probe.privateInferParityCalls == 0)
            val rawSummary = raw.summary()
            check(rawSummary.getBoolean("positionalEncodingUnchanged"))
            report.put("rawNativeBoundary", rawSummary).put("conditionSummary", score(samples))
                .put("phaseZero120RawTextAndProbabilityBitParity", true)
                .put("collectionValidationPassed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failureClass", error.javaClass.name)
        } finally {
            rawObserver?.let { raw -> report.put("rawNativeBoundary", raw.summary()) }
            ownedOcr?.let { ocr ->
                cleanup("privateOcrClosedHandleZero") {
                    ocr.close()
                    check(ocrField(ocr, "ncnnHandle") == 0L)
                }
            }
            ownedOcr = null
            input?.let { live -> cleanup("inputs40Unchanged") { live.verifyUnchanged() } }
            reference?.let { prior -> cleanup("historicalReferenceUnchanged") { prior.verifyUnchanged() } }
            modelsBefore?.let { before ->
                cleanup("models7Unchanged") {
                    val after = fingerprintModels(modelDirectory(context))
                    report.put("modelsAfter", after)
                    check(before.toString() == after.toString())
                }
            }
            settingsBefore?.let { before ->
                cleanup("settingsDiskBytesAndPresenceUnchanged") {
                    val after = settingsFingerprint(context)
                    report.put("settingsAfter", after)
                    check(before.toString() == after.toString())
                }
            }
            cleanup("sharedEngineStillAbsentAndIdle") {
                report.put("idleAfter", NativeDiagnosticIdleGuard.requireIdle(context))
            }
            collector?.snapshot()?.let { snapshot ->
                publicNativeCalls = snapshot.events.count { it.type.name == "INFER" && it.reason == "completed" }
                report.put(
                    "observer",
                    JSONObject().put("callCount", snapshot.callCount)
                        .put("lineCount", snapshot.lineCount).put("recordedLines", snapshot.recordedLines)
                        .put("eventCount", snapshot.events.size).put("droppedLines", snapshot.droppedLines)
                        .put("droppedEvents", snapshot.droppedEvents)
                        .put("recordingFailureCount", snapshot.recordingFailureCount)
                        .put("truncated", snapshot.truncated),
                )
            }
            collector = null
            primaryWitnesses.clear()
            report.put("publicBaselineCallsCompleted", publicCalls)
                .put("publicBaselineNativeCallsCompleted", publicNativeCalls)
                .put("directRawNativeCallsCompleted", rawObserver?.completed ?: 0)
                .put("directRawConditionsCompleted", rawCalls)
                .put("totalOcrNativeCallsCompleted", publicNativeCalls + (rawObserver?.completed ?: 0))
                .put("passed", failure == null && report.getBoolean("collectionValidationPassed"))
                .put("finalized", true).put("stage", "FINAL")
                .put("wallMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
            rawObserver = null
            try {
                checkpoint()
                plain(file, output, false)
                check(output.list()?.toSet() == setOf("result.json") && file.length() in 1..MAX_REPORT_BYTES)
                sendStatus(
                    "OCR_SAMPLING_PHASE",
                    JSONObject().put("schemaVersion", 1).put("runId", id)
                        .put("ownedDirectory", output.name).put("file", "result.json").put("bytes", file.length())
                        .put("sha256", diagnosticSha(file)).put("passed", report.getBoolean("passed"))
                        .put("terminalReportSealed", true).put("canonicalBoundaryVerified", true),
                )
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
        check(report.getBoolean("passed"))
    }

    private fun productionConfig() = OcrConfig().also { config ->
        check(config.horizontalContextPolicy == HorizontalOcrContextPolicy.GUARDED_32)
        check(config.horizontalCropPolicy == HorizontalOcrCropPolicy.UNIFORM)
        check(config.stripPad == 4 && config.textHeight == 48 && config.useBicubic && config.ocrUnsharp)
        check(config.ignoreBubble == 0 && config.minProb == 0.5f && config.minTextLength == 0)
        check(config.concurrent && config.concurrency == 8)
        check(config.ncnnMixed && config.ncnnFp16Storage && config.ncnnFp16Arith)
    }

    private fun loadColdOcr(
        model: File,
        dictionary: List<String>,
        config: OcrConfig,
        trace: OcrSelectionDiagnostics,
        report: JSONObject,
    ): Ocr {
        val previous = EngineTrace.sink
        var poolMessages = 0
        var poolRc: Int? = null
        var loadMessages = 0
        var previousSinkFailure: Throwable? = null
        val own: (String) -> Unit = { message ->
            if (message.startsWith("ncnn.pool init rc=")) {
                poolMessages = (poolMessages + 1).coerceAtMost(3)
                poolRc = message.substringAfter("rc=").toIntOrNull()
            }
            if (message.startsWith("ocr.load ")) loadMessages = (loadMessages + 1).coerceAtMost(3)
            try {
                previous?.invoke(message)
            } catch (error: Throwable) {
                // Let the constructor return its owned handle before propagating a chained observer failure.
                if (previousSinkFailure == null) previousSinkFailure = error
            }
        }
        var ocr: Ocr? = null
        var failure: Throwable? = null
        EngineTrace.sink = own
        try {
            ocr = Ocr(model.path, dictionary, config, diagnostics = trace)
            previousSinkFailure?.let { throw it }
            check(poolMessages == 1 && poolRc == 0 && loadMessages == 1 && poolReady())
            report.put(
                "coldBootstrap",
                JSONObject().put("poolMessages", poolMessages).put("poolRc", poolRc)
                    .put("ocrLoadMessages", loadMessages).put("existingTraceSinkChained", previous != null),
            )
            return ocr
        } catch (error: Throwable) {
            failure = error
            try {
                ocr?.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw error
        } finally {
            if (EngineTrace.sink === own) {
                EngineTrace.sink = previous
            } else {
                val error = IllegalStateException("Engine trace ownership changed during model construction")
                ocr?.close()
                if (failure == null) throw error else failure.addSuppressed(error)
            }
        }
    }

    private fun completeTrace(snapshot: OcrSelectionDiagnostics.Snapshot, count: Int) {
        check(snapshot.callCount == count.toLong() && snapshot.lineCount == count.toLong())
        check(snapshot.recordedLines == count.toLong() && snapshot.events.size <= 4096)
        check(snapshot.droppedLines == 0L && snapshot.droppedEvents == 0L && snapshot.recordingFailureCount == 0L)
        check(!snapshot.truncated && snapshot.events.all { it.callOrdinal in 1L..count.toLong() })
    }

    private fun verifyPublic(
        events: List<OcrSelectionDiagnostics.Event>,
        line: TextLine,
        kept: Boolean,
        prior: JSONObject,
    ): OcrSelectionDiagnostics.Event {
        check(events.isNotEmpty() && events.first().type.name == "CALL_START")
        check(events.last().type.name == "CALL_END" && events.none { it.errorClass != null })
        check(events.count { it.type.name == "CALL_START" } == 1 && events.count { it.type.name == "CALL_END" } == 1)
        val end = events.single { it.type.name == "LINE_END" }
        check(end.text == line.text && end.direction == line.direction && end.intentionallyKept == kept)
        val expected = prior.getJSONObject("candidatePublic")
        check(line.text == expected.getString("text") && line.direction == expected.getString("direction"))
        check(kept == expected.getBoolean("intentionallyKept"))
        val inferred = events.filter { it.type.name == "INFER" }
        check(inferred.size in 1..9)
        inferred.forEachIndexed { index, event ->
            check(event.reason == "completed" && event.lineIndex == 0 && event.probabilityBits != null)
            check(event.nativeCallsStarted == index + 1L && event.nativeCallsCompleted == index + 1L)
            check(event.inputHeight == 48 && (event.inputWidth ?: 0) > 0)
            check(event.chwSha256?.matches(Regex("[a-f0-9]{64}")) == true)
        }
        check(end.nativeCallsStarted == inferred.size.toLong() && end.nativeCallsCompleted == inferred.size.toLong())
        val primary = inferred.single { it.attemptIndex == 0 && it.role?.name == "PRIMARY" }
        check(primary.actualMargin == 32) { "All 120 PRIMARY tensors must use fixed right32" }
        check(primary.text != null && Float.fromBits(checkNotNull(primary.probabilityBits)).isFinite())
        val observations = prior.getJSONArray("rawObservations")
        val old = (0 until observations.length()).map { observations.getJSONObject(it) }
            .single { it.getInt("rightMargin") == 32 }
        val tensor = old.getJSONObject("tensor")
        check(primary.inputWidth == tensor.getInt("width") && primary.inputHeight == tensor.getInt("height"))
        check(primary.chwSha256 == tensor.getString("float32LittleEndianSha256"))
        val expanded = old.getJSONArray("expandedQuad")
        val bits = FloatArray(8) { expanded.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
        val crop = events.single { it.type.name == "CROP" && it.attemptIndex == 0 }
        check(crop.quadSha256 == diagnosticFloatSha(bits) && crop.direction == "h")
        check(crop.stripPad == 4 && crop.bicubic == true && crop.stripHeight == 48)
        return primary
    }

    private fun primaryJson(primary: OcrSelectionDiagnostics.Event) = JSONObject()
        .put("inputWidth", primary.inputWidth).put("inputHeight", primary.inputHeight)
        .put("actualMargin", primary.actualMargin).put("chwSha256", primary.chwSha256)
        .put("textUtf8Sha256", textSha(checkNotNull(primary.text))).put("probabilityBits", primary.probabilityBits)
        .put("scope", "First completed PRIMARY of same public baseline call")

    private fun verifyTensor(tensor: OcrDiagnosticTensor, plan: SamplingPlan) {
        check(tensor.width == plan.width + 32 && tensor.height == plan.height && tensor.height == 48)
        check(tensor.chw.size == 3 * tensor.width * tensor.height && tensor.chw.all { it.isFinite() })
        check(tensor.production != null)
    }

    /** Direct JNI wrapper; counters describe this test boundary, not internal OCR trace hooks. */
    private class RawNative(private val ocr: Ocr, private val dictionary: List<String>) {
        private val thread = Thread.currentThread().id
        private val handle = ocrField(ocr, "ncnnHandle") as Long
        private val threads = ocrField(ocr, "threads") as Int
        private val pe = (
            Ocr::class.java.getDeclaredField("PE\$delegate").apply { isAccessible = true }
                .get(null) as Lazy<*>
            ).value as FloatArray
        private val peShaBefore = diagnosticFloatSha(pe)
        private val collapse = Ocr.Companion::class.java.declaredMethods.single {
            it.name.startsWith("ctcCollapse")
        }.apply { isAccessible = true }
        var started = 0
            private set
        var completed = 0
            private set
        private var validated = 0

        @Suppress("UNCHECKED_CAST")
        fun infer(tensor: OcrDiagnosticTensor, result: JSONObject) {
            check(Thread.currentThread().id == thread && handle != 0L && threads == 1)
            check(ocrField(ocr, "ncnnHandle") == handle)
            val steps = tensor.width / 4 - 1
            check(steps in 1..2048 && steps * 320 <= pe.size && started < 600)
            val indices = IntArray(steps)
            val logp = FloatArray(steps)
            val boundary = JSONObject().put("enter", 1).put("call", 0).put("exit", 0)
                .put("scope", "Test wrapper around real NcnnBackend.ocrCtc API")
                .put("samePrivateOcrHandleVerified", true).put("serialize", false)
            result.put("nativeBoundary", boundary).put("timeSteps", steps)
                .put("outputIndicesElements", indices.size).put("outputLogpElements", logp.size)
            started++
            val start = SystemClock.elapsedRealtimeNanos()
            boundary.put("call", 1)
            val rc = NcnnBackend.ocrCtc(
                handle, tensor.chw, tensor.width, tensor.height, pe, steps, indices, logp, false,
            )
            boundary.put("exit", 1).put("rc", rc).put("expectedRc", steps)
            completed++
            check(rc == steps && indices.all { it in dictionary.indices } && logp.all { it.isFinite() })
            validated++
            val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            val decoded = invoke(collapse, Ocr.Companion, indices, logp, steps, dictionary) as Pair<String, Float>
            check(decoded.second.isFinite() && decoded.second in 0f..1f)
            result.put("text", bounded(decoded.first)).put("probabilityBits", decoded.second.toRawBits())
                .put("probability", decoded.second.toDouble()).put("completed", true)
                .put("durationMsIncludingBoundaryAndValidation", elapsed).put("timeSteps", steps)
                .put("decoder", "Actual production ctcCollapse; no public filter/recovery/correction")
        }

        fun summary() = JSONObject().put("enter", started).put("call", started).put("exit", completed)
            .put("expectedCalls", 600).put("validatedRcAndFiniteOutputs", validated)
            .put("allNativeRcEqualExpectedSteps", validated == 600)
            .put("positionalEncodingUnchanged", diagnosticFloatSha(pe) == peShaBefore)
            .put("positionalEncodingFloat32Sha256", peShaBefore)
            .put("nativeInternalTraceClaimed", false).put("boundedCounters", true)
    }

    private data class Phase(val name: String, val x: Float, val y: Float) {
        fun json() = JSONObject().put("name", name).put("destinationX", x.toDouble()).put("destinationY", y.toDouble())
    }

    private data class SamplingPlan(
        val expanded: List<Pt>,
        val ordered: List<Pt>,
        val width: Int,
        val height: Int,
        val inverse: FloatArray,
        val x0: Int,
        val y0: Int,
        val cropWidth: Int,
        val cropHeight: Int,
    ) {
        fun json() = JSONObject().put("expandedQuad", quadJson(expanded)).put("orderedQuad", quadJson(ordered))
            .put("stripWidth", width).put("stripHeight", height)
            .put("inputWidth", width + 32).put("inputHeight", height)
            .put("inverseMatrixFloat32Sha256", diagnosticFloatSha(inverse))
            .put("sourceCrop", JSONArray(listOf(x0, y0, cropWidth, cropHeight))).put("horizontal", true)
    }

    /** Only warp arithmetic is copied. Production quad ordering, unsharp and CHW are reused and bit checked. */
    private class SourceSampler(private val ocr: Ocr) {
        private val methods = Ocr::class.java.declaredMethods.associateBy { it.name }

        @Suppress("UNCHECKED_CAST")
        fun plan(page: Bitmap, quad: List<Pt>): SamplingPlan {
            val expanded = invoke(method("expandQuad"), ocr, quad, 4, page.width, page.height) as List<Pt>
            val sorted = invoke(method("sortPnts"), ocr, expanded) as Pair<List<Pt>, Boolean>
            check(!sorted.second) { "The complete 120-case corpus must remain horizontal; no skipping" }
            val pts = sorted.first
            val p1x = (pts[0].x + pts[1].x) / 2f
            val p1y = (pts[0].y + pts[1].y) / 2f
            val p2x = (pts[2].x + pts[3].x) / 2f
            val p2y = (pts[2].y + pts[3].y) / 2f
            val p3x = (pts[1].x + pts[2].x) / 2f
            val p3y = (pts[1].y + pts[2].y) / 2f
            val p4x = (pts[3].x + pts[0].x) / 2f
            val p4y = (pts[3].y + pts[0].y) / 2f
            val vLen = hypot((p2x - p1x).toDouble(), (p2y - p1y).toDouble()).toFloat()
            val hLen = hypot((p4x - p3x).toDouble(), (p4y - p3y).toDouble()).toFloat()
            val ratio = vLen / max(hLen, 1e-6f)
            check(ratio.isFinite() && ratio > 0f)
            val h = max(48, 2)
            val w = max((48 / ratio).roundToInt(), 2)
            check(w in 2..8_000)
            val src = floatArrayOf(pts[0].x, pts[0].y, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y)
            val dst = floatArrayOf(
                0f,
                0f,
                (w - 1).toFloat(),
                0f,
                (w - 1).toFloat(),
                (h - 1).toFloat(),
                0f,
                (h - 1).toFloat(),
            )
            val forward = Matrix()
            check(forward.setPolyToPoly(src, 0, dst, 0, 4))
            val inverse = Matrix()
            check(forward.invert(inverse))
            val iv = FloatArray(9)
            inverse.getValues(iv)
            check(iv.all { it.isFinite() })
            val x0 = (floor(pts.minOf { it.x }) - 2f).toInt().coerceIn(0, page.width - 1)
            val y0 = (floor(pts.minOf { it.y }) - 2f).toInt().coerceIn(0, page.height - 1)
            val x1 = (ceil(pts.maxOf { it.x }) + 2f).toInt().coerceIn(0, page.width - 1)
            val y1 = (ceil(pts.maxOf { it.y }) + 2f).toInt().coerceIn(0, page.height - 1)
            check(x1 - x0 + 1 >= 2 && y1 - y0 + 1 >= 2)
            return SamplingPlan(expanded, pts, w, h, iv, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        }

        fun sample(page: Bitmap, plan: SamplingPlan, phase: Phase): Bitmap {
            val cw = plan.cropWidth
            val ch = plan.cropHeight
            val src = IntArray(cw * ch)
            page.getPixels(src, 0, cw, plan.x0, plan.y0, cw, ch)
            val out = IntArray(plan.width * plan.height)
            val iv = plan.inverse
            for (dy in 0 until plan.height) {
                for (dx in 0 until plan.width) {
                    val sx: Float
                    val sy: Float
                    if (phase.x == 0f && phase.y == 0f) {
                        // Preserve production integer dx/dy expressions and Float operation order exactly.
                        val den = iv[6] * dx + iv[7] * dy + iv[8]
                        sx = (iv[0] * dx + iv[1] * dy + iv[2]) / den - plan.x0
                        sy = (iv[3] * dx + iv[4] * dy + iv[5]) / den - plan.y0
                    } else {
                        val px = dx + phase.x
                        val py = dy + phase.y
                        val den = iv[6] * px + iv[7] * py + iv[8]
                        sx = (iv[0] * px + iv[1] * py + iv[2]) / den - plan.x0
                        sy = (iv[3] * px + iv[4] * py + iv[5]) / den - plan.y0
                    }
                    check(sx.isFinite() && sy.isFinite())
                    out[dy * plan.width + dx] = bicubicSample(src, cw, ch, sx, sy)
                }
            }
            return Bitmap.createBitmap(out, plan.width, plan.height, Bitmap.Config.ARGB_8888)
        }

        private fun method(name: String) = checkNotNull(methods[name]).apply { isAccessible = true }

        private fun bicubicSample(px: IntArray, cw: Int, ch: Int, sx: Float, sy: Float): Int {
            val a = -0.75f
            val fx = floor(sx).toInt()
            val fy = floor(sy).toInt()
            var r = 0f
            var g = 0f
            var b = 0f
            for (my in -1..2) {
                val wy = cubicW(sy - (fy + my), a)
                val rowBase = (fy + my).coerceIn(0, ch - 1) * cw
                var rr = 0f
                var gg = 0f
                var bb = 0f
                for (mx in -1..2) {
                    val wx = cubicW(sx - (fx + mx), a)
                    val p = px[rowBase + (fx + mx).coerceIn(0, cw - 1)]
                    rr += wx * ((p shr 16) and 0xFF)
                    gg += wx * ((p shr 8) and 0xFF)
                    bb += wx * (p and 0xFF)
                }
                r += wy * rr
                g += wy * gg
                b += wy * bb
            }
            return (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
        }

        private fun cubicW(t: Float, a: Float): Float {
            val x = abs(t)
            return when {
                x <= 1f -> ((a + 2f) * x - (a + 3f)) * x * x + 1f
                x < 2f -> ((a * x - 5f * a) * x + 8f * a) * x - 4f * a
                else -> 0f
            }
        }
    }

    private fun score(rows: JSONArray): JSONArray {
        check(rows.length() == 120)
        return JSONArray(
            PHASES.mapIndexed { phaseIndex, phase ->
                var correct = 0
                var improved = 0
                var regressed = 0
                val changes = JSONArray()
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val conditions = row.getJSONArray("conditions")
                    check(conditions.length() == 5)
                    val base = conditions.getJSONObject(0)
                    val candidate = conditions.getJSONObject(phaseIndex)
                    check(candidate.getString("phase") == phase.name)
                    val oldCorrect = base.getBoolean("matchesGroundTruth")
                    val newCorrect = candidate.getBoolean("matchesGroundTruth")
                    if (newCorrect) correct++
                    if (!oldCorrect && newCorrect) improved++
                    if (oldCorrect && !newCorrect) regressed++
                    if (base.getString("text") != candidate.getString("text")) changes.put(row.getString("id"))
                }
                phase.json().put("samples", 120).put("rawCorrect", correct).put("rawImproved", improved)
                    .put("rawRegressed", regressed).put("textChangedIds", changes)
                    .put("preservesRawBaselineCorrect", regressed == 0)
                    .put("scope", "Raw PRIMARY only; public guarded/body/recovery pipeline not rerun")
                    .put("public107PreservationOrImprovementEstablished", false).put("promotionEligible", false)
            },
        )
    }

    private fun verifySource(case: OcrRightContextCase, drawing: OcrRightContextDrawing, prior: JSONObject) {
        check(drawing.quad.size == 4 && drawing.quad.all { it.x.isFinite() && it.y.isFinite() })
        check(prior.getString("group") == case.group && prior.getString("expected") == case.expected)
        check(prior.getInt("sourceWidth") == drawing.page.width && prior.getInt("sourceHeight") == drawing.page.height)
        check(prior.getString("sourcePixelsArgbSha256") == pixelsSha(diagnosticPixels(drawing.page)))
        check(prior.getJSONArray("quad").toString() == quadJson(drawing.quad).toString())
    }

    private fun modelDirectory(context: Context): File {
        val files = context.filesDir.canonicalFile
        val data = File(context.applicationInfo.dataDir).canonicalFile
        check(files.isDirectory && files.name == "files" && files.parentFile == data)
        return File(files, "models").also { plain(it, files, true) }
    }

    private fun fingerprintModels(directory: File) = JSONArray(
        MODEL_NAMES.map { name ->
            val file = File(directory, name)
            plain(file, directory, false)
            check(file.length() > 0)
            JSONObject().put("name", name).put("bytes", file.length()).put("sha256", diagnosticSha(file))
        },
    )

    /** Preparation only: retain every file in each full snapshot; never retry the final preservation check. */
    private suspend fun awaitStableStartupSettings(
        context: Context,
        report: JSONObject,
        checkpoint: () -> Unit,
    ): JSONArray {
        val observations = JSONArray()
        val preparation = JSONObject().put("passed", false).put("timeoutMs", 10_000)
            .put("intervalMs", 250).put("requiredConsecutiveFullSnapshots", 3).put("maxSnapshots", 40)
            .put("scope", "Before settingsBefore only; final preservation check remains a single attempt")
            .put("backupFilesExcludedFromSnapshots", false).put("backupFilesDeleted", false)
            .put("settingsWrittenByBarrier", 0).put("observations", observations)
        report.put("startupSettingsStabilization", preparation)
        val started = SystemClock.elapsedRealtimeNanos()
        return withTimeout(10_000L) {
            var previous: String? = null
            var consecutive = 0
            repeat(40) { index ->
                currentCoroutineContext().ensureActive()
                NativeDiagnosticIdleGuard.requireIdle(context)
                val snapshot = settingsFingerprint(context)
                val encoded = snapshot.toString()
                val backupCount = (0 until snapshot.length()).count {
                    snapshot.getJSONObject(it).getString("name").endsWith(".xml.bak")
                }
                consecutive = if (backupCount == 0) {
                    if (encoded == previous) consecutive + 1 else 1
                } else {
                    0
                }
                if (index == 0) preparation.put("initialFullSnapshot", snapshot)
                observations.put(
                    JSONObject().put("index", index).put("fileCount", snapshot.length())
                        .put("fullSnapshotUtf8Sha256", bytesSha(encoded.toByteArray(Charsets.UTF_8)))
                        .put("xmlBackupCount", backupCount).put("consecutiveEligibleSnapshots", consecutive)
                        .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0),
                )
                if (consecutive == 3) {
                    preparation.put("passed", true).put(
                        "stableFullSnapshotUtf8Sha256",
                        bytesSha(encoded.toByteArray(Charsets.UTF_8)),
                    )
                    checkpoint()
                    return@withTimeout snapshot
                }
                previous = if (backupCount == 0) encoded else null
                checkpoint()
                if (index < 39) delay(250L)
            }
            error("Startup settings did not reach three identical full snapshots without XML backups")
        }
    }

    private fun settingsFingerprint(context: Context): JSONArray {
        val data = File(context.applicationInfo.dataDir).canonicalFile
        val directory = File(data, "shared_prefs")
        plain(directory, data, true)
        val files = checkNotNull(directory.listFiles()).sortedBy { it.name }
        check(files.size <= 128 && files.sumOf { it.length() } <= 4_194_304L)
        return JSONArray(
            files.map { file ->
                plain(file, directory, false)
                JSONObject().put("name", file.name).put("bytes", file.length()).put("sha256", diagnosticSha(file))
            },
        )
    }

    private class HistoricalReference(private val parent: File, basename: String) {
        private val directory = File(parent, basename)
        private val file = File(directory, "result.json")
        val report: JSONObject
        val rows: Map<String, JSONObject>
        val ids: List<String>

        init {
            check(basename.matches(Regex("ocr-sampling-phase-reference-$UUID_PATTERN")))
            verifyUnchanged()
            report = JSONObject(file.readText(Charsets.UTF_8))
            check(report.getBoolean("passed") && report.getBoolean("accuracyGatePassed"))
            check(report.getString("candidatePolicy") == "GUARDED_32")
            val samples = report.getJSONArray("samples")
            check(samples.length() == 120)
            val entries = (0 until samples.length()).map { samples.getJSONObject(it) }
            ids = entries.map { it.getString("id") }
            rows = entries.associateBy { it.getString("id") }
            check(
                rows.size == 120 && entries.count {
                    it.getJSONObject("candidatePublic").getString("text") == it.getString("expected")
                } == 107,
            )
        }

        fun verifyUnchanged() {
            plain(directory, parent, true)
            plain(file, directory, false)
            check(directory.list()?.toSet() == setOf("result.json"))
            check(file.length() == REFERENCE_BYTES && diagnosticSha(file) == REFERENCE_SHA)
        }

        fun description() = JSONObject().put("basename", directory.name).put("bytes", REFERENCE_BYTES)
            .put("sha256", REFERENCE_SHA).put("samples", 120).put("publicCorrect", 107)
    }

    private companion object {
        const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        const val MAX_REPORT_BYTES = 1_048_576L
        const val INPUT_MANIFEST_SHA = "3178bfe3563ea01ce40423d7f9fb7579160797b42c190d416b29cb0c36ec015a"
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        const val REFERENCE_BYTES = 903_253L
        const val REFERENCE_SHA = "ed39996cafe9225ed8bc365390e78e3413c99da909668655e2c079e25d9dad66"
        const val GUARDED32_CONTEXT_SHA = "db1c82f4adad3c48d2755998063c4c7ffc4db906dc1887aa694e40bfa2d0f437"
        val PHASES = listOf(
            Phase("zero", 0f, 0f),
            Phase("xMinusHalf", -0.5f, 0f),
            Phase("xPlusHalf", 0.5f, 0f),
            Phase("yMinusHalf", 0f, -0.5f),
            Phase("yPlusHalf", 0f, 0.5f),
        )
        val VARIANT = OcrDiagnosticVariant("sampling-phase-fixed", 4, 32, true)
        val MODEL_NAMES = listOf(
            "dbnet_detect.ncnn.param",
            "dbnet_detect.ncnn.bin",
            "ocr_48px_ctc.ncnn.param",
            "ocr_48px_ctc_mixed.ncnn.param",
            "ocr_48px_ctc.ncnn.bin",
            "mit_aot_fixed512.ncnn.param",
            "mit_aot_fixed512.ncnn.bin",
        )

        fun plain(file: File, parent: File, directory: Boolean) {
            check(file.canonicalFile == file.absoluteFile && file.parentFile == parent)
            check(if (directory) file.isDirectory else file.isFile)
        }

        fun poolReady() = NcnnBackend::class.java.getDeclaredField("threadPoolReady")
            .apply { isAccessible = true }.getBoolean(NcnnBackend)

        fun ocrField(ocr: Ocr, name: String): Any? = Ocr::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(ocr)

        fun invoke(method: Method, owner: Any?, vararg args: Any?): Any? = try {
            method.invoke(owner, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }

        fun bounded(text: String): String {
            check(text.length <= 256) { "Diagnostic text exceeds bounded payload" }
            return text
        }

        fun textSha(text: String) = bytesSha(bounded(text).toByteArray(Charsets.UTF_8))

        fun quadJson(quad: List<Pt>) = JSONArray(quad.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) })

        fun pixelsSha(pixels: IntArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(4096)
            var used = 0
            for (pixel in pixels) {
                buffer[used++] = (pixel ushr 24).toByte()
                buffer[used++] = (pixel ushr 16).toByte()
                buffer[used++] = (pixel ushr 8).toByte()
                buffer[used++] = pixel.toByte()
                if (used == buffer.size) {
                    digest.update(buffer)
                    used = 0
                }
            }
            digest.update(buffer, 0, used)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun bytesSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun sendStatus(prefix: String, value: JSONObject) {
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "$prefix=$value")
                },
            )
        }
    }
}
