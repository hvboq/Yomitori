package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import li.joye.yakuyomi.engine.Pt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/** Host-prepared neutral inputs are pinned independently of the OCR result being evaluated. */
internal class OcrRightContextInputs(context: Context, basename: String, val manifestBytes: ByteArray) {
    val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
    val directory: File
    val sourceManifest: JSONArray = manifest.getJSONArray("files")

    init {
        val uuid = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        check(basename.matches(Regex("ocr-right-context-input-$uuid"))) {
            "A host-prepared audit-owned UUID input basename is required"
        }
        val parent = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        directory = File(parent, basename)
        check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) {
            "Input directory must be an existing plain audit-owned UUID directory"
        }
        check(directory.canonicalFile.parentFile == parent)
        check(manifest.getInt("schemaVersion") == 1 && sourceManifest.length() == 40)
        val expected = (0 until sourceManifest.length()).map { sourceManifest.getJSONObject(it).getString("name") }
        check(expected.distinct().size == 40)
        check(directory.listFiles()?.map { it.name }?.toSet() == expected.toSet()) {
            "Input must contain exactly the 40 pinned neutral files"
        }
        for (index in 0 until sourceManifest.length()) {
            val entry = sourceManifest.getJSONObject(index)
            val name = entry.getString("name")
            check(name.matches(Regex("[A-Za-z0-9_.-]+")) && !name.contains(".."))
            val file = file(name)
            check(file.length() == entry.getLong("bytes") && diagnosticSha(file) == entry.getString("sha256")) {
                "Pinned neutral input changed: $name"
            }
        }
    }

    fun file(name: String): File = File(directory, name).also {
        check(
            it.isFile && it.canonicalFile == it.absoluteFile &&
                it.canonicalFile.parentFile == directory.canonicalFile,
        )
    }

    fun verifyUnchanged() {
        check(
            directory.listFiles()?.map { it.name }?.toSet() ==
                (0 until sourceManifest.length()).map { sourceManifest.getJSONObject(it).getString("name") }.toSet(),
        )
        for (i in 0 until sourceManifest.length()) {
            val entry = sourceManifest.getJSONObject(i)
            val file = file(entry.getString("name"))
            check(file.length() == entry.getLong("bytes") && diagnosticSha(file) == entry.getString("sha256"))
        }
    }

    fun fixedCases(): List<OcrRightContextCase> {
        val cases = ArrayList<OcrRightContextCase>()
        val frozen = manifest.getJSONArray("frozenSamples")
        check(frozen.length() == 36)
        for (index in 0 until frozen.length()) {
            val item = frozen.getJSONObject(index)
            check(item.getInt("sampleIndex") == index && item.getString("expected") == FROZEN_TEXTS[index % 18])
            val name = item.getString("file")
            cases += OcrRightContextCase(
                id = item.getString("id"),
                group = "frozen36",
                expected = item.getString("expected"),
                metadata = JSONObject(item.toString()).put("groundTruthIsPreOcrRenderingString", true),
                open = {
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(file(name).path))
                    check(bitmap.width == item.getInt("width") && bitmap.height == item.getInt("height"))
                    OcrRightContextDrawing(bitmap, rightContextQuad(item.getJSONArray("quad")))
                },
            )
        }
        val saved = manifest.getJSONArray("savedPages")
        check(saved.length() == 2)
        for (pageIndex in 0 until saved.length()) {
            val page = saved.getJSONObject(pageIndex)
            val name = page.getString("page")
            val expected = SAVED_TEXTS.getValue(name)
            check(page.getJSONArray("expected").toString() == JSONArray(expected).toString())
            val geometry = JSONObject(file(page.getString("geometry")).readText()).getJSONArray("regions")
            check(geometry.length() == expected.size)
            for (lineIndex in expected.indices) {
                val quad = rightContextQuad(geometry.getJSONObject(lineIndex).getJSONArray("quads").getJSONArray(0))
                cases += OcrRightContextCase(
                    id = "saved-$name-$lineIndex",
                    group = "saved16",
                    expected = expected[lineIndex],
                    metadata = JSONObject().put("page", name).put("region", lineIndex)
                        .put("image", page.getString("image")).put("geometry", page.getString("geometry"))
                        .put("groundTruthIsPreOcrRenderingString", true),
                    open = {
                        OcrRightContextDrawing(
                            checkNotNull(BitmapFactory.decodeFile(file(page.getString("image")).path)),
                            quad,
                        )
                    },
                )
            }
        }
        check(cases.size == 52)
        return cases
    }

    companion object {
        private val FROZEN_TEXTS = listOf(
            "私はここにいます", "ばらの花が咲きました", "ぱんを食べました", "これは", "ことば", "らっぱ", "ことば。", "らっぱ。",
            "少し待って、", "ありがとうございます、", "うたいます♪", "音楽です♫", "輪の形はの", "あいうえお", "これはぱ", "ことばば",
            "ことば", "らっぱ",
        )
        private val SAVED_TEXTS = mapOf(
            "001" to listOf("こんにちは。", "今日もよろしくお願いします。", "明日の会議は午後三時です。", "少し待ってください。"),
            "003" to listOf(
                "今日はいい天気ですね。", "一緒に出かけませんか。", "分かりました。", "分かりました。",
                "まだ準備ができません。", "少し待ってください。", "分かりました。", "分かりました。",
                "ありがとうございます。", "もうすぐ終わります。", "分かりました。", "分かりました。",
            ),
        )
    }
}

