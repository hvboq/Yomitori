package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.TranslatingPageLoader
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File

/** Real download/reader recovery with a synthetic HTTP source, native OCR, and the configured server. */
@RunWith(AndroidJUnit4::class)
class OnlineReaderRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = Injekt.get<TranslationPreferences>()
    private val downloads get() = Injekt.get<DownloadManager>()
    private val translations get() = Injekt.get<TranslationManager>()
    private val cache get() = Injekt.get<TranslationCache>()

    @Test
    fun retryCurrentChapterKeepsAnotherDownloadErrorUntouched() = runCase("retry-one-error") { fixture ->
        val (otherManga, otherChapter) = fixture.addChapter("/chapter-b")
        fixture.stage("two_real_download_errors") {
            fixture.server.failPageLists += setOf("/chapter", "/chapter-b")
            downloads.downloadChapters(fixture.manga, listOf(fixture.chapter), autoStart = false)
            downloads.downloadChapters(otherManga, listOf(otherChapter), autoStart = false)
            downloads.startDownloads()
            waitFor(60000) {
                downloads.getQueuedDownloadOrNull(fixture.chapter.id)?.status == Download.State.ERROR &&
                    downloads.getQueuedDownloadOrNull(otherChapter.id)?.status == Download.State.ERROR &&
                    !downloads.isRunning
            }
            assertTrue(fixture.loader() is HttpPageLoader)
            assertEquals(2, fixture.activity.viewModel.state.value.currentPage)
            assertEquals(0, TranslationBusy.active.value)
        }
        val otherRequests = fixture.server.requests.count { it == "/chapter-b" }
        fixture.stage("same_reader_retries_only_selected_error") {
            fixture.server.failPageLists -= "/chapter"
            fixture.settings.set(prefs.liveTranslate, true)
            fixture.clickChapterAction(stop = false)
            fixture.awaitReload()
            fixture.awaitTranslated()
            assertEquals(Download.State.ERROR, downloads.getQueuedDownloadOrNull(otherChapter.id)?.status)
            assertEquals(listOf(otherChapter.id), downloads.queueState.value.map { it.chapter.id })
            assertEquals(otherRequests, fixture.server.requests.count { it == "/chapter-b" })
            assertEquals(1, cache.getTranslatedCount(fixture.manga))
        }
    }

    @Test
    fun stopDuringDownloadPreventsCompletionFromRestartingTranslation() = runCase("stop-online") { fixture ->
        fixture.stage("stop_pending_online_translation") {
            fixture.server.holdImages = true
            fixture.settings.set(prefs.liveTranslate, true)
            // Prove stop also suppresses the ordinary automatic-download translation fallback.
            fixture.settings.set(prefs.translationEnabled, true)
            fixture.clickChapterAction(stop = false)
            waitFor(30000) {
                fixture.server.heldImageRequest.count == 0L &&
                    translations.isPendingTranslate(fixture.chapter.id)
            }
            assertTrue(fixture.loader() is HttpPageLoader)
            assertTrue(
                "This must exercise download pending, not queued translation",
                translations.queueState.value.isEmpty(),
            )
            assertTrue(fixture.activity.viewModel.state.value.liveTranslateProgress == null)
            fixture.clickChapterAction(stop = true)
            waitFor(10000) { !translations.isPendingTranslate(fixture.chapter.id) }
            assertTrue(translations.queueState.value.none { it.chapter.id == fixture.chapter.id })
            fixture.assertChapterAction(stop = false)
            fixture.server.releaseDownload()
        }
        fixture.stage("download_finishes_without_translation_or_reader_reload") {
            fixture.awaitDownloaded()
            SystemClock.sleep(1500)
            assertTrue(fixture.loader() is HttpPageLoader)
            assertEquals(2, fixture.activity.viewModel.state.value.currentPage)
            assertEquals(0, TranslationBusy.active.value)
            assertTrue(translations.queueState.value.none { it.chapter.id == fixture.chapter.id })
            assertFalse(translations.isPendingTranslate(fixture.chapter.id))
            val directory = checkNotNull(fixture.directory())
            assertFalse(PageTranslator(context).isChapterTranslated(directory))
            assertTrue(directory.findFile(".yakuyomi_translated") == null)
            fixture.pageFiles(directory).forEachIndexed { index, file ->
                assertArrayEquals(fixture.originals[index], file.openInputStream().use { it.readBytes() })
            }
            // Re-selecting the visible chapter must not undo the explicit stop either.
            fixture.moveToPage(0)
            fixture.moveToPage(1)
            SystemClock.sleep(1000)
            assertTrue(fixture.loader() is HttpPageLoader)
            assertTrue(translations.queueState.value.none { it.chapter.id == fixture.chapter.id })
            assertEquals(0, cache.getTranslatedCount(fixture.manga))
        }
    }

    @Test
    fun manualStartReloadsAlreadyDownloadedChapterStillUsingHttpLoader() = runCase("late-reload") { fixture ->
        fixture.stage("download_completes_with_existing_http_reader") {
            // Construct the exact state left after a bounded poll has ended without waiting five
            // minutes: an external download finishes while this reader has no translation poll.
            // The production timeout's step selection has its own JVM regression coverage.
            downloads.downloadChapters(fixture.manga, listOf(fixture.chapter))
            fixture.awaitDownloaded()
            SystemClock.sleep(1000)
            assertTrue(fixture.loader() is HttpPageLoader)
            assertFalse(PageTranslator(context).isChapterTranslated(checkNotNull(fixture.directory())))
            assertFalse(translations.isPendingTranslate(fixture.chapter.id))
            assertEquals(2, fixture.activity.viewModel.state.value.currentPage)
        }
        fixture.stage("explicit_start_reloads_same_reader_and_translates") {
            fixture.settings.set(prefs.liveTranslate, true)
            fixture.clickChapterAction(stop = false)
            fixture.awaitReload()
            fixture.awaitTranslated()
        }
    }

    private fun runCase(name: String, block: suspend (Fixture) -> Unit) = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("onlineReaderRecovery") == "true")
        assertTrue("Requires idle user download queue", downloads.queueState.value.isEmpty() && !downloads.isRunning)
        assertTrue("Requires idle user translation queue", translations.queueState.value.isEmpty())
        assertEquals(0, TranslationBusy.active.value)
        assertTrue("Native models and real server must be configured", Injekt.get<TranslationEngineService>().isReady())
        val api = listOf(prefs.provider, prefs.model, prefs.apiBase, prefs.apiKeyFor(prefs.provider.get()))
        val apiBefore = api.map { it.isSet() to it.get() }
        val sources = Injekt.get<SourceManager>()
        withTimeout(20000) { sources.isInitialized.first { it } }
        Injekt.get<GetFavorites>().await()
        val output = File(context.getExternalFilesDir(null), "online-reader-$name-${System.currentTimeMillis()}")
            .apply { check(mkdirs()) }
        val report = JSONObject().put("case", name).put("source", "synthetic HTTP; not Hitomi")
            .put("translation", "configured real server and native OCR").put("success", false)
        val began = SystemClock.elapsedRealtime()
        fun save() {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - began)
            File(output, "result.json").writeText(report.toString(2))
        }
        try {
            OnlineReaderSettings(context).use { settings ->
                configure(settings)
                val root = File(context.filesDir, "online-recovery-${System.nanoTime()}").apply { check(mkdirs()) }
                settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
                val expectedDownloads = File(root, "downloads").canonicalFile
                waitFor(10000) {
                    Injekt.get<StorageManager>().getDownloadsDirectory()?.uri?.let { uri ->
                        uri.scheme == "file" && uri.path?.let { File(it).canonicalFile } == expectedDownloads
                    } == true
                }
                val originals = listOf(sample(1), sample(2))
                OnlineReaderFixtureServer(originals).use { server ->
                    OnlineReaderSourceRegistration(sources, server.source).use {
                        val owned = mutableListOf<Pair<Manga, Chapter>>()
                        try {
                            val (manga, chapter) = addChapter(server, "/chapter").also { owned += it }
                            Injekt.get<MangaRepository>().update(
                                MangaUpdate(manga.id, viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue.toLong()),
                            )
                            cache.getTranslatedCount(manga)
                            ActivityScenario.launch<ReaderActivity>(
                                ReaderActivity.newIntent(context, manga.id, chapter.id),
                            ).use { scenario ->
                                lateinit var activity: ReaderActivity
                                scenario.onActivity { activity = it }
                                val fixture = Fixture(
                                    settings, server, manga, chapter, scenario, activity,
                                    originals, owned, output, report, ::save,
                                )
                                fixture.stage("online_two_pages_at_page2") {
                                    waitFor(60000) {
                                        fixture.loader() is HttpPageLoader && fixture.pagesReady()
                                    }
                                    fixture.moveToPage(1)
                                    assertTrue(fixture.directory() == null)
                                }
                                block(fixture)
                                report.put("httpRequests", JSONArray(server.requests))
                            }
                        } finally {
                            server.releaseDownload()
                            owned.forEach { (_, chapter) ->
                                translations.cancelPendingTranslation(chapter.id)
                                downloads.getQueuedDownloadOrNull(chapter.id)?.let {
                                    downloads.cancelQueuedDownloads(listOf(it))
                                }
                            }
                            withTimeout(120000) { TranslationBusy.active.first { it == 0 } }
                            waitFor(30000) { owned.none { downloads.getQueuedDownloadOrNull(it.second.id) != null } }
                            Injekt.get<ChapterRepository>().removeChaptersWithIds(owned.map { it.second.id })
                            owned.forEach { cache.invalidate(it.first.id) }
                        }
                    }
                    assertTrue(sources.get(server.source.id) == null)
                }
            }
            assertTrue("API configuration must remain unchanged", api.map { it.isSet() to it.get() } == apiBefore)
            report.put("settingsRestoredAndPersisted", true).put("apiConfigurationUnchanged", true).put("success", true)
        } catch (error: Throwable) {
            report.put("failureType", error.javaClass.simpleName)
            runCatching { screenshot(output, "failure.png") }
            throw error
        } finally {
            save()
        }
    }

    private inner class Fixture(
        val settings: OnlineReaderSettings,
        val server: OnlineReaderFixtureServer,
        val manga: Manga,
        val chapter: Chapter,
        val scenario: ActivityScenario<ReaderActivity>,
        val activity: ReaderActivity,
        val originals: List<ByteArray>,
        private val owned: MutableList<Pair<Manga, Chapter>>,
        private val output: File,
        private val report: JSONObject,
        private val save: () -> Unit,
    ) {
        private val stages = JSONArray().also { report.put("stages", it) }
        private val actions = JSONArray().also { report.put("actualUiActions", it) }

        fun clickChapterAction(stop: Boolean) = inspectChapterAction(stop, click = true)

        fun assertChapterAction(stop: Boolean) = inspectChapterAction(stop, click = false)

        private fun inspectChapterAction(stop: Boolean, click: Boolean) {
            lateinit var startLabel: String
            lateinit var stopLabel: String
            scenario.onActivity {
                startLabel = it.stringResource(MR.strings.reader_translate_this_chapter)
                stopLabel = it.stringResource(MR.strings.reader_stop_chapter_translate)
                val page = it.viewModel.state.value.viewerChapters!!.currChapter.pages!![1]
                it.viewModel.openPageDialog(page)
            }
            val action = if (stop) "stop" else "start"
            checkOnlineReaderAction(
                packageName = context.packageName,
                expectedLabel = if (stop) stopLabel else startLabel,
                forbiddenLabel = if (stop) startLabel else stopLabel,
                click = click,
                beforeClick = { screenshot(output, "dialog-${actions.length() + 1}-$action.png") },
            )
            if (!click) scenario.onActivity { it.viewModel.closeDialog() }
            waitFor(10000) { activity.viewModel.state.value.dialog == null }
            actions.put(JSONObject().put("action", action).put("accessibilityClick", click))
            save()
        }

        suspend fun addChapter(path: String) = this@OnlineReaderRecoveryTest.addChapter(server, path)
            .also { owned += it }

        fun directory() = Injekt.get<DownloadProvider>().findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            server.source,
        )

        fun loader() = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader

        fun pagesReady() = activity.viewModel.state.value.viewerChapters?.currChapter?.pages?.let { pages ->
            pages.size == 2 && pages.all { it.status == Page.State.Ready }
        } == true

        fun moveToPage(index: Int) {
            scenario.onActivity {
                val pages = it.viewModel.state.value.viewerChapters!!.currChapter.pages!!
                it.viewModel.state.value.viewer!!.moveToPage(pages[index])
            }
            waitFor(10000) { activity.viewModel.state.value.currentPage == index + 1 }
        }

        fun pageFiles(directory: UniFile) = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.orEmpty().endsWith(".png") }.sortedBy { it.name }

        fun awaitDownloaded() {
            waitFor(120000) {
                check(server.failure == null) { "Fixture server failed" }
                directory()?.isDirectory == true && downloads.getQueuedDownloadOrNull(chapter.id) == null
            }
        }

        fun awaitReload() {
            waitFor(120000) {
                check(server.failure == null) { "Fixture server failed" }
                loader() is TranslatingPageLoader
            }
            scenario.onActivity { assertTrue("Reader instance must stay the same", it === activity) }
            waitFor(10000) { activity.viewModel.state.value.currentPage == 2 }
        }

        fun awaitTranslated() {
            val translator = PageTranslator(context)
            waitFor(420000) {
                check(server.failure == null) { "Fixture server failed" }
                check(
                    translations.queueState.value.none {
                        it.chapter.id == chapter.id && it.status == TranslationItem.Status.ERROR
                    },
                ) { "Real translation failed; inspect application diagnostics" }
                directory()?.let { translator.isChapterTranslated(it) } == true &&
                    translations.queueState.value.none { it.chapter.id == chapter.id } &&
                    downloads.getQueuedDownloadOrNull(chapter.id) == null &&
                    TranslationBusy.active.value == 0 && pagesReady()
            }
            waitFor(20000) { cache.getTranslatedCount(manga) == 1 }
            assertEquals(2, activity.viewModel.state.value.currentPage)
            val directory = checkNotNull(directory())
            val files = pageFiles(directory)
            assertEquals(2, files.size)
            val materialsDirectory = checkNotNull(directory.findFile(".yakuyomi"))
            val regionsReport = JSONArray()
            files.forEachIndexed { index, file ->
                val stem = checkNotNull(file.name).substringBeforeLast('.')
                val original = checkNotNull(materialsDirectory.findFile("$stem.orig.png"))
                assertArrayEquals(originals[index], original.openInputStream().use { it.readBytes() })
                assertFalse(originals[index].contentEquals(file.openInputStream().use { it.readBytes() }))
                val materials = Json { ignoreUnknownKeys = true }.decodeFromString<PageMaterials>(
                    checkNotNull(materialsDirectory.findFile("$stem.json")).openInputStream()
                        .bufferedReader().use { it.readText() },
                )
                val regions = materials.regions.filterNot { it.preserveOriginal }
                assertTrue(regions.any { it.source.contains("こんにちは") })
                assertTrue(regions.isNotEmpty() && regions.all { Regex("[가-힣]").containsMatchIn(it.target) })
                regions.forEach { regionsReport.put(JSONObject().put("source", it.source).put("target", it.target)) }
            }
            report.put("regions", regionsReport).put("translatedChapters", 1)
        }

        suspend fun stage(name: String, action: suspend () -> Unit) {
            val entry = JSONObject().put("name", name).put("success", false)
            stages.put(entry)
            val began = SystemClock.elapsedRealtime()
            try {
                action()
                screenshot(output, "${stages.length()}-$name.png")
                entry.put("success", true)
            } finally {
                entry.put("elapsedMs", SystemClock.elapsedRealtime() - began)
                save()
            }
        }
    }

    private suspend fun addChapter(server: OnlineReaderFixtureServer, path: String): Pair<Manga, Chapter> {
        val manga = Injekt.get<MangaRepository>().insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = server.source.id,
                    url = "/recovery/${System.nanoTime()}",
                    title = "Online recovery synthetic ${System.nanoTime()}",
                    initialized = true,
                ),
            ),
        ).single()
        val chapter = Injekt.get<ChapterRepository>().addAll(
            listOf(Chapter.create().copy(mangaId = manga.id, url = path, name = "Fixture", chapterNumber = 1.0)),
        ).single()
        return manga to chapter
    }

    private fun configure(settings: OnlineReaderSettings) {
        settings.set(prefs.translationMasterEnabled, true)
        settings.set(prefs.translationEnabled, false)
        settings.set(prefs.liveTranslate, false)
        settings.set(prefs.keepMaterials, true)
        settings.set(prefs.privacyAcknowledged, true)
        settings.set(prefs.nightReadEnabled, false)
        settings.set(prefs.sourceLangName, "Japanese")
        settings.set(prefs.targetLangName, "Korean")
        settings.set(prefs.orientation, "horizontal")
        settings.set(prefs.liveTranslateCategories, emptySet())
        settings.set(prefs.liveTranslateCategoriesExclude, emptySet())
        val download = Injekt.get<DownloadPreferences>()
        settings.set(download.downloadOnlyOverWifi, false)
        settings.set(download.saveChaptersAsCBZ, false)
        settings.set(download.splitTallImages, false)
        settings.set(download.autoDownloadWhileReading, 0)
        settings.set(download.removeAfterReadSlots, -1)
        settings.set(download.removeAfterMarkedAsRead, false)
        val reader = Injekt.get<ReaderPreferences>()
        settings.set(reader.fullscreen, false)
        settings.set(reader.landscapeZoom, false)
        settings.set(reader.showNavigationOverlayNewUser, false)
        settings.set(reader.showNavigationOverlayOnStart, false)
    }

    private fun sample(number: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(if (number == 1) Color.LTGRAY else Color.rgb(220, 230, 240))
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                drawRoundRect(50f, 100f, 1150f, 800f, 100f, 100f, ink)
                ink.color = Color.BLACK
                ink.textSize = 72f
                drawText("こんにちは。", 170f, 350f, ink)
                drawText("少し待ってください。", 100f, 490f, ink)
            }
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitFor(timeout: Long, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < until) { "Recovery stage timed out after ${timeout}ms" }
            SystemClock.sleep(100)
        }
    }

    private fun screenshot(output: File, name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).let { bitmap ->
            try {
                File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
