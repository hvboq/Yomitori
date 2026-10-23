package mihon.translation

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Native holdout: the actual public OCR candidate must preserve every already-correct result. */
@RunWith(AndroidJUnit4::class)
class OcrRightContextHoldoutTest {
    /** Collection is explicit and does not turn imperfect OCR into an accuracy pass. */
    @Test
    fun collectPublicPoliciesWithoutAnAccuracyPassClaim() = execute(enforceNoRegression = false)

    /** Root normally selects only this method; all rows are collected before the gate is asserted. */
    @Test
    fun candidatePreservesEveryCorrectPublicBaselineOnFrozenAndNewSources() = execute(enforceNoRegression = true)

    private fun execute(enforceNoRegression: Boolean) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "ocr-right-context-holdout") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(15 * 60 * 1000L) { collect(enforceNoRegression) }
                }
            }
    }

    private suspend fun collect(enforceNoRegression: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("ocrRightContextHoldout") == "true") { "Explicit ocrRightContextHoldout=true is required" }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation is required" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Actual ARM64 process is required" }
        val inputName = checkNotNull(args.getString("ocrRightContextInput")) {
            "A pinned UUID input basename is required"
        }
        val input = OcrRightContextInputs(
            context,
            inputName,
            instrumentation.context.assets.open("ocr-right-context/input-manifest.json").use { it.readBytes() },
        )
        val margins = (args.getString("ocrRightContextRawMargins") ?: "16,32").split(',').map { it.toInt() }
        check(margins.distinct() == margins && margins.isNotEmpty() && margins.all { it in listOf(16, 24, 32, 48) }) {
            "Raw margins must be a unique subset of 16,24,32,48"
        }
        check(16 in margins && 32 in margins) { "The raw baseline and 32-column observation must both be present" }
        val shared = Injekt.get<TranslationEngineService>()
        requireSharedIdle(shared)
        val baselineConfig = OcrConfig()
        check(baselineConfig.horizontalContextPolicy == HorizontalOcrContextPolicy.LEGACY_16)
        check(baselineConfig.stripPad == 4 && baselineConfig.textHeight == 48 && baselineConfig.ignoreBubble == 0)
        val candidateConfig = baselineConfig.copy(horizontalContextPolicy = HorizontalOcrContextPolicy.GUARDED_32)
        val cases = input.fixedCases() + rightContextGeneratedCases()
        check(cases.size == 120 && cases.map { it.id }.distinct().size == 120)
        val output = File(
            checkNotNull(context.getExternalFilesDir(null)),
            "ocr-right-context-holdout-${UUID.randomUUID()}",
        )
        check(output.mkdir())
        val reportFile = File(output, "result.json")
        val artifacts = OcrDiagnosticArtifacts(output)
        val rows = JSONArray()
        val start = SystemClock.elapsedRealtimeNanos()
        val threadId = Thread.currentThread().id
        val report = JSONObject().put("passed", false).put("collectionValidationPassed", false)
            .put("accuracyGateEnforced", enforceNoRegression).put("accuracyGatePassed", JSONObject.NULL)
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("process64Bit", true).put("javaThreadId", threadId)
            .put("javaThreadName", Thread.currentThread().name).put("androidTid", Process.myTid())
            .put("inputBasename", inputName).put("pinnedInputFiles", input.sourceManifest)
            .put("inputManifestSha256", bytesSha(input.manifestBytes))
            .put("baselinePolicy", baselineConfig.horizontalContextPolicy.name)
            .put("candidatePolicy", candidateConfig.horizontalContextPolicy.name)
            .put("rawMargins", JSONArray(margins)).put("expectedSamples", cases.size).put("samples", rows)
            .put("globalPreferencesChanged", false).put("sharedEngineShutdownCalled", false)
            .put("modelsCopiedOrDownloaded", false).put("llmOrOtherNetworkCalls", false)
            .put("actualReaderActivity", false).put("signedRelease", false)
            .put("publicPolicyScope", "Actual Ocr.recognize including correction, filtering and recovery")
            .put("rawScope", "Separate raw native margin interventions; not the public candidate result")
            .put("candidateForwardMarginsInstrumented", false)
            .put(
                "candidateFallbackScope",
                "Configured GUARDED_32 may select a whole LEGACY_16 result; forward count is not guessed",
            )
            .put(
                "accuracyScope",
                "Exact original rendering strings. Baseline errors stay errors and never become ground truth",
            )
            .put("clipScope", "Clipped-source controls keep the original full rendering text as independent truth")
            .put("sourcePixelHashLayout", "Row-major bytes A,R,G,B; independent of PNG encoding")
            .put("fontFamilyScope", "Requested families are compared by actual source and raw tensor hashes")
            .put("distinctJapaneseFontBinaryProved", false)
            .put(
                "timingScope",
                "Fixed policy order, serial native calls, observation and PNG overhead; not a speed benchmark",
            )
            .put("allOutputArtifactsRetained", true)
            .put("cleanupScope", "Only exact owned UUID output files in ownedNeutralFiles")
        var failure: Throwable? = null
        var baselineProbe: OcrDiagnosticProbe? = null
        var candidateProbe: OcrDiagnosticProbe? = null
        var beforeModels = JSONArray()
        fun checkpoint() {
            report.put("ownedNeutralFiles", artifacts.manifest())
            reportFile.writeText(report.toString(2))
        }
        try {
            checkpoint()
            val models = File(context.filesDir, "models")
            beforeModels = fingerprintModels(models)
            report.put("modelsBefore", beforeModels)
            val dictionary = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
            val param = File(models, "ocr_48px_ctc.ncnn.param")
            val baselineStart = SystemClock.elapsedRealtimeNanos()
            Ocr(param.path, dictionary, baselineConfig).use { baseline ->
                report.put("baselineCreateMs", elapsed(baselineStart))
                baselineProbe = OcrDiagnosticProbe(baseline, dictionary)
                val candidateStart = SystemClock.elapsedRealtimeNanos()
                Ocr(param.path, dictionary, candidateConfig).use { candidate ->
                    report.put("candidateCreateMs", elapsed(candidateStart))
                    candidateProbe = OcrDiagnosticProbe(candidate, dictionary)
                    report.put("baselineBackend", checkNotNull(baselineProbe).metadata())
                        .put("candidateBackend", checkNotNull(candidateProbe).metadata())
                        .put("twoPrivateOcrInstances", true)
                    val selected = checkNotNull(baselineProbe).selectedParam(param.path)
                    val selectedCandidate = checkNotNull(candidateProbe).selectedParam(param.path)
                    check(selected == selectedCandidate)
                    report.put("selectedParam", File(selected.first).name).put("selectedMixed", selected.second)
                    for ((index, case) in cases.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        requireSharedIdle(shared)
                        check(Thread.currentThread().id == threadId)
                        report.put("currentSample", index).put("currentId", case.id)
                        checkpoint()
                        val drawing = case.open()
                        try {
                            val pixels = diagnosticPixels(drawing.page)
                            val originalQuad = drawing.quad.map { Pt(it.x, it.y) }
                            val row = JSONObject().put("sample", index).put("id", case.id).put("group", case.group)
                                .put("expected", case.expected).put("metadata", case.metadata)
                                .put("sourceWidth", drawing.page.width).put("sourceHeight", drawing.page.height)
                                .put("sourcePixelsArgbSha256", pixelsSha(pixels)).put("quad", quadJson(originalQuad))
                            if (case.group.startsWith("new-")) {
                                val sourceName = "sample-${index.toString().padStart(3, '0')}-source.png"
                                row.put(
                                    "sourceArtifact",
                                    artifacts.bitmap(sourceName, drawing.page),
                                )
                            }
                            rows.put(row)
                            val rawRows = JSONArray()
                            row.put("rawObservations", rawRows)
                            for (margin in margins) {
                                currentCoroutineContext().ensureActive()
                                val variant = OcrDiagnosticVariant("margin$margin", 4, margin, true)
                                val (strip, expanded) = checkNotNull(baselineProbe).crop(
                                    drawing.page,
                                    originalQuad,
                                    variant,
                                )
                                try {
                                    val tensor = checkNotNull(baselineProbe).tensor(strip, variant)
                                    val native = checkNotNull(baselineProbe).infer(tensor, baseline = margin == 16)
                                    // Full step traces already exist in the preprocessing diagnostic;
                                    // this holdout keeps bounded raw text/emissions and exact tensor identity.
                                    native.remove("steps")
                                    rawRows.put(
                                        JSONObject().put("rightMargin", margin).put("expandedQuad", quadJson(expanded))
                                            .put("tensor", tensor.json()).put("raw", native)
                                            .put("matchesGroundTruth", native.getString("text") == case.expected),
                                    )
                                } finally {
                                    strip.recycle()
                                }
                            }
                            val baselineLine = TextLine(originalQuad.map { Pt(it.x, it.y) }, 1f)
                            val baselineTime = SystemClock.elapsedRealtimeNanos()
                            val baselineKept = baseline.recognize(drawing.page, listOf(baselineLine))
                            row.put(
                                "baselinePublic",
                                publicJson(baselineLine, baselineKept, case.expected, baselineTime),
                            )
                            val candidateLine = TextLine(originalQuad.map { Pt(it.x, it.y) }, 1f)
                            val candidateTime = SystemClock.elapsedRealtimeNanos()
                            val candidateKept = candidate.recognize(drawing.page, listOf(candidateLine))
                            row.put(
                                "candidatePublic",
                                publicJson(candidateLine, candidateKept, case.expected, candidateTime),
                            )
                            check(baselineKept.isEmpty() && candidateKept.isEmpty()) {
                                "Intentional filtering is disabled"
                            }
                            check(baselineLine.quad == originalQuad && candidateLine.quad == originalQuad)
                            check(drawing.quad == originalQuad && pixels.contentEquals(diagnosticPixels(drawing.page)))
                            row.put("sourcePixelsAndQuadUnchanged", true)
                            row.put(
                                "correctBaselineRegressed",
                                baselineLine.text == case.expected && candidateLine.text != case.expected,
                            ).put(
                                "baselineErrorImproved",
                                baselineLine.text != case.expected && candidateLine.text == case.expected,
                            )
                            checkpoint()
                        } finally {
                            drawing.page.recycle()
                        }
                    }
                }
            }
            check(rows.length() == 120)
            val summary = summary(rows)
            report.put("summary", summary).put("collectionValidationPassed", true)
            // Accuracy is evaluated only after every expected/actual row and all artifacts exist.
            val noRegression = summary.getInt("correctBaselineRegressions") == 0
            val savedResults = summary.getJSONObject("groups").getJSONObject("saved16")
            val savedBaselineExact = savedResults.getInt("baselineExact") == 16
            val savedCandidateExact = savedResults.getInt("candidateExact") == 16
            val newBaselineNonVacuous = listOf("new-fonts", "new-decoration", "new-clip-quote").all {
                summary.getJSONObject("groups").getJSONObject(it).getInt("baselineExact") > 0
            }
            val fontComparison = requestedFamilyRasterDifferences(rows)
            val fontDiversity = fontComparison.getInt("sourceRasterDifferentPairs")
            report.put("fontFamilyPairedRasterComparison", fontComparison)
            val accuracyGate = JSONObject()
                .put("noCorrectBaselineRegressions", noRegression)
                .put("savedBaselineAll16Exact", savedBaselineExact)
                .put("savedCandidateAll16Exact", savedCandidateExact)
                .put("newBaselineGroupsNonVacuous", newBaselineNonVacuous)
                .put("requestedFontFamiliesHaveObservedRasterDifference", fontDiversity > 0)
            report.put("accuracyGate", accuracyGate)
            val gate = noRegression && savedBaselineExact && savedCandidateExact &&
                newBaselineNonVacuous && fontDiversity > 0
            report.put("accuracyGatePassed", gate)
            if (enforceNoRegression) {
                check(gate) {
                    "Public candidate failed the holdout gate; retain per-row results and baseline errors"
                }
            }
        } catch (error: Throwable) {
            failure = error
            report.put("errorType", error.javaClass.name).put("error", error.message)
        } finally {
            try {
                input.verifyUnchanged()
                report.put("pinnedInputFilesUnchanged", true)
                val after = fingerprintModels(File(context.filesDir, "models"))
                report.put("modelsAfter", after)
                check(beforeModels.length() == 7 && beforeModels.toString() == after.toString())
                report.put("existingSevenModelsUnchanged", true)
                requireSharedIdle(shared)
                report.put("sharedEngineStillAbsentAndIdle", true)
            } catch (postflight: Throwable) {
                failure?.addSuppressed(postflight) ?: run { failure = postflight }
                report.put("postflightError", "${postflight.javaClass.name}: ${postflight.message}")
            }
            report.put("rawNativeCalls", baselineProbe?.explicitNativeCalls ?: 0)
                .put("baselinePrivateInferParityCalls", baselineProbe?.privateInferParityCalls ?: 0)
                .put("publicBaselineCallsCompleted", completedPublicRows(rows, "baselinePublic"))
                .put("publicCandidateCallsCompleted", completedPublicRows(rows, "candidatePublic"))
                .put("totalNativeForwardCountKnown", false)
                .put("passed", failure == null).put("wallMs", elapsed(start))
            try {
                checkpoint()
            } catch (writeFailure: Throwable) {
                failure?.addSuppressed(writeFailure) ?: run { failure = writeFailure }
            }
            try {
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "stream",
                            "OCR_RIGHT_CONTEXT_HOLDOUT=" + JSONObject().put("passed", failure == null)
                                .put("collectionValidationPassed", report.optBoolean("collectionValidationPassed"))
                                .put("accuracyGateEnforced", enforceNoRegression)
                                .put("accuracyGatePassed", report.opt("accuracyGatePassed"))
                                .put("samples", rows.length()).put("report", reportFile.absolutePath),
                        )
                    },
                )
            } catch (statusFailure: Throwable) {
                failure?.addSuppressed(statusFailure) ?: run { failure = statusFailure }
            }
        }
        failure?.let { throw it }
        assertTrue(report.getBoolean("collectionValidationPassed"))
    }

    private fun publicJson(line: TextLine, kept: Set<TextLine>, expected: String, started: Long) =
        JSONObject().put("text", line.text).put("direction", line.direction)
            .put("matchesGroundTruth", line.text == expected)
            .put("intentionallyKept", line in kept).put("callWallMs", elapsed(started))

    private fun summary(rows: JSONArray): JSONObject {
        val all = (0 until rows.length()).map { rows.getJSONObject(it) }
        val groups = JSONObject()
        for ((name, selected) in all.groupBy { it.getString("group") }) {
            val baselineExact = selected.count { it.getJSONObject("baselinePublic").getBoolean("matchesGroundTruth") }
            val candidateExact = selected.count { it.getJSONObject("candidatePublic").getBoolean("matchesGroundTruth") }
            groups.put(
                name,
                JSONObject().put("samples", selected.size)
                    .put("baselineExact", baselineExact)
                    .put("candidateExact", candidateExact)
                    .put("correctBaselineRegressions", selected.count { it.getBoolean("correctBaselineRegressed") })
                    .put("baselineErrorsImproved", selected.count { it.getBoolean("baselineErrorImproved") }),
            )
        }
        val cohorts = JSONObject()
        val cohortRows = all.groupBy {
            if (it.getString("group").startsWith("new-")) "new68" else it.getString("group")
        }
        for ((name, selected) in cohortRows) {
            val baselineExact = selected.count { it.getJSONObject("baselinePublic").getBoolean("matchesGroundTruth") }
            val candidateExact = selected.count { it.getJSONObject("candidatePublic").getBoolean("matchesGroundTruth") }
            cohorts.put(
                name,
                JSONObject().put("samples", selected.size)
                    .put("baselineExact", baselineExact)
                    .put("candidateExact", candidateExact)
                    .put("correctBaselineRegressions", selected.count { it.getBoolean("correctBaselineRegressed") })
                    .put("baselineErrorsImproved", selected.count { it.getBoolean("baselineErrorImproved") }),
            )
        }
        return JSONObject().put("groups", groups).put("cohorts", cohorts).put("samples", all.size)
            .put("correctBaselineRegressions", all.count { it.getBoolean("correctBaselineRegressed") })
            .put("baselineErrorsImproved", all.count { it.getBoolean("baselineErrorImproved") })
    }

    private fun requestedFamilyRasterDifferences(rows: JSONArray): JSONObject {
        val fonts = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.getString("group") == "new-fonts" }
        val sans = fonts.filter { it.getJSONObject("metadata").getString("requestedFontFamily") == "sans-serif" }
        val comparisons = JSONArray()
        var sourceDifferent = 0
        var nonLatinSourceDifferent = 0
        val tensorDifferent = linkedMapOf<Int, Int>()
        for (first in sans) {
            val meta = first.getJSONObject("metadata")
            val second = fonts.single {
                val other = it.getJSONObject("metadata")
                other.getString("requestedFontFamily") == "serif" &&
                    other.getDouble("fontSize") == meta.getDouble("fontSize") &&
                    other.getBoolean("requestedBold") == meta.getBoolean("requestedBold") &&
                    it.getString("expected") == first.getString("expected")
            }
            val changed = first.getString("sourcePixelsArgbSha256") != second.getString("sourcePixelsArgbSha256")
            val hasLatin = first.getString("expected").any { it in 'A'..'Z' || it in 'a'..'z' }
            if (changed) sourceDifferent++
            if (changed && !hasLatin) nonLatinSourceDifferent++
            val tensors = JSONArray()
            val rawFirst = first.getJSONArray("rawObservations")
            val rawSecond = second.getJSONArray("rawObservations")
            check(rawFirst.length() == rawSecond.length())
            for (index in 0 until rawFirst.length()) {
                val a = rawFirst.getJSONObject(index)
                val b = rawSecond.getJSONObject(index)
                val margin = a.getInt("rightMargin")
                check(margin == b.getInt("rightMargin"))
                val aHash = a.getJSONObject("tensor").getString("float32LittleEndianSha256")
                val bHash = b.getJSONObject("tensor").getString("float32LittleEndianSha256")
                if (aHash != bHash) tensorDifferent[margin] = (tensorDifferent[margin] ?: 0) + 1
                tensors.put(
                    JSONObject().put("rightMargin", margin).put("sansTensorSha256", aHash)
                        .put("serifTensorSha256", bHash).put("different", aHash != bHash),
                )
            }
            comparisons.put(
                JSONObject().put("sansSample", first.getInt("sample"))
                    .put("serifSample", second.getInt("sample")).put("containsLatin", hasLatin)
                    .put("sameExpectedText", first.getString("expected")).put("fontSize", meta.getDouble("fontSize"))
                    .put("bold", meta.getBoolean("requestedBold"))
                    .put("sansSourcePixelsArgbSha256", first.getString("sourcePixelsArgbSha256"))
                    .put("serifSourcePixelsArgbSha256", second.getString("sourcePixelsArgbSha256"))
                    .put("sourceRasterDifferent", changed).put("tensorComparisons", tensors),
            )
        }
        return JSONObject().put("pairs", comparisons).put("pairedSamples", comparisons.length())
            .put("sourceRasterDifferentPairs", sourceDifferent)
            .put("nonLatinSourceRasterDifferentPairs", nonLatinSourceDifferent)
            .put("tensorDifferentPairsByMargin", JSONObject(tensorDifferent.mapKeys { it.key.toString() }))
            .put("scope", "Observed line raster differences, not identification of the underlying Japanese font binary")
    }

    private fun completedPublicRows(rows: JSONArray, field: String) =
        (0 until rows.length()).count { rows.getJSONObject(it).has(field) }

    private fun fingerprintModels(directory: File): JSONArray {
        val names = listOf(
            "dbnet_detect.ncnn.param",
            "dbnet_detect.ncnn.bin",
            "ocr_48px_ctc.ncnn.param",
            "ocr_48px_ctc_mixed.ncnn.param",
            "ocr_48px_ctc.ncnn.bin",
            "mit_aot_fixed512.ncnn.param",
            "mit_aot_fixed512.ncnn.bin",
        )
        return JSONArray(
            names.map { name ->
                val file = File(directory, name)
                check(file.isFile && file.length() > 0 && file.canonicalFile.parentFile == directory.canonicalFile)
                JSONObject().put("name", name).put("bytes", file.length()).put("sha256", diagnosticSha(file))
            },
        )
    }

    private fun requireSharedIdle(service: TranslationEngineService) {
        check(!service.loading.value && !service.warm.value) { "Shared audit engine must be absent and idle" }
        val engine = TranslationEngineService::class.java.getDeclaredField("engine").apply {
            isAccessible = true
        }.get(service)
        val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight").apply {
            isAccessible = true
        }.get(service) as AtomicInteger
        check(engine == null && inFlight.get() == 0) { "Do not close or compete with the shared engine" }
    }

    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    private fun quadJson(quad: List<Pt>) = JSONArray(quad.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) })

    private fun bytesSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun pixelsSha(pixels: IntArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = ByteArray(4096)
        var used = 0
        for (pixel in pixels) {
            row[used++] = (pixel ushr 24).toByte()
            row[used++] = (pixel ushr 16).toByte()
            row[used++] = (pixel ushr 8).toByte()
            row[used++] = pixel.toByte()
            if (used == row.size) {
                digest.update(row)
                used = 0
            }
        }
        digest.update(row, 0, used)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
