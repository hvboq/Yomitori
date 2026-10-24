package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.PageProcessingDetails
import eu.kanade.tachiyomi.data.translation.PageProcessingReason
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStore
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.ArchivePageLoader
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
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
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual long presses and menu clicks; saved neutral results need no models or OCR/LLM work. */
@RunWith(AndroidJUnit4::class)
class ProcessingSummaryReaderUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 120_000)
    fun savedAndLegacyRecordsUseTheRealPageMenuWithoutChangingFiles(): Unit = runBlocking {
        withFixture(archive = false) { fixture ->
            val expected = fixture.prepareSavedAndLegacyPages()
            val before = fileHashes(fixture.file)
            ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                val pages = ready(scenario, archive = false)
                val lock = pageMutex(fixture.pageFile())
                val owner = Any()
                lock.lock(owner)
                val query = try {
                    openPageActions(scenario, pages.first())
                    clickRecordAndAwaitLoading(scenario)
                } finally {
                    lock.unlock(owner)
                }
                withTimeout(10_000) { query.join() }
                val saved = awaitSummary(scenario)
                assertEquals(expected, saved.details)
                assertLabel(MR.strings.reader_processing_record_no_text)
                assertLabelText(context.stringResource(MR.strings.reader_processing_record_detected_lines, 0))
                assertLabelText(context.stringResource(MR.strings.reader_processing_record_regions, 0))
                val recordedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                    .format(Date(expected.recordedAtEpochMs))
                assertLabelText(context.stringResource(MR.strings.reader_processing_record_time, recordedAt))
                click(MR.strings.action_ok)
                awaitNoDialog(scenario)

                // This page has a genuine legacy completion entry and saved original, but no summary.
                openPageActions(scenario, pages[1])
                click(MR.strings.reader_processing_record)
                assertNull(awaitSummary(scenario).details)
                assertLabel(MR.strings.reader_processing_record_missing)
                click(MR.strings.action_ok)
                awaitNoDialog(scenario)
            }
            assertEquals(
                "Reading records changed a page, original, journal, manifest or summary",
                before,
                fileHashes(fixture.file),
            )
        }
    }

    @Test(timeout = 120_000)
    fun dismissedReadsCannotReopenTheDialogOrReplaceALaterPageMenu(): Unit = runBlocking {
        withFixture(archive = false) { fixture ->
            fixture.prepareSavedAndLegacyPages()
            val before = fileHashes(fixture.file)
            ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                val pages = ready(scenario, archive = false)
                val lock = pageMutex(fixture.pageFile())
                val owner = Any()

                lock.lock(owner)
                val closedQuery = try {
                    openPageActions(scenario, pages.first())
                    clickRecordAndAwaitLoading(scenario).also {
                        click(MR.strings.action_ok)
                        awaitNoDialog(scenario)
                        assertFalse("The lookup must still be waiting on the page lock", it.isCompleted)
                    }
                } finally {
                    lock.unlock(owner)
                }
                withTimeout(10_000) { closedQuery.join() }
                instrumentation.waitForIdleSync()
                assertNull(currentDialog(scenario))
                awaitRecordDialogAbsent()

                lock.lock(owner)
                lateinit var laterMenu: ReaderViewModel.Dialog.PageActions
                val supersededQuery = try {
                    openPageActions(scenario, pages.first())
                    clickRecordAndAwaitLoading(scenario).also {
                        click(MR.strings.action_ok)
                        awaitNoDialog(scenario)
                        openPageActions(scenario, pages[1])
                        laterMenu = currentDialog(scenario) as ReaderViewModel.Dialog.PageActions
                        assertSame(pages[1], laterMenu.page)
                        assertFalse("The old lookup completed before the replacement menu", it.isCompleted)
                    }
                } finally {
                    lock.unlock(owner)
                }
                withTimeout(10_000) { supersededQuery.join() }
                instrumentation.waitForIdleSync()
                assertSame("The old result replaced the new page menu", laterMenu, currentDialog(scenario))
                assertLabel(MR.strings.reader_processing_record)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitNoDialog(scenario)
            }
            assertEquals(before, fileHashes(fixture.file))
        }
    }

    @Test(timeout = 120_000)
    fun archiveEntriesHaveNoSavedRecordAction(): Unit = runBlocking {
        withFixture(archive = true) { fixture ->
            val before = fileHashes(fixture.file)
            ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                val pages = ready(scenario, archive = true)
                assertTrue(pages.all { it.uri == null })
                openPageActions(scenario, pages.first())
                checkOnlineReaderAction(
                    packageName = context.packageName,
                    expectedLabel = context.stringResource(MR.strings.action_save),
                    forbiddenLabel = context.stringResource(MR.strings.reader_processing_record),
                    click = false,
                    beforeClick = {},
                )
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitNoDialog(scenario)
            }
            assertEquals(before, fileHashes(fixture.file))
        }
    }

    private suspend fun withFixture(archive: Boolean, block: suspend (Fixture) -> Unit): Unit = coroutineScope {
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        check(InstrumentationRegistry.getArguments().getString("processingSummaryReaderUi") == "true") {
            "Explicit processingSummaryReaderUi=true opt-in required"
        }
        requireIdle()
        val sources = Injekt.get<SourceManager>()
        withTimeout(20_000) { sources.isInitialized.first { it } }
        check(sources.get(LocalSource.ID) is LocalSource)
        val storage = Injekt.get<StorageManager>()
        val oldStorage = effectiveStorage(storage)
        val root = File(context.cacheDir.canonicalFile, "processing-summary-reader-${UUID.randomUUID()}")
            .apply { check(mkdir()) }
        val settings = OnlineReaderSettings(context)
        val server = FixtureTranslationServer()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val service = Injekt.get<TranslationEngineService>()
        val engineEvents = CopyOnWriteArrayList<String>()
        val observers = listOf(
            launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                service.warm.collect { if (it) engineEvents += "warm" }
            },
            launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                service.loading.collect { if (it) engineEvents += "loading" }
            },
            launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                TranslationBusy.active.collect { if (it != 0) engineEvents += "busy" }
            },
        )
        val title = "Neutral saved record ${root.name}"
        var mangaId: Long? = null
        var chapterId: Long? = null
        var failure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        try {
            configure(settings, root, server)
            waitUntil(20_000) { storage.getLocalSourceDirectory()?.uri == Uri.fromFile(File(root, "local")) }
            val book = File(root, "local/$title").apply { check(mkdirs()) }
            val chapterName = if (archive) "Chapter.cbz" else "Chapter"
            val chapterFile = File(book, chapterName)
            if (archive) {
                ZipOutputStream(chapterFile.outputStream()).use { zip ->
                    for (name in listOf("001.png", "002.png", ".yakuyomi/001.orig.png")) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(imageBytes())
                        zip.closeEntry()
                    }
                }
            } else {
                check(chapterFile.mkdir())
                for (name in listOf("001.png", "002.png")) File(chapterFile, name).writeBytes(imageBytes())
            }
            val manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = LocalSource.ID, url = title, title = title, initialized = true)),
            ).single().also { mangaId = it.id }
            mangas.update(MangaUpdate(manga.id, viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue.toLong()))
            val chapter = chapters.addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "$title/$chapterName", name = chapterName)),
            ).single().also { chapterId = it.id }
            block(Fixture(manga, chapter, chapterFile))
            assertTrue(
                "Record lookup loaded an engine or started page processing: $engineEvents",
                engineEvents.isEmpty(),
            )
            assertFalse(service.warm.value)
            assertFalse(service.loading.value)
            assertEquals("Record lookup sent a translation request", 0, server.requests.size)
            assertNull(server.failure)
            assertTrue(Injekt.get<TranslationManager>().queueState.value.isEmpty())
            assertTrue(Injekt.get<DownloadManager>().queueState.value.isEmpty())
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(label: String, action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (error: Throwable) {
                        cleanupFailures += AssertionError("Owned processing-record cleanup failed: $label", error)
                    }
                }
                cleanup("observers") { observers.forEach { it.cancelAndJoin() } }
                cleanup("owned database rows") {
                    chapterId?.let { id ->
                        check(chapters.getChapterById(id)?.mangaId == mangaId)
                        chapters.removeChaptersWithIds(listOf(id))
                        Injekt.get<TranslationManager>().forgetChapterOutputs(listOf(id))
                    }
                    mangaId?.let { id ->
                        val row = mangas.getMangaById(id)
                        check(row.source == LocalSource.ID && row.url == title)
                        val driver = Injekt.get<SqlDriver>()
                        check(
                            driver.execute(null, "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?", 3) {
                                bindLong(0, id)
                                bindLong(1, LocalSource.ID)
                                bindString(2, title)
                            }.await() == 1L,
                        )
                        driver.notifyListeners("mangas", "chapters", "history")
                        Injekt.get<TranslationCache>().invalidate(id)
                    }
                }
                var storageRestored = false
                cleanup("preference value, presence and effective storage") {
                    settings.close()
                    waitUntil(20_000) { effectiveStorage(storage) == oldStorage }
                    storageRestored = true
                }
                cleanup("fixture server") { server.close() }
                if (storageRestored && TranslationBusy.active.value == 0) {
                    cleanup("owned files") {
                        check(
                            root.canonicalFile == root.absoluteFile &&
                                root.parentFile == context.cacheDir.canonicalFile,
                        )
                        check(root.name.startsWith("processing-summary-reader-") && root.deleteRecursively())
                    }
                } else {
                    cleanupFailures += AssertionError("Retained owned files because storage or work is not idle")
                }
            }
        }
        failure?.let { error ->
            cleanupFailures.forEach(error::addSuppressed)
            throw error
        }
        if (cleanupFailures.isNotEmpty()) {
            throw AssertionError("Processing-record fixture cleanup failed").apply {
                cleanupFailures.forEach(::addSuppressed)
            }
        }
    }

    private inner class Fixture(val manga: Manga, val chapter: Chapter, val file: File) {
        fun intent() = ReaderActivity.newIntent(context, manga.id, chapter.id)
        fun pageFile(): UniFile = checkNotNull(UniFile.fromFile(File(file, "001.png")))

        suspend fun prepareSavedAndLegacyPages(): PageProcessingDetails {
            val directory = checkNotNull(UniFile.fromFile(file))
            val source = image()
            val output = image()
            val mask = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ALPHA_8)
            val translator = PageTranslator(context)
            try {
                assertTrue(
                    translator.persistLivePage(
                        directory,
                        pageFile(),
                        source,
                        output,
                        PageAnalysis(
                            mask,
                            emptyList(),
                            PageProgress(WIDTH, HEIGHT, emptyList(), noTextDetected = true),
                        ),
                        "boxfill",
                    ),
                )
            } finally {
                source.recycle()
                output.recycle()
                mask.recycle()
            }
            val details = checkNotNull(translator.getPageProcessingDetails(directory, "001.png"))
            assertEquals(PageProcessingReason.NO_TEXT, details.outcome.reason)
            assertNotNull(details.outcome.counts)
            File(file, ".yakuyomi/002.orig.png").writeBytes(File(file, "002.png").readBytes())
            File(file, CompletionManifestCodec.PRIMARY).writeBytes(
                CompletionManifestCodec.encode(setOf("001.png", "002.png")),
            )
            assertTrue(translator.isPageTranslated(directory, "002.png"))
            assertNull(translator.getPageProcessingDetails(directory, "002.png"))
            return details
        }
    }

    private fun configure(settings: OnlineReaderSettings, root: File, server: FixtureTranslationServer) {
        val preferences = Injekt.get<TranslationPreferences>()
        settings.set(preferences.translationMasterEnabled, false)
        settings.set(preferences.translationEnabled, false)
        settings.set(preferences.liveTranslate, false)
        settings.set(preferences.nightReadEnabled, false)
        settings.set(preferences.apiKeyFor("custom"), "")
        settings.set(preferences.provider, "custom")
        settings.set(preferences.apiBase, server.base)
        settings.set(preferences.model, "fixture-model")
        settings.set(preferences.targetLangName, "Korean")
        settings.set(preferences.inpaintMethod, "boxfill")
        val reader = Injekt.get<ReaderPreferences>()
        settings.set(reader.nightReadMode, false)
        settings.set(reader.landscapeZoom, false)
        settings.set(reader.dualPageSplitPaged, false)
        settings.set(reader.dualPageRotateToFit, false)
        settings.set(reader.imageScaleType, 1)
        settings.set(reader.cropBorders, false)
        settings.set(reader.readWithLongTap, true)
        settings.set(reader.showNavigationOverlayNewUser, false)
        settings.set(reader.showNavigationOverlayOnStart, false)
        val downloads = Injekt.get<DownloadPreferences>()
        settings.set(downloads.autoDownloadWhileReading, 0)
        settings.set(downloads.removeAfterReadSlots, -1)
        settings.set(downloads.removeAfterMarkedAsRead, false)
        settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
    }

    private suspend fun requireIdle() {
        val stored = context.getSharedPreferences("active_translations", Context.MODE_PRIVATE)
        check(stored.all.keys.all { it in setOf("paused", "paused_night", "pending_translate", "pending_night") })
        for (key in listOf("pending_translate", "pending_night")) {
            check(stored.getStringSet(key, emptySet()).orEmpty().isEmpty())
        }
        val store = TranslationStore(context)
        withTimeout(10_000) { check(store.restore().isEmpty()) }
        val marks = store.restorePendingMarks(Long.MIN_VALUE)
        check(marks.translate.isEmpty() && marks.night.isEmpty())
        val manager = Injekt.get<TranslationManager>()
        withTimeout(10_000) { manager.ensureRestored() }
        check(manager.queueState.value.isEmpty() && Injekt.get<DownloadManager>().queueState.value.isEmpty())
        check(TranslationBusy.active.value == 0 && !Injekt.get<TranslationEngineService>().warm.value)
        for (tag in listOf("Translator", "TranslatorHeartbeat")) {
            check(
                WorkManager.getInstance(context).getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS)
                    .all { it.state.isFinished },
            )
        }
    }

    private fun ready(scenario: ActivityScenario<ReaderActivity>, archive: Boolean): List<ReaderPage> {
        var pages: List<ReaderPage>? = null
        waitUntil(20_000) {
            scenario.onActivity {
                it.viewModel.state.value.viewerChapters?.currChapter?.let { chapter ->
                    if (chapter.pages?.size == 2 && chapter.pages!!.all { page -> page.status == Page.State.Ready }) {
                        check(
                            if (archive) {
                                chapter.pageLoader is ArchivePageLoader
                            } else {
                                chapter.pageLoader is DirectoryPageLoader
                            },
                        )
                        pages = chapter.pages
                    }
                }
            }
            pages != null
        }
        return checkNotNull(pages)
    }

    private fun openPageActions(scenario: ActivityScenario<ReaderActivity>, page: ReaderPage) {
        scenario.onActivity { checkNotNull(it.viewModel.state.value.viewer).moveToPage(page) }
        waitUntil(10_000) {
            var ready = false
            scenario.onActivity {
                val viewer = checkNotNull(it.viewModel.state.value.viewer).getView()
                val holder = viewTree(viewer).filterIsInstance<PagerPageHolder>()
                    .firstOrNull { item -> item.page === page }
                val image = holder?.let { item ->
                    viewTree(item).filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
                }
                val visible = Rect()
                ready = holder?.getGlobalVisibleRect(visible) == true && visible.width() >= viewer.width * 0.95f &&
                    it.viewModel.state.value.currentPage == page.index + 1 &&
                    image?.isReady == true && image.isImageLoaded
            }
            ready
        }
        val bounds = Rect()
        scenario.onActivity {
            val viewer = checkNotNull(it.viewModel.state.value.viewer).getView()
            check(viewer.getGlobalVisibleRect(bounds))
            val rootOrigin = IntArray(2)
            viewer.rootView.getLocationOnScreen(rootOrigin)
            bounds.offset(rootOrigin[0], rootOrigin[1])
        }
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            0,
        )
        try {
            instrumentation.sendPointerSync(down)
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        } finally {
            down.recycle()
            val up = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0,
            )
            try {
                instrumentation.sendPointerSync(up)
            } finally {
                up.recycle()
            }
        }
        waitUntil(10_000) { (currentDialog(scenario) as? ReaderViewModel.Dialog.PageActions)?.page === page }
    }

    /** The held real page mutex keeps exactly this new ViewModel IO child alive until the test releases it. */
    private fun clickRecordAndAwaitLoading(scenario: ActivityScenario<ReaderActivity>): Job {
        val before = scopeChildren(scenario)
        click(MR.strings.reader_processing_record)
        waitUntil(10_000) { (currentDialog(scenario) as? ReaderViewModel.Dialog.ProcessingSummary)?.isLoading == true }
        assertLabel(MR.strings.loading)
        var query: Job? = null
        waitUntil(10_000) {
            query = scopeChildren(scenario).filter { it !in before && it.isActive }.singleOrNull()
            query != null
        }
        return checkNotNull(query)
    }

    private fun scopeChildren(scenario: ActivityScenario<ReaderActivity>): Set<Job> {
        var children: Set<Job> = emptySet()
        scenario.onActivity {
            children = checkNotNull(it.viewModel.viewModelScope.coroutineContext[Job]).children.toSet()
        }
        return children
    }

    private fun currentDialog(scenario: ActivityScenario<ReaderActivity>): ReaderViewModel.Dialog? {
        var dialog: ReaderViewModel.Dialog? = null
        scenario.onActivity { dialog = it.viewModel.state.value.dialog }
        return dialog
    }

    private fun awaitSummary(scenario: ActivityScenario<ReaderActivity>): ReaderViewModel.Dialog.ProcessingSummary {
        var summary: ReaderViewModel.Dialog.ProcessingSummary? = null
        waitUntil(10_000) {
            summary = (currentDialog(scenario) as? ReaderViewModel.Dialog.ProcessingSummary)
                ?.takeUnless { it.isLoading }
            summary != null
        }
        return checkNotNull(summary)
    }

    private fun awaitNoDialog(scenario: ActivityScenario<ReaderActivity>) {
        waitUntil(10_000) { currentDialog(scenario) == null }
    }

    @Suppress("DEPRECATION")
    private fun awaitRecordDialogAbsent() {
        val title = context.stringResource(MR.strings.reader_processing_record)
        fun containsTitle(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.text?.toString() == title) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    if (containsTitle(child)) return true
                } finally {
                    child.recycle()
                }
            }
            return false
        }
        waitUntil(5_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitUntil false
            try {
                root.packageName?.toString() == context.packageName && !containsTitle(root)
            } finally {
                root.recycle()
            }
        }
    }

    private fun click(resource: StringResource) = action(context.stringResource(resource), click = true)
    private fun assertLabel(resource: StringResource) = assertLabelText(context.stringResource(resource))
    private fun assertLabelText(label: String) = action(label, click = false)

    private fun action(label: String, click: Boolean) = checkOnlineReaderAction(
        context.packageName,
        label,
        "OWNED_PROCESSING_RECORD_ACTION_MUST_NOT_EXIST",
        click,
        beforeClick = {},
    )

    private fun waitUntil(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Processing-record reader UI timed out" }
            SystemClock.sleep(50)
        }
    }

    private fun viewTree(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(viewTree(view.getChildAt(index)))
    }

    private fun pageMutex(pageFile: UniFile): Mutex = PageTranslator.Companion::class.java
        .getDeclaredMethod("pageLock", UniFile::class.java).apply { isAccessible = true }
        .invoke(PageTranslator.Companion, pageFile) as Mutex

    private fun effectiveStorage(storage: StorageManager): Uri? = (
        StorageManager::class.java.getDeclaredField("baseDir").apply { isAccessible = true }.get(storage) as UniFile?
        )?.uri

    private fun fileHashes(file: File): Map<String, String> = if (file.isDirectory) {
        file.walkTopDown().filter { it.isFile }.associate { it.relativeTo(file).path to sha(it.readBytes()) }
    } else {
        mapOf(file.name to sha(file.readBytes()))
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun image(): Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
    }

    private fun imageBytes(): ByteArray {
        val bitmap = image()
        try {
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 384
    }
}
