package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.HorizontalOcrContextPolicy
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.math.exp

/** Opt-in diagnostics on the saved synthetic benchmark originals. Never changes app preferences. */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalEngineApi::class)
class OcrPunctuationTest {
    @Test
    fun savedFullStopsRecoverAndRealKanaStayUnchanged() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ocrPunctuation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Injekt.get<TranslationEngineService>().shutdown()
        val input = File(context.filesDir, "ocr-punctuation-input")
        val output = File(context.getExternalFilesDir(null), "ocr-punctuation-regression-${System.currentTimeMillis()}")
        check(output.mkdir())
        val dictionary = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        val rows = JSONArray()
        val baselineMismatches = JSONArray()
        val terminalKanaCoverage = mutableMapOf('は' to 0, 'ば' to 0, 'ぱ' to 0)
        val preservationFailures = mutableListOf<String>()
        try {
            // This historical circle-correction test compares against raw16, including its errors.
            // The product default is checked against independent truth by OcrRightContextHoldoutTest.
            val legacy = OcrConfig(horizontalContextPolicy = HorizontalOcrContextPolicy.LEGACY_16)
            Ocr(File(context.filesDir, "models/ocr_48px_ctc.ncnn.param").path, dictionary, legacy).use { ocr ->
                val expectedPages = mapOf(
                    "001" to listOf("こんにちは。", "今日もよろしくお願いします。", "明日の会議は午後三時です。", "少し待ってください。"),
                    "003" to listOf(
                        "今日はいい天気ですね。", "一緒に出かけませんか。", "分かりました。", "分かりました。",
                        "まだ準備ができません。", "少し待ってください。", "分かりました。", "分かりました。",
                        "ありがとうございます。", "もうすぐ終わります。", "分かりました。", "分かりました。",
                    ),
                )
                for ((name, expected) in expectedPages) {
                    val page = checkNotNull(BitmapFactory.decodeFile(File(input, "$name.orig.png").path))
                    try {
                        val regions = JSONObject(File(input, "$name.json").readText()).getJSONArray("regions")
                        assertEquals(expected.size, regions.length())
                        for (i in expected.indices) {
                            val coords = regions.getJSONObject(i).getJSONArray("quads").getJSONArray(0)
                            val quad = (0 until 4).map { j ->
                                coords.getJSONArray(j).let { Pt(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
                            }
                            val line = TextLine(quad, 1f)
                            ocr.recognize(page, listOf(line))
                            rows.put(
                                JSONObject().put(
                                    "page",
                                    name,
                                ).put("region", i).put("expected", expected[i]).put("actual", line.text),
                            )
                            assertEquals(
                                "Body changed: $name/$i",
                                expected[i].removeSuffix("。"),
                                line.text.removeSuffix("。"),
                            )
                            assertEquals("Full stop was not recovered: $name/$i", expected[i], line.text)
                        }
                    } finally {
                        page.recycle()
                    }
                }
                // Actual Android glyphs, including full-height は/ば/ぱ at the end and in the body.
                // This tests the new correction's character preservation, not universal OCR accuracy.
                // Record baseline model errors (e.g. 60px これは -> これば), but do not require the
                // punctuation correction to fix them. Aggregate every sample before asserting coverage.
                val samples = listOf(
                    "私はここにいます", "ばらの花が咲きました", "ぱんを食べました", "これは", "ことば", "らっぱ", "ことば。", "らっぱ。",
                    "少し待って、", "ありがとうございます、", "うたいます♪", "音楽です♫", "輪の形はの", "あいうえお", "これはぱ", "ことばば",
                ).map { it to "none" } + listOf("ことば" to "belowRing", "らっぱ" to "hollowNote")
                for (size in listOf(36f, 60f)) {
                    for ((text, decoration) in samples) {
                        val page = Bitmap.createBitmap(1000, 160, Bitmap.Config.ARGB_8888)
                        try {
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.BLACK
                                textSize = size
                            }
                            Canvas(page).apply {
                                drawColor(Color.WHITE)
                                drawText(text, 20f, 110f, paint)
                            }
                            val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
                            if (decoration != "none") {
                                val x = 20f + bounds.right + 10f
                                val y = if (decoration == "belowRing") 110f + bounds.bottom + 17f else 106f
                                paint.style = Paint.Style.STROKE
                                paint.strokeWidth = 2f
                                Canvas(page).apply {
                                    drawCircle(x, y, 5f, paint)
                                    if (decoration == "hollowNote") drawLine(x + 5, y, x + 5, 110f + bounds.top, paint)
                                }
                                bounds.right += 18
                                if (decoration == "belowRing") bounds.bottom += 25
                            }
                            val quad = listOf(
                                Pt(20f + bounds.left, 110f + bounds.top),
                                Pt(20f + bounds.right, 110f + bounds.top),
                                Pt(20f + bounds.right, 110f + bounds.bottom),
                                Pt(20f + bounds.left, 110f + bounds.bottom),
                            )
                            val strip = crop(ocr, page, quad)
                            val rawOutput = try {
                                rawInference(ocr, strip, dictionary)
                            } finally {
                                strip.recycle()
                            }
                            val raw = rawOutput.getString("text")
                            val baseline = if (rawOutput.getDouble("probability") >= OcrConfig().minProb) raw else ""
                            val line = TextLine(quad, 1f)
                            ocr.recognize(page, listOf(line))
                            val row = JSONObject().put(
                                "fontSize",
                                size,
                            ).put("expected", text).put("decoration", decoration)
                                .put(
                                    "raw",
                                    raw,
                                ).put("baseline", baseline).put("probability", rawOutput.getDouble("probability"))
                                .put("actual", line.text).put("baselineMatchesExpected", baseline == text)
                            rows.put(row)
                            if (baseline != text) baselineMismatches.put(row)
                            if (!text.endsWith("。")) {
                                if (baseline !=
                                    line.text
                                ) {
                                    preservationFailures +=
                                        "Kana changed: $text size=$size raw=$raw actual=${line.text}"
                                }
                                val last = text.last()
                                if (decoration == "none" && last in terminalKanaCoverage && raw == text &&
                                    line.text == text
                                ) {
                                    terminalKanaCoverage[last] = terminalKanaCoverage.getValue(last) + 1
                                }
                            } else {
                                val expectedBody = text.removeSuffix("。")
                                val actualBody = line.text.removeSuffix("。")
                                fun agreesWithBody(body: String): Boolean {
                                    if (!baseline.startsWith(body)) return false
                                    val tail = baseline.substring(body.length)
                                    return tail.codePointCount(0, tail.length) <= 1
                                }
                                row.put("baselineBodyMatchesExpected", agreesWithBody(expectedBody))
                                // If the model body was correct, keep the independent ground truth.
                                // Otherwise only allow unchanged output or one terminal punctuation
                                // correction with the entire baseline body preserved.
                                val preserved = if (agreesWithBody(expectedBody)) {
                                    actualBody == expectedBody
                                } else {
                                    line.text == baseline || (line.text.endsWith("。") && agreesWithBody(actualBody))
                                }
                                if (!preserved) {
                                    preservationFailures +=
                                        "Body changed: $text size=$size raw=$raw actual=${line.text}"
                                }
                            }
                        } finally {
                            page.recycle()
                        }
                    }
                }
                for ((kana, count) in terminalKanaCoverage) {
                    if (count ==
                        0
                    ) {
                        preservationFailures += "No correctly recognized and preserved terminal $kana sample"
                    }
                }
                assertTrue(preservationFailures.joinToString("\n"), preservationFailures.isEmpty())
            }
        } finally {
            File(output, "result.json").writeText(
                JSONObject().put("rows", rows).put("baselineModelMismatches", baselineMismatches)
                    .put("preservedCorrectTerminalKana", JSONObject(terminalKanaCoverage.mapKeys { it.key.toString() }))
                    .put("preservationFailures", JSONArray(preservationFailures)).toString(2),
            )
        }
    }

