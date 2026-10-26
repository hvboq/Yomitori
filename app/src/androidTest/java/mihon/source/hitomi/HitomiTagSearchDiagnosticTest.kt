package mihon.source.hitomi

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.translation.NativeDiagnosticIdleGuard
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Requires liveHitomiTagSearchDiagnostic=true and diagnosticRunId=UUID in an isolated audit install.
 * Exercises production suggestion parsing, editor replacement and search, without gallery/UI/image APIs.
 * Only bounded index bodies are held in memory. Candidate text, IDs, URLs and raw failures never leave it.
 */
@RunWith(AndroidJUnit4::class)
class HitomiTagSearchDiagnosticTest {
    @Test
    fun allNamespacesConnectSuggestionsToSearchIndexes() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("liveHitomiTagSearchDiagnostic") == "true") { "Explicit live opt-in required" }
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        val runId = arguments.getString("diagnosticRunId")
        check(runId != null && runCatching { UUID.fromString(runId).toString() == runId }.getOrDefault(false)) {
            "Canonical diagnostic UUID required"
        }
        val started = System.nanoTime()
        val budget = Budget(started + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS))
        val rows = JSONArray()
        val refreshRows = mutableListOf<() -> Unit>()
        val report = JSONObject().put("schemaVersion", 1).put("passed", false).put("terminal", false)
            .put("namespaces", rows).put("maximumIndexHttpExchanges", MAX_REQUESTS)
            .put("maximumIndexBodyBytes", MAX_TOTAL_BYTES)
            .put("maximumSearchBodyBytes", MAX_SEARCH_BYTES).put("maximumSuggestionBodyBytes", MAX_SUGGESTION_BYTES)
            .put("maximumDurationMs", TOTAL_TIMEOUT_MS).put("signedRelease", false).put("publicUiVerified", false)
            .put("galleryCalls", 0).put("imageCalls", 0).put("translationCalls", 0).put("downloadQueueCalls", 0)
            .put("preferenceWrites", 0).put("contentFiles", 0).put("candidateTextRetained", false)
            .put("fullUrlsRetained", false).put("galleryIdsRetained", false).put("rawErrorsRetained", false)
            .put("sharedSourceTransportShutdownCalls", 0).put("sharedEngineCalls", 0)
            .put("requestCounterIncludesDns", false)
        fun emit() {
            report.put("indexHttpExchanges", budget.requests.get()).put("indexBodyBytes", budget.bytes.get())
                .put("durationMs", (System.nanoTime() - started) / 1_000_000)
            instrumentation.sendStatus(2, Bundle().apply { putString("hitomiTagSearchEvidence", report.toString()) })
        }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "hitomi-tag-search-http") }
        val pool = ConnectionPool()
        var client: OkHttpClient? = null
        var preferencesUnchanged: (() -> Boolean)? = null
        var failure: Throwable? = null
        var completed = false
        var cleanupPassed = true
        try {
            NativeDiagnosticIdleGuard.requireIdle(context)
            report.put("idleBefore", true)
            val source = HitomiSource(Injekt.get<NetworkHelper>())
            val preferences = source.getSourcePreferences()
            val originalPreferences = preferences.all.toMap()
            preferencesUnchanged = { preferences.all == originalPreferences }
            val transport = source.client
            val owned = transport.newBuilder().apply {
                interceptors().clear()
                networkInterceptors().clear()
            }.cache(null).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE).eventListener(EventListener.NONE)
                .connectionPool(pool).dispatcher(Dispatcher(executor))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS).addInterceptor(budget)
                .addNetworkInterceptor(budget::observeWire).build().also { client = it }
            check(owned.dns === transport.dns && owned.proxy == transport.proxy)
            check(owned.sslSocketFactory === transport.sslSocketFactory)
            check(owned.hostnameVerifier === transport.hostnameVerifier)
            check(owned.connectionPool === pool && owned.dispatcher.executorService === executor && owned.cache == null)
            report.put("sourceDnsProxyTlsPreserved", true).put("privateCachelessHttpClient", true)
                .put("sharedSourceDohAndRelayBorrowed", true)
            val api = HitomiClient(owned) { source.headers }
            withTimeout(TOTAL_TIMEOUT_MS) {
                for ((namespace, prefix, suggestionPath) in CASES) {
                    NativeDiagnosticIdleGuard.requireIdle(context)
                    val row = JSONObject().put("namespace", namespace).put("status", 0).put("phase", 1)
                    rows.put(row)
                    val suggestion = Expected(
                        "https://tagindex.hitomi.la$suggestionPath".toHttpUrl(),
                        MAX_SUGGESTION_BYTES,
                    )
                    var search: Expected? = null
                    val refresh = {
                        row.put("suggestions", suggestion.report())
                        search?.let { row.put("search", it.report()) }
                        Unit
                    }
                    refreshRows += refresh
                    try {
                        val token = checkNotNull(HitomiSearchQuery.activeToken(prefix))
                        budget.expected = suggestion
                        val suggestions = api.searchSuggestions(token.text)
                        val candidates = suggestions.filter {
                            it.value.substringBefore(':') == namespace && (it.count ?: 0) > 0
                        }
                        row.put("suggestionCount", suggestions.size).put("positiveCandidateCount", candidates.size)
                        val selected =
                            checkNotNull(candidates.firstOrNull { '_' in it.value } ?: candidates.firstOrNull())
                        row.put("phase", 2)
                        // Independently normalize the display name, instead of deriving the expected path from
                        // either the canonical token or the production request. JVM fixtures pin literal encodings.
                        val name = selected.label.substringAfter(':').trim()
                            .replace(Regex("[\\p{javaWhitespace}\\p{Zs}]+"), " ").lowercase().replace('_', ' ')
                        check(selected.label.substringBefore(':') == namespace)
                        val canonicalMatches = selected.value == "$namespace:${name.replace(' ', '_')}"
                        row.put("canonicalMatches", canonicalMatches).put("selectedCount", selected.count)
                            .put("selectedMultiword", ' ' in name)
                        check(canonicalMatches)
                        row.put("phase", 3)
                        val replacement = checkNotNull(HitomiSearchQuery.replaceAt(prefix, token, selected.value))
                        val replacementMatches = replacement.query == "${selected.value} " &&
                            replacement.caret == replacement.query.length
                        row.put("replacementMatches", replacementMatches)
                        check(replacementMatches)
                        row.put("phase", 4)
                        val segments = when (namespace) {
                            "language" -> listOf("index-$name.nozomi")
                            "female", "male" -> listOf("tag", "$namespace:$name-all.nozomi")
                            else -> listOf(namespace, "$name-all.nozomi")
                        }
                        val expectedUrl = HitomiClient.INDEX.toHttpUrl().newBuilder().apply {
                            segments.forEach { addPathSegment(it) }
                        }.build()
                        search = Expected(expectedUrl, MAX_SEARCH_BYTES)
                        budget.expected = search
                        val (ids, hasNext) = api.search(replacement.query, "all", 1)
                        row.put("resultCount", ids.size).put("hasNextPage", hasNext).put("phase", 5)
                        val validResults = ids.size in 1..HitomiProtocol.PAGE_SIZE &&
                            ids.all { it > 0 } && ids.distinct().size == ids.size
                        row.put("resultShapeValid", validResults)
                        check(validResults)
                        check(suggestion.succeeded() && search.succeeded())
                        row.put("status", 1)
                    } catch (error: Throwable) {
                        row.put("status", 2).put("errorClass", error.javaClass.simpleName)
                        throw error
                    } finally {
                        refresh()
                        emit()
                    }
                }
                completed = rows.length() == 9
            }
        } catch (error: Throwable) {
            failure = error
            report.put("status", 2).put("errorClass", error.javaClass.simpleName)
        } finally {
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    cleanupPassed = false
                    report.put("cleanupErrorClass", error.javaClass.simpleName)
                    if (failure == null || error is VirtualMachineError || error is ThreadDeath) failure = error
                }
            }
            cleanup { client?.dispatcher?.cancelAll() }
            cleanup { pool.evictAll() }
            cleanup { executor.shutdownNow() }
            cleanup { check(executor.awaitTermination(5, TimeUnit.SECONDS)) }
            cleanup { pool.evictAll() }
            cleanup { check(pool.connectionCount() == 0) }
            cleanup {
                check(preferencesUnchanged?.invoke() == true)
                report.put("sourcePreferencesUnchanged", true)
            }
            cleanup {
                NativeDiagnosticIdleGuard.requireIdle(context)
                report.put("idleAfter", true)
            }
            report.put("ownedHttpCleanupPassed", cleanupPassed).put("terminal", true)
                .put("passed", completed && cleanupPassed && failure == null)
            refreshRows.forEach { it() }
            emit()
        }
        // Normal failures/cancellation must not attach transport causes containing candidate URLs.
        when (val error = failure) {
            is VirtualMachineError -> throw error
            is ThreadDeath -> throw error
            is CancellationException -> throw CancellationException("Live tag search diagnostic cancelled")
            null -> check(report.getBoolean("passed")) { "Live tag search diagnostic incomplete" }
            else -> throw AssertionError("Live tag search diagnostic failed; inspect numeric evidence")
        }
    }

    /** All request observations are facts only; expected URLs stay in private process memory. */
    private class Expected(val url: HttpUrl, val limit: Long) {
        val requests = AtomicInteger()
        val bytes = AtomicLong()
        var httpStatus = 0
        var wireMatches = false
        var networkResponse = false
        var cacheResponse = false

        fun matches(actual: HttpUrl): Boolean = actual.isHttps && actual.port == 443 &&
            actual.username.isEmpty() && actual.password.isEmpty() && actual.query == null && actual.fragment == null &&
            actual.host == url.host && actual.pathSegments == url.pathSegments && actual.encodedPath == url.encodedPath

        fun succeeded() = requests.get() == 1 && wireMatches && httpStatus == 200 && networkResponse && !cacheResponse

        fun report(): JSONObject = JSONObject().put("requests", requests.get()).put("bodyBytes", bytes.get())
            .put("httpStatus", httpStatus).put("expectedWireMatch", wireMatches)
            .put("networkResponse", networkResponse).put("cacheResponse", cacheResponse)
    }

    /** Bounds actual HTTP exchanges and decoded bodies, including chunked/gzip responses. */
    private class Budget(private val deadline: Long) : Interceptor {
        @Volatile var expected: Expected? = null
        val requests = AtomicInteger()
        val bytes = AtomicLong()

        fun observeWire(chain: Interceptor.Chain): Response {
            val target = expected ?: throw IOException("Missing diagnostic request")
            if (!target.matches(chain.request().url) || target.requests.incrementAndGet() != 1 ||
                requests.incrementAndGet() > MAX_REQUESTS || System.nanoTime() >= deadline
            ) {
                throw IOException("Diagnostic request budget or route rejected")
            }
            target.wireMatches = true
            return chain.proceed(chain.request())
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val target = expected ?: throw IOException("Missing diagnostic request")
            if (!target.matches(chain.request().url) || chain.request().method != "GET" ||
                chain.request().body != null || chain.request().header("Range") != null || System.nanoTime() >= deadline
            ) {
                throw IOException("Diagnostic request route or deadline rejected")
            }
            val response = chain.proceed(
                chain.request().newBuilder().header("Cache-Control", "no-cache, no-store").build(),
            )
            target.httpStatus = response.code
            target.networkResponse = response.networkResponse != null
            target.cacheResponse = response.cacheResponse != null
            val body = response.body
            if (body.contentLength() > target.limit || body.contentLength() > MAX_TOTAL_BYTES - bytes.get()) {
                chain.call().cancel()
                response.close()
                throw IOException("Diagnostic declared body budget exceeded")
            }
            val bounded = object : ResponseBody() {
                private val input = object : ForwardingSource(body.source()) {
                    private var exhausted = false

                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (System.nanoTime() >= deadline) throw IOException("Diagnostic deadline exceeded")
                        timeout().deadlineNanoTime(deadline)
                        val remaining = minOf(target.limit - target.bytes.get(), MAX_TOTAL_BYTES - bytes.get())
                        if (remaining < 0) throw IOException("Diagnostic body budget exceeded")
                        val count = super.read(sink, minOf(byteCount, remaining + 1))
                        if (count == -1L) exhausted = true
                        if (count > 0) {
                            val perBody = target.bytes.addAndGet(count)
                            val total = bytes.addAndGet(count)
                            if (perBody > target.limit || total > MAX_TOTAL_BYTES) {
                                throw IOException("Diagnostic body budget exceeded")
                            }
                        }
                        return count
                    }

                    override fun close() {
                        // Early parser rejection/overflow must not drain an unbounded body during close.
                        if (!exhausted) chain.call().cancel()
                        super.close()
                    }
                }.buffer()

                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = input
            }
            return response.newBuilder().body(bounded).build()
        }
    }

    private companion object {
        const val MAX_REQUESTS = 18
        const val MAX_SUGGESTION_BYTES = 256L * 1024
        const val MAX_SEARCH_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val TOTAL_TIMEOUT_MS = 180_000L
        val CASES = listOf(
            Triple("artist", "artist:", "/artist.json"),
            Triple("group", "group:", "/group.json"),
            Triple("series", "series:", "/series.json"),
            Triple("character", "character:", "/character.json"),
            Triple("tag", "tag:", "/tag.json"),
            Triple("female", "female:glasses", "/female/g/l/a/s/s/e/s.json"),
            Triple("male", "male:glasses", "/male/g/l/a/s/s/e/s.json"),
            Triple("language", "language:ja", "/language/j/a.json"),
            Triple("type", "type:", "/type.json"),
        )
    }
}
