package mihon.source.hitomi

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Repair persisted thumbnail URLs and expired page routes without executing remote JavaScript. */
internal class HitomiImageInterceptor(
    private val nanoTime: () -> Long = System::nanoTime,
) : Interceptor {
    private val routingLock = Any()
    private var cachedRouting: HitomiProtocol.Routing? = null
    private var cachedAt = 0L
    private var loading: CompletableFuture<HitomiProtocol.Routing>? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.method != "GET" && original.method != "HEAD") return chain.proceed(original)
        val image = imageRequest(original.url) ?: return chain.proceed(original)
        // Covers already-persisted atn URLs; metadata does not have to fetch gg.js for every cover.
        val request = if (image.thumbnailSize != null) {
            original.newBuilder().url(image.url(routing(chain, original, refresh = false))).build()
        } else {
            original
        }
        val response = chain.proceed(request)
        if (response.code != 403 && response.code != 404) return response
        response.close()
        val route = routing(chain, original, refresh = true)
        // Return the retry directly: another 403/404 must not cause an unbounded loop.
        return chain.proceed(original.newBuilder().url(image.url(route)).build())
    }

    private fun routing(chain: Interceptor.Chain, request: Request, refresh: Boolean): HitomiProtocol.Routing {
        return try {
            routingOnce(chain, request, refresh)
        } catch (error: RoutingOwnerCancelledException) {
            // The shared lookup belongs to another image call. Its cancellation must not cancel
            // this cover too. Retry ownership once; repeated cancellations cannot loop indefinitely.
            if (chain.call().isCanceled()) throw IOException("Canceled", error)
            routingOnce(chain, request, refresh)
        }
    }

    private fun routingOnce(chain: Interceptor.Chain, request: Request, refresh: Boolean): HitomiProtocol.Routing {
        var owner = false
        val future = synchronized(routingLock) {
            val cached = cachedRouting
            if (!refresh && cached != null && nanoTime() - cachedAt in 0 until CACHE_NANOS) return cached
            loading ?: CompletableFuture<HitomiProtocol.Routing>().also {
                loading = it
                owner = true
            }
        }
        if (!owner) {
            // Wait outside the monitor, and let cancelled image calls leave a shared lookup promptly.
            while (true) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                try {
                    return future.get(100, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    continue
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted awaiting image routing").apply { initCause(error) }
                } catch (error: ExecutionException) {
                    val cause = error.cause
                    if (cause is RoutingOwnerCancelledException) throw cause
                    throw IOException("Image routing refresh failed", error.cause)
                }
            }
        }
        try {
            val routingRequest = request.newBuilder().url("${HitomiClient.INDEX}/gg.js")
                .get()
                .removeHeader("Range")
                .removeHeader("If-None-Match")
                .removeHeader("If-Modified-Since")
                .header("Cache-Control", "no-cache").build()
            val route = chain.proceed(routingRequest).use { response ->
                if (!response.isSuccessful) throw IOException("Image routing refresh failed (HTTP ${response.code})")
                val source = response.body.source()
                source.request(1_000_001)
                if (source.buffer.size > 1_000_000) throw IOException("Image routing response too large")
                try {
                    HitomiProtocol.routing(source.buffer.readUtf8())
                } catch (error: IllegalArgumentException) {
                    throw IOException("Invalid image routing response", error)
                }
            }
            synchronized(routingLock) {
                cachedRouting = route
                cachedAt = nanoTime()
            }
            future.complete(route)
            return route
        } catch (error: Throwable) {
            // Release ownership before waking waiters so their retry cannot join this failed future.
            synchronized(routingLock) {
                if (loading === future) loading = null
            }
            future.completeExceptionally(
                if (chain.call().isCanceled()) RoutingOwnerCancelledException(error) else error,
            )
            throw error
        } finally {
            synchronized(routingLock) {
                if (loading === future) loading = null
            }
        }
    }

    private class RoutingOwnerCancelledException(cause: Throwable) :
        IOException("Image routing owner was cancelled", cause)

    private data class ImageRequest(val hash: String, val extension: String, val thumbnailSize: String? = null) {
        fun url(route: HitomiProtocol.Routing): String = if (thumbnailSize != null) {
            route.thumbnailUrl(hash, extension, thumbnailSize)
        } else {
            route.imageUrl(hash, extension)
        }
    }

    private fun imageRequest(url: HttpUrl): ImageRequest? {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath.length > 512 || url.encodedPath.contains('%') || url.query != null
        ) {
            return null
        }
        val segments = url.pathSegments
        val filename = segments.last()
        val hash = filename.substringBeforeLast('.')
        val extension = filename.substringAfterLast('.')
        if (!hash.matches(HASH) || extension !in setOf("avif", "webp", "jpg", "jpeg", "png", "gif")) return null
        if (url.host == "atn.gold-usergeneratedcontent.net" || url.host == "btn.gold-usergeneratedcontent.net") {
            if (segments.size != 4 || extension !in setOf("avif", "webp")) return null
            val size = when (segments[0]) {
                "${extension}bigtn" -> "big"
                "${extension}smalltn" -> "small"
                else -> return null
            }
            val suffix = hash.takeLast(3)
            if (segments[1] != suffix[2].toString() || segments[2] != suffix.take(2)) return null
            return ImageRequest(hash, extension, size)
        }
        if (!url.host.matches(PAGE_HOST) || segments.size < 3 ||
            segments.dropLast(2).any { !it.matches(PATH_SEGMENT) } ||
            segments[segments.lastIndex - 1] != HitomiProtocol.imageId(hash).toString()
        ) {
            return null
        }
        return ImageRequest(hash, extension)
    }

    private companion object {
        const val CACHE_NANOS = 60_000_000_000L
        val HASH = Regex("[a-fA-F0-9]{3,128}")
        val PAGE_HOST = Regex("[aw][12]\\.gold-usergeneratedcontent\\.net")
        val PATH_SEGMENT = Regex("[A-Za-z0-9_-]+")
    }
}
