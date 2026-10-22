// Adapted from qwopqwop200/Aidoku native Hitomi adapter (821a612f).
// Copyright 2025 Aidoku community source contributors. SPDX-License-Identifier: MIT
package mihon.source.hitomi

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.time.LocalDateTime
import java.time.ZoneOffset

class HitomiSource(private val networkHelper: NetworkHelper) : HttpSource(), ConfigurableSource {
    override val name = "Hitomi"
    override val lang = "all"
    override val baseUrl = "https://hitomi.la"
    override val supportsLatest = true

    // Deliberately independent of external extension identities and display-name changes.
    override val id = ID
    private val directClient by lazy {
        networkHelper.client.newBuilder()
            .dns(HitomiDohDns.forSource(networkHelper.client.dns))
            .addInterceptor(HitomiImageInterceptor())
            .build()
    }
    private val bypassClient by lazy {
        directClient.newBuilder().connectionPool(ConnectionPool()).proxy(HitomiBypassProxy.proxy).build()
    }
    internal val dpiBypassEnabled get() = getSourcePreferences().getBoolean(PREF_DPI_BYPASS, true)
    override val client: OkHttpClient get() = if (dpiBypassEnabled) bypassClient else directClient

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_DPI_BYPASS
                title = screen.context.stringResource(MR.strings.hitomi_dpi_bypass)
                summary = screen.context.stringResource(MR.strings.hitomi_dpi_bypass_summary)
                setDefaultValue(true)
            },
        )
    }
    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", networkHelper.defaultUserAgentProvider())
        .add("Referer", "$baseUrl/")

    private val directApi by lazy { HitomiClient(directClient) { headers } }
    private val bypassApi by lazy { HitomiClient(bypassClient) { headers } }
    private val api get() = if (dpiBypassEnabled) bypassApi else directApi
    private val routingLock = Mutex()
    private var cachedRouting: Pair<Long, HitomiProtocol.Routing>? = null

    override suspend fun getPopularManga(page: Int): MangasPage = listing("popular/week-all.nozomi", page)
    override suspend fun getLatestUpdates(page: Int): MangasPage = listing("index-all.nozomi", page)

    suspend fun getSearchSuggestions(query: String): List<HitomiSearchSuggestion> = api.searchSuggestions(query)

    private suspend fun listing(path: String, page: Int): MangasPage {
        val (ids, hasNext) = api.page(path, page)
        return MangasPage(mangas(ids), hasNext)
    }

    override fun getFilterList() = FilterList(
        LanguageFilter(),
        Filter.Header("Search: words, gallery ID/URL, artist:name, tag:name, -tag:name"),
    )

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        require(page > 0)
        galleryId(query.trim())?.let { id ->
            return if (page ==
                1
            ) {
                MangasPage(listOf(toManga(api.gallery(id))), false)
            } else {
                MangasPage(emptyList(), false)
            }
        }
        val language = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.language ?: "all"
        val (ids, hasNext) = if (query.isBlank()) {
            api.page(
                "index-$language.nozomi",
                page,
            )
        } else {
            api.search(query, language, page)
        }
        return MangasPage(mangas(ids), hasNext)
    }

    private suspend fun mangas(ids: List<Long>): List<SManga> = coroutineScope {
        // Preserve index order while bounding metadata requests. Network failures stay visible.
        ids.chunked(4).flatMap { batch -> batch.map { id -> async { toManga(api.gallery(id)) } }.awaitAll() }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val gallery = api.gallery(requireNotNull(galleryId(manga.url)))
        val updated = if (fetchDetails) toManga(gallery) else manga
        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    url = "/reader/${gallery.text("id")}.html"
                    name = gallery.text("title").ifBlank { "Gallery" }
                    chapter_number = 1f
                    date_upload = runCatching {
                        LocalDateTime.parse(
                            gallery.text("date").take(19).replace(' ', 'T'),
                        ).toInstant(ZoneOffset.UTC).toEpochMilli()
                    }.getOrDefault(0L)
                },
            )
        } else {
            chapters
        }
        return SMangaUpdate(updated, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val gallery = api.gallery(requireNotNull(galleryId(chapter.url)))
        return gallery.getValue("files").jsonArray.mapIndexed { index, item ->
            val file = item.jsonObject
            val extension = if (file.text("haswebp") == "1" && file.text("hasavif") != "1") "webp" else "avif"
            // Stable hash descriptor; CDN routing is resolved when the image is requested.
            Page(index, "${file.text("hash")}.$extension")
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        val route = routingLock.withLock {
            val now = System.nanoTime()
            cachedRouting?.takeIf { now - it.first in 0 until 60_000_000_000L }?.second ?: api.routing().also {
                cachedRouting = now to it
            }
        }
        return route.imageUrl(page.url.substringBeforeLast('.'), page.url.substringAfterLast('.'))
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${requireNotNull(galleryId(manga.url))}.html"
    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    private fun toManga(gallery: JsonObject): SManga = SManga.create().apply {
        val galleryId = gallery.text("id").toLong().also { require(it > 0) }
        url = "/g/$galleryId"
        title = gallery.text("japanese_title").ifBlank { gallery.text("title") }
        artist = gallery.names("artists", "artist").joinToString(", ")
        author = (gallery.names("groups", "group") + gallery.names("artists", "artist")).distinct().joinToString(", ")
        genre = gallery.names("tags", "tag").joinToString(", ")
        val files = gallery.getValue("files").jsonArray
        description =
            "${gallery.text(
                "title",
            )}\nType: ${gallery.text("type")}\nLanguage: ${gallery.text("language")}\nPages: ${files.size}"
        val hash = files.firstOrNull()?.jsonObject?.text("hash")
        if (hash != null && hash.matches(Regex("[a-fA-F0-9]{3,128}"))) {
            // Keep the stored cover key stable. The image interceptor resolves atn/btn using current gg.js,
            // including covers already saved by older versions of the app.
            thumbnail_url =
                "https://atn.gold-usergeneratedcontent.net/avifbigtn/${hash.takeLast(
                    1,
                )}/${hash.takeLast(3).take(2)}/$hash.avif"
        }
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    private class LanguageFilter : Filter.Select<String>(
        "Language",
        arrayOf("All", "Japanese", "Korean", "English", "Chinese"),
    ) {
        val language get() = arrayOf("all", "japanese", "korean", "english", "chinese")[state]
    }

    companion object {
        const val ID = 7064495633411907201L
        internal const val PREF_DPI_BYPASS = "hitomi_dpi_bypass"

        internal fun galleryId(input: String): Long? {
            input.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
            val path = if (input.startsWith('/')) {
                input
            } else {
                val url = input.toHttpUrlOrNull() ?: return null
                if (url.host != "hitomi.la" && !url.host.endsWith(".hitomi.la")) return null
                url.encodedPath
            }
            return Regex(
                "/(?:g|reader)/(\\d+)(?:\\.html)?/?$",
            ).find(path)?.groupValues?.get(1)?.toLongOrNull()?.takeIf {
                it >
                    0
            }
                ?: Regex("-(\\d+)\\.html$").find(path)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
        }

        private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content?.takeUnless {
            it == "null"
        }.orEmpty()
        private fun JsonObject.names(
            key: String,
            field: String,
        ) = get(key)?.takeUnless { it.toString() == "null" }?.jsonArray
            ?.map { it.jsonObject.text(field) }.orEmpty()
    }
}
