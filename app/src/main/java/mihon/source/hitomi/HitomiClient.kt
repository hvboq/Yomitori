// Adapted from qwopqwop200/Aidoku native Hitomi adapter (821a612f).
// Copyright 2025 Aidoku community source contributors. SPDX-License-Identifier: MIT
package mihon.source.hitomi

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

internal class HitomiClient(private val client: OkHttpClient, private val headers: () -> Headers) {
    private val json = Json { ignoreUnknownKeys = true }
    private val versionLock = Mutex()
    private var savedVersion: Pair<Long, String>? = null
    private val suggestions = HitomiSearchSuggestions(client, headers)

    suspend fun searchSuggestions(query: String): List<HitomiSearchSuggestion> = suggestions.get(query)

    private suspend fun bytes(
        url: String,
        first: Long? = null,
        last: Long? = null,
        allowEnd: Boolean = false,
        allowMissing: Boolean = false,
    ): ByteArray {
        val request = Request.Builder().url(url).headers(headers()).apply {
            if (first != null) header("Range", "bytes=$first-$last")
        }.build()
        // Keep the call attached to cancellation until the body is consumed, including stalled reads.
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(
                        runCatching {
                            response.use { readBytes(it, call, first, last, allowEnd, allowMissing) }
                        },
                    )
                }
            })
        }
    }

    private fun readBytes(
        response: Response,
        call: Call,
        first: Long?,
        last: Long?,
        allowEnd: Boolean,
        allowMissing: Boolean,
    ): ByteArray {
        if (allowMissing && response.code == 404) return byteArrayOf()
        if (!response.isSuccessful && !(allowEnd && response.code == 416)) {
            throw IOException("Hitomi request failed (HTTP ${response.code})")
        }
        val body = response.body
        require(body.contentLength() <= HitomiProtocol.MAX_BYTES) { "Hitomi response too large" }
        val data = body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                if (call.isCanceled()) throw IOException("Canceled")
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= HitomiProtocol.MAX_BYTES) { "Hitomi response too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(data.size <= HitomiProtocol.MAX_BYTES) { "Hitomi response too large" }
        if (call.isCanceled()) throw IOException("Canceled")
        return if (first == null) {
            data
        } else {
            HitomiProtocol.range(data, response.code, response.header("Content-Range"), first, last!!, allowEnd)
        }
    }

    suspend fun gallery(id: Long): JsonObject {
        require(id > 0)
        return json.parseToJsonElement(
            HitomiProtocol.galleryJson(bytes("$INDEX/galleries/$id.js").decodeToString()),
        ).jsonObject
    }

    suspend fun routing(): HitomiProtocol.Routing =
        HitomiProtocol.routing(bytes("$INDEX/gg.js").decodeToString())

    suspend fun page(path: String, page: Int): Pair<List<Long>, Boolean> {
        require(page > 0)
        val first = (page.toLong() - 1) * HitomiProtocol.PAGE_SIZE * 4
        val ids = HitomiProtocol.ids(bytes("$INDEX/$path", first, first + HitomiProtocol.PAGE_SIZE * 4 - 1, true))
        return ids to (ids.size == HitomiProtocol.PAGE_SIZE)
    }

    suspend fun search(query: String, language: String, page: Int): Pair<List<Long>, Boolean> {
        require(page > 0)
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val positives = terms.filterNot { it.startsWith('-') }.toMutableList()
        val negatives = terms.filter { it.startsWith('-') && it.length > 1 }.map { it.drop(1) }
        if (language != "all" &&
            positives.none { it.startsWith("language:") }
        ) {
            positives += "language:$language"
        }
        var result: List<Long>? = null
        for (term in positives) {
            currentCoroutineContext().ensureActive()
            val found = termIds(term)
            result = result?.let { previous ->
                val set = found.toHashSet()
                previous.filter { it in set }
            } ?: found
            if (result.isEmpty()) return emptyList<Long>() to false
        }
        if (result == null) result = HitomiProtocol.ids(bytes("$INDEX/index-$language.nozomi"))
        val excluded = HashSet<Long>()
        for (term in negatives) excluded.addAll(termIds(term))
        val ordered = result.filterNot { it in excluded }.distinct()
        val start = (page.toLong() - 1) * HitomiProtocol.PAGE_SIZE
        if (start >= ordered.size) return emptyList<Long>() to false
        val end = minOf(start + HitomiProtocol.PAGE_SIZE, ordered.size.toLong()).toInt()
        return ordered.subList(start.toInt(), end) to (end < ordered.size)
    }

    private suspend fun termIds(raw: String): List<Long> {
        val term = raw.replace('_', ' ')
        if (':' in term) {
            val namespace = term.substringBefore(':')
            val value = term.substringAfter(':').trim()
            require(value.isNotEmpty()) { "Empty search filter" }
            val prefix = when (namespace) {
                "language" -> ""
                "female", "male" -> "tag"
                "artist", "group", "series", "character", "tag", "type" -> namespace
                else -> throw IllegalArgumentException("Unsupported search filter: $namespace")
            }
            val filename = when (namespace) {
                "language" -> "index-$value.nozomi"
                "female", "male" -> "$term-all.nozomi"
                else -> "$value-all.nozomi"
            }
            val url = INDEX.toHttpUrl().newBuilder().apply { if (prefix.isNotEmpty()) addPathSegment(prefix) }
                .addPathSegment(filename).build()
            return HitomiProtocol.ids(bytes(url.toString(), allowMissing = true))
        }
        val key = MessageDigest.getInstance("SHA-256").digest(term.toByteArray()).copyOf(4)
        val version = versionLock.withLock {
            val now = System.nanoTime()
            savedVersion?.takeIf { now - it.first in 0 until 60_000_000_000L }?.second ?: run {
                val value = bytes("$INDEX/galleriesindex/version?_=0").decodeToString().trim()
                require(value.length in 1..128 && value.all(Char::isDigit)) { "Invalid search index version" }
                savedVersion = now to value
                value
            }
        }
        var address = 0L
        val visited = HashSet<Long>()
        repeat(64) {
            currentCoroutineContext().ensureActive()
            require(visited.add(address)) { "Cyclic search index" }
            val node = HitomiProtocol.node(
                bytes("$INDEX/galleriesindex/galleries.$version.index", address, Math.addExact(address, 463), true),
            )
            val position = node.keys.indexOfFirst { HitomiProtocol.compareKey(key, it) <= 0 }.let {
                if (it <
                    0
                ) {
                    node.keys.size
                } else {
                    it
                }
            }
            if (position < node.keys.size && HitomiProtocol.compareKey(key, node.keys[position]) == 0) {
                val value = node.values[position]
                return HitomiProtocol.searchIds(
                    bytes(
                        "$INDEX/galleriesindex/galleries.$version.data",
                        value.offset,
                        Math.addExact(
                            value.offset,
                            value.length.toLong() - 1,
                        ),
                    ),
                )
            }
            address = node.children[position]
            if (address == 0L) return emptyList()
        }
        error("Search index exceeded traversal limit")
    }

    companion object {
        const val INDEX = "https://ltn.gold-usergeneratedcontent.net"
    }
}
