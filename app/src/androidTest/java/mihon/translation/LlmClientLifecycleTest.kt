package mihon.translation

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import li.joye.yakuyomi.engine.Yakuyomi
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Explicit audit-only, bounded HTTP/1.1 keep-alive observations. Observe mode does not call retained
 * idle sockets a leak. verifyClosed mode checks a separately implemented owned-client close contract.
 * Factory construction is real only in the second method; neither method calls an image pipeline.
 */
@RunWith(AndroidJUnit4::class)
class LlmClientLifecycleTest {
    @Test
    fun recordsThreeIndependentKeepAliveClientsWithoutNativeInference(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(90_000) {
            withProbe("recordsThreeIndependentKeepAliveClientsWithoutNativeInference", native = false) { probe ->
                KeepAliveServer(expectedClients = 3).use { server ->
                    val translators = mutableListOf<LlmTranslator>()
                    val clients = mutableListOf<OkHttpClient>()
                    var failure: Throwable? = null
                    try {
                        repeat(3) {
                            val translator = LlmTranslator("", translatorConfig(server.url))
                            translators += translator
                            val client = clientOf(translator)
                            clients += client
                            exercise(translator)
                            awaitQuiescent(client)
                        }
                        assertEquals(3, distinctIdentities(clients))
                        assertEquals(3, distinctIdentities(clients.map { it.connectionPool }))
                        assertEquals(3, distinctIdentities(clients.map { it.dispatcher }))
                        assertEquals(3, distinctIdentities(clients.map { it.dispatcher.executorService }))
                        val before = clients.map(::clientState)
                        assertTrue(
                            "Each real keep-alive client must own one idle connection",
                            before.all {
                                it.getInt("connections") == 1 && it.getInt("idleConnections") == 1 &&
                                    !it.getBoolean("executorShutdown")
                            },
                        )
                        probe.report.put("independentClients", 3).put("requestsPerClient", 2)
                            .put("beforeOwnedClose", JSONArray(before))
                        if (probe.verifyClosed) {
                            translators.forEach(::closePubliclyTwice)
                            clients.forEach(::requireClosed)
                            probe.report.put("afterOwnedClose", JSONArray(clients.map(::clientState)))
                                .put("explicitOwnedCloseVerified", true)
                        } else {
                            probe.report.put("explicitOwnedCloseVerified", false)
                                .put("retentionObservedBeforeManualCleanup", true)
                        }
                        probe.report.put("server", server.completedFacts())
                    } catch (error: Throwable) {
                        failure = error
                        throw error
                    } finally {
                        cleanOwnedClients(clients, failure)
                        probe.report.put("afterManualOwnedCleanup", JSONArray(clients.map(::clientState)))
                    }
                }
            }
        }
    }

