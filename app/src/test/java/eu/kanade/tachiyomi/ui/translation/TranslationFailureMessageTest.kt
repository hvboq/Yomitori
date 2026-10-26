package eu.kanade.tachiyomi.ui.translation

import eu.kanade.tachiyomi.data.translation.model.TranslationFailureCode
import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.engine.FailureKind
import li.joye.yakuyomi.engine.ProcessingFailure
import li.joye.yakuyomi.engine.ProcessingStage
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR

/** Shared production resource resolver; no Context, network or copied UI mapping. */
class TranslationFailureMessageTest {
    @Test
    fun `typed timeout uses the predefined timeout notice and other causes remain distinct`() {
        translationFailureMessage(TranslationFailureCode.SERVER_TIMEOUT) shouldBe
            MR.strings.translation_failure_server_timeout
        translationFailureMessage(TranslationFailureCode.SERVER_DNS) shouldBe MR.strings.translation_failure_server_dns
        translationFailureMessage(TranslationFailureCode.STORAGE) shouldBe MR.strings.translation_failure_storage
        (
            translationFailureMessage(TranslationFailureCode.SERVER_TIMEOUT) ==
                translationFailureMessage(TranslationFailureCode.UNKNOWN)
            ) shouldBe false
    }

    @Test
    fun `missing and unknown metadata use the existing safe unknown message`() {
        translationFailureMessage(null) shouldBe MR.strings.translation_failure_unknown
        translationFailureMessage(TranslationFailureCode.UNKNOWN) shouldBe MR.strings.translation_failure_unknown
    }

    @Test
    fun `a non translation timeout kind does not become a server timeout notice`() {
        val failure = ProcessingFailure(ProcessingStage.OCR, FailureKind.TIMEOUT)
        val code = TranslationFailureCode.fromProcessingFailure(failure)
        code shouldBe TranslationFailureCode.OCR
        translationFailureMessage(code) shouldBe MR.strings.translation_failure_ocr
        (translationFailureMessage(code) == MR.strings.translation_failure_server_timeout) shouldBe false
    }
}
