package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Actual bounded loopback HTTP through LlmTranslator, without native inference or external services.
 * Socket EOF confirms peer release for each Connection: close response; it does not count explicit
 * ResponseBody.close calls or establish production server/reader/native recovery.
 */
@RunWith(AndroidJUnit4::class)
class GatewayHttpRecoveryTest {
    @Before
    fun requireAuditPackageBeforeCreatingTranslatorOrServer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Only the isolated audit package may run this fixture", AUDIT_PACKAGE, context.packageName)
    }

    @Test
    fun transientGatewayErrorsRetryIdenticalWholePageBatchOverActualHttp() = runBlocking<Unit> {
        val queries = listOf("合成の一行目です。", "「二行目」です。", "三行目\n続きの行。")
        val originalQueries = queries.toList()
        val targets = listOf("합성 첫 문장입니다.", "두 번째 문장입니다.", "세 번째 문장과 다음 줄입니다.")
        val content = targetContent(targets)
        for (status in listOf(502, 503, 504)) {
            GatewayServer(listOf(errorReply(status, queries), successReply(queries, content, 41, 17))).use { server ->
                val result = withTimeout(CALL_DEADLINE_MS) { translator(server).translateDetailed(queries) }
                server.assertFinishedAndReleased()
                assertNull("HTTP $status must recover with the single permitted retry", result.error)
                assertEquals(targets, result.translations)
                assertEquals(content, result.raw)
                assertNotNull(result.usage)
                assertEquals(41, result.usage?.promptTokens)
                assertEquals(17, result.usage?.completionTokens)
                assertEquals("The caller's entire source page must remain unchanged", originalQueries, queries)
                assertEquals(2, server.requests.size)
                assertArrayEquals(
                    "The physical retry must preserve the complete logical page request byte for byte",
                    server.requests[0].bytes,
                    server.requests[1].bytes,
                )
                assertEquals(
                    server.requests[0].json.getJSONArray("messages").toString(),
                    server.requests[1].json.getJSONArray("messages").toString(),
                )
            }
        }
    }

    @Test
    fun sameTranslatorRecoversForNextPageAfterPersistentGatewayFailureWithoutMixingResults() = runBlocking<Unit> {
        val failedQueries = listOf("未解決の合成文です。", "まだ翻訳されていない文です。")
        val nextQueries = listOf("別ページの第一文。", "別ページの第二文。", "別ページの第三文。")
        val failedSourceSnapshot = failedQueries.toList()
        val nextSourceSnapshot = nextQueries.toList()
        val targets = listOf("다음 페이지 첫 문장입니다.", "다음 페이지 둘째 문장입니다.", "다음 페이지 셋째 문장입니다.")
        val nextContent = targetContent(targets)
        val replies = listOf(
            errorReply(502, failedQueries),
            errorReply(502, failedQueries),
            successReply(nextQueries, nextContent, 53, 23),
        )
        GatewayServer(replies).use { server ->
            val sameTranslator = translator(server)
            val failed = withTimeout(CALL_DEADLINE_MS) { sameTranslator.translateDetailed(failedQueries) }
            assertEquals("HTTP 502", failed.error)
            assertEquals("An unresolved page must retain all caller source text", failedQueries, failed.translations)
            assertNull("Provider error bodies must not become result diagnostics", failed.raw)
            assertNull("A failed page must not inherit usage", failed.usage)

            val recovered = withTimeout(CALL_DEADLINE_MS) { sameTranslator.translateDetailed(nextQueries) }
            server.assertFinishedAndReleased()
            assertNull("A new page must not inherit the previous HTTP failure", recovered.error)
            assertEquals(targets, recovered.translations)
            assertEquals("Only the new page's completion may be retained", nextContent, recovered.raw)
            assertNotNull(recovered.usage)
            assertEquals(53, recovered.usage?.promptTokens)
            assertEquals(23, recovered.usage?.completionTokens)
            assertEquals("The prior per-call failure must remain unchanged", "HTTP 502", failed.error)
            assertEquals(failedSourceSnapshot, failed.translations)
            assertNull(failed.raw)
            assertNull(failed.usage)
            assertEquals(failedSourceSnapshot, failedQueries)
            assertEquals(nextSourceSnapshot, nextQueries)
            assertEquals(3, server.requests.size)
            assertArrayEquals(server.requests[0].bytes, server.requests[1].bytes)
            assertFalse(
                "A different page must not reuse the failed page's HTTP request",
                server.requests[1].bytes.contentEquals(server.requests[2].bytes),
            )
            assertFalse(
                server.requests[1].json.getJSONArray("messages").toString() ==
                    server.requests[2].json.getJSONArray("messages").toString(),
            )
        }
    }

    private fun translator(server: GatewayServer) = LlmTranslator(
        "",
        TranslatorConfig(
            provider = "custom",
            apiBase = server.url,
            model = FIXTURE_MODEL,
            fromLangName = "Japanese",
            toLangName = "Korean",
            sampleSource = "",
            sampleTarget = "",
        ),
    )

    private fun targetContent(targets: List<String>) = targets.mapIndexed { index, target ->
        "<|${index + 1}|>$target"
    }.joinToString("\n")

    private fun errorReply(status: Int, queries: List<String>) = Reply(
        status,
        queries,
        JSONObject().put("error", JSONObject().put("message", "Synthetic gateway status $status")).toString(),
    )

    private fun successReply(queries: List<String>, content: String, promptTokens: Int, completionTokens: Int) = Reply(
        200,
        queries,
        JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put("finish_reason", "stop")
                        .put("message", JSONObject().put("content", content)),
                ),
            )
            .put("usage", JSONObject().put("prompt_tokens", promptTokens).put("completion_tokens", completionTokens))
            .toString(),
    )

    private data class Reply(val status: Int, val queries: List<String>, val content: String)

    private data class RecordedRequest(val bytes: ByteArray, val json: JSONObject)

    /** Bounded script and one owned thread; requests and replies contain synthetic text only. */
    private class GatewayServer(private val replies: List<Reply>) : AutoCloseable {
        init {
            require(replies.size in 1..3)
            replies.forEach {
                require(it.status in setOf(200, 502, 503, 504))
                require(it.content.toByteArray(Charsets.UTF_8).size in 1..MAX_BODY_BYTES)
            }
        }

        private val listener = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"))
        private val activeSocket = AtomicReference<Socket?>()
        private val finished = CountDownLatch(1)
        private val closing = AtomicBoolean()
        private val failure = AtomicReference<Throwable?>()
        private val recorded = mutableListOf<RecordedRequest>()
        private var responsesSent = 0
        private var peerEofs = 0
        private var ownedSocketsClosed = 0
        private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SERVER_DEADLINE_MS)
        val url = "http://127.0.0.1:${listener.localPort}/v1/chat/completions"
        val requests: List<RecordedRequest>
            get() = synchronized(recorded) { recorded.toList() }
        private val worker = thread(name = "gateway-http-recovery-fixture", isDaemon = true) {
            try {
                for (reply in replies) {
                    listener.soTimeout = remainingTimeoutMillis()
                    val socket = listener.accept()
                    activeSocket.set(socket)
                    if (closing.get()) socket.close()
                    try {
                        socket.use {
                            socket.soTimeout = remainingTimeoutMillis()
                            val input = socket.getInputStream().buffered()
                            val request = readRequest(input, reply.queries)
                            synchronized(recorded) { recorded += request }
                            val response = reply.content.toByteArray(Charsets.UTF_8)
                            check(response.size in 1..MAX_BODY_BYTES)
                            socket.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 ${reply.status} Synthetic\r\n" +
                                            "Content-Type: application/json; charset=utf-8\r\n" +
                                            "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(Charsets.UTF_8),
                                )
                                write(response)
                                flush()
                            }
                            responsesSent++
                            socket.soTimeout = remainingTimeoutMillis()
                            check(input.read() == -1) { "The client did not release its Connection: close response" }
                            peerEofs++
                        }
                    } finally {
                        activeSocket.compareAndSet(socket, null)
                        check(socket.isClosed) { "The fixture's accepted socket remained open" }
                        ownedSocketsClosed++
                    }
                }
            } catch (error: Throwable) {
                if (!closing.get()) failure.compareAndSet(null, error)
            } finally {
                try {
                    listener.close()
                } finally {
                    finished.countDown()
                }
            }
        }

        private fun readRequest(input: InputStream, expectedQueries: List<String>): RecordedRequest {
            var headerBytes = 0
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val byte = input.read()
                    check(byte >= 0) { "Unexpected request header EOF" }
                    headerBytes++
                    check(headerBytes <= MAX_HEADER_BYTES) { "Synthetic request headers exceeded their total limit" }
                    if (byte == 10) return bytes.toString("UTF-8").trimEnd('\r')
                    check(bytes.size() < MAX_HEADER_LINE_BYTES) { "Synthetic request header line exceeded its limit" }
                    bytes.write(byte)
                }
            }
            check(line() == "POST /v1/chat/completions HTTP/1.1")
            val headers = linkedMapOf<String, String>()
            var headerLines = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                check(++headerLines <= MAX_HEADER_LINES) { "Too many synthetic request headers" }
                check(':' in header)
                val name = header.substringBefore(':').lowercase(Locale.ROOT)
                check(name !in headers) { "Duplicate synthetic request header" }
                headers[name] = header.substringAfter(':').trim()
            }
            check("authorization" !in headers && "proxy-authorization" !in headers && "cookie" !in headers)
            check("transfer-encoding" !in headers)
            check(headers.getValue("content-type").startsWith("application/json"))
            val length = headers.getValue("content-length").toInt()
            check(length in 1..MAX_BODY_BYTES)
            val bytes = ByteArray(length)
            DataInputStream(input).readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            val json = JSONObject(text)
            check(json.getString("model") == FIXTURE_MODEL)
            check(!json.getBoolean("stream"))
            val messages = json.getJSONArray("messages")
            check(messages.length() == 2)
            check(messages.getJSONObject(0).getString("role") == "system")
            check(messages.getJSONObject(1).getString("role") == "user")
            val regions = JSONArray(messages.getJSONObject(1).getString("content"))
            check(regions.length() == expectedQueries.size) { "Every page region must be sent in one logical batch" }
            expectedQueries.forEachIndexed { index, query ->
                val region = regions.getJSONObject(index)
                check(region.length() == 2)
                check(region.getInt("id") == index + 1)
                check(region.getString("text") == query)
            }
            check("image_url" !in text && "data:image" !in text)
            return RecordedRequest(bytes, json)
        }

        private fun remainingTimeoutMillis(): Int {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            check(remaining > 0L) { "The synthetic gateway fixture exceeded its total deadline" }
            return minOf(SOCKET_TIMEOUT_MS, remaining).coerceAtLeast(1L).toInt()
        }

        fun assertFinishedAndReleased() {
            assertTrue(
                "The bounded gateway fixture did not finish",
                finished.await(SERVER_DEADLINE_MS, TimeUnit.MILLISECONDS),
            )
            failure.get()?.let { throw AssertionError("The bounded gateway fixture failed", it) }
            assertEquals(replies.size, requests.size)
            assertEquals(replies.size, responsesSent)
            assertEquals("Every response must reach client EOF before the server closes", replies.size, peerEofs)
            assertEquals(replies.size, ownedSocketsClosed)
            assertTrue(listener.isClosed)
        }

        override fun close() {
            closing.set(true)
            try {
                listener.close()
            } finally {
                try {
                    activeSocket.getAndSet(null)?.close()
                } finally {
                    worker.join(CLOSE_DEADLINE_MS)
                    check(!worker.isAlive) { "The bounded gateway fixture thread did not terminate" }
                }
            }
        }
    }

    private companion object {
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val FIXTURE_MODEL = "gateway-http-recovery-fixture"
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_HEADER_LINE_BYTES = 8192
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_HEADER_LINES = 64
        const val SOCKET_TIMEOUT_MS = 5000L
        const val CALL_DEADLINE_MS = 10000L
        const val SERVER_DEADLINE_MS = 15000L
        const val CLOSE_DEADLINE_MS = 6000L
    }
}
