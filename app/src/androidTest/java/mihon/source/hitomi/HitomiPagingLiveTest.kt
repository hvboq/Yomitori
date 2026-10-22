package mihon.source.hitomi

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Live metadata only. Opt in with -Pandroid.testInstrumentationRunnerArguments.hitomiPaging=true. */
@RunWith(AndroidJUnit4::class)
class HitomiPagingLiveTest {
    @Test
    fun popularLatestAndSearchLoadTwoPagesUsingProductionTransport() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hitomiPaging") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reportFile = File(requireNotNull(context.getExternalFilesDir(null)), "hitomi-paging-result.json")
        val pages = JSONArray()
        val report = JSONObject().put("pages", pages).put("passed", false)
        val started = SystemClock.elapsedRealtime()
        try {
            withTimeout(180_000) {
                val manager = Injekt.get<SourceManager>()
                manager.isInitialized.first { it }
                val source = manager.get(HitomiSource.ID) as HitomiSource
                val preferences = source.getSourcePreferences()
                val wasSet = preferences.contains(HitomiSource.PREF_DPI_BYPASS)
                val previous = source.dpiBypassEnabled
                try {
                    assertTrue(preferences.edit().putBoolean(HitomiSource.PREF_DPI_BYPASS, true).commit())
                    source.client.connectionPool.evictAll()
                    for (kind in listOf("popular", "latest", "search")) {
                        for (page in 1..2) {
                            val pageStarted = SystemClock.elapsedRealtime()
                            val result = when (kind) {
                                "popular" -> source.getPopularManga(page)
                                "latest" -> source.getLatestUpdates(page)
                                else -> source.getSearchManga(
                                    page,
                                    "type:manga language:japanese",
                                    source.getFilterList(),
                                )
                            }
                            val count = result.mangas.size
                            pages.put(
                                JSONObject().put("kind", kind).put("page", page).put("count", count)
                                    .put("elapsedMs", SystemClock.elapsedRealtime() - pageStarted)
                                    .put("hasNextPage", result.hasNextPage),
                            )
                            val label = "$kind page $page"
                            assertTrue("$label must be nonempty and bounded", count in 1..HitomiProtocol.PAGE_SIZE)
                            val ids = result.mangas.map { manga ->
                                assertTrue("$label metadata must have a title", manga.title.isNotBlank())
                                assertTrue("$label must use a canonical gallery URL", manga.url.matches(GALLERY_URL))
                                requireNotNull(HitomiSource.galleryId(manga.url)) {
                                    "$label metadata must have a positive gallery ID"
                                }
                            }
                            assertEquals("$label IDs must be unique within the page", count, ids.distinct().size)
                            if (count == HitomiProtocol.PAGE_SIZE) {
                                // These broad live indexes contain far more than two pages. For ranged
                                // popular/latest lists a full page is also the source's continuation signal.
                                assertTrue("$label full page must allow continuation", result.hasNextPage)
                            } else {
                                assertFalse("$label partial page must end pagination", result.hasNextPage)
                            }
                            // Live indexes may move between requests; cross-page uniqueness is not an invariant.
                        }
                    }
                    report.put("passed", true)
                } finally {
                    val restored = preferences.edit().apply {
                        if (wasSet) {
                            putBoolean(HitomiSource.PREF_DPI_BYPASS, previous)
                        } else {
                            remove(HitomiSource.PREF_DPI_BYPASS)
                        }
                    }.commit()
                    assertTrue("DPI preference must be restored", restored)
                    assertEquals(wasSet, preferences.contains(HitomiSource.PREF_DPI_BYPASS))
                    assertEquals(previous, source.dpiBypassEnabled)
                }
            }
        } catch (failure: Throwable) {
            // Never store gallery titles, IDs, remote payloads, URLs or image data in the report.
            report.put("passed", false).put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            reportFile.writeText(report.toString(2))
        }
    }

    companion object {
        private val GALLERY_URL = Regex("/g/[1-9][0-9]*")
    }
}