    private fun crop(ocr: Ocr, page: Bitmap, quad: List<Pt>): Bitmap {
        @Suppress("UNCHECKED_CAST")
        val expanded = method("expandQuad").invoke(ocr, quad, 4, page.width, page.height) as List<Pt>

        @Suppress("UNCHECKED_CAST")
        val ordered = method("sortPnts").invoke(ocr, expanded) as Pair<List<Pt>, Boolean>
        return method("transformedRegion").invoke(ocr, page, ordered.first, ordered.second, 48, true) as Bitmap
    }

    @Test
    fun inspectSavedPunctuationCrops() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("ocrPunctuation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "ocr-punctuation-input")
        val output = File(context.getExternalFilesDir(null), "ocr-punctuation-${System.currentTimeMillis()}")
        check(output.mkdir())
        Injekt.get<TranslationEngineService>().shutdown()
        val dictionary = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        val rows = JSONArray()
        try {
            for (pad in listOf(4, 0, 2, 6, 8)) {
                val cfg = OcrConfig(stripPad = pad, horizontalContextPolicy = HorizontalOcrContextPolicy.LEGACY_16)
                Ocr(File(context.filesDir, "models/ocr_48px_ctc.ncnn.param").path, dictionary, cfg).use { ocr ->
                    for (name in listOf("001", "003")) {
                        val page = checkNotNull(BitmapFactory.decodeFile(File(input, "$name.orig.png").path))
                        try {
                            val regions = JSONObject(File(input, "$name.json").readText()).getJSONArray("regions")
                            for (i in 0 until regions.length()) {
                                val region = regions.getJSONObject(i)
                                if (pad != 4 && !region.getString("source").endsWith("ば")) continue
                                val coords = region.getJSONArray("quads").getJSONArray(0)
                                var quad = (0 until 4).map { j ->
                                    coords.getJSONArray(j).let {
                                        Pt(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())
                                    }
                                }
                                if (pad > 0) {
                                    @Suppress("UNCHECKED_CAST")
                                    val expanded = method(
                                        "expandQuad",
                                    ).invoke(ocr, quad, pad, page.width, page.height) as List<Pt>
                                    quad = expanded
                                }
                                @Suppress("UNCHECKED_CAST")
                                val ordered = method("sortPnts").invoke(ocr, quad) as Pair<List<Pt>, Boolean>
                                val strip = method("transformedRegion").invoke(
                                    ocr,
                                    page,
                                    ordered.first,
                                    ordered.second,
                                    cfg.textHeight,
                                    cfg.useBicubic,
                                ) as Bitmap
                                try {
                                    File(output, "$name-$i-pad$pad.png").outputStream().use {
                                        check(strip.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                    rows.put(
                                        rawInference(ocr, strip, dictionary).put("page", name).put("region", i)
                                            .put("pad", pad).put("previous", region.getString("source")),
                                    )
                                } finally {
                                    strip.recycle()
                                }
                            }
                        } finally {
                            page.recycle()
                        }
                    }
                }
            }
        } finally {
            File(output, "result.json").writeText(JSONObject().put("rows", rows).toString(2))
        }
    }

    private fun method(name: String) = Ocr::class.java.declaredMethods.single { it.name == name }.apply {
        isAccessible = true
    }

    private fun rawInference(ocr: Ocr, strip: Bitmap, dictionary: List<String>): JSONObject {
        val input = method("stripToChw").invoke(ocr, strip)
        fun field(name: String) = input.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(input)
        val w = field("w") as Int
        val h = field("h") as Int
        val t = w / 4 - 1
        val idx = IntArray(t)
        val logp = FloatArray(t)
        val peMethod = Ocr.Companion::class.java.declaredMethods.single { it.name.startsWith("sinusoidalPe") }
        peMethod.isAccessible = true
        val pe = peMethod.invoke(Ocr.Companion, t) as FloatArray
        val handle = Ocr::class.java.getDeclaredField("ncnnHandle").apply { isAccessible = true }.getLong(ocr)
        check(NcnnBackend.ocrCtc(handle, field("chw") as FloatArray, w, h, pe, t, idx, logp, true) == t)
        val tokens = JSONArray()
        val text = StringBuilder()
        var logpSum = 0.0
        var last = 0
        for (step in 0 until t) {
            if (idx[step] != 0 && idx[step] != last) {
                val char = dictionary[idx[step]]
                text.append(char)
                logpSum += logp[step]
                tokens.put(JSONObject().put("text", char).put("step", step).put("probability", exp(logp[step])))
            }
            last = idx[step]
        }
        return JSONObject().put("text", text.toString()).put("tokens", tokens).put("stripWidth", strip.width)
            .put("stripHeight", strip.height).put("backend", ocr.backend)
            .put("probability", if (tokens.length() == 0) 0.0 else exp(logpSum / tokens.length()))
    }
}
