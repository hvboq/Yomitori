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
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
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

/**
 * Fixed neutral note pairs distinguish source raster, initial preprocessing and public OCR output.
 * Expected labels only score outputs; they never select a crop, fallback, font or replacement.
 * Collection success does not imply accuracy, a native-model cause, or production eligibility.
 */
@RunWith(AndroidJUnit4::class)
class OcrNoteGlyphDiagnosticTest {
    @Test
    fun compareNeutralNoteGlyphRastersAndDefaultPublicOcr() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "ocr-note-glyph-diagnostic") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    withTimeout(10 * 60 * 1000L) { collect() }
                }
            }
    }

    private suspend fun collect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("ocrNoteGlyphDiagnostic") == "true") {
            "Explicit ocrNoteGlyphDiagnostic=true is required; missing opt-in is not a pass or skip"
        }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation is required" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Actual ARM64 process is required" }
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val reference = HistoricalReference(
            external,
            checkNotNull(arguments.getString("ocrNoteGlyphReference")) {
                "A pinned reference UUID basename is required"
            },
        )
        val originals = rightContextGeneratedCases().filter { it.id in ORIGINAL_IDS }
        check(originals.map { it.id } == ORIGINAL_IDS)
        check(originals.all { reference.rows.getValue(it.id).getString("expected") == it.expected })
        val config = OcrConfig(
            horizontalContextPolicy = HorizontalOcrContextPolicy.GUARDED_32,
            horizontalCropPolicy = HorizontalOcrCropPolicy.UNIFORM,
            ocrUnsharp = true,
        )
        check(config == OcrConfig()) { "This diagnostic measures the actual default policy" }
        check(config.useBicubic && config.stripPad == 4 && config.textHeight == 48 && config.ignoreBubble == 0)
        check(config.concurrent && config.concurrency == 8 && config.minProb == 0.5f && config.minTextLength == 0)
        check(config.ncnnMixed && config.ncnnFp16Storage && config.ncnnFp16Arith)
        val shared = Injekt.get<TranslationEngineService>()
        val manager = Injekt.get<TranslationManager>()
        withTimeout(10_000) { manager.ensureRestored() }
        requireIdle(shared)
        val saved = OnlineReaderSettings(context)
        val output = File(external, "ocr-note-glyph-diagnostic-${UUID.randomUUID()}")
        check(output.mkdir() && output.canonicalFile == output.absoluteFile && output.parentFile == external)
        val reportFile = File(output, "result.json")
        val originalRows = JSONArray()
        val pairRows = JSONArray()
        val started = SystemClock.elapsedRealtimeNanos()
        val threadId = Thread.currentThread().id
        val report = JSONObject().put("passed", false).put("collectionValidationPassed", false)
            .put("diagnosticOnly", true).put("accuracyIsPassCriterion", false).put("promotionEligible", false)
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("process64Bit", true)
            .put("javaThreadId", threadId).put("javaThreadName", Thread.currentThread().name)
            .put("androidTid", Process.myTid()).put("sameThreadSerialPublicCalls", true)
            .put("historicalReference", reference.description()).put("config", configJson(config))
            .put("originalSampleIds", JSONArray(ORIGINAL_IDS)).put("expectedOriginalPublicCalls", 8)
            .put("expectedControlPublicCalls", 16).put("expectedTotalPublicCalls", 24)
            .put("expectedControlPairs", 8).put("directRawInferCalls", 0)
            .put("sourceInput40FilesRequired", false).put("newImagesWritten", 0)
            .put("sourcePixelHashLayout", "Row-major bytes A,R,G,B; independent of PNG encoding")
            .put("renderContract", renderContract())
            .put("actualSelectedMarginBodyOrRecoveryPerPublicCall", "Unmeasured")
            .put("preprocessingScope", "Initial crop nominal16/32; actual public guards/recovery are not instrumented")
            .put("fontScope", "Requested Android family/style and observed raster; actual fallback font file unknown")
            .put("hasGlyphImpliesDistinctRaster", false).put("sameOutputImpliesSameRaster", false)
            .put("modelOnlyCauseClaimed", false).put("textNormalizationOrCorrectionByTest", false)
            .put("llmOrOtherNetworkCalls", false).put("defaultOrModelChanged", false)
            .put("actualReaderActivity", false).put("signedRelease", false)
            .put("timingScope", "Bounded diagnostic collection, not a performance benchmark")
            .put("totalNativeForwardCountKnown", false)
            .put("staticMaximumForwardsPerSingleLineCall", 9).put("staticMaximumFor24Calls", 216)
            .put("staticBoundIsMeasuredCount", false)
            .put("staticBoundBasis", "Context<=2 plus circle-body<=1 per crop, recovery<=3 crops")
            .put("sharedEngineShutdownCalled", false).put("originals", originalRows).put("pairs", pairRows)
        var failure: Throwable? = null
        var beforeModels = JSONArray()
        var ownedOcr: Ocr? = null
        var originalCalls = 0
        var controlCalls = 0
        fun checkpoint() {
            val atomic = AtomicFile(reportFile)
            val stream = atomic.startWrite()
            try {
                stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
        fun status(phase: String) {
            val progress = JSONObject().put("phase", phase).put("report", reportFile.absolutePath)
                .put("originals", originalRows.length()).put("pairs", pairRows.length())
                .put("publicCallsCompleted", originalCalls + controlCalls)
                .put("terminalReportSealed", phase == "COMPLETED" || phase == "FAILED")
            if (phase == "COMPLETED" || phase == "FAILED") {
                check(output.list()?.toSet() == setOf("result.json"))
                check(reportFile.isFile && reportFile.canonicalFile == reportFile.absoluteFile)
                check(reportFile.canonicalFile.parentFile == output && reportFile.length() in 1..2_097_152L)
                progress.put("ownedDirectory", output.name).put("canonicalBoundaryVerified", true)
                    .put(
                        "ownedFiles",
                        JSONArray().put(
                            JSONObject().put("name", "result.json").put("bytes", reportFile.length())
                                .put("sha256", diagnosticSha(reportFile)),
                        ),
                    )
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply { putString("stream", "OCR_NOTE_GLYPH_DIAGNOSTIC=$progress") },
            )
        }
        try {
            saved.set(Injekt.get<TranslationPreferences>().liveTranslate, false)
            requireIdle(shared)
            report.put("temporaryLiveTranslateDisabled", true)
            val models = modelDirectory(context)
            beforeModels = fingerprintModels(models)
            check(beforeModels.toString() == reference.report.getJSONArray("modelsAfter").toString())
            report.put("modelsBefore", beforeModels).put("sameSevenModelsAsHistoricalReference", true)
            checkpoint()
            status("STARTED")
            val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
            check(bytesSha(dictionaryBytes) == DICTIONARY_SHA)
            val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
            check(dictionary.size == 19_264)
            report.put("dictionaryRows", dictionary.size).put("dictionarySha256", bytesSha(dictionaryBytes))
            val noteDictionaryRows = JSONArray()
            for ((index, codepoint) in NOTES.withIndex()) {
                val label = String(Character.toChars(codepoint))
                val rows = dictionary.indices.filter { dictionary[it] == label }
                check(rows == listOf(2213 + index))
                noteDictionaryRows.put(
                    JSONObject().put("codepointHex", "U+%04X".format(codepoint))
                        .put("dictionaryRowZeroBased", rows.single()).put("dictionaryOccurrences", rows.size),
                )
            }
            report.put("noteDictionaryEntries", noteDictionaryRows).put("dictionaryRowIsModelCtcIndex", false)
            val param = File(models, "ocr_48px_ctc.ncnn.param")
            Ocr(param.path, dictionary, config).use { ocr ->
                ownedOcr = ocr
                val probe = OcrDiagnosticProbe(ocr, dictionary)
                verifyBackend(probe, param.path, reference, report)
                check(
                    ocr.intentionalFilterContext == TranslationContext.forOcrPolicy(
                        config.ignoreBubble,
                        config.horizontalContextPolicy,
                        config.horizontalCropPolicy,
                    ),
                )
                check(ocr.intentionalFilterContext.semanticFingerprint == DEFAULT_CONTEXT_SHA)
                report.put("actualOcrContext", ocr.intentionalFilterContext.semanticFingerprint)
                for (case in originals) {
                    currentCoroutineContext().ensureActive()
                    requireIdle(shared)
                    check(Thread.currentThread().id == threadId)
                    val drawing = case.open()
                    try {
                        val prior = reference.rows.getValue(case.id)
                        val pixels = diagnosticPixels(drawing.page)
                        val quad = drawing.quad.map { Pt(it.x, it.y) }
                        verifyHistoricalSource(case, drawing, pixels, prior)
                        val observed = observe(probe, drawing.page, quad)
                        verifyHistoricalTensor(observed.tensor16, observed.expanded, rawReference(prior, 16))
                        verifyHistoricalTensor(observed.tensor32, observed.expanded, rawReference(prior, 32))
                        val row = JSONObject().put("id", case.id).put("expected", case.expected)
                            .put("metadata", case.metadata).put("sourcePixelsArgbSha256", pixelsSha(pixels))
                            .put("quad", quadJson(quad)).put("initialPreprocessing", observed.json())
                            .put("historicalSourceQuadAnd16And32ChwExact", true)
                        originalRows.put(row)
                        val line = TextLine(quad.map { Pt(it.x, it.y) }, 1f)
                        val kept = ocr.recognize(drawing.page, listOf(line))
                        originalCalls++
                        check(kept.isEmpty() && line.quad == quad && drawing.quad == quad)
                        check(pixels.contentEquals(diagnosticPixels(drawing.page)))
                        val historicalPublic = prior.getJSONObject("candidatePublic")
                        row.put("public", publicJson(line, case.expected))
                            .put("historicalGuarded32Text", historicalPublic.getString("text"))
                            .put("historicalGuarded32Direction", historicalPublic.getString("direction"))
                        check(line.text == historicalPublic.getString("text"))
                        check(line.direction == historicalPublic.getString("direction"))
                        row.put("historicalGuarded32PublicTextAndDirectionExact", true)
                        checkpoint()
                    } finally {
                        drawing.page.recycle()
                    }
                }
                check(originalRows.length() == 8 && originalCalls == 8 && controlCalls == 0)
                status("ORIGINALS_REPRODUCED")
                val specifications = controlSpecs()
                check(specifications.size == 8 && specifications.map { it.id }.distinct().size == 8)
                for (spec in specifications) {
                    currentCoroutineContext().ensureActive()
                    requireIdle(shared)
                    check(Thread.currentThread().id == threadId)
                    val left = render(spec, 0)
                    try {
                        val right = render(spec, 1)
                        try {
                            val leftPixels = diagnosticPixels(left.page)
                            val rightPixels = diagnosticPixels(right.page)
                            val leftObserved = observe(probe, left.page, left.quad)
                            val rightObserved = observe(probe, right.page, right.quad)
                            check(left.quad == right.quad && left.quad == spec.quad)
                            repeatAndVerify(spec, 0, left, leftPixels, leftObserved, probe)
                            repeatAndVerify(spec, 1, right, rightPixels, rightObserved, probe)
                            check(leftObserved.expanded == rightObserved.expanded)
                            check(leftObserved.width == rightObserved.width)
                            check(leftObserved.height == rightObserved.height)
                            val sourceDiff = pixelDiff(leftPixels, rightPixels, WIDTH, HEIGHT)
                            val stripDiff = pixelDiff(
                                leftObserved.pixels,
                                rightObserved.pixels,
                                leftObserved.width,
                                leftObserved.height,
                            )
                            val tensor16Diff = tensorDiff(leftObserved.tensor16, rightObserved.tensor16)
                            val tensor32Diff = tensorDiff(leftObserved.tensor32, rightObserved.tensor32)
                            val row = spec.json().put("left", controlJson(spec, 0, leftPixels, leftObserved))
                                .put("right", controlJson(spec, 1, rightPixels, rightObserved))
                                .put("sourceArgbDiff", sourceDiff).put("productionCropArgbDiff", stripDiff)
                                .put("nominal16ChwDiff", tensor16Diff).put("nominal32ChwDiff", tensor32Diff)
                                .put("rawUnionInkDiff", inkDiffs(leftPixels, rightPixels, spec.rawUnion))
                                .put("commonQuadInkDiff", inkDiffs(leftPixels, rightPixels, spec.commonBounds))
                                .put("allRequestedCodepointsSupported", codepointsSupported(spec))
                                .put("bothControlsRepeatedExactly", true).put("actualProbeDirection", "h")
                            pairRows.put(row)
                            val leftLine = TextLine(left.quad.map { Pt(it.x, it.y) }, 1f)
                            val rightLine = TextLine(right.quad.map { Pt(it.x, it.y) }, 1f)
                            check(ocr.recognize(left.page, listOf(leftLine)).isEmpty())
                            controlCalls++
                            check(ocr.recognize(right.page, listOf(rightLine)).isEmpty())
                            controlCalls++
                            check(leftLine.direction == "h" && rightLine.direction == "h")
                            check(leftLine.quad == spec.quad && rightLine.quad == spec.quad)
                            check(leftPixels.contentEquals(diagnosticPixels(left.page)))
                            check(rightPixels.contentEquals(diagnosticPixels(right.page)))
                            row.put("leftPublic", publicJson(leftLine, spec.texts[0]))
                                .put("rightPublic", publicJson(rightLine, spec.texts[1]))
                                .put(
                                    "actualPublicDirections",
                                    JSONArray(listOf(leftLine.direction, rightLine.direction)),
                                )
                                .put(
                                    "interpretation",
                                    interpret(
                                        sourceDiff,
                                        tensor16Diff,
                                        tensor32Diff,
                                        leftLine,
                                        rightLine,
                                        codepointsSupported(spec),
                                    ),
                                )
                                .put("sourceAndQuadUnchanged", true)
                            checkpoint()
                            status("COLLECTING")
                        } finally {
                            right.page.recycle()
                        }
                    } finally {
                        left.page.recycle()
                    }
                }
                check(originalCalls == 8 && controlCalls == 16 && pairRows.length() == 8)
                check(probe.explicitNativeCalls == 0 && probe.privateInferParityCalls == 0)
                report.put("probeExplicitNativeCalls", 0).put("probePrivateInferParityCalls", 0)
                    .put("summary", summarize(originalRows, pairRows)).put("collectionValidationPassed", true)
            }
        } catch (error: Throwable) {
            failure = error
            report.put("failureClass", error.javaClass.name)
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
            cleanup("privateNativeOcrClosed") {
                ownedOcr?.let { ocr ->
                    val handle = Ocr::class.java.getDeclaredField("ncnnHandle").apply { isAccessible = true }
                        .getLong(ocr)
                    check(handle == 0L)
                }
            }
            cleanup("historicalReferenceUnchanged") { reference.verifyUnchanged() }
            cleanup("existingSevenModelsUnchanged") {
                val after = fingerprintModels(modelDirectory(context))
                report.put("modelsAfter", after)
                check(beforeModels.length() == 7 && beforeModels.toString() == after.toString())
            }
            cleanup("sharedEngineStillAbsentAndQueuesIdle") { requireIdle(shared) }
            cleanup("temporaryLiveTranslateRestoredIncludingPresenceAndDisk") { saved.close() }
            report.put("publicOriginalCallsCompleted", originalCalls).put("publicControlCallsCompleted", controlCalls)
                .put("collectionValidationPassed", failure == null && report.optBoolean("collectionValidationPassed"))
                .put("passed", failure == null).put("wallMs", elapsed(started)).put("finalReportWritten", true)
            cleanup("finalReportWritten") { checkpoint() }
            if (failure != null) {
                report.put("passed", false).put("collectionValidationPassed", false).put("failureReportRewritten", true)
                cleanup("failureReportRewritten") { checkpoint() }
            }
            status(if (failure == null) "COMPLETED" else "FAILED") // No later report mutation or write.
        }
        failure?.let { throw it }
        check(report.getBoolean("collectionValidationPassed"))
    }
    private data class ControlSpec(
        val id: String,
        val family: String,
        val size: Float,
        val contextName: String,
        val texts: List<String>,
        val measurements: List<JSONObject>,
        val rawUnion: Rect,
        val paddedUnion: Rect,
        val commonBounds: Rect,
        val quad: List<Pt>,
    )

    private data class NoteDrawing(val page: Bitmap, val quad: List<Pt>, val paintMetadata: JSONObject)

    private data class CropObservation(
        val expanded: List<Pt>,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        val tensor16: OcrDiagnosticTensor,
        val tensor32: OcrDiagnosticTensor,
    )

    private fun controlSpecs(): List<ControlSpec> {
        val result = mutableListOf<ControlSpec>()
        for (family in listOf("sans-serif", "serif")) {
            for (size in listOf(44f, 60f)) {
                for ((name, prefix) in listOf("phrase" to "音楽です", "isolated" to "")) {
                    val texts = NOTES.map { prefix + String(Character.toChars(it)) }
                    val paint = paint(family, size)
                    val bounds = texts.map { text -> Rect().also { paint.getTextBounds(text, 0, text.length, it) } }
                    val raw = Rect(
                        X.toInt() + bounds.minOf { it.left },
                        BASELINE.toInt() + bounds.minOf { it.top },
                        X.toInt() + bounds.maxOf { it.right },
                        BASELINE.toInt() + bounds.maxOf { it.bottom },
                    )
                    check(raw.width() > 0 && raw.height() > 0)
                    val padded = Rect(raw.left - 2, raw.top - 2, raw.right + 2, raw.bottom + 2)
                    val common = Rect(
                        padded.left,
                        padded.top,
                        padded.left + maxOf(padded.width(), 2 * padded.height()),
                        padded.bottom,
                    )
                    check(common.left >= 0 && common.top >= 0 && common.right < WIDTH && common.bottom < HEIGHT)
                    val metadata = texts.indices.map { index ->
                        paintMetadata(family, size, texts[index], NOTES[index], paint)
                    }
                    result += ControlSpec(
                        "$family-${size.toInt()}-$name", family, size, name, texts, metadata,
                        raw, padded, common, rectQuad(common),
                    )
                }
            }
        }
        return result
    }

    private fun paint(family: String, size: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = Typeface.create(family, Typeface.NORMAL)
    }

    private fun render(spec: ControlSpec, index: Int): NoteDrawing {
        val paint = paint(spec.family, spec.size)
        val metadata = paintMetadata(spec.family, spec.size, spec.texts[index], NOTES[index], paint)
        check(metadata.toString() == spec.measurements[index].toString())
        val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            Canvas(page).apply {
                drawColor(Color.WHITE)
                drawText(spec.texts[index], X, BASELINE, paint)
            }
            check(
                diagnosticPixels(page).all { pixel ->
                    val red = (pixel ushr 16) and 255
                    (pixel ushr 24) == 255 && red == ((pixel ushr 8) and 255) && red == (pixel and 255)
                },
            ) { "Control must be opaque black/white grayscale, including antialias pixels" }
            return NoteDrawing(page, spec.quad.map { Pt(it.x, it.y) }, metadata)
        } catch (error: Throwable) {
            page.recycle()
            throw error
        }
    }

    private fun paintMetadata(family: String, size: Float, text: String, codepoint: Int, paint: Paint): JSONObject {
        val note = String(Character.toChars(codepoint))
        check(note.codePointCount(0, note.length) == 1 && Character.codePointAt(note, 0) == codepoint)
        val metrics = paint.fontMetrics
        return JSONObject().put("requestedFontFamily", family).put("requestedStyle", "NORMAL")
            .put("requestedStyleValue", Typeface.NORMAL).put("actualTypefaceStyle", paint.typeface.style)
            .put("actualTypefaceBold", paint.typeface.isBold).put("actualTypefaceItalic", paint.typeface.isItalic)
            .put("textSize", floatJson(size)).put("antiAlias", paint.isAntiAlias).put("paintFlags", paint.flags)
            .put("drawColorArgb", "ff000000").put("backgroundArgb", "ffffffff")
            .put("text", measurement(paint, text)).put("singleCodepoint", measurement(paint, note))
            .put("codepointDecimal", codepoint).put("codepointHex", "U+%04X".format(codepoint))
            .put("codepointIntRawBitsHex", "%08x".format(codepoint))
            .put("singleCodepointCount", 1).put("utf16CodeUnitsHex", JSONArray(note.map { "%04X".format(it.code) }))
            .put("utf8BytesHex", note.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) })
            .put(
                "fontMetrics",
                JSONObject().put("top", floatJson(metrics.top)).put("ascent", floatJson(metrics.ascent))
                    .put("descent", floatJson(metrics.descent)).put("bottom", floatJson(metrics.bottom))
                    .put("leading", floatJson(metrics.leading)),
            )
            .put("actualFontFileOrGlyphIdKnown", false)
    }

    private fun measurement(paint: Paint, text: String): JSONObject {
        val widths = FloatArray(text.length)
        val count = paint.getTextWidths(text, widths)
        check(count == text.length)
        val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        val codepoints = text.codePoints().toArray()
        return JSONObject().put("string", text).put("singleGlyphForFullString", paint.hasGlyph(text))
            .put(
                "perCodepointHasGlyph",
                JSONArray(
                    codepoints.map { codepoint ->
                        JSONObject().put("codepointHex", "U+%04X".format(codepoint))
                            .put("hasGlyph", paint.hasGlyph(String(Character.toChars(codepoint))))
                    },
                ),
            )
            .put("measureText", floatJson(paint.measureText(text))).put("getTextWidthsCount", count)
            .put("getTextWidths", JSONArray(widths.map { floatJson(it) })).put("getTextBounds", rectJson(bounds))
    }

    private fun floatJson(value: Float): JSONObject = JSONObject().put("value", value.toDouble())
        .put("rawBitsHex", "%08x".format(value.toRawBits()))

    private fun ControlSpec.json(): JSONObject = JSONObject().put("id", id).put("requestedFamily", family)
        .put("textSize", size.toDouble()).put("context", contextName).put("requestedStyle", "NORMAL")
        .put("notes", JSONArray(NOTES.map { "U+%04X".format(it) })).put("texts", JSONArray(texts))
        .put("rawUnionInkBounds", rectJson(rawUnion)).put("unionWithTwoPixelMargin", rectJson(paddedUnion))
        .put("commonMinimumWidthBounds", rectJson(commonBounds)).put("sharedQuad", quadJson(quad))
        .put("sameQuadForBothGlyphs", true).put("individualGlyphCropOrAlignment", false)

    private fun codepointsSupported(spec: ControlSpec): Boolean = spec.measurements.all { measurement ->
        val codepoints = measurement.getJSONObject("text").getJSONArray("perCodepointHasGlyph")
        (0 until codepoints.length()).all { codepoints.getJSONObject(it).getBoolean("hasGlyph") }
    }

    private fun renderContract(): JSONObject = JSONObject().put("width", WIDTH).put("height", HEIGHT)
        .put("x", X.toDouble()).put("baseline", BASELINE.toDouble()).put("antialias", true)
        .put("foreground", "black").put("background", "white").put("style", "NORMAL")
        .put("families", JSONArray(listOf("sans-serif", "serif"))).put("sizes", JSONArray(listOf(44, 60)))
        .put("contexts", JSONArray(listOf("音楽です+note", "isolated note")))
        .put("notes", JSONArray(NOTES.map { "U+%04X".format(it) }))
        .put("quadRule", "Union full-text ink bounds +2px each side; right=left+max(width,2*height)")
        .put("quadRuleFixedBeforeObservation", true).put("repeatRendersPerControl", 1)
        .put("thresholdRoi", "Original common union ink bounds; enlarged shared quad ROI also recorded")
        .put("thresholdRule", "Opaque grayscale pixel ink iff channel value < threshold; thresholds128/245")

    private fun observe(probe: OcrDiagnosticProbe, page: Bitmap, quad: List<Pt>): CropObservation {
        val originalQuad = quad.map { Pt(it.x, it.y) }
        val variant = OcrDiagnosticVariant("defaultInitialNominal32", 4, 32, true)
        // Probe.crop uses actual expandQuad/sortPnts/transformedRegion and asserts !isVertical.
        val (strip, expanded) = probe.crop(page, quad, variant)
        try {
            val pixels = diagnosticPixels(strip)
            val tensor16 = probe.tensor(strip, variant.copy(name = "defaultInitialNominal16", rightMargin = 16))
            val tensor32 = probe.tensor(strip, variant)
            check(tensor16.json().getBoolean("productionPreprocessParity"))
            check(tensor32.json().getBoolean("productionPreprocessParity"))
            check(strip.height == 48 && tensor16.height == 48 && tensor32.height == 48)
            check(tensor16.width == strip.width + 16 && tensor32.width == strip.width + 32)
            check(tensor16.width / 4 - 1 in 1..2048 && tensor32.width / 4 - 1 in 1..2048)
            check(pixels.contentEquals(diagnosticPixels(strip)) && quad == originalQuad)
            return CropObservation(expanded, strip.width, strip.height, pixels, tensor16, tensor32)
        } finally {
            strip.recycle()
        }
    }

    private fun CropObservation.json(): JSONObject = JSONObject().put("expandedQuad", quadJson(expanded))
        .put("stripWidth", width).put("stripHeight", height).put("stripArgbSha256", pixelsSha(pixels))
        .put("nominal16", tensor16.json()).put("nominal32", tensor32.json())
        .put("actualProbeDirection", "h").put("actualPublicMarginMeasured", false)

    private fun repeatAndVerify(
        spec: ControlSpec,
        index: Int,
        original: NoteDrawing,
        originalPixels: IntArray,
        originalCrop: CropObservation,
        probe: OcrDiagnosticProbe,
    ) {
        val repeated = render(spec, index)
        try {
            val pixels = diagnosticPixels(repeated.page)
            check(originalPixels.contentEquals(pixels) && original.quad == repeated.quad)
            check(original.paintMetadata.toString() == repeated.paintMetadata.toString())
            val crop = observe(probe, repeated.page, repeated.quad)
            check(crop.expanded == originalCrop.expanded && crop.width == originalCrop.width)
            check(crop.height == originalCrop.height && crop.pixels.contentEquals(originalCrop.pixels))
            check(tensorDiff(crop.tensor16, originalCrop.tensor16).getLong("rawFloatBitsDiffCount") == 0L)
            check(tensorDiff(crop.tensor32, originalCrop.tensor32).getLong("rawFloatBitsDiffCount") == 0L)
            check(diagnosticFloatSha(crop.tensor16.chw) == diagnosticFloatSha(originalCrop.tensor16.chw))
            check(diagnosticFloatSha(crop.tensor32.chw) == diagnosticFloatSha(originalCrop.tensor32.chw))
            check(pixels.contentEquals(diagnosticPixels(repeated.page)))
            check(originalPixels.contentEquals(diagnosticPixels(original.page)))
            check(original.quad == spec.quad && repeated.quad == spec.quad)
        } finally {
            repeated.page.recycle()
        }
    }

    private fun controlJson(spec: ControlSpec, index: Int, pixels: IntArray, crop: CropObservation): JSONObject =
        JSONObject().put("label", "U+%04X".format(NOTES[index])).put("expected", spec.texts[index])
            .put("paintMetadata", spec.measurements[index]).put("sourceArgbSha256", pixelsSha(pixels))
            .put("quad", quadJson(spec.quad)).put("initialPreprocessing", crop.json())

    private fun pixelDiff(a: IntArray, b: IntArray, width: Int, height: Int): JSONObject {
        check(a.size == width * height && b.size == a.size)
        var count = 0
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (index in a.indices) {
            if (a[index] == b[index]) continue
            count++
            val x = index % width
            val y = index / width
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        return JSONObject().put("width", width).put("height", height).put("differentPixelCount", count)
            .put("comparedPixels", a.size).put("sameArgbBits", count == 0)
            .put(
                "bboxRightBottomExclusive",
                if (count == 0) JSONObject.NULL else rectJson(Rect(left, top, right + 1, bottom + 1)),
            )
    }

    private fun inkDiffs(a: IntArray, b: IntArray, roi: Rect): JSONObject {
        check(a.size == WIDTH * HEIGHT && b.size == a.size)
        check(roi.left >= 0 && roi.top >= 0 && roi.right <= WIDTH && roi.bottom <= HEIGHT)
        val rows = JSONArray()
        for (threshold in listOf(128, 245)) {
            var leftInk = 0
            var rightInk = 0
            var differentInk = 0
            for (y in roi.top until roi.bottom) {
                for (x in roi.left until roi.right) {
                    val offset = y * WIDTH + x
                    val left = ((a[offset] ushr 16) and 255) < threshold
                    val right = ((b[offset] ushr 16) and 255) < threshold
                    if (left) leftInk++
                    if (right) rightInk++
                    if (left != right) differentInk++
                }
            }
            rows.put(
                JSONObject().put("threshold", threshold).put("leftInkPixels", leftInk)
                    .put("rightInkPixels", rightInk).put("differentInkPixels", differentInk),
            )
        }
        return JSONObject().put("sameSourceCoordinatesRoi", rectJson(roi))
            .put("comparedPixels", roi.width() * roi.height()).put("thresholds", rows)
    }

    private fun tensorDiff(a: OcrDiagnosticTensor, b: OcrDiagnosticTensor): JSONObject {
        check(a.width == b.width && a.height == b.height && a.chw.size == b.chw.size)
        check(a.chw.size == a.width * a.height * 3)
        val perChannel = LongArray(3)
        val area = a.width * a.height
        for (index in a.chw.indices) {
            if (a.chw[index].toRawBits() != b.chw[index].toRawBits()) perChannel[index / area]++
        }
        val count = perChannel.sum()
        return JSONObject().put("sameShape", true).put("width", a.width).put("height", a.height)
            .put("floatElements", a.chw.size).put("rawFloatBitsDiffCount", count)
            .put("perChannelRawBitsDiffCount", JSONArray(perChannel.toList())).put("sameFloatBits", count == 0L)
            .put("leftFloat32LittleEndianSha256", diagnosticFloatSha(a.chw))
            .put("rightFloat32LittleEndianSha256", diagnosticFloatSha(b.chw))
    }

    private fun interpret(
        source: JSONObject,
        tensor16: JSONObject,
        tensor32: JSONObject,
        left: TextLine,
        right: TextLine,
        codepointsSupported: Boolean,
    ): JSONObject {
        val sourceDifferent = source.getInt("differentPixelCount") > 0
        val bothTensorsSame = tensor16.getBoolean("sameFloatBits") && tensor32.getBoolean("sameFloatBits")
        val outputSame = left.text == right.text
        val kind = when {
            !codepointsSupported -> "requested-codepoint-support-missing"
            !sourceDifferent -> "observed-source-raster-identical"
            bothTensorsSame -> "distinct-source-collapsed-in-both-observed-initial-contexts"
            outputSame -> "distinct-source-and-initial-tensors-same-public-output-for-this-pair"
            else -> "observed-public-outputs-differ"
        }
        return JSONObject().put("kind", kind).put("sourcePixelsDiffer", sourceDifferent)
            .put("bothObservedInitialContextTensorsSame", bothTensorsSame).put("publicTextSame", outputSame)
            .put("initialPreprocessingLossEvidence", sourceDifferent && bothTensorsSame)
            .put("nominal16PreprocessingLossEvidence", sourceDifferent && tensor16.getBoolean("sameFloatBits"))
            .put("nominal32PreprocessingLossEvidence", sourceDifferent && tensor32.getBoolean("sameFloatBits"))
            .put("allRequestedCodepointsSupported", codepointsSupported)
            .put(
                "publicDidNotDistinguishThisPair",
                codepointsSupported && sourceDifferent && !bothTensorsSame && outputSame,
            )
            .put("nativeModelOnlyCauseEstablished", false)
            .put(
                "scope",
                "This pair and initial nominal16/32 only; public guards/body/recovery and raw logits unmeasured",
            )
    }

    private fun publicJson(line: TextLine, expected: String): JSONObject = JSONObject().put("text", line.text)
        .put("direction", line.direction).put("matchesLabel", line.text == expected)
        .put("codepointsHex", JSONArray(line.text.codePoints().toArray().map { "U+%04X".format(it) }))

    private fun summarize(originals: JSONArray, pairs: JSONArray): JSONObject {
        val originalRows = (0 until originals.length()).map { originals.getJSONObject(it) }
        val pairRows = (0 until pairs.length()).map { pairs.getJSONObject(it) }
        val controls = pairRows.flatMap { listOf(it.getJSONObject("leftPublic"), it.getJSONObject("rightPublic")) }
        val kinds = pairRows.groupingBy { it.getJSONObject("interpretation").getString("kind") }.eachCount()
        val originalExact = originalRows.count { it.getJSONObject("public").getBoolean("matchesLabel") }
        val controlExact = controls.count { it.getBoolean("matchesLabel") }
        val sourceDifferent = pairRows.count { it.getJSONObject("sourceArgbDiff").getInt("differentPixelCount") > 0 }
        return JSONObject().put("historicalOriginalSamples", originalRows.size)
            .put("historicalOriginalLabelsExact", originalExact)
            .put("historicalGuarded32PredictionsReproduced", 8).put("controlPairs", pairRows.size)
            .put("controlSamples", controls.size).put("controlLabelsExact", controlExact)
            .put("sourceDifferentPairs", sourceDifferent)
            .put("interpretationCounts", JSONObject(kinds)).put("accuracySeparateFromCollection", true)
            .put("promotionEligible", false)
    }

    private fun verifyBackend(
        probe: OcrDiagnosticProbe,
        paramPath: String,
        reference: HistoricalReference,
        report: JSONObject,
    ) {
        val actual = probe.metadata()
        val historical = reference.report.getJSONObject("candidateBackend")
        for (key in listOf("backend", "nativeThreads", "serialize", "peElements", "peFloat32LittleEndianSha256")) {
            check(actual.get(key) == historical.get(key))
        }
        check(actual.getInt("nativeThreads") == 1 && actual.getString("backend") == "NCNN-mixed")
        val selected = probe.selectedParam(paramPath)
        check(selected.second && reference.report.getBoolean("selectedMixed"))
        check(File(selected.first).name == "ocr_48px_ctc_mixed.ncnn.param")
        check(File(selected.first).name == reference.report.getString("selectedParam"))
        report.put("backend", actual).put("selectedParam", File(selected.first).name)
            .put("selectedMixed", selected.second).put("backendAndPeMatchHistoricalReference", true)
            .put("onePrivateOcrInstance", true).put("probeUsedOnlyForMetadataAndPreprocessing", true)
    }

    private fun verifyHistoricalSource(
        case: OcrRightContextCase,
        drawing: OcrRightContextDrawing,
        pixels: IntArray,
        prior: JSONObject,
    ) {
        check(prior.getString("group") == case.group && prior.getString("expected") == case.expected)
        check(prior.getInt("sourceWidth") == drawing.page.width && prior.getInt("sourceHeight") == drawing.page.height)
        check(prior.getString("sourcePixelsArgbSha256") == pixelsSha(pixels))
        check(prior.getJSONArray("quad").toString() == quadJson(drawing.quad).toString())
    }

    private fun rawReference(prior: JSONObject, margin: Int): JSONObject {
        val observations = prior.getJSONArray("rawObservations")
        val rows = (0 until observations.length()).map { observations.getJSONObject(it) }
            .filter { it.getInt("rightMargin") == margin }
        check(rows.size == 1)
        return rows.single()
    }

    private fun verifyHistoricalTensor(tensor: OcrDiagnosticTensor, expanded: List<Pt>, prior: JSONObject) {
        check(quadJson(expanded).toString() == prior.getJSONArray("expandedQuad").toString())
        val expected = prior.getJSONObject("tensor")
        check(tensor.width == expected.getInt("width") && tensor.height == expected.getInt("height"))
        check(tensor.height == 48 && tensor.chw.size == tensor.width * tensor.height * 3)
        check(tensor.json().getBoolean("productionPreprocessParity"))
        check(diagnosticFloatSha(tensor.chw) == expected.getString("float32LittleEndianSha256"))
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

    private fun configJson(config: OcrConfig) = JSONObject().put("dataClassValue", config.toString())
        .put("ocrUnsharp", config.ocrUnsharp).put("horizontalContextPolicy", config.horizontalContextPolicy.name)
        .put("horizontalCropPolicy", config.horizontalCropPolicy.name)
        .put("stripPad", config.stripPad).put("textHeight", config.textHeight).put("useBicubic", config.useBicubic)
        .put("minProb", config.minProb.toDouble()).put("minTextLength", config.minTextLength)
        .put("ignoreBubble", config.ignoreBubble).put("concurrent", config.concurrent)
        .put("concurrency", config.concurrency).put("ncnnFp16Storage", config.ncnnFp16Storage)
        .put("ncnnFp16Arith", config.ncnnFp16Arith).put("ncnnMixed", config.ncnnMixed)

    private class HistoricalReference(private val parent: File, basename: String) {
        private val directory = File(parent, basename)
        private val file = File(directory, "result.json")
        val report: JSONObject
        val rows: Map<String, JSONObject>

        init {
            check(basename.matches(Regex("ocr-note-glyph-reference-$UUID_PATTERN")))
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

    private fun rectJson(rect: Rect): JSONObject = JSONObject().put("left", rect.left).put("top", rect.top)
        .put("right", rect.right).put("bottom", rect.bottom).put("width", rect.width()).put("height", rect.height())

    private fun rectQuad(rect: Rect): List<Pt> = listOf(
        Pt(rect.left.toFloat(), rect.top.toFloat()),
        Pt(rect.right.toFloat(), rect.top.toFloat()),
        Pt(rect.right.toFloat(), rect.bottom.toFloat()),
        Pt(rect.left.toFloat(), rect.bottom.toFloat()),
    )

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 220
        const val X = 20f
        const val BASELINE = 130f
        const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        const val REFERENCE_BYTES = 903_253L
        const val REFERENCE_SHA = "ed39996cafe9225ed8bc365390e78e3413c99da909668655e2c079e25d9dad66"
        const val DEFAULT_CONTEXT_SHA = "db1c82f4adad3c48d2755998063c4c7ffc4db906dc1887aa694e40bfa2d0f437"
        val ORIGINAL_IDS = listOf("new-4", "new-10", "new-16", "new-22", "new-28", "new-34", "new-40", "new-46")
        val NOTES = listOf(0x266B, 0x266C)
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
