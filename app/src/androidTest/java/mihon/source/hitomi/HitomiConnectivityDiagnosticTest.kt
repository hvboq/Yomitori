package mihon.source.hitomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Opt in with -e hitomiDiagnostic true. Outputs transport facts only; never saves fetched artwork. */
@RunWith(AndroidJUnit4::class)
class HitomiConnectivityDiagnosticTest {
    @Test
    fun suggestionsCoversAndReaderImagesUseProductionTransport() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hitomiDiagnostic") == "true")
        val started = System.nanoTime()
        val stages = JSONArray()
        val failed = mutableListOf<String>()
        val result = JSONObject().put("stages", stages).put("imageBytesRetained", false)
        val source = HitomiSource(Injekt.get<NetworkHelper>())
        val preferences = source.getSourcePreferences()
        val wasSet = preferences.contains(HitomiSource.PREF_DPI_BYPASS)
        val previous = source.dpiBypassEnabled
        val fragmentedBefore = HitomiBypassProxy.fragmentedConnections
        preferences.edit().putBoolean(HitomiSource.PREF_DPI_BYPASS, true).commit()

        suspend fun <T> stage(name: String, host: String, block: suspend (JSONObject) -> T): T? {
            val row = JSONObject().put("stage", name).put("host", host)
            stages.put(row)
            val before = System.nanoTime()
            val fragments = HitomiBypassProxy.fragmentedConnections
            return try {
                block(row).also { row.put("status", "passed") }
            } catch (error: CancellationException) {
                row.put("status", "cancelled").put("errorType", error.javaClass.simpleName)
                throw error
            } catch (error: Throwable) {
                failed += name
                row.put("status", "failed").put("errorType", error.javaClass.simpleName)
                Regex("HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { row.put("httpStatus", it) }
                null
            } finally {
                row.put("durationMs", (System.nanoTime() - before) / 1_000_000)
                row.put("tlsFragmentedConnections", HitomiBypassProxy.fragmentedConnections - fragments)
            }
        }

        suspend fun suggestionStage(name: String, query: String, requireJapanese: Boolean) {
            stage(name, "tagindex.hitomi.la") { row ->
                val suggestions = try {
                    withTimeout(30_000) { source.getSearchSuggestions(query) }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw java.io.IOException("Suggestion stage timed out")
                }
                row.put("resultCount", suggestions.size)
                assertTrue("Suggestion list must be nonempty and bounded", suggestions.size in 1..20)
                if (requireJapanese) {
                    val found = suggestions.any { it.value == "language:japanese" }
                    row.put("japaneseLanguagePresent", found)
                    assertTrue("Japanese language suggestion missing", found)
                } else {
                    assertTrue(
                        "Suggestion index returned another namespace",
                        suggestions.all {
                            it.value.startsWith("${query.substringBefore(':')}:")
                        },
                    )
                }
            }
        }

        try {
            withTimeout(240_000) {
                source.client.connectionPool.evictAll()
                suggestionStage("suggestionsGlobal", "jap", true)
                suggestionStage("suggestionsLanguage", "language:ja", true)
                suggestionStage("suggestionsArtist", "artist:", false)
                for (namespace in listOf("group", "series", "character", "tag", "female", "male", "type")) {
                    suggestionStage("suggestions-$namespace", "$namespace:", false)
                }

                val listing = stage("popularListing", "ltn.gold-usergeneratedcontent.net") { row ->
                    source.getPopularManga(1).also {
                        row.put("resultCount", it.mangas.size)
                        assertTrue("Popular list is empty", it.mangas.isNotEmpty())
                    }
                }
                val api = HitomiClient(source.client) { source.headers }
                val routing = stage("routing", "ltn.gold-usergeneratedcontent.net") { api.routing() }
                if (listing != null) {
                    val covers = listing.mangas.mapNotNull { it.thumbnail_url }.distinct()
                    val grouped = covers.groupBy { url ->
                        val hash = url.toHttpUrl().pathSegments.last().substringBeforeLast('.')
                        routing?.thumbnailUrl(hash)?.toHttpUrl()?.host ?: url.toHttpUrl().host
                    }
                    val selected = (grouped.values.map { it.first() } + covers).distinct().take(2)
                    selected.firstOrNull { url ->
                        val hash = url.toHttpUrl().pathSegments.last().substringBeforeLast('.')
                        routing?.thumbnailUrl(hash)?.toHttpUrl()?.host != url.toHttpUrl().host
                    }?.let { legacyUrl ->
                        stage("legacyCoverRoute", legacyUrl.toHttpUrl().host) { row ->
                            // Isolate the old fixed-host behavior while retaining exactly the same DPI transport.
                            val legacyClient = source.client.newBuilder().apply {
                                interceptors().removeAll { it is HitomiImageInterceptor }
                            }.build()
                            val request = Request.Builder().url(legacyUrl).headers(source.headers).build()
                            withContext(Dispatchers.IO) {
                                legacyClient.newCall(request).await().use { response ->
                                    row.put("httpStatus", response.code)
                                }
                            }
                        }
                    }
                    stage("coverSelection", "gold-usergeneratedcontent.net") { row ->
                        row.put("resultCount", selected.size)
                        assertTrue("Two cover samples required", selected.size == 2)
                    }
                    selected.forEachIndexed { index, url ->
                        stage("cover${index + 1}", url.toHttpUrl().host) { row ->
                            val request = Request.Builder().url(url).headers(source.headers).build()
                            withContext(Dispatchers.IO) {
                                source.client.newCall(request).await().use { decode(it, row) }
                            }
                        }
                    }
                    val details = stage("details", "ltn.gold-usergeneratedcontent.net") {
                        source.getMangaUpdate(listing.mangas.first(), emptyList(), true, true).also {
                            assertTrue("Expected one gallery chapter", it.chapters.size == 1)
                        }
                    }
                    val pages = details?.let { update ->
                        stage("pageList", "ltn.gold-usergeneratedcontent.net") { row ->
                            source.getPageList(update.chapters.single()).also {
                                row.put("resultCount", it.size)
                                assertTrue("Page list is empty", it.isNotEmpty())
                            }
                        }
                    }
                    if (pages != null) {
                        val avif = pages.filter { it.url.endsWith(".avif") }
                        stage("avifSelection", "gold-usergeneratedcontent.net") { row ->
                            row.put("resultCount", avif.size)
                            assertTrue("Three AVIF samples required", avif.size >= 3)
                        }
                        if (avif.isNotEmpty()) {
                            listOf(avif.first(), avif[avif.size / 2], avif.last()).distinctBy { it.index }
                                .forEachIndexed { index, page ->
                                    stage("readerAvif${index + 1}", "gold-usergeneratedcontent.net") { row ->
                                        page.imageUrl = source.getImageUrl(page)
                                        row.put("requestedHost", page.imageUrl!!.toHttpUrl().host)
                                        withContext(Dispatchers.IO) {
                                            source.getImage(page).use { decode(it, row) }
                                        }
                                    }
                                }
                        }
                        val metadata = stage("webpAvailability", "ltn.gold-usergeneratedcontent.net") { row ->
                            val id = requireNotNull(HitomiSource.galleryId(listing.mangas.first().url))
                            api.gallery(id).getValue("files").jsonArray.first().jsonObject.also {
                                row.put("available", it["haswebp"]?.jsonPrimitive?.content == "1")
                            }
                        }
                        if (metadata?.get("haswebp")?.jsonPrimitive?.content == "1" && routing != null) {
                            stage("readerWebp", "gold-usergeneratedcontent.net") { row ->
                                val hash = requireNotNull(metadata).getValue("hash").jsonPrimitive.content
                                val page = Page(0, "$hash.webp").apply { imageUrl = routing.imageUrl(hash, "webp") }
                                row.put("requestedHost", page.imageUrl!!.toHttpUrl().host)
                                withContext(Dispatchers.IO) {
                                    source.getImage(page).use { decode(it, row) }
                                }
                            }
                        } else {
                            stages.put(JSONObject().put("stage", "readerWebp").put("status", "skippedUnavailable"))
                        }
                    }
                }
                val fragmented = HitomiBypassProxy.fragmentedConnections - fragmentedBefore
                assertTrue("Production transport did not fragment TLS", fragmented > 0)
                result.put("completed", true)
                assertTrue("Failed diagnostic stages: ${failed.joinToString()}", failed.isEmpty())
                result.put("passed", true)
            }
        } catch (error: Throwable) {
            result.put("passed", false).put("errorType", error.javaClass.simpleName)
            throw error
        } finally {
            preferences.edit().apply {
                if (wasSet) putBoolean(HitomiSource.PREF_DPI_BYPASS, previous) else remove(HitomiSource.PREF_DPI_BYPASS)
            }.commit()
            result.put("durationMs", (System.nanoTime() - started) / 1_000_000)
                .put("tlsFragmentedConnections", HitomiBypassProxy.fragmentedConnections - fragmentedBefore)
                .put(
                    "bypassPreferenceRestored",
                    source.dpiBypassEnabled == previous &&
                        preferences.contains(HitomiSource.PREF_DPI_BYPASS) == wasSet,
                )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "hitomi-connectivity-diagnostic.json").writeText(result.toString(2))
        }
    }

    private fun decode(response: Response, row: JSONObject) {
        row.put("host", response.request.url.host).put("httpStatus", response.code)
        assertTrue("Image HTTP ${response.code}", response.isSuccessful)
        response.body.byteStream().use { input ->
            val decoder =
                requireNotNull(ImageDecoder.newInstance(input, false, null)) { "Image decoder initialization failed" }
            try {
                row.put("width", decoder.width).put("height", decoder.height)
                assertTrue("Image dimensions invalid", decoder.width > 0 && decoder.height > 0)
                val bitmap = requireNotNull(decoder.decode(sampleSize = 1)) { "Image decode failed" }
                try {
                    assertTrue("Decoded bitmap is empty", bitmap.width > 0 && bitmap.height > 0)
                    row.put("decodedWidth", bitmap.width).put("decodedHeight", bitmap.height)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                decoder.recycle()
            }
        }
    }
}
