package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.Yakuyomi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest

/** Explicit opt-in, synthetic images only. No preferences are changed and no existing files are deleted. */
@RunWith(AndroidJUnit4::class)
class PhysicalDevicePerformanceTest {
    @Test
    fun coldStartAndConsecutiveWarmPages() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("liveTranslationBase").orEmpty()
        val model = args.getString("liveTranslationModel").orEmpty()
        assumeTrue(
            "Requires physicalPerformance=true and explicit liveTranslationBase/liveTranslationModel",
            args.getString("physicalPerformance") == "true" && base.isNotBlank() && model.isNotBlank(),
        )
        assertTrue("This benchmark requires an ARM64 process", android.os.Process.is64Bit())
        assertTrue("ARM64 device required", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val warmPages = (args.getString("physicalWarmPages")?.toIntOrNull() ?: 20).coerceIn(10, 60)
        val method = args.getString("physicalInpaintMethod") ?: "auto_whole"
        require(method in setOf("auto_whole", "auto_tile", "boxfill"))
        val ocrConcurrency = args.getString("physicalOcrConcurrency")?.let { value ->
            requireNotNull(value.toIntOrNull()?.takeIf { it in 1..16 }) { "physicalOcrConcurrency must be 1..16" }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "physical-performance-${System.currentTimeMillis()}")
        check(directory.mkdir()) { "Cannot create unique benchmark directory" }
        val reportFile = File(directory, "result.json")
        val rows = JSONArray()
        val report = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("sdk", Build.VERSION.SDK_INT)
            .put("model", model)
            .put("apiKeyUsed", false)
            .put("preferencesChanged", false)
            .put("method", method)
            .put("warmPagesRequested", warmPages)
            .put(
                "execution",
                "One independent engine; sequential pages; test does not request GC; three synthetic fixtures",
            )
            .put(
                "coldDefinition",
                "Model resolution + engine creation + explicit native warmup + first page through verified persistence; not app process launch",
            )
            .put(
                "llmTimingDefinition",
                "Client request latency including network, server queue/inference, response parsing and any bounded parameter negotiation",
            )
            .put("stageClock", "Existing PageStats uses currentTimeMillis; outer durations use elapsedRealtimeNanos")
            .put(
                "memoryDefinition",
                "Process-wide PSS/ART Java heap/native heap samples; not per-engine allocation or a leak proof",
            )
            .put("pages", rows)
            .put("memoryBefore", memory())
        fun checkpoint() {
            val temporary = File(directory, "result.json.tmp")
            temporary.outputStream().use { it.write(report.toString(2).toByteArray(Charsets.UTF_8)) }
            check(temporary.renameTo(reportFile)) { "Cannot checkpoint benchmark report" }
        }
        checkpoint()
        println("PHYSICAL_PERFORMANCE_REPORT=${reportFile.absolutePath}")
        val totalStart = SystemClock.elapsedRealtimeNanos()
        var engine: TranslationEngine? = null
        var successful = 0
        var fatal: Throwable? = null
        try {
            val prefs = Injekt.get<TranslationPreferences>()
            val initial = TranslationEngineConfig.buildEngineConfig(prefs, method)
            // Capture settings into immutable engine config; never change the global endpoint or credentials.
            val config = initial.copy(
                ocr = ocrConcurrency?.let { initial.ocr.copy(concurrent = true, concurrency = it) } ?: initial.ocr,
                translator = initial.translator.copy(
                    provider = "custom",
                    apiBase = LlmProviders.chatUrlOf(LlmProviders.byId("custom"), base),
                    model = model,
                    fromLangName = "Japanese",
                    toLangName = "Korean",
                    sampleSource = "",
                    sampleTarget = "",
                    temperature = 0.0,
                    thinking = false,
                ),
                render = initial.render.copy(orientation = TextOrientation.HORIZONTAL),
            )
            report.put("ocrConcurrency", config.ocr.concurrency)
                .put("ocrConcurrent", config.ocr.concurrent)
                .put("ocrConcurrencyOverride", ocrConcurrency ?: JSONObject.NULL)
                .put("ocrMixedRequested", config.ocr.ncnnMixed)
                .put("cpuSupportsFp16", Yakuyomi.ncnnCpuSupportsFp16())
                .put("renderOrientation", "horizontal")
            var start = SystemClock.elapsedRealtimeNanos()
            val bundle = requireNotNull(TranslationEngineConfig.resolveModelSet(context)) { "Models unavailable" }
            report.put("modelResolutionMs", elapsed(start))
            start = SystemClock.elapsedRealtimeNanos()
            val activeEngine = Yakuyomi.create(bundle.models, bundle.alphabet, "", config)
            engine = activeEngine
            report.put("engineCreateMs", elapsed(start))
            start = SystemClock.elapsedRealtimeNanos()
            activeEngine.warmUp()
            report.put("nativeWarmupMs", elapsed(start))
                .put("initializationWallMs", elapsed(totalStart))
                .put("memoryAfterWarmup", memory())
            checkpoint()
            val translator = PageTranslator(context)
            val chapter = requireNotNull(UniFile.fromFile(directory))
            repeat(warmPages + 1) { index ->
                val row = JSONObject().put("index", index).put("phase", if (index == 0) "first" else "warm")
                    .put("fixture", listOf("horizontal", "vertical", "dense")[index % 3])
                    .put("memoryBefore", memory())
                rows.put(row)
                checkpoint() // An unfinished row identifies the page active during a native crash.
                val pageStart = SystemClock.elapsedRealtimeNanos()
                var original: Bitmap? = null
                var result: PageResult? = null
                var originalHash: String? = null
                val pageFile = File(directory, "%03d.png".format(index + 1))
                try {
                    val source = fixture(index % 3)
                    original = source
                    pageFile.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    originalHash = digest(pageFile)
                    val inferenceStart = SystemClock.elapsedRealtimeNanos()
                    result = withTimeout(240_000) { activeEngine.translatePage(source) }
                    row.put("engineCallMs", elapsed(inferenceStart))
                    val translated = when (val outcome = result) {
                        is PageResult.Translated -> outcome
                        is PageResult.Partial -> {
                            row.put("stats", stats(outcome.stats))
                                .put(
                                    "pendingRegionIds",
                                    JSONArray(outcome.analysis.progress?.pendingRegionIds.orEmpty()),
                                )
                            error("Partial OCR is not a completed performance page")
                        }
                        is PageResult.Skipped -> {
                            row.put("stats", stats(outcome.stats))
                            error("Skipped: ${outcome.reason}")
                        }
                        is PageResult.Failed -> error("Engine failed: ${outcome.reason}")
                        null -> error("Missing engine result")
                    }
                    row.put("stats", stats(translated.stats))
                    val regions = requireNotNull(translated.analysis).regions.filterNot { it.preserveOriginal }
                    row.put(
                        "regions",
                        JSONArray().apply {
                            regions.forEach {
                                put(JSONObject().put("source", it.sourceText).put("target", it.translatedText))
                            }
                        },
                    )
                    check(regions.isNotEmpty() && regions.any { Regex("[가-힣]").containsMatchIn(it.translatedText) }) {
                        "No Korean translated region"
                    }
                    val saveStart = SystemClock.elapsedRealtimeNanos()
                    check(
                        translator.persistLivePage(
                            chapter,
                            UniFile.fromFile(pageFile)!!,
                            source,
                            translated.page,
                            translated.analysis,
                            method,
                        ),
                    ) { "Persistence failed" }
                    row.put("persistMs", elapsed(saveStart))
                    val verifyStart = SystemClock.elapsedRealtimeNanos()
                    check(translator.isPageTranslated(chapter, pageFile.name)) { "Completion marker missing" }
                    val savedOriginal = File(directory, ".yakuyomi/${pageFile.nameWithoutExtension}.orig.png")
                    check(digest(savedOriginal) == originalHash) { "Original bytes changed" }
                    check(digest(pageFile) != originalHash) { "Translation did not change saved image" }
                    val readerPage = ReaderPage(index, stream = { pageFile.inputStream() })
                    val displayed = requireNotNull(readerPage.displayStream)().use { BitmapFactory.decodeStream(it) }
                    check(displayed != null) { "Reader stream cannot decode persisted image" }
                    try {
                        check(displayed.width == source.width && displayed.height == source.height) {
                            "Saved dimensions changed"
                        }
                    } finally {
                        displayed.recycle()
                    }
                    row.put("verifyMs", elapsed(verifyStart)).put("originalPreserved", true)
                        .put("completionMarked", true).put("readerStreamDecoded", true).put("status", "passed")
                    successful++
                } catch (error: Throwable) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    // The test uses no credentials. Still remove URLs from failure text before writing artifacts.
                    row.put("status", "failed").put("failureType", error.javaClass.simpleName)
                        .put("failure", safeFailure(error))
                    row.put("completionMarkedAfterFailure", translator.isPageTranslated(chapter, pageFile.name))
                    if (originalHash != null && pageFile.isFile) {
                        row.put("pageBytesUnchangedAfterFailure", digest(pageFile) == originalHash)
                    }
                } finally {
                    val output = when (val outcome = result) {
                        is PageResult.Translated -> outcome.page to outcome.analysis?.mask
                        is PageResult.Partial -> outcome.page to outcome.analysis.mask
                        else -> null
                    }
                    output?.let { (page, mask) ->
                        if (page !== original) page.recycle()
                        mask?.let { if (it !== original && it !== page) it.recycle() }
                    }
                    original?.recycle()
                    row.put("pageWallMs", elapsed(pageStart)).put("memoryAfterRelease", memory())
                    if (index == 0) report.put("coldThroughFirstVerifiedPageMs", elapsed(totalStart))
                    report.put("successfulPages", successful).put("totalWallMs", elapsed(totalStart))
                    checkpoint()
                }
            }
        } catch (error: Throwable) {
            fatal = error
            report.put("fatalType", error.javaClass.simpleName).put("fatalFailure", safeFailure(error))
        } finally {
            report.put("memoryBeforeEngineClose", memory())
            val closeStart = SystemClock.elapsedRealtimeNanos()
            try {
                engine?.close()
            } catch (error: Throwable) {
                fatal = fatal ?: error
                report.put("closeFailure", safeFailure(error))
            }
            report.put("engineCloseMs", elapsed(closeStart)).put("memoryAfterEngineClose", memory())
                .put("totalWallMs", elapsed(totalStart)).put("successfulPages", successful)
                .put("warmStatistics", warmStatistics(rows))
                .put("completed", fatal == null && successful == warmPages + 1)
            checkpoint()
            println(
                "PHYSICAL_PERFORMANCE_SUMMARY=" + JSONObject()
                    .put("successfulPages", successful).put("requestedPages", warmPages + 1)
                    .put("totalWallMs", elapsed(totalStart)).put("report", reportFile.absolutePath),
            )
        }
        assertTrue(
            "Benchmark failed; inspect the checkpointed synthetic report",
            fatal == null && successful == warmPages + 1,
        )
    }

    private fun elapsed(start: Long): Double = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    private fun warmStatistics(rows: JSONArray): JSONObject {
        val warm = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("phase") == "warm" }
        val passed = warm.filter { it.optString("status") == "passed" }
        fun distribution(values: List<Double>): JSONObject {
            val sorted = values.sorted()
            return JSONObject().put("count", sorted.size).apply {
                if (sorted.isNotEmpty()) {
                    put("minMs", sorted.first()).put("maxMs", sorted.last()).put("meanMs", sorted.average())
                    val middle = sorted.size / 2
                    put(
                        "medianMs",
                        if (sorted.size % 2 ==
                            0
                        ) {
                            (sorted[middle - 1] + sorted[middle]) / 2
                        } else {
                            sorted[middle]
                        },
                    )
                    put("p90Ms", sorted[(kotlin.math.ceil(sorted.size * 0.9).toInt() - 1).coerceAtLeast(0)])
                }
            }
        }
        return JSONObject().put("attempted", warm.size).put("passed", passed.size)
            .put("timingPopulation", "Successful warm pages only; initialization and first page excluded")
            .apply {
                for (field in listOf("pageWallMs", "engineCallMs", "persistMs", "verifyMs")) {
                    put(field, distribution(passed.map { it.getDouble(field) }))
                }
                for (field in listOf("detectMs", "ocrMs", "llmRequestMs", "inpaintMs", "renderMs")) {
                    put(field, distribution(passed.map { it.getJSONObject("stats").getDouble(field) }))
                }
                put(
                    "byFixturePageWallMs",
                    JSONObject().apply {
                        for (type in listOf("horizontal", "vertical", "dense")) {
                            put(
                                type,
                                distribution(
                                    passed.filter {
                                        it.getString("fixture") == type
                                    }.map { it.getDouble("pageWallMs") },
                                ),
                            )
                        }
                    },
                )
            }
    }

    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        return JSONObject().put("pssKiB", info.totalPss).put("javaPssKiB", info.dalvikPss)
            .put("nativePssKiB", info.nativePss).put("privateDirtyKiB", info.totalPrivateDirty)
            .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaHeapCommittedBytes", runtime.totalMemory()).put("javaHeapMaxBytes", runtime.maxMemory())
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun stats(value: PageStats): JSONObject = JSONObject()
        .put("detectMs", value.detectMs).put("ocrMs", value.ocrMs).put("llmRequestMs", value.translateMs)
        .put("inpaintMs", value.inpaintMs).put("renderMs", value.renderMs).put("pipelineWallMs", value.wallMs)
        .put("lines", value.lines).put("regions", value.regions).put("kept", value.kept)
        .put("promptTokens", value.promptTokens).put("completionTokens", value.completionTokens)

    private fun safeFailure(error: Throwable): String = error.message.orEmpty()
        .replace(Regex("https?://\\S+"), "[endpoint]").take(600)

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun fixture(type: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.LTGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun bubble(left: Float, top: Float, right: Float, bottom: Float) {
            paint.color = Color.DKGRAY
            canvas.drawRect(left - 8, top - 8, right + 8, bottom + 8, paint)
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(left, top, right, bottom), 70f, 70f, paint)
            paint.color = Color.BLACK
        }
        when (type) {
            0 -> {
                paint.textSize = 60f
                bubble(70f, 120f, 1130f, 650f)
                canvas.drawText("こんにちは。", 200f, 340f, paint)
                canvas.drawText("今日もよろしくお願いします。", 150f, 470f, paint)
                bubble(70f, 850f, 1130f, 1450f)
                canvas.drawText("明日の会議は午後三時です。", 180f, 1080f, paint)
                canvas.drawText("少し待ってください。", 240f, 1210f, paint)
            }
            1 -> {
                paint.textSize = 64f
                bubble(80f, 100f, 550f, 1470f)
                bubble(650f, 100f, 1120f, 1470f)
                for ((x, text) in listOf(980f to "明日もよろしく", 860f to "お願いします", 410f to "少し待って", 290f to "ください")) {
                    text.forEachIndexed { index, c -> canvas.drawText(c.toString(), x, 350f + index * 80f, paint) }
                }
            }
            else -> {
                paint.textSize = 36f
                val lines = listOf(
                    "今日はいい天気ですね。",
                    "一緒に出かけませんか。",
                    "まだ準備ができません。",
                    "少し待ってください。",
                    "もうすぐ終わります。",
                    "ありがとうございます。",
                )
                repeat(6) { index ->
                    val left = 50f + (index % 2) * 600f
                    val top = 70f + (index / 2) * 500f
                    bubble(left, top, left + 500f, top + 430f)
                    canvas.drawText(lines[index], left + 30f, top + 170f, paint)
                    canvas.drawText("分かりました。", left + 100f, top + 260f, paint)
                }
            }
        }
        return bitmap
    }
}
