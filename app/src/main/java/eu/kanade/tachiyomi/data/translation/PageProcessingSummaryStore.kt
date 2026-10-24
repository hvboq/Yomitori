package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CancellationException

/**
 * Optional historical diagnostics. The caller owns the page lock and bounded/exact-name file IO.
 * Missing, stale or corrupt records never authorize completion, source replacement or inference.
 */
internal class PageProcessingSummaryStore(
    private val readBytes: (String) -> ByteArray?,
    private val writeBytes: (String, ByteArray) -> Unit,
    private val delete: (String) -> Unit,
) {
    fun read(fullPageFilename: String, expectedBinding: PageProcessingBinding): PageProcessingRecord? = optional(null) {
        if (!PageProcessingSummaryCodec.matchesPageName(fullPageFilename, expectedBinding)) return@optional null
        val bytes = readBytes(PageProcessingSummaryCodec.exactName(fullPageFilename)) ?: return@optional null
        PageProcessingSummaryCodec.decode(bytes)?.takeIf { it.binding == expectedBinding }
    }

    /** A failed write may leave an older saved record; its timestamp must remain visible as such. */
    fun writeBestEffort(fullPageFilename: String, record: PageProcessingRecord): Boolean {
        // Try to retire the older explanation even when the replacement is invalid or IO fails.
        invalidateBestEffort(fullPageFilename)
        return optional(false) {
            require(PageProcessingSummaryCodec.matchesPageName(fullPageFilename, record.binding)) {
                "Processing summary page binding mismatch"
            }
            val name = PageProcessingSummaryCodec.exactName(fullPageFilename)
            writeBytes(name, PageProcessingSummaryCodec.encode(record))
            readBytes(name)?.let(PageProcessingSummaryCodec::decode) == record
        }
    }

    fun invalidateBestEffort(fullPageFilename: String): Boolean = optional(false) {
        delete(PageProcessingSummaryCodec.exactName(fullPageFilename))
        true
    }

    private inline fun <T> optional(fallback: T, action: () -> T): T = try {
        action()
    } catch (exception: Exception) {
        if (exception is CancellationException) throw exception
        fallback
    }
}
