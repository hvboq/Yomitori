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
import android.util.AtomicFile
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
import li.joye.yakuyomi.engine.HorizontalOcrCropPolicy
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.OcrSelectionDiagnostics
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TranslationContext
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

/** Research diagnostic. Expected strings never select geometry or an OCR branch. */
@OptIn(InternalEngineApi::class)
@RunWith(AndroidJUnit4::class)
class OcrFontGeometryDiagnosticTest {
    @Test
    fun compareSansAndSerifWithSharedGeometryWithoutPromotion() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "ocr-font-geometry") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) { withTimeout(15 * 60 * 1000L) { collect() } }
            }
    }

    private suspend fun collect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("ocrFontGeometry") == "true") { "Explicit diagnostic opt-in required" }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit")
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val output = File(external, "ocr-font-geometry-${UUID.randomUUID()}")
        check(output.mkdir() && output.canonicalFile == output.absoluteFile && output.parentFile == external)
        val reportFile = File(output, "result.json")
        val baselineRows = JSONArray()
        val pairRows = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("passed", false).put("diagnosticOnly", true)
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("process64Bit", true)
            .put("collectionValidationPassed", false).put("baselineHistoricalGatePassed", false)
            .put("commonGeometryGatePassed", false).put("promotionEligible", false)
            .put("qualityImprovementClaimed", false).put("defaultChanged", false)
            .put("baselineExpectedSamples", 120).put("newExpectedPairs", 5).put("newExpectedConditions", 10)
            .put("expectedPublicCalls", 130).put("newConditionAccuracyDeterminesPass", false)
            .put("scoresCombinedAcrossOldAndNewInputs", false).put("settingsWritten", false)
            .put("apiKeyReadOrWritten", false).put("networkCallsRequested", 0).put("nativeOtherThanOcr", false)
            .put("newImagesWritten", 0).put("modelFilesCopiedOrDownloaded", false)
            .put("actualFontFileOrGlyphIdentityMeasured", false).put("modelOnlyCauseEstablished", false)
            .put("fontEvidenceScope", "Requested family/style, observed ARGB, bounds, shared quad and same-call trace")
            .put("timingScope", "Sequential diagnostic duration including tracing; not a benchmark")
            .put("baseline", baselineRows).put("pairs", pairRows)
        var failure: Throwable? = null
        var input: OcrRightContextInputs? = null
        var reference: HistoricalReference? = null
        var nativeOcr: Ocr? = null
        var collector: OcrSelectionDiagnostics? = null
        var beforeModels: JSONArray? = null
        var beforeSettings: JSONObject? = null
        var shared: TranslationEngineService? = null
        val pairs = ArrayList<FontPair>()
        var baselineCalls = 0
        var newCalls = 0
        val threadId = Thread.currentThread().id
        val started = SystemClock.elapsedRealtimeNanos()
        fun checkpoint() {
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            check(bytes.size.toLong() in 1..MAX_REPORT_BYTES)
            val atomic = AtomicFile(reportFile)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
        fun status(phase: String) {
            val terminal = phase == "COMPLETED" || phase == "FAILED"
            val message = JSONObject().put("phase", phase).put("report", reportFile.absolutePath)
                .put("completedSamples", baselineCalls).put("completedBaselineSamples", baselineCalls)
                .put("newConditionsCompleted", newCalls).put("publicCallsCompleted", baselineCalls + newCalls)
                .put("passed", if (terminal) failure == null else JSONObject.NULL)
                .put("terminalReportSealed", terminal)
            if (terminal) {
                check(output.list()?.toSet() == setOf("result.json"))
                check(reportFile.canonicalFile == reportFile.absoluteFile && reportFile.parentFile == output)
                check(reportFile.isFile && reportFile.length() in 1..MAX_REPORT_BYTES)
                message.put("ownedDirectory", output.name).put("canonicalBoundaryVerified", true)
                    .put(
                        "ownedFiles",
                        JSONArray().put(
                            JSONObject().put("name", "result.json")
                                .put("bytes", reportFile.length()).put("sha256", diagnosticSha(reportFile)),
                        ),
                    )
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "OCR_FONT_GEOMETRY=$message") })
        }
        try {
            checkpoint()
            status("STARTED")
            val liveInput = OcrRightContextInputs(
                context,
                checkNotNull(arguments.getString("ocrRightContextInput")),
                instrumentation.context.assets.open("ocr-right-context/input-manifest.json").use { it.readBytes() },
            )
            input = liveInput
            check(bytesSha(liveInput.manifestBytes) == INPUT_MANIFEST_SHA)
            val historical = HistoricalReference(
                external,
                checkNotNull(arguments.getString("ocrFontGeometryReference")),
            )
            reference = historical
            val cases = liveInput.fixedCases() + rightContextGeneratedCases()
            check(cases.size == 120 && cases.map { it.id }.toSet() == historical.rows.keys)
            check(cases.all { historical.rows.getValue(it.id).getString("expected") == it.expected })
            val service = Injekt.get<TranslationEngineService>()
            shared = service
            withTimeout(10_000) { Injekt.get<TranslationManager>().ensureRestored() }
            requireIdle(service)
            beforeSettings = settingsFingerprint(context)
            val models = modelDirectory(context)
            val modelPins = fingerprintModels(models)
            beforeModels = modelPins
            check(modelPins.toString() == historical.report.getJSONArray("modelsAfter").toString())
            report.put("inputManifestSha256", bytesSha(liveInput.manifestBytes))
                .put("pinnedInputFiles", liveInput.sourceManifest).put("historicalReference", historical.description())
                .put("modelsBefore", modelPins).put("settingsBefore", beforeSettings)
                .put("sameThreadSerialPublicCalls", true).put("javaThreadId", threadId)
            val byId = cases.associateBy { it.id }
            for (id in CLEAN_IDS) pairs += preparePair(byId.getValue(id), historical.rows.getValue(id))
            check(pairs.size == 5 && pairs.sumOf { it.renders.size } == 10)
            report.put("allCommonQuadsFixedBeforeNativeOcrConstruction", true)
                .put("renderOperationsIncludingExactRepeat", 20)
            for (pair in pairs) pairRows.put(pair.metadata)
            checkpoint()
            val config = OcrConfig()
            check(config.horizontalContextPolicy == HorizontalOcrContextPolicy.GUARDED_32)
            check(config.horizontalCropPolicy == HorizontalOcrCropPolicy.UNIFORM)
            check(config.stripPad == 4 && config.textHeight == 48 && config.useBicubic && config.ocrUnsharp)
            check(config.ignoreBubble == 0 && config.minProb == 0.5f && config.minTextLength == 0)
            check(config.concurrent && config.concurrency == 8)
            check(config.ncnnMixed && config.ncnnFp16Storage && config.ncnnFp16Arith)
            val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
            check(bytesSha(dictionaryBytes) == DICTIONARY_SHA)
            val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
            check(dictionary.size == 19_264)
            val trace = OcrSelectionDiagnostics(maxLines = 160, maxEvents = 4096)
            collector = trace
            report.put("requestedConfig", config.toString()).put("dictionarySha256", DICTIONARY_SHA)
                .put("observerMaxLines", 160).put("observerMaxEvents", 4096)
            Ocr(File(models, "ocr_48px_ctc.ncnn.param").path, dictionary, config, diagnostics = trace).use { ocr ->
                nativeOcr = ocr
                report.put("privateOcrInstancesCreated", 1)
                val probe = OcrDiagnosticProbe(ocr, dictionary)
                val metadata = probe.metadata()
                check(metadata.getInt("nativeThreads") == 1 && !metadata.getBoolean("serialize"))
                check(metadata.getString("backend") == "NCNN-mixed")
                val historicalBackend = historical.report.getJSONObject("candidateBackend")
                for (key in listOf(
                    "backend",
                    "nativeThreads",
                    "serialize",
                    "peElements",
                    "peFloat32LittleEndianSha256",
                )) {
                    check(metadata.get(key) == historicalBackend.get(key))
                }
                val selected = probe.selectedParam(File(models, "ocr_48px_ctc.ncnn.param").path)
                check(selected.second && File(selected.first).name == historical.report.getString("selectedParam"))
                check(ocr.intentionalFilterContext.semanticFingerprint == GUARDED32_CONTEXT_SHA)
                check(
                    ocr.intentionalFilterContext == TranslationContext.forOcrPolicy(
                        0,
                        HorizontalOcrContextPolicy.GUARDED_32,
                    ),
                )
                report.put("backend", metadata).put("selectedParam", File(selected.first).name)
                    .put("explicitWarmUpCalls", 0).put("directRawInferCalls", 0)
                for (case in cases) {
                    currentCoroutineContext().ensureActive()
                    requireIdle(service)
                    check(Thread.currentThread().id == threadId)
                    val drawing = case.open()
                    try {
                        val prior = historical.rows.getValue(case.id)
                        verifySource(case, drawing, prior)
                        val pixels = diagnosticPixels(drawing.page)
                        val quad = drawing.quad.map { Pt(it.x, it.y) }
                        val result = observe(ocr, trace, drawing, case.expected, baselineCalls + 1, prior) {
                            baselineCalls++
                        }
                        val expected = prior.getJSONObject("candidatePublic")
                        check(result.getString("text") == expected.getString("text"))
                        check(result.getString("direction") == expected.getString("direction"))
                        check(result.getBoolean("intentionallyKept") == expected.getBoolean("intentionallyKept"))
                        check(pixels.contentEquals(diagnosticPixels(drawing.page)) && drawing.quad == quad)
                        baselineRows.put(
                            JSONObject().put("id", case.id).put("expected", case.expected)
                                .put("sourceWidth", drawing.page.width).put("sourceHeight", drawing.page.height)
                                .put("sourceArgbSha256", pixelsSha(pixels)).put("quad", quadJson(quad))
                                .put("historicalReproduced", true).put("sourceAndQuadUnchanged", true)
                                .put("public", result),
                        )
                        checkpoint()
                    } finally {
                        drawing.page.recycle()
                    }
                }
                val correct = (0 until baselineRows.length()).count {
                    baselineRows.getJSONObject(it).getJSONObject("public").getBoolean("matchesGroundTruth")
                }
                check(baselineCalls == 120 && baselineRows.length() == 120 && correct == 107)
                report.put("baselineHistoricalGatePassed", true)
                    .put("baseline120ActualPrimaryChwHistoricalPinsMatched", true)
                    .put(
                        "baselineSummary",
                        JSONObject().put("samples", 120).put("correct", correct)
                            .put("historicalTextDirectionKeepParity", 120).put("scoreScope", "Original120 only"),
                    )
                status("BASELINE_GATE_COMPLETED")
                for (pair in pairs) {
                    val results = pair.metadata.getJSONArray("results")
                    for (render in pair.renders) {
                        currentCoroutineContext().ensureActive()
                        requireIdle(service)
                        check(Thread.currentThread().id == threadId)
                        val before = diagnosticPixels(render.bitmap)
                        check(pixelsSha(before) == render.metadata.getString("sourceArgbSha256"))
                        val quad = pair.quad.map { Pt(it.x, it.y) }
                        check(quadJson(quad).toString() == pair.metadata.getJSONArray("commonQuad").toString())
                        val result = observe(
                            ocr,
                            trace,
                            OcrRightContextDrawing(render.bitmap, quad),
                            pair.text,
                            baselineCalls + newCalls + 1,
                            null,
                        ) { newCalls++ }
                        check(before.contentEquals(diagnosticPixels(render.bitmap)) && quad == pair.quad)
                        results.put(JSONObject().put("requestedFamily", render.family).put("public", result))
                        checkpoint()
                    }
                    requireCommonActualGeometry(results)
                    pair.metadata.put("sameFirstCropAndPrimaryShape", true)
                    pair.metadata.put("sourceAndQuadUnchanged", true)
                }
                check(newCalls == 10 && baselineCalls == 120)
                requireCompleteTrace(trace.snapshot(), 130)
                check(probe.explicitNativeCalls == 0 && probe.privateInferParityCalls == 0)
                report.put("observerSnapshot", snapshotJson(trace.snapshot()))
                    .put("newConditionSummary", newSummary(pairRows))
                    .put("commonGeometryGatePassed", true).put("collectionValidationPassed", true)
            }
        } catch (error: Throwable) {
            failure = error
            report.put("failureClass", error.javaClass.name)
            try {
                collector?.snapshot()?.let { snapshot ->
                    val assigned = (0 until baselineRows.length()).map {
                        baselineRows.getJSONObject(it).getJSONObject("public").getLong("publicCallOrdinal")
                    }.toMutableSet()
                    for (index in 0 until pairRows.length()) {
                        val rows = pairRows.getJSONObject(index).getJSONArray("results")
                        for (item in 0 until rows.length()) {
                            assigned += rows.getJSONObject(item).getJSONObject("public").getLong("publicCallOrdinal")
                        }
                    }
                    report.put("snapshotAtFailure", snapshotJson(snapshot))
                        .put(
                            "unassignedEventsAtFailure",
                            JSONArray(snapshot.events.filter { it.callOrdinal !in assigned }.map(::eventJson)),
                        )
                }
            } catch (captureError: Throwable) {
                error.addSuppressed(captureError)
            }
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
            report.put("privateOcrInstancesCreated", if (nativeOcr == null) 0 else 1)
            if (nativeOcr != null) {
                cleanup("privateOcrClosed") {
                    val ocr = checkNotNull(nativeOcr)
                    val field = Ocr::class.java.getDeclaredField("ncnnHandle").apply { isAccessible = true }
                    check(field.getLong(ocr) == 0L)
                }
            } else {
                report.put("privateOcrClosed", JSONObject.NULL)
            }
            cleanup("ownedBitmapsRecycled") { pairs.flatMap { it.renders }.forEach { it.bitmap.recycle() } }
            if (input != null) {
                cleanup("inputsUnchanged") { checkNotNull(input).verifyUnchanged() }
            } else {
                report.put("inputsUnchanged", JSONObject.NULL)
            }
            if (reference != null) {
                cleanup("referenceUnchanged") { checkNotNull(reference).verifyUnchanged() }
            } else {
                report.put("referenceUnchanged", JSONObject.NULL)
            }
            if (beforeModels != null) {
                cleanup("modelsUnchanged") {
                    val before = checkNotNull(beforeModels)
                    val after = fingerprintModels(modelDirectory(context))
                    report.put("modelsAfter", after)
                    check(before.toString() == after.toString())
                }
            } else {
                report.put("modelsUnchanged", JSONObject.NULL)
            }
            if (beforeSettings != null) {
                cleanup("settingsValuePresenceAndDiskUnchanged") {
                    val before = checkNotNull(beforeSettings)
                    val after = settingsFingerprint(context)
                    report.put("settingsAfter", after)
                    check(before.toString() == after.toString())
                }
            } else {
                report.put("settingsValuePresenceAndDiskUnchanged", JSONObject.NULL)
            }
            if (shared != null) {
                cleanup("sharedEngineStillAbsentAndQueuesIdle") { requireIdle(checkNotNull(shared)) }
            } else {
                report.put("sharedEngineStillAbsentAndQueuesIdle", JSONObject.NULL)
            }
            nativeOcr = null
            collector = null
            report.put("collectorOwnedReferenceReleased", true).put("baselineCallsCompleted", baselineCalls)
                .put("newConditionsCompleted", newCalls).put("publicCallsCompleted", baselineCalls + newCalls)
                .put("passed", failure == null).put("finalReportWritten", true).put("wallMs", elapsed(started))
            cleanup("finalReportWritten") { checkpoint() }
            if (failure != null) {
                report.put("passed", false).put("collectionValidationPassed", false)
                cleanup("failureReportWritten") { checkpoint() }
            }
            try {
                status(if (failure == null) "COMPLETED" else "FAILED")
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
        check(report.getBoolean("baselineHistoricalGatePassed") && report.getBoolean("commonGeometryGatePassed"))
    }

    private suspend fun observe(
        ocr: Ocr,
        collector: OcrSelectionDiagnostics,
        drawing: OcrRightContextDrawing,
        expected: String,
        ordinal: Int,
        historical: JSONObject?,
        completed: () -> Unit,
    ): JSONObject {
        val line = TextLine(drawing.quad.map { Pt(it.x, it.y) }, 1f)
        val started = SystemClock.elapsedRealtimeNanos()
        val kept = ocr.recognize(drawing.page, listOf(line))
        completed()
        check(line.quad == drawing.quad)
        val snapshot = collector.snapshot()
        requireCompleteTrace(snapshot, ordinal)
        val events = snapshot.events.filter { it.callOrdinal == ordinal.toLong() }
        verifyCall(events, ordinal.toLong(), line, line in kept, historical)
        return publicJson(line, kept, expected, started).put("publicCallOrdinal", ordinal)
            .put("events", JSONArray(events.map(::eventJson))).put("selection", selectionSummary(events))
            .put("sameCallFinalPublicResultVerified", true)
    }

    private data class FontRender(val family: String, val bitmap: Bitmap, val ink: Rect, val metadata: JSONObject)
    private data class FontPair(
        val text: String,
        val quad: List<Pt>,
        val renders: List<FontRender>,
        val metadata: JSONObject,
    )

    private fun preparePair(case: OcrRightContextCase, prior: JSONObject): FontPair {
        check(case.id in CLEAN_IDS && case.metadata.getString("requestedFontFamily") == "serif")
        check(case.metadata.getString("decoration") == "none" && case.metadata.getInt("rightClipPixels") == 0)
        check(prior.getJSONObject("candidatePublic").getString("text") != case.expected)
        val size = case.metadata.getDouble("fontSize").toFloat()
        val bold = case.metadata.getBoolean("requestedBold")
        val renders = ArrayList<FontRender>()
        try {
            for (family in listOf("sans-serif", "serif")) {
                val drawn = drawFont(case.expected, family, size, bold)
                renders += drawn
                val repeat = drawFont(case.expected, family, size, bold)
                try {
                    check(
                        drawn.metadata.getString("sourceArgbSha256") ==
                            repeat.metadata.getString("sourceArgbSha256"),
                    )
                    check(drawn.ink == repeat.ink)
                    drawn.metadata.put("exactRepeatSourceAndInkBoundsVerified", true)
                } finally {
                    repeat.bitmap.recycle()
                }
            }
            val union = Rect(renders.first().ink).apply { union(renders.last().ink) }
            check(union.left >= 8 && union.top >= 8 && union.right <= 992 && union.bottom <= 212)
            check(union.height() > 0 && union.width() >= 2 * union.height())
            check(renders.all { union.contains(it.ink) })
            val common = listOf(
                Pt(union.left.toFloat(), union.top.toFloat()),
                Pt(union.right.toFloat(), union.top.toFloat()),
                Pt(union.right.toFloat(), union.bottom.toFloat()),
                Pt(union.left.toFloat(), union.bottom.toFloat()),
            )
            val metadata = JSONObject().put("seedId", case.id).put("renderText", case.expected)
                .put("textOrigin", "Previously frozen neutral case; text defines input and is scored only")
                .put("sizePx", size.toDouble()).put("requestedBold", bold).put("canvasWidth", 1000)
                .put("canvasHeight", 220).put("drawX", 20).put("baselineY", 130)
                .put("quadDefinition", "Common actual-ink union; exclusive right/bottom; no extra source pad")
                .put("commonQuad", quadJson(common)).put("unionInkBounds", rectJson(union))
                .put("bothInkBoundsInsideCommonQuad", true).put("geometryFixedBeforeAnyNewOcrCall", true)
                .put("sources", JSONArray(renders.map { it.metadata })).put("results", JSONArray())
            return FontPair(case.expected, common, renders, metadata)
        } catch (error: Throwable) {
            renders.forEach { it.bitmap.recycle() }
            throw error
        }
    }

    private fun drawFont(text: String, family: String, size: Float, bold: Boolean): FontRender {
        val bitmap = Bitmap.createBitmap(1000, 220, Bitmap.Config.ARGB_8888)
        try {
            val face = Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = size
                typeface = face
            }
            val support = JSONArray()
            for (point in text.codePoints().distinct().toArray()) {
                val supported = Character.isWhitespace(point) || paint.hasGlyph(String(Character.toChars(point)))
                support.put(JSONObject().put("codePoint", point).put("supported", supported))
                check(supported)
            }
            val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(text, 20f, 130f, paint)
            }
            val pixels = diagnosticPixels(bitmap)
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            pixels.forEachIndexed { index, pixel ->
                check((pixel ushr 24) == 255)
                if (pixel != Color.WHITE) {
                    val x = index % bitmap.width
                    val y = index / bitmap.width
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
            check(right >= left && bottom >= top)
            val ink = Rect(left, top, right + 1, bottom + 1)
            val metadata = JSONObject().put("requestedFamily", family).put("requestedBold", bold)
                .put("requestedSizePx", size.toDouble()).put("actualTypefaceStyle", face.style)
                .put("actualTypefaceBold", face.isBold).put("sourceArgbSha256", pixelsSha(pixels))
                .put("paintBoundsRelativeToDrawOrigin", rectJson(bounds)).put("actualInkBounds", rectJson(ink))
                .put("fontMetricsTop", paint.fontMetrics.top.toDouble())
                .put("fontMetricsBottom", paint.fontMetrics.bottom.toDouble()).put("codepointSupport", support)
                .put("actualFontFileOrGlyphIdKnown", false)
            return FontRender(family, bitmap, ink, metadata)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun requireCommonActualGeometry(results: JSONArray) {
        check(results.length() == 2)
        fun event(index: Int, type: String): JSONObject {
            val events = results.getJSONObject(index).getJSONObject("public").getJSONArray("events")
            return (0 until events.length()).map { events.getJSONObject(it) }.single {
                it.getString("type") == type && it.getInt("attemptIndex") == 0 &&
                    (type != "INFER" || it.optString("role") == "PRIMARY")
            }
        }
        val firstCrop = event(0, "CROP")
        val secondCrop = event(1, "CROP")
        for (key in listOf("quadSha256", "stripWidth", "stripHeight", "stripPad", "bicubic", "direction")) {
            check(firstCrop.get(key) == secondCrop.get(key))
        }
        check(firstCrop.getInt("stripPad") == 4 && firstCrop.getInt("stripHeight") == 48)
        check(firstCrop.getString("direction") == "h")
        val first = event(0, "INFER")
        val second = event(1, "INFER")
        check(first.getInt("inputWidth") == second.getInt("inputWidth"))
        check(first.getInt("inputHeight") == 48 && second.getInt("inputHeight") == 48)
        check(first.getInt("actualMargin") == 32 && second.getInt("actualMargin") == 32)
        check(first.getInt("inputWidth") == firstCrop.getInt("stripWidth") + 32)
        // CHW contents, later attempts, selected context/body and strings may differ. They are outcomes.
    }

    private fun newSummary(pairs: JSONArray): JSONObject {
        val family = JSONObject()
        for (name in listOf("sans-serif", "serif")) {
            val results = (0 until pairs.length()).map { pairs.getJSONObject(it).getJSONArray("results") }
                .flatMap { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
                .filter { it.getString("requestedFamily") == name }
            check(results.size == 5)
            family.put(
                name,
                JSONObject().put("conditions", 5).put(
                    "correct",
                    results.count { it.getJSONObject("public").getBoolean("matchesGroundTruth") },
                ),
            )
        }
        return JSONObject().put("pairs", 5).put("conditions", 10).put("byRequestedFamily", family)
            .put("scoreScope", "New10 only; never summed with baseline120")
            .put("newAccuracyDeterminesCollectionPass", false)
    }

    private fun settingsFingerprint(context: Context): JSONObject {
        val live = Injekt.get<TranslationPreferences>().liveTranslate
        val directory = File(context.applicationInfo.dataDir, "shared_prefs").canonicalFile
        check(directory.isDirectory)
        val files = checkNotNull(directory.listFiles()).sortedBy { it.name }
        check(files.size <= 128 && files.sumOf { it.length() } <= 4_194_304L)
        val rows = JSONArray(
            files.map { file ->
                check(file.isFile && file.canonicalFile == file.absoluteFile && file.parentFile == directory)
                JSONObject().put("name", file.name).put("bytes", file.length()).put("sha256", diagnosticSha(file))
            },
        )
        return JSONObject().put("liveTranslateValue", live.get()).put("liveTranslateIsSet", live.isSet())
            .put("preferenceFileHashes", rows).put("preferenceValuesDumped", false)
    }

    private fun rectJson(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

    private fun publicJson(line: TextLine, kept: Set<TextLine>, expected: String, started: Long) =
        JSONObject().put("text", line.text).put("direction", line.direction)
            .put("matchesGroundTruth", line.text == expected).put("intentionallyKept", line in kept)
            .put("publicDurationMsIncludingTrace", elapsed(started))

    private fun requireCompleteTrace(snapshot: OcrSelectionDiagnostics.Snapshot, calls: Int) {
        check(snapshot.callCount == calls.toLong() && snapshot.lineCount == calls.toLong())
        check(snapshot.recordedLines == calls.toLong())
        check(snapshot.droppedLines == 0L && snapshot.droppedEvents == 0L && snapshot.recordingFailureCount == 0L)
        check(!snapshot.truncated && snapshot.events.size <= 4096)
        check(snapshot.events.all { it.callOrdinal in 1L..calls.toLong() })
    }

    private fun verifyCall(
        events: List<OcrSelectionDiagnostics.Event>,
        ordinal: Long,
        line: TextLine,
        intentionallyKept: Boolean,
        historical: JSONObject?,
    ) {
        check(events.isNotEmpty() && events.all { it.callOrdinal == ordinal })
        check(events.count { it.type.name == "CALL_START" } == 1)
        check(events.count { it.type.name == "CALL_END" } == 1)
        val ends = events.filter { it.type.name == "LINE_END" }
        check(ends.size == 1)
        val end = ends.single()
        check(end.lineIndex == 0 && end.text == line.text && end.direction == line.direction)
        check(end.intentionallyKept == intentionallyKept && end.errorClass == null)
        check(end.accepted == line.text.isNotBlank())
        val inferred = events.filter { it.type.name == "INFER" }
        check(inferred.isNotEmpty() && inferred.size <= 9)
        check(
            inferred.all {
                it.lineIndex == 0 && it.role != null && it.errorClass == null &&
                    it.actualMargin in listOf(16, 32) && it.inputHeight == 48 &&
                    (it.inputWidth ?: 0) > 0 && it.probabilityBits != null &&
                    it.chwSha256?.matches(Regex("[a-f0-9]{64}")) == true
            },
        )
        check(events.none { it.errorClass != null })
        check(events.first().type.name == "CALL_START" && events.last().type.name == "CALL_END")
        inferred.forEachIndexed { index, event ->
            check(event.nativeCallsStarted?.toLong() == index + 1L)
            check(event.nativeCallsCompleted?.toLong() == index + 1L)
        }
        check(end.nativeCallsStarted?.toLong() == inferred.size.toLong())
        check(end.nativeCallsCompleted?.toLong() == inferred.size.toLong())
        val firstPrimary = inferred.single { it.attemptIndex == 0 && it.role?.name == "PRIMARY" }
        if (historical != null) {
            val firstTensor = rawReference(historical, checkNotNull(firstPrimary.actualMargin)).getJSONObject("tensor")
            check(firstPrimary.inputWidth == firstTensor.getInt("width"))
            check(firstPrimary.inputHeight == firstTensor.getInt("height"))
            check(firstPrimary.chwSha256 == firstTensor.getString("float32LittleEndianSha256"))
            val ordered = rawReference(historical, checkNotNull(firstPrimary.actualMargin)).getJSONArray("expandedQuad")
            val orderedBits = FloatArray(ordered.length() * 2) { index ->
                ordered.getJSONArray(index / 2).getDouble(index % 2).toFloat()
            }
            val initialCrop = events.single { it.type.name == "CROP" && it.attemptIndex == 0 }
            check(initialCrop.quadSha256 == diagnosticFloatSha(orderedBits))
        }
        check(events.filter { it.type.name == "CONTEXT" }.all { it.selectedMargin in listOf(16, 32) })
        val starts = events.filter { it.type.name == "ATTEMPT_START" }
        check(starts.isNotEmpty() && starts.size <= 3 && starts.all { it.attemptIndex in 0..2 })
        check(starts.map { it.attemptIndex }.distinct().size == starts.size)
        check(
            events.filter { it.type.name == "ATTEMPT_END" }.map { it.attemptIndex }.toSet() ==
                starts.map { it.attemptIndex }.toSet(),
        )
    }

    private fun eventJson(event: OcrSelectionDiagnostics.Event) = JSONObject()
        .put("callOrdinal", event.callOrdinal).put("lineIndex", event.lineIndex).put("attemptIndex", event.attemptIndex)
        .put("type", event.type.name).put("role", event.role?.name ?: JSONObject.NULL)
        .put("reason", event.reason ?: JSONObject.NULL).put("stripPad", event.stripPad ?: JSONObject.NULL)
        .put("bicubic", event.bicubic ?: JSONObject.NULL).put("direction", event.direction ?: JSONObject.NULL)
        .put("stripWidth", event.stripWidth ?: JSONObject.NULL).put("stripHeight", event.stripHeight ?: JSONObject.NULL)
        .put("requestedMargin", event.requestedMargin ?: JSONObject.NULL)
        .put("actualMargin", event.actualMargin ?: JSONObject.NULL)
        .put("selectedMargin", event.selectedMargin ?: JSONObject.NULL)
        .put("stemEvaluation", event.stemEvaluation?.name ?: JSONObject.NULL).put("text", event.text ?: JSONObject.NULL)
        .put("probabilityBits", event.probabilityBits ?: JSONObject.NULL)
        .put("inputWidth", event.inputWidth ?: JSONObject.NULL).put("inputHeight", event.inputHeight ?: JSONObject.NULL)
        .put("chwSha256", event.chwSha256 ?: JSONObject.NULL).put("quadSha256", event.quadSha256 ?: JSONObject.NULL)
        .put("bodyMode", event.bodyMode ?: JSONObject.NULL).put("bodyEnd", event.bodyEnd ?: JSONObject.NULL)
        .put("originalText", event.originalText ?: JSONObject.NULL).put("bodyText", event.bodyText ?: JSONObject.NULL)
        .put("correctedText", event.correctedText ?: JSONObject.NULL).put("accepted", event.accepted ?: JSONObject.NULL)
        .put("intentionallyKept", event.intentionallyKept ?: JSONObject.NULL)
        .put("nativeCallsStarted", event.nativeCallsStarted ?: JSONObject.NULL)
        .put("nativeCallsCompleted", event.nativeCallsCompleted ?: JSONObject.NULL)
        .put("errorClass", event.errorClass ?: JSONObject.NULL)

    private fun snapshotJson(snapshot: OcrSelectionDiagnostics.Snapshot) = JSONObject()
        .put("callCount", snapshot.callCount).put("lineCount", snapshot.lineCount)
        .put("recordedLines", snapshot.recordedLines).put("droppedLines", snapshot.droppedLines)
        .put("droppedEvents", snapshot.droppedEvents).put("recordingFailureCount", snapshot.recordingFailureCount)
        .put("truncated", snapshot.truncated).put("eventCount", snapshot.events.size)

    private fun selectionSummary(events: List<OcrSelectionDiagnostics.Event>): JSONObject {
        val starts = events.filter { it.type.name == "ATTEMPT_START" }
        val accepted = events.filter { it.type.name == "ATTEMPT_END" && it.accepted == true }.lastOrNull()
        val lastAttempt = starts.last().attemptIndex
        val finalContext = events.lastOrNull { it.type.name == "CONTEXT" && it.attemptIndex == lastAttempt }
        val acceptedContext = accepted?.let { end ->
            events.lastOrNull { it.type.name == "CONTEXT" && it.attemptIndex == end.attemptIndex }
        }
        return JSONObject().put("attempts", starts.size).put("recoveryAttempts", starts.count { it.attemptIndex > 0 })
            .put("acceptedAttemptIndex", accepted?.attemptIndex ?: JSONObject.NULL)
            .put("acceptedAttemptSelectedMargin", acceptedContext?.selectedMargin ?: JSONObject.NULL)
            .put("lastAttemptIndex", lastAttempt)
            .put("lastAttemptSelectedMargin", finalContext?.selectedMargin ?: JSONObject.NULL)
            .put("lastContextStemEvaluation", finalContext?.stemEvaluation?.name ?: JSONObject.NULL)
            .put("inferBoundaryCalls", events.count { it.type.name == "INFER" })
            .put("bodyInferBoundaryCalls", events.count { it.type.name == "INFER" && it.role?.name == "BODY" })
            .put("terminalReasons", JSONArray(events.filter { it.type.name == "TERMINAL" }.map { it.reason }))
            .put("scope", "Actual mode call events; accepted crop and all attempted crops are separate")
    }

    private fun verifySource(case: OcrRightContextCase, drawing: OcrRightContextDrawing, prior: JSONObject) {
        check(prior.getString("group") == case.group && prior.getString("expected") == case.expected)
        check(prior.getInt("sourceWidth") == drawing.page.width && prior.getInt("sourceHeight") == drawing.page.height)
        check(prior.getString("sourcePixelsArgbSha256") == pixelsSha(diagnosticPixels(drawing.page)))
        check(prior.getJSONArray("quad").toString() == quadJson(drawing.quad).toString())
    }

    private fun rawReference(prior: JSONObject, margin: Int): JSONObject {
        val observations = prior.getJSONArray("rawObservations")
        val rows = (0 until observations.length()).map { observations.getJSONObject(it) }
            .filter { it.getInt("rightMargin") == margin }
        check(rows.size == 1)
        return rows.single()
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

    /** Context's /data/user alias may canonicalize to /data/data; only literal descendants are accepted. */
    private fun modelDirectory(context: Context): File {
        val files = context.filesDir.canonicalFile
        val data = File(context.applicationInfo.dataDir).canonicalFile
        check(files.isDirectory && files.name == "files" && files.parentFile == data)
        val directory = File(files, "models")
        check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile)
        return directory
    }

    private fun fingerprintModels(directory: File) = JSONArray(
        MODEL_NAMES.map { name ->
            val file = File(directory, name)
            check(file.canonicalFile == file.absoluteFile && file.parentFile == directory)
            check(file.isFile && file.length() > 0)
            JSONObject().put("name", name).put("bytes", file.length()).put("sha256", diagnosticSha(file))
        },
    )

    private class HistoricalReference(private val parent: File, basename: String) {
        private val directory = File(parent, basename)
        private val file = File(directory, "result.json")
        val report: JSONObject
        val rows: Map<String, JSONObject>

        init {
            check(basename.matches(Regex("ocr-font-geometry-reference-$UUID_PATTERN")))
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
        const val MAX_REPORT_BYTES = 8_388_608L
        const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        const val INPUT_MANIFEST_SHA = "3178bfe3563ea01ce40423d7f9fb7579160797b42c190d416b29cb0c36ec015a"
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        const val REFERENCE_BYTES = 903_253L
        const val REFERENCE_SHA = "ed39996cafe9225ed8bc365390e78e3413c99da909668655e2c079e25d9dad66"
        const val GUARDED32_CONTEXT_SHA = "db1c82f4adad3c48d2755998063c4c7ffc4db906dc1887aa694e40bfa2d0f437"
        val CLEAN_IDS = listOf("new-28", "new-40", "new-29", "new-64", "new-66")
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
