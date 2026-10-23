package mihon.translation

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
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
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
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

/**
 * Opt-in, paired native accuracy collection. Every source runs actual public recognize twice:
 * unchanged GUARDED_32 defaults with unsharp on, then the same config with only unsharp off.
 * A collection pass never means the candidate preserves accuracy or is ready for production.
 * The pinned earlier report identifies the already-correct 107; its errors are not ground truth.
 */
@RunWith(AndroidJUnit4::class)
class OcrUnsharpHoldoutDiagnosticTest {
    @Test
    fun fixedUnsharpConditionsCollectPublicResultsWithoutPromotingTheCandidate() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "ocr-unsharp-holdout") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(15 * 60 * 1000L) { collect() }
                }
            }
    }

    private suspend fun collect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("ocrUnsharpHoldout") == "true") {
            "Explicit ocrUnsharpHoldout=true is required; missing opt-in is not a pass or skip"
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
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val reference = HistoricalReference(
            external,
            checkNotNull(arguments.getString("ocrUnsharpReference")) { "A pinned reference UUID basename is required" },
        )
        val cases = input.fixedCases() + rightContextGeneratedCases()
        check(cases.size == 120 && cases.map { it.id }.distinct().size == 120)
        check(cases.map { it.id }.toSet() == reference.rows.keys)
        check(cases.all { reference.rows.getValue(it.id).getString("expected") == it.expected })
        val baselineConfig = OcrConfig()
        check(baselineConfig.horizontalContextPolicy == HorizontalOcrContextPolicy.GUARDED_32)
        check(baselineConfig.ocrUnsharp && baselineConfig.useBicubic && baselineConfig.stripPad == 4)
        check(baselineConfig.textHeight == 48 && baselineConfig.ignoreBubble == 0)
        val candidateConfig = baselineConfig.copy(ocrUnsharp = false)
        check(candidateConfig.copy(ocrUnsharp = true) == baselineConfig && !candidateConfig.ocrUnsharp) {
            "Unsharp must be the only configuration intervention"
        }
        val shared = Injekt.get<TranslationEngineService>()
        val translations = Injekt.get<TranslationManager>()
        withTimeout(10_000) { translations.ensureRestored() }
        requireIdle(shared)
        val saved = OnlineReaderSettings(context)
        val output = File(external, "ocr-unsharp-holdout-${UUID.randomUUID()}")
        check(output.mkdir() && output.canonicalFile == output.absoluteFile && output.parentFile == external)
        val reportFile = File(output, "result.json")
        val rows = JSONArray()
        val started = SystemClock.elapsedRealtimeNanos()
        val threadId = Thread.currentThread().id
        val report = JSONObject().put("passed", false).put("diagnosticOnly", true)
            .put("collectionValidationPassed", false).put("accuracyGateIsPassCriterion", false)
            .put("boundedAccuracyGatePassed", JSONObject.NULL).put("promotionEligible", false)
            .put("promotionScope", "Requires independent new holdout and public integration; not evaluated here")
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("process64Bit", true)
            .put("javaThreadId", threadId).put("javaThreadName", Thread.currentThread().name)
            .put("androidTid", Process.myTid()).put("sameThreadSerialPublicCalls", true)
            .put("expectedSamples", 120).put("expectedPublicRecognizeCalls", 240)
            .put("inputBasename", inputName).put("pinnedInputFiles", input.sourceManifest)
            .put("inputManifestSha256", bytesSha(input.manifestBytes))
            .put("historicalReference", reference.description())
            .put("baselineConfig", configJson(baselineConfig)).put("candidateConfig", configJson(candidateConfig))
            .put("onlyConfigDifference", "ocrUnsharp: true -> false")
            .put("conditionOrder", JSONArray(listOf("unsharpOn", "unsharpOff")))
            .put("perSampleCandidateSelection", false).put("modelOutputRewrittenByTest", false)
            .put("testTextNormalization", "None; exact Unicode strings, no trim or whitespace replacement")
            .put("publicScope", "Actual Ocr.recognize including guarded context, correction, filtering and recovery")
            .put("rawInferenceObservations", false).put("rawNativeCalls", 0)
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
        fun checkpoint() = reportFile.writeText(report.toString(2))
        fun status(phase: String) {
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "OCR_UNSHARP_HOLDOUT=" + JSONObject().put("phase", phase).put("samples", rows.length())
                            .put("publicCallsCompleted", baselineCalls + candidateCalls)
                            .put("report", reportFile.absolutePath),
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
            check(beforeModels.toString() == reference.report.getJSONArray("modelsAfter").toString()) {
                "Pinned historical model fingerprints differ"
            }
            report.put("modelsBefore", beforeModels).put("sameSevenModelsAsHistoricalReference", true)
            checkpoint()
            status("STARTED")
            val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
            val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
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
                    val selected = baselineProbe.selectedParam(param.path)
                    check(selected == candidateProbe.selectedParam(param.path))
                    check(File(selected.first).name == reference.report.getString("selectedParam"))
                    check(selected.second == reference.report.getBoolean("selectedMixed"))
                    report.put("baselineBackend", baselineBackend).put("candidateBackend", candidateBackend)
                        .put("selectedParam", File(selected.first).name).put("selectedMixed", selected.second)
                        .put("twoPrivateOcrInstances", true).put("probeUsedForMetadataOnly", true)
                        .put("backendAndPeMatchHistoricalReference", true)
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
                                .put("historicalGuarded32Text", historicalText)
                                .put("historicalCorrect", historicalText == case.expected)
                                .put("historicalSourceAndGeometryMatch", true)
                            rows.put(row)
                            val baselineLine = TextLine(quad.map { Pt(it.x, it.y) }, 1f)
                            val baselineStart = SystemClock.elapsedRealtimeNanos()
                            val baselineKept = baseline.recognize(drawing.page, listOf(baselineLine))
                            baselineCalls++
                            row.put("unsharpOn", publicJson(baselineLine, baselineKept, case.expected, baselineStart))
                            check(
                                baselineLine.quad == quad && sourcePixels.contentEquals(diagnosticPixels(drawing.page)),
                            )
                            val candidateLine = TextLine(quad.map { Pt(it.x, it.y) }, 1f)
                            val candidateStart = SystemClock.elapsedRealtimeNanos()
                            val candidateKept = candidate.recognize(drawing.page, listOf(candidateLine))
                            candidateCalls++
                            row.put(
                                "unsharpOff",
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
                "Unsharp-on historical public predictions changed; retain the collected rows"
            }
            val directionsReproduced = (0 until rows.length()).all {
                rows.getJSONObject(it).getBoolean("baselineHistoricalDirectionReproduced")
            }
            check(directionsReproduced)
            check(summary.getInt("unsharpOnExact") == 107 && summary.getInt("historicalCorrect") == 107)
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
                val after = fingerprintModels(File(context.filesDir, "models"))
                report.put("modelsAfter", after)
                check(beforeModels.length() == 7 && beforeModels.toString() == after.toString())
            }
            cleanup("sharedEngineStillAbsentAndQueuesIdle") { requireIdle(shared) }
            cleanup("temporaryLiveTranslateRestoredIncludingPresenceAndDisk") { saved.close() }
            report.put("publicUnsharpOnCallsCompleted", baselineCalls)
                .put("publicUnsharpOffCallsCompleted", candidateCalls)
                .put("collectionValidationPassed", failure == null && report.optBoolean("collectionValidationPassed"))
                .put("passed", failure == null).put("wallMs", elapsed(started))
            cleanup("finalReportWritten") { checkpoint() }
            cleanup("terminalStatusSent") { status(if (failure == null) "COMPLETED" else "FAILED") }
        }
        failure?.let { throw it }
        check(report.getBoolean("collectionValidationPassed"))
    }

    private fun publicJson(line: TextLine, kept: Set<TextLine>, expected: String, started: Long) =
        JSONObject().put("text", line.text).put("direction", line.direction)
            .put("matchesGroundTruth", line.text == expected).put("intentionallyKept", line in kept)
            .put("errorShape", errorShape(expected, line.text)).put("callWallMs", elapsed(started))

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
            .put("unsharpOnExact", selected.count { it.getJSONObject("unsharpOn").getBoolean("matchesGroundTruth") })
            .put("unsharpOffExact", selected.count { it.getJSONObject("unsharpOff").getBoolean("matchesGroundTruth") })
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
        for (condition in listOf("unsharpOn", "unsharpOff")) {
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

    private fun fingerprintModels(directory: File) = JSONArray(
        MODEL_NAMES.map { name ->
            val file = File(directory, name)
            check(file.isFile && file.length() > 0)
            check(file.canonicalFile.parentFile == directory.canonicalFile)
            JSONObject().put("name", name).put("bytes", file.length()).put("sha256", diagnosticSha(file))
        },
    )

    private fun configJson(config: OcrConfig) = JSONObject().put("dataClassValue", config.toString())
        .put("ocrUnsharp", config.ocrUnsharp).put("horizontalContextPolicy", config.horizontalContextPolicy.name)
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
            check(basename.matches(Regex("ocr-unsharp-reference-$UUID_PATTERN")))
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
        const val REFERENCE_BYTES = 903_253L
        const val REFERENCE_SHA = "ed39996cafe9225ed8bc365390e78e3413c99da909668655e2c079e25d9dad66"
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
