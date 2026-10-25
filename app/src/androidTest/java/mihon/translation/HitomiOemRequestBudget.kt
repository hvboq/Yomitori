package mihon.translation

import android.os.SystemClock
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Budgets only the derived image client; real Hitomi metadata delegates use their existing client. */
internal class HitomiOemRequestBudget {
    private val started = SystemClock.elapsedRealtime()
    private val active = ConcurrentHashMap.newKeySet<Call>()
    val applicationAttempts = AtomicInteger()
    val networkExchanges = AtomicInteger()
    val bytesRead = AtomicLong()

    val network = Interceptor { chain ->
        checkTime()
        val url = chain.request().url
        val page = url.host.matches(Regex("[aw][12]\\.gold-usergeneratedcontent\\.net"))
        val route = url.host == "ltn.gold-usergeneratedcontent.net" && url.encodedPath == "/gg.js"
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || (!page && !route)
        ) {
            throw IOException("AUDIT_IMAGE_HOST_GUARD")
        }
        if (networkExchanges.incrementAndGet() > MAX_ATTEMPTS) throw IOException("AUDIT_REQUEST_BUDGET")
        chain.proceed(chain.request())
    }

    // Appended after HitomiImageInterceptor: its bounded recovery and gg.js chain.proceed calls count too.
    val application = Interceptor { chain ->
        checkTime()
        if (applicationAttempts.incrementAndGet() > MAX_ATTEMPTS) throw IOException("AUDIT_REQUEST_BUDGET")
        val call = chain.call()
        active += call
        try {
            val response = chain.proceed(chain.request())
            try {
                val offset = if (response.code == 206) {
                    val range = response.header("Content-Range").orEmpty()
                    val actual = Regex("bytes ([0-9]+)-[0-9]+/[0-9]+\\b").matchEntire(range)
                        ?.groupValues?.get(1)?.toLongOrNull() ?: throw IOException("AUDIT_RANGE_GUARD")
                    val expected = Regex("bytes=([0-9]+)-").matchEntire(response.request.header("Range").orEmpty())
                        ?.groupValues?.get(1)?.toLongOrNull() ?: throw IOException("AUDIT_RANGE_GUARD")
                    if (actual != expected) throw IOException("AUDIT_RANGE_GUARD")
                    actual
                } else {
                    0L
                }
                if (offset !in 0 until MAX_IMAGE_BYTES) throw IOException("AUDIT_IMAGE_SIZE_GUARD")
                val maximum = MAX_IMAGE_BYTES - offset
                val body = response.body
                if (body.contentLength() > maximum) throw IOException("AUDIT_IMAGE_SIZE_GUARD")
                response.newBuilder().body(object : ResponseBody() {
                    private val wrapped: BufferedSource = object : ForwardingSource(body.source()) {
                        private var read = 0L

                        override fun read(sink: Buffer, byteCount: Long): Long {
                            checkTime()
                            val count = super.read(sink, minOf(byteCount, maximum - read + 1))
                            if (count > 0) {
                                read += count
                                if (read > maximum || bytesRead.addAndGet(count) > MAX_TOTAL_BYTES) {
                                    call.cancel()
                                    throw IOException("AUDIT_BODY_BUDGET")
                                }
                            }
                            return count
                        }

                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                active -= call
                            }
                        }
                    }.buffer()

                    override fun contentType(): MediaType? = body.contentType()
                    override fun contentLength(): Long = body.contentLength()
                    override fun source(): BufferedSource = wrapped
                }).build()
            } catch (failure: Throwable) {
                response.close()
                throw failure
            }
        } catch (failure: Throwable) {
            active -= call
            throw failure
        }
    }

    fun cancelOwnedCalls() {
        active.toList().forEach { it.cancel() }
    }

    fun requireClosed() {
        check(active.isEmpty()) { "AUDIT_OWNED_IMAGE_RESPONSE_STILL_OPEN" }
    }

    private fun checkTime() {
        if (SystemClock.elapsedRealtime() - started > 120_000) throw IOException("AUDIT_IMAGE_WALL_BUDGET")
    }

    companion object {
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
        private const val MAX_ATTEMPTS = 20
    }
}
