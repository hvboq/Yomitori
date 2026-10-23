package mihon.translation

import android.graphics.Rect
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
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicit native diagnostic: neutral Android glyphs only; no detector, translator or reader.
 * Passing means complete observations and preprocessing/decoder parity, not perfect OCR accuracy.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalEngineApi::class)
class OcrPreprocessingDiagnosticTest {
    @Test
    fun neutralGlyphsCompareOnePreprocessingFactorAtATime() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "ocr-preprocessing-diagnostic") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(10 * 60 * 1000L) { diagnose() }
                }
            }
    }

    private suspend fun diagnose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("ocrPreprocessingDiagnostic") == "true") {
            "Explicit ocrPreprocessingDiagnostic=true is required; missing opt-in is not a pass or skip"
        }
        val retain = arguments.getString("ocrPreprocessingKeepArtifacts")?.let {
            check(it == "true" || it == "false") { "ocrPreprocessingKeepArtifacts must be true or false" }
            it == "true"
        } ?: false
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation is mandatory" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Actual ARM64 process is required" }
        val shared = Injekt.get<TranslationEngineService>()
        requireSharedIdle(shared)
        // Keep the recorded 16-column reference fixed as the product default evolves.
        val cfg = OcrConfig(horizontalContextPolicy = HorizontalOcrContextPolicy.LEGACY_16)
        val productionScope = "Legacy16 public recognize includes circle/filter/blank recovery"
        check(cfg.textHeight == 48 && cfg.stripPad == 4 && cfg.useBicubic && cfg.ocrUnsharp && cfg.ignoreBubble == 0) {
            "Production defaults changed; update this explicitly controlled diagnostic before running"
        }
        val output = File(
            checkNotNull(context.getExternalFilesDir(null)),
            "ocr-preprocessing-diagnostic-${UUID.randomUUID()}",
        )
        check(output.mkdir()) { "Cannot create own diagnostic directory" }
        val artifacts = OcrDiagnosticArtifacts(output)
        val reportFile = File(output, "result.json")
        val rows = JSONArray()
        val samples = JSONArray()
        val start = SystemClock.elapsedRealtimeNanos()
        val threadId = Thread.currentThread().id
        val report = JSONObject().put("passed", false).put("diagnosticOnly", true)
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("process64Bit", Process.is64Bit())
            .put("javaThreadId", threadId).put("javaThreadName", Thread.currentThread().name)
            .put("androidTid", Process.myTid()).put("sameThreadSerialNativeCalls", true)
            .put("nativeOcrCallStarted", false).put("nativeOcrCallCompleted", false)
            .put("sharedEngineShutdownCalled", false).put("globalPreferencesChanged", false)
            .put("modelFilesCopiedOrReplaced", false).put("llmOrOtherNetworkCalls", false)
            .put("actualReaderActivity", false).put("signedRelease", false)
            .put("groundTruthSource", "Unchanged OcrPunctuationTest 18 samples at 36px and 60px")
            .put("sourceTypefaceFixed", false)
            .put("sourceTypefaceScope", "Android default Paint; actual neutral source PNGs identify this device raster")
            .put("accuracyIsNotPassCriterion", true)
            .put("accuracyScope", "Exact independent neutral ground truth; errors remain errors in every condition")
            .put(
                "observationScope",
                "Raw native CTC before circle correction/filter/recovery; production baseline separately",
            )
            .put("stepGeometryScope", "CTC step is a model index; not a calibrated source-glyph coordinate")
            .put("timingScope", "Fixed-order quality diagnostics with export/parity overhead; not a speed benchmark")
            .put("variantOrder", JSONArray(OcrDiagnosticVariant.ALL.map { it.json() }))
            .put("samples", samples).put("rows", rows).put("artifactRetentionRequested", retain)
            .put("cleanupScope", "Only this UUID directory; exact image names, byte lengths and SHA-256 below")
            .put("resultJsonSelfHash", "Host must hash result.json after export; self-reference is excluded")
        fun checkpoint() {
            report.put("ownedNeutralFiles", artifacts.manifest())
            reportFile.writeText(report.toString(2))
        }
        var failure: Throwable? = null
        var beforeModels: List<ModelFingerprint> = emptyList()
        var probe: OcrDiagnosticProbe? = null
        var publicRecognizeCalls = 0
        var sampleCount = 0
        try {
            checkpoint()
            val modelDirectory = File(context.filesDir, "models")
            val base = File(modelDirectory, "ocr_48px_ctc.ncnn.param")
            val bin = File(modelDirectory, "ocr_48px_ctc.ncnn.bin")
            val mixed = File(modelDirectory, "ocr_48px_ctc_mixed.ncnn.param")
            check(bin.isFile && bin.length() > 0 && (base.isFile || mixed.isFile)) {
                "Existing audit OCR models are required; this test does not copy or download models"
            }
            beforeModels = listOf(base, mixed, bin).filter { it.exists() }.map(::fingerprint)
            report.put("modelFilesBefore", JSONArray(beforeModels.map { it.json() }))
            report.put("configuredOcr", configJson(cfg)).put("cpuSupportsFp16", NcnnBackend.cpuSupportsFp16)
            val dictionary = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
            report.put("dictionaryRows", dictionary.size)
            val createStart = SystemClock.elapsedRealtimeNanos()
            Ocr(base.path, dictionary, cfg).use { ocr ->
                report.put("nativeCreateMs", elapsed(createStart))
                val observer = OcrDiagnosticProbe(ocr, dictionary).also { probe = it }
                val selected = observer.selectedParam(base.path)
                val selectedFile = File(selected.first)
                check(selectedFile.canonicalFile.parentFile == modelDirectory.canonicalFile)
                check(ocr.backend == if (selected.second) "NCNN-mixed" else "NCNN")
                report.put("actualBackend", observer.metadata())
                    .put("selectedParam", fingerprint(selectedFile).json()).put("selectedMixed", selected.second)
                checkpoint()
                for (size in listOf(36f, 60f)) {
                    for (sample in OcrDiagnosticSample.ALL) {
                        currentCoroutineContext().ensureActive()
                        check(Thread.currentThread().id == threadId)
                        requireSharedIdle(shared)
                        val id = sampleCount++
                        val prefix = "sample-${id.toString().padStart(3, '0')}"
                        val drawing = sample.draw(size)
                        try {
                            val originalPixels = diagnosticPixels(drawing.page)
                            val originalQuad = drawing.quad.toList()
                            val sampleJson = JSONObject().put("sample", id).put("fontSize", size.toDouble())
                                .put("expected", sample.text).put("decoration", sample.decoration)
                                .put("pageWidth", drawing.page.width).put("pageHeight", drawing.page.height)
                                .put("quad", quadJson(drawing.quad)).put("textBounds", rectJson(drawing.textBounds))
                                .put("decoratedBounds", rectJson(drawing.decoratedBounds))
                                .put("paintGlyphSupport", drawing.glyphSupport)
                            samples.put(sampleJson)
                            sampleJson.put("source", artifacts.bitmap("$prefix-source.png", drawing.page))
                            var baselineRaw: String? = null
                            var baselineStripSha: String? = null
                            var baselineExpanded: List<Pt>? = null
                            var baselineTensor: OcrDiagnosticTensor? = null
                            for (variant in OcrDiagnosticVariant.ALL) {
                                currentCoroutineContext().ensureActive()
                                report.put("currentSample", id).put("currentVariant", variant.name)
                                checkpoint()
                                val (strip, expanded) = observer.crop(drawing.page, drawing.quad, variant)
                                try {
                                    val stripBefore = diagnosticPixels(strip)
                                    val stripFile = artifacts.bitmap("$prefix-${variant.name}-strip.png", strip)
                                    val tensor = observer.tensor(strip, variant)
                                    val tensorBitmap = tensor.image()
                                    val tensorFile = try {
                                        artifacts.bitmap("$prefix-${variant.name}-tensor.png", tensorBitmap)
                                    } finally {
                                        tensorBitmap.recycle()
                                    }
                                    report.put("nativeOcrCallStarted", true)
                                    val raw = observer.infer(tensor, variant == OcrDiagnosticVariant.BASELINE)
                                    val actualRaw = raw.getString("text")
                                    val row = JSONObject().put("sample", id).put("fontSize", size.toDouble())
                                        .put("expected", sample.text).put("decoration", sample.decoration)
                                        .put("variant", variant.json()).put("expandedQuad", quadJson(expanded))
                                        .put(
                                            "sourceDarkPixelsOutsideExpandedQuad",
                                            diagnosticDarkOutsideQuad(originalPixels, drawing.page.width, expanded),
                                        )
                                        .put("stripWidth", strip.width).put("stripHeight", strip.height)
                                        .put("strip", stripFile).put("tensor", tensor.json().put("image", tensorFile))
                                        .put("raw", raw).put("rawMatchesGroundTruth", actualRaw == sample.text)
                                        .put(
                                            "passesConfiguredMinProbability",
                                            raw.getDouble("probability") >= cfg.minProb,
                                        )
                                    rows.put(row)
                                    if (variant == OcrDiagnosticVariant.BASELINE) {
                                        baselineRaw = actualRaw
                                        baselineStripSha = stripFile.getString("sha256")
                                        baselineExpanded = expanded
                                        baselineTensor = tensor
                                        val line = TextLine(drawing.quad.toList(), 1f)
                                        publicRecognizeCalls++
                                        val intentionallyKept = ocr.recognize(drawing.page, listOf(line))
                                        check(intentionallyKept.isEmpty() && line.direction == "h")
                                        sampleJson.put("baselineProductionText", line.text)
                                            .put("baselineProductionMatchesGroundTruth", line.text == sample.text)
                                            .put("productionChangedRaw", line.text != actualRaw)
                                            .put("productionScope", productionScope)
                                    } else {
                                        val prior = checkNotNull(baselineTensor)
                                        val changed = listOf(
                                            variant.stripPad != 4,
                                            variant.rightMargin != 16,
                                            !variant.unsharp,
                                        ).count { it }
                                        check(changed == 1) { "An intervention changed multiple factors" }
                                        if (variant.stripPad == 4) {
                                            check(
                                                expanded == baselineExpanded &&
                                                    stripFile.getString("sha256") == baselineStripSha,
                                            )
                                        }
                                        if (variant.name == "right32") {
                                            assertRightPaddingOnly(prior, tensor, strip.width)
                                        }
                                        if (variant.name == "unsharpOff") {
                                            check(prior.width == tensor.width && prior.height == tensor.height)
                                        }
                                        row.put("sameAsBaselineRaw", actualRaw == baselineRaw)
                                            .put(
                                                "improvedAgainstGroundTruth",
                                                baselineRaw != sample.text && actualRaw == sample.text,
                                            )
                                            .put(
                                                "regressedAgainstGroundTruth",
                                                baselineRaw == sample.text && actualRaw != sample.text,
                                            )
                                            .put("independentSingleFactorVerified", true)
                                    }
                                    check(stripBefore.contentEquals(diagnosticPixels(strip))) {
                                        "Preprocessing mutated its input strip"
                                    }
                                    check(originalQuad == drawing.quad)
                                    checkpoint()
                                } finally {
                                    strip.recycle()
                                }
                            }
                            check(originalPixels.contentEquals(diagnosticPixels(drawing.page))) {
                                "Diagnostic changed original pixels"
                            }
                            sampleJson.put("originalPixelsAndQuadUnchanged", true)
                        } finally {
                            drawing.page.recycle()
                        }
                    }
                }
            }
            check(sampleCount == 36 && samples.length() == 36 && rows.length() == 144) {
                "Incomplete diagnostic corpus"
            }
            check(checkNotNull(probe).explicitNativeCalls == 144 && checkNotNull(probe).privateInferParityCalls == 36)
            check(publicRecognizeCalls == 36)
            report.put("nativeOcrCallCompleted", true).put("summary", summary(rows, samples)).put("passed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("errorType", error.javaClass.name).put("error", error.message)
        } finally {
            report.put("sampleCount", samples.length()).put("rowCount", rows.length())
                .put("explicitDiagnosticNativeCalls", probe?.explicitNativeCalls ?: 0)
                .put("productionPrivateInferParityCalls", probe?.privateInferParityCalls ?: 0)
                .put("productionPublicRecognizeCalls", publicRecognizeCalls)
                .put("totalNativeCallsKnown", false)
                .put(
                    "nativeCallCountingScope",
                    "Public recognize may add circle/recovery calls; not instrumented internally",
                )
            try {
                requireSharedIdle(shared)
                report.put("sharedEngineStillAbsentAndIdle", true)
                check(beforeModels.isNotEmpty()) { "Model preflight did not complete" }
                val after = beforeModels.map { fingerprint(it.file) }
                check(beforeModels == after) { "Existing OCR model files changed" }
                report.put("modelFilesAfter", JSONArray(after.map { it.json() })).put("existingModelsUnchanged", true)
            } catch (cleanupError: Throwable) {
                failure?.addSuppressed(cleanupError) ?: run { failure = cleanupError }
                report.put("postflightError", "${cleanupError.javaClass.name}: ${cleanupError.message}")
            }
            if (!retain) {
                try {
                    report.put("ownedNeutralFilesRemoved", artifacts.cleanup())
                } catch (cleanupError: Throwable) {
                    failure?.addSuppressed(cleanupError) ?: run { failure = cleanupError }
                    report.put("cleanupError", "${cleanupError.javaClass.name}: ${cleanupError.message}")
                }
            }
            report.put("passed", failure == null).put("wallMs", elapsed(start))
            try {
                checkpoint()
            } catch (checkpointError: Throwable) {
                failure?.addSuppressed(checkpointError) ?: run { failure = checkpointError }
            }
            try {
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "stream",
                            "OCR_PREPROCESSING_DIAGNOSTIC=" + JSONObject().put("passed", failure == null)
                                .put("report", reportFile.absolutePath).put("samples", samples.length())
                                .put("rows", rows.length()).put("artifactsRetained", retain).toString() + "\n",
                        )
                    },
                )
            } catch (statusError: Throwable) {
                failure?.addSuppressed(statusError) ?: run { failure = statusError }
            }
        }
        failure?.let { throw it }
    }

    private fun assertRightPaddingOnly(before: OcrDiagnosticTensor, after: OcrDiagnosticTensor, stripWidth: Int) {
        check(after.width == before.width + 16 && before.height == after.height)
        for (channel in 0..2) {
            for (y in 0 until before.height) {
                for (x in 0 until stripWidth) {
                    val first = before.chw[channel * before.width * before.height + y * before.width + x]
                    val second = after.chw[channel * after.width * after.height + y * after.width + x]
                    check(first.toRawBits() == second.toRawBits()) {
                        "Right-padding intervention changed source pixels"
                    }
                }
                for (x in stripWidth until after.width) {
                    check(after.chw[channel * after.width * after.height + y * after.width + x] == 1f)
                }
            }
        }
    }

    private fun requireSharedIdle(service: TranslationEngineService) {
        check(!service.loading.value && !service.warm.value) { "Shared audit engine must be absent and idle" }
        val engine = TranslationEngineService::class.java.getDeclaredField("engine").apply {
            isAccessible = true
        }.get(service)
        val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight").apply {
            isAccessible = true
        }.get(service) as AtomicInteger
        check(engine == null && inFlight.get() == 0) { "Refusing to close or compete with an existing shared engine" }
    }

    private fun summary(rows: JSONArray, samples: JSONArray): JSONObject {
        val variants = JSONArray()
        for (variant in OcrDiagnosticVariant.ALL) {
            val selected = (0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { it.getJSONObject("variant").getString("name") == variant.name }
            variants.put(
                JSONObject().put("variant", variant.name).put("rows", selected.size)
                    .put("rawExact", selected.count { it.getBoolean("rawMatchesGroundTruth") })
                    .put("improved", selected.count { it.optBoolean("improvedAgainstGroundTruth") })
                    .put("regressed", selected.count { it.optBoolean("regressedAgainstGroundTruth") }),
            )
        }
        return JSONObject().put("variants", variants).put(
            "baselineProductionExact",
            (0 until samples.length()).count {
                samples.getJSONObject(it).getBoolean("baselineProductionMatchesGroundTruth")
            },
        )
    }

    private fun configJson(cfg: OcrConfig) = JSONObject().put("textHeight", cfg.textHeight)
        .put("stripPad", cfg.stripPad).put("useBicubic", cfg.useBicubic).put("ocrUnsharp", cfg.ocrUnsharp)
        .put("minProb", cfg.minProb.toDouble()).put("ignoreBubble", cfg.ignoreBubble)
        .put("concurrent", cfg.concurrent).put("concurrency", cfg.concurrency)
        .put("ncnnMixed", cfg.ncnnMixed).put("ncnnFp16Storage", cfg.ncnnFp16Storage)
        .put("ncnnFp16Arith", cfg.ncnnFp16Arith)

    private data class ModelFingerprint(val file: File, val bytes: Long, val sha256: String) {
        fun json() = JSONObject().put("name", file.name).put("bytes", bytes).put("sha256", sha256)
    }

    private fun fingerprint(file: File) = ModelFingerprint(file, file.length(), diagnosticSha(file))
    private fun quadJson(quad: List<Pt>) = JSONArray(quad.map { listOf(it.x, it.y) })
    private fun rectJson(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
}
