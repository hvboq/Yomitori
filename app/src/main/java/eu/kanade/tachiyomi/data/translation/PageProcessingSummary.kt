package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.Serializable
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.RegionState

/** A saved explanation of a processing result, never an authority to skip or overwrite a page. */
@Serializable
enum class PageProcessingReason {
    TRANSLATED,
    PARTIAL,
    NO_TEXT,
    OCR_POLICY_KEPT,
    RESPONSE_FILTERED,
    MIXED_KEPT,
    UNKNOWN,
}

/** Final progress counts include earlier resumed successes; these are not API request counts. */
@Serializable
data class PageProcessingCounts(
    val detectedLines: Int?,
    val regions: Int,
    val ocrPolicyKeptLines: Int,
    val ocrPolicyKeptRegions: Int,
    val responseFilteredRegions: Int,
    val translatedRegions: Int,
    val pendingRegions: Int,
)

/** Numeric observations of unresolved groups, never an explanation of why recognition was empty. */
@Serializable
data class PageProcessingPendingOcr(val unrecognizedLines: Int, val heldRecognizedLines: Int)

/** Only immutable scalars escape inference: no recognized/translated text, geometry or bitmaps. */
@Serializable
data class PageProcessingOutcome(
    val reason: PageProcessingReason,
    val counts: PageProcessingCounts? = null,
    /** Null means that this saved observation has no breakdown; it must never be displayed as zero. */
    val pendingOcr: PageProcessingPendingOcr? = null,
) {
    companion object {
        /** Missing/inconsistent custom results must not be explained using a free-form reason. */
        fun fromProgress(
            progress: PageProgress?,
            detectedLines: Int?,
            skipped: Boolean = false,
        ): PageProcessingOutcome {
            val unknown = PageProcessingOutcome(PageProcessingReason.UNKNOWN)
            if (progress == null || detectedLines?.let { it !in 0..MAX_SUMMARY_LINES } == true) return unknown
            try {
                progress.validateFor(progress.width, progress.height)
            } catch (_: IllegalArgumentException) {
                return unknown
            }
            if (progress.noTextDetected) {
                if (detectedLines != null && detectedLines != 0) return unknown
                return PageProcessingOutcome(
                    PageProcessingReason.NO_TEXT,
                    PageProcessingCounts(0, 0, 0, 0, 0, 0, 0),
                )
            }
            if (detectedLines == 0) return unknown

            val translated = progress.regions.count { it.state == RegionState.TRANSLATED }
            val pending = progress.regions.count { it.state == RegionState.OCR_PENDING }
            if (skipped && (translated != 0 || pending != 0)) return unknown
            val keptByPolicy = progress.regions.count { region ->
                region.state == RegionState.INTENTIONALLY_KEPT && region.lines.any { it.intentionallyKept }
            }
            val responseFiltered = progress.regions.count { region ->
                region.state == RegionState.INTENTIONALLY_KEPT && region.lines.none { it.intentionallyKept }
            }
            val counts = PageProcessingCounts(
                detectedLines = detectedLines,
                regions = progress.regions.size,
                ocrPolicyKeptLines = progress.regions.sumOf { region -> region.lines.count { it.intentionallyKept } },
                ocrPolicyKeptRegions = keptByPolicy,
                responseFilteredRegions = responseFiltered,
                translatedRegions = translated,
                pendingRegions = pending,
            )
            val reason = when {
                pending > 0 -> PageProcessingReason.PARTIAL
                translated > 0 -> PageProcessingReason.TRANSLATED
                keptByPolicy > 0 && responseFiltered > 0 -> PageProcessingReason.MIXED_KEPT
                keptByPolicy > 0 -> PageProcessingReason.OCR_POLICY_KEPT
                responseFiltered > 0 -> PageProcessingReason.RESPONSE_FILTERED
                else -> return unknown
            }
            val pendingOcr = if (pending > 0) {
                val pendingLines = progress.regions.filter { it.state == RegionState.OCR_PENDING }.flatMap { it.lines }
                PageProcessingPendingOcr(
                    unrecognizedLines = pendingLines.count { it.text.isBlank() && !it.intentionallyKept },
                    heldRecognizedLines = pendingLines.count { it.text.isNotBlank() && !it.intentionallyKept },
                )
            } else {
                null
            }
            return PageProcessingOutcome(reason, counts, pendingOcr)
        }
    }
}

/** Historical UI data only: this does not claim compatibility with the current engine settings. */
data class PageProcessingDetails(val outcome: PageProcessingOutcome, val recordedAtEpochMs: Long)

@Serializable
internal data class PageProcessingBinding(
    val pageNameSha256: String,
    val sourceSha256: String?,
    val displaySha256: String,
    val sourceTokenSha256: String,
    val journalRevision: String?,
    val contextSha256: String?,
)

@Serializable
internal data class PageProcessingRecord(
    val binding: PageProcessingBinding,
    val outcome: PageProcessingOutcome,
    val recordedAtEpochMs: Long,
)

internal const val MAX_SUMMARY_REGIONS = 4096
internal const val MAX_SUMMARY_LINES = 16384
