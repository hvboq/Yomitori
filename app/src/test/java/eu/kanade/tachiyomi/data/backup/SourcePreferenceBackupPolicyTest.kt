package eu.kanade.tachiyomi.data.backup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourcePreferenceBackupPolicyTest {
    @Test
    fun `canonical source IDs remain valid without consulting installed extensions`() {
        for (id in listOf(0L, 1L, -1L, 4317264810535L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            SourcePreferenceBackupPolicy.isValidSourceKey("source_$id") shouldBe true
        }
    }

    @Test
    fun `app preference filenames and paths cannot be used as source keys`() {
        for (key in listOf(
            "app.yomitori.yakuyomi.dev_preferences",
            "app.yomitori.yakuyomi_preferences",
            "../app.yomitori.yakuyomi.dev_preferences",
            "source_1/../app.yomitori.yakuyomi.dev_preferences",
            "source_1\\..\\app.yomitori.yakuyomi.dev_preferences",
            "source_1.xml",
            "source_1\u0000",
            "SOURCE_1",
        )) {
            SourcePreferenceBackupPolicy.isValidSourceKey(key) shouldBe false
        }
    }

    @Test
    fun `noncanonical numeric keys cannot alias valid source namespaces`() {
        for (key in listOf(
            "", "source_", "source_01", "source_+1", "source_-0", "source_-01",
            "source_ 1", "source_1 ", "source_1\n", "source_１",
        )) {
            SourcePreferenceBackupPolicy.isValidSourceKey(key) shouldBe false
        }
    }

    @Test
    fun `out of range and oversized IDs are rejected`() {
        for (key in listOf(
            "source_9223372036854775808",
            "source_-9223372036854775809",
            "source_${"1".repeat(1000)}",
        )) {
            SourcePreferenceBackupPolicy.isValidSourceKey(key) shouldBe false
        }
    }
}
