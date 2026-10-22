package mihon.translation

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Text-only local fixture. It validates the real HTTP contract, not translation quality. */
internal class FixtureTranslationServer : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<String>()
    val disconnectedRequests = CopyOnWriteArrayList<Int>()

    /** These requests must be cancelled by the client before any response; other IO errors fail. */
    @Volatile var expectClientDisconnect: Set<Int> = emptySet()

    @Volatile var malformedResponse = false

    /** Optional deterministic gate; runs after recording a request and before choosing its response. */
    @Volatile var beforeResponse: ((requestNumber: Int) -> Unit)? = null

    @Volatile var failure: Throwable? = null
    val base get() = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = thread(name = "yakuyomi-test-llm", isDaemon = true) {
        try {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) {
                            val value = input.read()
                            check(value >= 0)
                            if (value == 10) return bytes.toString("UTF-8").trimEnd('\r')
                            bytes.write(value)
                        }
                    }
                    check(line() == "POST /v1/chat/completions HTTP/1.1")
                    val headers = buildMap {
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            put(header.substringBefore(':').lowercase(), header.substringAfter(':').trim())
                        }
                    }
                    check("authorization" !in headers) // Unauthenticated custom server.
                    val bytes = ByteArray(headers.getValue("content-length").toInt())
                    java.io.DataInputStream(input).readFully(bytes)
                    val request = bytes.toString(Charsets.UTF_8)
                    val body = JSONObject(request)
                    check(body.getString("model") == "fixture-model")
                    check(!body.getBoolean("stream"))
                    val messages = body.getJSONArray("messages")
                    check(messages.length() == 2) // No Chinese few-shot examples.
                    check(messages.getJSONObject(0).getString("content").contains("Korean"))
                    val source = messages.getJSONObject(1).getString("content")
                    check(!request.contains("image_url"))
                    val regions = JSONArray(source)
                    val ids = (0 until regions.length()).map { index ->
                        val region = regions.getJSONObject(index)
                        check(region.getInt("id") == index + 1)
                        check(region.getString("text").isNotBlank())
                        region.getInt("id").toString()
                    }
                    check(ids.isNotEmpty())
                    requests += source
                    val requestNumber = requests.size
                    beforeResponse?.invoke(requestNumber)
                    if (requestNumber in expectClientDisconnect) {
                        val eof = try {
                            input.read() == -1
                        } catch (_: SocketException) {
                            true // A peer reset also proves cancellation; a read timeout must fail.
                        }
                        check(eof) { "Expected client to close the cancelled request before headers" }
                        disconnectedRequests += requestNumber
                        return@use
                    }
                    val translated = if (malformedResponse) {
                        ids.dropLast(1).joinToString("\n") { "<|$it|>안녕하세요." } + "\n<|99|>잘못된 번호"
                    } else {
                        ids.joinToString("\n") { "<|$it|>안녕하세요. 오늘도 잘 부탁드립니다." }
                    }
                    val response = JSONObject().put(
                        "choices",
                        JSONArray().put(JSONObject().put("message", JSONObject().put("content", translated))),
                    ).toString().toByteArray()
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        write(response)
                        flush()
                    }
                }
            }
        } catch (error: Throwable) {
            if (!server.isClosed) failure = error
        }
    }

    override fun close() {
        server.close()
        worker.join(1000)
    }
}
