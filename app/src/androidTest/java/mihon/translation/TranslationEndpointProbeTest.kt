package mihon.translation

import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.LlmModels
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.ModelSource
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Network-only audit; no native/model-weight, preference or credential API is accessed. */
@RunWith(AndroidJUnit4::class)
class TranslationEndpointProbeTest {
    @Test
    fun discoverModelsAndTranslateNeutralBatch() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("translationEndpointProbe") == "true") { "Explicit endpoint probe opt-in required" }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit package required" }
        val base = arguments.getString("liveTranslationBase").orEmpty()
        val model = arguments.getString("liveTranslationModel").orEmpty()
        val runId = arguments.getString("translationEndpointProbeRunId").orEmpty()
        check(runId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        check(UUID.fromString(runId).toString() == runId) { "Canonical run UUID required" }
        val uri = runCatching { URI(base) }.getOrNull()
        check(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank())
        check(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        check(uri.port == -1 || uri.port in 1..65535)
        check(model.isNotBlank() && model.length <= 256 && model.none(Char::isISOControl))
        val preset = LlmProviders.byId("custom")
        val modelsUrl = LlmProviders.modelsUrlOf(preset, base)
        val chatUrl = LlmProviders.chatUrlOf(preset, base)
        val files = context.filesDir.canonicalFile
        check(files.isDirectory && files.name == "files")
        check(files.parentFile == File(context.applicationInfo.dataDir).canonicalFile)
        val output = File(files, "translation-endpoint-probe-$runId")
        check(output.canonicalFile == output.absoluteFile && output.parentFile == files && output.mkdir())
        val reportFile = File(output, "result.json")
        val started = SystemClock.elapsedRealtimeNanos()
        var stage = "READY"
        var stageStarted = started
        var failed = false
        var cancelled = false
        val report = JSONObject().put("schema", 1).put("passed", false).put("finalized", false)
            .put("modelsEndpointSha256", sha(modelsUrl)).put("chatEndpointSha256", sha(chatUrl))
            .put("modelSha256", sha(model)).put("modelCount", 0).put("targetCount", 0)
            .put("nativeApiCallsByTest", 0).put("nativeEnginesCreatedByTest", 0).put("modelDownloadsByTest", 0)
            .put("modelWeightFilesReadByTest", 0).put("preferencesAccessedByTest", false)
            .put("credentialApisAccessedByTest", false).put("apiKeyUsed", false)
            .put("logicalTranslationBatchesAttempted", 0).put("actualHttpAttemptsMeasured", false)
            .put("timeoutMs", 120_000).put("clock", "elapsedRealtimeNanos")
        fun checkpoint() {
            check(output.canonicalFile == output.absoluteFile && reportFile.canonicalFile.parentFile == output)
            report.put("stage", stage).put("elapsedMs", elapsed(started))
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            check(bytes.size <= 16_384) { "Sanitized report exceeded bound" }
            val atomic = AtomicFile(reportFile)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
            runCatching {
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString("stream", "TRANSLATION_ENDPOINT_PROBE=$stage;report=${reportFile.path}")
                    },
                )
            } // Progress delivery is optional; the atomic report and JUnit result are authoritative.
        }
        try {
            withTimeout(120_000) {
                stage = "MODELS"
                stageStarted = SystemClock.elapsedRealtimeNanos()
                checkpoint()
                val models = LlmModels.list(modelsUrl, ModelSource.OPENAI, "")
                report.put("modelsElapsedMs", elapsed(stageStarted)).put("modelCount", models.size)
                safeApiError(LlmModels.lastError)?.let { report.put("lastError", it) }
                check(models.any { it.id == model }) { "Requested model absent from discovery response" }
                report.put("requestedModelPresent", true)
                stage = "TRANSLATION"
                stageStarted = SystemClock.elapsedRealtimeNanos()
                report.put("logicalTranslationBatchesAttempted", 1)
                checkpoint()
                val translator = LlmTranslator(
                    "",
                    TranslatorConfig(
                        provider = "custom",
                        model = model,
                        apiBase = chatUrl,
                        targetLang = "KOR",
                        fromLangName = "Japanese",
                        toLangName = "Korean",
                        sampleSource = "",
                        sampleTarget = "",
                        temperature = 0.0,
                        thinking = false,
                    ),
                )
                val translated = translator.use {
                    it.translateDetailed(listOf("こんにちは。", "今日はいい天気ですね。"))
                }
                report.put("translationElapsedMs", elapsed(stageStarted))
                    .put("targetCount", translated.translations.size)
                safeApiError(translated.error)?.let { report.put("lastError", it) }
                check(translated.error == null) { "Translation API returned a sanitized failure" }
                val nonblank = translated.translations.map { it.isNotBlank() }
                val hangul = translated.translations.map { Regex("[가-힣]").containsMatchIn(it) }
                report.put("targetsNonblank", JSONArray(nonblank)).put("targetsContainHangul", JSONArray(hangul))
                check(translated.translations.size == 2 && nonblank.all { it } && hangul.all { it }) {
                    "Expected exactly two nonblank Korean translations"
                }
                check(elapsed(started) <= 120_000.0) { "Total endpoint probe deadline exceeded" }
                report.put("passed", true)
            }
        } catch (error: Throwable) {
            failed = true
            cancelled = error is CancellationException
            report.put("firstFailureStage", stage).put("failureClass", error.javaClass.simpleName)
                .put("failedStageElapsedMs", elapsed(stageStarted)).put("cancelled", cancelled)
            if (!report.has("lastError")) report.put("lastError", error.javaClass.simpleName)
        } finally {
            try {
                check(output.list()?.toSet() == setOf("result.json")) { "Unexpected owned report inventory" }
            } catch (error: Throwable) {
                if (!failed) report.put("firstFailureStage", "REPORT_INVENTORY")
                failed = true
                report.put("reportInventoryFailureClass", error.javaClass.simpleName)
            }
            stage = if (failed) "FAILED" else "COMPLETED"
            report.put("passed", !failed).put("finalized", true)
            try {
                checkpoint()
            } catch (_: Throwable) {
                failed = true // A report-write failure must never produce a passing JUnit result.
            }
        }
        if (cancelled) throw CancellationException("Endpoint probe cancelled; inspect sanitized report")
        check(!failed) { "Endpoint probe failed; inspect sanitized report" }
    }

    private fun safeApiError(value: String?): String? = when {
        value == null -> null
        value.matches(Regex("HTTP [1-5][0-9]{2}")) -> value
        value == "HTTP response exceeds 1 MiB" -> "ResponseTooLarge"
        value.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]{0,100}")) -> value
        else -> "UnclassifiedApiFailure"
    }

    private fun elapsed(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
