package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.model.TranslationFailureCode
import li.joye.yakuyomi.engine.FailureKind
import li.joye.yakuyomi.engine.ProcessingFailure
import li.joye.yakuyomi.engine.ProcessingStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationFailureCodeTest {
    @Test
    fun `wire vocabulary stays stable and round trips without raw diagnostic text`() {
        val expected = listOf(
            "server_dns", "server_connection", "server_timeout", "server_tls", "server_auth",
            "server_rate_limit", "server_http", "server_request", "server_response", "model_unavailable",
            "no_pages", "ocr_pending", "image_read", "original_missing", "original_ambiguous", "storage",
            "render", "inpaint", "detect", "ocr", "not_downloaded", "multiple", "unknown",
        )
        assertEquals(expected, TranslationFailureCode.entries.map { it.wireValue })
        assertEquals(expected.size, expected.toSet().size)
        TranslationFailureCode.entries.forEach { code ->
            assertEquals(code, TranslationFailureCode.fromWire(code.wireValue))
            assertTrue(code.wireValue.matches(Regex("[a-z_]+")))
        }
    }

    @Test
    fun `missing legacy code stays absent and future or malformed codes use safe fallback`() {
        assertNull(TranslationFailureCode.fromWire(null))
        listOf("", "future_storage_v2", "SERVER_DNS", "https://invalid.example/private", "token=neutral")
            .forEach { assertEquals(TranslationFailureCode.UNKNOWN, TranslationFailureCode.fromWire(it)) }
    }

    @Test
    fun `concurrent page causes merge independently of order and grouping`() {
        val codes = listOf(null) + TranslationFailureCode.entries
        for (first in codes) {
            assertEquals(first, TranslationFailureCode.merge(first, null))
            assertEquals(first, TranslationFailureCode.merge(null, first))
            assertEquals(first, TranslationFailureCode.merge(first, first))
            for (second in codes) {
                assertEquals(
                    TranslationFailureCode.merge(first, second),
                    TranslationFailureCode.merge(second, first),
                )
                for (third in codes) {
                    assertEquals(
                        TranslationFailureCode.merge(TranslationFailureCode.merge(first, second), third),
                        TranslationFailureCode.merge(first, TranslationFailureCode.merge(second, third)),
                    )
                }
            }
        }
        assertEquals(
            TranslationFailureCode.MULTIPLE,
            TranslationFailureCode.merge(TranslationFailureCode.SERVER_DNS, TranslationFailureCode.STORAGE),
        )
    }

    @Test
    fun `only typed translation transport failures become server-specific queue causes`() {
        val expected = mapOf(
            FailureKind.DNS to TranslationFailureCode.SERVER_DNS,
            FailureKind.CONNECTION to TranslationFailureCode.SERVER_CONNECTION,
            FailureKind.TIMEOUT to TranslationFailureCode.SERVER_TIMEOUT,
            FailureKind.TLS to TranslationFailureCode.SERVER_TLS,
            FailureKind.AUTH to TranslationFailureCode.SERVER_AUTH,
            FailureKind.RATE_LIMIT to TranslationFailureCode.SERVER_RATE_LIMIT,
            FailureKind.HTTP_SERVER to TranslationFailureCode.SERVER_HTTP,
            FailureKind.HTTP_REQUEST to TranslationFailureCode.SERVER_REQUEST,
            FailureKind.INVALID_RESPONSE to TranslationFailureCode.SERVER_RESPONSE,
            FailureKind.UNKNOWN to TranslationFailureCode.UNKNOWN,
        )
        expected.forEach { (kind, code) ->
            assertEquals(
                code,
                TranslationFailureCode.fromProcessingFailure(ProcessingFailure(ProcessingStage.TRANSLATE, kind)),
            )
        }
    }

    @Test
    fun `non-translation stages never acquire a guessed server cause`() {
        val expected = mapOf(
            ProcessingStage.DETECT to TranslationFailureCode.DETECT,
            ProcessingStage.OCR to TranslationFailureCode.OCR,
            ProcessingStage.INPAINT to TranslationFailureCode.INPAINT,
            ProcessingStage.RENDER to TranslationFailureCode.RENDER,
            ProcessingStage.UNKNOWN to TranslationFailureCode.UNKNOWN,
        )
        expected.forEach { (stage, code) ->
            FailureKind.entries.forEach { kind ->
                assertEquals(code, TranslationFailureCode.fromProcessingFailure(ProcessingFailure(stage, kind)))
            }
        }
        assertEquals(TranslationFailureCode.UNKNOWN, TranslationFailureCode.fromProcessingFailure(null))
    }
}
