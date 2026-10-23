package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.Pt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.roundToInt

/** The same 18 neutral texts/decorations and Android drawing operations as OcrPunctuationTest. */
internal data class OcrDiagnosticSample(val text: String, val decoration: String = "none") {
    fun draw(fontSize: Float): OcrDiagnosticDrawing {
        val page = Bitmap.createBitmap(1000, 160, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = fontSize
            }
            Canvas(page).apply {
                drawColor(Color.WHITE)
                drawText(text, 20f, 110f, paint)
            }
            val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
            val originalBounds = Rect(bounds)
            val glyphSupport = JSONArray()
            text.codePoints().distinct().toArray().forEach { codePoint ->
                val glyph = String(Character.toChars(codePoint))
                glyphSupport.put(JSONObject().put("text", glyph).put("paintHasGlyph", paint.hasGlyph(glyph)))
            }
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
            return OcrDiagnosticDrawing(page, quad, originalBounds, bounds, glyphSupport)
        } catch (error: Throwable) {
            page.recycle()
            throw error
        }
    }

    companion object {
        val ALL = listOf(
            "私はここにいます", "ばらの花が咲きました", "ぱんを食べました", "これは", "ことば", "らっぱ", "ことば。", "らっぱ。",
            "少し待って、", "ありがとうございます、", "うたいます♪", "音楽です♫", "輪の形はの", "あいうえお", "これはぱ", "ことばば",
        ).map { OcrDiagnosticSample(it) } + listOf(
            OcrDiagnosticSample("ことば", "belowRing"),
            OcrDiagnosticSample("らっぱ", "hollowNote"),
        )
    }
}

internal data class OcrDiagnosticDrawing(
    val page: Bitmap,
    val quad: List<Pt>,
    val textBounds: Rect,
    val decoratedBounds: Rect,
    val glyphSupport: JSONArray,
)

internal data class OcrDiagnosticVariant(
    val name: String,
    val stripPad: Int,
    val rightMargin: Int,
    val unsharp: Boolean,
) {
    fun json() = JSONObject().put("name", name).put("stripPad", stripPad)
        .put("rightMargin", rightMargin).put("unsharp", unsharp).put("bicubic", true).put("textHeight", 48)

    companion object {
        val BASELINE = OcrDiagnosticVariant("baseline", 4, 16, true)
        val ALL = listOf(
            BASELINE,
            BASELINE.copy(name = "right32", rightMargin = 32),
            BASELINE.copy(name = "unsharpOff", unsharp = false),
            BASELINE.copy(name = "cropPad6", stripPad = 6),
        )
    }
}

/** Test-only observation of one private Ocr. No preferences, model copying or network operations. */
@OptIn(InternalEngineApi::class)
internal class OcrDiagnosticProbe(private val ocr: Ocr, private val dictionary: List<String>) {
    private val threadId = Thread.currentThread().id
    private val handle = field("ncnnHandle").getLong(ocr).also { check(it != 0L) }
    private val threads = field("threads").getInt(ocr)
    private val pe = (field("PE\$delegate").get(null) as Lazy<*>).value as FloatArray
    private val methods = Ocr::class.java.declaredMethods.associateBy { it.name }
    private val widthGuard = Class.forName("li.joye.yakuyomi.engine.OcrSafetyKt").declaredMethods
        .single { it.name == "checkedOcrInputWidth" }.apply { isAccessible = true }
    private val collapse = Ocr.Companion::class.java.declaredMethods
        .single { it.name.startsWith("ctcCollapse") }.apply { isAccessible = true }
    var explicitNativeCalls = 0
        private set
    var privateInferParityCalls = 0
        private set

    fun metadata() = JSONObject().put("backend", ocr.backend).put("nativeThreads", threads)
        .put("serialize", threads > 1).put("javaThreadId", threadId)
        .put("peSource", "Actual production Ocr PE delegate; no alternate encoding")
        .put("peElements", pe.size).put("peFloat32LittleEndianSha256", diagnosticFloatSha(pe))

    @Suppress("UNCHECKED_CAST")
    fun selectedParam(basePath: String): Pair<String, Boolean> =
        call(method("pickParam"), ocr, basePath) as Pair<String, Boolean>

    @Suppress("UNCHECKED_CAST")
    fun crop(page: Bitmap, quad: List<Pt>, variant: OcrDiagnosticVariant): Pair<Bitmap, List<Pt>> {
        sameThread()
        val expanded = call(method("expandQuad"), ocr, quad, variant.stripPad, page.width, page.height) as List<Pt>
        val sorted = call(method("sortPnts"), ocr, expanded) as Pair<List<Pt>, Boolean>
        check(!sorted.second) { "This diagnostic corpus must remain horizontal" }
        val strip = checkNotNull(
            call(method("transformedRegion"), ocr, page, sorted.first, false, 48, true) as Bitmap?,
        )
        return strip to expanded
    }

