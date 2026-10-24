package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Bounded fixed-schema diagnostics. No field can carry a page name, URL, source or target text. */
internal object PageProcessingSummaryCodec {
    const val MAX_BYTES = 16 * 1024
    private const val SCHEMA_VERSION = 1
    private const val PREFIX = "page-summary-"
    private const val SUFFIX = ".summary"
    private val sha256 = Regex("[a-f0-9]{64}")
    private val revision = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val summaryName = Regex("$PREFIX[a-f0-9]{64}${Regex.escape(SUFFIX)}")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    fun exactName(fullPageFilename: String): String =
        PREFIX + digest(fullPageFilename.toByteArray(Charsets.UTF_8)) + SUFFIX

    fun isSummaryName(name: String): Boolean = summaryName.matches(name)

    fun encode(record: PageProcessingRecord): ByteArray {
        validate(record)
        val payload = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        val bytes = json.encodeToString(Envelope(SCHEMA_VERSION, record, digest(payload))).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Processing summary is too large" }
        return bytes
    }

    fun decode(bytes: ByteArray): PageProcessingRecord? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return try {
            val envelope = json.decodeFromString<Envelope>(bytes.toString(Charsets.UTF_8))
            require(envelope.schemaVersion == SCHEMA_VERSION) { "Unsupported processing summary" }
            validate(envelope.record)
            require(sha256.matches(envelope.checksumSha256)) { "Invalid processing summary checksum" }
            val payload = json.encodeToString(envelope.record).toByteArray(Charsets.UTF_8)
            require(envelope.checksumSha256 == digest(payload)) {
                "Processing summary checksum mismatch"
            }
            // Besides duplicate members and unknown fields, this rejects invalid UTF-8 and any
            // alternate encoding whose interpretation depends on a JSON parser's permissiveness.
            require(json.encodeToString(envelope).toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                "Noncanonical processing summary"
            }
            envelope.record
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            null
        }
    }

    internal fun matchesPageName(fullPageFilename: String, binding: PageProcessingBinding): Boolean =
        exactName(fullPageFilename) == PREFIX + binding.pageNameSha256 + SUFFIX

    private fun validate(record: PageProcessingRecord) {
        val binding = record.binding
        require(
            sha256.matches(binding.pageNameSha256) && sha256.matches(binding.displaySha256) &&
                sha256.matches(binding.sourceTokenSha256) && binding.sourceSha256?.let(sha256::matches) != false &&
                binding.contextSha256?.let(sha256::matches) != false &&
                binding.journalRevision?.let(revision::matches) != false,
        ) { "Invalid processing summary binding" }
        require(record.recordedAtEpochMs >= 0) { "Invalid processing summary timestamp" }
        val outcome = record.outcome
        val counts = outcome.counts
        if (outcome.reason == PageProcessingReason.UNKNOWN) {
            require(counts == null) { "Unknown processing result has counts" }
            return
        }
        requireNotNull(counts) { "Processing result has no counts" }
        require(
            counts.detectedLines?.let { it in 0..MAX_SUMMARY_LINES } != false &&
                counts.ocrPolicyKeptLines in 0..MAX_SUMMARY_LINES &&
                listOf(
                    counts.regions,
                    counts.ocrPolicyKeptRegions,
                    counts.responseFilteredRegions,
                    counts.translatedRegions,
                    counts.pendingRegions,
                ).all { it in 0..MAX_SUMMARY_REGIONS },
        ) { "Invalid processing summary counts" }
        require(
            counts.regions == counts.ocrPolicyKeptRegions + counts.responseFilteredRegions +
                counts.translatedRegions + counts.pendingRegions,
        ) { "Processing region counts do not add up" }
        require(
            counts.ocrPolicyKeptLines >= counts.ocrPolicyKeptRegions &&
                counts.detectedLines?.let { counts.ocrPolicyKeptLines <= it } != false,
        ) { "Invalid OCR policy line count" }
        val consistent = when (outcome.reason) {
            PageProcessingReason.NO_TEXT -> counts == PageProcessingCounts(0, 0, 0, 0, 0, 0, 0)
            PageProcessingReason.PARTIAL -> counts.regions > 0 && counts.pendingRegions > 0
            PageProcessingReason.TRANSLATED -> counts.pendingRegions == 0 && counts.translatedRegions > 0
            PageProcessingReason.OCR_POLICY_KEPT -> counts.regions > 0 && counts.ocrPolicyKeptRegions == counts.regions
            PageProcessingReason.RESPONSE_FILTERED ->
                counts.regions > 0 &&
                    counts.responseFilteredRegions == counts.regions && counts.ocrPolicyKeptLines == 0
            PageProcessingReason.MIXED_KEPT ->
                counts.ocrPolicyKeptRegions > 0 && counts.responseFilteredRegions > 0 &&
                    counts.regions == counts.ocrPolicyKeptRegions + counts.responseFilteredRegions
            PageProcessingReason.UNKNOWN -> false
        }
        require(consistent && (counts.regions == 0 || counts.detectedLines != 0)) { "Inconsistent processing result" }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Serializable
    private data class Envelope(val schemaVersion: Int, val record: PageProcessingRecord, val checksumSha256: String)
}
