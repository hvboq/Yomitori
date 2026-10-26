package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import mihon.translation.NativeDiagnosticIdleGuard
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.service.TranslationPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Scripted image responses and injected processing only; no Activity, socket, model or server. */
internal class OnlineSelectedPageFixture private constructor(val root: File, target: Context) {
    val context: Context = object : ContextWrapper(target) {
        private val privateCache = File(root, "cache").apply { check(mkdir()) }
        override fun getCacheDir(): File = privateCache
    }
    private val originalPreferences = preferenceSnapshot(target)
    private val loaders = mutableListOf<OnlineTranslatedPageLoader>()
    private val imageCaches = mutableListOf<ChapterCache>()
    private val clients = mutableListOf<OkHttpClient>()
    private val chapters = mutableListOf<ReaderChapter>()

    class Session(
        val loader: OnlineTranslatedPageLoader,
        val pages: List<ReaderPage>,
        val imageRequests: CopyOnWriteArrayList<Int>,
    )

    suspend fun session(
        count: Int = 12,
        limits: OnlinePageCache.Limits = loaderLimits(),
        translate: suspend (Int, OnlineTranslatedPageLoader.StoredPage) -> PageTranslator.SinglePageResult,
    ): Session {
        val requested = CopyOnWriteArrayList<Int>()
        val nonce = UUID.randomUUID().toString()
        val scriptedClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "online-selected.invalid")
            val index = request.url.pathSegments.last().removeSuffix(".png").toInt()
            check(index in 0 until count)
            requested += index
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("neutral")
                .body(original(index).toResponseBody("image/png".toMediaType())).build()
        }.build()
        clients += scriptedClient
        val source = object : HttpSource() {
            override val id = 9_800_001L
            override val name = "Neutral selected page $nonce"
            override val lang = "ja"
            override val supportsLatest = false
            override val baseUrl = "https://online-selected.invalid"
            override val client = scriptedClient
            override fun headersBuilder(): Headers.Builder = Headers.Builder()
            override suspend fun getPageList(chapter: SChapter): List<Page> = (0 until count).map { index ->
                Page(index, "/logical/$index", "$baseUrl/$nonce/$index.png")
            }
        }
        val manga = Manga.create().copy(
            id = 9_800_002L,
            source = source.id,
            title = "Neutral selected page",
            url = "/neutral/$nonce",
        )
        val chapter = ReaderChapter(
            Chapter.create().copy(
                id = 9_800_003L,
                mangaId = manga.id,
                url = "/chapter/$nonce",
                name = "Neutral chapter",
            ),
        )
        chapters += chapter
        val imageCache = ChapterCache(context, Json)
        imageCaches += imageCache
        val delegate = HttpPageLoader(chapter, source, imageCache)
        val pages = mutableListOf<ReaderPage>()
        val loader = OnlineTranslatedPageLoader(
            delegate,
            chapter,
            manga,
            context,
            OnlinePageCache(context, limits),
        ) { stored ->
            val index = pages.single { it.uri == stored.pageFile.uri }.index
            translate(index, stored)
        }
        loaders += loader
        pages += loader.getPages()
        chapter.state = ReaderChapter.State.Loaded(pages)
        chapter.pageLoader = loader
        return Session(loader, pages, requested)
    }

    /** Real persistence with scripted pixels/progress; this never calls an inference entry point. */
    suspend fun complete(stored: OnlineTranslatedPageLoader.StoredPage, index: Int) {
        val directory = File(checkNotNull(stored.chapterDir.uri.path))
        val bytes = File(directory, "source.bin").readBytes()
        val original = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val outputBytes = translated(index)
        val output = checkNotNull(BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.size))
        val mask = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val progress = progress(pending = false)
        val translator = PageTranslator(context)
        val field = PageTranslator::class.java.getDeclaredField("translationPreferences").apply { isAccessible = true }
        val previous = field.get(translator)
        field.set(translator, TranslationPreferences(InMemoryPreferenceStore()).apply { segThreshold.set("0.5") })
        try {
            assertTrue(
                translator.persistLivePage(
                    stored.chapterDir,
                    stored.pageFile,
                    original,
                    output,
                    PageAnalysis(mask, progress.toTextRegions(), progress),
                    "boxfill",
                ),
            )
        } finally {
            field.set(translator, previous)
            original.recycle()
            output.recycle()
            mask.recycle()
        }
    }

    suspend fun close(target: Context, success: Boolean) {
        for (loader in loaders) loader.stopAndJoin()
        // The tests do not assess page-list caching during recycle. Prevent its unowned global
        // cleanup coroutine from recreating this UUID after the fixture has removed it.
        chapters.forEach { it.state = ReaderChapter.State.Wait }
        loaders.forEach(PageLoader::recycle)
        clients.forEach { it.dispatcher.cancelAll() }
        imageCaches.forEach {
            val field = ChapterCache::class.java.getDeclaredField("diskCache").apply { isAccessible = true }
            (field.get(it) as DiskLruCache).close()
        }
        clients.forEach {
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        assertEquals(originalPreferences, preferenceSnapshot(target))
        NativeDiagnosticIdleGuard.requireIdle(target)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "ONLINE_SELECTED_FIXTURE success=$success native=0 sockets=0 server=0\n")
                if (!success) putString("onlineSelectedFailedRoot", root.path)
            },
        )
        if (success) {
            check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
            check(root.name.startsWith("online-selected-"))
            root.walkTopDown().forEach { check(it.canonicalFile.toPath().startsWith(root.toPath())) }
            check(root.deleteRecursively())
        }
    }

    companion object {
        const val WIDTH = 32
        const val HEIGHT = 32

        fun smallLimits(entries: Int = 8) = OnlinePageCache.Limits(
            bytes = 128 * 1024,
            entries = entries,
            sourceBytes = 4096,
            pagePixels = WIDTH.toLong() * HEIGHT,
            processingReserve = 32 * 1024,
            retainedBytes = 64 * 1024,
        )

        fun loaderLimits(entries: Int = 8) = smallLimits(entries).copy(
            bytes = 128L * 1024 * 1024,
            retainedBytes = 64 * 1024,
        )

        fun encoded(color: Int, width: Int = WIDTH, height: Int = HEIGHT): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        fun original(index: Int) = encoded(Color.rgb(240 - index % 32, 245, 250))
        fun translated(index: Int) = encoded(Color.rgb(20 + index % 32, 60, 120))

        fun progress(pending: Boolean): PageProgress = PageProgress(
            WIDTH,
            HEIGHT,
            (0..1).map { id ->
                val y = 2f + id * 14
                val unresolved = pending && id == 1
                RegionProgress(
                    id,
                    if (unresolved) RegionState.OCR_PENDING else RegionState.TRANSLATED,
                    listOf(
                        OcrLineProgress(
                            listOf(Pt(2f, y), Pt(26f, y), Pt(26f, y + 10), Pt(2f, y + 10)),
                            .9f,
                            "h",
                            if (unresolved) "" else "Neutral $id",
                        ),
                    ),
                    "h",
                    translatedText = if (unresolved) "" else "중립 $id",
                )
            },
        )

        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun settled(loader: OnlineTranslatedPageLoader) = withTimeout(5000) {
            val scopeField = loader.javaClass.getDeclaredField("scope").apply { isAccessible = true }
            val controllerField = loader.javaClass.getDeclaredField("controller").apply { isAccessible = true }
            val commandsField = loader.javaClass.getDeclaredField("commands").apply { isAccessible = true }
            val scope = scopeField.get(loader) as CoroutineScope
            val controller = controllerField.get(loader) as Job
            val commands = commandsField.get(loader) as Channel<*>
            // Observe drained commands and completed owned children, rather than relying only
            // on a timeout. This helper does not advance state or request a translation.
            var stable = 0
            while (stable < 3) {
                withContext(Dispatchers.Main.immediate) { Unit }
                val others = scope.coroutineContext[Job]!!.children.filter { it !== controller }.toList()
                stable = if (commands.isEmpty && others.none { it.isActive }) stable + 1 else 0
                delay(20)
            }
        }

        suspend fun run(block: suspend (OnlineSelectedPageFixture) -> Unit) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            check(target.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit required" }
            NativeDiagnosticIdleGuard.requireIdle(target)
            val root = File(target.cacheDir.canonicalFile, "online-selected-${UUID.randomUUID()}")
            check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile && root.mkdir())
            val fixture = OnlineSelectedPageFixture(root, target)
            var success = false
            try {
                block(fixture)
                success = true
            } finally {
                withContext(NonCancellable) { fixture.close(target, success) }
            }
        }

        private fun preferenceSnapshot(context: Context): Map<String, String> {
            val directory = File(context.applicationInfo.dataDir, "shared_prefs")
            return directory.listFiles().orEmpty().associate { file ->
                check(file.isFile && file.length() <= 8 * 1024 * 1024)
                file.name to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        }
    }
}
