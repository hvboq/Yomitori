package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/** Bounded fixed-schema diagnostics. No field can carry a page name, URL, source or target text. */
internal object PageProcessingSummaryCodec {
    const val MAX_BYTES = 16 * 1024
    private const val SCHEMA_VERSION = 2
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
            val text = bytes.toString(Charsets.UTF_8)
            when (json.parseToJsonElement(text).jsonObject.getValue("schemaVersion").jsonPrimitive.int) {
                1 -> {
                    // Verify the exact old DTO before adding an unknown breakdown in memory.
                    // Re-encoding v1 with v2 defaults would change its checksum and canonical bytes.
                    val envelope = json.decodeFromString<LegacyEnvelopeV1>(text)
                    val record = envelope.record.toCurrent()
                    validate(record)
                    verifyEnvelope(
                        envelope.checksumSha256,
                        json.encodeToString(envelope.record),
                        json.encodeToString(envelope),
                        bytes,
                    )
                    record
                }
                SCHEMA_VERSION -> {
                    val envelope = json.decodeFromString<Envelope>(text)
                    validate(envelope.record)
                    verifyEnvelope(
                        envelope.checksumSha256,
                        json.encodeToString(envelope.record),
                        json.encodeToString(envelope),
                        bytes,
                    )
                    envelope.record
                }
                else -> null
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            null
        }
    }

    private fun verifyEnvelope(checksum: String, payload: String, canonical: String, bytes: ByteArray) {
        require(sha256.matches(checksum)) { "Invalid processing summary checksum" }
        require(checksum == digest(payload.toByteArray(Charsets.UTF_8))) { "Processing summary checksum mismatch" }
        // Also rejects duplicate members, unknown fields, invalid UTF-8 and alternate JSON encodings.
        require(canonical.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Noncanonical processing summary" }
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
            require(counts == null && outcome.pendingOcr == null) { "Unknown processing result has observations" }
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
        outcome.pendingOcr?.let { pending ->
            require(outcome.reason == PageProcessingReason.PARTIAL) { "Non-pending result has OCR breakdown" }
            require(
                pending.unrecognizedLines in counts.pendingRegions..MAX_SUMMARY_LINES &&
                    pending.heldRecognizedLines in 0..MAX_SUMMARY_LINES,
            ) { "Invalid pending OCR line counts" }
            val minimumLines = pending.unrecognizedLines + pending.heldRecognizedLines +
                counts.ocrPolicyKeptLines + counts.translatedRegions + counts.responseFilteredRegions
            require(minimumLines <= MAX_SUMMARY_LINES) { "Too many processing summary lines" }
            require(
                counts.detectedLines?.let { minimumLines <= it } != false,
            ) { "Pending OCR lines exceed detected lines" }
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Serializable
    private data class Envelope(val schemaVersion: Int, val record: PageProcessingRecord, val checksumSha256: String)

    /** Frozen v1 field order/defaults are part of its canonical checksum contract. */
    @Serializable
    private data class LegacyOutcomeV1(
        val reason: PageProcessingReason,
        val counts: PageProcessingCounts? = null,
    )

    @Serializable
    private data class LegacyRecordV1(
        val binding: PageProcessingBinding,
        val outcome: LegacyOutcomeV1,
        val recordedAtEpochMs: Long,
    ) {
        fun toCurrent() = PageProcessingRecord(
            binding,
            PageProcessingOutcome(outcome.reason, outcome.counts),
            recordedAtEpochMs,
        )
    }

    @Serializable
    private data class LegacyEnvelopeV1(val schemaVersion: Int, val record: LegacyRecordV1, val checksumSha256: String)
}
