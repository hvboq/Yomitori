package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.DetectorConfig
import li.joye.yakuyomi.engine.EngineTrace
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.NcnnLoadDiagnosticApi
import li.joye.yakuyomi.engine.NcnnLoadReport
import li.joye.yakuyomi.engine.NcnnNetOptions
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import mihon.core.migration.Migrator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Field
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Real public model methods, opt-in Net ownership transfer, and a fixed ABBA comparison.
 * A collection pass is not an OFF-quality acceptance or a product-default promotion.
 * No Activity, manager factory, settings/credential API, translation server, GC or trim is requested.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalEngineApi::class, NcnnLoadDiagnosticApi::class)
class NativeWinogradQualityDiagnosticTest {
    @Test(timeout = 1_200_000)
    fun recordsPublicModelQualityAndLatencyWithoutPromotion() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("winogradQuality") == "true") { "Explicit Winograd diagnostic opt-in required" }
        val id = args.getString("winogradQualityRunId").orEmpty()
        check(UUID_REGEX.matches(id) && UUID.fromString(id).toString() == id) { "Canonical fresh UUID required" }
        val role = args.getString("winogradQualityRole").orEmpty()
        check(role in setOf("ocr", "detector", "inpainter")) { "Select exactly one model role" }
        Executors.newSingleThreadExecutor { task -> Thread(task, "winograd-public-$role") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    Migrator.await()
                    collect(id, role)
                }
            }
    }

    private suspend fun collect(id: String, role: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit")
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
        val cache = context.cacheDir.canonicalFile
        val output = File(cache, "native-winograd-quality-$id")
        check(cache.isDirectory && !output.exists() && output.mkdir()) { "Never reuse a diagnostic UUID" }
        requirePlain(output, cache, directory = true)
        val images = JSONArray()
        val sessions = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("runId", id).put("role", role)
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("passed", false).put("finalized", false)
            .put("collectionValidationPassed", false).put("candidateGatePassed", false)
            .put("candidateQualityDeterminesCollectionPass", false).put("productionOptionsPromoted", false)
            .put("productionConstructorsInstrumented", false).put("actualKotlinFactoryInvoked", false)
            .put("publicModelMethodsInvoked", true).put("nativeModelsHeldAtOnceByTest", 1)
            .put("networkCallsByTest", 0).put("settingsWrittenByTest", 0).put("credentialApisAccessedByTest", false)
            .put("serviceOrManagerFactoriesCalledByTest", 0).put("modelCopiesOrDownloadsByTest", 0)
            .put("gcOrTrimRequestedByTest", false).put("cpuAffinityChangedByTest", false)
            .put("explicitWarmUpCalls", 0).put("automaticRetries", 0).put("fixedOrder", "ordinary, A, B, B, A")
            .put("conditionA", "productionDefaults").put("conditionB", "winogradOff")
            .put("timingClock", "System.nanoTime / CLOCK_MONOTONIC")
            .put("latencyScope", "Public call only; input preparation, hashes, reports and model loading are outside")
            .put("coldIoOrLongTermPerformanceMeasured", false).put("loadSamplesAreProcessAllocatorNotRssPss", true)
            .put("appMigrationAwaitedButHostMustStabilizeVersionPersistence", true)
            .put("modelOwnership", "Ordinary wrapper or explicit diagnostic detach into an empty wrapper")
            .put("borrowedHandlesStoredInWrappers", false).put("imageFiles", images).put("sessions", sessions)
            .put("indirectSimpleOmpBootstrapReluPossible", true).put("explicitScaffoldLoadsExpected", 4)
        val temporary = linkedMapOf<String, String>()
        val cleanupFailures = mutableListOf<Throwable>()
        var failure: Throwable? = null
        var pins: JSONArray? = null
        var stage = "PREFLIGHT"
        val file = File(output, "result.json")
        fun checkpoint() {
            report.put("stage", stage)
            writeReport(output, report)
        }
        fun phase(value: String) {
            stage = value
            checkpoint()
            sendStatus("WINOGRAD_QUALITY_STAGE", JSONObject().put("runId", id).put("role", role).put("stage", stage))
        }
        try {
            report.put("idleBefore", NativeDiagnosticIdleGuard.requireIdle(context))
            val models = File(context.filesDir.canonicalFile, "models")
            requirePlain(models, context.filesDir.canonicalFile, directory = true)
            pins = modelPins(models)
            report.put("modelsBefore", pins)
            val scaffold = prepareScaffold(output, temporary)
            phase("INPUT_PREPARATION")
            when (role) {
                "ocr" -> compareOcr(context, models, scaffold, report, sessions, ::phase, ::checkpoint)
                "detector" -> compareDetector(context, models, scaffold, output, report, sessions, images, ::phase)
                "inpainter" -> compareInpainter(context, models, scaffold, output, report, sessions, images, ::phase)
            }
            check(sessions.length() == 5)
            check(
                (0 until sessions.length()).all {
                    sessions.getJSONObject(it).optBoolean("wrapperClosedHandleZero", false)
                },
            )
            report.put("idleAfter", NativeDiagnosticIdleGuard.requireIdle(context))
            val after = modelPins(models)
            check(after.toString() == pins.toString()) { "Pinned model files changed" }
            report.put("modelsAfter", after).put("models7Unchanged", true)
                .put("collectionValidationPassed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failedStage", stage).put("failureClass", error.javaClass.name)
        } finally {
            for ((name, expectedSha) in temporary) {
                try {
                    val own = File(output, name)
                    requirePlain(own, output, directory = false)
                    check(digest(own) == expectedSha)
                    check(own.delete() && !own.exists())
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
            if (pins != null) {
                try {
                    check(modelPins(File(context.filesDir.canonicalFile, "models")).toString() == pins.toString())
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
            for (index in 0 until images.length()) {
                try {
                    val image = images.getJSONObject(index)
                    val own = File(output, image.getString("name"))
                    requirePlain(own, output, directory = false)
                    check(own.length() == image.getLong("bytes") && own.length() in 1..MAX_IMAGE_BYTES)
                    check(digest(own) == image.getString("sha256"))
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
            val imageNames = (0 until images.length()).map { images.getJSONObject(it).getString("name") }.toSet()
            val expected = imageNames + "result.json"
            val inventory = output.listFiles().orEmpty().map { it.name }.toSet()
            val inventoryOk = inventory == expected && images.length() == imageNames.size && inventory.size <= 32
            report.put("ownedFinalInventoryMatchesImagesAndReport", inventoryOk)
                .put("finalImagesCanonicalRegularSingleLinkSizeAndShaVerified", cleanupFailures.isEmpty())
                .put("temporaryScaffoldFilesRemoved", cleanupFailures.isEmpty())
                .put("cleanupFailureClasses", JSONArray(cleanupFailures.map { it.javaClass.name }))
            val passed = failure == null && cleanupFailures.isEmpty() && inventoryOk &&
                report.optBoolean("collectionValidationPassed", false)
            report.put("passed", passed).put("finalized", true).put("stage", "FINAL")
            try {
                writeReport(output, report)
                sendStatus(
                    "WINOGRAD_QUALITY",
                    JSONObject().put("runId", id).put("bytes", file.length()).put("sha256", digest(file))
                        .put("passed", passed),
                )
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { error ->
            cleanupFailures.forEach(error::addSuppressed)
            throw error
        }
        check(cleanupFailures.isEmpty() && report.getBoolean("passed")) { "Diagnostic collection or cleanup failed" }
    }

    private suspend fun compareOcr(
        context: Context,
        models: File,
        scaffold: File,
        report: JSONObject,
        sessions: JSONArray,
        phase: (String) -> Unit,
        checkpoint: () -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val manifest = instrumentation.context.assets.open("ocr-right-context/input-manifest.json")
            .use { it.readBytes() }
        check(sha(manifest) == INPUT_MANIFEST_SHA)
        val inputs = OcrRightContextInputs(context, checkNotNull(args.getString("ocrRightContextInput")), manifest)
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val reference = historicalReference(external, checkNotNull(args.getString("winogradQualityReference")))
        val priorRows = reference.getJSONArray("samples")
        val historical = (0 until priorRows.length()).map { priorRows.getJSONObject(it) }
            .associateBy { it.getString("id") }
        val cases = inputs.fixedCases() + rightContextGeneratedCases()
        check(cases.size == 120 && historical.keys == cases.map { it.id }.toSet())
        val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
        check(sha(dictionaryBytes) == DICTIONARY_SHA)
        val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
        check(dictionary.size == 19_264)
        val config = OcrConfig()
        check(config.concurrent && config.concurrency == 8 && config.ignoreBubble == 0)
        check(config.stripPad == 4 && config.textHeight == 48 && config.ncnnMixed && config.ncnnFp16Storage)
        val spec = specification(models, "ocr")
        report.put("inputManifestSha256", INPUT_MANIFEST_SHA).put("historicalReferenceSha256", REFERENCE_SHA)
            .put("historicalReferenceBytes", REFERENCE_BYTES).put("original120Correct", 107)
            .put("ocrDictionarySha256", DICTIONARY_SHA).put("requestedConfig", config.toString())
            .put("ocrMixedSelected", spec.param.name.contains("mixed"))
            .put("singleLinePublicCallsExpected", 600).put("multiLinePublicCallsExpected", 5)
            .put("multilineConfiguredConcurrentPathExercised", true)
            .put("nativeOverlapOrPeakParallelismMeasured", false)
        val baseline = linkedMapOf<String, OcrObservation>()
        var baselineOptions: NcnnNetOptions? = null
        var candidateAccepted = true
        var primaryFailure: Throwable? = null
        try {
            for (index in -1..3) {
                currentCoroutineContext().ensureActive()
                NativeDiagnosticIdleGuard.requireIdle(context)
                val off = index == 1 || index == 2
                phase(if (index < 0) "OCR_ORDINARY" else "OCR_${index}_${condition(off)}")
                val session = openSession("ocr", models, scaffold, dictionary, index >= 0, off)
                session.use {
                    val ocr = session.model as Ocr
                    check(ocr.backend == if (spec.param.name.contains("mixed")) "NCNN-mixed" else "NCNN")
                    val row = session.row(index)
                    val observations = JSONArray().also { row.put("calls", it) }
                    sessions.put(row)
                    if (index < 0) {
                        baselineOptions = session.options
                    } else {
                        requireMatchedOptions(checkNotNull(baselineOptions), session.options, off)
                    }
                    var correct = 0
                    var previousCorrectRegressions = 0
                    var previousCorrectBehaviorChanges = 0
                    var changed = 0
                    val elapsed = mutableListOf<Long>()
                    val singleResults = linkedMapOf<String, OcrObservation>()
                    for ((ordinal, case) in cases.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val prior = historical.getValue(case.id)
                        check(case.expected == prior.getString("expected") && case.group == prior.getString("group"))
                        val drawing = case.open()
                        try {
                            val source = diagnosticPixels(drawing.page)
                            check(drawing.page.width == prior.getInt("sourceWidth"))
                            check(drawing.page.height == prior.getInt("sourceHeight"))
                            check(pixelsSha(source) == prior.getString("sourcePixelsArgbSha256"))
                            check(quadJson(drawing.quad).toString() == prior.getJSONArray("quad").toString())
                            val line = TextLine(drawing.quad.map { Pt(it.x, it.y) }, 1f)
                            val start = System.nanoTime()
                            val kept = ocr.recognize(drawing.page, listOf(line))
                            val nanos = System.nanoTime() - start
                            elapsed += nanos
                            val observed = OcrObservation(line.text, line.direction, line in kept)
                            singleResults[case.id] = observed
                            val expected = prior.getJSONObject("candidatePublic")
                            val historicalValue = OcrObservation(
                                expected.getString("text"),
                                expected.getString("direction"),
                                expected.getBoolean("intentionallyKept"),
                            )
                            val matches = line.text == case.expected
                            if (matches) correct++
                            if (index < 0) {
                                check(observed == historicalValue) {
                                    "Ordinary public OCR did not reproduce the pinned historical result"
                                }
                                baseline[case.id] = observed
                            } else if (!off) {
                                check(observed == baseline.getValue(case.id)) {
                                    "Diagnostic default changed public OCR"
                                }
                            }
                            val original = if (index < 0) observed else baseline.getValue(case.id)
                            if (observed != original) changed++
                            val regression = original.text == case.expected && !matches
                            if (regression) previousCorrectRegressions++
                            if (original.text == case.expected && observed != original) previousCorrectBehaviorChanges++
                            check(source.contentEquals(diagnosticPixels(drawing.page)) && drawing.quad == line.quad)
                            observations.put(
                                JSONObject().put("id", case.id)
                                    .put("group", case.group)
                                    .put("textSha256", sha(line.text.toByteArray()))
                                    .put("expectedSha256", sha(case.expected.toByteArray()))
                                    .put("direction", line.direction)
                                    .put("intentionallyKept", line in kept).put("matchesGroundTruth", matches)
                                    .put("sameAsOrdinaryPublic", observed == original)
                                    .put("previousCorrectRegression", regression)
                                    .put("elapsedNs", nanos).put("sourceAndQuadUnchanged", true),
                            )
                            if ((ordinal + 1) % 10 == 0) checkpoint()
                        } finally {
                            drawing.page.recycle()
                        }
                    }
                    check(observations.length() == 120)
                    if (index < 0) check(correct == 107 && baseline.size == 120)
                    if (off && previousCorrectBehaviorChanges != 0) candidateAccepted = false
                    row.put("correct", correct).put("previousCorrectRegressions", previousCorrectRegressions)
                        .put("previousCorrectTextDirectionOrKeepChanges", previousCorrectBehaviorChanges)
                        .put("changedPublicResults", changed).put("latency", latency(elapsed))
                        .put("ordinaryAndDiagnosticDefaultParityRequired", !off)
                    row.put("multiLineCall", compareTwoLineOcr(ocr, cases, singleResults))
                }
                check(session.closed)
            }
            report.put("baselineHistoricalGatePassed", true).put("ordinaryDiagnosticDefaultParityPassed", true)
                .put("candidateGatePassed", candidateAccepted)
                .put("candidateGate", "OFF preserves text/direction/keep for the original107 correct cases")
                .put("singleLinePublicOcrCallsCompleted", 600).put("multiLinePublicOcrCallsCompleted", 5)
                .put("publicOcrCallsCompleted", 605)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                inputs.verifyUnchanged()
                historicalReference(external, checkNotNull(args.getString("winogradQualityReference")))
            } catch (error: Throwable) {
                val original = primaryFailure
                if (original == null) throw error else original.addSuppressed(error)
            }
        }
    }

    private data class OcrObservation(val text: String, val direction: String, val kept: Boolean)

    private suspend fun compareTwoLineOcr(
        ocr: Ocr,
        cases: List<OcrRightContextCase>,
        singleResults: Map<String, OcrObservation>,
    ): JSONObject {
        val selected = listOf("saved-001-0", "saved-001-1").map { id -> cases.single { it.id == id } }
        val drawing = selected[0].open()
        try {
            val source = diagnosticPixels(drawing.page)
            val second = selected[1].open()
            val secondQuad = try {
                check(drawing.page.width == second.page.width && drawing.page.height == second.page.height)
                check(source.contentEquals(diagnosticPixels(second.page))) {
                    "Concurrent OCR requires the same saved page"
                }
                second.quad.map { Pt(it.x, it.y) }
            } finally {
                second.page.recycle()
            }
            val lines = listOf(TextLine(drawing.quad.map { Pt(it.x, it.y) }, 1f), TextLine(secondQuad, 1f))
            val originalQuads = lines.map { it.quad.toList() }
            val start = System.nanoTime()
            val kept = ocr.recognize(drawing.page, lines)
            val elapsed = System.nanoTime() - start
            check(source.contentEquals(diagnosticPixels(drawing.page)))
            check(lines.indices.all { lines[it].quad == originalQuads[it] })
            val results = lines.mapIndexed { index, line ->
                val observed = OcrObservation(line.text, line.direction, line in kept)
                check(observed == singleResults.getValue(selected[index].id)) {
                    "Concurrent public OCR changed a single-line result"
                }
                JSONObject().put("id", selected[index].id).put("textSha256", sha(line.text.toByteArray()))
                    .put("direction", line.direction).put("intentionallyKept", line in kept)
                    .put("matchesSameSessionSingleLineResult", true)
            }
            return JSONObject().put("lines", 2).put("elapsedNs", elapsed).put("results", JSONArray(results))
                .put("sourceAndQuadsUnchanged", true).put("sameNativeNetAsSingleLineCalls", true)
                .put("nativeForwardOrOverlapCountMeasured", false)
        } finally {
            drawing.page.recycle()
        }
    }

    private suspend fun compareDetector(
        context: Context,
        models: File,
        scaffold: File,
        output: File,
        report: JSONObject,
        sessions: JSONArray,
        images: JSONArray,
        phase: (String) -> Unit,
    ) {
        check(DetectorConfig().dbnetInputSize == 1024)
        val baseline = linkedMapOf<String, DetectorObservation>()
        var baselineOptions: NcnnNetOptions? = null
        var accepted = true
        report.put("detectorInputSize", 1024).put("fixtureCount", 2).put("repetitionsPerFixturePerNet", 3)
            .put("publicDetectorCallsExpected", 30).put("actualDetectorChwShapeRequired", "768x1024")
            .put("detectorNativeTraceObserverEnabled", true)
        for (index in -1..3) {
            NativeDiagnosticIdleGuard.requireIdle(context)
            val off = index == 1 || index == 2
            phase(if (index < 0) "DETECTOR_ORDINARY" else "DETECTOR_${index}_${condition(off)}")
            val session = openSession("detector", models, scaffold, emptyList(), index >= 0, off)
            session.use {
                val detector = session.model as Detector
                val row = session.row(index)
                val calls = JSONArray().also { row.put("calls", it) }
                sessions.put(row)
                if (index < 0) {
                    baselineOptions = session.options
                } else {
                    requireMatchedOptions(checkNotNull(baselineOptions), session.options, off)
                }
                val times = mutableListOf<Long>()
                for (fixture in listOf("vertical", "horizontal")) {
                    val page = detectorPage(fixture)
                    try {
                        val source = diagnosticPixels(page)
                        val sourceSha = pixelsSha(source)
                        check(page.width == 1200 && page.height == 1600)
                        if (index < 0) saveImage(output, "detector-$fixture-source.png", page, images)
                        repeat(3) { repetition ->
                            currentCoroutineContext().ensureActive()
                            val key = "$fixture-$repetition"
                            val measured = tracedNative("detectDbnet", "768x1024") { detector.detect(page) }
                            times += measured.elapsedNs
                            val detection = measured.value
                            try {
                                val mask = diagnosticPixels(detection.textMask)
                                check(detection.textMask.width == page.width)
                                check(detection.textMask.height == page.height)
                                check(detection.lines.isNotEmpty() && detection.lines.size <= 32)
                                if (fixture == "vertical") check(detection.lines.size == 4)
                                val geometry = JSONArray(
                                    detection.lines.map { line ->
                                        JSONObject().put(
                                            "quadBits",
                                            JSONArray(
                                                line.quad.map {
                                                    JSONArray(listOf(it.x.toRawBits(), it.y.toRawBits()))
                                                },
                                            ),
                                        ).put("scoreBits", line.score.toRawBits()).put("direction", line.direction)
                                    },
                                ).toString()
                                val observed = DetectorObservation(geometry, mask, detection.lines.size)
                                if (index < 0) baseline[key] = observed
                                val original = baseline.getValue(key)
                                val exact = geometry == original.geometry && mask.contentEquals(original.mask)
                                if (index >= 0 && !off) {
                                    check(exact) {
                                        "Diagnostic default changed public detector output"
                                    }
                                }
                                if (off && !exact) accepted = false
                                val intersection = mask.indices.count {
                                    (mask[it] and 255) > 127 && (original.mask[it] and 255) > 127
                                }
                                val union = mask.indices.count {
                                    (mask[it] and 255) > 127 || (original.mask[it] and 255) > 127
                                }
                                check(union > 0)
                                calls.put(
                                    JSONObject().put("id", key).put("sourceArgbSha256", sourceSha)
                                        .put("publicLineCount", detection.lines.size)
                                        .put("ordinaryLineCount", original.lines)
                                        .put("geometry", JSONArray(geometry))
                                        .put("geometrySha256", sha(geometry.toByteArray()))
                                        .put("maskArgbSha256", pixelsSha(mask))
                                        .put("maskOnPixels", mask.count { (it and 255) > 127 })
                                        .put("sameLineCountAsOrdinary", detection.lines.size == original.lines)
                                        .put("exactGeometryAsOrdinary", geometry == original.geometry)
                                        .put("exactMaskAsOrdinary", mask.contentEquals(original.mask))
                                        .put("maskIntersectionPixels", intersection).put("maskUnionPixels", union)
                                        .put("maskIntersectionOverUnion", intersection.toDouble() / union)
                                        .put("elapsedNs", measured.elapsedNs).put("nativeTrace", measured.trace),
                                )
                                check(source.contentEquals(diagnosticPixels(page))) {
                                    "Detector changed its input page"
                                }
                                if (repetition == 0) {
                                    val label = if (index < 0) "ordinary" else "round-$index"
                                    saveImage(output, "detector-$fixture-$label-mask.png", detection.textMask, images)
                                }
                            } finally {
                                detection.textMask.recycle()
                            }
                        }
                    } finally {
                        page.recycle()
                    }
                }
                check(calls.length() == 6)
                row.put("latency", latency(times))
            }
            check(session.closed)
        }
        report.put("ordinaryDiagnosticDefaultParityPassed", true).put("publicDetectorCallsCompleted", 30)
            .put("candidateGatePassed", accepted)
            .put("candidateGate", "Both OFF rounds preserve exact public geometry and segmentation masks")
    }

    private suspend fun compareInpainter(
        context: Context,
        models: File,
        scaffold: File,
        output: File,
        report: JSONObject,
        sessions: JSONArray,
        images: JSONArray,
        phase: (String) -> Unit,
    ) {
        val config = InpainterConfig()
        check(config.method == "aot" && config.tileSize == 768 && config.maskDilate == 24f && config.bboxPad == 16)
        val baseline = linkedMapOf<String, InpaintObservation>()
        var baselineOptions: NcnnNetOptions? = null
        var accepted = true
        var ordinaryRepeatedOutputsExact = true
        val ordinaryRepeatObservations = JSONArray()
        report.put("inpainterConfig", config.toString()).put("fixtureCount", 2).put("repetitionsPerFixturePerNet", 2)
            .put("publicInpaintCallsExpected", 20).put("actualAotSideRequired", 768)
            .put("inpainterNativeTraceObserverEnabled", true).put("oneAotForwardRequiredPerPublicCall", true)
            .put("flatShortcutCallsAcceptedAsAotTiming", false)
            .put("knownCleanReferenceGeneratedBeforeCorruption", true)
            .put("ordinarySameNetRepeatObservations", ordinaryRepeatObservations)
            .put("ordinaryRepeatObservationChangesParityOracle", false)
            .put("inpaintOutputEvidencePolicy", "Save each observed full-size output before unchanged hard gates")
        for (index in -1..3) {
            NativeDiagnosticIdleGuard.requireIdle(context)
            val off = index == 1 || index == 2
            phase(if (index < 0) "INPAINTER_ORDINARY" else "INPAINTER_${index}_${condition(off)}")
            val session = openSession("inpainter", models, scaffold, emptyList(), index >= 0, off)
            session.use {
                val inpainter = session.model as Inpainter
                val row = session.row(index)
                val calls = JSONArray().also { row.put("calls", it) }
                sessions.put(row)
                if (index < 0) {
                    baselineOptions = session.options
                } else {
                    requireMatchedOptions(checkNotNull(baselineOptions), session.options, off)
                }
                val times = mutableListOf<Long>()
                for (name in listOf("texture", "gradient")) {
                    val fixture = inpaintFixture(name)
                    fixture.use {
                        val before = diagnosticPixels(fixture.source)
                        val clean = diagnosticPixels(fixture.clean)
                        val tight = diagnosticPixels(fixture.segmentation)
                        repeat(2) { repetition ->
                            currentCoroutineContext().ensureActive()
                            val key = "$name-$repetition"
                            val regions = fixture.regions()
                            val removal = inpainter.buildMask(fixture.source, regions, fixture.segmentation)
                            try {
                                val mask = diagnosticPixels(removal)
                                val active = mask.indices.filter { (mask[it] and 255) > 127 }
                                check(active.size > 2000)
                                check(mask.indices.all { !fixture.protected(it) || (mask[it] and 255) == 0 })
                                if (index < 0 && repetition == 0) {
                                    saveImage(output, "inpaint-$name-source.png", fixture.source, images)
                                    saveImage(output, "inpaint-$name-clean-reference.png", fixture.clean, images)
                                    saveImage(output, "inpaint-$name-removal.png", removal, images)
                                }
                                val measured = tracedNative("inpaint", "s=768") {
                                    inpainter.inpaint(fixture.source, regions, fixture.segmentation)
                                }
                                times += measured.elapsedNs
                                val result = measured.value
                                try {
                                    val pixels = diagnosticPixels(result)
                                    check(result.width == fixture.source.width)
                                    check(result.height == fixture.source.height)
                                    val sourceAfter = diagnosticPixels(fixture.source)
                                    val cleanAfter = diagnosticPixels(fixture.clean)
                                    val segmentationAfter = diagnosticPixels(fixture.segmentation)
                                    val removalAfter = diagnosticPixels(removal)
                                    val sourceUnchanged = before.contentEquals(sourceAfter)
                                    val cleanUnchanged = clean.contentEquals(cleanAfter)
                                    val segmentationUnchanged = tight.contentEquals(segmentationAfter)
                                    val removalUnchanged = mask.contentEquals(removalAfter)
                                    val outsideExact = pixels.indices.all {
                                        (mask[it] and 255) > 127 || pixels[it] == before[it]
                                    }
                                    val protectedExact = pixels.indices.all {
                                        !fixture.protected(it) || pixels[it] == before[it]
                                    }
                                    val metric = maskedDifference(pixels, clean, mask)
                                    val observed = InpaintObservation(pixels, metric)
                                    if (index < 0) baseline[key] = observed
                                    val original = baseline.getValue(key)
                                    val exact = pixels.contentEquals(original.pixels)
                                    val noCleanRegression = metric.meanAbsolute <= original.metric.meanAbsolute
                                    if (off && !noCleanRegression) accepted = false
                                    val versusOrdinary = maskedDifference(pixels, original.pixels, mask)
                                    val versusInput = maskedDifference(pixels, before, mask)
                                    if (index < 0 && repetition > 0) {
                                        val first = baseline.getValue("$name-0")
                                        val firstCall = (0 until calls.length()).map { calls.getJSONObject(it) }
                                            .single { it.getString("id") == "$name-0" }
                                        val firstSourceSha = firstCall.getString("sourceArgbSha256")
                                        val firstRemovalSha = firstCall.getString("effectiveRemovalArgbSha256")
                                        val repeatedSourceSha = pixelsSha(before)
                                        val repeatedRemovalSha = pixelsSha(mask)
                                        val repeatedDifference = maskedDifference(pixels, first.pixels, mask)
                                        val repeatedExact = pixels.contentEquals(first.pixels)
                                        ordinaryRepeatedOutputsExact = ordinaryRepeatedOutputsExact && repeatedExact
                                        ordinaryRepeatObservations.put(
                                            JSONObject().put("fixture", name).put("sameNativeNet", true)
                                                .put("firstRepetition", 0).put("repeatedRepetition", repetition)
                                                .put("firstOutputArgbSha256", pixelsSha(first.pixels))
                                                .put("repeatedOutputArgbSha256", pixelsSha(pixels))
                                                .put("firstSourceArgbSha256", firstSourceSha)
                                                .put("repeatedSourceArgbSha256", repeatedSourceSha)
                                                .put("firstRemovalArgbSha256", firstRemovalSha)
                                                .put("repeatedRemovalArgbSha256", repeatedRemovalSha)
                                                .put("sameSourceAsFirst", repeatedSourceSha == firstSourceSha)
                                                .put("sameRemovalAsFirst", repeatedRemovalSha == firstRemovalSha)
                                                .put("exactRepeatedOutput", repeatedExact)
                                                .put("differenceFromFirst", repeatedDifference.json())
                                                .put("firstCleanReferenceError", first.metric.json())
                                                .put("repeatedCleanReferenceError", metric.json()),
                                        )
                                        report.put("ordinarySameNetRepeatabilityObserved", true)
                                            .put(
                                                "ordinarySameNetRepeatObservationsCompleted",
                                                ordinaryRepeatObservations.length(),
                                            )
                                            .put(
                                                "ordinarySameNetRepeatedOutputsExactSoFar",
                                                ordinaryRepeatedOutputsExact,
                                            )
                                    }
                                    val label = if (index < 0) "ordinary" else "round-$index"
                                    val evidenceImage = "inpaint-$name-$label-repeat-$repetition-output.png"
                                    val call = JSONObject().put("id", key).put("sourceArgbSha256", pixelsSha(before))
                                        .put("cleanReferenceArgbSha256", pixelsSha(clean))
                                        .put("segmentationArgbSha256", pixelsSha(tight))
                                        .put("effectiveRemovalArgbSha256", pixelsSha(mask))
                                        .put("removalPixels", active.size)
                                        .put("outputArgbSha256", pixelsSha(pixels))
                                        .put("correspondingOrdinaryOutputArgbSha256", pixelsSha(original.pixels))
                                        .put("outsideMaskExact", outsideExact)
                                        .put("protectedRegionExact", protectedExact)
                                        .put("exactOutputAsOrdinary", exact)
                                        .put("cleanReferenceError", metric.json())
                                        .put("ordinaryCleanReferenceError", original.metric.json())
                                        .put("outputDifferenceFromOrdinary", versusOrdinary.json())
                                        .put("outputDifferenceFromInput", versusInput.json())
                                        .put("cleanReferenceMaeNoHigherThanOrdinary", noCleanRegression)
                                        .put("sourceArgbAfterSha256", pixelsSha(sourceAfter))
                                        .put("cleanReferenceArgbAfterSha256", pixelsSha(cleanAfter))
                                        .put("segmentationArgbAfterSha256", pixelsSha(segmentationAfter))
                                        .put("publicBuildMaskWitnessArgbAfterSha256", pixelsSha(removalAfter))
                                        .put("sourceUnchanged", sourceUnchanged)
                                        .put("cleanReferenceUnchanged", cleanUnchanged)
                                        .put("segmentationUnchanged", segmentationUnchanged)
                                        .put("publicBuildMaskWitnessUnchanged", removalUnchanged)
                                        .put("regionOnArtAfterCall", regions.first().onArt)
                                        .put("publicInputWitnessesCapturedBeforeParityGate", true)
                                        .put("evidenceImage", evidenceImage).put("evidenceImageSaved", false)
                                        .put("elapsedNs", measured.elapsedNs).put("nativeTrace", measured.trace)
                                    calls.put(call)
                                    saveImage(output, evidenceImage, result, images)
                                    call.put("evidenceImageSaved", true)
                                    // Preserve the same hard gates after recording the measured mismatch.
                                    check(regions.first().onArt) { "Uncertain texture must actually use AOT" }
                                    check(outsideExact) {
                                        "Public inpainting changed pixels outside its effective removal mask"
                                    }
                                    check(protectedExact) { "Public inpainting changed the protected region" }
                                    check(sourceUnchanged)
                                    check(cleanUnchanged)
                                    check(segmentationUnchanged)
                                    check(removalUnchanged)
                                    if (index >= 0 && !off) {
                                        check(exact) {
                                            "Diagnostic default changed public inpainting output"
                                        }
                                    }
                                    check(versusInput.changedPixels > 0) {
                                        "AOT fixture must produce an observable reconstruction"
                                    }
                                } finally {
                                    result.recycle()
                                }
                            } finally {
                                removal.recycle()
                            }
                        }
                    }
                }
                check(calls.length() == 4)
                row.put("latency", latency(times))
            }
            check(session.closed)
        }
        report.put("ordinaryDiagnosticDefaultParityPassed", true).put("publicInpaintCallsCompleted", 20)
            .put("candidateGatePassed", accepted)
            .put("candidateGate", "OFF keeps protected/context pixels exact and masked clean-reference MAE no higher")
            .put("crossFrameworkToleranceReused", false)
    }

    private suspend fun <T> tracedNative(
        kind: String,
        dimension: String,
        block: suspend () -> T,
    ): NativeMeasurement<T> {
        val previous = EngineTrace.sink
        var primaryFailure: Throwable? = null
        var retainedValue: Any? = null
        var enters = 0
        var calls = 0
        var exits = 0
        var actualRc: Int? = null
        val own: (String) -> Unit = { message ->
            if (message.startsWith("ncnn.$kind.enter ")) {
                check(message == "ncnn.$kind.enter $dimension")
                enters++
            }
            if (message.startsWith("ncnn.$kind.call ")) {
                check(message == "ncnn.$kind.call $dimension")
                calls++
            }
            if (message.startsWith("ncnn.$kind.exit rc=")) {
                actualRc = message.substringAfter("rc=").toInt()
                exits++
            }
            previous?.invoke(message)
        }
        EngineTrace.sink = own
        return try {
            val start = System.nanoTime()
            val value = block()
            retainedValue = value
            val elapsed = System.nanoTime() - start
            try {
                check(enters == 1 && calls == 1 && exits == 1) { "Expected exactly one completed native forward" }
                check(if (kind == "inpaint") actualRc == 0 else (actualRc ?: 0) > 0)
                NativeMeasurement(
                    value,
                    elapsed,
                    JSONObject().put("kind", kind).put("dimension", dimension)
                        .put("enter", enters).put("call", calls).put("exit", exits).put("rc", actualRc)
                        .put("observerIncludedInTimedPublicCall", true),
                )
            } catch (error: Throwable) {
                when (value) {
                    is Bitmap -> value.recycle()
                    is li.joye.yakuyomi.engine.Detection -> value.textMask.recycle()
                }
                retainedValue = null
                throw error
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            if (EngineTrace.sink === own) {
                EngineTrace.sink = previous
            } else {
                val error = IllegalStateException("Engine trace ownership changed during a measured call")
                when (val value = retainedValue) {
                    is Bitmap -> value.recycle()
                    is li.joye.yakuyomi.engine.Detection -> value.textMask.recycle()
                }
                val original = primaryFailure
                if (original == null) throw error else original.addSuppressed(error)
            }
        }
    }

    private fun detectorPage(name: String): Bitmap {
        if (name == "vertical") {
            val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                .open("native-quality/vertical-punctuation.png").use { it.readBytes() }
            check(sha(bytes) == VERTICAL_SHA)
            return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        check(name == "horizontal")
        return Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.LTGRAY)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 60f }
            fun bubble(top: Float, bottom: Float) {
                paint.color = Color.DKGRAY
                canvas.drawRect(62f, top - 8, 1138f, bottom + 8, paint)
                paint.color = Color.WHITE
                canvas.drawRoundRect(RectF(70f, top, 1130f, bottom), 70f, 70f, paint)
                paint.color = Color.BLACK
            }
            bubble(120f, 650f)
            canvas.drawText("こんにちは。", 200f, 340f, paint)
            canvas.drawText("今日もよろしくお願いします。", 150f, 470f, paint)
            bubble(850f, 1450f)
            canvas.drawText("明日の会議は午後三時です。", 180f, 1080f, paint)
            canvas.drawText("少し待ってください。", 240f, 1210f, paint)
        }
    }

    private fun inpaintFixture(name: String): InpaintFixture {
        check(name in setOf("texture", "gradient"))
        val width = 640
        val height = 800
        val cleanPixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (name == "texture") {
                val hatch = ((x / 5 + y / 7) % 2) * 86
                Color.rgb(70 + hatch + x % 37, 65 + hatch + y % 43, 80 + hatch + (x + y) % 29)
            } else {
                Color.rgb(25 + x * 205 / width, 30 + y * 195 / height, 40 + (x + y) * 180 / (width + height))
            }
        }
        for (y in 338 until 394) {
            for (x in 292 until 340) {
                cleanPixels[y * width + x] = if (x in 310..316 && y in 352..380) Color.BLACK else Color.rgb(35, 95, 210)
            }
        }
        val sourcePixels = cleanPixels.copyOf()
        val mask = IntArray(sourcePixels.size) { Color.BLACK }
        for (y in 305 until 438) {
            for (x in 205 until 445) {
                val ink = (x in 210..224 || x in 263..277 || x in 366..380 || x in 420..434) ||
                    (y in 317..329 || y in 407..419)
                if (ink) {
                    if (!(x in 292 until 340 && y in 338 until 394)) sourcePixels[y * width + x] = Color.BLACK
                    mask[y * width + x] = Color.WHITE
                }
            }
        }
        val source = Bitmap.createBitmap(sourcePixels, width, height, Bitmap.Config.ARGB_8888)
        var clean: Bitmap? = null
        var segmentation: Bitmap? = null
        try {
            val cleanBitmap = Bitmap.createBitmap(cleanPixels, width, height, Bitmap.Config.ARGB_8888)
                .also { clean = it }
            val segmentationBitmap = Bitmap.createBitmap(mask, width, height, Bitmap.Config.ARGB_8888)
                .also { segmentation = it }
            return InpaintFixture(source, cleanBitmap, segmentationBitmap)
        } catch (error: Throwable) {
            source.recycle()
            clean?.recycle()
            segmentation?.recycle()
            throw error
        }
    }

    private class InpaintFixture(val source: Bitmap, val clean: Bitmap, val segmentation: Bitmap) : AutoCloseable {
        fun protected(index: Int): Boolean {
            val x = index % source.width
            val y = index / source.width
            return x in 292 until 340 && y in 338 until 394
        }

        fun regions() = listOf(
            region(190f, 288f, 460f, 456f),
            region(292f, 338f, 340f, 394f).apply {
                preserveOriginal = true
            },
        )

        private fun region(left: Float, top: Float, right: Float, bottom: Float) = TextRegion(
            listOf(TextLine(listOf(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom)), 1f)),
            "h",
        )

        override fun close() {
            source.recycle()
            clean.recycle()
            segmentation.recycle()
        }
    }

    private fun maskedDifference(left: IntArray, right: IntArray, mask: IntArray): PixelDifference {
        check(left.size == right.size && left.size == mask.size)
        var sum = 0L
        var changed = 0
        var maximum = 0
        var count = 0
        for (index in mask.indices) {
            if ((mask[index] and 255) <= 127) continue
            count++
            if (left[index] != right[index]) changed++
            for (shift in RGB_SHIFTS) {
                val delta = abs(((left[index] ushr shift) and 255) - ((right[index] ushr shift) and 255))
                sum += delta
                maximum = max(maximum, delta)
            }
        }
        check(count > 0)
        return PixelDifference(sum.toDouble() / (3L * count), maximum, changed, count)
    }

    private data class DetectorObservation(val geometry: String, val mask: IntArray, val lines: Int)
    private data class InpaintObservation(val pixels: IntArray, val metric: PixelDifference)
    private data class NativeMeasurement<T>(val value: T, val elapsedNs: Long, val trace: JSONObject)
    private data class PixelDifference(
        val meanAbsolute: Double,
        val maxAbsolute: Int,
        val changedPixels: Int,
        val maskPixels: Int,
    ) {
        fun json() = JSONObject().put("meanAbsoluteRgb0To255", meanAbsolute).put("maxAbsoluteRgb0To255", maxAbsolute)
            .put("changedArgbPixels", changedPixels).put("maskPixels", maskPixels)
    }

    private fun historicalReference(parent: File, name: String): JSONObject {
        check(name.matches(Regex("winograd-quality-reference-$UUID_PATTERN")))
        val directory = File(parent, name)
        requirePlain(directory, parent, directory = true)
        check(directory.list()?.toSet() == setOf("result.json"))
        val file = File(directory, "result.json")
        requirePlain(file, directory, directory = false)
        check(file.length() == REFERENCE_BYTES && digest(file) == REFERENCE_SHA)
        val report = JSONObject(file.readText(Charsets.UTF_8))
        check(report.getBoolean("passed") && report.getBoolean("accuracyGatePassed"))
        check(report.getString("candidatePolicy") == "GUARDED_32" && report.getJSONArray("samples").length() == 120)
        return report
    }

    private fun openSession(
        role: String,
        models: File,
        scaffold: File,
        dictionary: List<String>,
        diagnostic: Boolean,
        off: Boolean,
    ): Session {
        val spec = specification(models, role)
        val initialPath = if (diagnostic) scaffold.path else File(models, spec.wrapperParamName).path
        val constructorStart = System.nanoTime()
        val model: AutoCloseable = when (role) {
            "ocr" -> Ocr(initialPath, dictionary, OcrConfig())
            "detector" -> Detector(initialPath, DetectorConfig())
            "inpainter" -> Inpainter(initialPath, InpainterConfig())
            else -> error("Unsupported role")
        }
        val constructorNs = System.nanoTime() - constructorStart
        try {
            val field = model.javaClass.getDeclaredField("ncnnHandle").apply { isAccessible = true }
            check(field.getLong(model) != 0L)
            if (!diagnostic) {
                val options = NcnnBackend.readNetOptionsDiagnostic(field.getLong(model))
                if (role == "inpainter") check(options.numThreads == 1)
                return Session(model, field, spec, options, constructorNs, null, 0L, false, off)
            }
            model.close()
            check(field.getLong(model) == 0L) { "The wrapper must be empty before adopting a diagnostic Net" }
            val start = System.nanoTime()
            val owner = NcnnBackend.createNetLoadDiagnostic(
                spec.param.path,
                spec.bin.path,
                spec.threads,
                true,
                true,
                role == "ocr" || role == "inpainter",
                off,
            )
            val loadNs = System.nanoTime() - start
            owner.use {
                val load = owner.loadReport
                check(load.loaded && load.paramResult == 0 && load.modelResult == 0)
                check(load.headerVersionNumber == NCNN_VERSION && load.linkedVersionNumber == NCNN_VERSION)
                val options = owner.withHandle { NcnnBackend.readNetOptionsDiagnostic(it) }
                check(options == load.snapshots.last().options && !options.useLocalPoolAllocator)
                if (role == "ocr" || role == "inpainter") check(options.numThreads == 1)
                var pending = owner.detachHandle()
                try {
                    field.setLong(model, pending)
                    pending = 0L // Primitive assignment follows the only throwing handoff operation.
                } finally {
                    if (pending != 0L) NcnnBackend.releaseNet(pending)
                }
                check(!owner.isOpen && field.getLong(model) != 0L)
                return Session(model, field, spec, options, constructorNs, load, loadNs, true, off)
            }
        } catch (error: Throwable) {
            try {
                model.close()
            } catch (closing: Throwable) {
                error.addSuppressed(closing)
            }
            throw error
        }
    }

    private inner class Session(
        val model: AutoCloseable,
        private val field: Field,
        private val spec: Spec,
        val options: NcnnNetOptions,
        private val constructorNs: Long,
        private val load: NcnnLoadReport?,
        private val loadNs: Long,
        private val detached: Boolean,
        private val off: Boolean,
    ) : AutoCloseable {
        var closed = false
            private set
        private var metadata: JSONObject? = null

        fun row(index: Int) = JSONObject().put("index", index)
            .put("condition", if (index < 0) "ordinaryProduction" else condition(off))
            .put("constructorNs", constructorNs).put("constructorUsesTinyScaffold", detached)
            .put("realDiagnosticLoadNs", if (detached) loadNs else JSONObject.NULL)
            .put("diagnosticLoad", load?.json() ?: JSONObject.NULL).put("actualOptions", options.json())
            .put("handleDetachedAndAdopted", detached).put("wrapperSoleOwnerDuringPublicCalls", true)
            .put("actualNetParamName", spec.param.name)
            .put("actualNetParamSha256", MODEL_PINS.single { it.name == spec.param.name }.sha256)
            .put("actualNetBinName", spec.bin.name)
            .put("actualNetBinSha256", MODEL_PINS.single { it.name == spec.bin.name }.sha256)
            .put("wrapperConstructorPathContextMatchesRealNet", !detached).put("wrapperClosedHandleZero", false)
            .also { metadata = it }

        override fun close() {
            if (closed) return
            model.close()
            check(field.getLong(model) == 0L) { "Wrapper close did not clear the adopted handle" }
            closed = true
            metadata?.put("wrapperClosedHandleZero", true)
        }
    }

    private fun specification(models: File, role: String): Spec = when (role) {
        "ocr" -> {
            val base = File(models, "ocr_48px_ctc.ncnn.param")
            val mixed = File(models, "ocr_48px_ctc_mixed.ncnn.param")
            val config = OcrConfig()
            val canMixed = config.ncnnMixed && config.ncnnFp16Storage && NcnnBackend.cpuSupportsFp16 && mixed.exists()
            val selected = if (canMixed) mixed else base
            Spec(selected, File(models, "ocr_48px_ctc.ncnn.bin"), base.name, 1)
        }
        "detector" -> Spec(
            File(models, "dbnet_detect.ncnn.param"),
            File(models, "dbnet_detect.ncnn.bin"),
            "dbnet_detect.ncnn.param",
        )
        "inpainter" -> Spec(
            File(models, "mit_aot_fixed512.ncnn.param"),
            File(models, "mit_aot_fixed512.ncnn.bin"),
            "mit_aot_fixed512.ncnn.param",
            1,
        )
        else -> error("Unsupported model")
    }

    private fun prepareScaffold(output: File, temporary: MutableMap<String, String>): File {
        val bytes = TINY_PARAM.toByteArray(Charsets.UTF_8)
        val files = listOf(
            "scaffold.ncnn.param" to bytes,
            "scaffold_mixed.ncnn.param" to bytes,
            "scaffold.ncnn.bin" to byteArrayOf(),
        )
        for ((name, content) in files) {
            val file = File(output, name)
            check(!file.exists())
            temporary[name] = sha(content)
            file.outputStream().use { it.write(content) }
            requirePlain(file, output, directory = false)
            check(digest(file) == temporary[name])
        }
        return File(output, "scaffold.ncnn.param")
    }

    private fun requireMatchedOptions(baseline: NcnnNetOptions, actual: NcnnNetOptions, off: Boolean) {
        check(
            actual == baseline.copy(
                useWinogradConvolution = if (off) false else baseline.useWinogradConvolution,
                useA53A55OptimizedKernel = actual.useA53A55OptimizedKernel,
            ),
        ) { "Unexpected actual Net option change" }
    }

    private fun latency(values: List<Long>): JSONObject {
        check(values.isNotEmpty() && values.all { it > 0 })
        fun summary(rows: List<Long>): JSONObject {
            if (rows.isEmpty()) return JSONObject().put("count", 0)
            val sorted = rows.sorted()
            return JSONObject().put("count", rows.size).put("minimumNs", sorted.first()).put("maximumNs", sorted.last())
                .put("medianNs", sorted[sorted.size / 2]).put("meanNs", rows.sum().toDouble() / rows.size)
        }
        return JSONObject().put("firstPublicCallNs", values.first())
            .put("followingPublicCalls", summary(values.drop(1)))
            .put("allPublicCalls", summary(values))
    }

    private fun modelPins(directory: File): JSONArray = JSONArray(
        MODEL_PINS.map { pin ->
            val file = File(directory, pin.name)
            requirePlain(file, directory, directory = false)
            check(file.length() == pin.bytes && digest(file) == pin.sha256)
            JSONObject().put("name", pin.name).put("bytes", pin.bytes).put("sha256", pin.sha256)
        },
    )

    private fun requirePlain(file: File, parent: File, directory: Boolean) {
        check(file.canonicalFile == file.absoluteFile && file.parentFile == parent.canonicalFile)
        val status = Os.lstat(file.path)
        check(if (directory) OsConstants.S_ISDIR(status.st_mode) else OsConstants.S_ISREG(status.st_mode))
        if (!directory) check(status.st_nlink == 1L)
    }

    private fun writeReport(directory: File, report: JSONObject) {
        val file = File(directory, "result.json")
        if (file.exists()) requirePlain(file, directory, directory = false)
        val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..MAX_REPORT_BYTES)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        requirePlain(file, directory, directory = false)
        check(file.length() == bytes.size.toLong() && digest(file) == sha(bytes))
    }

    private fun saveImage(directory: File, name: String, bitmap: Bitmap, images: JSONArray) {
        check(name.matches(Regex("[a-z0-9-]+\\.png")) && images.length() < 31)
        val file = File(directory, name)
        check(!file.exists())
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        requirePlain(file, directory, directory = false)
        check(file.length() in 1..MAX_IMAGE_BYTES)
        images.put(JSONObject().put("name", name).put("bytes", file.length()).put("sha256", digest(file)))
    }

    private fun sendStatus(label: String, value: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "$label=$value") },
        )
    }

    private fun NcnnLoadReport.json() = JSONObject().put("headerVersionNumber", headerVersionNumber)
        .put("linkedVersionNumber", linkedVersionNumber).put("loaded", loaded).put("paramResult", paramResult)
        .put("modelResult", modelResult)
        .put("winogradDisabled", winogradDisabled)
        .put("usedExtendedOptions", usedExtendedOptions)
        .put(
            "snapshots",
            JSONArray(
                snapshots.map {
                    JSONObject().put("stage", it.stage.name)
                        .put("startedAtNs", it.startedAtNs)
                        .put("finishedAtNs", it.finishedAtNs)
                        .put("sampleOverheadNs", it.sampleOverheadNs)
                        .put("allocatedBytes", it.allocatedBytes)
                        .put("arenaBytes", it.arenaBytes)
                        .put("mappedBytes", it.mappedBytes)
                        .put("freeBytes", it.freeBytes)
                        .put("options", it.options?.json() ?: JSONObject.NULL)
                },
            ),
        )

    private fun NcnnNetOptions.json() = JSONObject().put("numThreads", numThreads)
        .put("openmpBlocktime", openmpBlocktime)
        .put("lightmode", lightmode).put("useWinogradConvolution", useWinogradConvolution)
        .put("useWinograd23Convolution", useWinograd23Convolution)
        .put("useWinograd43Convolution", useWinograd43Convolution)
        .put("useWinograd63Convolution", useWinograd63Convolution).put("useSgemmConvolution", useSgemmConvolution)
        .put("usePackingLayout", usePackingLayout)
        .put("useFp16Packed", useFp16Packed)
        .put("useFp16Storage", useFp16Storage)
        .put("useFp16Arithmetic", useFp16Arithmetic)
        .put("useBf16Storage", useBf16Storage)
        .put("useBf16Packed", useBf16Packed)
        .put("useInt8Inference", useInt8Inference)
        .put("useInt8Packed", useInt8Packed)
        .put("useInt8Storage", useInt8Storage)
        .put("useInt8Arithmetic", useInt8Arithmetic).put("useLocalPoolAllocator", useLocalPoolAllocator)
        .put("useMappedModelLoading", useMappedModelLoading).put("useA53A55OptimizedKernel", useA53A55OptimizedKernel)
        .put("useVulkanCompute", useVulkanCompute)

    private fun digest(file: File): String = file.inputStream().use { input ->
        val length = file.length()
        check(length in 0..MAX_FILE_BYTES)
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var consumed = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            consumed += count
            check(consumed <= length)
            hash.update(buffer, 0, count)
        }
        check(consumed == length && file.length() == length)
        hex(hash.digest())
    }

    private fun pixelsSha(pixels: IntArray): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4096)
        var used = 0
        for (pixel in pixels) {
            bytes[used++] = (pixel ushr 24).toByte()
            bytes[used++] = (pixel ushr 16).toByte()
            bytes[used++] = (pixel ushr 8).toByte()
            bytes[used++] = pixel.toByte()
            if (used == bytes.size) {
                hash.update(bytes)
                used = 0
            }
        }
        hash.update(bytes, 0, used)
        return hex(hash.digest())
    }

    private fun quadJson(quad: List<Pt>) = JSONArray(quad.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) })
    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun condition(off: Boolean) = if (off) "winogradOff" else "productionDefaults"

    private data class Spec(val param: File, val bin: File, val wrapperParamName: String, val threads: Int = 0)
    private data class ModelPin(val name: String, val bytes: Long, val sha256: String)

    private companion object {
        const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        val UUID_REGEX = Regex(UUID_PATTERN)
        const val MAX_REPORT_BYTES = 1024 * 1024
        const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
        const val MAX_FILE_BYTES = 192L * 1024 * 1024
        const val NCNN_VERSION = 20261002
        const val INPUT_MANIFEST_SHA = "3178bfe3563ea01ce40423d7f9fb7579160797b42c190d416b29cb0c36ec015a"
        const val REFERENCE_BYTES = 903_253L
        const val REFERENCE_SHA = "ed39996cafe9225ed8bc365390e78e3413c99da909668655e2c079e25d9dad66"
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        const val VERTICAL_SHA = "281ffb1cc92aa692bddf6ea1429ecbb32a3ea6cfc974c09329154abd628010f3"
        const val TINY_PARAM = "7767517\n2 2\nInput input 0 1 in0\nReLU relu 1 1 in0 out0 0=0\n"
        val RGB_SHIFTS = intArrayOf(16, 8, 0)
        val MODEL_PINS = listOf(
            ModelPin(
                "dbnet_detect.ncnn.param",
                13392,
                "9e6db2f8c6b0662ab00eb2100b3373d3c984a235eaac0e61c0b2a484ee1ff7b5",
            ),
            ModelPin(
                "dbnet_detect.ncnn.bin",
                153010556,
                "f57bdbede7764a534c56e88be0269602259a7fcd47e54e8b7d954fd0fcc55c3d",
            ),
            ModelPin(
                "ocr_48px_ctc.ncnn.param",
                18133,
                "e701cfc5df9d3c55c9fd0a36725499d01d45c18e32946271fc26e580cd9901bd",
            ),
            ModelPin(
                "ocr_48px_ctc_mixed.ncnn.param",
                18438,
                "32e298deca3acb8ba95897ca8cccbd028582ae42a697c3c05b4eaefe198309e3",
            ),
            ModelPin(
                "ocr_48px_ctc.ncnn.bin",
                83037664,
                "3e0a809441f5284871d18a3d757a7ec098c4b9ee64bdfd7ac776a84aa057c7a9",
            ),
            ModelPin(
                "mit_aot_fixed512.ncnn.param",
                33810,
                "f21ef860d21a6cdf60dfb1742c08d1c0d98837bceeb2ee3fe9c2dbbeee7d32b5",
            ),
            ModelPin(
                "mit_aot_fixed512.ncnn.bin",
                11366088,
                "a52db45eafc1dd2aa4ce9a339c711917fa98fefb31ce4506d4c95e8b5e3560b6",
            ),
        )
    }
}
