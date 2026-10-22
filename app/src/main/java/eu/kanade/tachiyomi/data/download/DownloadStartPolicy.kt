package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.model.Download

/** A targeted retry queues its selected item first; the worker must leave other errors alone. */
internal fun shouldResumeDownload(status: Download.State, retryErrors: Boolean): Boolean =
    status != Download.State.DOWNLOADED && (retryErrors || status != Download.State.ERROR)
