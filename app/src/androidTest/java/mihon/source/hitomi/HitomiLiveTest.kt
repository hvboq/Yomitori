package mihon.source.hitomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.decoder.ImageDecoder
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Opt in with -Pandroid.testInstrumentationRunnerArguments.hitomiLive=true. */
@RunWith(AndroidJUnit4::class)
class HitomiLiveTest {
    @Test
    fun registeredSourceSearchDetailsAndPageImages() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hitomiLive") == "true")
        withTimeout(120000) {
            val manager = Injekt.get<SourceManager>()
            manager.isInitialized.first { it }
            val source = manager.get(HitomiSource.ID) as HitomiSource
            val preferences = source.getSourcePreferences()
            val wasSet = preferences.contains(HitomiSource.PREF_DPI_BYPASS)
            val previous = source.dpiBypassEnabled
            preferences.edit().putBoolean(HitomiSource.PREF_DPI_BYPASS, true).commit()
            try {
                source.client.connectionPool.evictAll()
                val fragmentedBefore = HitomiBypassProxy.fragmentedConnections
                verifyPages(source)
                val fragmented = HitomiBypassProxy.fragmentedConnections - fragmentedBefore
                assertTrue("Live HTTPS must use the TLS ClientHello splitter", fragmented > 0)
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                File(context.getExternalFilesDir(null), "hitomi-dpi-result.json").writeText(
                    JSONObject().put("tlsFragmentedConnections", fragmented)
                        .put("searchDetailsAndThreeImagesDecoded", true)
                        .put("certificateValidationEnabled", true).toString(2),
                )
            } finally {
                preferences.edit().apply {
                    if (wasSet) {
                        putBoolean(
                            HitomiSource.PREF_DPI_BYPASS,
                            previous,
                        )
                    } else {
                        remove(HitomiSource.PREF_DPI_BYPASS)
                    }
                }.commit()
            }
        }
    }

    private suspend fun verifyPages(source: HitomiSource) {
        val results = source.getSearchManga(1, "type:manga language:japanese", source.getFilterList())
        assertTrue(results.mangas.isNotEmpty())
        assertTrue(results.mangas.all { it.title.isNotBlank() && it.url.startsWith("/g/") })
        val details = source.getMangaUpdate(results.mangas.first(), emptyList(), true, true)
        assertEquals(1, details.chapters.size)
        val pages = source.getPageList(details.chapters.single())
        assertTrue(pages.isNotEmpty())
        assertEquals(pages.indices.toList(), pages.map { it.index })
        for (index in setOf(0, pages.size / 2, pages.lastIndex)) {
            val page = pages[index]
            page.imageUrl = source.getImageUrl(page)
            source.getImage(page).use { response ->
                assertTrue("Image HTTP ${response.code}", response.isSuccessful)
                response.body.byteStream().use { input ->
                    val decoder = requireNotNull(ImageDecoder.newInstance(input, false, null))
                    try {
                        assertTrue(decoder.width > 0 && decoder.height > 0)
                        val bitmap = requireNotNull(decoder.decode(sampleSize = 1))
                        assertTrue(bitmap.width > 0 && bitmap.height > 0)
                        bitmap.recycle()
                    } finally {
                        decoder.recycle()
                    }
                }
            }
        }
    }
}
