package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp

/** Local, opt-in diagnosis. The copyrighted source is neither bundled nor copied into the report. */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalEngineApi::class)
class OcrVerticalTailDiagnosticTest {
    @Test
    fun compareFixedVerticalQuadsWithoutChangingProduction() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("ocrVerticalTail") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null))
        val output = File(external, "ocr-vertical-tail-${System.currentTimeMillis()}")
        check(output.mkdir())
        val rows = JSONArray()
        val failures = JSONArray()
        val report = JSONObject().put("pid", Process.myPid()).put("rows", rows).put("failures", failures)
            .put("preferencesChanged", false).put("sourceImagesWritten", false)
            .put("design", "3 fixed vertical quads x pad 0/2/4 x sharpening off/on x right tensor margin 16/32")
            .put("confidenceDefinition", "exp(mean emitted-token log probability); not calibrated accuracy")
            .put("timingDefinition", "elapsedRealtimeNanos per native forward; diagnostic, not a benchmark")
            .put("qualityLimit", "Records incorrect OCR as observed; expected text is never inserted into model output")
        fun checkpoint() = File(output, "result.json").writeText(report.toString(2))
        checkpoint()
        println("OCR_VERTICAL_TAIL_REPORT=${File(output, "result.json").absolutePath}")
        try {
            // The operator keeps the app idle. Do not close or reconfigure its shared engine.
            val service = Injekt.get<TranslationEngineService>()
            val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight")
                .apply { isAccessible = true }.get(service) as AtomicInteger
            check(inFlight.get() == 0) { "Run while production translation is idle" }
            val source = File(external, "real-page-input/ch34_011.jpg")
            check(source.isFile) { "Provision real-page-input/ch34_011.jpg first" }
            val sha = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(sha == "0db76891835ba25c4a897ae05e16dd3cfe19521376125bb35cf8e634b00354ae") {
                "Unexpected local fixture"
            }
            report.put("fixtureSha256", sha)
            val dictionary = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
            val model = File(context.filesDir, "models/ocr_48px_ctc.ncnn.param")
            val page = checkNotNull(BitmapFactory.decodeFile(source.path))
            try {
                check(page.width == 1351 && page.height == 1920)
                report.put("pageWidth", page.width).put("pageHeight", page.height)
                // Coordinates from the preserved production .yakuyomi/001.json. Ground truth was
                // read on the original image. The second control also ends in the omitted glyph い.
                val samples = listOf(
                    Sample("missing_tail", "待ってください", rect(690.97156f, 947.84656f, 726.5284f, 1146.5283f)),
                    Sample("same_balloon_control", "ちょっと", rect(728.9144f, 950.1644f, 757.96045f, 1067.3354f)),
                    Sample("terminal_i_control", "はい", rect(984.85f, 189.84999f, 1025.1499f, 256.4f)),
                )
                for (sharpen in listOf(true, false)) {
                    val cfg = OcrConfig(ocrUnsharp = sharpen)
                    Ocr(model.path, dictionary, cfg).use { ocr ->
                        for (sample in samples) {
                            for (pad in listOf(4, 0, 2)) {
                                val strip = crop(ocr, page, sample.quad, pad, cfg)
                                try {
                                    for (margin in listOf(16, 32)) {
                                        val row = JSONObject().put("sample", sample.name)
                                            .put("expected", sample.expected)
                                            .put("pad", pad).put("sharpen", sharpen).put("rightMargin", margin)
                                            .put("quad", JSONArray(sample.quad.map { listOf(it.x, it.y) }))
                                            .put("stripWidth", strip.width).put("stripHeight", strip.height)
                                            .put("backend", ocr.backend)
                                        rows.put(row)
                                        try {
                                            val result = infer(ocr, strip, dictionary, margin)
                                            result.keys().forEach { key -> row.put(key, result.get(key)) }
                                            row.put("matchesExpected", row.getString("rawText") == sample.expected)
                                            if (pad == 4 && margin == 32) {
                                                val line = TextLine(sample.quad, 1f)
                                                ocr.recognize(page, listOf(line))
                                                row.put("productionRecognizeText", line.text)
                                                val filtered = if (row.getDouble("confidence") >= cfg.minProb) {
                                                    row.getString("rawText")
                                                } else {
                                                    ""
                                                }
                                                check(line.text == filtered) {
                                                    "Diagnostic differs from production vertical OCR"
                                                }
                                                check(line.text == sample.expected) {
                                                    "Known vertical tail/control regression: ${sample.name}"
                                                }
                                            }
                                        } catch (error: Exception) {
                                            row.put("error", error.javaClass.simpleName)
                                            failures.put(
                                                "${sample.name}/pad$pad/sharp$sharpen/margin$margin: " +
                                                    error.javaClass.simpleName,
                                            )
                                        } finally {
                                            checkpoint()
                                        }
                                    }
                                } finally {
                                    strip.recycle()
                                }
                            }
                        }
                    }
                }
                check(rows.length() == 36 && failures.length() == 0) { "Diagnostic incomplete; inspect result.json" }
                report.put("completed", true)
            } finally {
                page.recycle()
            }
        } catch (error: Throwable) {
            report.put("completed", false).put("fatalError", error.javaClass.simpleName)
            throw error
        } finally {
            checkpoint()
        }
        Unit
    }

    private data class Sample(val name: String, val expected: String, val quad: List<Pt>)

    @Test
    fun compareEverySavedVerticalLineOnTwoRealPages() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ocrVerticalTailAll") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null))
        val output = File(external, "ocr-vertical-all-${System.currentTimeMillis()}")
        check(output.mkdir())
        val rows = JSONArray()
        val regions = JSONArray()
        val report = JSONObject().put("pid", Process.myPid()).put("rows", rows).put("regions", regions)
            .put("preferencesChanged", false).put("sourceImagesWritten", false)
            .put("design", "Every saved vertical line; identical pad4/bicubic/sharpened input, tensor margin16 vs32")
            .put("qualityLimit", "Previous region OCR is not ground truth; changed lines require original-image review")
            .put("confidenceDefinition", "exp(mean log probability of emitted greedy CTC tokens)")
        fun checkpoint() = File(output, "result.json").writeText(report.toString(2))
        checkpoint()
        println("OCR_VERTICAL_ALL_REPORT=${File(output, "result.json").absolutePath}")
        try {
            val service = Injekt.get<TranslationEngineService>()
            val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight")
                .apply { isAccessible = true }.get(service) as AtomicInteger
            check(inFlight.get() == 0) { "Run while production translation is idle" }
            val dictionary = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
            val cfg = OcrConfig()
            val fixtures = listOf(
                Triple("ch34_011", "0db76891835ba25c4a897ae05e16dd3cfe19521376125bb35cf8e634b00354ae", 15),
                Triple("demo01", "f9072089137c0d0fa9e2bad330644a55c75f95744d19400ef53b7904c149fafe", 31),
            )
            Ocr(File(context.filesDir, "models/ocr_48px_ctc.ncnn.param").path, dictionary, cfg).use { ocr ->
                report.put("backend", ocr.backend)
                for ((name, sha, expectedLines) in fixtures) {
                    val input = File(external, "real-page-input/$name.jpg")
                    check(input.isFile && digest(input) == sha) { "Unexpected fixture $name" }
                    val materials = File(external, "real-page-input/$name.materials.json")
                    check(materials.isFile && materials.length() <= 1_048_576) {
                        "Missing or oversized materials $name"
                    }
                    val materialsSha = digest(materials)
                    val saved = JSONObject(materials.readText()).getJSONArray("regions")
                    val page = checkNotNull(BitmapFactory.decodeFile(input.path))
                    try {
                        var pageLines = 0
                        for (regionIndex in 0 until saved.length()) {
                            val region = saved.getJSONObject(regionIndex)
                            if (region.getString("direction") != "v") continue
                            val quads = region.getJSONArray("quads")
                            val beforeRegion = StringBuilder()
                            val afterRegion = StringBuilder()
                            for (lineIndex in 0 until quads.length()) {
                                val coords = quads.getJSONArray(lineIndex)
                                check(coords.length() == 4)
                                val quad = (0 until 4).map { index ->
                                    val point = coords.getJSONArray(index)
                                    Pt(point.getDouble(0).toFloat(), point.getDouble(1).toFloat()).also {
                                        check(it.x.isFinite() && it.y.isFinite())
                                        check(it.x in 0f..(page.width - 1f) && it.y in 0f..(page.height - 1f))
                                    }
                                }
                                val row = JSONObject().put("fixture", name).put("region", regionIndex)
                                    .put("line", lineIndex).put("quad", coords).put("fixtureSha256", sha)
                                    .put("materialsSha256", materialsSha)
                                rows.put(row)
                                val strip = crop(ocr, page, quad, 4, cfg)
                                try {
                                    row.put("stripWidth", strip.width).put("stripHeight", strip.height)
                                    val before = infer(ocr, strip, dictionary, 16)
                                    val after = infer(ocr, strip, dictionary, 32)
                                    row.put("margin16", before).put("margin32", after)
                                    val beforeText = before.getString("rawText")
                                    val afterText = after.getString("rawText")
                                    row.put("changed", beforeText != afterText)
                                    row.put(
                                        "changeKind",
                                        when {
                                            beforeText == afterText -> "unchanged"
                                            afterText.startsWith(beforeText) -> "suffix_added"
                                            beforeText.startsWith(afterText) -> "suffix_removed"
                                            else -> "body_changed"
                                        },
                                    )
                                    beforeRegion.append(filtered(before, cfg))
                                    afterRegion.append(filtered(after, cfg))
                                    val line = TextLine(quad, 1f)
                                    ocr.recognize(page, listOf(line))
                                    row.put("productionRecognizeText", line.text)
                                    check(line.text == filtered(after, cfg)) {
                                        "Production margin32 mismatch $name/$regionIndex/$lineIndex"
                                    }
                                    pageLines++
                                } finally {
                                    strip.recycle()
                                    checkpoint()
                                }
                            }
                            regions.put(
                                JSONObject().put("fixture", name).put("region", regionIndex)
                                    .put("previousSavedSource", region.getString("source"))
                                    .put("margin16Source", beforeRegion.toString())
                                    .put("margin32Source", afterRegion.toString()),
                            )
                        }
                        check(pageLines == expectedLines) { "Unexpected vertical coverage for $name: $pageLines" }
                    } finally {
                        page.recycle()
                    }
                }
            }
            check(rows.length() == 46)
            report.put("completed", true)
        } catch (error: Throwable) {
            report.put("completed", false).put("fatalError", error.javaClass.simpleName)
            throw error
        } finally {
            checkpoint()
        }
        Unit
    }

    private fun filtered(result: JSONObject, cfg: OcrConfig): String =
        if (result.getDouble("confidence") >= cfg.minProb) result.getString("rawText") else ""

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) =
        listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1))

    private fun method(name: String) = Ocr::class.java.declaredMethods.single { it.name == name }
        .apply { isAccessible = true }

    private fun crop(ocr: Ocr, page: Bitmap, quad: List<Pt>, pad: Int, cfg: OcrConfig): Bitmap {
        @Suppress("UNCHECKED_CAST")
        val expanded = if (pad == 0) {
            quad
        } else {
            method("expandQuad")
                .invoke(ocr, quad, pad, page.width, page.height) as List<Pt>
        }

        @Suppress("UNCHECKED_CAST")
        val ordered = method("sortPnts").invoke(ocr, expanded) as Pair<List<Pt>, Boolean>
        check(ordered.second) { "Saved quad unexpectedly classified horizontal" }
        return checkNotNull(
            method("transformedRegion")
                .invoke(ocr, page, ordered.first, true, cfg.textHeight, cfg.useBicubic) as Bitmap?,
        )
    }

    private fun infer(ocr: Ocr, strip: Bitmap, dictionary: List<String>, margin: Int): JSONObject {
        // Reuse production normalization/sharpening. Extend only the existing white tensor margin;
        // never rescale the strip, rewrite source pixels, or change the production PAD_MARGIN.
        val input = checkNotNull(method("stripToChw").invoke(ocr, strip))
        fun field(name: String) = input.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(input)
        val originalWidth = field("w") as Int
        val height = field("h") as Int
        val original = field("chw") as FloatArray
        check(originalWidth == strip.width + 16 && height == strip.height)
        val width = strip.width + margin
        val tensor = if (margin == 16) {
            original
        } else {
            check(margin == 32)
            FloatArray(3 * height * width) { 1f }.also { extended ->
                for (channel in 0..2) {
                    for (y in 0 until height) {
                        val from = (channel * height + y) * originalWidth
                        original.copyInto(extended, (channel * height + y) * width, from, from + originalWidth)
                    }
                }
            }
        }
        val steps = width / 4 - 1
        val idx = IntArray(steps)
        val logp = FloatArray(steps)
        val peMethod = Ocr.Companion::class.java.declaredMethods.single { it.name.startsWith("sinusoidalPe") }
            .apply { isAccessible = true }
        val pe = peMethod.invoke(Ocr.Companion, steps) as FloatArray
        val handle = Ocr::class.java.getDeclaredField("ncnnHandle").apply { isAccessible = true }.getLong(ocr)
        val start = SystemClock.elapsedRealtimeNanos()
        check(NcnnBackend.ocrCtc(handle, tensor, width, height, pe, steps, idx, logp, true) == steps)
        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val tokens = JSONArray()
        val trace = JSONArray()
        val text = StringBuilder()
        var last = 0
        var logpSum = 0.0
        for (step in 0 until steps) {
            check(logp[step].isFinite() && idx[step] in dictionary.indices)
            trace.put(JSONObject().put("step", step).put("id", idx[step]).put("logProbability", logp[step]))
            if (idx[step] != last && idx[step] != 0) {
                val token = dictionary[idx[step]].let { if (it == "<SP>") " " else it }
                text.append(token)
                logpSum += logp[step]
                tokens.put(JSONObject().put("text", token).put("step", step).put("probability", exp(logp[step])))
            }
            last = idx[step]
        }
        return JSONObject().put("rawText", text.toString()).put("tokens", tokens).put("ctcSteps", trace)
            .put("confidence", if (tokens.length() == 0) 0.0 else exp(logpSum / tokens.length()))
            .put("inputWidth", width).put("inputHeight", height).put("forwardMs", elapsed)
    }
}
