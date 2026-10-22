package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.engine.TextOrientation
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.service.TranslationPreferences

class KoreanTranslationConfigTest {
    @Test
    fun `Korean defaults never include Chinese examples`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        val config = EngineConfigMapping.buildEngineConfig(prefs, "auto_whole")
        config.translator.fromLangName shouldBe "Japanese"
        config.translator.toLangName shouldBe "Korean"
        config.translator.sampleTarget shouldBe ""
        config.translator.sampleSource shouldBe ""
        config.render.orientation shouldBe TextOrientation.HORIZONTAL
        prefs.keepMaterials.get() shouldBe true
        prefs.provider.get() shouldBe "custom"
    }

    @Test
    fun `local servers accept an empty key but cloud providers require one`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        prefs.hasCredentials() shouldBe true
        prefs.provider.set("openai")
        prefs.hasCredentials() shouldBe false
        prefs.apiKeyFor("openai").set("test-only")
        prefs.hasCredentials() shouldBe true
        prefs.provider.set("custom")
        prefs.activeApiKey() shouldBe ""
    }

    @Test
    fun `Chinese examples only apply to their own target language`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        prefs.targetLangName.set(TranslationPreferences.TRAD_CHINESE_LANG)
        EngineConfigMapping.buildEngineConfig(prefs, "boxfill").translator.sampleTarget.isNotBlank() shouldBe true
        prefs.targetLangName.set("English")
        EngineConfigMapping.buildEngineConfig(prefs, "boxfill").translator.sampleTarget shouldBe ""
    }
}