internal data class OcrRightContextCase(
    val id: String,
    val group: String,
    val expected: String,
    val metadata: JSONObject,
    val open: () -> OcrRightContextDrawing,
)

internal data class OcrRightContextDrawing(val page: Bitmap, val quad: List<Pt>)

internal fun rightContextQuad(array: JSONArray): List<Pt> {
    check(array.length() == 4)
    return (0 until 4).map { i ->
        array.getJSONArray(i).let { Pt(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
    }
}

internal fun rightContextGeneratedCases(): List<OcrRightContextCase> {
    val cases = ArrayList<OcrRightContextCase>()
    val phrases = listOf(
        "すずとただ。",
        "はばぱ、ただ。",
        "これはばらです。",
        "うたいます♪",
        "音楽です♫",
        "「ABC はばぱ、すず。」",
    )
    for (family in listOf("sans-serif", "serif")) {
        for (size in listOf(40f, 56f)) {
            for (bold in listOf(false, true)) {
                for (text in phrases) {
                    cases += generated(cases.size, "new-fonts", text, family, size, bold)
                }
            }
        }
    }
    val styles = listOf(Triple("sans-serif", 44f, false), Triple("serif", 60f, true))
    for ((family, size, bold) in styles) {
        for (text in listOf("らっぱ", "ことば")) {
            for (decoration in listOf("none", "lowerRing", "stem")) {
                cases += generated(cases.size, "new-decoration", text, family, size, bold, decoration)
            }
        }
        for (text in listOf("すず、", "「はばぱ。」")) {
            for (clip in listOf(0, 3)) {
                cases += generated(cases.size, "new-clip-quote", text, family, size, bold, clip = clip)
            }
        }
    }
    check(cases.size == 68)
    return cases
}

private fun generated(
    index: Int,
    group: String,
    text: String,
    family: String,
    size: Float,
    bold: Boolean,
    decoration: String = "none",
    clip: Int = 0,
): OcrRightContextCase {
    val metadata = JSONObject().put("requestedFontFamily", family).put("fontSize", size.toDouble())
        .put("requestedBold", bold).put("decoration", decoration).put("rightClipPixels", clip)
        .put("groundTruthScope", "Original drawText string; clipped controls retain that independent truth")
        .put("fontFallbackScope", "Android requested family and observed raster; no fixed system font binary claimed")
    return OcrRightContextCase("new-$index", group, text, metadata) {
        val face = Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = size
            typeface = face
        }
        check(
            text.codePoints().allMatch {
                Character.isWhitespace(it) || paint.hasGlyph(String(Character.toChars(it)))
            },
        ) {
            "Requested Android face cannot draw holdout source"
        }
        val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        val x = 20f
        val baseline = 130f
        var right = x + bounds.right
        var bottom = baseline + bounds.bottom
        var top = baseline + bounds.top
        val ringRadius = max(4f, size / 12f)
        val ringX = right + 12f
        val ringY = if (decoration == "lowerRing") bottom + 16f else baseline - 4f
        if (decoration != "none") {
            right = ringX + ringRadius + 2f
            bottom = max(bottom, ringY + ringRadius + 2f)
            top = minOf(top, ringY - ringRadius - 2f)
        }
        val width = if (clip > 0) ceil(right - clip).toInt() else 1000
        check(width > 30 && width <= 1000 && top >= 0 && bottom < 220)
        val bitmap = Bitmap.createBitmap(width, 220, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(text, x, baseline, paint)
                if (decoration != "none") {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    drawCircle(ringX, ringY, ringRadius, paint)
                    if (decoration == "stem") {
                        drawLine(ringX + ringRadius, ringY, ringX + ringRadius, baseline + bounds.top, paint)
                    }
                }
            }
            metadata.put("actualTypefaceStyle", face.style).put("actualTypefaceBold", face.isBold)
                .put("fontMetricsTop", paint.fontMetrics.top.toDouble())
                .put("fontMetricsBottom", paint.fontMetrics.bottom.toDouble()).put("allSourceGlyphsSupported", true)
            OcrRightContextDrawing(
                bitmap,
                listOf(
                    Pt(x + bounds.left, top),
                    Pt(minOf(right, width.toFloat()), top),
                    Pt(minOf(right, width.toFloat()), bottom),
                    Pt(x + bounds.left, bottom),
                ),
            )
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }
}
