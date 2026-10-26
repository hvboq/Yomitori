package eu.kanade.tachiyomi.ui.translation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.translation.model.TranslationFailureCode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Only predefined local messages are shown; exception text and server responses never enter this UI. */
@Composable
internal fun TranslationFailureHint(failureCode: TranslationFailureCode?, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(translationFailureMessage(failureCode)),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Shared predefined messages; null/unknown retains the queue's existing generic hint. */
internal fun translationFailureMessage(failureCode: TranslationFailureCode?): StringResource =
    when (failureCode ?: TranslationFailureCode.UNKNOWN) {
        TranslationFailureCode.SERVER_DNS -> MR.strings.translation_failure_server_dns
        TranslationFailureCode.SERVER_CONNECTION -> MR.strings.translation_failure_server_connection
        TranslationFailureCode.SERVER_TIMEOUT -> MR.strings.translation_failure_server_timeout
        TranslationFailureCode.SERVER_TLS -> MR.strings.translation_failure_server_tls
        TranslationFailureCode.SERVER_AUTH -> MR.strings.translation_failure_server_auth
        TranslationFailureCode.SERVER_RATE_LIMIT -> MR.strings.translation_failure_server_rate_limit
        TranslationFailureCode.SERVER_HTTP -> MR.strings.translation_failure_server_http
        TranslationFailureCode.SERVER_REQUEST -> MR.strings.translation_failure_server_request
        TranslationFailureCode.SERVER_RESPONSE -> MR.strings.translation_failure_server_response
        TranslationFailureCode.MODEL_UNAVAILABLE -> MR.strings.translation_failure_model_unavailable
        TranslationFailureCode.NO_PAGES -> MR.strings.translation_failure_no_pages
        TranslationFailureCode.OCR_PENDING -> MR.strings.translation_failure_ocr_pending
        TranslationFailureCode.IMAGE_READ -> MR.strings.translation_failure_image_read
        TranslationFailureCode.ORIGINAL_MISSING -> MR.strings.translation_failure_original_missing
        TranslationFailureCode.ORIGINAL_AMBIGUOUS -> MR.strings.translation_failure_original_ambiguous
        TranslationFailureCode.STORAGE -> MR.strings.translation_failure_storage
        TranslationFailureCode.RENDER -> MR.strings.translation_failure_render
        TranslationFailureCode.INPAINT -> MR.strings.translation_failure_inpaint
        TranslationFailureCode.DETECT -> MR.strings.translation_failure_detect
        TranslationFailureCode.OCR -> MR.strings.translation_failure_ocr
        TranslationFailureCode.NOT_DOWNLOADED -> MR.strings.translation_failure_not_downloaded
        TranslationFailureCode.MULTIPLE -> MR.strings.translation_failure_multiple
        TranslationFailureCode.UNKNOWN -> MR.strings.translation_failure_unknown
    }
