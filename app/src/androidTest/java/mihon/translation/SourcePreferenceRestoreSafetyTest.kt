package mihon.translation

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcePreferenceRestoreSafetyTest {
    @Test
    fun sourceRestoreCannotWriteGlobalEndpointOrCredentialsAndStillRestoresUninstalledSource() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val global = PreferenceManager.getDefaultSharedPreferences(context)
        val sourceKey = "source_${Long.MIN_VALUE + 17}"
        val source = context.getSharedPreferences(sourceKey, Context.MODE_PRIVATE)
        val endpointKey = "translation_api_base"
        val credentialKey = "translation_api_key_enc__custom"
        val markerKey = "backup_namespace_test_marker"
        val countKey = "backup_namespace_test_count"
        val globalSnapshot = global.all.filterKeys { it == endpointKey || it == credentialKey }
        val sourceSnapshot = source.all.filterKeys { it == markerKey || it == countKey }
        try {
            assertTrue(
                global.edit().putString(endpointKey, "https://trusted.invalid/v1")
                    .putString(credentialKey, "dummy-test-ciphertext").commit(),
            )
            assertTrue(source.edit().remove(markerKey).remove(countKey).commit())
            PreferenceRestorer(context).restoreSource(
                listOf(
                    BackupSourcePreferences(
                        "${context.packageName}_preferences",
                        listOf(
                            BackupPreference(endpointKey, StringPreferenceValue("https://untrusted.invalid/v1")),
                            BackupPreference(credentialKey, StringPreferenceValue("dummy-imported-ciphertext")),
                        ),
                    ),
                    BackupSourcePreferences(
                        sourceKey,
                        listOf(
                            BackupPreference(markerKey, StringPreferenceValue("restored")),
                            BackupPreference(countKey, IntPreferenceValue(7)),
                        ),
                    ),
                ),
            )
            // Boolean assertions keep credential values out of instrumentation failure output.
            assertTrue(
                "Source backup changed the global endpoint",
                global.getString(endpointKey, null) == "https://trusted.invalid/v1",
            )
            assertTrue(
                "Source backup changed global credentials",
                global.getString(credentialKey, null) == "dummy-test-ciphertext",
            )
            assertEquals("restored", source.getString(markerKey, null))
            assertEquals(7, source.getInt(countKey, -1))
        } finally {
            try {
                restoreSnapshot(global, setOf(endpointKey, credentialKey), globalSnapshot)
            } finally {
                restoreSnapshot(source, setOf(markerKey, countKey), sourceSnapshot)
            }
        }
    }

    private fun restoreSnapshot(preferences: SharedPreferences, keys: Set<String>, snapshot: Map<String, *>) {
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        snapshot.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue("Failed to restore original test preferences", editor.commit())
    }
}
