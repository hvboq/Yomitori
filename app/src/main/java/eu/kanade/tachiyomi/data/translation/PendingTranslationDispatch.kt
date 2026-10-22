package eu.kanade.tachiyomi.data.translation

/** Serializes explicit reader intent with the download-completion enqueue boundary. */
internal class PendingTranslationDispatch {
    private val lock = Any()
    private val stoppedDownloads = mutableSetOf<Long>()

    fun mark(chapterId: Long, action: () -> Unit) = synchronized(lock) {
        stoppedDownloads.remove(chapterId)
        action()
    }

    fun cancel(chapterId: Long, hasPending: () -> Boolean, action: () -> Unit) = synchronized(lock) {
        if (hasPending()) stoppedDownloads.add(chapterId)
        action()
    }

    /** True also means an explicit stop consumed this completion; automatic translation must not override it. */
    fun onDownloaded(chapterId: Long, action: () -> Boolean): Boolean = synchronized(lock) {
        if (stoppedDownloads.remove(chapterId)) true else action()
    }
}
