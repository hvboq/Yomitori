package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.WebViewInterceptor
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.lang.reflect.Proxy

/**
 * Screen-free calls to the real challenge selector, using bounded synthetic streams only.
 * No WebView, Activity, network, models, preferences, or user documents are used. Constructing the
 * required cookie jar obtains Android's CookieManager singleton but does not read or change cookies.
 */
@RunWith(AndroidJUnit4::class)
class CloudflareBodyBudgetTest {
    private val interceptor by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CloudflareInterceptor(context, AndroidCookieJar()) {
            error("The direct shouldIntercept probe must not request a WebView user agent")
        }
    }

    @Before
    fun requireAuditPackageBeforeCreatingInterceptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Only the isolated audit package may run this fixture", AUDIT_PACKAGE, context.packageName)
    }

    @Test
    fun oversizedChallengeUsesBoundedPrefixAndPreservesCallerResponse() {
        for (code in listOf(403, 503)) {
            for (server in CLOUDFLARE_SERVERS) {
                val prefix = "<html><body><div id=\"challenge-error-title\">Synthetic challenge</div>"
                withResponse(code, server, prefix, totalBytes = VIRTUAL_BODY_BYTES) { response, body ->
                    assertTrue(
                        "The known challenge must remain detectable for $code/$server",
                        interceptor.shouldIntercept(response),
                    )
                    assertBoundedAndCallerReadable(response, body, prefix)
                }
            }
        }
    }

    @Test
    fun oversizedOrdinaryErrorUsesBoundedPrefixAndPreservesCallerResponse() {
        for (code in listOf(403, 503)) {
            for (server in CLOUDFLARE_SERVERS) {
                val prefix = "<html><body><h1>Ordinary synthetic access error</h1>"
                withResponse(code, server, prefix, totalBytes = VIRTUAL_BODY_BYTES) { response, body ->
                    assertFalse(
                        "A large ordinary error must not trigger a challenge for $code/$server",
                        interceptor.shouldIntercept(response),
                    )
                    assertBoundedAndCallerReadable(response, body, prefix)
                }
            }
        }
    }

    @Test
    fun challengeMarkerAfterProbeBudgetDoesNotCountAsDetected() {
        val prefix = "<html><body>" + " ".repeat(PROBE_BYTES.toInt() + SOURCE_SEGMENT_BYTES) +
            "<div id=\"challenge-error-text\">Synthetic late challenge</div></body></html>"
        withResponse(403, "cloudflare", prefix) { response, body ->
            assertFalse(
                "A challenge outside the bounded HTML prefix is not detected",
                interceptor.shouldIntercept(response),
            )
            assertBoundedAndCallerReadable(response, body, "<html><body>")
        }
    }

    @Test
    fun smallChallengeSelectorsRemainRecognizedAndCallerReadable() {
        for (code in listOf(403, 503)) {
            for (server in CLOUDFLARE_SERVERS) {
                for (id in listOf("challenge-error-title", "challenge-error-text")) {
                    val html = "<html><body><div id=\"$id\">Synthetic challenge</div></body></html>"
                    withResponse(code, server, html) { response, body ->
                        assertTrue(
                            "Existing selector $code/$server/$id must remain recognized",
                            interceptor.shouldIntercept(response),
                        )
                        assertFalse("The caller still owns the response", body.stream.closed)
                        assertSame(body, response.body)
                        assertEquals(html, response.body.source().readUtf8())
                    }
                }
            }
        }
    }

    @Test
    fun ordinaryErrorsAndNonMatchingStatusOrServerDoNotTriggerChallenge() {
        val ordinary = "<html><body><h1>Unavailable in this region</h1></body></html>"
        for (code in listOf(403, 503)) {
            for (server in CLOUDFLARE_SERVERS) {
                withResponse(code, server, ordinary) { response, body ->
                    assertFalse(interceptor.shouldIntercept(response))
                    assertFalse(body.stream.closed)
                    assertEquals(ordinary, response.body.source().readUtf8())
                }
            }
        }

        val matchingHtml = "<div id=\"challenge-error-title\">Synthetic challenge</div>"
        val ineligible = listOf(
            200 to "cloudflare",
            404 to "cloudflare",
            500 to "cloudflare",
            403 to "nginx",
            503 to null,
        )
        for ((code, server) in ineligible) {
            withResponse(code, server, matchingHtml, totalBytes = VIRTUAL_BODY_BYTES) { response, body ->
                assertFalse(
                    "Status/server controls must not trigger a challenge: $code/$server",
                    interceptor.shouldIntercept(response),
                )
                assertEquals("Ineligible responses must not be probed", 0L, body.stream.bytesRead)
                assertFalse(body.stream.closed)
                assertSame(body, response.body)
                assertEquals(matchingHtml, response.body.source().readUtf8(matchingHtml.length.toLong()))
            }
        }
    }

    @Test
    fun probeIOExceptionPropagatesWithoutClosingCallerResponse() {
        val prefix = "<html><body>Ordinary synthetic error"
        withResponse(
            503,
            "cloudflare",
            prefix,
            totalBytes = VIRTUAL_BODY_BYTES,
            hardLimitBytes = 4096L,
        ) { response, body ->
            val failure = runCatching { interceptor.shouldIntercept(response) }.exceptionOrNull()
            assertTrue("A real probe I/O failure must remain visible to the caller", failure is IOException)
            assertEquals("The failure must be the fixture's own bounded read fault", OVERREAD_MESSAGE, failure?.message)
            assertEquals(4096L, body.stream.bytesRead)
            assertFalse("Even a failed probe must leave response ownership with the caller", body.stream.closed)
            assertSame(body, response.body)
        }
    }

    @Test
    fun outerInterceptorClosesResponseWhenChallengeProbeThrows() {
        val sentinel = IOException("Synthetic outer challenge probe failure")
        val outer = selectorInterceptor { throw sentinel }
        withResponse(503, "cloudflare", "<html>Synthetic outer response</html>") { response, body ->
            val chain = FixedResponseChain(response)
            val failure = runCatching { outer.intercept(chain) }.exceptionOrNull()
            assertSame("The original probe failure must reach the caller unchanged", sentinel, failure)
            assertEquals("The outer interceptor must obtain exactly one response", 1, chain.proceedCalls)
            assertTrue("An exception must release the response that cannot be returned", body.stream.closed)
        }
    }

    @Test
    fun outerInterceptorReturnsUnselectedResponseOpenForCaller() {
        val outer = selectorInterceptor { false }
        val html = "<html>Synthetic unselected response</html>"
        withResponse(403, "cloudflare", html) { response, body ->
            val chain = FixedResponseChain(response)
            val returned = outer.intercept(chain)
            assertSame("The caller must receive the same unselected response", response, returned)
            assertEquals(1, chain.proceedCalls)
            assertFalse("A returned response remains owned by its caller", body.stream.closed)
            assertEquals("The false selector must leave the source untouched", 0L, body.stream.bytesRead)
            assertEquals(html, returned.body.source().readUtf8())
        }
    }

    private fun selectorInterceptor(selector: (Response) -> Boolean) = object : WebViewInterceptor(
        InstrumentationRegistry.getInstrumentation().targetContext,
        { error("The screen-free outer fixture must not initialize a WebView user agent") },
    ) {
        override fun shouldIntercept(response: Response) = selector(response)

        override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response =
            error("The screen-free outer fixture must not resolve a challenge with WebView")
    }

    private fun assertBoundedAndCallerReadable(response: Response, body: StreamingBody, prefix: String) {
        assertTrue("The challenge probe must read some HTML", body.stream.bytesRead > 0L)
        assertTrue(
            "The $PROBE_BYTES-byte probe may read ahead by at most one $SOURCE_SEGMENT_BYTES-byte segment; " +
                "actual=${body.stream.bytesRead}",
            body.stream.bytesRead <= PROBE_BYTES + SOURCE_SEGMENT_BYTES,
        )
        assertFalse("Peeking must not close the caller's original body", body.stream.closed)
        assertSame("The original body must not be replaced", body, response.body)
        assertEquals(
            "Peeking must not advance the caller's cursor",
            prefix,
            response.body.source().readUtf8(prefix.length.toLong()),
        )
    }

    private fun withResponse(
        code: Int,
        server: String?,
        prefix: String,
        totalBytes: Long = prefix.toByteArray(Charsets.UTF_8).size.toLong(),
        hardLimitBytes: Long = HARD_LIMIT_BYTES,
        block: (Response, StreamingBody) -> Unit,
    ) {
        val body = StreamingBody(prefix, totalBytes, hardLimitBytes)
        val response = Response.Builder()
            .request(Request.Builder().url("https://cloudflare-probe.invalid/synthetic").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Synthetic response")
            .apply {
                if (server != null) header("Server", server)
            }
            .body(body)
            .build()
        response.use { block(it, body) }
        assertTrue("Only the caller's use/close must release the original source", body.stream.closed)
    }

    private class StreamingBody(prefix: String, totalBytes: Long, hardLimitBytes: Long) : ResponseBody() {
        val stream = GeneratedSource(prefix.toByteArray(Charsets.UTF_8), totalBytes, hardLimitBytes)
        private val buffered = stream.buffer()

        override fun contentType() = "text/html; charset=utf-8".toMediaType()

        override fun contentLength() = -1L

        override fun source(): BufferedSource = buffered
    }

    /** Direct outer-chain fixture; only request/proceed are expected, and no transport exists. */
    private class FixedResponseChain(
        response: Response,
        private val recorder: ResponseRecorder = ResponseRecorder(response),
    ) : Interceptor.Chain by recorder.chain {
        val proceedCalls: Int
            get() = recorder.proceedCalls
    }

    private class ResponseRecorder(response: Response) {
        var proceedCalls = 0
            private set

        // The pinned Chain has many transport configuration members. Rejecting unexpected calls
        // avoids creating a client/socket just to implement members unused by this direct fixture.
        val chain = Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java),
        ) { _, method, args ->
            when (method.name) {
                "request" -> response.request
                "proceed" -> {
                    val request = args?.singleOrNull() as? Request
                    assertSame("The outer interceptor must preserve the original request", response.request, request)
                    proceedCalls++
                    check(proceedCalls == 1) { "The screen-free outer fixture must not retry" }
                    response
                }
                else -> error("No transport in this fixture: unexpected ${method.name}")
            }
        } as Interceptor.Chain
    }

    /** Virtual tail, an 8 KiB reusable block, and a 1 MiB fault ceiling avoid a huge allocation. */
    private class GeneratedSource(
        private val prefix: ByteArray,
        private val totalBytes: Long,
        private val hardLimitBytes: Long,
    ) : Source {
        private val filler = ByteArray(SOURCE_SEGMENT_BYTES) { 'x'.code.toByte() }
        var bytesRead = 0L
            private set
        var closed = false
            private set

        init {
            require(totalBytes >= prefix.size)
            require(hardLimitBytes > 0L)
        }

        override fun read(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "The fixture source is closed" }
            require(byteCount >= 0L)
            if (byteCount == 0L) return 0L
            if (bytesRead == totalBytes) return -1L
            if (bytesRead >= hardLimitBytes) throw IOException(OVERREAD_MESSAGE)

            val count = minOf(
                byteCount,
                SOURCE_SEGMENT_BYTES.toLong(),
                totalBytes - bytesRead,
                hardLimitBytes - bytesRead,
            ).toInt()
            val prefixCount = minOf(count.toLong(), (prefix.size - bytesRead).coerceAtLeast(0L)).toInt()
            if (prefixCount > 0) sink.write(prefix, bytesRead.toInt(), prefixCount)
            if (count > prefixCount) sink.write(filler, 0, count - prefixCount)
            bytesRead += count
            return count.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val PROBE_BYTES = 256L * 1024L
        const val SOURCE_SEGMENT_BYTES = 8192
        const val HARD_LIMIT_BYTES = 1024L * 1024L
        const val VIRTUAL_BODY_BYTES = 4L * 1024L * 1024L
        const val OVERREAD_MESSAGE = "Synthetic Cloudflare probe exceeded its bounded source ceiling"
        val CLOUDFLARE_SERVERS = listOf("cloudflare", "cloudflare-nginx")
    }
}
