package eu.kanade.tachiyomi.data.translation.model

import li.joye.yakuyomi.engine.FailureKind
import li.joye.yakuyomi.engine.ProcessingFailure
import li.joye.yakuyomi.engine.ProcessingStage

/** Stable, non-sensitive queue metadata. Never persist an exception message or server response here. */
enum class TranslationFailureCode(val wireValue: String) {
    SERVER_DNS("server_dns"),
    SERVER_CONNECTION("server_connection"),
    SERVER_TIMEOUT("server_timeout"),
    SERVER_TLS("server_tls"),
    SERVER_AUTH("server_auth"),
    SERVER_RATE_LIMIT("server_rate_limit"),
    SERVER_HTTP("server_http"),
    SERVER_REQUEST("server_request"),
    SERVER_RESPONSE("server_response"),
    MODEL_UNAVAILABLE("model_unavailable"),
    NO_PAGES("no_pages"),
    OCR_PENDING("ocr_pending"),
    IMAGE_READ("image_read"),
    ORIGINAL_MISSING("original_missing"),
    ORIGINAL_AMBIGUOUS("original_ambiguous"),
    STORAGE("storage"),
    RENDER("render"),
    INPAINT("inpaint"),
    DETECT("detect"),
    OCR("ocr"),
    NOT_DOWNLOADED("not_downloaded"),
    MULTIPLE("multiple"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** Missing legacy metadata stays absent; an unfamiliar future code must not discard a queue entry. */
        fun fromWire(value: String?): TranslationFailureCode? = value?.let { raw ->
            entries.firstOrNull { it.wireValue == raw } ?: UNKNOWN
        }

        /** A chapter can run pages concurrently, so merging must not depend on completion order. */
        fun merge(first: TranslationFailureCode?, second: TranslationFailureCode?): TranslationFailureCode? = when {
            first == null -> second
            second == null || first == second -> first
            else -> MULTIPLE
        }

        fun fromProcessingFailure(failure: ProcessingFailure?): TranslationFailureCode {
            if (failure == null) return UNKNOWN
            return when (failure.stage) {
                ProcessingStage.DETECT -> DETECT
                ProcessingStage.OCR -> OCR
                ProcessingStage.INPAINT -> INPAINT
                ProcessingStage.RENDER -> RENDER
                ProcessingStage.TRANSLATE -> when (failure.kind) {
                    FailureKind.DNS -> SERVER_DNS
                    FailureKind.CONNECTION -> SERVER_CONNECTION
                    FailureKind.TIMEOUT -> SERVER_TIMEOUT
                    FailureKind.TLS -> SERVER_TLS
                    FailureKind.AUTH -> SERVER_AUTH
                    FailureKind.RATE_LIMIT -> SERVER_RATE_LIMIT
                    FailureKind.HTTP_SERVER -> SERVER_HTTP
                    FailureKind.HTTP_REQUEST -> SERVER_REQUEST
                    FailureKind.INVALID_RESPONSE -> SERVER_RESPONSE
                    FailureKind.UNKNOWN -> UNKNOWN
                }
                ProcessingStage.UNKNOWN -> UNKNOWN
            }
        }
    }
}
