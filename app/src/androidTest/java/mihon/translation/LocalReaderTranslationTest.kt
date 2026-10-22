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
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.ArchivePageLoader
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.core.archive.archiveReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Real LocalSource/ReaderActivity and native OCR; only the translation HTTP response is synthetic. */
@RunWith(AndroidJUnit4::class)
class LocalReaderTranslationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun localDirectorySupportsPageTranslationComparisonReRenderAndChapterQueue() = runBlocking<Unit> {
        withFixture(archive = false) { fixture, server ->
            ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                val activity = ready(scenario)
                val chapter = checkNotNull(activity.viewModel.state.value.viewerChapters).currChapter
                assertTrue(chapter.pageLoader is DirectoryPageLoader)
                val pages = checkNotNull(chapter.pages)
                assertEquals(2, pages.size)
                assertEquals(Uri.fromFile(File(fixture.file, "002.png")), pages[1].uri)
                scenario.onActivity {
                    it.viewModel.state.value.viewer!!.moveToPage(pages[1])
                }
                pageAction(scenario, pages[1], MR.strings.reader_translate_this_page)
                waitFor(180000) {
                    server.failure?.let { throw AssertionError("Fixture HTTP failure", it) }
                    fixture.translator.isPageTranslated(fixture.uniFile, "002.png") &&
                        pages[1].status == Page.State.Ready
                }
                assertFalse(fixture.translator.isPageTranslated(fixture.uniFile, "001.png"))
                assertArrayEquals(fixture.original, File(fixture.file, ".yakuyomi/002.orig.png").readBytes())
                assertTrue(File(fixture.file, ".yakuyomi/002.json").readText().contains("안녕하세요"))
                compareOriginal(scenario, pages[1], fixture.original)
                val calls = server.requests.size
                val revision = pages[1].reloadFlow.value
                pageAction(scenario, pages[1], MR.strings.reader_change_removal)
                waitFor(10000) { activity.viewModel.state.value.dialog is ReaderViewModel.Dialog.ReRenderMethod }
                scenario.onActivity { it.viewModel.reRenderPage("boxfill") }
                waitFor(180000) { pages[1].reloadFlow.value > revision && TranslationBusy.active.value == 0 }
                assertEquals("Re-render must reuse persisted OCR/translation", calls, server.requests.size)
                val firstRevision = pages[0].reloadFlow.value
                pageAction(scenario, pages[0], MR.strings.reader_translate_this_chapter)
                waitFor(180000) {
                    server.failure?.let { throw AssertionError("Fixture HTTP failure", it) }
                    fixture.translator.isChapterTranslated(fixture.uniFile) && pages[0].reloadFlow.value > firstRevision
                }
                waitFor(10000) { TranslationBusy.active.value == 0 && fixture.manager.queueState.value.isEmpty() }
                assertEquals(2, server.requests.size)
                assertEquals(2, activity.viewModel.state.value.currentPage)
                fixture.assertNoDownload()
                assertArrayEquals(fixture.original, File(fixture.file, ".yakuyomi/001.orig.png").readBytes())
                compareOriginal(scenario, pages[0], fixture.original)
            }
            val before = File(fixture.file, "001.png").readBytes()
            ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                val activity = ready(scenario)
                assertEquals(2, activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.size)
                assertArrayEquals(before, File(fixture.file, "001.png").readBytes())
                assertEquals(2, server.requests.size)
            }
        }
    }

    @Test
    fun localArchiveChapterTranslationReopensTwoPagesAndKeepsVerifiedOriginalBackup() = runBlocking<Unit> {
        withFixture(archive = true) { fixture, server ->
            val originalArchive = fixture.file.readBytes()
            ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                val activity = ready(scenario)
                val originalLoader = activity.viewModel.state.value.viewerChapters!!.currChapter.pageLoader
                assertTrue(originalLoader is ArchivePageLoader)
                val page = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!![1]
                scenario.onActivity {
                    it.viewModel.state.value.viewer!!.moveToPage(page)
                }
                pageAction(
                    scenario,
                    page,
                    MR.strings.reader_translate_this_chapter,
                    forbidden = MR.strings.reader_translate_this_page,
                )
                waitFor(240000) {
                    server.failure?.let { throw AssertionError("Fixture HTTP failure", it) }
                    val current = activity.viewModel.state.value.viewerChapters?.currChapter
                    fixture.translator.isArchiveTranslated(fixture.uniFile) &&
                        current?.pageLoader is ArchivePageLoader && current.pageLoader !== originalLoader &&
                        current.pages?.size == 2
                }
                waitFor(10000) { activity.viewModel.state.value.currentPage == 2 }
                val pages = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!
                assertEquals("Originals and masks must not become reader pages", 2, pages.size)
                assertTrue(pages.all { it.status == Page.State.Ready })
                fixture.assertNoDownload()
                ZipFile(fixture.file).use { archive ->
                    assertArrayEquals(
                        fixture.original,
                        archive.getInputStream(archive.getEntry(".yakuyomi/001.orig.png")).use { it.readBytes() },
                    )
                    val translated = archive.getInputStream(archive.getEntry("002.png")).use { it.readBytes() }
                    assertArrayEquals(translated, pages[1].displayStream!!().use { it.readBytes() })
                }
                val backups = fixture.file.parentFile!!.listFiles()!!.filter { it.name.endsWith(".bak") }
                assertEquals(1, backups.size)
                assertArrayEquals(originalArchive, backups.single().readBytes())
                assertEquals(2, server.requests.size)
            }
            val completedArchive = fixture.file.readBytes()
            ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                val activity = ready(scenario)
                assertEquals(2, activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.size)
                assertArrayEquals(completedArchive, fixture.file.readBytes())
                assertEquals(2, server.requests.size)
            }
        }
    }

    @Test
    fun localDirectoryAutomaticTranslationUsesActualStopAndRestartActions() = localAutomaticTranslation(archive = false)

    @Test
    fun localArchiveAutomaticTranslationUsesActualStopAndRestartActions() = localAutomaticTranslation(archive = true)

    private fun localAutomaticTranslation(archive: Boolean) = runBlocking<Unit> {
        // One page isolates the held request from the chapter's multi-page pipeline. The other
        // tests cover two-page resume and archive replacement without concurrent fixture sockets.
        withFixture(archive = archive, live = true, pageCount = 1) { fixture, server ->
            val requested = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.expectClientDisconnect = setOf(1)
            server.beforeResponse = { number ->
                if (number == 1) {
                    requested.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Local automatic translation gate timed out" }
                }
            }
            // Migrate the impossible local pending state left by the previously released bug.
            fixture.manager.markForTranslate(fixture.chapter.id)
            val originalArchive = if (archive) fixture.file.readBytes() else null
            try {
                ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                    val activity = ready(scenario, expectedPages = 1)
                    assertTrue("Local auto mode never reached native OCR/LLM", requested.await(180, TimeUnit.SECONDS))
                    fixture.assertNoDownload()
                    assertFalse(activity.viewModel.state.value.translationDownloadPending)
                    waitFor(10000) { activity.viewModel.state.value.liveTranslateProgress != null }
                    pageAction(
                        scenario,
                        activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.first(),
                        MR.strings.reader_stop_chapter_translate,
                        forbidden = MR.strings.reader_translate_this_chapter,
                    )
                    release.countDown()
                    waitFor(30000) {
                        server.failure?.let { throw AssertionError("Fixture cancellation failure", it) }
                        TranslationBusy.active.value == 0 && fixture.manager.queueState.value.isEmpty() &&
                            1 in server.disconnectedRequests
                    }
                    fixture.assertNoDownload()
                    if (archive) {
                        assertArrayEquals(originalArchive, fixture.file.readBytes())
                    } else {
                        assertArrayEquals(fixture.original, File(fixture.file, "001.png").readBytes())
                    }
                    // Queue removal/reload in this session must not auto-enqueue the stopped chapter.
                    SystemClock.sleep(500)
                    assertEquals(1, server.requests.size)
                    assertTrue(fixture.manager.queueState.value.isEmpty())
                    pageAction(
                        scenario,
                        activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.first(),
                        MR.strings.reader_translate_this_chapter,
                    )
                    waitFor(240000) {
                        server.failure?.let { throw AssertionError("Fixture HTTP failure", it) }
                        val done = if (archive) {
                            fixture.translator.isArchiveTranslated(fixture.uniFile)
                        } else {
                            fixture.translator.isChapterTranslated(fixture.uniFile)
                        }
                        done && TranslationBusy.active.value == 0 && fixture.manager.queueState.value.isEmpty()
                    }
                    fixture.assertNoDownload()
                    assertEquals(2, server.requests.size) // One cancelled request and one completed translation.
                    waitFor(10000) { activity.viewModel.state.value.liveTranslateProgress == null }
                    if (!archive) {
                        val page = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.first()
                        compareOriginal(scenario, page, fixture.original)
                        pageAction(scenario, page, MR.strings.reader_change_removal)
                        waitFor(10000) {
                            activity.viewModel.state.value.dialog is ReaderViewModel.Dialog.ReRenderMethod
                        }
                        scenario.onActivity { it.viewModel.closeDialog() }
                    }
                }
                ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                    val activity = ready(scenario, expectedPages = 1)
                    assertEquals(1, activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.size)
                    fixture.assertNoDownload()
                    assertEquals("Reopening completed local content must not translate again", 2, server.requests.size)
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun archiveReaderExcludesReservedMaterialsButKeepsNormalNestedImages() = runBlocking<Unit> {
        val file = File.createTempFile("local-reader-paths-", ".cbz", context.cacheDir)
        try {
            val image = sample()
            ZipOutputStream(file.outputStream()).use { zip ->
                listOf("001.png", "chapter/002.png", ".yakuyomi-art/003.png", ".yakuyomi/001.orig.png")
                    .forEach { name ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(image)
                        zip.closeEntry()
                    }
            }
            val loader = ArchivePageLoader(checkNotNull(UniFile.fromFile(file)).archiveReader(context))
            try {
                assertEquals(3, loader.getPages().size)
            } finally {
                loader.recycle()
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun withFixture(
        archive: Boolean,
        live: Boolean = false,
        pageCount: Int = 2,
        block: suspend (Fixture, FixtureTranslationServer) -> Unit,
    ) {
        val manager = Injekt.get<TranslationManager>()
        val downloads = Injekt.get<DownloadManager>()
        assertTrue(
            "Requires idle user queues",
            manager.queueState.value.isEmpty() && downloads.queueState.value.isEmpty(),
        )
        assertEquals(0, TranslationBusy.active.value)
        val sources = Injekt.get<SourceManager>()
        withTimeout(20000) { sources.isInitialized.first { it } }
        assertTrue(sources.get(LocalSource.ID) is LocalSource)
        Injekt.get<GetFavorites>().await()
        val root = File(context.filesDir, "local-reader-test-${System.nanoTime()}").apply { check(mkdirs()) }
        val chapters = Injekt.get<ChapterRepository>()
        var chapterId: Long? = null
        try {
            OnlineReaderSettings(context).use { settings ->
                FixtureTranslationServer().use { server ->
                    val prefs = Injekt.get<TranslationPreferences>()
                    val reader = Injekt.get<ReaderPreferences>()
                    settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
                    settings.set(prefs.apiKeyFor("custom"), "")
                    settings.set(prefs.apiBase, server.base)
                    settings.set(prefs.model, "fixture-model")
                    settings.set(prefs.provider, "custom")
                    settings.set(prefs.targetLangName, "Korean")
                    settings.set(prefs.orientation, "horizontal")
                    settings.set(prefs.translationMasterEnabled, true)
                    settings.set(prefs.translationEnabled, false)
                    settings.set(prefs.liveTranslate, live)
                    settings.set(prefs.translationSourcesExclude, emptySet())
                    settings.set(prefs.liveTranslateCategories, emptySet())
                    settings.set(prefs.liveTranslateCategoriesExclude, emptySet())
                    settings.set(prefs.liveInpaintMethod, "boxfill")
                    settings.set(prefs.inpaintMethod, "boxfill")
                    settings.set(prefs.keepMaterials, true)
                    settings.set(prefs.privacyAcknowledged, true)
                    settings.set(prefs.nightReadEnabled, false)
                    settings.set(reader.landscapeZoom, false)
                    settings.set(reader.showNavigationOverlayNewUser, false)
                    settings.set(reader.showNavigationOverlayOnStart, false)
                    val storage = Injekt.get<StorageManager>()
                    waitFor(10000) { storage.getLocalSourceDirectory()?.uri == Uri.fromFile(File(root, "local")) }
                    val title = "Local reader fixture ${System.nanoTime()}"
                    val mangaDir = File(root, "local/$title").apply { check(mkdirs()) }
                    val image = sample()
                    val chapterName = if (archive) "Chapter.cbz" else "Chapter"
                    val file = File(mangaDir, chapterName)
                    if (archive) {
                        ZipOutputStream(file.outputStream()).use { zip ->
                            for (name in (1..pageCount).map { "%03d.png".format(it) }) {
                                zip.putNextEntry(ZipEntry(name))
                                zip.write(image)
                                zip.closeEntry()
                            }
                        }
                    } else {
                        check(file.mkdir())
                        (1..pageCount).forEach { File(file, "%03d.png".format(it)).writeBytes(image) }
                    }
                    val repository = Injekt.get<MangaRepository>()
                    val manga = repository.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = LocalSource.ID,
                                url = title,
                                title = title,
                                initialized = true,
                            ),
                        ),
                    ).single()
                    repository.update(MangaUpdate(manga.id, viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue.toLong()))
                    val chapter = chapters.addAll(
                        listOf(
                            Chapter.create().copy(mangaId = manga.id, url = "$title/$chapterName", name = chapterName),
                        ),
                    ).single()
                    chapterId = chapter.id
                    try {
                        block(Fixture(manga, chapter, file, image), server)
                    } finally {
                        manager.cancelPendingTranslation(chapter.id)
                        waitFor(30000) { TranslationBusy.active.value == 0 && manager.queueState.value.isEmpty() }
                    }
                }
            }
        } finally {
            chapterId?.let { chapters.removeChaptersWithIds(listOf(it)) }
            root.deleteRecursively() // Exclusively created test root; settings restoration is completed above.
        }
    }

    private inner class Fixture(val manga: Manga, val chapter: Chapter, val file: File, val original: ByteArray) {
        val manager = Injekt.get<TranslationManager>()
        val translator = PageTranslator(context)
        val uniFile = checkNotNull(UniFile.fromFile(file))
        fun intent() = ReaderActivity.newIntent(context, manga.id, chapter.id)
        fun assertNoDownload() {
            assertFalse(manager.isPendingTranslate(chapter.id))
            assertTrue(Injekt.get<DownloadManager>().queueState.value.isEmpty())
        }
    }

    private fun ready(scenario: ActivityScenario<ReaderActivity>, expectedPages: Int = 2): ReaderActivity {
        lateinit var activity: ReaderActivity
        scenario.onActivity { activity = it }
        waitFor(15000) { activity.viewModel.state.value.viewerChapters?.currChapter?.pages?.size == expectedPages }
        return activity
    }

    private fun compareOriginal(scenario: ActivityScenario<ReaderActivity>, page: ReaderPage, original: ByteArray) {
        pageAction(scenario, page, MR.strings.reader_show_original, forbidden = MR.strings.reader_show_translation)
        waitFor(10000) { page.isOriginalPreview }
        assertArrayEquals(original, page.displayStream!!().use { it.readBytes() })
        pageAction(scenario, page, MR.strings.reader_show_translation, forbidden = MR.strings.reader_show_original)
        waitFor(10000) { !page.isOriginalPreview }
    }

    /** Open the real page menu, then activate its accessibility button, never the operation's VM method. */
    private fun pageAction(
        scenario: ActivityScenario<ReaderActivity>,
        page: ReaderPage,
        action: StringResource,
        forbidden: StringResource = MR.strings.reader_stop_chapter_translate,
    ) {
        lateinit var expectedLabel: String
        lateinit var forbiddenLabel: String
        scenario.onActivity {
            expectedLabel = it.stringResource(action)
            forbiddenLabel = it.stringResource(forbidden)
            it.viewModel.openPageDialog(page)
        }
        checkOnlineReaderAction(
            packageName = context.packageName,
            expectedLabel = expectedLabel,
            forbiddenLabel = forbiddenLabel,
            click = true,
            beforeClick = {},
        )
    }

    private fun sample(): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.LTGRAY)
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                drawRoundRect(50f, 100f, 1150f, 800f, 100f, 100f, ink)
                ink.color = Color.BLACK
                ink.textSize = 72f
                drawText("こんにちは。", 170f, 350f, ink)
                drawText("今日もよろしくお願いします。", 100f, 490f, ink)
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
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < end) { "Local reader test timed out after ${timeout}ms" }
            SystemClock.sleep(100)
        }
    }
}
