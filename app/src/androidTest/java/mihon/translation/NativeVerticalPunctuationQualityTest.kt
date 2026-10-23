package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Grouping
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.LlmModels
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.ModelSource
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrRecognizer
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.Renderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.TranslatorConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Explicitly selected ARM64/audit quality test; never uses a user's manga, credentials or preferences.
 * Real DBNet -> real NCNN OCR -> real LlmTranslator -> removal -> renderer -> app persistence.
 * This is an internal pipeline/storage test, not an Activity, public-menu or signed-release test.
 */
@RunWith(AndroidJUnit4::class)
class NativeVerticalPunctuationQualityTest {
    @Test
    fun realVerticalColumnsKeepPunctuationAndBecomeBoundedKoreanBalloons() = runBlocking<Unit>(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("nativeVerticalQuality") == "true") {
            "Explicit nativeVerticalQuality=true is required; missing opt-in is not a pass or skip"
        }
        val base = arguments.getString("liveTranslationBase").orEmpty()
        val model = arguments.getString("liveTranslationModel").orEmpty()
        check(base.isNotBlank() && model.isNotBlank()) {
            "Explicit liveTranslationBase and liveTranslationModel are required"
        }
        val retain = arguments.getString("nativeVerticalKeepArtifacts")?.let {
            check(it == "true" || it == "false") { "nativeVerticalKeepArtifacts must be true or false" }
            it == "true"
        } ?: false
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation is mandatory" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Actual ARM64 process is required" }
        val shared = Injekt.get<TranslationEngineService>()
        requireSharedIdle(shared)

        val directory = File(context.getExternalFilesDir(null), "native-vertical-quality-${UUID.randomUUID()}")
        check(directory.mkdir()) { "Cannot create owned quality-report directory" }
        val chapterDirectory = File(directory, "chapter").apply { check(mkdir()) }
        val reportFile = File(directory, "result.json")
        val checks = JSONArray()
        val batches = JSONArray()
        val ocrCalls = JSONArray()
        val report = JSONObject()
            .put("passed", false).put("targetPackage", context.packageName)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)
            .put("model", model).put("apiKeyUsed", false).put("serverAddressWritten", false)
            .put("globalPreferencesChanged", false).put("sharedEngineShutdownCalled", false)
            .put("nativeOcrEnabledByOptIn", true).put("nativeOcrCallStarted", false)
            .put("nativeOcrCallCompleted", false).put("translationPostProceedAttempted", false)
            .put("modelListCallStarted", false).put("modelListCallCompleted", false)
            .put("actualReaderActivity", false).put("signedRelease", false)
            .put(
                "targetMeaningCheckScope",
                "Required Korean semantic fragments only; fluent translation, quote integrity " +
                    "and consistent politeness require separate review",
            )
            .put("fixture", "CC0 fixed PNG; Meiryo 72px; four vertical columns; two balloons")
            .put("expectedLeft", EXPECTED_LEFT).put("expectedRight", EXPECTED_RIGHT)
            .put("inputSha256", INPUT_SHA).put("cleanReferenceSha256", CLEAN_SHA)
            .put("punctuationGroundTruth", "300C/300D/3001/3002 drawn with FE41/FE42/FE11/FE12 vertical font forms")
            .put(
                "ocrObservation",
                "Unmodified native recognizer output after production correction; not raw CTC logits",
            )
            .put(
                "readingOrderScope",
                "Top-to-bottom in columns; right-to-left within each balloon; " +
                    "no assumed global region order",
            )
            .put(
                "wireObservationScope",
                "Read-only application interceptor on the new translator only; " +
                    "execute attempts, not socket retries",
            )
            .put(
                "timingScope",
                "Quality diagnostics include observation/checkpoint overhead; not a performance benchmark",
            )
            .put(
                "repeatRenderScope",
                "Pixel consistency check only; independent quality uses the fixed clean reference " +
                    "and declared source, semantic targets and pixel bands",
            )
            .put("logicalPageAttempts", 0).put("ocrCalls", ocrCalls).put("httpExecuteAttempts", batches)
            .put("checks", checks).put("artifactRetentionRequested", retain)
        fun checkpoint() {
            val temporary = File(directory, "result.json.tmp")
            temporary.writeText(report.toString(2), Charsets.UTF_8)
            check(temporary.renameTo(reportFile)) { "Cannot checkpoint quality report" }
        }
        fun verify(name: String, pass: Boolean, detail: String = "") {
            checks.put(JSONObject().put("name", name).put("passed", pass).put("detail", detail))
            checkpoint()
        }
        checkpoint()
        println("NATIVE_VERTICAL_QUALITY_REPORT=${reportFile.absolutePath}")
        var result: PageResult? = null
        var original: Bitmap? = null
        var cleanReference: Bitmap? = null
        var cleaned: Bitmap? = null
        var repeatedRender: Bitmap? = null
        var ownedClient: OkHttpClient? = null
        var fatal: Throwable? = null
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            val inputBytes = instrumentation.context.assets.open("native-quality/vertical-punctuation.png").use {
                it.readBytes()
            }
            val cleanBytes = instrumentation.context.assets.open("native-quality/vertical-punctuation-clean.png").use {
                it.readBytes()
            }
            check(sha256(inputBytes) == INPUT_SHA && sha256(cleanBytes) == CLEAN_SHA) {
                "Committed input/reference SHA mismatch"
            }
            val source = checkNotNull(BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size))
            original = source
            val reference = checkNotNull(BitmapFactory.decodeByteArray(cleanBytes, 0, cleanBytes.size))
            cleanReference = reference
            check(
                source.width == WIDTH && source.height == HEIGHT && reference.width == WIDTH &&
                    reference.height == HEIGHT,
            )
            val originalPixels = pixels(source)
            val referencePixels = pixels(reference)
            val inputFile = File(chapterDirectory, "001.png").apply { writeBytes(inputBytes) }
            verify(
                "independent clean reference differs only in known source columns",
                fixtureDifferenceIsBounded(originalPixels, referencePixels),
            )

            val names = listOf(
                "dbnet_detect.ncnn.param",
                "dbnet_detect.ncnn.bin",
                "ocr_48px_ctc.ncnn.param",
                "ocr_48px_ctc_mixed.ncnn.param",
                "ocr_48px_ctc.ncnn.bin",
                "mit_aot_fixed512.ncnn.param",
                "mit_aot_fixed512.ncnn.bin",
            )
            val models = names.associateWith { name ->
                File(context.filesDir, "models/$name").also {
                    check(it.isFile && it.length() > 0) { "Required native model is missing: $name" }
                }
            }
            report.put(
                "nativeModelFiles",
                JSONArray(
                    names.map { name ->
                        JSONObject().put(
                            "name",
                            name,
                        ).put("bytes", models.getValue(name).length()).put("sha256", sha256(models.getValue(name)))
                    },
                ),
            )
            val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
            check(
                listOf("。", "、", "「", "」").all {
                    it in alphabet
                },
            ) { "Required punctuation tokens are absent from this OCR alphabet" }
            report.put("requiredPunctuationInAlphabet", true)
            checkpoint()

            val preset = LlmProviders.byId("custom")
            val listed = withTimeout(45_000) {
                report.put("modelListCallStarted", true)
                checkpoint()
                LlmModels.list(LlmProviders.modelsUrlOf(preset, base), ModelSource.OPENAI, "").also {
                    report.put("modelListCallCompleted", true)
                    checkpoint()
                }
            }
            check(listed.any { it.id == model }) { "The explicit real-server model is not discoverable" }
            report.put("selectedModelDiscovered", true)
            val config = EngineConfig(
                render = RenderConfig(orientation = TextOrientation.HORIZONTAL),
                translator = TranslatorConfig(
                    provider = "custom", apiBase = LlmProviders.chatUrlOf(preset, base), model = model,
                    fromLangName = "Japanese", toLangName = "Korean", sampleSource = "", sampleTarget = "",
                    temperature = 0.0, thinking = false,
                ),
            )
            val llm = LlmTranslator("", config.translator)
            val clientField = LlmTranslator::class.java.getDeclaredField("client").apply { isAccessible = true }
            val originalClient = clientField.get(llm) as OkHttpClient
            val observingClient = originalClient.newBuilder().addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val body = checkNotNull(request.body) { "Expected one JSON POST body" }
                    check(!body.isOneShot() && !body.isDuplex()) { "Only repeatable request bodies may be observed" }
                    check(request.method == "POST" && request.header("Authorization") == null) {
                        "Fixture requests must use unauthenticated POST"
                    }
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    val bytes = buffer.readByteArray()
                    val payload = JSONObject(String(bytes, Charsets.UTF_8))
                    val messages = payload.getJSONArray("messages")
                    val user = messages.getJSONObject(messages.length() - 1)
                    check(user.getString("role") == "user")
                    val suppliedRegions = JSONArray(user.getString("content"))
                    val row = JSONObject().put("attempt", batches.length() + 1).put("requestBodySha256", sha256(bytes))
                        .put("messagesSha256", sha256(messages.toString().toByteArray(Charsets.UTF_8)))
                        .put("regionPayloadSha256", sha256(suppliedRegions.toString().toByteArray(Charsets.UTF_8)))
                        .put("modelMatches", payload.getString("model") == model).put("regions", suppliedRegions)
                        .put(
                            "noImageContent",
                            (0 until messages.length()).all {
                                messages.getJSONObject(it).opt("content") is String
                            },
                        )
                    batches.put(row)
                    checkpoint()
                    val requestStarted = SystemClock.elapsedRealtimeNanos()
                    val response = try {
                        report.put("translationPostProceedAttempted", true)
                        chain.proceed(request)
                    } catch (failure: Throwable) {
                        row.put(
                            "failureType",
                            failure.javaClass.simpleName,
                        ).put("responseHeadersWallMs", elapsed(requestStarted))
                        try {
                            checkpoint()
                        } catch (checkpointFailure: Throwable) {
                            if (checkpointFailure !== failure) failure.addSuppressed(checkpointFailure)
                        }
                        throw failure
                    }
                    try {
                        row.put("httpStatus", response.code).put("responseHeadersWallMs", elapsed(requestStarted))
                        checkpoint()
                        response
                    } catch (observationFailure: Throwable) {
                        try {
                            response.close()
                        } catch (closeFailure: Throwable) {
                            if (closeFailure !== observationFailure) observationFailure.addSuppressed(closeFailure)
                        }
                        throw observationFailure
                    }
                },
            ).build()
            ownedClient = observingClient
            clientField.set(llm, observingClient)
            verify(
                "observer preserves transport configuration and shared client resources",
                observingClient.connectTimeoutMillis == originalClient.connectTimeoutMillis &&
                    observingClient.readTimeoutMillis == originalClient.readTimeoutMillis &&
                    observingClient.callTimeoutMillis == originalClient.callTimeoutMillis &&
                    observingClient.sslSocketFactory === originalClient.sslSocketFactory &&
                    observingClient.hostnameVerifier === originalClient.hostnameVerifier &&
                    observingClient.proxy == originalClient.proxy &&
                    observingClient.followRedirects == originalClient.followRedirects &&
                    observingClient.followSslRedirects == originalClient.followSslRedirects &&
                    observingClient.retryOnConnectionFailure == originalClient.retryOnConnectionFailure &&
                    observingClient.dispatcher === originalClient.dispatcher &&
                    observingClient.connectionPool === originalClient.connectionPool,
            )
            val createStart = SystemClock.elapsedRealtimeNanos()
            Detector(models.getValue("dbnet_detect.ncnn.param").path, config.detector).use { detector ->
                Ocr(models.getValue("ocr_48px_ctc.ncnn.param").path, alphabet, config.ocr).use { nativeOcr ->
                    Inpainter(models.getValue("mit_aot_fixed512.ncnn.param").path, config.inpainter).use { inpainter ->
                        val observingOcr = object : OcrRecognizer {
                            override val intentionalFilterContext get() = nativeOcr.intentionalFilterContext
                            override suspend fun recognize(page: Bitmap, lines: List<TextLine>): Set<TextLine> {
                                val row = JSONObject().put("call", ocrCalls.length() + 1)
                                    .put("before", JSONArray(lines.map(::lineJson)))
                                ocrCalls.put(row)
                                report.put("nativeOcrCallStarted", true)
                                checkpoint()
                                return nativeOcr.recognize(page, lines).also { ignored ->
                                    report.put("nativeOcrCallCompleted", true)
                                    val groups = Grouping.group(lines)
                                    row.put(
                                        "after",
                                        JSONArray(lines.map(::lineJson)),
                                    ).put("intentionallyIgnored", ignored.size)
                                    row.put("baseGeometryGroupingObservation", JSONArray(groups.map(::regionJson)))
                                    checkpoint()
                                }
                            }
                            override fun warmUp() = nativeOcr.warmUp()
                            override fun close() = Unit // The surrounding use owns the native recognizer.
                        }
                        val pipeline = Pipeline(detector, observingOcr, llm, inpainter, config)
                        report.put("nativeCreateMs", elapsed(createStart)).put("ocrBackend", nativeOcr.backend)
                            .put(
                                "nativeSessionColdDefinition",
                                "New private native components; shared engine is absent; " +
                                    "no process-cold or page-cold claim",
                            )
                            .put("inpaintMethod", config.inpainter.method).put("ocrConcurrency", config.ocr.concurrency)
                        val warmStart = SystemClock.elapsedRealtimeNanos()
                        pipeline.warmUp()
                        report.put("nativeWarmupMs", elapsed(warmStart)).put("logicalPageAttempts", 1)
                        checkpoint()
                        val pageStart = SystemClock.elapsedRealtimeNanos()
                        result = withTimeout(240_000) { pipeline.translatePage(source) }
                        report.put(
                            "pipelineWallMs",
                            elapsed(pageStart),
                        ).put("resultType", result!!.javaClass.simpleName)
                        checkpoint()
                        val translated = result as? PageResult.Translated
                        if (translated == null) {
                            val outcome = result
                            report.put(
                                "failureStage",
                                when (outcome) {
                                    is PageResult.Failed -> outcome.reason.substringBefore(':').take(40)
                                    else -> "not-complete"
                                },
                            )
                            val analysis = when (outcome) {
                                is PageResult.Partial -> outcome.analysis
                                is PageResult.Skipped -> outcome.analysis
                                else -> null
                            }
                            analysis?.let { report.put("regions", JSONArray(it.regions.map(::regionJson))) }
                            verify(
                                "native pipeline completes every neutral region",
                                false,
                                "See OCR, request and geometry observations",
                            )
                        } else {
                            val analysis = checkNotNull(translated.analysis)
                            report.put("regions", JSONArray(analysis.regions.map(::regionJson)))
                                .put(
                                    "stats",
                                    JSONObject().put(
                                        "lines",
                                        translated.stats.lines,
                                    ).put("regions", translated.stats.regions)
                                        .put("detectMs", translated.stats.detectMs).put("ocrMs", translated.stats.ocrMs)
                                        .put(
                                            "llmMs",
                                            translated.stats.translateMs,
                                        ).put("inpaintMs", translated.stats.inpaintMs)
                                        .put(
                                            "renderMs",
                                            translated.stats.renderMs,
                                        ).put("promptTokens", translated.stats.promptTokens)
                                        .put("completionTokens", translated.stats.completionTokens),
                                )
                            checkpoint()
                            verifyRecognition(analysis.regions, checks, report, ::checkpoint)
                            verifyBatches(analysis, batches, checks, ::checkpoint)
                            val progress = checkNotNull(analysis.progress)
                            verify(
                                "all stable progress IDs are complete",
                                progress.regions.indices.all {
                                    progress.regions[it].id == it &&
                                        progress.regions[it].state == RegionState.TRANSLATED
                                },
                            )
                            verify("input bitmap is unchanged", originalPixels.contentEquals(pixels(source)))
                            verifyMeaning(analysis.regions, checks, report, ::checkpoint)

                            val cleanedPage = inpainter.inpaint(source, analysis.regions, analysis.mask)
                            cleaned = cleanedPage
                            val cleanedPixels = pixels(cleanedPage)
                            val removalDifference = difference(referencePixels, cleanedPixels)
                            report.put("cleanReferenceVsRemoval", removalDifference)
                            verify(
                                "native removal exactly restores independent clean fixture",
                                removalDifference.getLong("changedPixels") == 0L,
                            )
                            val secondRender = Renderer.render(cleanedPage, analysis.regions, config.render)
                            repeatedRender = secondRender
                            verify(
                                "repeated render is pixel consistent (not an independent quality oracle)",
                                pixels(secondRender).contentEquals(pixels(translated.page)),
                            )
                            verifyInk(referencePixels, pixels(translated.page), checks, report, ::checkpoint)

                            val localPreferences = TranslationPreferences(InMemoryPreferenceStore())
                            val storage = PageTranslator(context).also {
                                PageTranslator::class.java.getDeclaredField("translationPreferences").apply {
                                    isAccessible =
                                        true
                                }
                                    .set(it, localPreferences)
                            }
                            val chapter = checkNotNull(UniFile.fromFile(chapterDirectory))
                            val saveStart = SystemClock.elapsedRealtimeNanos()
                            val saved = storage.persistLivePage(
                                chapter,
                                checkNotNull(UniFile.fromFile(inputFile)),
                                source,
                                translated.page,
                                analysis,
                                METHOD,
                            )
                            report.put("persistWallMs", elapsed(saveStart)).put("persistenceReturned", saved)
                            checkpoint()
                            verify("application persistence succeeds", saved)
                            verify("completion marker is readable", storage.isPageTranslated(chapter, inputFile.name))
                            verifyStorage(
                                chapterDirectory,
                                analysis,
                                inputBytes,
                                translated.page,
                                checks,
                                report,
                                ::checkpoint,
                            )
                            if (retain) {
                                writeBitmap(File(directory, "inpainted.png"), cleanedPage)
                                writeBitmap(File(directory, "rendered.png"), translated.page)
                            }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            fatal = failure
            // Do not export endpoint addresses, API headers or arbitrary server exception bodies.
            report.put("fatalType", failure.javaClass.simpleName)
                .put("fatalMessage", failure.message.orEmpty().replace(Regex("https?://[^\\s]+"), "[server]").take(180))
        } finally {
            repeatedRender?.recycle()
            cleaned?.recycle()
            release(result)
            original?.recycle()
            cleanReference?.recycle()
            ownedClient?.let {
                it.dispatcher.cancelAll()
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
            // No native component is shared. Their use scopes close them; never call pipeline.close().
            val sharedStillIdle = runCatching { requireSharedIdle(shared) }.isSuccess
            report.put("sharedEngineStillAbsentAndIdle", sharedStillIdle)
            if (!sharedStillIdle && fatal == null) fatal = IllegalStateException("Shared engine unexpectedly changed")
            if (!retain) {
                check(chapterDirectory.canonicalFile.parentFile == directory.canonicalFile)
                val removed = chapterDirectory.deleteRecursively() && !chapterDirectory.exists()
                report.put("ownedChapterRemoved", removed)
                if (!removed && fatal == null) fatal = IllegalStateException("Owned chapter cleanup failed")
            } else {
                report.put("ownedChapterRetainedForExplicitInspection", true)
            }
            val failures = (0 until checks.length()).count { !checks.getJSONObject(it).getBoolean("passed") }
            report.put("qualityCheckFailures", failures).put("qualityChecks", checks.length())
                .put("passed", fatal == null && failures == 0).put("wallMs", elapsed(started))
            checkpoint()
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "NATIVE_VERTICAL_QUALITY_SUMMARY=" + JSONObject().put("passed", report.getBoolean("passed"))
                            .put(
                                "checks",
                                checks.length(),
                            ).put("failures", failures).put("report", reportFile.absolutePath) +
                            "\n",
                    )
                },
            )
        }
        assertTrue(
            "Native vertical quality failed; inspect the checkpointed neutral report",
            report.getBoolean("passed"),
        )
    }

    private fun verifyRecognition(
        regions: List<TextRegion>,
        checks: JSONArray,
        report: JSONObject,
        checkpoint: () -> Unit,
    ) {
        val lines = regions.flatMap { it.lines }
        val assigned = COLUMNS.map { column -> lines.filter { abs(centerX(it) - column.x) < 46f } }
        val rows = JSONArray()
        for ((index, column) in COLUMNS.withIndex()) {
            val matched = assigned[index]
            val actual = matched.sortedBy { it.quad.minOf { point -> point.y } }.joinToString("") { it.text }
            rows.put(
                JSONObject().put("column", index).put("balloon", column.balloon).put("expected", column.text)
                    .put(
                        "actual",
                        actual,
                    ).put("detectedLines", matched.size).put("lines", JSONArray(matched.map(::lineJson))),
            )
            checkRow(
                checks,
                "exact OCR column $index including punctuation",
                actual == column.text,
                "expected=${column.text}; actual=$actual",
            )
            checkRow(checks, "column $index is vertical", matched.isNotEmpty() && matched.all { it.direction == "v" })
        }
        val assignedCount = assigned.sumOf { it.size }
        checkRow(
            checks,
            "all detected lines belong uniquely to the four declared columns",
            assignedCount == lines.size && assigned.all { it.isNotEmpty() },
            "actual=${lines.size}; assigned=$assignedCount",
        )
        for (balloon in listOf("left", "right")) {
            val actual = COLUMNS.indices.filter { COLUMNS[it].balloon == balloon }.sortedByDescending { COLUMNS[it].x }
                .joinToString("") { index ->
                    assigned[index].sortedBy { line -> line.quad.minOf { it.y } }.joinToString("") { it.text }
                }
            val expected = if (balloon == "left") EXPECTED_LEFT else EXPECTED_RIGHT
            checkRow(
                checks,
                "exact $balloon balloon right-to-left OCR",
                actual == expected,
                "expected=$expected; actual=$actual",
            )
            val balloonRegions = regions.filter { region ->
                region.lines.any { line -> nearestColumn(line).balloon == balloon }
            }
            checkRow(
                checks,
                "pretranslation $balloon balloon is one complete source region",
                balloonRegions.singleOrNull()?.sourceText == expected,
                "expected=$expected; regionCount=${balloonRegions.size}; " +
                    "actualSources=${JSONArray(balloonRegions.map { it.sourceText })}",
            )
        }
        // Tie the geometry oracle to the actual production region order used by sourceText/API input.
        // Detector encounter order across different balloons is deliberately not a contract here.
        checkRow(
            checks,
            "each production group contains lines from only one balloon",
            regions.all { region ->
                region.lines.map { line -> nearestColumn(line).balloon }.distinct().size == 1
            },
        )
        checkRow(
            checks,
            "production sourceText follows column slots right-to-left and top-to-bottom",
            regions.all { region ->
                val expected = region.lines.sortedWith(
                    compareByDescending<TextLine> { nearestColumn(it).x }.thenBy { line -> line.quad.minOf { it.y } },
                )
                region.direction == "v" && region.lines == expected &&
                    region.sourceText == expected.joinToString("") { it.text }
            },
        )
        report.put("columnQuality", rows)
        checkpoint()
    }

    private fun verifyBatches(analysis: PageAnalysis, batches: JSONArray, checks: JSONArray, checkpoint: () -> Unit) {
        val ready = analysis.regions.filterNot { it.preserveOriginal }
        checkRow(checks, "at least one real translation POST completed", batches.length() > 0)
        checkRow(
            checks,
            "a real whole-page POST receives successful HTTP status",
            (0 until batches.length()).any {
                batches.getJSONObject(it).optInt("httpStatus") in 200..299
            },
        )
        val messageHashes = (0 until batches.length()).map {
            batches.getJSONObject(it).getString("messagesSha256")
        }.toSet()
        val regionHashes = (0 until batches.length()).map {
            batches.getJSONObject(it).getString("regionPayloadSha256")
        }.toSet()
        checkRow(
            checks,
            "one page's execute attempts retain identical messages and regions",
            messageHashes.size == 1 && regionHashes.size == 1,
        )
        for (index in 0 until batches.length()) {
            val attempt = batches.getJSONObject(index)
            val supplied = attempt.getJSONArray("regions")
            val exact = supplied.length() == ready.size && ready.indices.all { position ->
                val row = supplied.getJSONObject(position)
                row.getInt("id") == position + 1 && row.getString("text") == ready[position].sourceText
            }
            attempt.put("matchesWholePageAnalysisOrderAndIds", exact)
                .put(
                    "progressIdMapping",
                    JSONArray(
                        ready.indices.map { position ->
                            JSONObject().put(
                                "requestId",
                                position + 1,
                            ).put("progressId", analysis.regions.indexOf(ready[position]))
                        },
                    ),
                )
            checkRow(checks, "execute attempt ${index + 1} contains every ready region with exact IDs", exact)
            checkRow(checks, "execute attempt ${index + 1} sends text only", attempt.getBoolean("noImageContent"))
            checkRow(
                checks,
                "execute attempt ${index + 1} uses the requested model",
                attempt.getBoolean("modelMatches"),
            )
        }
        checkpoint()
    }

    private fun verifyMeaning(
        regions: List<TextRegion>,
        checks: JSONArray,
        report: JSONObject,
        checkpoint: () -> Unit,
    ) {
        val values = JSONObject()
        for (left in listOf(true, false)) {
            val balloon = if (left) "left" else "right"
            val selected = regions.filter { ((it.x0 + it.x1) / 2f < 600f) == left }.sortedByDescending { it.x0 }
            val target = selected.joinToString(" ") { it.translatedText }
            values.put(balloon, target)
            val content = Regex("[가-힣]").findAll(target).count()
            val semantic = if (left) {
                target.contains("기다") && (target.contains("잠시") || target.contains("조금"))
            } else {
                target.contains("내일") && target.contains("잘") && target.contains("부탁")
            }
            checkRow(checks, "$balloon translation retains neutral sentence meaning", content >= 5 && semantic, target)
            checkRow(
                checks,
                "$balloon targets do not copy OCR column wrapping",
                selected.all {
                    '\n' !in
                        it.translatedText &&
                        '\r' !in it.translatedText
                },
            )
        }
        report.put("actualBalloonTargets", values)
        checkpoint()
    }

    private fun verifyInk(
        reference: IntArray,
        output: IntArray,
        checks: JSONArray,
        report: JSONObject,
        checkpoint: () -> Unit,
    ) {
        val bounds = listOf(Rect(WIDTH, HEIGHT, 0, 0), Rect(WIDTH, HEIGHT, 0, 0))
        val occupied = Array(2) { BooleanArray(HEIGHT) }
        val counts = LongArray(2)
        var escaped = 0L
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val index = y * WIDTH + x
                if (reference[index] == output[index]) continue
                val balloon = if (x < 600) 0 else 1
                if (!INK_BANDS[balloon].contains(x, y)) escaped++
                counts[balloon]++
                occupied[balloon][y] = true
                bounds[balloon].union(x, y, x + 1, y + 1)
            }
        }
        val measurements = JSONArray()
        for (index in 0..1) {
            val box = bounds[index]
            // Small gaps inside Hangul syllable strokes are not separate text lines.
            var lastInkRow = -10
            var rowBands = 0
            occupied[index].indices.filter { occupied[index][it] }.forEach { y ->
                if (y - lastInkRow > 4) rowBands++
                lastInkRow = y
            }
            measurements.put(
                JSONObject().put("balloon", index).put("changedPixels", counts[index])
                    .put("bounds", rectJson(box)).put("occupiedRowBands", rowBands)
                    .put("rowBandMinimumEmptyGapPixels", 4)
                    .put("width", box.width()).put("height", box.height()),
            )
            checkRow(
                checks,
                "balloon $index has compact horizontal target ink",
                counts[index] > 100 && box.width() > 160 && box.height() in 12..360 && box.width() > box.height(),
                box.toShortString(),
            )
            checkRow(
                checks,
                "balloon $index avoids a one-character vertical stack",
                rowBands in 1..5,
                "occupiedRowBands=$rowBands",
            )
        }
        checkRow(
            checks,
            "balloon walls, surrounding artwork and blue mark remain unchanged",
            escaped == 0L,
            "escapedPixels=$escaped",
        )
        report.put("actualOutputInk", measurements).put("outputPixelsOutsidePermittedBands", escaped)
        checkpoint()
    }

    private fun verifyStorage(
        directory: File,
        analysis: PageAnalysis,
        original: ByteArray,
        output: Bitmap,
        checks: JSONArray,
        report: JSONObject,
        checkpoint: () -> Unit,
    ) {
        val materials = File(directory, ".yakuyomi")
        val originalFile = File(materials, "001.orig.png")
        val jsonFile = File(materials, "001.json")
        val maskFile = File(materials, "001.mask.png")
        val manifest = File(directory, CompletionManifestCodec.PRIMARY)
        checkRow(
            checks,
            "durable completion manifest contains exactly this page",
            manifest.isFile &&
                CompletionManifestCodec.decode(manifest.readBytes()) == setOf("001.png"),
        )
        checkRow(
            checks,
            "persisted original preserves exact committed PNG bytes",
            originalFile.isFile && originalFile.readBytes().contentEquals(original),
        )
        val decoded = BitmapFactory.decodeFile(File(directory, "001.png").path)
        try {
            checkRow(
                checks,
                "saved display decodes to exact pipeline pixels",
                decoded != null && decoded.width == WIDTH &&
                    decoded.height == HEIGHT && pixels(decoded).contentEquals(pixels(output)),
            )
        } finally {
            decoded?.recycle()
        }
        checkRow(checks, "region JSON and mask both exist", jsonFile.isFile && maskFile.isFile)
        if (jsonFile.isFile) {
            val saved = JSONObject(jsonFile.readText()).getJSONArray("regions")
            val exact = saved.length() == analysis.regions.size && analysis.regions.indices.all { index ->
                val live = analysis.regions[index]
                val row = saved.getJSONObject(index)
                row.getString("source") == live.sourceText && row.getString("target") == live.translatedText &&
                    row.getJSONArray("quads").toString() ==
                    JSONArray(live.lines.map { line -> line.quad.map { listOf(it.x, it.y) } }).toString()
            }
            checkRow(checks, "stored source, target, order and quads equal native analysis", exact)
        }
        if (maskFile.isFile) {
            val saved = BitmapFactory.decodeFile(maskFile.path)
            try {
                val matches =
                    saved != null && saved.width == WIDTH && saved.height == HEIGHT &&
                        binaryMaskMatches(saved, analysis.mask)
                checkRow(checks, "stored binary mask preserves every native threshold bit", matches)
            } finally {
                saved?.recycle()
            }
        }
        val files = directory.walkTopDown().filter { it.isFile }.map {
            JSONObject().put(
                "relativeName",
                it.relativeTo(directory).invariantSeparatorsPath,
            ).put("bytes", it.length()).put("sha256", sha256(it))
        }.toList()
        report.put("verifiedStoredFiles", JSONArray(files))
        checkpoint()
    }

    private fun requireSharedIdle(service: TranslationEngineService) {
        check(!service.loading.value && !service.warm.value) {
            "Shared audit engine must be cold and idle before this independent test"
        }
        val engine = TranslationEngineService::class.java.getDeclaredField("engine").apply {
            isAccessible = true
        }.get(service)
        val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight").apply {
            isAccessible = true
        }.get(service) as AtomicInteger
        check(engine == null && inFlight.get() == 0) { "Refusing to close or compete with an existing shared engine" }
    }

    private fun binaryMaskMatches(saved: Bitmap, native: Bitmap): Boolean {
        val savedRow = IntArray(WIDTH)
        val nativeRow = IntArray(WIDTH)
        for (y in 0 until HEIGHT) {
            saved.getPixels(savedRow, 0, WIDTH, 0, y, WIDTH, 1)
            native.getPixels(nativeRow, 0, WIDTH, 0, y, WIDTH, 1)
            if (savedRow.indices.any { ((savedRow[it] and 255) > 127) != ((nativeRow[it] and 255) > 127) }) return false
        }
        return true
    }

    private fun fixtureDifferenceIsBounded(source: IntArray, clean: IntArray): Boolean {
        var differences = 0
        source.indices.forEach { index ->
            if (source[index] != clean[index]) {
                differences++
                val x = index % WIDTH
                val y = index / WIDTH
                if (COLUMNS.none { abs(x - it.x) <= 45 && y in 280..1000 }) return false
            }
        }
        return differences > 1000
    }

    private fun difference(expected: IntArray, actual: IntArray): JSONObject {
        var changed = 0L
        val bounds = Rect(WIDTH, HEIGHT, 0, 0)
        expected.indices.forEach {
            if (expected[it] != actual[it]) {
                changed++
                val x = it % WIDTH
                val y = it / WIDTH
                bounds.union(x, y, x + 1, y + 1)
            }
        }
        return JSONObject().put("changedPixels", changed).put("bounds", rectJson(bounds))
    }

    private fun lineJson(line: TextLine) = JSONObject().put("text", line.text).put("direction", line.direction)
        .put("score", line.score).put("quad", JSONArray(line.quad.map { listOf(it.x, it.y) }))

    private fun regionJson(region: TextRegion) = JSONObject().put(
        "source",
        region.sourceText,
    ).put("target", region.translatedText)
        .put("direction", region.direction).put("preserveOriginal", region.preserveOriginal)
        .put("onArt", region.onArt).put("lines", JSONArray(region.lines.map(::lineJson)))

    private fun checkRow(rows: JSONArray, name: String, pass: Boolean, detail: String = "") {
        rows.put(JSONObject().put("name", name).put("passed", pass).put("detail", detail))
    }

    private fun rectJson(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun centerX(line: TextLine) = line.quad.map { it.x }.average().toFloat()
    private fun nearestColumn(line: TextLine) = COLUMNS.minBy { abs(centerX(line) - it.x) }
    private fun pixels(
        bitmap: Bitmap,
    ) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    private fun sha256(
        bytes: ByteArray,
    ) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <
                    0
                ) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun writeBitmap(
        file: File,
        bitmap: Bitmap,
    ) = file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    private fun release(result: PageResult?) {
        when (result) {
            is PageResult.Translated -> {
                result.page.recycle()
                result.analysis?.mask?.let { if (!it.isRecycled) it.recycle() }
            }
            is PageResult.Partial -> {
                result.page.recycle()
                if (!result.analysis.mask.isRecycled) result.analysis.mask.recycle()
            }
            is PageResult.Skipped -> result.analysis?.mask?.let { if (!it.isRecycled) it.recycle() }
            else -> Unit
        }
    }

    private data class Column(val balloon: String, val x: Int, val text: String)
    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 1600
        const val METHOD = "auto_whole"
        const val INPUT_SHA = "281ffb1cc92aa692bddf6ea1429ecbb32a3ea6cfc974c09329154abd628010f3"
        const val CLEAN_SHA = "0578fee16b8ef07922abf629aa2c67b8344e22454d2a924e3976c0abf1ec3a7e"
        const val EXPECTED_LEFT = "少し待って、ください。"
        const val EXPECTED_RIGHT = "「明日もよろしくお願いします。」"
        val COLUMNS = listOf(
            Column("left", 430, "少し待って、"),
            Column("left", 310, "ください。"),
            Column("right", 1000, "「明日もよろしく"),
            Column("right", 880, "お願いします。」"),
        )
        val INK_BANDS = listOf(Rect(88, 200, 542, 1100), Rect(658, 200, 1112, 1100))
    }
}
