package mihon.source.hitomi

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class HitomiClientTest {
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

    private fun ids(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply {
        values.forEach(::putInt)
    }.array()
}
