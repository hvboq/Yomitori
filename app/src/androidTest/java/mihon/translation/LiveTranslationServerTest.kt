package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import li.joye.yakuyomi.engine.LlmModels
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.ModelSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Opt-in only: sends a synthetic Japanese fixture to the explicitly supplied server. */
@RunWith(AndroidJUnit4::class)
class LiveTranslationServerTest {
    @Test
    fun realServerTranslatesOcrAndPreservesOriginal() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("liveTranslationBase").orEmpty()
        val model = args.getString("liveTranslationModel").orEmpty()
        assumeTrue(
            "Requires explicit liveTranslationBase and liveTranslationModel",
            base.isNotBlank() && model.isNotBlank(),
        )
        val keepSettings = args.getString("configureTranslation") == "true"
        val preset = LlmProviders.byId("custom")
        val models = LlmModels.list(LlmProviders.modelsUrlOf(preset, base), ModelSource.OPENAI, "")
        assertTrue("Model discovery failed: ${LlmModels.lastError}", models.any { it.id == model })

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Injekt.get<TranslationPreferences>()
        val restore = mutableListOf<Pair<Boolean, () -> Unit>>()
        fun <T> set(preference: Preference<T>, value: T, permanent: Boolean = false) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restore += permanent to { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }

        var succeeded = false
        try {
            set(prefs.apiKeyFor("custom"), "", permanent = true)
            set(prefs.apiBase, base, permanent = true)
            set(prefs.provider, "custom", permanent = true)
            set(prefs.model, model, permanent = true)
            set(prefs.sourceLangName, "Japanese", permanent = true)
            set(prefs.targetLangName, "Korean", permanent = true)
            set(prefs.orientation, "horizontal", permanent = true)
            set(prefs.keepMaterials, true)
            set(prefs.liveTranslate, false)
            set(prefs.nightReadEnabled, false)

            val output = File(context.getExternalFilesDir(null), "live-server-${System.currentTimeMillis()}")
            assertTrue(output.mkdirs())
            val page = File(output, "001.png")
            val original = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
            try {
                Canvas(original).apply {
                    drawColor(Color.LTGRAY)
                    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                    drawRoundRect(50f, 100f, 1150f, 800f, 100f, 100f, ink)
                    ink.color = Color.BLACK
                    ink.textSize = 72f
                    drawText("こんにちは。", 170f, 350f, ink)
                    drawText("今日もよろしくお願いします。", 100f, 490f, ink)
                }
                page.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                original.recycle()
            }
            val originalBytes = page.readBytes()
            val chapter = requireNotNull(UniFile.fromFile(output))
            val translator = PageTranslator(context)
            val start = SystemClock.elapsedRealtime()
            val translated = withTimeout(300_000) {
                translator.translateChapter(chapter, "auto_whole", nightMaterials = false)
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            assertEquals("Live OCR/translation/render/save failed; inspect app diagnostics", 1, translated)
            assertTrue(translator.isPageTranslated(chapter, "001.png"))
            assertArrayEquals(originalBytes, File(output, ".yakuyomi/001.orig.png").readBytes())
            assertFalse(originalBytes.contentEquals(page.readBytes()))
            val materials = Json { ignoreUnknownKeys = true }.decodeFromString<PageMaterials>(
                File(output, ".yakuyomi/001.json").readText(),
            )
            val regions = materials.regions.filterNot { it.preserveOriginal }
            assertTrue(regions.isNotEmpty())
            assertTrue(regions.any { it.source.contains("こんにちは") })
            assertTrue(regions.all { Regex("[가-힣]").containsMatchIn(it.target) })
            val report = JSONObject()
                .put("base", base)
                .put("model", model)
                .put("apiKeyUsed", false)
                .put("elapsedMs", elapsed)
                .put("originalPreserved", true)
                .put("translatedPages", translated)
                .put(
                    "regions",
                    JSONArray().apply {
                        regions.forEach { put(JSONObject().put("source", it.source).put("target", it.target)) }
                    },
                )
            File(output, "result.json").writeText(report.toString(2))
            println("LIVE_TRANSLATION_RESULT=${output.absolutePath}")
            println(report.toString(2))
            succeeded = true
        } finally {
            try {
                Injekt.get<TranslationEngineService>().shutdown()
            } finally {
                restore.asReversed().forEach { (permanent, action) ->
                    if (!(permanent && keepSettings && succeeded)) action()
                }
            }
        }
    }
}
