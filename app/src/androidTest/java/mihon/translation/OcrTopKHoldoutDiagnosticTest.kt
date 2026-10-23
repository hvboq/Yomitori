package mihon.translation

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
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.OcrCtcDiagnosticApi
import li.joye.yakuyomi.engine.OcrCtcTopKDiagnosticResult
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
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
import kotlin.math.exp

/**
 * Explicitly selected native diagnostic; observations never change production decoding.
 * Fixed K=8 observation of all steps, independent blank and unchanged legacy greedy.
 * Raw16/raw32 are primary-crop observations, not complete public GUARDED_32 behavior.
 * No decoder candidate is selected or promoted; existing wrong strings remain errors.
 */
@OptIn(InternalEngineApi::class, OcrCtcDiagnosticApi::class)
@RunWith(AndroidJUnit4::class)
class OcrTopKHoldoutDiagnosticTest {
    @Test
    fun collectAllStepTopKAndBlankWithoutChangingPublicGreedyResults() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "ocr-topk-holdout") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(20 * 60 * 1000L) { collect() }
                }
            }
    }

    private suspend fun collect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("ocrTopKHoldout") == "true") {
            "Explicit ocrTopKHoldout=true is required; missing opt-in is a failure, not a skip"
        }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit")
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
        val inputName = checkNotNull(arguments.getString("ocrRightContextInput"))
        val input = OcrRightContextInputs(
            context,
            inputName,
            instrumentation.context.assets.open("ocr-right-context/input-manifest.json").use { it.readBytes() },
        )
        check(bytesSha(input.manifestBytes) == INPUT_MANIFEST_SHA)
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val reference = HistoricalReference(external, checkNotNull(arguments.getString("ocrTopKReference")))
        val cases = input.fixedCases() + rightContextGeneratedCases()
        check(cases.size == 120 && cases.map { it.id }.distinct().size == 120)
        check(cases.map { it.id }.toSet() == reference.rows.keys)
        check(cases.all { it.expected == reference.rows.getValue(it.id).getString("expected") })
        val config = OcrConfig()
        check(config.horizontalContextPolicy == HorizontalOcrContextPolicy.GUARDED_32)
        check(config.ocrUnsharp && config.useBicubic && config.stripPad == 4 && config.textHeight == 48)
        check(config.ignoreBubble == 0 && config.concurrent && config.concurrency == 8)
        check(config.minProb == 0.5f && config.minTextLength == 0)
        check(config.ncnnMixed && config.ncnnFp16Storage && config.ncnnFp16Arith)
        val shared = Injekt.get<TranslationEngineService>()
        withTimeout(10_000) { Injekt.get<TranslationManager>().ensureRestored() }
        requireIdle(shared)
        val saved = OnlineReaderSettings(context)
        val output = File(external, "ocr-topk-holdout-${UUID.randomUUID()}")
        check(output.mkdir() && output.canonicalFile == output.absoluteFile && output.parentFile == external)
        val reportFile = File(output, "result.json")
        val rows = JSONArray()
        val started = SystemClock.elapsedRealtimeNanos()
        val threadId = Thread.currentThread().id
        val report = JSONObject().put("passed", false).put("diagnosticOnly", true)
            .put("collectionValidationPassed", false).put("promotionEligible", false)
            .put("decoderCandidateEvaluated", false).put("modelOutputRewrittenByTest", false)
            .put("testTextNormalization", "None: exact Unicode strings without trim or replacement")
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("process64Bit", true)
            .put("javaThreadId", threadId).put("javaThreadName", Thread.currentThread().name)
            .put("androidTid", Process.myTid()).put("sameThreadSerialCalls", true)
            .put("config", config.toString()).put("inputBasename", inputName)
            .put("pinnedInputFiles", input.sourceManifest).put("inputManifestSha256", INPUT_MANIFEST_SHA)
            .put("historicalReference", reference.description()).put("expectedSamples", 120)
            .put("rawMargins", JSONArray(MARGINS)).put("fixedK", 8).put("blankId", 0)
            .put("expectedDiagnosticForwards", 240).put("expectedSeparateLegacyForwards", 240)
            .put("expectedPublicRecognizeCalls", 120).put("samples", rows)
            .put("rawScope", "Primary crop at fixed margins; not public guarded context or recovery")
            .put("legacySameForwardScope", "Independent original strict-maximum greedy in diagnostic forward")
            .put("legacyCrossForwardScope", "Separate unchanged ocrCtc via existing OcrDiagnosticProbe")
            .put("publicScope", "Actual recognize including guard, punctuation, filtering and recovery")
            .put("topKLogProbabilityScope", "Full-vocabulary log_softmax; retained eight are not renormalized")
            .put("blankScope", "Always retained separately; not forced into top eight")
            .put("scoreTieScope", "Native ties prefer lower ID; rounded logp alone cannot prove logit ties")
            .put("timingScope", "Serial fixed-order collection and validation; not a speed benchmark")
            .put("historicalIncorrectPredictionsAreGroundTruth", false)
            .put("sourcePixelHashLayout", "Row-major A,R,G,B bytes; independent of PNG encoding")
            .put("tensorHashLayout", "RGB CHW float32 little-endian; dimensions recorded separately")
            .put("newImagesWritten", 0).put("ownedOutputFiles", JSONArray(listOf("result.json")))
            .put("modelFilesCopiedOrDownloaded", false).put("llmOrOtherNetworkCalls", false)
            .put("actualReaderActivity", false).put("signedRelease", false)
            .put("totalNativeForwardCountKnown", false).put("sharedEngineShutdownCalled", false)
        var failure: Throwable? = null
        var beforeModels: JSONArray? = null
        var privateOcr: Ocr? = null
        var diagnosticCalls = 0
        var legacyCalls = 0
        var publicCalls = 0
        var allSteps = 0
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
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "OCR_TOPK_HOLDOUT=" + JSONObject().put("phase", phase).put("samples", rows.length())
                            .put("diagnosticCalls", diagnosticCalls).put("legacyCalls", legacyCalls)
                            .put("publicCalls", publicCalls).put("report", reportFile.absolutePath),
                    )
                },
            )
        }
        try {
            saved.set(Injekt.get<TranslationPreferences>().liveTranslate, false)
            requireIdle(shared)
            report.put("temporaryLiveTranslateDisabled", true)
            val models = File(context.filesDir, "models")
            beforeModels = fingerprintModels(models)
            check(beforeModels.toString() == reference.report.getJSONArray("modelsAfter").toString())
            report.put("modelsBefore", beforeModels).put("sameSevenModelsAsReference", true)
            val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
            check(bytesSha(dictionaryBytes) == DICTIONARY_SHA)
            val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
            check(dictionary.size == 19_264)
            report.put("dictionaryRows", dictionary.size).put("dictionarySha256", DICTIONARY_SHA)
            // Freeze all source pixels/geometry before model construction or inference.
            // Requested Android family names alone do not prove raster identity.
            for (case in cases) {
                currentCoroutineContext().ensureActive()
                val drawing = case.open()
                try {
                    verifySource(case, drawing, reference.rows.getValue(case.id))
                } finally {
                    drawing.page.recycle()
                }
            }
            report.put("all120SourceHashesAndGeometryPreflightPassed", true)
            checkpoint()
            status("SOURCE_PREFLIGHT_COMPLETED")
            val param = File(models, "ocr_48px_ctc.ncnn.param")
            Ocr(param.path, dictionary, config).use { ocr ->
                privateOcr = ocr
                val probe = OcrDiagnosticProbe(ocr, dictionary)
                val access = DiagnosticAccess(ocr)
                val metadata = probe.metadata()
                val priorMetadata = reference.report.getJSONObject("candidateBackend")
                for (key in BACKEND_KEYS) check(metadata.get(key) == priorMetadata.get(key))
                check(metadata.getInt("nativeThreads") == 1 && access.threads == 1)
                val selected = probe.selectedParam(param.path)
                check(File(selected.first).name == reference.report.getString("selectedParam"))
                check(selected.second == reference.report.getBoolean("selectedMixed"))
                report.put("backend", metadata).put("selectedParam", File(selected.first).name)
                    .put("selectedMixed", selected.second).put("privateOcrInstances", 1)
                // Pin every CHW before the first diagnostic, legacy or public forward.
                for (case in cases) {
                    currentCoroutineContext().ensureActive()
                    requireIdle(shared)
                    val drawing = case.open()
                    try {
                        verifySource(case, drawing, reference.rows.getValue(case.id))
                        for (margin in MARGINS) {
                            val variant = variant(margin)
                            val (strip, expanded) = probe.crop(drawing.page, drawing.quad, variant)
                            try {
                                verifyTensor(
                                    probe.tensor(strip, variant),
                                    expanded,
                                    rawReference(reference, case.id, margin),
                                )
                            } finally {
                                strip.recycle()
                            }
                        }
                    } finally {
                        drawing.page.recycle()
                    }
                }
                report.put("all240ChwHashesPreflightPassed", true)
                checkpoint()
                status("TENSOR_PREFLIGHT_COMPLETED")
                for ((index, case) in cases.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    requireIdle(shared)
                    check(Thread.currentThread().id == threadId)
                    val historical = reference.rows.getValue(case.id)
                    val drawing = case.open()
                    try {
                        verifySource(case, drawing, historical)
                        val sourcePixels = diagnosticPixels(drawing.page)
                        val originalQuad = drawing.quad.map { Pt(it.x, it.y) }
                        val row = JSONObject().put("sample", index).put("id", case.id).put("group", case.group)
                            .put("expected", case.expected).put("metadata", case.metadata)
                            .put("sourceWidth", drawing.page.width).put("sourceHeight", drawing.page.height)
                            .put("sourcePixelsArgbSha256", pixelsSha(sourcePixels)).put("quad", quadJson(originalQuad))
                            .put("historicalPublic", historical.getJSONObject("candidatePublic"))
                            .put(
                                "historicalCorrect",
                                historical.getJSONObject("candidatePublic").getString("text") == case.expected,
                            )
                        val rawRows = JSONArray()
                        row.put("rawObservations", rawRows)
                        rows.put(row)
                        for (margin in MARGINS) {
                            currentCoroutineContext().ensureActive()
                            val variant = variant(margin)
                            val priorRaw = rawReference(reference, case.id, margin)
                            val (strip, expanded) = probe.crop(drawing.page, originalQuad, variant)
                            try {
                                val tensor = probe.tensor(strip, variant)
                                verifyTensor(tensor, expanded, priorRaw)
                                val chwHash = diagnosticFloatSha(tensor.chw)
                                val rawRow = JSONObject().put("rightMargin", margin)
                                    .put("expandedQuad", quadJson(expanded)).put("tensor", tensor.json())
                                rawRows.put(rawRow)
                                check(probe.metadata().toString() == metadata.toString())
                                val topK = access.topK(tensor)
                                diagnosticCalls++
                                rawRow.put("topK", topKPayload(topK))
                                check(probe.metadata().toString() == metadata.toString())
                                val old = probe.infer(tensor, baseline = false)
                                legacyCalls++
                                rawRow.put("legacyCrossForward", old)
                                val observed = validateTopK(topK, old, dictionary)
                                rawRow.put("parity", observed)
                                allSteps += topK.steps
                                val historicalRaw = priorRaw.getJSONObject("raw")
                                check(old.getString("text") == historicalRaw.getString("text"))
                                check(
                                    floatBits(old.getDouble("probability")) ==
                                        floatBits(historicalRaw.getDouble("probability")),
                                )
                                check(
                                    old.getJSONArray("emissions").toString() ==
                                        historicalRaw.getJSONArray("emissions").toString(),
                                )
                                check(tensor.chw.size == tensor.width * tensor.height * 3)
                                check(diagnosticFloatSha(tensor.chw) == chwHash)
                                rawRow.put("historicalRawText", historicalRaw.getString("text"))
                                    .put("historicalRawGreedyParity", true).put("chwUnchangedByForwards", true)
                                // Keep the actual old all-step arrays, without duplicating token/probability
                                // objects. Float raw bits preserve negative zero across JSON parsers.
                                val oldSteps = old.getJSONArray("steps")
                                old.put(
                                    "ids",
                                    JSONArray(
                                        (0 until oldSteps.length()).map {
                                            oldSteps.getJSONObject(it).getInt("id")
                                        },
                                    ),
                                ).put(
                                    "logp",
                                    JSONArray(
                                        (0 until oldSteps.length()).map {
                                            oldSteps.getJSONObject(it).getDouble("logProbability")
                                        },
                                    ),
                                ).put(
                                    "logpFloat32Bits",
                                    JSONArray(
                                        (0 until oldSteps.length()).map {
                                            floatBits(oldSteps.getJSONObject(it).getDouble("logProbability"))
                                        },
                                    ),
                                )
                                old.remove("steps")
                            } finally {
                                strip.recycle()
                            }
                        }
                        val line = TextLine(originalQuad.map { Pt(it.x, it.y) }, 1f)
                        val publicStart = SystemClock.elapsedRealtimeNanos()
                        val kept = ocr.recognize(drawing.page, listOf(line))
                        publicCalls++
                        val historicalPublic = historical.getJSONObject("candidatePublic")
                        val predictionParity = line.text == historicalPublic.getString("text")
                        val directionParity = line.direction == historicalPublic.getString("direction")
                        row.put(
                            "actualPublic",
                            JSONObject().put("text", line.text).put("direction", line.direction)
                                .put("matchesGroundTruth", line.text == case.expected)
                                .put("intentionallyKept", line in kept).put("callWallMs", elapsed(publicStart)),
                        ).put("historicalPublicPredictionParity", predictionParity)
                            .put("historicalPublicDirectionParity", directionParity)
                            .put(
                                "historicalCorrectRegressed",
                                row.getBoolean("historicalCorrect") && line.text != case.expected,
                            )
                        check(kept.isEmpty() && predictionParity && directionParity)
                        check(line.quad == originalQuad && drawing.quad == originalQuad)
                        check(sourcePixels.contentEquals(diagnosticPixels(drawing.page)))
                        row.put("sourcePixelsAndGeometryUnchanged", true)
                        if ((index + 1) % 10 == 0) {
                            checkpoint()
                            status("COLLECTING")
                        }
                    } finally {
                        drawing.page.recycle()
                    }
                }
                check(probe.explicitNativeCalls == 240 && probe.privateInferParityCalls == 0)
                check(probe.metadata().toString() == metadata.toString())
            }
            check(rows.length() == 120 && diagnosticCalls == 240 && legacyCalls == 240 && publicCalls == 120)
            val summary = summarize(rows)
            report.put("summary", summary)
            check(summary.getInt("historicalPublicCorrect") == 107 && summary.getInt("actualPublicCorrect") == 107)
            check(summary.getInt("historicalCorrectRegressions") == 0)
            check(summary.getJSONArray("historicalErrorIds").length() == 13)
            check(summary.getInt("uniqueHistoricalErrorSourcePixelHashes") == 11)
            check(summary.getJSONArray("clippedHistoricalErrorIds").length() == 3)
            report.put("existingPublic107Preserved", true).put("all120PublicPredictionsReproduced", true)
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
                        .put("passed", false).put("collectionValidationPassed", false)
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            report.put("privateOcrCreated", privateOcr != null)
            cleanup("privateNativeOcrClosed") {
                privateOcr?.let { check(ocrField("ncnnHandle").getLong(it) == 0L) }
            }
            cleanup("pinnedInputFilesUnchanged") { input.verifyUnchanged() }
            cleanup("historicalReferenceUnchanged") { reference.verifyUnchanged() }
            if (beforeModels != null) {
                cleanup("existingSevenModelsUnchanged") {
                    val after = fingerprintModels(File(context.filesDir, "models"))
                    report.put("modelsAfter", after)
                    check(beforeModels.toString() == after.toString())
                }
            } else {
                report.put("existingSevenModelsUnchanged", JSONObject.NULL)
                    .put("modelFingerprintPreflightCompleted", false)
            }
            cleanup("sharedEngineStillAbsentAndQueuesIdle") { requireIdle(shared) }
            cleanup("temporaryLiveTranslateRestoredIncludingPresenceAndDisk") { saved.close() }
            report.put("diagnosticForwardsCompleted", diagnosticCalls)
                .put("separateLegacyForwardsCompleted", legacyCalls)
                .put("publicRecognizeCallsCompleted", publicCalls).put("allObservedTimeSteps", allSteps)
                .put("collectionValidationPassed", failure == null && report.optBoolean("collectionValidationPassed"))
                .put("passed", failure == null).put("wallMs", elapsed(started))
            cleanup("terminalStatusSent") { status(if (failure == null) "COMPLETED" else "FAILED") }
            cleanup("finalReportWritten") {
                report.put("finalReportWritten", true)
                checkpoint()
            }
        }
        failure?.let { throw it }
        check(report.getBoolean("collectionValidationPassed"))
        check(output.listFiles()?.map { it.name }?.toSet() == setOf("result.json"))
    }

    private fun verifySource(case: OcrRightContextCase, drawing: OcrRightContextDrawing, prior: JSONObject) {
        check(case.group == prior.getString("group") && case.expected == prior.getString("expected"))
        check(drawing.page.width == prior.getInt("sourceWidth") && drawing.page.height == prior.getInt("sourceHeight"))
        check(pixelsSha(diagnosticPixels(drawing.page)) == prior.getString("sourcePixelsArgbSha256"))
        check(quadJson(drawing.quad).toString() == prior.getJSONArray("quad").toString())
    }

    private fun rawReference(reference: HistoricalReference, id: String, margin: Int): JSONObject {
        val raw = reference.rows.getValue(id).getJSONArray("rawObservations")
        val matches = (0 until raw.length()).map { raw.getJSONObject(it) }
            .filter { it.getInt("rightMargin") == margin }
        check(matches.size == 1)
        return matches.single()
    }

    private fun variant(margin: Int) = OcrDiagnosticVariant("fixed-margin-$margin", 4, margin, true)

    private fun verifyTensor(tensor: OcrDiagnosticTensor, expanded: List<Pt>, prior: JSONObject) {
        check(quadJson(expanded).toString() == prior.getJSONArray("expandedQuad").toString())
        val expected = prior.getJSONObject("tensor")
        check(tensor.width == expected.getInt("width") && tensor.height == expected.getInt("height"))
        check(tensor.height == 48 && tensor.chw.size == tensor.width * tensor.height * 3)
        check(tensor.json().getBoolean("productionPreprocessParity"))
        check(diagnosticFloatSha(tensor.chw) == expected.getString("float32LittleEndianSha256"))
    }

    private fun topKPayload(result: OcrCtcTopKDiagnosticResult): JSONObject = JSONObject()
        .put("steps", result.steps).put("vocabularySize", result.vocabularySize).put("k", result.k)
        .put("layout", "Flat step-major arrays: topK offset = step * k + rank; blank/greedy offset = step")
        .put("ids", JSONArray(result.ids.toList())).put("logp", jsonFloats(result.logp))
        .put("logpFloat32Bits", JSONArray(result.logp.map { it.toRawBits() }))
        .put("blankLogp", jsonFloats(result.blankLogp))
        .put("blankLogpFloat32Bits", JSONArray(result.blankLogp.map { it.toRawBits() }))
        .put("greedyIds", JSONArray(result.greedyIds.toList())).put("greedyLogp", jsonFloats(result.greedyLogp))
        .put("greedyLogpFloat32Bits", JSONArray(result.greedyLogp.map { it.toRawBits() }))
        .put("nonFiniteJsonScope", "Nonfinite floats serialize as null; their raw bits remain and validation fails")

    private fun jsonFloats(values: FloatArray) = JSONArray().apply {
        values.forEach { put(if (it.isFinite()) it.toDouble() else JSONObject.NULL) }
    }

    private fun validateTopK(
        result: OcrCtcTopKDiagnosticResult,
        old: JSONObject,
        dictionary: List<String>,
    ): JSONObject {
        check(result.k == 8 && result.steps in 1..2048 && result.vocabularySize == dictionary.size)
        check(result.ids.size == result.steps * 8 && result.logp.size == result.steps * 8)
        check(result.blankLogp.size == result.steps && result.greedyIds.size == result.steps)
        check(result.greedyLogp.size == result.steps)
        check(old.getInt("timeSteps") == result.steps && old.getBoolean("decoderParity"))
        val oldSteps = old.getJSONArray("steps")
        check(oldSteps.length() == result.steps)
        val text = StringBuilder()
        var last = 0
        var sum = 0.0
        var emissions = 0
        for (step in 0 until result.steps) {
            val offset = step * 8
            val ids = (0 until 8).map { result.ids[offset + it] }
            val logp = (0 until 8).map { result.logp[offset + it] }
            check(ids.distinct().size == 8 && ids.all { it in dictionary.indices })
            check(logp.all { it.isFinite() && it <= 0f })
            check(logp.zipWithNext().all { it.first >= it.second })
            // The API orders exact raw-logit ties, not rounded logp ties. Equal returned
            // logp does not justify an ID-order assertion without the original logits.
            val blank = result.blankLogp[step]
            check(blank.isFinite() && blank <= 0f)
            val blankRank = ids.indexOf(0)
            if (blankRank >= 0) {
                check(logp[blankRank].toRawBits() == blank.toRawBits())
            } else {
                check(blank <= logp.last())
            }
            val greedy = result.greedyIds[step]
            val greedyLogp = result.greedyLogp[step]
            check(greedy in dictionary.indices && greedyLogp.isFinite() && greedyLogp <= 0f)
            check(ids.first() == greedy && logp.first().toRawBits() == greedyLogp.toRawBits())
            val oldStep = oldSteps.getJSONObject(step)
            check(oldStep.getInt("id") == greedy)
            check(floatBits(oldStep.getDouble("logProbability")) == greedyLogp.toRawBits())
            val probabilityMass = logp.sumOf { exp(it.toDouble()) }
            check(probabilityMass <= 1.00001)
            if (greedy != 0 && greedy != last) {
                val token = dictionary[greedy]
                text.append(if (token == "<SP>") " " else token)
                sum += greedyLogp
                emissions++
            }
            last = greedy
        }
        val probability = if (emissions == 0) 0f else exp(sum / emissions).toFloat()
        check(text.toString() == old.getString("text"))
        check(probability.toRawBits() == floatBits(old.getDouble("probability")))
        return JSONObject().put("steps", result.steps).put("vocabularySize", result.vocabularySize).put("k", 8)
            .put("greedyText", text.toString()).put("allStepsValidated", result.steps)
            .put("greedyProbability", probability.toDouble())
            .put("rank0SameForwardLegacyParity", true).put("separateLegacyForwardParity", true)
            .put("legacyDecoderParity", true)
    }

    private fun summarize(rows: JSONArray): JSONObject {
        val all = (0 until rows.length()).map { rows.getJSONObject(it) }
        val errors = all.filter { !it.getBoolean("historicalCorrect") }
        val groups = JSONObject()
        all.groupBy { it.getString("group") }.forEach { (name, cohort) ->
            groups.put(
                name,
                JSONObject().put("samples", cohort.size)
                    .put("historicalPublicCorrect", cohort.count { it.getBoolean("historicalCorrect") })
                    .put(
                        "actualPublicCorrect",
                        cohort.count { it.getJSONObject("actualPublic").getBoolean("matchesGroundTruth") },
                    ),
            )
        }
        return JSONObject().put("samples", all.size).put("groups", groups)
            .put("historicalPublicCorrect", all.count { it.getBoolean("historicalCorrect") })
            .put("actualPublicCorrect", all.count { it.getJSONObject("actualPublic").getBoolean("matchesGroundTruth") })
            .put("historicalCorrectRegressions", all.count { it.getBoolean("historicalCorrectRegressed") })
            .put("historicalErrorIds", JSONArray(errors.map { it.getString("id") }))
            .put(
                "uniqueHistoricalErrorSourcePixelHashes",
                errors.map { it.getString("sourcePixelsArgbSha256") }.distinct().size,
            )
            .put(
                "clippedHistoricalErrorIds",
                JSONArray(
                    errors.filter { it.getJSONObject("metadata").optInt("rightClipPixels") > 0 }
                        .map { it.getString("id") },
                ),
            )
    }

    /** Only fields missing from the reusable probe API; no alternate preprocessing or PE. */
    private class DiagnosticAccess(private val ocr: Ocr) {
        private val threadId = Thread.currentThread().id
        private val handle = ocrField("ncnnHandle").getLong(ocr).also { check(it != 0L) }
        val threads = ocrField("threads").getInt(ocr)
        private val pe = (ocrField("PE\$delegate").get(null) as Lazy<*>).value as FloatArray

        fun topK(tensor: OcrDiagnosticTensor): OcrCtcTopKDiagnosticResult {
            check(Thread.currentThread().id == threadId && ocrField("ncnnHandle").getLong(ocr) == handle)
            val steps = tensor.width / 4 - 1
            check(steps in 1..2048 && steps * 320 <= pe.size)
            return NcnnBackend.ocrCtcTopKDiagnostic(
                handle,
                tensor.chw,
                tensor.width,
                tensor.height,
                pe,
                steps,
                threads,
            ).also { check(it.steps == steps && Thread.currentThread().id == threadId) }
        }
    }

    private fun requireIdle(shared: TranslationEngineService) {
        check(!shared.loading.value && !shared.warm.value)
        check(TranslationBusy.active.value == 0 && Injekt.get<TranslationManager>().queueState.value.isEmpty())
        val downloads = Injekt.get<DownloadManager>()
        check(downloads.queueState.value.isEmpty() && !downloads.isRunning)
        val engine = TranslationEngineService::class.java.getDeclaredField("engine").apply { isAccessible = true }
            .get(shared)
        val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight").apply { isAccessible = true }
            .get(shared) as AtomicInteger
        check(engine == null && inFlight.get() == 0)
    }

    private fun fingerprintModels(directory: File) = JSONArray(
        MODEL_NAMES.map { name ->
            val file = File(directory, name)
            check(file.isFile && file.length() > 0)
            check(file.canonicalFile.parentFile == directory.canonicalFile)
            JSONObject().put("name", name).put("bytes", file.length()).put("sha256", diagnosticSha(file))
        },
    )

    private class HistoricalReference(private val parent: File, basename: String) {
        private val directory = File(parent, basename)
        private val file = File(directory, "result.json")
        val report: JSONObject
        val rows: Map<String, JSONObject>

        init {
            check(basename.matches(Regex("ocr-topk-reference-$UUID_PATTERN")))
            verifyUnchanged()
            report = JSONObject(file.readText(Charsets.UTF_8))
            check(report.getBoolean("passed") && report.getBoolean("accuracyGatePassed"))
            check(report.getString("candidatePolicy") == HorizontalOcrContextPolicy.GUARDED_32.name)
            check(report.getJSONArray("rawMargins").toString() == JSONArray(MARGINS).toString())
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
            check(directory.canonicalFile.parentFile == parent && directory.list()?.toSet() == setOf("result.json"))
            check(file.isFile && file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == directory)
            check(file.length() == REFERENCE_BYTES && diagnosticSha(file) == REFERENCE_SHA)
        }

        fun description() = JSONObject().put("basename", directory.name).put("name", "result.json")
            .put("bytes", REFERENCE_BYTES).put("sha256", REFERENCE_SHA)
            .put("publicPolicy", "GUARDED_32").put("historicalCorrect", 107).put("historicalErrors", 13)
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
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun bytesSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun elapsed(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    private fun floatBits(value: Double) = value.toFloat().toRawBits()

    private companion object {
        const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        const val REFERENCE_BYTES = 903_253L
        const val REFERENCE_SHA = "ed39996cafe9225ed8bc365390e78e3413c99da909668655e2c079e25d9dad66"
        const val INPUT_MANIFEST_SHA = "3178bfe3563ea01ce40423d7f9fb7579160797b42c190d416b29cb0c36ec015a"
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        val MARGINS = listOf(16, 32)
        val BACKEND_KEYS = listOf("backend", "nativeThreads", "serialize", "peElements", "peFloat32LittleEndianSha256")
        val MODEL_NAMES = listOf(
            "dbnet_detect.ncnn.param",
            "dbnet_detect.ncnn.bin",
            "ocr_48px_ctc.ncnn.param",
            "ocr_48px_ctc_mixed.ncnn.param",
            "ocr_48px_ctc.ncnn.bin",
            "mit_aot_fixed512.ncnn.param",
            "mit_aot_fixed512.ncnn.bin",
        )

        fun ocrField(name: String) = Ocr::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
}
