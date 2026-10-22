package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.TranslationContext

/** Result metadata is captured from the registered engine, never from a later preferences read. */
internal data class StoredPageResult(
    val result: PageResult,
    val contextPayload: String?,
    val restarted: Boolean,
)

/**
 * A canonical, bounded journal payload containing only a semantic digest. Provider/model failover
 * does not invalidate successful targets; changing prompt, language or selection policy does.
 * Malformed or unknown payloads are failures, while an absent legacy payload requires fresh OCR.
 */
internal object StoredTranslationContext {
    const val MAX_PAYLOAD_BYTES = 1024
    private const val SCHEMA_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    fun encode(context: TranslationContext): String = json.encodeToString(
        Payload(SCHEMA_VERSION, context.semanticFingerprint),
    )

    fun decode(payload: String): TranslationContext {
        require(payload.isNotEmpty() && payload.length <= MAX_PAYLOAD_BYTES) { "Invalid stored context size" }
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Invalid stored context size" }
        val decoded = json.decodeFromString<Payload>(payload)
        require(decoded.schemaVersion == SCHEMA_VERSION) { "Unsupported stored translation context" }
        val context = TranslationContext(decoded.semanticFingerprint)
        // Only our canonical encoding is accepted. This also rejects duplicate members instead
        // of allowing a JSON parser's last-value-wins rule to choose a compatibility identity.
        require(encode(context) == payload) { "Noncanonical stored translation context" }
        return context
    }

    @Serializable
    private data class Payload(val schemaVersion: Int, val semanticFingerprint: String)
}
