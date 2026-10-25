package mihon.translation

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.TranslatorConfig
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Fixed-only owned-client contract checks. Every request is a bounded neutral loopback request.
 * Only the public-constructor case creates native models; no case calls an image pipeline.
 */
@RunWith(AndroidJUnit4::class)
class LlmOwnedCloseIntegrationTest {
    @Test(timeout = 90_000)
    fun closeDuringGatewayRetryPreservesExistingBatchAndRejectsNewCalls(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(80_000) {
            withProbe("closeDuringGatewayRetryPreservesExistingBatchAndRejectsNewCalls", native = false) { probe ->
                coroutineScope {
                    val server = ScenarioServer(Scenario.RETRY_GATE)
                    val translator = LlmTranslator("", translatorConfig(server.url))
                    val client = clientOf(translator)
                    var active: Job? = null
                    var failure: Throwable? = null
                    try {
                        val result = async { translator.translateDetailed(SOURCES) }.also { active = it }
                        server.awaitFirstRequest()
                        closePublicly(translator)
                        val rejected = translator.translateDetailed(NEW_SOURCES)
                        assertNotNull("A new logical batch must be rejected after close", rejected.error)
                        assertEquals(NEW_SOURCES, rejected.translations)
                        assertEquals(
                            "The held first batch is the only request before its response",
                            1,
                            server.postCount(),
                        )
                        assertTrue("The already started batch must still be active", result.isActive)
                        assertFalse(
                            "Closing during a live batch must not shut its executor down",
                            client.dispatcher.executorService.isShutdown,
                        )

                        server.releaseFirstResponse()
                        val translated = result.await()
                        assertEquals(null, translated.error)
                        assertEquals(null, translated.failure)
                        assertEquals(TARGETS, translated.translations)
                        awaitClosed(client)
                        closePublicly(translator)
                        val facts = server.requireCompleted(expectedPosts = 2)
                        assertEquals(
                            "The gateway retry must reuse its keep-alive connection",
                            1,
                            facts.getInt("connectionsAccepted"),
                        )
                        assertTrue(
                            "The physical retry must preserve the whole logical batch",
                            facts.getBoolean("allRequestBodiesIdentical"),
                        )
                        probe.report.put("server", facts).put("newBatchRejectedWithoutHttp", true)
                            .put("existingFullBatchSucceededAfterGatewayRetry", true).put("logicalBatchesSent", 1)
                            .put("physicalPosts", 2).put("ownedClientAfterDrain", clientState(client))
                    } catch (error: Throwable) {
                        failure = error
                        throw error
                    } finally {
                        cleanupOwned(translator, client, active, server, failure)
                    }
                }
            }
        }
    }

