package mihon.source.hitomi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HitomiImageInterceptorTest {
    @Test
    fun `stale CDN path refreshes once and preserves image hash and range`() {
        val requests = mutableListOf<Request>()
        client { request ->
            requests.add(request)
            if (request.isRouting()) 200 to "var o = 0; b: 'new/'" else 404 to ""
        }.useClient { client ->
            val request = Request.Builder().url("https://a1.gold-usergeneratedcontent.net/old/3243/000abc.avif")
                .header("Referer", "https://hitomi.la/").header("Range", "bytes=100-").build()
            client.newCall(request).execute().use { it.code shouldBe 404 }
            requests.size shouldBe 3 // A second 404 must not loop.
            requests[1].header("Range") shouldBe null
            requests[2].url.toString() shouldBe "https://a1.gold-usergeneratedcontent.net/new/3243/000abc.avif"
            requests[2].header("Range") shouldBe "bytes=100-"
            requests[2].header("Referer") shouldBe "https://hitomi.la/"
        }
    }

    @Test
    fun `persisted atn thumbnails are routed before sending and reuse current routing`() {
        val requests = mutableListOf<Request>()
        client { request ->
            requests.add(request)
            200 to if (request.isRouting()) CURRENT_ROUTE else "image"
        }.useClient { client ->
            client.fetch(OLD_THUMB)
            client.fetch("https://btn.gold-usergeneratedcontent.net/avifbigtn/6/7e/0007e6.avif")
            requests.map { it.url.host } shouldBe listOf(
                "ltn.gold-usergeneratedcontent.net",
                "btn.gold-usergeneratedcontent.net",
                "atn.gold-usergeneratedcontent.net",
            )
            requests[1].url.encodedPath shouldBe "/avifbigtn/0/05/000050.avif"
            requests[2].url.encodedPath shouldBe "/avifbigtn/6/7e/0007e6.avif"
        }
    }

    @Test
    fun `thumbnail forbidden response refreshes once and retains image request headers`() {
        val requests = mutableListOf<Request>()
        var lookups = 0
        client { request ->
            requests.add(request)
            if (request.isRouting()) {
                lookups++
                200 to if (lookups == 1) CURRENT_ROUTE else "var o = 0; b: 'next/'"
            } else {
                403 to ""
            }
        }.useClient { client ->
            val request = Request.Builder().url(OLD_THUMB).header("Range", "bytes=10-")
                .header("Referer", "https://hitomi.la/").header("If-None-Match", "image-etag").build()
            client.newCall(request).execute().use { it.code shouldBe 403 }
            requests.map { it.url.host } shouldBe listOf(
                "ltn.gold-usergeneratedcontent.net",
                "btn.gold-usergeneratedcontent.net",
                "ltn.gold-usergeneratedcontent.net",
                "atn.gold-usergeneratedcontent.net",
            )
            requests.filter { it.isRouting() }.forEach {
                it.header("Range") shouldBe null
                it.header("If-None-Match") shouldBe null
                it.header("Referer") shouldBe "https://hitomi.la/"
            }
            requests.last().header("Range") shouldBe "bytes=10-"
            requests.last().header("If-None-Match") shouldBe "image-etag"
        }
    }

    @Test
    fun `webp stale w host and previously generated a host recover to w host`() {
        for (oldHost in listOf("w1", "a1")) {
            val requests = mutableListOf<Request>()
            client { request ->
                requests.add(request)
                if (request.isRouting()) 200 to CURRENT_ROUTE else 404 to ""
            }.useClient { client ->
                client.fetch("https://$oldHost.gold-usergeneratedcontent.net/old/5/000050.webp", expectedCode = 404)
                requests.size shouldBe 3
                requests.last().url.toString() shouldBe
                    "https://w2.gold-usergeneratedcontent.net/1792656001/5/000050.webp"
            }
        }
    }

    @Test
    fun `unrelated hosts unsafe paths and mismatched hash directories are not retried`() {
        val urls = listOf(
            "https://a1.gold-usergeneratedcontent.net.example.org/old/5/000050.avif",
            "https://other.gold-usergeneratedcontent.net/old/5/000050.avif",
            "https://a3.gold-usergeneratedcontent.net/old/5/000050.avif",
            "http://a1.gold-usergeneratedcontent.net/old/5/000050.avif",
            "https://a1.gold-usergeneratedcontent.net:444/old/5/000050.avif",
            "https://a1.gold-usergeneratedcontent.net/old/6/000050.avif",
            "https://atn.gold-usergeneratedcontent.net/avifbigtn/1/05/000050.avif",
            "https://atn.gold-usergeneratedcontent.net/avifbigtn/0/05/000050.html",
            "https://atn.gold-usergeneratedcontent.net/other/0/05/000050.avif",
            "$OLD_THUMB?redirect=other",
        )
        val requests = mutableListOf<Request>()
        client { request ->
            requests.add(request)
            404 to ""
        }.useClient { client ->
            urls.forEach { client.fetch(it, expectedCode = 404) }
            requests.map { it.url.toString() } shouldBe urls
        }
    }

    @Test
    fun `thumbnail routing expires and failed lookup can be retried`() {
        var now = 0L
        var lookups = 0
        client(HitomiImageInterceptor { now }) { request ->
            if (request.isRouting()) {
                lookups++
                if (lookups == 1) 503 to "" else 200 to CURRENT_ROUTE
            } else {
                200 to "image"
            }
        }.useClient { client ->
            shouldThrow<IOException> { client.fetch(OLD_THUMB) }
            client.fetch(OLD_THUMB)
            client.fetch(OLD_THUMB)
            lookups shouldBe 2
            now = TimeUnit.SECONDS.toNanos(60)
            client.fetch(OLD_THUMB)
            lookups shouldBe 3
        }
    }

    @Test
    fun `oversized routing response fails before any image request`() {
        val requests = mutableListOf<Request>()
        client { request ->
            requests.add(request)
            200 to "x".repeat(1_000_001)
        }.useClient { client ->
            shouldThrow<IOException> { client.fetch(OLD_THUMB) }
            requests.size shouldBe 1
            requests.single().isRouting() shouldBe true
        }
    }

    @Test
    fun `simultaneous thumbnails share one routing request without holding a network monitor`() {
        val lookupStarted = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)
        val callersStarted = CountDownLatch(4)
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val lookups = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        client { request ->
            requests.add(request)
            if (request.isRouting()) {
                lookups.incrementAndGet()
                lookupStarted.countDown()
                check(releaseLookup.await(5, TimeUnit.SECONDS))
                200 to CURRENT_ROUTE
            } else {
                200 to "image"
            }
        }.useClient { client ->
            try {
                val tasks = List(4) {
                    executor.submit {
                        callersStarted.countDown()
                        client.fetch(OLD_THUMB)
                    }
                }
                callersStarted.await(5, TimeUnit.SECONDS) shouldBe true
                lookupStarted.await(5, TimeUnit.SECONDS) shouldBe true
                releaseLookup.countDown()
                tasks.forEach { it.get(5, TimeUnit.SECONDS) }
                lookups.get() shouldBe 1
                requests.count { it.url.host == "btn.gold-usergeneratedcontent.net" } shouldBe 4
            } finally {
                releaseLookup.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `cancelling routing owner does not fail other covers waiting for the same lookup`() {
        val lookupStarted = CountDownLatch(1)
        val followersStarted = CountDownLatch(2)
        val followerThreads = ConcurrentHashMap.newKeySet<Thread>()
        val lookups = AtomicInteger()
        val executor = Executors.newFixedThreadPool(3)
        val client = OkHttpClient.Builder().addInterceptor(HitomiImageInterceptor()).addInterceptor { chain ->
            val request = chain.request()
            if (request.isRouting() && lookups.incrementAndGet() == 1) {
                lookupStarted.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                chain.call().isCanceled() shouldBe true
                throw IOException("Cancelled fixture lookup")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (request.isRouting()) CURRENT_ROUTE else "image").toResponseBody()).build()
        }.build()
        client.useClient {
            val ownerCall = client.newCall(Request.Builder().url(OLD_THUMB).build())
            try {
                val owner = executor.submit<Boolean> {
                    shouldThrow<IOException> { ownerCall.execute().close() }
                    true
                }
                lookupStarted.await(2, TimeUnit.SECONDS) shouldBe true
                val followers = List(2) {
                    executor.submit {
                        followerThreads.add(Thread.currentThread())
                        followersStarted.countDown()
                        client.fetch(OLD_THUMB)
                    }
                }
                followersStarted.await(2, TimeUnit.SECONDS) shouldBe true
                // Both live calls must have joined the first future before its owner is cancelled.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                var joinedLookup = false
                while (System.nanoTime() < deadline) {
                    if (followerThreads.all { it.state == Thread.State.TIMED_WAITING }) {
                        joinedLookup = true
                        break
                    }
                    Thread.sleep(5)
                }
                joinedLookup shouldBe true
                lookups.get() shouldBe 1
                ownerCall.cancel()
                owner.get(2, TimeUnit.SECONDS) shouldBe true
                followers.forEach { it.get(2, TimeUnit.SECONDS) }
                lookups.get() shouldBe 2
            } finally {
                ownerCall.cancel()
                executor.shutdownNow()
            }
        }
    }

    private fun client(
        interceptor: HitomiImageInterceptor = HitomiImageInterceptor(),
        response: (Request) -> Pair<Int, String>,
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).addInterceptor { chain ->
        val request = chain.request()
        val (code, body) = response(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(body.toResponseBody()).build()
    }.build()

    private fun OkHttpClient.useClient(block: (OkHttpClient) -> Unit) {
        try {
            block(this)
        } finally {
            dispatcher.executorService.shutdown()
            connectionPool.evictAll()
        }
    }

    private fun OkHttpClient.fetch(url: String, expectedCode: Int = 200) {
        newCall(Request.Builder().url(url).build()).execute().use { it.code shouldBe expectedCode }
    }

    private fun Request.isRouting(): Boolean = url.encodedPath == "/gg.js"

    private companion object {
        const val OLD_THUMB = "https://atn.gold-usergeneratedcontent.net/avifbigtn/0/05/000050.avif"
        const val CURRENT_ROUTE = "var o = 1; switch(g) { case 1662: o = 0; break; } b: '1792656001/'"
    }
}
