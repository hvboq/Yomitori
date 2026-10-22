package mihon.source.hitomi

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException

data class HitomiSearchSuggestion(val value: String, val label: String = value, val count: Int? = null)

/** Uses the source's client, so suggestions share its DoH, TLS fragmentation and request headers. */
internal class HitomiSearchSuggestions(private val client: OkHttpClient, private val headers: () -> Headers) {
    suspend fun get(query: String): List<HitomiSearchSuggestion> {
        val url = HitomiSuggestionProtocol.url(query) ?: return emptyList()
        val request = Request.Builder().url(url).headers(headers()).build()
        // Keep cancellation attached until the body has been consumed, not just until headers arrive.
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { response.use(::readSuggestions) })
                }
            })
        }
    }

    private fun readSuggestions(response: Response): List<HitomiSearchSuggestion> {
        if (response.code == 404) return emptyList()
        if (!response.isSuccessful) throw IOException("Hitomi suggestions failed (HTTP ${response.code})")
        val body = response.body
        if (body.contentLength() > HitomiSuggestionProtocol.MAX_BYTES) {
            throw IOException("Hitomi suggestions response too large")
        }
        val source = body.source()
        val buffer = Buffer()
        while (true) {
            val read = source.read(buffer, minOf(8192L, HitomiSuggestionProtocol.MAX_BYTES + 1 - buffer.size))
            if (read == -1L) break
            if (buffer.size > HitomiSuggestionProtocol.MAX_BYTES) {
                throw IOException("Hitomi suggestions response too large")
            }
        }
        return try {
            HitomiSuggestionProtocol.parse(buffer.readUtf8())
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid Hitomi suggestions response")
        }
    }
}

/** Protocol from hitomi.la search.js / searchlib.js: per-character JSON indexes, not curated tags. */
internal object HitomiSuggestionProtocol {
    const val MAX_BYTES = 256L * 1024L
    const val MAX_RESULTS = 20
    private const val MAX_ROWS = 1000
    private const val MAX_QUERY_LENGTH = 128
    private const val MAX_NAME_LENGTH = 256
    private val namespaces =
        setOf("artist", "group", "series", "character", "tag", "female", "male", "language", "type")
    private val whitespace = Regex("\\s+")
    private val json = Json { ignoreUnknownKeys = true }

    fun url(query: String): HttpUrl? {
        if (query.any(Char::isISOControl)) return null
        val normalized = query.trim().lowercase().replace('_', ' ')
        if (normalized.isEmpty() || normalized.length > MAX_QUERY_LENGTH || normalized.any(Char::isISOControl)) {
            return null
        }
        val field = if (':' in normalized) normalized.substringBefore(':') else "global"
        if (field != "global" && field !in namespaces) return null
        val term = if (':' in normalized) normalized.substringAfter(':') else normalized
        if (field == "global" && term.isBlank()) return null
        val segments = term.codePoints().toArray().map { codePoint ->
            when (codePoint) {
                ' '.code -> "_"
                '/'.code -> "slash"
                '.'.code -> "dot"
                else -> String(Character.toChars(codePoint))
            }
        }
        return "https://tagindex.hitomi.la".toHttpUrl().newBuilder().apply {
            if (segments.isEmpty()) {
                addPathSegment("$field.json")
            } else {
                addPathSegment(field)
                segments.dropLast(1).forEach { addPathSegment(it) }
                addPathSegment("${segments.last()}.json")
            }
        }.build()
    }

    fun parse(text: String): List<HitomiSearchSuggestion> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Suggestion response too large" }
        val rows = requireNotNull(json.parseToJsonElement(text) as? JsonArray) { "Expected suggestion array" }
        require(rows.size <= MAX_ROWS) { "Too many suggestions" }
        val results = LinkedHashMap<String, HitomiSearchSuggestion>()
        for (element in rows) {
            val row = element as? JsonArray ?: continue
            if (row.size != 3) continue
            val nameField = row[0] as? JsonPrimitive ?: continue
            val namespaceField = row[2] as? JsonPrimitive ?: continue
            if (!nameField.isString || !namespaceField.isString) continue
            val name = nameField.content.trim()
            val namespace = namespaceField.content.lowercase()
            if (namespace !in namespaces || name.isEmpty() || name.length > MAX_NAME_LENGTH ||
                name.any(Char::isISOControl)
            ) {
                continue
            }
            val count = if (row[1] == JsonNull) {
                null
            } else {
                val number = row[1] as? JsonPrimitive ?: continue
                if (number.isString) continue
                number.intOrNull?.takeIf { it >= 0 } ?: continue
            }
            val value = "$namespace:${name.lowercase().replace(whitespace, "_")}"
            results.putIfAbsent(value, HitomiSearchSuggestion(value, "$namespace:$name", count))
            if (results.size == MAX_RESULTS) break
        }
        return results.values.toList()
    }
}
