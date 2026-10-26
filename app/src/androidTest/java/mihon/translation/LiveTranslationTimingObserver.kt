package mihon.translation

import android.os.SystemClock
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

/** Bounded, non-throwing observation of the factory client's unchanged requests and transport. */
internal class LiveTranslationTimingObserver(private val pendingText: String) : EventListener.Factory {
    private val started = SystemClock.elapsedRealtimeNanos()
    private val rows = mutableListOf<JSONObject>()
    private var logical = 0
    private var calls = 0
    private var exchanges = 0
    private var terminals = 0
    private var dropped = 0
    private var failures = 0
    private var payloadChecks = 0
    private var payloadMatches = true
    private val startedCalls = mutableSetOf<Int>()
    private val endedCalls = mutableSetOf<Int>()
    private val batches = mutableSetOf<Int>()

    @Synchronized
    fun beginLogical(number: Int) {
        check(number in 1..2 && number == logical + 1 && calls == terminals)
        logical = number
    }

    @Synchronized
    override fun create(call: Call): EventListener {
        val ordinal = ++calls
        val attempt = logical
        return object : EventListener() {
            override fun callStart(call: Call) {
                event(attempt, ordinal, "CALL_START")
                try {
                    val request = call.request()
                    val body = checkNotNull(request.body)
                    check(request.method == "POST" && !body.isOneShot() && !body.isDuplex())
                    check(body.contentLength() in 1..131_072)
                    val buffer = Buffer()
                    try {
                        body.writeTo(buffer)
                        val payload = JSONObject(buffer.readUtf8())
                        val messages = payload.getJSONArray("messages")
                        val user = messages.getJSONObject(messages.length() - 1)
                        val regions = JSONArray(user.getString("content"))
                        val matches = user.getString("role") == "user" && regions.length() == 1 &&
                            regions.getJSONObject(0).getInt("id") == 1 &&
                            regions.getJSONObject(0).getString("text") == pendingText &&
                            (0 until messages.length()).all { messages.getJSONObject(it).get("content") is String }
                        synchronized(this@LiveTranslationTimingObserver) {
                            payloadChecks++
                            payloadMatches = payloadMatches && matches
                        }
                    } finally {
                        buffer.clear()
                    }
                } catch (_: Exception) {
                    synchronized(this@LiveTranslationTimingObserver) { failures++ }
                }
            }
            override fun dnsStart(call: Call, domainName: String) = event(attempt, ordinal, "DNS_START")
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) =
                event(attempt, ordinal, "DNS_END")
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
                event(attempt, ordinal, "CONNECT_START")
            override fun secureConnectStart(call: Call) = event(attempt, ordinal, "TLS_START")
            override fun secureConnectEnd(call: Call, handshake: Handshake?) = event(attempt, ordinal, "TLS_END")
            override fun connectEnd(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
                event(attempt, ordinal, "CONNECT_END")
            override fun connectFailed(
                call: Call,
                address: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?,
                ioe: IOException,
            ) = event(attempt, ordinal, "CONNECT_FAILED", failure = ioe)
            override fun connectionAcquired(call: Call, connection: Connection) =
                event(attempt, ordinal, "CONNECTION_ACQUIRED")
            override fun connectionReleased(call: Call, connection: Connection) =
                event(attempt, ordinal, "CONNECTION_RELEASED")
            override fun requestHeadersStart(call: Call) = event(attempt, ordinal, "REQUEST_HEADERS_START")
            override fun requestHeadersEnd(call: Call, request: okhttp3.Request) =
                event(attempt, ordinal, "REQUEST_HEADERS_END")
            override fun requestBodyStart(call: Call) = event(attempt, ordinal, "REQUEST_BODY_START")
            override fun requestBodyEnd(call: Call, byteCount: Long) =
                event(attempt, ordinal, "REQUEST_BODY_END", count = byteCount)
            override fun requestFailed(call: Call, ioe: IOException) =
                event(attempt, ordinal, "REQUEST_FAILED", failure = ioe)
            override fun responseHeadersStart(call: Call) = event(attempt, ordinal, "RESPONSE_HEADERS_START")
            override fun responseHeadersEnd(call: Call, response: Response) =
                event(attempt, ordinal, "RESPONSE_HEADERS_END", count = response.code.toLong())
            override fun responseBodyStart(call: Call) = event(attempt, ordinal, "RESPONSE_BODY_START")
            override fun responseBodyEnd(call: Call, byteCount: Long) =
                event(attempt, ordinal, "RESPONSE_BODY_END", count = byteCount)
            override fun responseFailed(call: Call, ioe: IOException) =
                event(attempt, ordinal, "RESPONSE_FAILED", failure = ioe)
            override fun callEnd(call: Call) = event(attempt, ordinal, "CALL_END")
            override fun callFailed(call: Call, ioe: IOException) =
                event(attempt, ordinal, "CALL_FAILED", failure = ioe)
        }
    }

    @Synchronized
    private fun event(attempt: Int, call: Int, phase: String, count: Long? = null, failure: IOException? = null) {
        try {
            if (phase == "CALL_START") {
                if (!startedCalls.add(call) || attempt !in 1..2) failures++
                batches += attempt
            }
            if (phase == "REQUEST_HEADERS_END") exchanges++
            if (phase == "CALL_END" || phase == "CALL_FAILED") {
                terminals++
                if (!endedCalls.add(call) || call !in startedCalls) failures++
            }
            if (rows.size >= 256) {
                dropped++
                return
            }
            rows += JSONObject().put("attempt", attempt).put("call", call).put("phase", phase)
                .put("elapsedNs", SystemClock.elapsedRealtimeNanos() - started).also {
                    if (count != null) it.put("count", count)
                    if (failure != null) it.put("exception", safeException(failure))
                }
        } catch (_: Exception) {
            failures++
        }
    }

    @Synchronized
    fun snapshot(): JSONObject = JSONObject().put("httpCalls", calls).put("httpExchanges", exchanges)
        .put("logicalTranslationBatchesObserved", batches.size)
        .put("terminalCalls", terminals).put("droppedEvents", dropped).put("observerFailures", failures)
        .put("pendingOnlyPayloadChecks", payloadChecks).put("pendingOnlyPayloadMatches", payloadMatches)
        .put(
            "complete",
            calls in 1..6 && calls == terminals && startedCalls == endedCalls &&
                payloadChecks == calls && payloadMatches && dropped == 0 && failures == 0,
        )
        .put("events", JSONArray(rows))

    companion object {
        fun safeException(error: Throwable): String = when (error) {
            is kotlinx.coroutines.TimeoutCancellationException -> "HARNESS_DEADLINE"
            is kotlinx.coroutines.CancellationException -> "CANCELLED"
            is java.net.SocketTimeoutException -> "SOCKET_TIMEOUT"
            is java.io.InterruptedIOException -> "INTERRUPTED_IO"
            is java.net.UnknownHostException -> "DNS"
            is javax.net.ssl.SSLException -> "TLS"
            is java.net.SocketException -> "SOCKET"
            is IOException -> "IO"
            is AssertionError -> "ASSERTION"
            else -> "OTHER"
        }
    }
}
