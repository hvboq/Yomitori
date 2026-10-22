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
import li.joye.yakuyomi.engine.LlmProviders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Opt in with onlineReaderFlow=true. Exercises a synthetic HTTP manga source, NOT Hitomi.
 * Uses the configured real server unless both liveTranslationBase/liveTranslationModel are supplied.
 * Explicit overrides use an empty API key and are restored, including absent preferences, on exit.
 */
@RunWith(AndroidJUnit4::class)
class OnlineReaderFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun onlinePagesDownloadTranslateAndReopenInTheSameReader() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("onlineReaderFlow") == "true")
        val overrideBase = args.getString("liveTranslationBase").orEmpty().trim()
        val overrideModel = args.getString("liveTranslationModel").orEmpty().trim()
        require(overrideBase.isBlank() == overrideModel.isBlank()) {
            "Supply liveTranslationBase and liveTranslationModel together"
        }
        val downloads = Injekt.get<DownloadManager>()
        val translations = Injekt.get<TranslationManager>()
        // Never start, pause, or remove the user's existing queue as part of this test.
        assertTrue("Requires an idle download queue", downloads.queueState.value.isEmpty() && !downloads.isRunning)
        assertTrue("Requires an idle translation queue", translations.queueState.value.isEmpty())
        assertEquals(0, TranslationBusy.active.value)
        val prefs = Injekt.get<TranslationPreferences>()
        val apiPreferences = listOf(
            prefs.provider,
            prefs.model,
            prefs.apiBase,
            prefs.apiKeyFor(prefs.provider.get()),
            prefs.apiKeyFor("custom"),
        ).distinctBy { it.key() }
        val apiBefore = apiPreferences.map { it.isSet() to it.get() }
        val sources = Injekt.get<SourceManager>()
        withTimeout(20000) { sources.isInitialized.first { it } }
        Injekt.get<GetFavorites>().await()
        val output = File(context.getExternalFilesDir(null), "online-reader-flow-${System.currentTimeMillis()}")
            .apply { check(mkdirs()) }
        val stages = JSONArray()
        val report = JSONObject().put("source", "synthetic HTTP fixture; not Hitomi")
            .put("translation", "configured real server and native OCR")
            .put("stages", stages).put("success", false)
        val started = SystemClock.elapsedRealtime()
        fun saveReport() {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            File(output, "result.json").writeText(report.toString(2))
        }
        fun stage(name: String, block: () -> Unit) {
            val step = JSONObject().put("name", name).put("success", false)
            stages.put(step)
            val start = SystemClock.elapsedRealtime()
            try {
                block()
                step.put("success", true)
            } finally {
                step.put("elapsedMs", SystemClock.elapsedRealtime() - start)
                saveReport()
            }
        }
        val originals = listOf(sample(1), sample(2))
        try {
            OnlineReaderSettings(context).use { settings ->
                configure(settings)
                if (overrideBase.isNotBlank()) {
                    settings.set(prefs.apiKeyFor("custom"), "")
                    settings.set(prefs.apiBase, overrideBase)
                    settings.set(prefs.model, overrideModel)
                    settings.set(prefs.provider, "custom")
                }
                val provider = LlmProviders.byId(prefs.provider.get())
                val endpoint = LlmProviders.chatUrlOf(provider, prefs.apiBase.get()).toHttpUrlOrNull()
                report.put("temporaryApiOverride", overrideBase.isNotBlank())
                    .put("model", prefs.model.get().ifBlank { provider.defaultModel })
                    .put(
                        "base",
                        endpoint?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)
                            ?.build()?.toString().orEmpty(),
                    )
                assertTrue(
                    "Configure the real server and install native models first",
                    Injekt.get<TranslationEngineService>().isReady(),
                )
                val root = File(context.filesDir, "online-reader-${System.nanoTime()}").apply { check(mkdirs()) }
                settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
                stage("isolated_empty_storage") {
                    val expected = File(root, "downloads").canonicalFile
                    waitFor(10000) {
                        Injekt.get<StorageManager>().getDownloadsDirectory()?.uri?.let {
                            it.scheme == "file" && it.path?.let { path -> File(path).canonicalFile == expected } == true
                        } == true
                    }
                    // StorageManager creates its own .nomedia sentinel when switching roots.
                    assertTrue(expected.listFiles().orEmpty().all { it.isFile && it.name == ".nomedia" })
                }
                OnlineReaderFixtureServer(originals).use { server ->
                    OnlineReaderSourceRegistration(sources, server.source).use {
                        val mangaRepository = Injekt.get<MangaRepository>()
                        val chapterRepository = Injekt.get<ChapterRepository>()
                        val manga = mangaRepository.insertNetworkManga(
                            listOf(
                                Manga.create().copy(
                                    source = server.source.id,
                                    url = "/fixture/${System.nanoTime()}",
                                    title = "Online reader synthetic ${System.nanoTime()}",
                                    initialized = true,
                                ),
                            ),
                        ).single()
                        val chapter = chapterRepository.addAll(
                            listOf(
                                Chapter.create().copy(
                                    mangaId = manga.id,
                                    url = "/chapter",
                                    name = "Fixture",
                                    chapterNumber = 1.0,
                                ),
                            ),
                        ).single()
                        val cache = Injekt.get<TranslationCache>()
                        val provider = Injekt.get<DownloadProvider>()
                        val translator = PageTranslator(context)
                        fun chapterDir() = provider.findChapterDir(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.title,
                            server.source,
                        )
                        fun checkBackgroundFailure() {
                            check(server.failure == null) { "Fixture HTTP server failed" }
                            check(
                                translations.queueState.value.none {
                                    it.chapter.id == chapter.id && it.status == TranslationItem.Status.ERROR
                                },
                            ) { "Real translation queue reported an error; inspect application diagnostics" }
                        }
                        try {
                            mangaRepository.update(
                                MangaUpdate(manga.id, viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue.toLong()),
                            )
                            assertEquals(0, cache.getTranslatedCount(manga))
                            assertTrue(chapterDir() == null)
                            val intent = ReaderActivity.newIntent(context, manga.id, chapter.id)
                            lateinit var downloadedDir: UniFile
                            var completedHashes = emptyMap<String, String>()
                            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                                lateinit var activity: ReaderActivity
                                scenario.onActivity { activity = it }
                                stage("http_reader_two_original_pages") {
                                    waitFor(60000) {
                                        checkBackgroundFailure()
                                        activity.viewModel.state.value.viewerChapters?.currChapter?.let {
                                            it.pageLoader is HttpPageLoader && it.pages?.size == 2 &&
                                                it.pages!!.all { page -> page.status == Page.State.Ready }
                                        } == true
                                    }
                                    val pages = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!
                                    pages.forEachIndexed { index, page ->
                                        assertArrayEquals(
                                            originals[index],
                                            page.displayStream!!().use { it.readBytes() },
                                        )
                                    }
                                    scenario.onActivity { it.viewModel.state.value.viewer!!.moveToPage(pages[1]) }
                                    waitFor(10000) { activity.viewModel.state.value.currentPage == 2 }
                                    assertTrue(chapterDir() == null)
                                    assertTrue(
                                        server.requests.containsAll(listOf("/chapter", "/image/1.png", "/image/2.png")),
                                    )
                                    screenshot(output, "01-online-page2.png")
                                }
                                stage("real_downloader_queued_from_reader") {
                                    server.holdImages = true
                                    settings.set(prefs.liveTranslate, true)
                                    scenario.onActivity { it.viewModel.startChapterTranslate() }
                                    waitFor(30000) {
                                        checkBackgroundFailure()
                                        server.heldImageRequest.count == 0L &&
                                            downloads.getQueuedDownloadOrNull(chapter.id) != null
                                    }
                                    assertEquals(2, activity.viewModel.state.value.currentPage)
                                    val loader = activity.viewModel.state.value.viewerChapters!!.currChapter.pageLoader
                                    assertTrue(loader is HttpPageLoader)
                                    screenshot(output, "02-downloading-page2.png")
                                    server.releaseDownload()
                                }
                                stage("same_activity_reloads_downloaded_pages_at_page2") {
                                    waitFor(120000) {
                                        checkBackgroundFailure()
                                        val loader = activity.viewModel.state.value.viewerChapters
                                            ?.currChapter?.pageLoader
                                        loader is TranslatingPageLoader
                                    }
                                    scenario.onActivity { assertTrue("Reader must not be recreated", it === activity) }
                                    waitFor(10000) { activity.viewModel.state.value.currentPage == 2 }
                                    downloadedDir = checkNotNull(chapterDir())
                                    assertTrue(downloadedDir.isDirectory)
                                    screenshot(output, "03-downloaded-reader-page2.png")
                                }
                                stage("native_ocr_real_translation_complete_and_badge_one") {
                                    waitFor(420000) {
                                        checkBackgroundFailure()
                                        translator.isChapterTranslated(downloadedDir) &&
                                            translations.queueState.value.none { it.chapter.id == chapter.id } &&
                                            downloads.getQueuedDownloadOrNull(chapter.id) == null &&
                                            TranslationBusy.active.value == 0 &&
                                            activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!
                                                .all { it.status == Page.State.Ready }
                                    }
                                    waitFor(20000) { cache.getTranslatedCount(manga) == 1 }
                                    assertEquals(2, activity.viewModel.state.value.currentPage)
                                    val pageFiles = downloadedDir.listFiles().orEmpty().filter {
                                        it.isFile && it.name.orEmpty().endsWith(".png")
                                    }.sortedBy { it.name }
                                    assertEquals(2, pageFiles.size)
                                    val materialsDir = checkNotNull(downloadedDir.findFile(".yakuyomi"))
                                    val regionsReport = JSONArray()
                                    pageFiles.forEachIndexed { index, file ->
                                        val stem = checkNotNull(file.name).substringBeforeLast('.')
                                        val original = checkNotNull(materialsDir.findFile("$stem.orig.png"))
                                        assertArrayEquals(
                                            originals[index],
                                            original.openInputStream().use { it.readBytes() },
                                        )
                                        val rendered = file.openInputStream().use { it.readBytes() }
                                        assertFalse(originals[index].contentEquals(rendered))
                                        val json = Json { ignoreUnknownKeys = true }
                                        val materials = json.decodeFromString<PageMaterials>(
                                            checkNotNull(materialsDir.findFile("$stem.json")).openInputStream()
                                                .bufferedReader().use { it.readText() },
                                        )
                                        val regions = materials.regions.filterNot { it.preserveOriginal }
                                        assertTrue(
                                            "Native OCR must find Japanese fixture text",
                                            regions.any { it.source.contains("こんにちは") },
                                        )
                                        assertTrue(
                                            "Real server must return Korean",
                                            regions.isNotEmpty() && regions.all {
                                                Regex("[가-힣]").containsMatchIn(it.target)
                                            },
                                        )
                                        regions.forEach {
                                            regionsReport.put(
                                                JSONObject().put("source", it.source).put("target", it.target),
                                            )
                                        }
                                    }
                                    report.put("regions", regionsReport).put("translatedChapters", 1)
                                    screenshot(output, "04-translated-page2.png")
                                }
                                stage("original_preview_and_translation_preserve_disk") {
                                    completedHashes = treeHashes(downloadedDir)
                                    val page = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!![1]
                                    val rendered = page.displayStream!!().use { it.readBytes() }
                                    scenario.onActivity {
                                        it.viewModel.openPageDialog(page)
                                        it.viewModel.toggleOriginalPreview()
                                    }
                                    waitFor(10000) { page.isOriginalPreview }
                                    assertArrayEquals(originals[1], page.displayStream!!().use { it.readBytes() })
                                    screenshot(output, "05-original-preview-page2.png")
                                    scenario.onActivity {
                                        it.viewModel.openPageDialog(page)
                                        it.viewModel.toggleOriginalPreview()
                                        it.viewModel.closeDialog()
                                    }
                                    waitFor(10000) { !page.isOriginalPreview }
                                    assertArrayEquals(rendered, page.displayStream!!().use { it.readBytes() })
                                    assertEquals(completedHashes, treeHashes(downloadedDir))
                                }
                            }
                            val requestsBeforeReopen = server.requests.size
                            stage("reopen_without_http_or_file_changes") {
                                ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                                    lateinit var reopened: ReaderActivity
                                    scenario.onActivity { reopened = it }
                                    waitFor(30000) {
                                        reopened.viewModel.state.value.viewerChapters?.currChapter?.pages?.let {
                                            it.size == 2 && it.all { page -> page.status == Page.State.Ready }
                                        } == true
                                    }
                                    // Let activation and any erroneously queued translation become observable.
                                    SystemClock.sleep(1500)
                                    val loader = reopened.viewModel.state.value.viewerChapters!!.currChapter.pageLoader
                                    assertFalse(loader is HttpPageLoader)
                                    assertTrue(translations.queueState.value.none { it.chapter.id == chapter.id })
                                    assertEquals(0, TranslationBusy.active.value)
                                    assertEquals(1, cache.getTranslatedCount(manga))
                                    assertEquals(completedHashes, treeHashes(downloadedDir))
                                    assertEquals(requestsBeforeReopen, server.requests.size)
                                    screenshot(output, "06-reopened.png")
                                }
                            }
                            report.put("httpRequests", JSONArray(server.requests))
                                .put("files", JSONObject(completedHashes))
                        } finally {
                            server.releaseDownload()
                            translations.cancelPendingTranslation(chapter.id)
                            downloads.getQueuedDownloadOrNull(chapter.id)?.let {
                                downloads.cancelQueuedDownloads(listOf(it))
                            }
                            withTimeout(120000) { TranslationBusy.active.first { it == 0 } }
                            waitFor(30000) { downloads.getQueuedDownloadOrNull(chapter.id) == null }
                            chapterRepository.removeChaptersWithIds(listOf(chapter.id))
                            cache.invalidate(manga.id)
                        }
                    }
                    assertTrue("Test source must be unregistered", sources.get(server.source.id) == null)
                }
            }
            assertTrue(
                "API configuration must remain unchanged",
                apiPreferences.map { it.isSet() to it.get() } == apiBefore,
            )
            report.put("settingsRestoredAndPersisted", true).put("apiConfigurationUnchanged", true).put("success", true)
        } catch (error: Throwable) {
            report.put("failureType", error.javaClass.simpleName)
            runCatching { screenshot(output, "failure.png") }
            throw error
        } finally {
            saveReport()
        }
    }

    private fun configure(settings: OnlineReaderSettings) {
        val prefs = Injekt.get<TranslationPreferences>()
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
                drawText(if (number == 1) "今日もよろしくお願いします。" else "少し待ってください。", 100f, 490f, ink)
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
            check(SystemClock.elapsedRealtime() < until) { "Online reader stage timed out after ${timeout}ms" }
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

    private fun treeHashes(root: UniFile): Map<String, String> = buildMap {
        fun visit(directory: UniFile, prefix: String) {
            directory.listFiles().orEmpty().forEach { file ->
                val path = prefix + checkNotNull(file.name)
                if (file.isDirectory) {
                    visit(file, "$path/")
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.openInputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    put(path, digest.digest().joinToString("") { "%02x".format(it) })
                }
            }
        }
        visit(root, "")
    }
}
