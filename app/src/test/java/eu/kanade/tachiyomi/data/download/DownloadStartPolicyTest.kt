package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.model.Download
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DownloadStartPolicyTest {
    @Test
    fun targetedRetryStartsSelectedQueuedItemAndLeavesOtherErrorsAndCompletedItemsAlone() {
        val states = listOf(Download.State.QUEUE, Download.State.ERROR, Download.State.DOWNLOADED)
        assertEquals(listOf(Download.State.QUEUE), states.filter { shouldResumeDownload(it, retryErrors = false) })
    }

    @Test
    fun ordinaryStartRetainsItsExistingResumeAllUnfinishedBehavior() {
        assertEquals(
            Download.State.entries.filter { it != Download.State.DOWNLOADED },
            Download.State.entries.filter { shouldResumeDownload(it, retryErrors = true) },
        )
    }
}
