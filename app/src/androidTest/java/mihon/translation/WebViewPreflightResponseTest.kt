package mihon.translation

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Direct outer-interceptor fixtures, with no transport, Activity, WebView, cookie jar, or user state.
 * The real support check may obtain CookieManager's singleton before reaching the injected PM fault;
 * no cookies are read/changed, and reaching that fault is asserted rather than assumed or skipped.
 */
@RunWith(AndroidJUnit4::class)
class WebViewPreflightResponseTest {
    private lateinit var targetContext: Context

    @Before
    fun requireAuditPackageBeforeCreatingAnyFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Only the isolated audit package may run this fixture", AUDIT_PACKAGE, context.packageName)
        targetContext = context
    }

    @Test
    fun packageManagerFailureClosesUnreturnedResponseOnceAndKeepsOriginalException() {
        val primary = IllegalStateException("Synthetic PackageManager lookup failure")
        withFixture(selector = { true }, packageManagerFailure = primary) { fixture ->
            val failure = runCatching { fixture.interceptor.intercept(fixture.chain.chain) }.exceptionOrNull()
            assertSame("The exact preflight failure must propagate", primary, failure)
            fixture.assertPreflightReachedWithoutResolution()
            assertEquals("The unreturnable response must be closed exactly once", 1, fixture.body.closeCalls)
            assertTrue("The original source must actually be released", fixture.body.sourceClosed)
            assertTrue("A successful close must not add suppressed failures", primary.suppressed.isEmpty())
        }
    }

    @Test
    fun packageManagerFailureSuppressesCloseFailureWithoutReplacingOriginalException() {
        val primary = IllegalStateException("Synthetic PackageManager failure with a close fault")
        val closeFailure = IOException("Synthetic preflight response close failure")
        withFixture(selector = { true }, packageManagerFailure = primary, closeFailure = closeFailure) { fixture ->
            val failure = runCatching { fixture.interceptor.intercept(fixture.chain.chain) }.exceptionOrNull()
            assertSame("A close failure must not replace the exact preflight failure", primary, failure)
            fixture.assertPreflightReachedWithoutResolution()
            assertEquals("A close failure must not cause repeated response close calls", 1, fixture.body.closeCalls)
            assertTrue("The fixture releases its source before injecting the close failure", fixture.body.sourceClosed)
            assertEquals("The one close failure must be retained", 1, primary.suppressed.size)
            assertSame(closeFailure, primary.suppressed.single())
        }
    }

    @Test
    fun unselectedResponseNeverQueriesPackageManagerAndRemainsOpenForItsCaller() {
        withFixture(selector = { false }) { fixture ->
            val returned = fixture.interceptor.intercept(fixture.chain.chain)
            assertSame("An unselected response must be returned unchanged", fixture.response, returned)
            assertEquals(1, fixture.chain.proceedCalls)
            assertEquals(1, fixture.interceptor.selectorCalls)
            assertEquals("A false selector must not reach WebView preparation", 0, fixture.context.packageManagerReads)
            assertEquals(0, fixture.interceptor.resolverCalls)
            assertEquals("The interceptor must not close a response it returns", 0, fixture.body.closeCalls)
            assertFalse(fixture.body.sourceClosed)
            assertEquals("The response cursor must remain unchanged", BODY_TEXT, returned.body.source().readUtf8())

            returned.close()
            assertEquals("The caller can release its response exactly once", 1, fixture.body.closeCalls)
            assertTrue(fixture.body.sourceClosed)
        }
    }

    @Test
    fun selectorFailureStillSuppressesCloseFailureAndClosesOnlyOnce() {
        val primary = IOException("Synthetic selector failure")
        val closeFailure = IOException("Synthetic selector response close failure")
        withFixture(selector = { throw primary }, closeFailure = closeFailure) { fixture ->
            val failure = runCatching { fixture.interceptor.intercept(fixture.chain.chain) }.exceptionOrNull()
            assertSame("The existing selector cleanup must preserve its primary failure", primary, failure)
            assertEquals(1, fixture.chain.proceedCalls)
            assertEquals(1, fixture.interceptor.selectorCalls)
            assertEquals("A failed selector must not reach WebView preparation", 0, fixture.context.packageManagerReads)
            assertEquals(0, fixture.interceptor.resolverCalls)
            assertEquals(1, fixture.body.closeCalls)
            assertTrue(fixture.body.sourceClosed)
            assertEquals(1, primary.suppressed.size)
            assertSame(closeFailure, primary.suppressed.single())
        }
    }

    private fun withFixture(
        selector: (Response) -> Boolean,
        packageManagerFailure: RuntimeException = IllegalStateException("Unexpected PackageManager access"),
        closeFailure: Throwable? = null,
        block: (Fixture) -> Unit,
    ) {
        val body = RecordingBody(closeFailure)
        val response = Response.Builder()
            .request(Request.Builder().url("https://webview-preflight.invalid/synthetic").build())
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("Synthetic response")
            .body(body)
            .build()
        val context = FailingPackageManagerContext(targetContext, packageManagerFailure)
        val fixture = Fixture(
            context,
            RecordingInterceptor(context, selector),
            response,
            body,
            RecordingChain(response),
        )
        try {
            block(fixture)
        } finally {
            // Independent cleanup closes the bounded source even when a baseline assertion fails.
            // It never calls Response.close(), changes the measured close counter, or injects a fault.
            body.releaseFixtureResources()
        }
    }

    private class Fixture(
        val context: FailingPackageManagerContext,
        val interceptor: RecordingInterceptor,
        val response: Response,
        val body: RecordingBody,
        val chain: RecordingChain,
    ) {
        fun assertPreflightReachedWithoutResolution() {
            assertEquals("The response must come from exactly one proceed call", 1, chain.proceedCalls)
            assertEquals(1, interceptor.selectorCalls)
            assertEquals(
                "The real support check must reach the injected PackageManager failure",
                1,
                context.packageManagerReads,
            )
            assertEquals("Preparation must fail before resolving a challenge", 0, interceptor.resolverCalls)
        }
    }

    private class FailingPackageManagerContext(base: Context, private val failure: RuntimeException) :
        ContextWrapper(base) {
        var packageManagerReads = 0
            private set

        override fun getPackageManager(): PackageManager {
            packageManagerReads++
            throw failure
        }
    }

    private class RecordingInterceptor(context: Context, private val selector: (Response) -> Boolean) :
        WebViewInterceptor(context, { error("The fixture must not initialize a WebView user agent") }) {
        var selectorCalls = 0
            private set
        var resolverCalls = 0
            private set

        override fun shouldIntercept(response: Response): Boolean {
            selectorCalls++
            return selector(response)
        }

        override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response {
            resolverCalls++
            error("The fixture must not resolve a challenge or instantiate a WebView")
        }
    }

    private class RecordingChain(response: Response) {
        var proceedCalls = 0
            private set

        val chain = Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java),
        ) { _, method, args ->
            when (method.name) {
                "request" -> response.request
                "proceed" -> {
                    assertSame("The original request must be preserved", response.request, args?.singleOrNull())
                    proceedCalls++
                    check(proceedCalls == 1) { "The fixture must not retry or create a transport" }
                    response
                }
                else -> error("No transport in this fixture: unexpected ${method.name}")
            }
        } as Interceptor.Chain
    }

    private class RecordingBody(private val closeFailure: Throwable?) : ResponseBody() {
        private val input = RecordingSource()
        private val buffered = input.buffer()
        var closeCalls = 0
            private set
        val sourceClosed: Boolean
            get() = input.closed

        override fun contentType() = "text/plain; charset=utf-8".toMediaType()

        override fun contentLength() = BODY_TEXT.length.toLong()

        override fun source(): BufferedSource = buffered

        override fun close() {
            closeCalls++
            buffered.close()
            closeFailure?.let { throw it }
        }

        fun releaseFixtureResources() {
            buffered.close()
        }
    }

    private class RecordingSource : Source {
        private val content = Buffer().writeUtf8(BODY_TEXT)
        var closed = false
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "The synthetic source is closed" }
            return content.read(sink, byteCount)
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
            content.clear()
        }
    }

    private companion object {
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val BODY_TEXT = "Synthetic preflight response owned by its caller."
    }
}
