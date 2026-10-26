package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
import li.joye.yakuyomi.engine.DetectorConfig
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
 * pageNumbers=2,3. No user-work text, URL, raw trace or exception message is emitted or saved.
 * Optional liveHitomiOcrPolarityDiagnostic=true adds neutral controls before fetching, then full-page RGB
 * inversion of owned opaque copies. Candidate confidence is not evidence of actual semantic correctness.
 * Optional liveHitomiOcrGroundTruthDiagnostic=true binds private salted expectations to source/quad and
 * retains bounded original ROI PNGs in an isolated cache directory for host extraction and cleanup.
 * Optional liveHitomiOcrLocalRedetectionDiagnostic=true compares local detection plus child OCR without
 * modifying or joining parent lines. Child matches do not prove coverage or a safe product recovery.
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
        val polarityDiagnostic = arguments.getString("liveHitomiOcrPolarityDiagnostic") == "true"
        val localRedetectionEnabled = arguments.getString("liveHitomiOcrLocalRedetectionDiagnostic") == "true"
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
        var groundTruth: OcrCandidateGroundTruth? = null
        var localKnownGeometryExact: Boolean? = null
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
            groundTruth = OcrCandidateGroundTruth.parse(context, arguments)
            check(groundTruth == null || polarityDiagnostic)
            check(!localRedetectionEnabled || groundTruth != null)
            idle(context)
            val nativeModels = Models(context).also { models = it }
            report.put("modelsBefore", nativeModels.manifest())
            if (polarityDiagnostic) {
                val neutral = JSONObject()
                report.put("polarityNeutralControl", neutral)
                neutralPolarity(context, nativeModels, neutral, nativeOwners)
                status()
            }
            if (localRedetectionEnabled) {
                val neutral = JSONObject().also { report.put("localRedetectionNeutralControl", it) }
                localKnownGeometryExact = neutralLocalRedetection(context, nativeModels, neutral, nativeOwners)
                report.put("localKnownGeometryExact", localKnownGeometryExact)
                status()
            }
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
                                groundTruth?.validatePage(descriptor.number, sha(bytes), bitmap.width, bitmap.height)
                                analyze(
                                    context,
                                    nativeModels,
                                    bitmap,
                                    row,
                                    nativeOwners,
                                    polarityDiagnostic,
                                    groundTruth,
                                    localRedetectionEnabled,
                                )
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
            groundTruth?.let { check(it.retainedImageCount == 2) }
            phase = 20
            if (localRedetectionEnabled) report.put("terminalQualityGate", "known-neutral-geometry-exact")
            // Keep the exact quality gate. Collect the alternatives before failing so a known baseline
            // OCR mismatch cannot hide evidence about whether local re-detection actually improves it.
            check(!localRedetectionEnabled || localKnownGeometryExact == true)
            report.put("passed", true)
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
            verify { groundTruth?.addSummary(report) }
            report.put("imageFilesByTest", groundTruth?.retainedImageCount ?: 0)
                .put("contentFilesByTest", groundTruth?.retainedImageCount ?: 0)
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
        polarityDiagnostic: Boolean,
        groundTruth: OcrCandidateGroundTruth?,
        localRedetectionEnabled: Boolean,
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
        if (polarityDiagnostic) {
            pagePolarity(context, models, page, on, onKept, row, owners, groundTruth)
            source.verify(page)
        }
        if (localRedetectionEnabled) {
            localRedetection(context, models, page, on, onKept, row, owners, checkNotNull(groundTruth))
            source.verify(page)
        }
        idle(context)
    }

    private suspend fun localRedetection(
        context: Context,
        models: Models,
        page: Bitmap,
        baseline: List<TextLine>,
        kept: Set<TextLine>,
        pageRow: JSONObject,
        owners: MutableList<Any>,
        groundTruth: OcrCandidateGroundTruth,
    ) {
        val parentIndex = baseline.indices.single { baseline[it].text.isBlank() && baseline[it] !in kept }
        val parent = baseline[parentIndex]
        val parentQuad = parent.quad.map { Pt(it.x, it.y) }
        val parentState = listOf(parent.direction, parent.text, parent.translatedText)
        val parentScore = parent.score.toRawBits()
        val bounds = paddedBounds(parent, page)
        val variants = JSONArray().also { pageRow.put("localRedetectionVariants", it) }
        val inverted = invertedOpaqueCopy(page)
        try {
            for ((name, input) in listOf("original" to page, "inverted" to inverted)) {
                val row = JSONObject().put("variant", name).put("parentLineIndex", parentIndex)
                    .put("parentMutation", false).put("childJoin", false).put("coverageClaimed", false)
                    .put("qualityPromoted", false).also { variants.put(it) }
                val children = redetectRegion(context, models, input, bounds, row, owners)
                row.put(
                    "childBboxRelations",
                    JSONArray(
                        children.map { child ->
                            JSONObject().put("parentIntersectionArea", bboxIntersection(child, parent))
                                .put(
                                    "neighborOverlapIndices",
                                    JSONArray(
                                        baseline.indices.filter {
                                            it != parentIndex && bboxIntersection(child, baseline[it]) > 0
                                        },
                                    ),
                                )
                        },
                    ),
                )
                groundTruth.recordChildCandidates(pageRow.getInt("pageOneBased"), parentIndex, parent, children, row)
                row.put("parentQuadUnchanged", parent.quad == parentQuad)
                check(parent.quad == parentQuad && parent.score.toRawBits() == parentScore)
                check(listOf(parent.direction, parent.text, parent.translatedText) == parentState && parent !in kept)
                row.put("parentStateAndKeptMembershipUnchanged", true)
            }
        } finally {
            inverted.recycle()
        }
    }

    private suspend fun neutralLocalRedetection(
        context: Context,
        models: Models,
        row: JSONObject,
        owners: MutableList<Any>,
    ): Boolean {
        val page = Bitmap.createBitmap(512, 200, Bitmap.Config.ARGB_8888)
        val expected = listOf("HELLO", "WORLD", "7", "NEXT")
        val geometry = mutableListOf<TextLine>()
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
            paint.color = Color.BLACK
            canvas.drawRoundRect(100f, 50f, 340f, 110f, 12f, 12f, paint)
            listOf(Triple(120f, 73f, 18f), Triple(120f, 97f, 18f), Triple(270f, 97f, 36f), Triple(110f, 136f, 18f))
                .forEachIndexed { index, (x, y, size) ->
                    paint.color = if (index == 3) Color.BLACK else Color.WHITE
                    paint.textSize = size
                    val ink = Rect().also { paint.getTextBounds(expected[index], 0, expected[index].length, it) }
                    canvas.drawText(expected[index], x, y, paint)
                    geometry.add(
                        TextLine(
                            listOf(
                                Pt(x + ink.left, y + ink.top),
                                Pt(x + ink.right, y + ink.top),
                                Pt(x + ink.right, y + ink.bottom),
                                Pt(x + ink.left, y + ink.bottom),
                            ),
                            1f,
                        ),
                    )
                }
            val source = SourceSnapshot(page)
            val known = JSONObject().also { row.put("knownGeometryControl", it) }
            val recognized = polarityRecognize(context, models, page, geometry, known, owners)
            val matches = recognized.indices.map { recognized[it].text == expected[it] }
            known.put("rawUtf8ExactMatches", JSONArray(matches))
                .put("syntheticActualTexts", JSONArray(recognized.map { it.text }))
            known.put("qualityPassed", matches.all { it })
            val parent = TextLine(listOf(Pt(100f, 50f), Pt(340f, 50f), Pt(340f, 110f), Pt(100f, 110f)), 1f)
            val bounds = paddedBounds(parent, page)
            row.put("parentQuad", JSONArray(parent.quad.map { JSONArray(listOf(it.x, it.y)) }))
                .put("neighborKnownQuad", JSONArray(geometry.last().quad.map { JSONArray(listOf(it.x, it.y)) }))
                .put("beforeAnyLiveFetch", true).put("syntheticImagesSaved", false)
            val variants = JSONArray().also { row.put("variants", it) }
            val inverted = invertedOpaqueCopy(page)
            try {
                for ((name, input) in listOf("original" to page, "inverted" to inverted)) {
                    val variant = JSONObject().put("variant", name).also { variants.put(it) }
                    val children = redetectRegion(context, models, input, bounds, variant, owners)
                    variant.put(
                        "rawUtf8ExpectedMatches",
                        JSONArray(
                            children.map { child ->
                                JSONArray(expected.map { child.text == it })
                            },
                        ),
                    )
                    variant.put("parentMutation", false).put("childJoin", false).put("coverageClaimed", false)
                }
            } finally {
                inverted.recycle()
            }
            source.verify(page)
            return matches.all { it }
        } finally {
            page.recycle()
        }
    }

    private fun paddedBounds(parent: TextLine, page: Bitmap): IntArray = intArrayOf(
        (floor(parent.quad.minOf { it.x }).toInt() - 8).coerceAtLeast(0),
        (floor(parent.quad.minOf { it.y }).toInt() - 8).coerceAtLeast(0),
        (ceil(parent.quad.maxOf { it.x }).toInt() + 8).coerceAtMost(page.width),
        (ceil(parent.quad.maxOf { it.y }).toInt() + 8).coerceAtMost(page.height),
    )

    private fun bboxIntersection(first: TextLine, second: TextLine): Double {
        val width = minOf(first.quad.maxOf { it.x }, second.quad.maxOf { it.x }) -
            maxOf(first.quad.minOf { it.x }, second.quad.minOf { it.x })
        val height = minOf(first.quad.maxOf { it.y }, second.quad.maxOf { it.y }) -
            maxOf(first.quad.minOf { it.y }, second.quad.minOf { it.y })
        return width.coerceAtLeast(0f).toDouble() * height.coerceAtLeast(0f).toDouble()
    }

    private suspend fun redetectRegion(
        context: Context,
        models: Models,
        page: Bitmap,
        bounds: IntArray,
        row: JSONObject,
        owners: MutableList<Any>,
    ): List<TextLine> {
        val (left, top, right, bottom) = bounds
        val width = right - left
        val height = bottom - top
        check(width in 1..512 && height in 1..160 && width.toLong() * height <= 81_920)
        val source = SourceSnapshot(page)
        val crop = Bitmap.createBitmap(page, left, top, width, height)
        try {
            val cropSource = SourceSnapshot(crop)
            val cfg = DetectorConfig()
            check(cfg.dbnetInputSize == 1024)
            row.put("cropBounds", JSONArray(bounds.toList())).put("sourcePaddingPixels", 8)
                .put("cropWidth", width).put("cropHeight", height).put("dbnetInputSize", cfg.dbnetInputSize)
                .put("calculatedScale", cfg.dbnetInputSize.toDouble() / maxOf(width, height))
                .put("nativeInputObserved", false).put("dbBinThreshold", cfg.dbBinThreshold.toDouble())
                .put("dbBoxThreshold", cfg.dbBoxThreshold.toDouble()).put("dbUnclipRatio", cfg.dbUnclipRatio.toDouble())
            idle(context)
            val started = SystemClock.elapsedRealtimeNanos()
            val children = Detector(models.file("dbnet_detect.ncnn.param").path, cfg).also { owners.add(it) }
                .use { detector ->
                    val detection = detector.detect(crop)
                    try {
                        row.put("detectorCalls", 1).put("detectedChildCount", detection.lines.size)
                        check(detection.lines.size <= 12)
                        row.put(
                            "localQuads",
                            JSONArray(
                                detection.lines.map { line ->
                                    JSONArray(line.quad.map { JSONArray(listOf(it.x, it.y)) })
                                },
                            ),
                        )
                        detection.lines.map { line ->
                            check(line.quad.all { it.x in 0f..width.toFloat() && it.y in 0f..height.toFloat() })
                            TextLine(line.quad.map { Pt(it.x + left, it.y + top) }, line.score)
                        }
                    } finally {
                        detection.textMask.recycle()
                    }
                }
            row.put("detectAndModelLifecycleMs", elapsed(started))
            cropSource.verify(crop)
            val output = if (children.isEmpty()) {
                row.put("recognizeCalls", 0).put("traceExecuted", false).put("lines", JSONArray())
                emptyList()
            } else {
                row.put("traceExecuted", true)
                polarityRecognize(context, models, page, children, row, owners)
            }
            source.verify(page)
            row.put("sourcePixelsMetadataUnchanged", true).put("localMaskRecycled", true)
                .put("coordinateTransform", "crop-origin-only")
            return output
        } finally {
            if (crop !== page) crop.recycle()
        }
    }

    private suspend fun neutralPolarity(
        context: Context,
        models: Models,
        row: JSONObject,
        owners: MutableList<Any>,
    ) {
        val expected = "こんにちは。"
        val drawing = OcrDiagnosticSample(expected).draw(60f)
        val source = SourceSnapshot(drawing.page)
        val geometry = listOf(TextLine(drawing.quad.map { Pt(it.x, it.y) }, 1f))
        val variants = JSONArray()
        row.put("variants", variants).put("expectedTextLength", expected.length)
            .put("beforeAnyLiveFetch", true).put("sourceImagesOrTextExported", false)
        try {
            val baseline = JSONObject().put("variant", "black-on-white").also { variants.put(it) }
            val baselineLine = polarityRecognize(context, models, drawing.page, geometry, baseline, owners).single()
            baseline.put("groundTruthExact", baselineLine.text == expected)
            check(baselineLine.text == expected && baselineLine.direction == "h")
            val inverted = invertedOpaqueCopy(drawing.page)
            try {
                val negative = JSONObject().put("variant", "white-on-black").also { variants.put(it) }
                val negativeLine = polarityRecognize(context, models, inverted, geometry, negative, owners).single()
                negative.put("groundTruthExact", negativeLine.text == expected)
                val restored = invertedOpaqueCopy(inverted)
                try {
                    source.verify(restored)
                    val positive = JSONObject().put("variant", "restored-black-on-white").also { variants.put(it) }
                    val positiveLine = polarityRecognize(context, models, restored, geometry, positive, owners).single()
                    positive.put("groundTruthExact", positiveLine.text == expected)
                        .put("baselineTextExact", positiveLine.text == baselineLine.text)
                    check(positiveLine.text == expected && positiveLine.direction == baselineLine.direction)
                    check(
                        baseline.getJSONObject("selectedParam").toString() ==
                            negative.getJSONObject("selectedParam").toString(),
                    )
                    check(
                        baseline.getJSONObject("selectedParam").toString() ==
                            positive.getJSONObject("selectedParam").toString(),
                    )
                    source.verify(restored)
                } finally {
                    restored.recycle()
                }
            } finally {
                inverted.recycle()
            }
            check(geometry.single().quad == drawing.quad && geometry.single().text.isEmpty())
            row.put("exactRgbInversionAndRestoration", true).put("sourcePixelsMetadataAndGeometryUnchanged", true)
                .put("baselineAndRestoredGroundTruthExact", true)
        } finally {
            try {
                source.verify(drawing.page)
            } finally {
                drawing.page.recycle()
            }
        }
    }

    private suspend fun pagePolarity(
        context: Context,
        models: Models,
        page: Bitmap,
        baseline: List<TextLine>,
        kept: Set<TextLine>,
        pageRow: JSONObject,
        owners: MutableList<Any>,
        groundTruth: OcrCandidateGroundTruth?,
    ) {
        val baselineRows = pageRow.getJSONArray("lines")
        val pending = baseline.indices.filter { baseline[it].text.isBlank() && baseline[it] !in kept }
        // The darkest recognized horizontal line is a deterministic control, not a polarity heuristic.
        val control = checkNotNull(
            baseline.indices.filter {
                baseline[it].text.isNotBlank() && baseline[it].direction == "h" && baseline[it] !in kept
            }.maxByOrNull {
                val evidence = baselineRows.getJSONObject(it)
                evidence.getLong("roiRawDarkPixels").toDouble() / evidence.getLong("roiPixels")
            },
        )
        val selected = (pending + control).distinct().sorted()
        val row = JSONObject().put("pendingLines", pending.size).put("recognizedControlLineIndex", control)
            .put("fullPageRgbInversion", true).put("semanticCorrectnessClaimed", false)
            .put("automaticPolarityHeuristicClaimed", false)
        pageRow.put("polarityCandidate", row)
        // Match the selected-line batch as well as its geometry; full-page OCR has different fan-out.
        val selectedOriginal = JSONObject().also { row.put("selectedOriginal", it) }
        val selectedBaseline = polarityRecognize(
            context,
            models,
            page,
            selected.map { baseline[it] },
            selectedOriginal,
            owners,
        )
        check(
            selected.indices.all { index ->
                selectedBaseline[index].text == baseline[selected[index]].text &&
                    selectedBaseline[index].direction == baseline[selected[index]].direction
            },
        )
        row.put("selectedOriginalMatchesFullPage", true).put("sameSelectedBatchForBothPolarities", true)
        val inverted = invertedOpaqueCopy(page)
        try {
            val candidate = polarityRecognize(context, models, inverted, selected.map { baseline[it] }, row, owners)
            check(pageRow.getJSONObject("onSelectedParam").toString() == row.getJSONObject("selectedParam").toString())
            check(
                selectedOriginal.getJSONObject("selectedParam").toString() ==
                    row.getJSONObject("selectedParam").toString(),
            )
            selected.forEachIndexed { index, baselineIndex ->
                val original = baseline[baselineIndex]
                check(candidate[index].quad == original.quad)
                val originalCrop = selectedOriginal.getJSONArray("lines").getJSONObject(index)
                    .getJSONArray("attempts").getJSONObject(0).getJSONObject("crop")
                val invertedCrop = row.getJSONArray("lines").getJSONObject(index)
                    .getJSONArray("attempts").getJSONObject(0).getJSONObject("crop")
                check(originalCrop.toString() == invertedCrop.toString())
                row.getJSONArray("lines").getJSONObject(index).put("baselineLineIndex", baselineIndex)
                    .put("baselineBlank", original.text.isBlank()).put("recognizedControl", baselineIndex == control)
                    .put("baselineTextExact", candidate[index].text == original.text)
                    .put("baselineDirectionExact", candidate[index].direction == original.direction)
            }
            row.put("sourceFullyOpaque", true).put("exactRgbInversion", true)
                .put("sourcePixelsMetadataAndGeometryUnchanged", true)
                .put("firstAttemptExpandedCropGeometryExact", true)
            groundTruth?.record(pageRow.getInt("pageOneBased"), page, baseline, candidate, selected, row)
        } finally {
            inverted.recycle()
        }
    }

    private suspend fun polarityRecognize(
        context: Context,
        models: Models,
        page: Bitmap,
        geometry: List<TextLine>,
        row: JSONObject,
        owners: MutableList<Any>,
    ): List<TextLine> {
        check(geometry.size in 1..MAX_LINES)
        val source = SourceSnapshot(page)
        val config = OcrConfig().also { check(it.minProb == 0.5f && it.ignoreBubble == 0) }
        val lines = geometry.map { cloneLine(it, page) }
        val trace = OcrSelectionDiagnostics(maxLines = MAX_LINES, maxEvents = 32768)
        val kept = try {
            Ocr(models.file("ocr_48px_ctc.ncnn.param").path, models.dictionary, config, diagnostics = trace)
                .also { owners.add(it) }.use { ocr ->
                    idle(context)
                    row.put("selectedParam", models.selected(ocr)).put("minProbability", config.minProb.toDouble())
                    val started = SystemClock.elapsedRealtimeNanos()
                    ocr.recognize(page, lines).also {
                        row.put("recognizeCalls", 1).put("recognizeMs", elapsed(started))
                    }
                }
        } finally {
            source.verify(page)
        }
        val evidence = JSONArray()
        lines.forEachIndexed { index, line ->
            check(line.quad == geometry[index].quad && line.score == geometry[index].score)
            evidence.put(lineEvidence(index, line, line in kept, page))
        }
        row.put("lines", evidence).put("width", page.width).put("height", page.height).put("density", page.density)
            .put("pixelsMetadataAndGeometryUnchanged", true)
        validateTrace(trace.snapshot(), lines, kept, config, evidence, row)
        idle(context)
        return lines
    }

    private fun invertedOpaqueCopy(source: Bitmap): Bitmap {
        check(source.config == Bitmap.Config.ARGB_8888)
        val snapshot = SourceSnapshot(source)
        val copy = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
        val before = IntArray(source.width)
        val after = IntArray(source.width)
        try {
            copy.density = source.density
            snapshot.verify(copy)
            for (y in 0 until source.height) {
                source.getPixels(before, 0, before.size, 0, y, before.size, 1)
                // Deliberately reject nonopaque pages; unpremultiplication must not confound this experiment.
                check(before.all { it ushr 24 == 255 })
                before.forEachIndexed { index, pixel -> after[index] = pixel xor 0x00FFFFFF }
                copy.setPixels(after, 0, after.size, 0, y, after.size, 1)
                copy.getPixels(after, 0, after.size, 0, y, after.size, 1)
                check(before.indices.all { after[it] == (before[it] xor 0x00FFFFFF) })
            }
            check(copy.width == source.width && copy.height == source.height && copy.density == source.density)
            check(copy.hasAlpha() == source.hasAlpha() && copy.isPremultiplied == source.isPremultiplied)
            snapshot.verify(source)
            return copy
        } catch (error: Throwable) {
            copy.recycle()
            throw error
        } finally {
            before.fill(0)
            after.fill(0)
        }
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
                    .put(
                        "crop",
                        JSONObject().put("expandedSortedQuadSha256", crop.quadSha256)
                            .put("stripWidth", crop.stripWidth).put("stripHeight", crop.stripHeight)
                            .put("direction", crop.direction).put("stripPad", crop.stripPad)
                            .put("bicubic", crop.bicubic),
                    )
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
