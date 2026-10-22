package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Whole-file integrity: a truncated page name must never become a different completed page. */
internal object CompletionManifestCodec {
    private const val HEADER = "YOMITORI-MANIFEST-1\n"
    const val MAX_BYTES = 8 * 1024 * 1024
    const val PRIMARY = ".yakuyomi_translated"
    const val PREVIOUS = "$PRIMARY.previous"
    const val CHECKPOINT_PREFIX = "$PRIMARY.checkpoint-"

    fun isCheckpoint(name: String): Boolean = name.matches(Regex("${Regex.escape(CHECKPOINT_PREFIX)}[a-f0-9-]{36}"))

    fun read(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_BYTES) return null
            output.write(buffer, 0, count)
        }
    }

    fun resolve(files: Map<String, ByteArray>): Set<String> {
        val checkpoints = files.filterKeys(::isCheckpoint).values.mapNotNull(::decode)
        val primary = files[PRIMARY]
        // Once migrated, partial legacy-looking primary bytes cannot fabricate a completed name.
        if (checkpoints.isNotEmpty()) return checkpoints.flatten().toSet() + primary?.let(::decode).orEmpty()
        fun oldOrVerified(bytes: ByteArray?): Set<String> = bytes?.let {
            decode(it) ?: it.toString(Charsets.UTF_8).takeUnless { text -> text.startsWith("YOMITORI-") }
                ?.lineSequence()?.map { line -> line.trim() }?.filter { line -> line.isNotEmpty() }?.toSet().orEmpty()
        }.orEmpty()
        return oldOrVerified(primary) + oldOrVerified(files[PREVIOUS])
    }

    fun encode(pages: Set<String>): ByteArray {
        val payload = Json.encodeToString(pages.sorted()).toByteArray(Charsets.UTF_8)
        val bytes = (HEADER + digest(payload) + "\n").toByteArray(Charsets.UTF_8) + payload
        require(bytes.size <= MAX_BYTES) { "Translation manifest is too large" }
        return bytes
    }

    fun decode(bytes: ByteArray): Set<String>? = runCatching {
        if (bytes.size > MAX_BYTES) return null
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.startsWith(HEADER)) return null
        val separator = text.indexOf('\n', HEADER.length)
        if (separator != HEADER.length + 64) return null
        val payload = text.substring(separator + 1).toByteArray(Charsets.UTF_8)
        if (text.substring(HEADER.length, separator) != digest(payload)) return null
        Json.decodeFromString<List<String>>(payload.toString(Charsets.UTF_8)).toSet()
    }.getOrNull()

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
