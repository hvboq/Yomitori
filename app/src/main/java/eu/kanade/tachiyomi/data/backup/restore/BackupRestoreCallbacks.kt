package eu.kanade.tachiyomi.data.backup.restore

/** Receives restore status without requiring Android notification side effects. */
interface BackupRestoreCallbacks {
    fun showRestoreProgress(content: String, progress: Int, maxAmount: Int, sync: Boolean)

    fun showRestoreComplete(time: Long, errorCount: Int, path: String?, file: String?, sync: Boolean)
}
