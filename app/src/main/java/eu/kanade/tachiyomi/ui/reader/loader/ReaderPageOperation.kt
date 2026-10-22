package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Restore display state even when the page operation's coroutine was cancelled. */
internal suspend fun refreshAfterPageOperation(page: ReaderPage) {
    withContext(NonCancellable + Dispatchers.Main.immediate) {
        try {
            page.chapter.pageLoader?.takeUnless { it.isRecycled }?.retryPage(page)
        } catch (error: Exception) {
            // Display failures must not mask the operation's cancellation or leave a permanent spinner.
            page.status = Page.State.Error(error)
        }
    }
}
