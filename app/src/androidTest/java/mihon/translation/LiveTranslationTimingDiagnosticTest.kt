package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.crash.TraceLog
import eu.kanade.tachiyomi.data.translation.SecretCipher
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.EngineTrace
import li.joye.yakuyomi.engine.FailureKind
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.ProcessingStage
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.Yakuyomi
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.StringCrypto
import tachiyomi.domain.translation.service.TranslationPreferences
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual audit settings and server; synthetic saved progress, no service, UI, disk page or settings writes. */
@RunWith(AndroidJUnit4::class)
class LiveTranslationTimingDiagnosticTest {
    @Test
    fun currentSettingsNeutralResumeRecordsTransportAndPreservesOriginal() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("liveTranslationTimingDiagnostic") == "true") { "Explicit timing opt-in required" }
        check(args.getString("useCurrentTranslationSettings") == "true") { "Current audit settings opt-in required" }
        val runId = args.getString("diagnosticRunId").orEmpty()
        check(runId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        check(UUID.fromString(runId).toString() == runId) { "Canonical UUID required" }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit package required" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Actual ARM64 process required" }
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
        val beforePreferences = copyPreferences(raw.all)
        val started = SystemClock.elapsedRealtimeNanos()
        val observer = LiveTranslationTimingObserver(PENDING_TEXT)
        val attempts = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId).put("terminal", false)
            .put("success", false).put("passed", false).put("transportObserved", false).put("timeoutReproduced", false)
            .put("historicalTimeoutResolved", false).put("releaseSettingsEqualityClaimed", false)
            .put("auditCurrentSettings", true).put("preferencesWritten", false).put("modelDownloads", 0)
            .put("publicTranslateCalls", 0).put("publicResumeCalls", 0).put("warmupCalls", 0)
            .put("detectorInferenceCalls", 0).put("preparedSyntheticProgress", true)
            .put("diskPagePersistenceExercised", false).put("scriptedTimeout", false)
            .put("serverTranslationSemanticsAssessed", false).put("attempts", attempts)
            .put("overallDeadlineMs", 570_000).put("clock", "elapsedRealtimeNanos")
        fun status(stage: String, terminal: Boolean = false) {
            report.put("stage", stage).put("terminal", terminal).put("elapsedMs", elapsed(started))
                .put("transport", observer.snapshot())
            val value = report.toString()
            check(value.toByteArray(Charsets.UTF_8).size <= 65_536) { "Safe report exceeds bound" }
            instrumentation.sendStatus(2, Bundle().apply { putString("liveTranslationTimingEvidence", value) })
        }
        var engine: Pipeline? = null
        var client: OkHttpClient? = null
        var fixture: Fixture? = null
        var files: List<File> = emptyList()
        var fileFacts: List<Pair<Long, String>> = emptyList()
        var owners: List<Any> = emptyList()
        var failure: Throwable? = null
        var cleanupCancellation: CancellationException? = null
        var fatal: Throwable? = null
        var cleanupFailures = 0
        try {
            report.put("idleBefore", NativeDiagnosticIdleGuard.requireIdle(context))
            check(!TraceLog.isEnabled && EngineTrace.sink == null) { "Shared tracing must already be disabled" }
            status("PREPARING")
            withTimeout(570_000) {
                val preferences = memoryPreferences(beforePreferences)
                check(preferences.provider.get() in setOf("custom", "sakura")) {
                    "Configured private provider required"
                }
                check(preferences.apiBase.get().isNotBlank() && preferences.model.get().isNotBlank())
                val config = TranslationEngineConfig.buildEngineConfig(preferences, preferences.inpaintMethod.get())
                val endpoint = URI(config.translator.apiBase)
                check(endpoint.scheme in setOf("http", "https") && !endpoint.host.isNullOrBlank())
                check(endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null)
                val key = preferences.activeApiKey()
                val keySlot = "translation_api_key_enc__${preferences.provider.get()}"
                check((beforePreferences[keySlot] as? String).isNullOrBlank() || key.isNotBlank()) {
                    "Existing encrypted credential could not be read"
                }
                files = nativeModels(context)
                fileFacts = files.map { it.length() to sha(it) }
                report.put(
                    "nativeModels",
                    JSONArray(
                        fileFacts.mapIndexed { index, fact ->
                            JSONObject().put("slot", index).put("bytes", fact.first).put("sha256", fact.second)
                        },
                    ),
                )
                val models = checkNotNull(ModelSet.resolve(files.map { it.name to it.path }))
                val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
                check(alphabet.size == 19_264)
                val creationStarted = SystemClock.elapsedRealtimeNanos()
                val pipeline = Yakuyomi.create(models, alphabet, key, config) as Pipeline
                engine = pipeline
                report.put("factoryCreationMs", elapsed(creationStarted)).put("nativeOwners", 3)
                owners = listOf("detector", "ocr", "inpainter").map { field(pipeline, it) }
                val llm = field(pipeline, "translator") as LlmTranslator
                check(field(pipeline, "ownedLlmTranslator") === llm)
                val originalClient = field(llm, "client") as OkHttpClient
                client = originalClient
                val observed = originalClient.newBuilder().eventListenerFactory(observer).build()
                requireSameTransport(originalClient, observed)
                LlmTranslator::class.java.getDeclaredField("client").apply { isAccessible = true }.set(llm, observed)
                client = observed
                report.put("factoryTranslatorIdentityPreserved", true).put("transportSettingsPreserved", true)
                    .put("connectTimeoutMs", observed.connectTimeoutMillis)
                    .put("readTimeoutMs", observed.readTimeoutMillis)
                    .put("callTimeoutMs", observed.callTimeoutMillis).put("writeTimeoutMs", observed.writeTimeoutMillis)
                val initialContext = checkNotNull(pipeline.translationContext)
                val input = Fixture().also { fixture = it }
                status("READY")
                for (number in 1..2) {
                    observer.beginLogical(number)
                    report.put("publicResumeCalls", number)
                    val row = JSONObject().put("attempt", number).put("explicitRetry", number == 2)
                    attempts.put(row)
                    val callStarted = SystemClock.elapsedRealtimeNanos()
                    var result: PageResult? = null
                    try {
                        status("RESUMING")
                        result = pipeline.resumePage(input.page, input.analysis)
                        row.put("elapsedMs", elapsed(callStarted))
                        row.put(
                            "result",
                            when (result) {
                                is PageResult.Failed -> "FAILED"
                                is PageResult.Translated -> "COMPLETED"
                                is PageResult.Partial -> "PARTIAL"
                                is PageResult.Skipped -> "SKIPPED"
                                null -> "NONE"
                            },
                        )
                        if (result is PageResult.Failed) {
                            row.put("failureStage", result.failure?.stage?.name ?: "UNKNOWN")
                                .put("failureKind", result.failure?.kind?.name ?: "UNKNOWN")
                        }
                        input.verifyUnchanged()
                        check(pipeline.translationContext == initialContext)
                        row.put("originalMaskProgressAndSuccessfulRegionUnchanged", true)
                        withTimeout(5_000) { awaitQuiescent(observed) }
                        val transport = observer.snapshot()
                        report.put("transportObserved", transport.getInt("httpCalls") > 0)
                        check(transport.getBoolean("complete")) {
                            "HTTP observation incomplete or neutral batch changed"
                        }
                        if (result is PageResult.Failed) {
                            val typed = result.failure
                            row.put("result", "FAILED").put("failureStage", typed?.stage?.name ?: "UNKNOWN")
                                .put("failureKind", typed?.kind?.name ?: "UNKNOWN")
                            val timedOut = typed?.stage == ProcessingStage.TRANSLATE &&
                                typed.kind == FailureKind.TIMEOUT
                            if (timedOut) report.put("timeoutReproduced", true)
                            status("FAILED_ATTEMPT")
                            if (timedOut && number == 1) continue
                            error("Actual page request failed; inspect safe typed result")
                        }
                        val completed = result as? PageResult.Translated ?: error("Neutral resume did not complete")
                        val progress = checkNotNull(completed.analysis?.progress)
                        check(!progress.hasPending && progress.regions.size == 2)
                        check(progress.regions[0] == input.progress.regions[0]) { "Previous successful region changed" }
                        check(progress.regions[1].lines.single().text == PENDING_TEXT) { "Neutral OCR oracle mismatch" }
                        val containsHangul = Regex("[가-힣]").containsMatchIn(progress.regions[1].translatedText)
                        check(containsHangul) { "Expected a nonblank Korean target for the neutral region" }
                        check(completed.stats.detectMs == 0L)
                        row.put("result", "COMPLETED").put("remainingPending", 0)
                            .put("previousSuccessfulRegionPreserved", true).put("neutralOcrExact", true)
                            .put("newTargetContainsHangul", containsHangul)
                            .put("newRegionState", progress.regions[1].state.name)
                            .put("ocrMs", completed.stats.ocrMs).put("translationMs", completed.stats.translateMs)
                            .put("inpaintMs", completed.stats.inpaintMs).put("renderMs", completed.stats.renderMs)
                        report.put("success", true).put("timeoutRecovered", number == 2)
                        break
                    } catch (error: Throwable) {
                        row.put("elapsedMs", elapsed(callStarted)).put("exception", safeException(error))
                        throw error
                    } finally {
                        release(result)
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            if (isFatal(error)) fatal = error
            report.put("success", false).put("exception", safeException(error))
                .put("harnessCancelled", error is CancellationException)
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(block: suspend () -> Unit) {
                    try {
                        block()
                    } catch (error: Throwable) {
                        cleanupFailures++
                        if (isFatal(error)) fatal = error
                        if (error is CancellationException) {
                            cleanupCancellation = error
                            report.put("cleanupCancelled", true).put("cleanupCancellation", safeException(error))
                        }
                    }
                }
                cleanup { fixture?.verifyUnchanged() }
                cleanup { engine?.close() }
                cleanup {
                    client?.let { owned ->
                        withTimeout(10_000) {
                            while (owned.dispatcher.queuedCallsCount() != 0 ||
                                owned.dispatcher.runningCallsCount() != 0 ||
                                !owned.dispatcher.executorService.isShutdown ||
                                owned.connectionPool.connectionCount() != 0
                            ) {
                                delay(25)
                            }
                        }
                        check(owned.dispatcher.executorService.awaitTermination(3, TimeUnit.SECONDS))
                        report.put("ownedHttpClosed", true).put("httpRunningAfter", 0).put("httpQueuedAfter", 0)
                            .put("httpConnectionsAfter", 0).put("httpExecutorTerminated", true)
                    }
                }
                cleanup {
                    owners.forEach { owner -> check((field(owner, "ncnnHandle") as Long) == 0L) }
                    report.put("nativeOwnersClosed", owners.size)
                }
                cleanup { fixture?.close() }
                cleanup {
                    check(files.map { it.length() to sha(it) } == fileFacts)
                    report.put("modelsUnchanged", true)
                }
                cleanup {
                    check(copyPreferences(raw.all) == beforePreferences) { "Preference values or presence changed" }
                    report.put("rawPreferenceValuesAndPresenceUnchanged", true)
                    report.put("idleAfter", NativeDiagnosticIdleGuard.requireIdle(context))
                    check(!TraceLog.isEnabled && EngineTrace.sink == null)
                }
            }
            report.put("cleanupFailures", cleanupFailures)
            if (cleanupFailures != 0) report.put("success", false)
            val transport = observer.snapshot()
            report.put("transportObserved", transport.getInt("httpCalls") > 0)
                .put("observationComplete", transport.getBoolean("complete"))
            report.put("passed", report.getBoolean("success"))
            status(if (report.getBoolean("success")) "COMPLETED" else "FAILED", terminal = true)
        }
        fatal?.let { throw it }
        cleanupCancellation?.let { throw it }
        val failed = failure
        if (failed is CancellationException) throw CancellationException("Live timing diagnostic cancelled")
        check(failed == null && cleanupFailures == 0 && report.getBoolean("success")) {
            "Live timing diagnostic failed; inspect sanitized evidence"
        }
    }

    private fun memoryPreferences(raw: Map<String, Any?>): TranslationPreferences {
        check(!raw.containsKey("translation_api_key")) { "Legacy migration must not be performed by this diagnostic" }
        val captured = raw.mapNotNull { (key, value) ->
            value?.let { InMemoryPreferenceStore.InMemoryPreference(key, it, it) }
        }
        val readOnlyCipher = object : StringCrypto {
            override fun encrypt(plain: String): String = error("Diagnostic encryption is forbidden")
            override fun decrypt(encrypted: String): String? = SecretCipher.decrypt(encrypted)
        }
        return TranslationPreferences(InMemoryPreferenceStore(captured.asSequence()), readOnlyCipher)
    }

    private fun requireSameTransport(before: OkHttpClient, after: OkHttpClient) {
        check(before.connectTimeoutMillis == 30_000 && before.readTimeoutMillis == 120_000)
        check(before.callTimeoutMillis == 150_000)
        check(before.connectTimeoutMillis == after.connectTimeoutMillis)
        check(before.readTimeoutMillis == after.readTimeoutMillis)
        check(before.callTimeoutMillis == after.callTimeoutMillis)
        check(before.writeTimeoutMillis == after.writeTimeoutMillis)
        check(before.dispatcher === after.dispatcher && before.connectionPool === after.connectionPool)
        check(before.sslSocketFactory === after.sslSocketFactory && before.hostnameVerifier === after.hostnameVerifier)
        check(before.dns === after.dns && before.proxy == after.proxy && before.proxySelector === after.proxySelector)
        check(before.authenticator === after.authenticator && before.proxyAuthenticator === after.proxyAuthenticator)
        check(before.cookieJar === after.cookieJar && before.cache === after.cache)
        check(before.followRedirects == after.followRedirects && before.followSslRedirects == after.followSslRedirects)
        check(before.retryOnConnectionFailure == after.retryOnConnectionFailure && before.protocols == after.protocols)
        check(before.connectionSpecs == after.connectionSpecs && before.certificatePinner == after.certificatePinner)
        check(before.interceptors == after.interceptors && before.networkInterceptors == after.networkInterceptors)
    }

    private class Fixture : AutoCloseable {
        val page = Bitmap.createBitmap(1000, 340, Bitmap.Config.ARGB_8888)
        val mask = try {
            Bitmap.createBitmap(1000, 340, Bitmap.Config.ARGB_8888)
        } catch (error: Throwable) {
            page.recycle()
            throw error
        }
        val progress: PageProgress
        val analysis: PageAnalysis
        private val original: String
        private val originalMask: String
        private val liveProgress: PageProgress

        init {
            try {
                page.density = Bitmap.DENSITY_NONE
                mask.density = Bitmap.DENSITY_NONE
                Canvas(page).drawColor(Color.WHITE)
                val regions = listOf("おやすみなさい。", PENDING_TEXT).mapIndexed { index, text ->
                    val drawing = OcrDiagnosticSample(text).draw(60f)
                    try {
                        check(drawing.page.width == 1000 && drawing.page.height == 160)
                        val offset = index * 180
                        Canvas(page).drawBitmap(drawing.page, null, Rect(0, offset, 1000, offset + 160), null)
                        val quad = drawing.quad.map { Pt(it.x, it.y + offset) }
                        RegionProgress(
                            id = index,
                            state = if (index == 0) RegionState.TRANSLATED else RegionState.OCR_PENDING,
                            lines = listOf(OcrLineProgress(quad, 1f, "h", if (index == 0) text else "")),
                            direction = "h", cx = (quad[0].x + quad[2].x) / 2, cy = (quad[0].y + quad[2].y) / 2,
                            boxW = quad[1].x - quad[0].x, boxH = quad[2].y - quad[0].y,
                            translatedText = if (index == 0) "PRESERVED" else "",
                        )
                    } finally {
                        drawing.page.recycle()
                    }
                }
                val pixels = IntArray(page.width * page.height)
                page.getPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
                for (i in pixels.indices) pixels[i] = if (Color.red(pixels[i]) < 128) Color.WHITE else Color.BLACK
                mask.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
                progress = PageProgress(page.width, page.height, regions).also {
                    it.validateFor(page.width, page.height)
                }
                analysis = PageAnalysis(mask, progress.toTextRegions(), progress)
                liveProgress = PageProgress.capture(page.width, page.height, analysis.regions, regions.map { it.state })
                original = bitmapHash(page)
                originalMask = bitmapHash(mask)
            } catch (error: Throwable) {
                page.recycle()
                mask.recycle()
                throw error
            }
        }

        fun verifyUnchanged() {
            check(!page.isRecycled && !mask.isRecycled)
            check(page.density == Bitmap.DENSITY_NONE && mask.density == Bitmap.DENSITY_NONE)
            check(bitmapHash(page) == original && bitmapHash(mask) == originalMask)
            check(analysis.progress == progress)
            val current = PageProgress.capture(
                page.width,
                page.height,
                analysis.regions,
                progress.regions.map { it.state },
            )
            check(current == liveProgress)
        }

        override fun close() {
            page.recycle()
            mask.recycle()
        }
    }

    private suspend fun awaitQuiescent(client: OkHttpClient) {
        while (client.dispatcher.queuedCallsCount() != 0 || client.dispatcher.runningCallsCount() != 0) delay(10)
    }

    private fun nativeModels(context: Context): List<File> {
        val directory = File(context.filesDir.canonicalFile, "models")
        check(directory.canonicalFile == directory.absoluteFile && directory.isDirectory)
        return listOf(
            "dbnet_detect.ncnn.param",
            "dbnet_detect.ncnn.bin",
            "ocr_48px_ctc.ncnn.param",
            "ocr_48px_ctc_mixed.ncnn.param",
            "ocr_48px_ctc.ncnn.bin",
            "mit_aot_fixed512.ncnn.param",
            "mit_aot_fixed512.ncnn.bin",
        ).map { name ->
            File(directory, name).also { check(it.canonicalFile == it.absoluteFile && it.isFile && it.length() > 0) }
        }
    }

    private fun release(result: PageResult?) {
        when (result) {
            is PageResult.Translated -> {
                result.page.recycle()
                result.analysis?.mask?.recycle()
            }
            is PageResult.Partial -> {
                result.page.recycle()
                result.analysis.mask.recycle()
            }
            is PageResult.Skipped -> result.analysis?.mask?.recycle()
            else -> Unit
        }
    }

    private companion object {
        const val PENDING_TEXT = "こんにちは。"
        fun copyPreferences(raw: Map<String, *>): Map<String, Any?> = raw.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        fun field(owner: Any, name: String): Any = checkNotNull(
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner),
        )
        fun safeException(error: Throwable) = LiveTranslationTimingObserver.safeException(error)
        fun isFatal(error: Throwable) = error is VirtualMachineError || error is ThreadDeath || error is LinkageError
        fun elapsed(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = stream.read(buffer)
                    if (size == -1) break
                    digest.update(buffer, 0, size)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun bitmapHash(bitmap: Bitmap): String {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val bytes = ByteBuffer.allocate(pixels.size * 4).apply { asIntBuffer().put(pixels) }.array()
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        }
    }
}
