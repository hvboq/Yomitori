package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslatorConfig
import org.junit.jupiter.api.Test

class StoredTranslationContextTest {
    @Test
    fun `semantic context round trips without raw language prompt or route values`() {
        val config = TranslatorConfig(
            provider = "private-provider",
            apiBase = "https://private-user:private-password@fixture.invalid/private-path?token=private-query",
            model = "private-model",
            fromLangName = "Japanese",
            toLangName = "Korean",
            sampleSource = "<|1|>private-source",
            sampleTarget = "<|1|>private-target",
        )
        val context = TranslationContext.fromTranslator(config)
        val payload = StoredTranslationContext.encode(context)
        StoredTranslationContext.decode(payload) shouldBe context
        listOf("private-", "Japanese", "Korean", "fixture.invalid", "token", "https").forEach {
            payload.contains(it) shouldBe false
        }
    }

    @Test
    fun `same language server failover has the same persisted compatibility identity`() {
        val original = TranslatorConfig(toLangName = "Korean")
        val failover = original.copy(provider = "custom", apiBase = "http://127.0.0.1/v1", model = "new-model")
        StoredTranslationContext.encode(TranslationContext.fromTranslator(original)) shouldBe
            StoredTranslationContext.encode(TranslationContext.fromTranslator(failover))
    }

    @Test
    fun `language and effective prompt changes have different compatibility identities`() {
        val original = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
        val changed = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "English"))
        (StoredTranslationContext.decode(StoredTranslationContext.encode(original)) == changed) shouldBe false
    }

    @Test
    fun `unsupported schema and unknown fields fail closed`() {
        val payload = payload()
        rejected(payload.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        rejected(payload.dropLast(1) + ",\"apiBase\":\"unexpected\"}")
    }

    @Test
    fun `missing malformed truncated and non object context cannot become legacy absence`() {
        rejected("")
        rejected("null")
        rejected("[]")
        rejected("{}")
        rejected(payload().dropLast(1))
        rejected(payload().replace("\"schemaVersion\":1,", ""))
    }

    @Test
    fun `invalid digest shape and noncanonical duplicate members are rejected`() {
        val payload = payload()
        val fingerprint = context().semanticFingerprint
        rejected(payload.replace(fingerprint, fingerprint.uppercase()))
        rejected(payload.replace(fingerprint, "g".repeat(64)))
        rejected(payload.replace(fingerprint, fingerprint.dropLast(1)))
        rejected(payload.dropLast(1) + ",\"schemaVersion\":1}")
        rejected(" $payload")
    }

    @Test
    fun `payload byte bounds are checked before json parsing`() {
        rejected("x".repeat(StoredTranslationContext.MAX_PAYLOAD_BYTES + 1))
        rejected("가".repeat(StoredTranslationContext.MAX_PAYLOAD_BYTES / 3 + 1))
    }

    private fun context() = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))

    private fun payload() = StoredTranslationContext.encode(context())

    private fun rejected(payload: String) {
        shouldThrow<IllegalArgumentException> { StoredTranslationContext.decode(payload) }
    }
}
