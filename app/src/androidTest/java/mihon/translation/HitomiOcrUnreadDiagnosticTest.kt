package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.crash.TraceLog
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineTrace
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.OcrSelectionDiagnostics
import li.joye.yakuyomi.engine.OcrSelectionDiagnostics.EventType
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import mihon.source.hitomi.HitomiClient
import mihon.source.hitomi.HitomiDohDns
import mihon.source.hitomi.HitomiImageInterceptor
import mihon.source.hitomi.HitomiProtocol
import mihon.source.hitomi.HitomiSource
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fresh selected-page research, not a replay of the signed reader's private original or geometry.
 * Required arguments: liveHitomiOcrDiagnostic=true, diagnosticRunId=UUID, hitomiReader=/reader/ID.html,
 * pageNumbers=2,3. No content, URL, raw trace, exception message or image is emitted or saved.
 * Public default Detector/Ocr configurations are test defaults, not the signed app's observed settings.
 */
@OptIn(InternalEngineApi::class)
@RunWith(AndroidJUnit4::class)
class HitomiOcrUnreadDiagnosticTest {
    @Test
    fun selectedPagesHaveParityAndNumericUnreadEvidence() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "hitomi-ocr-unread") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) { withTimeout(600_000) { collect() } }
            }
    }

    private suspend fun collect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val report = JSONObject().put("schemaVersion", 1).put("passed", false)
            .put("freshFetchAndDetection", true).put("signedReaderOriginalEqualityClaimed", false)
            .put("signedReaderGeometryEqualityClaimed", false).put("usesTestDefaultConfigurations", true)
            .put("publicSourcePageListInvoked", false).put("productionMetadataAndRoutingParsersUsed", true)
            .put("preferenceWritesByTest", 0).put("imageFilesByTest", 0).put("contentFilesByTest", 0)
            .put("translationCallsByTest", 0).put("downloadQueueCallsByTest", 0)
            .put("sharedEngineShutdownCalls", 0).put("warmUpCalls", 0)
        val pages = JSONArray()
        report.put("pages", pages)
        var phase = 0
        var failure: Throwable? = null
        var models: Models? = null
        var client: OkHttpClient? = null
        val nativeOwners = mutableListOf<Any>()
        val httpExecutor = Executors.newCachedThreadPool { task -> Thread(task, "hitomi-ocr-http") }
        val pool = ConnectionPool()
        fun status(terminal: Boolean = false) {
            report.put("phase", phase).put("terminal", terminal)
            instrumentation.sendStatus(2, Bundle().apply { putString("hitomiOcrUnreadEvidence", report.toString()) })
        }
        try {
            phase = 1
            check(arguments.getString("liveHitomiOcrDiagnostic") == "true")
            check(arguments.getString("diagnosticRunId")?.matches(UUID_PATTERN) == true)
            check(arguments.getString("pageNumbers") == "2,3")
            val reader = checkNotNull(arguments.getString("hitomiReader"))
            val match = checkNotNull(READER_PATTERN.matchEntire(reader))
            val id = checkNotNull(match.groupValues[1].toLongOrNull()).also { check(it > 0) }
            check(context.packageName == "app.yomitori.yakuyomi.audit")
            check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
            idle(context)
            val nativeModels = Models(context).also { models = it }
            report.put("modelsBefore", nativeModels.manifest())
            val source = HitomiSource(Injekt.get<NetworkHelper>())
            // Keep source DNS/proxy/TLS and use a separately owned production image interceptor. This client
            // has no HTTP cache, cookie store, verbose logger or challenge/WebView interceptor.
            val ownedClient = source.client.newBuilder().apply {
                interceptors().clear()
                networkInterceptors().clear()
            }.addInterceptor(HitomiImageInterceptor()).cache(null).cookieJar(CookieJar.NO_COOKIES).connectionPool(pool)
                .dispatcher(Dispatcher(httpExecutor)).callTimeout(90, TimeUnit.SECONDS).build().also { client = it }
            check(ownedClient.cache == null && ownedClient.connectionPool === pool)
            check(ownedClient.sslSocketFactory === source.client.sslSocketFactory)
            check(ownedClient.hostnameVerifier === source.client.hostnameVerifier)
            check(ownedClient.interceptors.count { it is HitomiImageInterceptor } == 1)
            report.put("privateCachelessHttpClient", true).put("normalTlsValidationPreserved", true)
                .put("sharedSourceDohAndRelayBorrowed", true).put("sharedSourceTransportShutdownCalls", 0)
            status()
            phase = 2
            val metadataBytes = fetch(ownedClient, source, "${HitomiClient.INDEX}/galleries/$id.js", MAX_METADATA_BYTES)
            val descriptors = try {
                val gallery = Json.parseToJsonElement(HitomiProtocol.galleryJson(metadataBytes.decodeToString()))
                    .jsonObject
                check(gallery.getValue("id").jsonPrimitive.content.toLongOrNull() == id)
                val files = gallery.getValue("files").jsonArray
                check(files.size >= 3)
                listOf(2, 3).map { number ->
                    val file = files[number - 1].jsonObject
                    val hash = file.getValue("hash").jsonPrimitive.content
                    check(hash.matches(Regex("[a-fA-F0-9]{3,128}")))
                    // Same natural-format rule as HitomiSource.getPageList; no forced WebP request.
                    val webp = file["haswebp"]?.jsonPrimitive?.content == "1"
                    val avif = file["hasavif"]?.jsonPrimitive?.content == "1"
                    PageDescriptor(number, hash, if (webp && !avif) "webp" else "avif")
                }
            } finally {
                metadataBytes.fill(0)
            }
            phase = 3
            val routingBytes = fetch(ownedClient, source, "${HitomiClient.INDEX}/gg.js", MAX_ROUTING_BYTES)
            val routing = try {
                HitomiProtocol.routing(routingBytes.decodeToString())
            } finally {
                routingBytes.fill(0)
            }
            for (descriptor in descriptors) {
                currentCoroutineContext().ensureActive()
                idle(context)
                phase = 10 + descriptor.number
                val bytes = fetch(
                    ownedClient,
                    source,
                    routing.imageUrl(descriptor.hash, descriptor.extension),
                    MAX_IMAGE_BYTES,
                )
                val row = JSONObject().put("pageOneBased", descriptor.number).put("encodedBytes", bytes.size)
                    .put("encodedSha256", sha(bytes))
                    .put("fullResolutionDecode", true).put("detectorCalls", 0).put("offCalls", 0).put("onCalls", 0)
                pages.put(row)
                try {
                    bytes.inputStream().use { input ->
                        val decoder = checkNotNull(ImageDecoder.newInstance(input, false, null))
                        try {
                            check(decoder.width in 1..MAX_SIDE && decoder.height in 1..MAX_SIDE)
                            check(decoder.width.toLong() * decoder.height <= MAX_PIXELS)
                            val bitmap = checkNotNull(decoder.decode(sampleSize = 1))
                            try {
                                check(bitmap.width == decoder.width && bitmap.height == decoder.height)
                                analyze(context, nativeModels, bitmap, row, nativeOwners)
                            } finally {
                                bitmap.recycle()
                            }
                        } finally {
                            decoder.recycle()
                        }
                    }
                } finally {
                    bytes.fill(0)
                    row.put("ownedEncodedBytesCleared", true)
                }
                status()
            }
            check(pages.length() == 2)
            report.put("passed", true)
            phase = 20
        } catch (error: Throwable) {
            // A private transport/native exception can embed a URL. Retain failure semantics, never its message/cause.
            failure = error
            report.put("errorClass", error.javaClass.name).put("passed", false)
        } finally {
            var cleanupFailures = 0
            fun verify(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    cleanupFailures++
                    if (failure == null) failure = error
                }
            }
            verify {
                nativeOwners.forEach { owner ->
                    check(
                        owner.javaClass.getDeclaredField("ncnnHandle").apply { isAccessible = true }.getLong(owner) ==
                            0L,
                    )
                }
            }
            verify { models?.verifyUnchanged() }
            verify { idle(context) }
            client?.dispatcher?.cancelAll()
            verify { pool.evictAll() }
            httpExecutor.shutdownNow()
            verify { check(httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) }
            verify { check(pool.connectionCount() == 0) }
            report.put("nativeOwnersCreated", nativeOwners.size).put("cleanupFailures", cleanupFailures)
                .put("ownedNativeHandlesClosed", cleanupFailures == 0)
                .put("modelsUnchanged", models?.unchanged == true)
                .put("privateHttpExecutorTerminated", httpExecutor.isTerminated)
                .put("privateHttpPoolConnections", pool.connectionCount())
                .put("passed", failure == null && report.optBoolean("passed"))
            status(terminal = true)
        }
        failure?.let { error ->
            val safe = "Hitomi OCR diagnostic failed at phase $phase (${error.javaClass.simpleName}); " +
                "private details omitted"
            when (error) {
                is CancellationException -> throw CancellationException(safe)
                is VirtualMachineError -> throw error
                is ThreadDeath -> throw error
                else -> throw AssertionError(safe)
            }
        }
    }

    private suspend fun analyze(
        context: Context,
        models: Models,
        page: Bitmap,
        row: JSONObject,
        owners: MutableList<Any>,
    ) {
        val source = SourceSnapshot(page)
        row.put("width", page.width).put("height", page.height).put("density", page.density)
        val started = SystemClock.elapsedRealtimeNanos()
        val geometry = Detector(models.file("dbnet_detect.ncnn.param").path).also { owners.add(it) }.use { detector ->
            val detection = detector.detect(page)
            try {
                check(detection.lines.size in 1..MAX_LINES)
                row.put("detectorCalls", 1).put("detectedLines", detection.lines.size)
                detection.lines.map { cloneLine(it, page) }
            } finally {
                detection.textMask.recycle()
            }
        }
        row.put("detectMs", elapsed(started))
        source.verify(page)
        val config = OcrConfig()
        row.put("minProbability", config.minProb.toDouble()).put("ocrConcurrency", config.concurrency)
            .put("ocrConcurrent", config.concurrent).put("ocrUnsharp", config.ocrUnsharp)
            .put("ocrBicubic", config.useBicubic).put("ocrStripPad", config.stripPad)
        val off = geometry.map { cloneLine(it, page) }
        val on = geometry.map { cloneLine(it, page) }
        val offKept = Ocr(models.file("ocr_48px_ctc.ncnn.param").path, models.dictionary, config)
            .also { owners.add(it) }.use { ocr ->
                idle(context)
                row.put("offSelectedParam", models.selected(ocr))
                val time = SystemClock.elapsedRealtimeNanos()
                ocr.recognize(page, off).also { row.put("offCalls", 1).put("offMs", elapsed(time)) }
            }
        source.verify(page)
        val trace = OcrSelectionDiagnostics(maxLines = MAX_LINES, maxEvents = 32768)
        val onKept = Ocr(models.file("ocr_48px_ctc.ncnn.param").path, models.dictionary, config, diagnostics = trace)
            .also { owners.add(it) }.use { ocr ->
                idle(context)
                row.put("onSelectedParam", models.selected(ocr))
                check(
                    row.getJSONObject("offSelectedParam").toString() == row.getJSONObject("onSelectedParam").toString(),
                )
                val time = SystemClock.elapsedRealtimeNanos()
                ocr.recognize(page, on).also { row.put("onCalls", 1).put("onMs", elapsed(time)) }
            }
        source.verify(page)
        val lines = JSONArray()
        row.put("lines", lines)
        for (index in geometry.indices) {
            check(off[index].quad == geometry[index].quad && on[index].quad == geometry[index].quad)
            check(off[index].text == on[index].text && off[index].direction == on[index].direction)
            check((off[index] in offKept) == (on[index] in onKept))
            lines.put(lineEvidence(index, on[index], on[index] in onKept, page))
        }
        row.put("observerExactParity", true).put("sourcePixelsMetadataAndGeometryUnchanged", true)
            .put("finalEmptyLines", on.count { it.text.isEmpty() })
            .put("finalWhitespaceOnlyLines", on.count { it.text.isNotEmpty() && it.text.isBlank() })
            .put("finalNonBlankLines", on.count { it.text.isNotBlank() }).put("intentionallyKeptLines", onKept.size)
        validateTrace(trace.snapshot(), on, onKept, config, lines, row)
        idle(context)
    }

    private fun validateTrace(
        snapshot: OcrSelectionDiagnostics.Snapshot,
        lines: List<TextLine>,
        kept: Set<TextLine>,
        config: OcrConfig,
        rows: JSONArray,
        page: JSONObject,
    ) {
        check(snapshot.callCount == 1L && snapshot.lineCount == lines.size.toLong())
        check(snapshot.recordedLines == lines.size.toLong() && snapshot.droppedLines == 0L)
        check(snapshot.droppedEvents == 0L && snapshot.recordingFailureCount == 0L && !snapshot.truncated)
        val events = snapshot.events
        check(events.all { it.callOrdinal == 1L && it.errorClass == null })
        check(events.first().type == EventType.CALL_START && events.last().type == EventType.CALL_END)
        check(events.count { it.type == EventType.CALL_START } == 1)
        check(events.count { it.type == EventType.CALL_END } == 1 && events.last().reason == "completed")
        var nativeStarted = 0L
        var nativeCompleted = 0L
        for (index in lines.indices) {
            val own = events.filter { it.lineIndex == index }
            val end = own.single { it.type == EventType.LINE_END }
            check(end == own.last() && end.reason != "error" && end.reason != "preexisting")
            check(end.text == lines[index].text && end.direction == lines[index].direction)
            check(end.accepted == lines[index].text.isNotBlank() && end.intentionallyKept == (lines[index] in kept))
            val starts = own.filter { it.type == EventType.ATTEMPT_START }
            val ends = own.filter { it.type == EventType.ATTEMPT_END }
            check(starts.isNotEmpty() && starts.map { it.attemptIndex } == starts.indices.toList())
            check(starts.map { it.attemptIndex } == ends.map { it.attemptIndex })
            check(ends.all { it.reason != "error" })
            val attempts = JSONArray()
            for (attempt in starts.indices) {
                val path = own.filter { it.attemptIndex == attempt && it.type != EventType.LINE_END }
                check(path.first().type == EventType.ATTEMPT_START && path.last().type == EventType.ATTEMPT_END)
                val crop = path.single { it.type == EventType.CROP }
                val terminals = path.filter { it.type == EventType.TERMINAL }
                val inferred = path.count { it.type == EventType.INFER }
                val warpFailed = crop.reason == "warp-null"
                val ignored = path.last().intentionallyKept == true
                val attemptRow = JSONObject().put("index", attempt).put("warpFailed", warpFailed)
                    .put("policyIgnored", ignored).put("inferences", inferred)
                    .put("accepted", path.last().accepted == true).put("intentionallyKept", ignored)
                attempts.put(attemptRow)
                if (warpFailed || ignored) {
                    check(terminals.isEmpty() && inferred == 0 && path.none { it.type == EventType.CONTEXT })
                    check(!warpFailed || !ignored)
                    check(!ignored || config.ignoreBubble in 1..50)
                    continue
                }
                check(crop.reason == "warped" && terminals.size == 1 && inferred > 0)
                val terminal = terminals.single()
                val text = checkNotNull(terminal.text)
                val probability = Float.fromBits(checkNotNull(terminal.probabilityBits))
                check(probability.isFinite())
                // Empty, whitespace and below-threshold are independent numeric facts, not a reason-string guess.
                attemptRow.put("terminalTextLength", text.length)
                    .put("terminalEmpty", text.isEmpty())
                    .put("terminalWhitespaceOnly", text.isNotEmpty() && text.isBlank())
                    .put("terminalProbability", probability.toDouble())
                    .put("terminalBelowMinProbability", probability < config.minProb)
                    .put(
                        "bodyInferences",
                        path.count {
                            it.type == EventType.INFER &&
                                it.role == OcrSelectionDiagnostics.InferRole.BODY
                        },
                    )
                    .put(
                        "fallbackInferences",
                        path.count {
                            it.type == EventType.INFER &&
                                it.role == OcrSelectionDiagnostics.InferRole.LEGACY_FALLBACK
                        },
                    )
            }
            val started = checkNotNull(end.nativeCallsStarted)
            val completed = checkNotNull(end.nativeCallsCompleted)
            check(started == completed && completed == own.count { it.type == EventType.INFER }.toLong())
            nativeStarted += started
            nativeCompleted += completed
            rows.getJSONObject(index).put("attempts", attempts).put("nativeBoundaryCallsStarted", started)
                .put("nativeBoundaryCallsCompleted", completed)
        }
        check(
            events.all {
                it.lineIndex in lines.indices || it.type in setOf(EventType.CALL_START, EventType.CALL_END)
            },
        )
        page.put("traceComplete", true).put("traceEvents", events.size)
            .put("nativeBoundaryCallsStarted", nativeStarted).put("nativeBoundaryCallsCompleted", nativeCompleted)
            .put("traceDroppedLines", snapshot.droppedLines).put("traceDroppedEvents", snapshot.droppedEvents)
            .put("traceRecordingFailures", snapshot.recordingFailureCount).put("traceTruncated", snapshot.truncated)
    }

    private fun lineEvidence(index: Int, line: TextLine, kept: Boolean, bitmap: Bitmap): JSONObject {
        val left = floor(line.quad.minOf { it.x }).toInt().coerceIn(0, bitmap.width)
        val top = floor(line.quad.minOf { it.y }).toInt().coerceIn(0, bitmap.height)
        val right = ceil(line.quad.maxOf { it.x }).toInt().coerceIn(left, bitmap.width)
        val bottom = ceil(line.quad.maxOf { it.y }).toInt().coerceIn(top, bitmap.height)
        check(right > left && bottom > top)
        val buffer = IntArray(right - left)
        var dark = 0L
        var alpha = 0L
        for (y in top until bottom) {
            bitmap.getPixels(buffer, 0, buffer.size, left, y, buffer.size, 1)
            for (pixel in buffer) {
                if (pixel ushr 24 != 255) alpha++
                if ((pixel ushr 16 and 255) < 128 && (pixel ushr 8 and 255) < 128 && (pixel and 255) < 128) dark++
            }
        }
        return JSONObject().put("lineIndex", index).put("detectorScore", line.score.toDouble())
            .put("quad", JSONArray(line.quad.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }))
            .put("roiLeft", left).put("roiTop", top).put("roiRight", right).put("roiBottom", bottom)
            .put("roiPixels", (right - left).toLong() * (bottom - top)).put("roiRawDarkPixels", dark)
            .put("roiNonOpaquePixels", alpha).put("finalTextLength", line.text.length)
            .put("finalEmpty", line.text.isEmpty()).put(
                "finalWhitespaceOnly",
                line.text.isNotEmpty() && line.text.isBlank(),
            )
            .put("vertical", line.direction == "v").put("intentionallyKept", kept)
    }

    private suspend fun fetch(client: OkHttpClient, source: HitomiSource, url: String, limit: Int): ByteArray {
        val parsed = url.toHttpUrl()
        check(parsed.isHttps && parsed.port == 443 && HitomiDohDns.allows(parsed.host))
        val request = Request.Builder().url(parsed).headers(source.headers)
            .header("Cache-Control", "no-cache, no-store").build()
        return client.newCall(request).awaitSuccess().use { response ->
            check(response.isSuccessful && response.cacheResponse == null)
            check(response.body.contentLength() <= limit)
            response.body.source().use { input ->
                input.timeout().deadline(30, TimeUnit.SECONDS)
                val output = PrivateByteBuffer()
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size().toLong() + count <= limit)
                        output.write(buffer, 0, count)
                    }
                    check(output.size() > 0)
                    output.toByteArray()
                } finally {
                    buffer.fill(0)
                    output.clear()
                }
            }
        }
    }

    private fun idle(context: Context) {
        NativeDiagnosticIdleGuard.requireIdle(context)
        check(!TraceLog.isEnabled && EngineTrace.sink == null)
    }

    private fun cloneLine(line: TextLine, page: Bitmap): TextLine {
        check(line.quad.size == 4 && line.score.isFinite())
        check(
            line.quad.all {
                it.x.isFinite() && it.y.isFinite() && it.x in 0f..page.width.toFloat() &&
                    it.y in 0f..page.height.toFloat()
            },
        )
        return TextLine(line.quad.map { Pt(it.x, it.y) }, line.score)
    }

    private class SourceSnapshot(bitmap: Bitmap) {
        private val width = bitmap.width
        private val height = bitmap.height
        private val density = bitmap.density
        private val config = bitmap.config
        private val alpha = bitmap.hasAlpha()
        private val premultiplied = bitmap.isPremultiplied
        private val hash = pixelsSha(bitmap)
        fun verify(bitmap: Bitmap) {
            check(!bitmap.isRecycled && bitmap.width == width && bitmap.height == height && bitmap.density == density)
            check(bitmap.config == config && bitmap.hasAlpha() == alpha && bitmap.isPremultiplied == premultiplied)
            check(hash.contentEquals(pixelsSha(bitmap)))
        }
    }

    private class Models(context: Context) {
        private val directory = File(context.filesDir.canonicalFile, "models")
        private val files = REQUIRED.map { expected ->
            check(
                directory.canonicalFile == directory.absoluteFile &&
                    directory.parentFile == context.filesDir.canonicalFile,
            )
            val file = File(directory, expected.name)
            check(file.isFile && file.canonicalFile == file.absoluteFile && file.parentFile == directory)
            check(file.length() == expected.bytes && diagnosticSha(file) == expected.sha256)
            file
        }
        private val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
        val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }.also {
            check(sha(dictionaryBytes) == DICTIONARY_SHA && it.size == 19264)
            dictionaryBytes.fill(0)
        }
        var unchanged = false
            private set
        fun file(name: String) = files.single { it.name == name }
        fun manifest() = JSONArray(
            REQUIRED.map {
                JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256)
            },
        )
        fun selected(ocr: Ocr): JSONObject {
            val selected = OcrDiagnosticProbe(ocr, dictionary).selectedParam(file("ocr_48px_ctc.ncnn.param").path)
            val file = file(File(selected.first).name)
            check(file.path == selected.first)
            return JSONObject().put("name", file.name).put("mixed", selected.second).put("sha256", diagnosticSha(file))
        }
        fun verifyUnchanged() {
            files.zip(REQUIRED).forEach { (file, expected) ->
                check(file.length() == expected.bytes && diagnosticSha(file) == expected.sha256)
            }
            unchanged = true
        }
    }

    private data class PageDescriptor(val number: Int, val hash: String, val extension: String)
    private data class ExpectedModel(val name: String, val bytes: Long, val sha256: String)
    private class PrivateByteBuffer : ByteArrayOutputStream() {
        fun clear() {
            buf.fill(0)
            reset()
        }
    }

    private companion object {
        const val MAX_METADATA_BYTES = 4 * 1024 * 1024
        const val MAX_ROUTING_BYTES = 1024 * 1024
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        const val MAX_SIDE = 8192
        const val MAX_PIXELS = 16_777_216L
        const val MAX_LINES = 256
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val READER_PATTERN = Regex("(?:https://hitomi\\.la)?/reader/([1-9][0-9]*)\\.html")
        val REQUIRED = listOf(
            ExpectedModel(
                "dbnet_detect.ncnn.param",
                13_392,
                "9e6db2f8c6b0662ab00eb2100b3373d3c984a235eaac0e61c0b2a484ee1ff7b5",
            ),
            ExpectedModel(
                "dbnet_detect.ncnn.bin",
                153_010_556,
                "f57bdbede7764a534c56e88be0269602259a7fcd47e54e8b7d954fd0fcc55c3d",
            ),
            ExpectedModel(
                "ocr_48px_ctc.ncnn.param",
                18_133,
                "e701cfc5df9d3c55c9fd0a36725499d01d45c18e32946271fc26e580cd9901bd",
            ),
            ExpectedModel(
                "ocr_48px_ctc_mixed.ncnn.param",
                18_438,
                "32e298deca3acb8ba95897ca8cccbd028582ae42a697c3c05b4eaefe198309e3",
            ),
            ExpectedModel(
                "ocr_48px_ctc.ncnn.bin",
                83_037_664,
                "3e0a809441f5284871d18a3d757a7ec098c4b9ee64bdfd7ac776a84aa057c7a9",
            ),
        )

        fun elapsed(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun pixelsSha(bitmap: Bitmap): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val row = IntArray(bitmap.width)
            val packed = ByteArray(bitmap.width * 4)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                row.forEachIndexed { x, pixel ->
                    for (channel in 0..3) packed[x * 4 + channel] = (pixel ushr (24 - channel * 8)).toByte()
                }
                digest.update(packed)
            }
            row.fill(0)
            packed.fill(0)
            return digest.digest()
        }
    }
}