    fun tensor(strip: Bitmap, variant: OcrDiagnosticVariant): OcrDiagnosticTensor {
        sameThread()
        val sw = strip.width
        val height = strip.height
        val width = call(widthGuard, null, sw, variant.rightMargin) as Int
        val pixels = diagnosticPixels(strip)
        if (variant.unsharp) call(method("unsharp"), ocr, pixels, sw, height)
        val area = width * height
        val chw = FloatArray(area * 3) { 1f }
        for (y in 0 until height) {
            for (x in 0 until sw) {
                val pixel = pixels[y * sw + x]
                val output = y * width + x
                chw[output] = (((pixel shr 16) and 255) - 127.5f) / 127.5f
                chw[area + output] = (((pixel shr 8) and 255) - 127.5f) / 127.5f
                chw[2 * area + output] = ((pixel and 255) - 127.5f) / 127.5f
            }
        }
        val production = if (variant.unsharp) {
            call(method("stripToChwWithMargin"), ocr, strip, variant.rightMargin)
        } else {
            null
        }
        if (production != null) {
            check(chwField(production, "w") == width && chwField(production, "h") == height)
            val expected = chwField(production, "chw") as FloatArray
            check(
                expected.size == chw.size && expected.indices.all { expected[it].toRawBits() == chw[it].toRawBits() },
            ) {
                "Diagnostic CHW differs from actual production preprocessing: ${variant.name}"
            }
        }
        return OcrDiagnosticTensor(chw, width, height, production)
    }

    @Suppress("UNCHECKED_CAST")
    fun infer(tensor: OcrDiagnosticTensor, baseline: Boolean): JSONObject {
        sameThread()
        val steps = tensor.width / 4 - 1
        check(steps >= 1 && steps * 320 <= pe.size)
        val indices = IntArray(steps)
        val logp = FloatArray(steps)
        val start = SystemClock.elapsedRealtimeNanos()
        explicitNativeCalls++
        check(
            NcnnBackend.ocrCtc(
                handle, tensor.chw, tensor.width, tensor.height, pe, steps, indices, logp, threads > 1,
            ) == steps,
        )
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        sameThread()
        val allSteps = JSONArray()
        val emissions = JSONArray()
        val text = StringBuilder()
        var last = 0
        var sum = 0.0
        for (step in 0 until steps) {
            val index = indices[step]
            check(index in dictionary.indices && logp[step].isFinite()) { "Invalid native CTC result" }
            val emitted = index != 0 && index != last
            val token = dictionary[index]
            allSteps.put(
                JSONObject().put("step", step).put("id", index).put("token", token)
                    .put("logProbability", logp[step].toDouble()).put("probability", exp(logp[step].toDouble()))
                    .put("emitted", emitted),
            )
            if (emitted) {
                text.append(if (token == "<SP>") " " else token)
                sum += logp[step]
                emissions.put(
                    JSONObject().put("step", step).put("id", index).put("token", token)
                        .put("logProbability", logp[step].toDouble()).put("probability", exp(logp[step].toDouble())),
                )
            }
            last = index
        }
        val probability = if (emissions.length() == 0) 0f else exp(sum / emissions.length()).toFloat()
        val decoded = call(collapse, Ocr.Companion, indices, logp, steps, dictionary) as Pair<String, Float>
        check(decoded.first == text.toString() && decoded.second.toRawBits() == probability.toRawBits()) {
            "Diagnostic decoder differs from actual production collapse"
        }
        if (baseline) {
            val production = checkNotNull(tensor.production)
            privateInferParityCalls++
            val actual = call(method("infer"), ocr, production) as Pair<String, Float>
            check(actual.first == decoded.first && actual.second.toRawBits() == decoded.second.toRawBits()) {
                "Repeated same-input production infer differs from diagnostic native inference"
            }
        }
        return JSONObject().put("text", text.toString()).put("probability", probability.toDouble())
            .put("emissions", emissions).put("steps", allSteps).put("timeSteps", steps)
            .put("nativeCallMs", elapsedMs).put("decoderParity", true)
            .put("productionInferParity", if (baseline) true else JSONObject.NULL)
    }

    private fun sameThread() {
        check(Thread.currentThread().id == threadId) { "Native diagnostic moved to another Java thread" }
    }

    private fun method(name: String) = checkNotNull(methods[name]) { "Missing production method $name" }
        .apply { isAccessible = true }

