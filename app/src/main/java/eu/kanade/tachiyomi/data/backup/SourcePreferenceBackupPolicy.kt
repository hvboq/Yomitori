package eu.kanade.tachiyomi.data.backup

/** Matches ConfigurableSource.preferenceKey without requiring the source to be installed. */
internal object SourcePreferenceBackupPolicy {
    fun isValidSourceKey(key: String): Boolean {
        // A canonical signed Long has at most 20 characters, including its optional minus sign.
        if (key.length !in 8..27 || !key.startsWith("source_")) return false
        val id = key.substring(7)
        return id.toLongOrNull()?.toString() == id
    }
}
