package mihon.translation

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in synthetic text only; spot checks and review samples, not a general translation quality score. */
@RunWith(AndroidJUnit4::class)
class TranslationQualityLiveTest {
    @Test
    fun preservesNegationNamesAndRegionMeaningInOnePage() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("liveTranslationBase").orEmpty()
        val model = args.getString("liveTranslationModel").orEmpty()
        assumeTrue(
            "Requires liveTranslationQuality=true and explicit liveTranslationBase/liveTranslationModel",
            args.getString("liveTranslationQuality") == "true" && base.isNotBlank() && model.isNotBlank(),
        )
        val sources = listOf(
            "田中さん、明日の会議は午後3時です。",
            "私は行きません。田中さんにそう伝えてください。",
            "えっ？",
            "この鍵は兄のものです。私のではありません。",
            "田中さんには鍵を渡さないでください。",
            "分かりました。渡しません。",
        )
        val reviewNotes = listOf(
            "Tanaka is addressed politely; tomorrow's meeting is at 3 pm.",
            "The speaker will NOT go; asks someone to tell Tanaka. Keep both sentences in this region.",
            "A short surprised reaction, without borrowing dialogue from other regions.",
            "The key belongs to an older brother, NOT the speaker. Speaker gender is unknown.",
            "A polite request NOT to hand the key to Tanaka.",
            "Acknowledges the request and says they will NOT hand it over; supplied page text gives context.",
        )
        val translator = LlmTranslator(
            apiKey = "",
            cfg = TranslatorConfig(
                provider = "custom",
                model = model,
                apiBase = LlmProviders.chatUrlOf(LlmProviders.byId("custom"), base),
                fromLangName = "Japanese",
                toLangName = "Korean",
                sampleSource = "",
                sampleTarget = "",
                temperature = 0.0,
                thinking = false,
            ),
        )
        val start = SystemClock.elapsedRealtime()
        val result = withTimeout(180_000) { translator.translateDetailed(sources) }
        assertTrue("Live translation request or exact-ID validation failed", result.error == null)
        assertEquals(sources.size, result.translations.size)
        val translated = result.translations
        val korean = Regex("[가-힣]")
        val tanaka = Regex("[다타]나카")
        val negative = Regex("안|않|아니|아닙|아닌|아냐|말[아고]|마(?:세요|십시오)")
        val nameForms = listOf(0, 1, 4).map { tanaka.find(translated[it])?.value }
        val checks = linkedMapOf(
            "koreanInEveryRegion" to translated.all { korean.containsMatchIn(it) },
            "meetingTimeRetainedInFirstRegion" to Regex("3|세\\s*시").containsMatchIn(translated[0]),
            "nameRetainedAndConsistent" to (nameForms.none { it == null } && nameForms.distinct().size == 1),
            "refusalToAttendRetained" to negative.containsMatchIn(translated[1]),
            "shortReactionNotExpandedIntoDialogue" to (translated[2].length <= 24),
            "ownershipNegationRetained" to negative.containsMatchIn(translated[3]),
            "negativeRequestRetained" to negative.containsMatchIn(translated[4]),
            "negativeAcknowledgementRetained" to negative.containsMatchIn(translated[5]),
        )
        val report = JSONObject()
            .put("model", model)
            .put("apiKeyUsed", false)
            .put("elapsedMs", SystemClock.elapsedRealtime() - start)
            .put("requiresHumanSemanticReview", true)
            .put("semanticSpotChecks", JSONObject(checks))
            .put(
                "regions",
                JSONArray().apply {
                    sources.indices.forEach { index ->
                        put(
                            JSONObject().put("id", index + 1).put("source", sources[index])
                                .put("target", translated[index]).put("reviewNote", reviewNotes[index]),
                        )
                    }
                },
            )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "translation-quality-${System.currentTimeMillis()}.json")
        output.writeText(report.toString(2))
        println("TRANSLATION_QUALITY_REPORT=${output.absolutePath}")
        println(report.toString(2))
        checks.forEach { (name, passed) ->
            assertTrue("Semantic spot check failed: $name; review synthetic report", passed)
        }
    }
}