    @Test
    fun recordsFactoryPipelineCloseWithoutImageInference(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(180_000) {
            withProbe("recordsFactoryPipelineCloseWithoutImageInference", native = true) { probe ->
                check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) {
                    "The native factory observation requires an actual ARM64 process"
                }
                val files = nativeModels(probe.context)
                val beforeModels = modelFacts(files)
                probe.report.put("modelsBefore", beforeModels)
                val models = checkNotNull(ModelSet.resolve(files.map { it.name to it.absolutePath }))
                val alphabet = probe.context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use {
                    it.readLines()
                }
                check(alphabet.size == 19_264 && listOf("。", "、", "「", "」").all { it in alphabet }) {
                    "The expected private OCR alphabet is required"
                }
                KeepAliveServer(expectedClients = 1).use { server ->
                    var engine: TranslationEngine? = null
                    var client: OkHttpClient? = null
                    var engineCloseAttempted = false
                    var failure: Throwable? = null
                    try {
                        val start = SystemClock.elapsedRealtimeNanos()
                        engine = Yakuyomi.create(
                            models,
                            alphabet,
                            "",
                            EngineConfig(
                                translator = translatorConfig(server.url),
                                inpainter = InpainterConfig(method = "boxfill"),
                            ),
                        )
                        probe.report.put("factoryConstructionNanoseconds", SystemClock.elapsedRealtimeNanos() - start)
                        assertTrue("The production factory identity must remain Pipeline", engine is Pipeline)
                        val translator = Pipeline::class.java.getDeclaredField("translator").apply {
                            isAccessible = true
                        }.get(engine) as? LlmTranslator ?: error("The actual factory must own an LLM translator")
                        client = clientOf(translator)
                        exercise(translator)
                        awaitQuiescent(client)
                        val before = clientState(client)
                        assertEquals(1, before.getInt("connections"))
                        assertEquals(1, before.getInt("idleConnections"))
                        assertFalse(before.getBoolean("executorShutdown"))
                        probe.report.put("beforeFactoryClose", before)
                        try {
                            engine.close()
                        } finally {
                            engineCloseAttempted = true
                        }
                        val after = clientState(client)
                        probe.report.put("afterFactoryClose", after)
                            .put("factoryCloseCalledBeforeAnyManualHttpCleanup", true)
                        if (probe.verifyClosed) {
                            requireClosed(client)
                            closePubliclyTwice(translator)
                            requireClosed(client)
                            probe.report.put("factoryOwnedCloseVerified", true)
                        } else {
                            probe.report.put("factoryOwnedCloseVerified", false)
                                .put("idleResourcesRetainedAfterFactoryClose", after.getInt("connections") > 0)
                        }
                        probe.report.put("server", server.completedFacts())
                    } catch (error: Throwable) {
                        failure = error
                        throw error
                    } finally {
                        var cleanupFailure: Throwable? = null
                        if (!engineCloseAttempted) {
                            try {
                                engine?.close()
                            } catch (cleanup: Throwable) {
                                cleanupFailure = cleanup
                            }
                        }
                        try {
                            cleanOwnedClients(listOfNotNull(client), failure)
                            client?.let { probe.report.put("afterManualOwnedCleanup", clientState(it)) }
                            val afterModels = modelFacts(files)
                            probe.report.put("modelsAfter", afterModels)
                            assertEquals(
                                "Factory lifecycle must not modify private model bytes",
                                beforeModels.toString(),
                                afterModels.toString(),
                            )
                        } catch (cleanup: Throwable) {
                            val earlier = cleanupFailure
                            if (earlier == null) cleanupFailure = cleanup else earlier.addSuppressed(cleanup)
                        }
                        cleanupFailure?.let { cleanup ->
                            val earlier = failure
                            if (earlier == null) throw cleanup else earlier.addSuppressed(cleanup)
                        }
                    }
                }
            }
        }
    }

    private suspend fun withProbe(method: String, native: Boolean, block: suspend (Probe) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("llmClientLifecycle") == "true") { "Explicit llmClientLifecycle=true is mandatory" }
        val mode = arguments.getString("llmClientLifecycleMode")
        check(mode in setOf("observe", "verifyClosed")) { "Choose llmClientLifecycleMode=observe or verifyClosed" }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "An isolated audit installation is mandatory" }
        val idleBefore = NativeDiagnosticIdleGuard.requireIdle(context)
        val uuid = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "llm-client-lifecycle-$uuid")
        check(
            !directory.exists() && directory.mkdir() &&
                directory.canonicalFile.parentFile == context.cacheDir.canonicalFile,
        )
        val report = JSONObject().put("schemaVersion", 1).put("runUuid", uuid).put("testMethod", method)
            .put("mode", mode).put("nativeModelConstruction", native).put("imageInferenceCalls", 0)
            .put("nativeForwardCallCountsMeasured", false).put("preferencesWritten", false)
            .put("externalNetworkUsed", false).put("sourceOrResponseBodiesPersisted", false)
            .put("performanceOrPermanentLeakClaimed", false).put("idleBefore", idleBefore)
            .put("startedElapsedRealtimeNanoseconds", SystemClock.elapsedRealtimeNanos()).put("passed", false)
        try {
            block(Probe(context, mode == "verifyClosed", report))
            report.put("idleAfter", NativeDiagnosticIdleGuard.requireIdle(context)).put("passed", true)
        } catch (error: Throwable) {
            report.put("failureClass", error.javaClass.simpleName)
            throw error
        } finally {
            report.put("finishedElapsedRealtimeNanoseconds", SystemClock.elapsedRealtimeNanos())
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            val file = File(directory, "report.json")
            check(file.createNewFile() && file.canonicalFile.parentFile == directory.canonicalFile)
            file.outputStream().use { it.write(bytes) }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "llmClientLifecycleReport",
                        JSONObject().put("relativePath", "cache/${directory.name}/report.json").put("runUuid", uuid)
                            .put(
                                "bytes",
                                bytes.size,
                            ).put("sha256", sha256(bytes)).put("passed", report.getBoolean("passed"))
                            .put("mode", mode).put("testMethod", method).put("nativeModelConstruction", native)
                            .put("imageInferenceCalls", 0).toString(),
                    )
                },
            )
        }
    }

    private suspend fun exercise(translator: LlmTranslator) {
        repeat(2) {
            val result = translator.translateDetailed(SOURCES)
            assertEquals(null, result.error)
            assertEquals(null, result.failure)
            assertEquals(TARGETS, result.translations)
        }
    }

    private fun translatorConfig(url: String) = TranslatorConfig(
        provider = "custom",
        apiBase = url,
        model = "neutral-client-lifecycle",
        fromLangName = "English",
        toLangName = "Korean",
        sampleSource = "",
        sampleTarget = "",
        thinking = false,
    )

    private fun clientOf(translator: LlmTranslator): OkHttpClient =
        LlmTranslator::class.java.getDeclaredField("client").apply {
            isAccessible = true
        }.get(translator) as OkHttpClient

    private suspend fun awaitQuiescent(client: OkHttpClient) = withTimeout(5_000) {
        while (client.dispatcher.runningCallsCount() != 0 || client.dispatcher.queuedCallsCount() != 0) delay(10)
    }

    /** Accessed only after two actual async requests, so this getter cannot create an unused executor. */
    private fun clientState(client: OkHttpClient): JSONObject {
        val executor = client.dispatcher.executorService as? ThreadPoolExecutor
            ?: error("The production dispatcher must expose its already used ThreadPoolExecutor")
        return JSONObject().put("connections", client.connectionPool.connectionCount())
            .put("idleConnections", client.connectionPool.idleConnectionCount())
            .put(
                "runningCalls",
                client.dispatcher.runningCallsCount(),
            ).put("queuedCalls", client.dispatcher.queuedCallsCount())
            .put("executorPoolSize", executor.poolSize).put("executorActiveCount", executor.activeCount)
            .put("executorQueueSize", executor.queue.size).put("executorCompletedTasks", executor.completedTaskCount)
            .put("executorShutdown", executor.isShutdown).put("executorTerminated", executor.isTerminated)
            .put("executorObservedOnlyAfterRealRequests", true)
    }

    private fun closePubliclyTwice(translator: LlmTranslator) {
        val value: Any = translator
        val closeable =
            value as? AutoCloseable ?: error("verifyClosed requires an explicit owned-client close contract")
        closeable.close()
        closeable.close()
    }

    private fun requireClosed(client: OkHttpClient) {
        val state = clientState(client)
        assertEquals(0, state.getInt("connections"))
        assertEquals(0, state.getInt("idleConnections"))
        assertEquals(0, state.getInt("runningCalls"))
        assertEquals(0, state.getInt("queuedCalls"))
        assertTrue(state.getBoolean("executorShutdown"))
    }

    private fun cleanOwnedClients(clients: List<OkHttpClient>, originalFailure: Throwable?) {
        var cleanupFailure: Throwable? = null
        clients.forEach { client ->
            try {
                // Successful measurements reach this path only after quiescence. On a failed or
                // cancelled probe, cancel only this probe's dedicated client before releasing it.
                if (originalFailure != null) client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
                check(client.dispatcher.executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    "Every dedicated probe executor must finish during cleanup"
                }
            } catch (error: Throwable) {
                val earlier = cleanupFailure
                if (earlier == null) cleanupFailure = error else earlier.addSuppressed(error)
            }
        }
        cleanupFailure?.let { if (originalFailure == null) throw it else originalFailure.addSuppressed(it) }
    }

    private fun nativeModels(context: Context): List<File> {
        val directory = File(context.filesDir, "models").canonicalFile
        return MODEL_NAMES.map { name ->
            File(directory, name).also {
                check(it.isFile && it.length() > 0 && it.canonicalFile == it.absoluteFile) {
                    "The required private native model must exist without an alias: $name"
                }
            }
        }
    }

    private fun modelFacts(files: List<File>): JSONArray = JSONArray().apply {
        files.forEach { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            put(JSONObject().put("name", file.name).put("bytes", file.length()).put("sha256", digest.digest().toHex()))
        }
    }

    private fun distinctIdentities(values: List<Any>): Int = IdentityHashMap<Any, Boolean>().apply {
        values.forEach { put(it, true) }
    }.size

    private data class Probe(val context: Context, val verifyClosed: Boolean, val report: JSONObject)

    private class KeepAliveServer(private val expectedClients: Int) : AutoCloseable {
        private val listener = ServerSocket(0, expectedClients, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout =
                1_000
        }
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val handlers = CopyOnWriteArrayList<Thread>()
        private val postsPerConnection = CopyOnWriteArrayList<AtomicInteger>()
        private val failure = AtomicReference<Throwable?>()
        private val closed = AtomicBoolean()
        val url = "http://127.0.0.1:${listener.localPort}/v1/chat/completions"
        private val worker = thread(isDaemon = true, name = "llm-client-lifecycle-accept") {
            try {
                while (!closed.get() && sockets.size < expectedClients) {
                    val socket = try {
                        listener.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    sockets += socket
                    val count = AtomicInteger()
                    postsPerConnection += count
                    handlers += thread(isDaemon = true, name = "llm-client-lifecycle-peer-${sockets.size}") {
                        try {
                            handle(socket, count)
                        } catch (error: Throwable) {
                            if (!closed.get()) failure.compareAndSet(null, error)
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) failure.compareAndSet(null, error)
            }
        }

        private fun handle(socket: Socket, count: AtomicInteger) = socket.use {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            while (!closed.get()) {
                val first = input.readLineCappedOrNull() ?: return@use
                check(first == "POST /v1/chat/completions HTTP/1.1" && count.get() < 2)
                val headers = mutableMapOf<String, String>()
                var headerBytes = first.length
                while (true) {
                    val line = checkNotNull(input.readLineCappedOrNull())
                    if (line.isEmpty()) break
                    headerBytes += line.length
                    check(headerBytes <= 16_384 && headers.size < 64 && ':' in line)
                    val key = line.substringBefore(':').lowercase()
                    check(headers.put(key, line.substringAfter(':').trim()) == null)
                }
                check("authorization" !in headers && headers["connection"]?.lowercase() != "close")
                val size = checkNotNull(headers["content-length"]?.toIntOrNull())
                check(size in 1..65_536)
                val body = ByteArray(size).also { DataInputStream(input).readFully(it) }
                val payload = JSONObject(body.toString(Charsets.UTF_8))
                check(payload.getString("model") == "neutral-client-lifecycle" && !payload.getBoolean("stream"))
                val messages = payload.getJSONArray("messages")
                check(
                    messages.length() == 2 &&
                        (0 until messages.length()).all { messages.getJSONObject(it).get("content") is String },
                )
                check(messages.getJSONObject(1).getString("role") == "user")
                val regions = JSONArray(messages.getJSONObject(1).getString("content"))
                check(
                    regions.length() == 2 && (0..1).all { index ->
                        regions.getJSONObject(index).getInt("id") == index + 1 &&
                            regions.getJSONObject(index).getString("text") == SOURCES[index]
                    },
                )
                val content = TARGETS.mapIndexed { index, target -> "<|${index + 1}|>$target" }.joinToString("\n")
                val response = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content)),
                    ),
                ).toString().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${response.size}\r\nConnection: keep-alive\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    write(response)
                    flush()
                }
                count.incrementAndGet()
            }
        }

        fun completedFacts(): JSONObject {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (failure.get() == null &&
                (sockets.size != expectedClients || postsPerConnection.any { it.get() != 2 }) &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(5)
            }
            failure.get()?.let { throw AssertionError("The owned keep-alive server failed", it) }
            assertEquals(expectedClients, sockets.size)
            assertEquals(List(expectedClients) { 2 }, postsPerConnection.map { it.get() })
            return JSONObject().put("connectionsAccepted", sockets.size).put(
                "postsCompleted",
                postsPerConnection.sumOf {
                    it.get()
                },
            )
                .put(
                    "postsPerConnection",
                    JSONArray(
                        postsPerConnection.map {
                            it.get()
                        },
                    ),
                ).put("responseConnectionClose", false)
                .put("twoRegionIdsValidatedPerPost", true).put("imageMessages", 0).put("authorizationHeaders", 0)
        }

        override fun close() {
            closed.set(true)
            listener.close()
            sockets.forEach { it.close() }
            worker.join(5_000)
            handlers.forEach { it.join(5_000) }
            check(!worker.isAlive && handlers.none { it.isAlive }) { "Every owned server thread must finish" }
            failure.get()?.let { throw AssertionError("The owned keep-alive server failed", it) }
        }

        private fun InputStream.readLineCappedOrNull(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = read()
                if (value == -1) {
                    check(bytes.size() == 0) { "Truncated bounded request line" }
                    return null
                }
                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                check(bytes.size() < 4_096)
                bytes.write(value)
            }
        }
    }

    private companion object {
        val SOURCES = listOf("Neutral sentence one.", "Neutral sentence two.")
        val TARGETS = listOf("중립 번역 하나입니다.", "중립 번역 둘입니다.")
        val MODEL_NAMES = listOf(
            "dbnet_detect.ncnn.param",
            "dbnet_detect.ncnn.bin",
            "ocr_48px_ctc.ncnn.param",
            "ocr_48px_ctc_mixed.ncnn.param",
            "ocr_48px_ctc.ncnn.bin",
            "mit_aot_fixed512.ncnn.param",
            "mit_aot_fixed512.ncnn.bin",
        )

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
