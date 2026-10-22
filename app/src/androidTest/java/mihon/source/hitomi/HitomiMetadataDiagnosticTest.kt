package mihon.source.hitomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Metadata only: does not request thumbnails or page images. Tags are not a content guarantee. */
@RunWith(AndroidJUnit4::class)
class HitomiMetadataDiagnosticTest {
    @Test
    fun findShortNonAdultTaggedCandidates() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hitomiMetadata") == "true")
        withTimeout(120000) {
            val manager = Injekt.get<SourceManager>()
            manager.isInitialized.first { it }
            val source = manager.get(HitomiSource.ID) as HitomiSource
            val query = "language:japanese tag:non-h"
            val results = source.getSearchManga(1, query, source.getFilterList())
            val rows = JSONArray()
            results.mangas.forEach { manga ->
                val pages = Regex("Pages: (\\d+)").find(manga.description.orEmpty())
                    ?.groupValues?.get(1)?.toInt()
                rows.put(
                    JSONObject().put("url", manga.url).put("title", manga.title)
                        .put("pages", pages).put("tags", manga.genre)
                        .put("nonAdultTag", manga.genre.orEmpty().split(", ").contains("non-h")),
                )
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "hitomi-metadata-diagnostic.json").writeText(
                JSONObject().put("query", query).put("results", rows)
                    .put("imagesRequested", false).put("preferencesChanged", false).toString(2),
            )
        }
    }
}
