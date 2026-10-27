package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.OnlinePageCache
import eu.kanade.tachiyomi.ui.reader.loader.OnlineTranslatedPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Real ReaderActivity reopen and compositor pixels; stored scripted output, no OCR or translation request. */
@RunWith(AndroidJUnit4::class)
class OnlineSelectedPageReopenDisplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 120_000)
    fun reopeningAfterOriginalRenderedDisplaysTheStoredTranslationWithLiveOff() = runBlocking<Unit> {
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "ISOLATED_AUDIT_REQUIRED" }
        check(InstrumentationRegistry.getArguments().getString("onlineSelectedPageReopenDisplay") == "true") {
            "ONLINE_SELECTED_REOPEN_OPT_IN_REQUIRED"
        }
        val scope = OnlineReaderFlowScope(context)
        val cacheRoot = File(context.cacheDir.canonicalFile, "online-reader-pages-v1")
        check(cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 16L * 1024 * 1024)
        val cacheBefore = snapshot(cacheRoot)
        val report = JSONObject().put("terminal", false).put("passed", false)
            .put("actualReaderActivityReopen", true).put("actualHistoryListClick", false)
            .put("scriptedStoredPageCount", 1).put("nativeInferenceCalls", 0).put("translationServerCalls", 0)
        var failure: Throwable? = null
        var ownedDirectory: File? = null
        var gate: CacheGate? = null
        val processorCalls = AtomicInteger()
        try {
            scope.prepare()
            configure(scope)
            val original = encoded(false)
            val server = scope.openServer(listOf(original))
            val repository = Injekt.get<MangaRepository>()
            val manga = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = server.source.id,
                        url = "/fixture/${scope.uuid}",
                        title = "Online reader synthetic ${scope.uuid}",
                        initialized = true,
                    ),
                ),
            ).single()
            scope.recordManga(manga)
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "Fixture", chapterNumber = 1.0),
                ),
            ).single()
            scope.recordOwned(manga, chapter)
            repository.update(
                MangaUpdate(
                    manga.id,
                    viewerFlags = (
                        ReadingMode.LEFT_TO_RIGHT.flagValue or
                            ReaderOrientation.LOCKED_PORTRAIT.flagValue
                        ).toLong(),
                ),
            )
            val translator = PageTranslator(context)
            lateinit var firstLoader: OnlineTranslatedPageLoader
            lateinit var firstPage: ReaderPage
            lateinit var savedBytes: ByteArray
            scope.launchReader(ReaderActivity.newIntent(context, manga.id, chapter.id)).use { first ->
                firstPage = readyPage(first)
                firstLoader = firstPage.chapter.pageLoader as OnlineTranslatedPageLoader
                waitFor { firstLoader.resolvePage(firstPage) != null }
                assertTrue("FIRST_ORIGINAL_RENDERED", rendered(first, firstPage, false))
                val stored = checkNotNull(firstLoader.resolvePage(firstPage))
                ownedDirectory = File(checkNotNull(stored.chapterDir.uri.path)).canonicalFile
                check(ownedDirectory?.parentFile == cacheRoot.canonicalFile)
                assertTrue(
                    "SCRIPTED_TRANSLATED_PAGE_PERSISTED",
                    firstLoader.withPageOperation(firstPage) { seed(translator, it) },
                )
                savedBytes = stored.pageFile.openInputStream().use { it.readBytes() }
                assertFalse(original.contentEquals(savedBytes))
                assertTrue(translator.isPageTranslated(stored.chapterDir, checkNotNull(stored.pageFile.name)))
                first.onActivity { firstPage.reload() }
                assertTrue("SEEDED_TRANSLATION_RENDERED_POSITIVE_CONTROL", rendered(first, firstPage, true))
            }
            withTimeout(10_000) { firstLoader.stopAndJoin() }
            assertTrue("FIRST_LOADER_RECYCLED", firstLoader.isRecycled)
            val imageCache = field(scope, "chapterCache") as ChapterCache
            // HttpPageLoader persists its URL list during recycle. Wait for that actual write so
            // the public reopen has the same source identity, without replacing source URLs.
            waitFor {
                runCatching {
                    imageCache.getPageListFromCache(chapter).single().url == firstPage.url
                }.getOrDefault(false)
            }
            val requestsBefore = server.requests.toList()
            val diskBefore = snapshot(checkNotNull(ownedDirectory))
            val captureGate = CacheGate()
            gate = captureGate
            captureGate.awaitHeld()
            scope.launchReader(ReaderActivity.newIntent(context, manga.id, chapter.id)).use { reopened ->
                val page = readyPage(reopened)
                val loader = page.chapter.pageLoader as OnlineTranslatedPageLoader
                assertTrue("REOPEN_HAS_NEW_LOADER", loader !== firstLoader)
                val neverTranslate:
                    suspend (OnlineTranslatedPageLoader.StoredPage) -> PageTranslator.SinglePageOutcome = {
                        processorCalls.incrementAndGet()
                        error("REOPEN_MUST_NOT_PROCESS_A_PAGE")
                    }
                loader.javaClass.getDeclaredField("translatePage").apply { isAccessible = true }
                    .set(loader, neverTranslate)
                assertTrue("HTTP_ORIGINAL_RENDERED_BEFORE_CACHE_ATTACH", rendered(reopened, page, false))
                assertEquals("CACHE_NOT_ATTACHED_WHILE_GATE_HELD", null, page.uri)
                val reloadBeforeAttach = page.reloadFlow.value
                report.put("originalPixelsObservedBeforeAttach", true).put("liveTranslateEnabled", false)
                captureGate.releaseAndJoin()
                gate = null
                waitFor { loader.resolvePage(page) != null }
                val stored = checkNotNull(loader.resolvePage(page))
                assertEquals(
                    "REOPEN_REUSES_EXACT_AUTHORITY",
                    ownedDirectory,
                    File(checkNotNull(stored.chapterDir.uri.path)),
                )
                assertArrayEquals("REOPEN_STREAM_IS_SAVED_TRANSLATION", savedBytes, stream(page))
                assertTrue(
                    "REOPEN_STILL_COMPLETED",
                    translator.isPageTranslated(stored.chapterDir, stored.pageFile.name!!),
                )
                val visible = rendered(reopened, page, true)
                report.put("savedTranslationStreamExact", true).put("storedCompletionRetained", true)
                    .put("savedTranslationRenderedAfterAttach", visible)
                    .put("reloadDeltaOnAttach", page.reloadFlow.value - reloadBeforeAttach)
                    .put("processorCallsOnReopen", processorCalls.get())
                    .put("additionalHttpRequestsOnReopen", server.requests.size - requestsBefore.size)
                assertEquals("REOPEN_DOES_NOT_PROCESS", 0, processorCalls.get())
                assertEquals("REOPEN_DOES_NOT_REQUEST_SOURCE_AGAIN", requestsBefore, server.requests.toList())
                assertEquals("REOPEN_PRESERVES_STORED_BYTES", diskBefore, snapshot(checkNotNull(ownedDirectory)))
                assertFalse(page.isOriginalPreview)
                assertTrue(scope.downloads.queueState.value.isEmpty())
                assertTrue(scope.translations.queueState.value.isEmpty())
                assertEquals(0, TranslationBusy.active.value)
                assertEquals(null, fieldNullable(scope.engine, "engine"))
                assertTrue("SAVED_TRANSLATION_MUST_REPLACE_RENDERED_ORIGINAL_ON_REOPEN", visible)

                val attachedReload = page.reloadFlow.value
                repeat(3) { checkNotNull(loader.ensurePage(page)) }
                assertEquals("SAME_AUTHORITY_DOES_NOT_RELOAD", attachedReload, page.reloadFlow.value)
                reopened.onActivity {
                    it.viewModel.openPageDialog(page)
                    it.viewModel.toggleOriginalPreview()
                }
                waitFor { page.isOriginalPreview }
                assertTrue("REOPEN_ORIGINAL_PREVIEW_RENDERED", rendered(reopened, page, false))
                val previewReload = page.reloadFlow.value
                repeat(3) { checkNotNull(loader.ensurePage(page)) }
                assertTrue("SAME_AUTHORITY_PRESERVES_ORIGINAL_PREVIEW", page.isOriginalPreview)
                assertEquals("SAME_AUTHORITY_PREVIEW_DOES_NOT_RELOAD", previewReload, page.reloadFlow.value)
                assertTrue("PRESERVED_PREVIEW_STILL_RENDERED", rendered(reopened, page, false))
                reopened.onActivity {
                    it.viewModel.openPageDialog(page)
                    it.viewModel.toggleOriginalPreview()
                }
                waitFor { !page.isOriginalPreview }
                assertTrue("REOPEN_TRANSLATION_COMPARISON_RENDERED", rendered(reopened, page, true))
                assertEquals("COMPARISON_DOES_NOT_PROCESS", 0, processorCalls.get())
                assertEquals("COMPARISON_DOES_NOT_REQUEST_SOURCE", requestsBefore, server.requests.toList())
                assertEquals("COMPARISON_PRESERVES_STORED_BYTES", diskBefore, snapshot(checkNotNull(ownedDirectory)))
                report.put("sameAuthorityRepeatedAttachReloadDelta", 0)
                    .put("sameAuthorityOriginalPreviewPreserved", true).put("comparisonReturnedToTranslation", true)
            }
        } catch (error: Throwable) {
            failure = error
            report.put("failureClass", error.javaClass.name)
        } finally {
            withContext(NonCancellable) {
                try {
                    gate?.releaseAndJoin()
                } catch (cleanup: Throwable) {
                    failure = preserveFailure(failure, cleanup)
                }
                try {
                    report.put("cleanup", scope.close())
                    ownedDirectory?.let { directory ->
                        check(directory.parentFile == cacheRoot.canonicalFile)
                        check(directory.name.matches(Regex("[a-f0-9]{64}")))
                        directory.walkBottomUp().forEach { file ->
                            check(file.canonicalFile.toPath().startsWith(directory.toPath()))
                            check(file.delete())
                        }
                    }
                    assertEquals("OTHER_CACHE_ENTRIES_PRESERVED", cacheBefore, snapshot(cacheRoot))
                    report.put("ownedCacheRemoved", true)
                } catch (cleanup: Throwable) {
                    failure = preserveFailure(failure, cleanup)
                }
            }
        }
        report.put("terminal", true).put("passed", failure == null)
        emitOnlineReaderFlow("onlineSelectedPageReopenDisplayEvidence", report)
        failure?.let { throw it }
    }

    private fun configure(scope: OnlineReaderFlowScope) {
        scope.set(scope.preferences.translationEnabled, false)
        scope.set(scope.preferences.liveTranslate, false)
        scope.set(scope.preferences.keepMaterials, true)
        scope.set(scope.preferences.nightReadEnabled, false)
        scope.set(scope.preferences.inpaintMethod, "boxfill")
        scope.set(scope.preferences.translationMasterEnabled, true)
        scope.set(scope.downloadPreferences.autoDownloadWhileReading, 0)
        scope.set(scope.downloadPreferences.removeAfterReadSlots, -1)
        scope.set(scope.downloadPreferences.removeAfterMarkedAsRead, false)
        scope.set(scope.readerPreferences.fullscreen, false)
        scope.set(scope.readerPreferences.landscapeZoom, false)
        scope.set(scope.readerPreferences.showNavigationOverlayNewUser, false)
        scope.set(scope.readerPreferences.showNavigationOverlayOnStart, false)
        scope.set(scope.readerPreferences.customBrightness, false)
        scope.set(scope.readerPreferences.colorFilter, false)
        scope.set(scope.readerPreferences.grayscale, false)
        scope.set(scope.readerPreferences.invertedColors, false)
    }

    private suspend fun seed(translator: PageTranslator, stored: OnlineTranslatedPageLoader.StoredPage): Boolean {
        val original = bitmap(false)
        val output = bitmap(true)
        val mask = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val progress = PageProgress(
            WIDTH,
            HEIGHT,
            listOf(
                RegionProgress(
                    0,
                    RegionState.TRANSLATED,
                    listOf(
                        OcrLineProgress(
                            listOf(Pt(20f, 20f), Pt(80f, 20f), Pt(80f, 40f), Pt(20f, 40f)),
                            .9f,
                            "h",
                            "Neutral",
                        ),
                    ),
                    "h",
                    translatedText = "중립",
                ),
            ),
        )
        return try {
            translator.persistLivePage(
                stored.chapterDir,
                stored.pageFile,
                original,
                output,
                PageAnalysis(mask, progress.toTextRegions(), progress),
                "boxfill",
            )
        } finally {
            original.recycle()
            output.recycle()
            mask.recycle()
        }
    }

    private suspend fun readyPage(scenario: ActivityScenario<ReaderActivity>): ReaderPage {
        var result: ReaderPage? = null
        waitFor {
            scenario.onActivity {
                result = it.viewModel.state.value.viewerChapters?.currChapter?.pages?.singleOrNull()
                    ?.takeIf { page -> page.status == Page.State.Ready }
            }
            result != null
        }
        return checkNotNull(result)
    }

    /** Read four black/white markers from the real compositor, without requesting a holder redraw. */
    private suspend fun rendered(
        scenario: ActivityScenario<ReaderActivity>,
        page: ReaderPage,
        translated: Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var points = emptyList<Pair<Int, Int>>()
            scenario.onActivity { activity ->
                val holder = views(activity.window.decorView).filterIsInstance<PagerPageHolder>()
                    .firstOrNull { it.page === page && it.getGlobalVisibleRect(Rect()) }
                val image = holder?.let { views(it).filterIsInstance<SubsamplingScaleImageView>().firstOrNull() }
                if (image?.isReady == true && image.isImageLoaded && image.sWidth == WIDTH && image.sHeight == HEIGHT) {
                    val location = IntArray(2)
                    image.getLocationOnScreen(location)
                    val visible = Rect()
                    if (image.getGlobalVisibleRect(visible)) {
                        points = (0..3).mapNotNull { marker ->
                            image.sourceToViewCoord(markerX(marker).toFloat(), markerY(marker).toFloat())?.let {
                                (location[0] + it.x).roundToInt() to (location[1] + it.y).roundToInt()
                            }
                        }.takeIf { candidates ->
                            candidates.size == 4 && candidates.all { visible.contains(it.first, it.second) }
                        }.orEmpty()
                    }
                }
            }
            if (points.size == 4) {
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    if (points.withIndex().all { (index, point) ->
                            point.first in 0 until screenshot.width && point.second in 0 until screenshot.height &&
                                screenshot.getPixel(point.first, point.second) == markerColor(index, translated)
                        }
                    ) {
                        return true
                    }
                } finally {
                    screenshot.recycle()
                }
            }
            delay(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private class CacheGate {
        private val held = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val thread = Thread {
            try {
                val monitor = OnlinePageCache::class.java.getDeclaredField("monitor")
                    .apply { isAccessible = true }.get(null)
                synchronized(checkNotNull(monitor)) {
                    held.countDown()
                    check(release.await(40, TimeUnit.SECONDS)) { "CACHE_GATE_RELEASE_TIMEOUT" }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { start() }

        suspend fun awaitHeld() = waitFor { held.count == 0L || failure.get() != null }.also {
            failure.get()?.let { throw it }
        }
        fun releaseAndJoin() {
            release.countDown()
            thread.join(5000)
            check(!thread.isAlive) { "CACHE_GATE_THREAD_DID_NOT_JOIN" }
            failure.get()?.let { throw it }
        }
    }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 320
        fun markerX(index: Int) = WIDTH * (if (index and 1 == 0) 1 else 3) / 4
        fun markerY(index: Int) = HEIGHT * (if (index and 2 == 0) 1 else 3) / 4
        fun markerColor(index: Int, translated: Boolean) =
            if ((index % 2 == 0) xor translated) Color.WHITE else Color.BLACK
        fun bitmap(translated: Boolean): Bitmap = Bitmap.createBitmap(
            WIDTH,
            HEIGHT,
            Bitmap.Config.ARGB_8888,
            false,
            ColorSpace.get(ColorSpace.Named.SRGB),
        ).apply {
            eraseColor(Color.GRAY)
            val canvas = Canvas(this)
            val paint = Paint().apply { isAntiAlias = false }
            for (index in 0..3) {
                paint.color = markerColor(index, translated)
                val x = markerX(index).toFloat()
                val y = markerY(index).toFloat()
                canvas.drawRect(x - 12f, y - 12f, x + 12f, y + 12f, paint)
            }
        }
        fun encoded(translated: Boolean): ByteArray {
            val bitmap = bitmap(translated)
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        fun stream(page: ReaderPage) = checkNotNull(page.displayStream).invoke().use { it.readBytes() }
        fun fieldNullable(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner)
        fun field(owner: Any, name: String) = checkNotNull(fieldNullable(owner, name))
        fun snapshot(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
                .digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }
        fun views(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(15_000) {
            while (!condition()) delay(25)
        }
        fun preserveFailure(previous: Throwable?, cleanup: Throwable): Throwable {
            if (previous == null || cleanup is VirtualMachineError || cleanup is ThreadDeath ||
                cleanup is LinkageError
            ) {
                previous?.let(cleanup::addSuppressed)
                return cleanup
            }
            previous.addSuppressed(cleanup)
            return previous
        }
    }
}