    @Test(timeout = 90_000)
    fun cancelHeldResponseAfterCloseDrainsCallbacksAndPreservesAnUnrelatedClient(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(80_000) {
            withProbe(
                "cancelHeldResponseAfterCloseDrainsCallbacksAndPreservesAnUnrelatedClient",
                native = false,
            ) { probe ->
                coroutineScope {
                    val heldServer = ScenarioServer(Scenario.HELD_BODY)
                    val otherServer = ScenarioServer(Scenario.TWO_SUCCESSES)
                    val translator = LlmTranslator("", translatorConfig(heldServer.url))
                    val other = LlmTranslator("", translatorConfig(otherServer.url))
                    val client = clientOf(translator)
                    val otherClient = clientOf(other)
                    var active: Job? = null
                    var failure: Throwable? = null
                    val returnedNormally = AtomicBoolean()
                    val propagated = AtomicReference<Throwable?>()
                    try {
                        assertNotSame(client.connectionPool, otherClient.connectionPool)
                        assertNotSame(client.dispatcher, otherClient.dispatcher)
                        val result = async {
                            try {
                                translator.translateDetailed(SOURCES).also { returnedNormally.set(true) }
                            } catch (error: Throwable) {
                                propagated.set(error)
                                throw error
                            }
                        }.also { active = it }
                        heldServer.awaitPartialBody()
                        requireSuccessful(other)
                        closePublicly(translator)
                        val rejected = translator.translateDetailed(NEW_SOURCES)
                        assertNotNull(rejected.error)
                        assertEquals(NEW_SOURCES, rejected.translations)
                        // The peer is already polling its socket. This negative observation tests
                        // close itself; the explicit coroutine cancellation below is a separate action.
                        assertFalse("Close must not cancel the live body read", heldServer.awaitPeerClose(200))
                        assertTrue(result.isActive)
                        assertFalse(client.dispatcher.executorService.isShutdown)
                        result.cancel(CancellationException("neutral owned-client cancellation"))
                        try {
                            result.await()
                            fail("The held request must propagate cancellation")
                        } catch (_: CancellationException) {
                            // The direct invocation catch below independently distinguishes a normal return.
                        }
                        result.join()
                        assertTrue(
                            "The translator invocation itself must propagate cancellation",
                            propagated.get() is CancellationException,
                        )
                        assertFalse("Cancellation cannot become a successful/fallback result", returnedNormally.get())
                        assertTrue("The cancelled socket must close at the peer", heldServer.awaitPeerClose(5_000))
                        awaitClosed(client)
                        requireSuccessful(other)
                        awaitQuiescent(otherClient)
                        assertFalse(
                            "An unrelated translator must remain open",
                            otherClient.dispatcher.executorService.isShutdown,
                        )
                        assertEquals(1, otherClient.connectionPool.idleConnectionCount())
                        val heldFacts = heldServer.requireCompleted(expectedPosts = 1)
                        val otherFacts = otherServer.requireCompleted(expectedPosts = 2)
                        closePublicly(other)
                        awaitClosed(otherClient)
                        probe.report.put("cancelledServer", heldFacts).put("unrelatedServer", otherFacts)
                            .put("newBatchRejectedWithoutHttp", true).put("directCancellationPropagated", true)
                            .put("liveRequestCancelledByClose", false)
                            .put("unrelatedClientWorkedBeforeAndAfterCancellation", true)
                            .put("ownedClientAfterCallbackDrain", clientState(client))
                            .put("unrelatedClientAfterExplicitClose", clientState(otherClient))
                    } catch (error: Throwable) {
                        failure = error
                        throw error
                    } finally {
                        var cleanupFailure: Throwable? = null
                        try {
                            cleanupOwned(translator, client, active, heldServer, failure)
                        } catch (cleanup: Throwable) {
                            cleanupFailure = cleanup
                        }
                        try {
                            cleanupOwned(other, otherClient, null, otherServer, failure)
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

    @Test(timeout = 180_000)
    fun publicSixArgumentPipelineKeepsItsTranslatorBorrowed(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(170_000) {
            withProbe("publicSixArgumentPipelineKeepsItsTranslatorBorrowed", native = true) { probe ->
                check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) {
                    "An actual ARM64 process is required"
                }
                val files = nativeModels(probe.context)
                val beforeModels = modelFacts(files)
                probe.report.put("modelsBefore", beforeModels)
                val models = checkNotNull(ModelSet.resolve(files.map { it.name to it.absolutePath }))
                val alphabet = probe.context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use {
                    it.readLines()
                }
                check(alphabet.size == 19_264 && listOf("。", "、", "「", "」").all { it in alphabet })
                val server = ScenarioServer(Scenario.TWO_SUCCESSES)
                val translator = LlmTranslator("", translatorConfig(server.url))
                val client = clientOf(translator)
                val config = EngineConfig(
                    translator = translatorConfig(server.url),
                    inpainter = InpainterConfig(method = "boxfill"),
                )
                var detector: Detector? = null
                var ocr: Ocr? = null
                var inpainter: Inpainter? = null
                var nativeClosed = false
                var failure: Throwable? = null
                try {
                    // Direct construction keeps one clear native owner. There is no second factory
                    // Pipeline retaining the same models and no image/warm-up inference is invoked.
                    detector = Detector(checkNotNull(models.detectorNcnn), config.detector)
                    ocr = Ocr(models.ocr, alphabet, config.ocr)
                    inpainter = Inpainter(checkNotNull(models.aotInpainterNcnn), config.inpainter)
                    val pipeline = Pipeline(detector, ocr, translator, inpainter, config, null)
                    pipeline.close()
                    assertZeroNativeHandle(detector)
                    assertZeroNativeHandle(ocr)
                    assertZeroNativeHandle(inpainter)
                    nativeClosed = true
                    requireSuccessful(translator)
                    pipeline.close()
                    requireSuccessful(translator)
                    awaitQuiescent(client)
                    assertFalse(
                        "The public constructor borrows even an AutoCloseable translator",
                        client.dispatcher.executorService.isShutdown,
                    )
                    assertEquals(1, client.connectionPool.idleConnectionCount())
                    val beforeExplicitClose = clientState(client)
                    closePublicly(translator)
                    closePublicly(translator)
                    awaitClosed(client)
                    probe.report.put("server", server.requireCompleted(expectedPosts = 2))
                        .put("nativeConstructionRoute", "direct_components").put("factoryInvoked", false)
                        .put("publicSixArgumentConstructorUsed", true)
                        .put("nativeHandleFieldsZeroAfterPipelineClose", 3)
                        .put("borrowedTranslatorWorkedAfterBothPipelineCloses", true)
                        .put("borrowedClientBeforeExplicitClose", beforeExplicitClose)
                        .put("borrowedClientAfterExplicitClose", clientState(client))
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    var cleanupFailure: Throwable? = null
                    if (!nativeClosed) {
                        listOfNotNull<AutoCloseable>(inpainter, ocr, detector).forEach { resource ->
                            try {
                                resource.close()
                            } catch (cleanup: Throwable) {
                                val earlier = cleanupFailure
                                if (earlier == null) cleanupFailure = cleanup else earlier.addSuppressed(cleanup)
                            }
                        }
                    }
                    try {
                        cleanupOwned(translator, client, null, server, failure)
                    } catch (cleanup: Throwable) {
                        val earlier = cleanupFailure
                        if (earlier == null) cleanupFailure = cleanup else earlier.addSuppressed(cleanup)
                    }
                    try {
                        val afterModels = modelFacts(files)
                        probe.report.put("modelsAfter", afterModels)
                        assertEquals(
                            "Private model bytes must remain exact",
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

    private suspend fun withProbe(method: String, native: Boolean, block: suspend (Probe) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("llmOwnedClose") == "true") {
            "Explicit llmOwnedClose=true is mandatory"
        }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "An isolated audit installation is mandatory" }
        val idleBefore = NativeDiagnosticIdleGuard.requireIdle(context)
        val uuid = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "llm-owned-close-$uuid")
        check(
            !directory.exists() && directory.mkdir() &&
                directory.canonicalFile.parentFile == context.cacheDir.canonicalFile,
        )
        val report = JSONObject().put("schemaVersion", 1).put("runUuid", uuid).put("testMethod", method)
            .put("nativeModelConstruction", native).put("imageInferenceCalls", 0)
            .put("nativeForwardCallCountsMeasured", false)
            .put("preferencesWritten", false).put("externalNetworkUsed", false)
            .put("sourceOrResponseBodiesPersisted", false)
            .put("hostOrClientCancelAllUsed", false).put("shutdownNowUsed", false)
            .put("performanceOrPermanentLeakClaimed", false)
            .put("idleBefore", idleBefore).put("startedElapsedRealtimeNanoseconds", SystemClock.elapsedRealtimeNanos())
            .put("passed", false)
        try {
            check(AutoCloseable::class.java.isAssignableFrom(LlmTranslator::class.java)) {
                "The explicit owned-client close feature is required"
            }
            block(Probe(context, report))
            report.put("idleAfter", NativeDiagnosticIdleGuard.requireIdle(context)).put("passed", true)
        } catch (error: Throwable) {
            report.put("failureClass", error.javaClass.simpleName)
            throw error
        } finally {
            report.put("finishedElapsedRealtimeNanoseconds", SystemClock.elapsedRealtimeNanos())
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            val file = File(directory, "report.json")
            check(!file.exists() && file.canonicalFile.parentFile == directory.canonicalFile)
            FileOutputStream(file).use {
                it.write(bytes)
                it.fd.sync()
            }
            check(file.readBytes().contentEquals(bytes))
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "llmOwnedCloseReport",
                        JSONObject().put("relativePath", "cache/${directory.name}/report.json")
                            .put("runUuid", uuid).put("bytes", bytes.size).put("sha256", sha256(bytes))
                            .put("passed", report.getBoolean("passed")).put("testMethod", method)
                            .put("nativeModelConstruction", native).put("imageInferenceCalls", 0).toString(),
                    )
                },
            )
        }
    }

    private suspend fun requireSuccessful(translator: LlmTranslator) {
        val result = translator.translateDetailed(SOURCES)
        assertEquals(null, result.error)
        assertEquals(null, result.failure)
        assertEquals(TARGETS, result.translations)
    }

    private fun translatorConfig(url: String) = TranslatorConfig(
        provider = "custom",
        apiBase = url,
        model = "neutral-owned-close-integration",
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

    private fun closePublicly(translator: LlmTranslator) {
        val value: Any = translator
        checkNotNull(value as? AutoCloseable) { "The explicit owned-client close feature is required" }.close()
    }

    private suspend fun awaitQuiescent(client: OkHttpClient) = withTimeout(5_000) {
        while (client.dispatcher.queuedCallsCount() != 0 || client.dispatcher.runningCallsCount() != 0) delay(10)
    }

    private suspend fun awaitClosed(client: OkHttpClient) = withTimeout(5_000) {
        while (client.connectionPool.connectionCount() != 0 || client.connectionPool.idleConnectionCount() != 0 ||
            client.dispatcher.queuedCallsCount() != 0 || client.dispatcher.runningCallsCount() != 0 ||
            !client.dispatcher.executorService.isShutdown
        ) {
            delay(10)
        }
        assertTrue(client.dispatcher.executorService.isShutdown)
    }

    /** This is called only after an actual request has used the dedicated executor. */
    private fun clientState(client: OkHttpClient): JSONObject {
        val executor = client.dispatcher.executorService as? ThreadPoolExecutor
            ?: error("Expected the used production executor")
        return JSONObject().put("connections", client.connectionPool.connectionCount())
            .put("idleConnections", client.connectionPool.idleConnectionCount())
            .put("runningCalls", client.dispatcher.runningCallsCount())
            .put("queuedCalls", client.dispatcher.queuedCallsCount())
            .put("executorPoolSize", executor.poolSize).put("executorActiveCount", executor.activeCount)
            .put("executorShutdown", executor.isShutdown).put("executorTerminated", executor.isTerminated)
    }

    private suspend fun cleanupOwned(
        translator: LlmTranslator,
        client: OkHttpClient,
        active: Job?,
        server: ScenarioServer,
        originalFailure: Throwable?,
    ) = withContext(NonCancellable) {
        var cleanupFailure: Throwable? = null
        fun record(error: Throwable) {
            val earlier = cleanupFailure
            if (earlier == null) cleanupFailure = error else earlier.addSuppressed(error)
        }
        server.releaseFirstResponse()
        try {
            active?.cancelAndJoin()
        } catch (cleanup: Throwable) {
            record(cleanup)
        }
        try {
            server.close()
        } catch (cleanup: Throwable) {
            record(cleanup)
        }
        try {
            closePublicly(translator)
        } catch (cleanup: Throwable) {
            record(cleanup)
        }
        try {
            // A failed fixture still owns these exact resources. Graceful cleanup never traverses
            // other clients, cancels their calls, or interrupts executor workers.
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            check(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            client.connectionPool.evictAll()
        } catch (cleanup: Throwable) {
            record(cleanup)
        }
        cleanupFailure?.let { if (originalFailure == null) throw it else originalFailure.addSuppressed(it) }
    }

    private fun nativeModels(context: Context): List<File> {
        val directory = File(context.filesDir, "models").canonicalFile
        return MODEL_NAMES.map { name ->
            File(directory, name).also {
                check(it.isFile && it.length() > 0 && it.canonicalFile == it.absoluteFile)
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

    private fun assertZeroNativeHandle(component: Any) {
        val field = component.javaClass.getDeclaredField("ncnnHandle").apply { isAccessible = true }
        assertEquals("A directly owned native component must be closed", 0L, field.getLong(component))
    }

    private data class Probe(val context: Context, val report: JSONObject)
    private enum class Scenario { RETRY_GATE, HELD_BODY, TWO_SUCCESSES }

    /** Bounded HTTP/1.1 peers; request bytes live only in RAM and only their hashes enter the report. */
    private class ScenarioServer(private val scenario: Scenario) : AutoCloseable {
        private val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val closed = AtomicBoolean()
        private val failure = AtomicReference<Throwable?>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val handlers = CopyOnWriteArrayList<Thread>()
        private val requests = CopyOnWriteArrayList<ByteArray>()
        private val accepted = AtomicInteger()
        private val firstRequest = CountDownLatch(1)
        private val firstResponse = CountDownLatch(1)
        private val partialBody = CountDownLatch(1)
        private val peerClosed = CountDownLatch(1)
        private val peerCloseKind = AtomicReference<String?>()
        val url = "http://127.0.0.1:${listener.localPort}/v1/chat/completions"
        private val acceptor = thread(isDaemon = true, name = "llm-owned-close-accept") {
            try {
                while (!closed.get()) {
                    val socket = try {
                        listener.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    check(accepted.incrementAndGet() <= 2) { "Unexpected additional owned test connection" }
                    sockets += socket
                    handlers += thread(isDaemon = true, name = "llm-owned-close-peer-${accepted.get()}") {
                        try {
                            handle(socket)
                        } catch (error: Throwable) {
                            if (!closed.get()) failure.compareAndSet(null, error)
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) failure.compareAndSet(null, error)
            }
        }

        private fun handle(socket: Socket) = socket.use {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            while (!closed.get()) {
                val body = readRequest(input) ?: return@use
                val index = synchronized(requests) {
                    check(requests.size < if (scenario == Scenario.HELD_BODY) 1 else 2)
                    requests += body
                    requests.size
                }
                firstRequest.countDown()
                when {
                    scenario == Scenario.RETRY_GATE && index == 1 -> {
                        check(firstResponse.await(10, TimeUnit.SECONDS)) {
                            "The first response gate was never released"
                        }
                        writeResponse(
                            socket,
                            503,
                            "{\"error\":\"neutral temporary failure\"}".toByteArray(Charsets.UTF_8),
                        )
                    }
                    scenario == Scenario.HELD_BODY -> {
                        val response = successBody()
                        val output = socket.getOutputStream()
                        output.write(headers(200, response.size))
                        output.write(response, 0, response.size / 2)
                        output.flush()
                        socket.soTimeout = 200
                        partialBody.countDown()
                        val deadline = SystemClock.elapsedRealtime() + 10_000
                        while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
                            try {
                                val value = input.read()
                                check(value == -1) { "Unexpected bytes while a response body was held" }
                                peerCloseKind.compareAndSet(null, "eof")
                                peerClosed.countDown()
                                return@use
                            } catch (_: SocketTimeoutException) {
                                // The explicit cancellation action must close the socket.
                            } catch (_: SocketException) {
                                peerCloseKind.compareAndSet(null, "socket_reset")
                                peerClosed.countDown()
                                return@use
                            }
                        }
                        check(closed.get()) { "The held response socket did not close after cancellation" }
                    }
                    else -> writeResponse(socket, 200, successBody())
                }
            }
        }

        private fun readRequest(input: InputStream): ByteArray? {
            val first = input.readLineCappedOrNull() ?: return null
            check(first == "POST /v1/chat/completions HTTP/1.1")
            val headers = mutableMapOf<String, String>()
            var headerBytes = first.length
            while (true) {
                val line = checkNotNull(input.readLineCappedOrNull())
                if (line.isEmpty()) break
                headerBytes += line.length
                check(headerBytes <= 16_384 && headers.size < 64 && ':' in line)
                check(headers.put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim()) == null)
            }
            check("authorization" !in headers && headers["connection"]?.lowercase() != "close")
            val length = checkNotNull(headers["content-length"]?.toIntOrNull())
            check(length in 1..65_536)
            val body = ByteArray(length).also { DataInputStream(input).readFully(it) }
            val payload = JSONObject(body.toString(Charsets.UTF_8))
            check(payload.getString("model") == "neutral-owned-close-integration" && !payload.getBoolean("stream"))
            val messages = payload.getJSONArray("messages")
            check(messages.length() == 2 && messages.getJSONObject(1).getString("role") == "user")
            check((0 until messages.length()).all { messages.getJSONObject(it).get("content") is String })
            val regions = JSONArray(messages.getJSONObject(1).getString("content"))
            check(
                regions.length() == 2 && (0..1).all { index ->
                    regions.getJSONObject(index).getInt("id") == index + 1 &&
                        regions.getJSONObject(index).getString("text") == SOURCES[index]
                },
            )
            return body
        }

        fun awaitFirstRequest() {
            check(firstRequest.await(5, TimeUnit.SECONDS)) { "The owned server did not receive the first request" }
            requireHealthy()
        }

        fun awaitPartialBody() {
            check(partialBody.await(5, TimeUnit.SECONDS)) { "The owned server did not start its partial body" }
            requireHealthy()
        }

        fun releaseFirstResponse() = firstResponse.countDown()
        fun postCount(): Int = requests.size
        fun awaitPeerClose(milliseconds: Long): Boolean = peerClosed.await(milliseconds, TimeUnit.MILLISECONDS)

        private fun requireHealthy() {
            failure.get()?.let { throw AssertionError("The owned neutral HTTP server failed", it) }
        }

        fun requireCompleted(expectedPosts: Int): JSONObject {
            requireHealthy()
            assertEquals(expectedPosts, requests.size)
            assertEquals(1, accepted.get())
            if (requests.size == 2) assertArrayEquals(requests[0], requests[1])
            return JSONObject().put("connectionsAccepted", accepted.get()).put("postsReceived", requests.size)
                .put("requestBodySha256", JSONArray(requests.map(::sha256)))
                .put("allRequestBodiesIdentical", requests.distinctBy(::sha256).size == 1)
                .put("twoRegionIdsValidatedPerPost", true).put("imageMessages", 0).put("authorizationHeaders", 0)
                .put("responseConnectionClose", false)
                .put("cancelledPeerClosure", peerCloseKind.get() ?: JSONObject.NULL)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            firstResponse.countDown()
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            acceptor.join(5_000)
            handlers.forEach { it.join(5_000) }
            check(!acceptor.isAlive && handlers.none { it.isAlive }) { "All owned HTTP threads must finish" }
            requireHealthy()
        }

        private fun headers(status: Int, size: Int): ByteArray = (
            "HTTP/1.1 $status ${if (status == 200) "OK" else "Service Unavailable"}\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: $size\r\nConnection: keep-alive\r\nCache-Control: no-store\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)

        private fun writeResponse(socket: Socket, status: Int, body: ByteArray) {
            socket.getOutputStream().apply {
                write(headers(status, body.size))
                write(body)
                flush()
            }
        }

        private fun successBody(): ByteArray {
            val content = TARGETS.mapIndexed { index, target -> "<|${index + 1}|>$target" }.joinToString("\n")
            return JSONObject().put(
                "choices",
                JSONArray().put(
                    JSONObject().put("finish_reason", "stop")
                        .put("message", JSONObject().put("content", content)),
                ),
            ).toString().toByteArray(Charsets.UTF_8)
        }

        private fun InputStream.readLineCappedOrNull(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = read()
                if (value == -1) {
                    check(bytes.size() == 0) { "Truncated HTTP line" }
                    return null
                }
                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                check(bytes.size() < 4_096)
                bytes.write(value)
            }
        }
    }

    private companion object {
        val SOURCES = listOf("Neutral first sentence.", "Neutral second sentence.")
        val NEW_SOURCES = listOf("Different neutral first sentence.", "Different neutral second sentence.")
        val TARGETS = listOf("첫 번째 중립 번역입니다.", "두 번째 중립 번역입니다.")
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