    private fun field(name: String) = Ocr::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun chwField(instance: Any, name: String) = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)

    private fun call(method: Method, owner: Any?, vararg args: Any?): Any? = try {
        method.invoke(owner, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}

internal data class OcrDiagnosticTensor(val chw: FloatArray, val width: Int, val height: Int, val production: Any?) {
    fun image(): Bitmap {
        val area = width * height
        val pixels = IntArray(area) { i ->
            fun channel(offset: Int) = (chw[offset + i] * 127.5f + 127.5f).roundToInt().coerceIn(0, 255)
            Color.rgb(channel(0), channel(area), channel(area * 2))
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun json() = JSONObject().put("width", width).put("height", height).put("channels", 3)
        .put("layout", "RGB CHW; float32 little-endian hash excludes dimensions")
        .put("float32LittleEndianSha256", diagnosticFloatSha(chw))
        .put("productionPreprocessParity", if (production == null) JSONObject.NULL else true)
}

internal class OcrDiagnosticArtifacts(val directory: File) {
    private class Owned(val file: File, var bytes: Long = -1, var sha256: String? = null)
    private val owned = ArrayList<Owned>()

    fun bitmap(name: String, bitmap: Bitmap): JSONObject {
        check(name.matches(Regex("sample-[0-9]{3}-(source|[A-Za-z0-9]+-(strip|tensor))\\.png")))
        val file = File(directory, name)
        check(file.canonicalFile.parentFile == directory.canonicalFile && !file.exists())
        val entry = Owned(file)
        owned += entry
        var failure: Throwable? = null
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                if (file.exists()) {
                    entry.bytes = file.length()
                    entry.sha256 = diagnosticSha(file)
                }
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
        check(entry.bytes > 0 && entry.sha256 != null) { "Neutral PNG did not survive its write" }
        return JSONObject().put("name", name).put("bytes", entry.bytes).put("sha256", entry.sha256)
            .put("rasterInk", diagnosticInk(bitmap))
    }

    fun manifest() = JSONArray(
        owned.map {
            JSONObject().put("name", it.file.name).put("bytes", it.bytes)
                .put("sha256", it.sha256 ?: JSONObject.NULL)
        },
    )

    fun cleanup(): Int {
        for (entry in owned) {
            check(entry.file.canonicalFile.parentFile == directory.canonicalFile)
            check(
                entry.sha256 != null && entry.file.length() == entry.bytes &&
                    diagnosticSha(entry.file) == entry.sha256,
            )
        }
        for (entry in owned) check(entry.file.delete()) { "Cannot delete own neutral image ${entry.file.name}" }
        check(owned.none { it.file.exists() })
        return owned.size
    }
}

internal fun diagnosticPixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
}

/** Independent raster observations, not Paint metrics, recognized text or calibrated CTC positions. */
private fun diagnosticInk(bitmap: Bitmap): JSONArray {
    val pixels = diagnosticPixels(bitmap)
    return JSONArray(
        listOf(128, 245).map { threshold ->
            var left = bitmap.width
            var top = bitmap.height
            var right = 0
            var bottom = 0
            var count = 0
            val edges = IntArray(4)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                if ((pixel ushr 16 and 255) >= threshold ||
                    (pixel ushr 8 and 255) >= threshold || (pixel and 255) >= threshold
                ) {
                    continue
                }
                val x = index % bitmap.width
                val y = index / bitmap.width
                count++
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
                if (x == 0) edges[0]++
                if (y == 0) edges[1]++
                if (x == bitmap.width - 1) edges[2]++
                if (y == bitmap.height - 1) edges[3]++
            }
            JSONObject().put("eachRgbChannelBelow", threshold).put("pixels", count)
                .put("boundsHalfOpen", if (count == 0) JSONObject.NULL else JSONArray(listOf(left, top, right, bottom)))
                .put("edgePixelCountsLeftTopRightBottom", JSONArray(edges.toList()))
        },
    )
}

internal fun diagnosticDarkOutsideQuad(pixels: IntArray, width: Int, quad: List<Pt>): Int {
    var outside = 0
    for (index in pixels.indices) {
        val pixel = pixels[index]
        if ((pixel ushr 16 and 255) >= 128 || (pixel ushr 8 and 255) >= 128 || (pixel and 255) >= 128) continue
        val x = index % width + 0.5f
        val y = index / width + 0.5f
        var inside = false
        var previous = quad.last()
        for (point in quad) {
            if ((point.y > y) != (previous.y > y) &&
                x < (previous.x - point.x) * (y - point.y) / (previous.y - point.y) + point.x
            ) {
                inside = !inside
            }
            previous = point
        }
        if (!inside) outside++
    }
    return outside
}

internal fun diagnosticFloatSha(values: FloatArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    for (value in values) {
        if (buffer.remaining() < Float.SIZE_BYTES) {
            digest.update(buffer.array(), 0, buffer.position())
            buffer.clear()
        }
        buffer.putFloat(value)
    }
    digest.update(buffer.array(), 0, buffer.position())
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun diagnosticSha(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
