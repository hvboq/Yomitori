package mihon.translation

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.LlmModels
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.ModelSource
import li.joye.yakuyomi.engine.Translator
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit server opt-in. Native OCR uses a neutral fixture with one forced first-pass blank. */
@RunWith(AndroidJUnit4::class)
class LivePartialTranslationServerTest {
    @Test
    fun realServerResumesOnlyNewGroupsAndKeepsSuccessfulTargetsAndOriginal() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val base = arguments.getString("liveTranslationBase").orEmpty()
        val model = arguments.getString("liveTranslationModel").orEmpty()
        val exportArgument = arguments.getString("exportReleasePartial")
        require(exportArgument == null || exportArgument == "true" || exportArgument == "false") {
            "exportReleasePartial must be true or false"
        }
        val exportPending = exportArgument == "true"
        if (exportPending) {
            assertTrue("Export requires explicit server and model", base.isNotBlank() && model.isNotBlank())
        } else {
            assumeTrue("Requires explicit server and model", base.isNotBlank() && model.isNotBlank())
        }
        val preset = LlmProviders.byId("custom")
        val models = LlmModels.list(LlmProviders.modelsUrlOf(preset, base), ModelSource.OPENAI, "")
        assertTrue("The selected model must be discoverable", models.any { it.id == model })
        val delegate = LlmTranslator(
            "",
            TranslatorConfig(
                provider = "custom",
                apiBase = LlmProviders.chatUrlOf(preset, base),
                model = model,
                fromLangName = "Japanese",
                toLangName = "Korean",
                sampleSource = "",
                sampleTarget = "",
            ),
        )
        val batches = mutableListOf<List<String>>()
        val requestMs = mutableListOf<Long>()
        val probe = object : Translator {
            override val translationContext get() = delegate.translationContext
            override suspend fun translate(queries: List<String>): List<String> {
                batches += queries.toList()
                val start = SystemClock.elapsedRealtime()
                return delegate.translate(queries).also { targets ->
                    requestMs += SystemClock.elapsedRealtime() - start
                    assertEquals(queries.size, targets.size)
                    assertTrue(
                        "Every fixture region should have Korean output",
                        targets.all {
                            Regex("[가-힣]").containsMatchIn(it)
                        },
                    )
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reportFile = File(context.getExternalFilesDir(null), "live-partial-context.json")
        reportFile.writeText(JSONObject().put("passed", false).put("nativeOcr", true).toString())
        val start = SystemClock.elapsedRealtime()
        val exportedPending = PartialOcrIntegrationTest().verifyLiveStoredLifecycle(probe, exportPending) {
            batches.size
        }
        assertEquals("First partial and pending-only resume each use one logical batch", 2, batches.size)
        val report = JSONObject().put("passed", true).put("nativeOcr", true).put("externalServer", true)
            .put("forcedFirstPassBlank", true).put("actualReaderActivity", false)
            .put("logicalTranslationBatches", batches.size).put("httpRetryCountMeasured", false)
            .put("regionsPerBatch", JSONArray(batches.map { it.size }))
            .put("requestWallMs", JSONArray(requestMs)).put("totalWallMs", SystemClock.elapsedRealtime() - start)
            .put("successfulTargetsPreserved", true).put("originalBytesPreserved", true)
            .put("savedActualContextPreserved", true).put("userSettingsChanged", false)
            .put("pendingExportRequested", exportPending)
        if (exportPending) report.put("pendingExport", requireNotNull(exportedPending))
        reportFile.writeText(report.toString(2))
    }
}
