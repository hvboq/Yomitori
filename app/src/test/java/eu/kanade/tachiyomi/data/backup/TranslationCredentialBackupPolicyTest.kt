package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class TranslationCredentialBackupPolicyTest {
    private val credentialKeys = listOf(
        "translation_api_key",
        "translation_api_key_enc",
        "translation_api_key_enc__custom",
        "translation_api_key_enc__sakura",
        "translation_api_key_enc__openai",
    )

    @Test
    fun `backup excludes legacy and encrypted API keys even when private preferences are requested`() {
        val store = MutableStringPreferenceStore()
        credentialKeys.forEach { store.getString(it).set("sensitive") }
        store.getString("translation_model").set("model-id")
        val creator = PreferenceBackupCreator(mockk(), store)
        for (includePrivate in listOf(false, true)) {
            creator.createApp(includePrivate).map { it.key } shouldBe listOf("translation_model")
        }
    }

    @Test
    fun `restoring a changed endpoint clears existing custom credentials and ignores imported secrets`() {
        val store = MutableStringPreferenceStore()
        credentialKeys.forEach { store.getString(it).set("current") }
        store.getString("translation_api_base").set("https://trusted.example/v1")
        val backup = credentialKeys.map { BackupPreference(it, StringPreferenceValue("imported")) } +
            BackupPreference("translation_api_base", StringPreferenceValue("https://other.example/v1"))
        val restored = TranslationCredentialBackupPolicy.prepareRestore(backup, store)
        restored.map { it.key } shouldBe listOf("translation_api_base")
        store.getString("translation_api_key_enc__custom").isSet() shouldBe false
        store.getString("translation_api_key_enc__sakura").isSet() shouldBe false
        store.getString("translation_api_key_enc__openai").get() shouldBe "current"
    }

    @Test
    fun `duplicate endpoints clear credentials before any intermediate endpoint is restored`() {
        val store = MutableStringPreferenceStore()
        val original = "https://trusted.example/v1"
        store.getString("translation_api_base").set(original)
        store.getString("translation_api_key_enc__custom").set("trusted-key")
        store.getString("translation_api_key_enc__sakura").set("trusted-sakura-key")
        val backup = listOf(
            BackupPreference("translation_api_base", StringPreferenceValue(original)),
            BackupPreference("translation_api_base", StringPreferenceValue("https://other.example/v1")),
            BackupPreference("translation_model", StringPreferenceValue("restored-model")),
            BackupPreference("translation_api_base", StringPreferenceValue(original)),
        )

        val restored = TranslationCredentialBackupPolicy.prepareRestore(backup, store)
        restored shouldBe backup
        restored.forEach { preference ->
            store.getString("translation_api_key_enc__custom").isSet() shouldBe false
            store.getString("translation_api_key_enc__sakura").isSet() shouldBe false
            store.getString(preference.key).set((preference.value as StringPreferenceValue).value)
        }
        store.getString("translation_api_base").get() shouldBe original
        store.getString("translation_model").get() shouldBe "restored-model"
    }

    @Test
    fun `same endpoint or absent endpoint preserves existing credentials`() {
        for (backup in listOf(
            emptyList(),
            listOf(BackupPreference("translation_api_base", StringPreferenceValue(" https://trusted.example/v1 "))),
        )) {
            val store = MutableStringPreferenceStore()
            store.getString("translation_api_base").set("https://trusted.example/v1")
            store.getString("translation_api_key_enc__custom").set("current")
            TranslationCredentialBackupPolicy.prepareRestore(backup, store)
            store.getString("translation_api_key_enc__custom").get() shouldBe "current"
        }
    }
}

internal class MutableStringPreferenceStore : PreferenceStore by InMemoryPreferenceStore() {
    private val values = mutableMapOf<String, Preference<String>>()

    override fun getString(key: String, defaultValue: String): Preference<String> =
        values.getOrPut(key) { InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue) }

    override fun getAll(): Map<String, *> = values.filterValues { it.isSet() }.mapValues { it.value.get() }
}
