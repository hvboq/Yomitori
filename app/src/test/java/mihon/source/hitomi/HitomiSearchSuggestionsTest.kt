package mihon.source.hitomi

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HitomiSearchSuggestionsTest {
    @Test
    fun `noncontrol editor whitespace in remote names becomes one canonical tag`() {
        for (separator in listOf(' ', '\u00A0', '\u2007', '\u202F', '\u3000')) {
            separator.isWhitespace() shouldBe true
            HitomiSuggestionProtocol.parse("""[["Neutral${separator}${separator}Name",1,"artist"]]""") shouldBe
                listOf(HitomiSearchSuggestion("artist:neutral_name", "artist:Neutral${separator}${separator}Name", 1))
        }
    }

    @Test
    fun `noncontrol editor whitespace prefixes address the same remote space index`() {
        for (separator in listOf(' ', '\u00A0', '\u2007', '\u202F', '\u3000')) {
            HitomiSuggestionProtocol.url("artist:A${separator}B/C.D")?.encodedPath shouldBe
                "/artist/a/_/b/slash/c/dot/d.json"
        }
    }

    @Test
    fun `ASCII control characters remain rejected in remote names and prefix requests`() {
        for (code in (0..31) + 127) {
            val control = code.toChar()
            HitomiSuggestionProtocol.url("artist:neutral${control}name") shouldBe null
            val escaped = "\\u${code.toString(16).padStart(4, '0')}"
            HitomiSuggestionProtocol.parse("""[["neutral${escaped}name",1,"artist"]]""") shouldBe emptyList()
        }
    }

    @Test
    fun `global and every supported namespace use the remote character index`() {
        HitomiSuggestionProtocol.url("JAP").toString() shouldBe "https://tagindex.hitomi.la/global/j/a/p.json"
        val namespaces = listOf("artist", "group", "series", "character", "tag", "female", "male", "language", "type")
        for (namespace in namespaces) {
            HitomiSuggestionProtocol.url("$namespace:ab")?.encodedPath shouldBe "/$namespace/a/b.json"
            HitomiSuggestionProtocol.url("$namespace:")?.encodedPath shouldBe "/$namespace.json"
        }
    }

    @Test
    fun `spaces slash dot unicode and URL metacharacters stay inside encoded path segments`() {
        HitomiSuggestionProtocol.url("artist:A_B/C.D")?.encodedPath shouldBe "/artist/a/_/b/slash/c/dot/d.json"
        HitomiSuggestionProtocol.url("character:日本")?.encodedPath shouldBe "/character/%E6%97%A5/%E6%9C%AC.json"
        val url = HitomiSuggestionProtocol.url("tag:a?#%")!!
        url.host shouldBe "tagindex.hitomi.la"
        url.query shouldBe null
        url.fragment shouldBe null
        url.pathSegments shouldBe listOf("tag", "a", "?", "#", "%.json")
    }

    @Test
    fun `invalid or excessive queries do not produce a request`() {
        listOf("", "  ", "http://elsewhere.test", "unsupported:abc", "a\nb", "a\t", "a".repeat(129))
            .forEach { HitomiSuggestionProtocol.url(it) shouldBe null }
    }

    @Test
    fun `remote names become canonical searchable tokens with display text and counts`() {
        val values = HitomiSuggestionProtocol.parse(
            """[["japanese",123,"language"],["Some Artist",42,"artist"],["A Series",null,"series"]]""",
        )
        values shouldBe listOf(
            HitomiSearchSuggestion("language:japanese", "language:japanese", 123),
            HitomiSearchSuggestion("artist:some_artist", "artist:Some Artist", 42),
            HitomiSearchSuggestion("series:a_series", "series:A Series", null),
        )
    }

    @Test
    fun `all supported result namespaces survive parsing without curated tag filtering`() {
        val namespaces = listOf("artist", "group", "series", "character", "tag", "female", "male", "language", "type")
        val json = namespaces.joinToString(prefix = "[", postfix = "]") { "[\"neutral name\",1,\"$it\"]" }
        HitomiSuggestionProtocol.parse(json).map { it.value } shouldBe namespaces.map { "$it:neutral_name" }
    }

    @Test
    fun `malformed rows unsupported namespaces and invalid counts are skipped`() {
        val json = """[null,{},[],[1,2,"artist"],["name",2,3],["name",-1,"artist"],
            ["name","3","artist"],["name",1.5,"artist"],["name",2147483648,"artist"],
            ["name",1,"unsupported"],["",1,"tag"],["a\nb",1,"tag"],["ok",0,"tag"]]"""
        HitomiSuggestionProtocol.parse(json) shouldBe listOf(HitomiSearchSuggestion("tag:ok", "tag:ok", 0))
    }

    @Test
    fun `deduplication preserves remote order and limits rendered results`() {
        val rows = listOf("[\"First Name\",3,\"artist\"]", "[\"first_name\",2,\"artist\"]") +
            (0..30).map { "[\"name$it\",1,\"tag\"]" }
        val result = HitomiSuggestionProtocol.parse(rows.joinToString(prefix = "[", postfix = "]"))
        result.size shouldBe 20
        result.first().count shouldBe 3
        result.last().value shouldBe "tag:name18"
    }

    @Test
    fun `malformed excessive row count and excessive byte count are rejected`() {
        listOf("{}", "[", List(1001) { "[]" }.joinToString(prefix = "[", postfix = "]"), " ".repeat(262145))
            .forEach { assertThrows<IllegalArgumentException> { HitomiSuggestionProtocol.parse(it) } }
    }

    @Test
    fun `client forwards source headers and parser result through supplied transport`() = runBlocking<Unit> {
        withClient({ chain ->
            chain.request().url.toString() shouldBe "https://tagindex.hitomi.la/language/j/a.json"
            chain.request().header("Referer") shouldBe "https://hitomi.la/"
            response(chain.request(), 200, """[["japanese",7,"language"]]""".toResponseBody())
        }) { api ->
            api.searchSuggestions("language:ja") shouldBe listOf(HitomiSearchSuggestion("language:japanese", count = 7))
        }
    }

    @Test
    fun `missing prefix is empty while HTTP failures are visible without response contents`() = runBlocking<Unit> {
        for (status in listOf(404, 503)) {
            withClient({ chain -> response(chain.request(), status, "not for error logs".toResponseBody()) }) { api ->
                if (status == 404) {
                    api.searchSuggestions("language:missing") shouldBe emptyList()
                } else {
                    val error = try {
                        api.searchSuggestions("language:ja")
                        error("Expected HTTP failure")
                    } catch (e: IOException) {
                        e
                    }
                    error.message shouldBe "Hitomi suggestions failed (HTTP 503)"
                }
            }
        }
    }

    @Test
    fun `response size is bounded for declared and streaming bodies`() = runBlocking<Unit> {
        for (declared in listOf(true, false)) {
            val oversized = object : ResponseBody() {
                private val source = Buffer().write(ByteArray(262145))
                override fun contentType(): MediaType? = null
                override fun contentLength() = if (declared) 262145L else -1L
                override fun source(): BufferedSource = source
            }
            withClient({ chain -> response(chain.request(), 200, oversized) }) { api ->
                val error = try {
                    api.searchSuggestions("language:ja")
                    error("Expected body limit")
                } catch (e: IOException) {
                    e
                }
                error.message shouldBe "Hitomi suggestions response too large"
            }
        }
    }

    @Test
    fun `cancellation after headers cancels the call and closes a stalled response body`() = runBlocking<Unit> {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        withClient({ chain ->
            val body = object : ResponseBody() {
                private val source = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        reading.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                        if (!chain.call().isCanceled()) throw IOException("Call was not cancelled")
                        throw IOException("Cancelled")
                    }

                    override fun timeout() = Timeout.NONE
                    override fun close() {
                        closed.countDown()
                    }
                }.buffer()

                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source(): BufferedSource = source
            }
            response(chain.request(), 200, body)
        }) { api ->
            val task = async(Dispatchers.Default) { api.searchSuggestions("language:ja") }
            try {
                reading.await(2, TimeUnit.SECONDS) shouldBe true
                task.cancelAndJoin()
                closed.await(1, TimeUnit.SECONDS) shouldBe true
                task.isCancelled shouldBe true
            } finally {
                task.cancelAndJoin()
            }
        }
    }

    private fun response(request: Request, code: Int, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()

    private suspend fun withClient(interceptor: Interceptor, block: suspend (HitomiClient) -> Unit) {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        try {
            block(HitomiClient(client) { Headers.headersOf("Referer", "https://hitomi.la/") })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
