package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import tachiyomi.core.common.preference.PreferenceStore

/** Keystore-bound ciphertext is not portable; legacy plaintext must never leave this device. */
internal object TranslationCredentialBackupPolicy {
    fun isCredential(key: String): Boolean = key == "translation_api_key" ||
        key == "translation_api_key_enc" || key.startsWith("translation_api_key_enc__")

    fun prepareRestore(preferences: List<BackupPreference>, store: PreferenceStore): List<BackupPreference> {
        val currentEndpoint = store.getString("translation_api_base").get().trim()
        val changesEndpoint = preferences.any {
            it.key == "translation_api_base" && it.value is StringPreferenceValue &&
                it.value.value.trim() != currentEndpoint
        }
        if (changesEndpoint) {
            // A restored endpoint must not receive credentials previously entrusted to another server.
            // Check every entry: restore applies duplicates in order, exposing intermediate endpoints.
            store.getString("translation_api_key_enc__custom").delete()
            store.getString("translation_api_key_enc__sakura").delete()
        }
        return preferences.filterNot { isCredential(it.key) }
    }
}
