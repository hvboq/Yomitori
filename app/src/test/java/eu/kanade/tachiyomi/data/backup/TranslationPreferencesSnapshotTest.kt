package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.translation.EngineConfigMapping
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.StringCrypto
import tachiyomi.domain.translation.service.TranslationPreferences

class TranslationPreferencesSnapshotTest {
    private val cipher = object : StringCrypto {
        override fun encrypt(plain: String) = "encrypted:$plain"
        override fun decrypt(encrypted: String) = encrypted.takeIf { it.startsWith("encrypted:") }
            ?.removePrefix("encrypted:")
    }

    @Test
    fun `provider switch after config capture cannot attach another provider key`() {
        val store = MutableStringPreferenceStore()
        val live = TranslationPreferences(store, cipher)
        live.provider.set("custom")
        live.apiBase.set("https://custom.example/v1")
        live.apiKeyFor("custom").set("custom-secret")
        live.apiKeyFor("openai").set("openai-secret")
        val snapshot = live.snapshot()
        val config = EngineConfigMapping.buildEngineConfig(snapshot, "boxfill")
        live.provider.set("openai")
        live.activeApiKey() shouldBe "openai-secret"
        config.translator.apiBase shouldBe "https://custom.example/v1/chat/completions"
        snapshot.activeApiKey() shouldBe "custom-secret"
        live.snapshot().activeApiKey() shouldBe "openai-secret"
    }

    @Test
    fun `endpoint edit and new key do not mutate a captured request pair`() {
        val store = MutableStringPreferenceStore()
        val live = TranslationPreferences(store, cipher)
        live.apiBase.set("https://first.example/v1")
        live.apiKeyFor("custom").set("first-key")
        val before = live.snapshot()
        live.apiKeyFor("custom").set("")
        live.apiBase.set("https://second.example/v1")
        val withoutKey = live.snapshot()
        live.apiKeyFor("custom").set("second-key")
        before.apiBase.get() shouldBe "https://first.example/v1"
        before.activeApiKey() shouldBe "first-key"
        withoutKey.apiBase.get() shouldBe "https://second.example/v1"
        withoutKey.activeApiKey() shouldBe ""
        live.snapshot().activeApiKey() shouldBe "second-key"
    }

    @Test
    fun `snapshot retains decryption and migrates the legacy plaintext in the real store`() {
        val store = MutableStringPreferenceStore()
        store.getString("translation_api_key").set("legacy-secret")
        store.getString("translation_provider").set("deepseek")
        val snapshot = TranslationPreferences(store, cipher).snapshot()
        snapshot.activeApiKey() shouldBe "legacy-secret"
        store.getString("translation_api_key").isSet() shouldBe false
        store.getString("translation_api_key_enc").get() shouldBe "encrypted:legacy-secret"
    }
}
