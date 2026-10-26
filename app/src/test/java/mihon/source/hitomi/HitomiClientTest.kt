package mihon.source.hitomi

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HitomiClientTest {
    @Test
    fun `remote suggestions replace the editor token and search every namespace with literal wire paths`() =
        runBlocking<Unit> {
            data class Case(val namespace: String, val segments: List<String>, val encodedPath: String)
            // These are literal protocol expectations, not paths built by the production URL helper.
            val cases = listOf(
                Case(
                    "artist",
                    listOf("artist", "neutral name/a?#%日本-all.nozomi"),
                    "/artist/neutral%20name%2Fa%3F%23%25%E6%97%A5%E6%9C%AC-all.nozomi",
                ),
                Case(
                    "group",
                    listOf("group", "neutral name/a?#%日本-all.nozomi"),
                    "/group/neutral%20name%2Fa%3F%23%25%E6%97%A5%E6%9C%AC-all.nozomi",
                ),
                Case(
                    "series",
                    listOf("series", "neutral name/a?#%日本-all.nozomi"),
                    "/series/neutral%20name%2Fa%3F%23%25%E6%97%A5%E6%9C%AC-all.nozomi",
                ),
                Case(
                    "character",
                    listOf("character", "neutral name/a?#%日本-all.nozomi"),
                    "/character/neutral%20name%2Fa%3F%23%25%E6%97%A5%E6%9C%AC-all.nozomi",
                ),
                Case(
                    "tag",
                    listOf("tag", "neutral name/a?#%日本-all.nozomi"),
                    "/tag/neutral%20name%2Fa%3F%23%25%E6%97%A5%E6%9C%AC-all.nozomi",
                ),
                Case(
                    "female",
                    listOf("tag", "female:neutral name/a?#%日本-all.nozomi"),
                    "/tag/female:neutral%20name%2Fa%3F%23%25%E6%97%A5%E6%9C%AC-all.nozomi",
                ),
                Case(
                    "male",
                    listOf("tag", "male:neutral name/a?#%日本-all.nozomi"),
                    "/tag/male:neutral%20name%2Fa%3F%23%25%E6%97%A5%E6%9C%AC-all.nozomi",
                ),
                Case(
                    "language",
                    listOf("index-neutral name/a?#%日本.nozomi"),
                    "/index-neutral%20name%2Fa%3F%23%25%E6%97%A5%E6%9C%AC.nozomi",
                ),
                Case(
                    "type",
                    listOf("type", "neutral name/a?#%日本-all.nozomi"),
                    "/type/neutral%20name%2Fa%3F%23%25%E6%97%A5%E6%9C%AC-all.nozomi",
                ),
            )
            for (case in cases) {
                var requests = 0
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    request.url.query shouldBe null
                    request.url.fragment shouldBe null
                    request.header("Referer") shouldBe "https://hitomi.la/"
                    val body = when (++requests) {
                        1 -> {
                            request.url.host shouldBe "tagindex.hitomi.la"
                            request.url.encodedPath shouldBe "/${case.namespace}/n.json"
                            """[["Neutral\u3000Name/a?#%日本",31,"${case.namespace}"]]""".toResponseBody()
                        }
                        2 -> {
                            request.url.host shouldBe "ltn.gold-usergeneratedcontent.net"
                            request.url.pathSegments shouldBe case.segments
                            request.url.encodedPath shouldBe case.encodedPath
                            request.header("Range") shouldBe null
                            ids(9, 7, 5).toResponseBody()
                        }
                        else -> error("Unexpected extra fixture request")
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body).build()
                }.build()
                try {
                    val api = HitomiClient(client) { Headers.headersOf("Referer", "https://hitomi.la/") }
                    val query = "${case.namespace}:n"
                    val token = requireNotNull(HitomiSearchQuery.activeToken(query))
                    val suggestion = api.searchSuggestions(token.text).single()
                    suggestion.value shouldBe "${case.namespace}:neutral_name/a?#%日本"
                    suggestion.count shouldBe 31
                    val replacement = requireNotNull(HitomiSearchQuery.replaceAt(query, token, suggestion.value))
                    replacement.query shouldBe "${case.namespace}:neutral_name/a?#%日本 "
                    replacement.caret shouldBe replacement.query.length
                    api.search(replacement.query, "all", 1) shouldBe (listOf(9L, 7L, 5L) to false)
                    requests shouldBe 2
                } finally {
                    client.dispatcher.executorService.shutdownNow()
                    client.connectionPool.evictAll()
                }
            }
        }

    @Test
    fun `every editor whitespace separator preserves query intersection and exclusions`() = runBlocking<Unit> {
        val requests = mutableListOf<List<String>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.pathSegments
            val data = when (request.url.pathSegments) {
                listOf("type", "manga-all.nozomi") -> ids(9, 7, 5)
                listOf("artist", "neutral name-all.nozomi") -> ids(7, 5)
                listOf("tag", "exclude-all.nozomi") -> ids(5)
                else -> error("Unexpected neutral filter path: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody()).build()
        }.build()
        try {
            val api = HitomiClient(client) { Headers.headersOf() }
            val separators = listOf(
                '\u3000', '\u00A0', '\u2007', '\u202F', ' ', '\t', '\n', '\r',
                '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u001F',
            )
            for (separator in separators) {
                separator.isWhitespace() shouldBe true
                requests.clear()
                val query = "type:manga${separator}artist:neutral_name$separator-tag:exclude"
                api.search(query, "all", 1) shouldBe (listOf(7L) to false)
                requests shouldBe listOf(
                    listOf("type", "manga-all.nozomi"),
                    listOf("artist", "neutral name-all.nozomi"),
                    listOf("tag", "exclude-all.nozomi"),
                )
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `missing tag index is an empty search rather than a network failure`() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                .body("".toResponseBody()).build()
        }.build()
        try {
            HitomiClient(client) { Headers.headersOf() }.search("tag:missing", "all", 1) shouldBe
                (emptyList<Long>() to false)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `intersection cannot resurrect a prior empty search`() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath
            val data = if (request.url.encodedPath == "/tag/empty-all.nozomi") byteArrayOf() else ids(7, 9)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody()).build()
        }.build()
        try {
            val api = HitomiClient(client) { Headers.headersOf("Referer", "https://hitomi.la/") }
            api.search("tag:empty tag:populated", "all", 1) shouldBe (emptyList<Long>() to false)
            requests shouldBe listOf("/tag/empty-all.nozomi")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `negative tags preserve positive result order`() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val data = when (request.url.encodedPath) {
                "/tag/first-all.nozomi" -> ids(9, 7, 5)
                "/tag/exclude-all.nozomi" -> ids(7)
                else -> error("Unexpected request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody()).build()
        }.build()
        try {
            HitomiClient(client) { Headers.headersOf() }.search("tag:first -tag:exclude", "all", 1) shouldBe
                (listOf(9L, 5L) to false)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `listing ranges paginate full partial and exhausted pages`() = runBlocking<Unit> {
        val allIds = ids(*IntArray(27) { it + 1 })
        val requestedRanges = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            request.url.encodedPath shouldBe "/index-japanese.nozomi"
            val range = requireNotNull(request.header("Range"))
            requestedRanges += range
            val match = requireNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(range))
            val first = match.groupValues[1].toInt()
            val requestedLast = match.groupValues[2].toInt()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            if (first >= allIds.size) {
                response.code(416).message("Range Not Satisfiable")
                    .header("Content-Range", "bytes */${allIds.size}")
                    .body(byteArrayOf().toResponseBody()).build()
            } else {
                val last = minOf(requestedLast, allIds.lastIndex)
                response.code(206).message("Partial Content")
                    .header("Content-Range", "bytes $first-$last/${allIds.size}")
                    .body(allIds.copyOfRange(first, last + 1).toResponseBody()).build()
            }
        }.build()
        try {
            val api = HitomiClient(client) { Headers.headersOf() }
            api.page("index-japanese.nozomi", 1) shouldBe ((1L..25L).toList() to true)
            api.page("index-japanese.nozomi", 2) shouldBe (listOf(26L, 27L) to false)
            api.page("index-japanese.nozomi", 3) shouldBe (emptyList<Long>() to false)
            requestedRanges shouldBe listOf("bytes=0-99", "bytes=100-199", "bytes=200-299")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `search exclusions are applied before pagination across a page boundary`() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val data = when (request.url.encodedPath) {
                "/tag/all-all.nozomi" -> ids(*IntArray(32) { 32 - it })
                "/tag/excluded-all.nozomi" -> ids(32, 8, 7)
                else -> error("Unexpected request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody()).build()
        }.build()
        try {
            val api = HitomiClient(client) { Headers.headersOf() }
            val query = "tag:all -tag:excluded"
            // The first 25 results include 6 and 5, pulled forward after boundary exclusions.
            api.search(query, "all", 1) shouldBe (((31L downTo 9L).toList() + listOf(6L, 5L)) to true)
            api.search(query, "all", 2) shouldBe (listOf(4L, 3L, 2L, 1L) to false)
            api.search(query, "all", 3) shouldBe (emptyList<Long>() to false)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `negative language does not remove the selected language filter`() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath
            val data = when (request.url.encodedPath) {
                "/type/manga-all.nozomi" -> ids(9, 7, 5)
                "/index-japanese.nozomi" -> ids(7, 5)
                "/index-english.nozomi" -> ids(9)
                else -> error("Unexpected request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody()).build()
        }.build()
        try {
            HitomiClient(client) { Headers.headersOf() }.search("type:manga -language:english", "japanese", 1) shouldBe
                (listOf(7L, 5L) to false)
            requests shouldBe listOf("/type/manga-all.nozomi", "/index-japanese.nozomi", "/index-english.nozomi")
            requests.clear()
            // An explicitly included language still overrides the selected dropdown language.
            HitomiClient(client) { Headers.headersOf() }.search("type:manga language:english", "japanese", 1) shouldBe
                (listOf(9L) to false)
            requests shouldBe listOf("/type/manga-all.nozomi", "/index-english.nozomi")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `cancelling metadata after headers cancels stalled body and closes it`() = runBlocking<Unit> {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancellationObserved = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = object : ResponseBody() {
                private val source = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        reading.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                        cancellationObserved.set(chain.call().isCanceled())
                        throw IOException("Fixture body stopped")
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
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body).build()
        }.build()
        val task = async(Dispatchers.Default) { HitomiClient(client) { Headers.headersOf() }.gallery(7) }
        try {
            reading.await(2, TimeUnit.SECONDS) shouldBe true
            task.cancelAndJoin()
            closed.await(1, TimeUnit.SECONDS) shouldBe true
            cancellationObserved.get() shouldBe true
            task.isCancelled shouldBe true
        } finally {
            task.cancelAndJoin()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun ids(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply {
        values.forEach(::putInt)
    }.array()
}
