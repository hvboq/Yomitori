package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual reader/menu/toast/persistence integration. Inference is scripted; no native OCR or HTTP. */
@RunWith(AndroidJUnit4::class)
class PartialReaderIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun actualPageMenuPersistsPartialShowsNoticeComparesOriginalAndResumesAfterReopening() = runBlocking<Unit> {
        val manager = Injekt.get<TranslationManager>()
        val downloads = Injekt.get<DownloadManager>()
        val service = Injekt.get<TranslationEngineService>()
        assertTrue(
            "Requires idle user queues",
            manager.queueState.value.isEmpty() && downloads.queueState.value.isEmpty(),
        )
        assertEquals(0, TranslationBusy.active.value)
        assertTrue(
            "Reader readiness requires the existing prepared models",
            TranslationEngineConfig.modelsResolvable(context),
        )
        val sources = Injekt.get<SourceManager>()
        withTimeout(20000) { sources.isInitialized.first { it } }
        assertTrue(sources.get(LocalSource.ID) is LocalSource)
        Injekt.get<GetFavorites>().await()
        service.shutdown() // Never keep a native engine and the scripted replacement alive together.

        val root = File(context.filesDir, "partial-reader-${System.nanoTime()}").apply { check(mkdirs()) }
        val title = "Partial reader fixture ${System.nanoTime()}"
        val chapters = Injekt.get<ChapterRepository>()
        val mangas = Injekt.get<MangaRepository>()
        val storage = Injekt.get<StorageManager>()
        val previousLocalDirectory = storage.getLocalSourceDirectory()?.uri
        var chapterId: Long? = null
        var mangaId: Long? = null
        val engine = ScriptedEngine(sample())
        var engineInstalled = false
        var primaryFailure: Throwable? = null
        val report = JSONObject().put("passed", false).put("actualReaderActivity", true)
            .put("actualPageMenu", true).put("nativeOcr", false).put("externalServer", false)
        val reportFile = File(context.getExternalFilesDir(null), "partial-reader-integration.json")
        reportFile.writeText(report.toString(2))
        try {
            OnlineReaderSettings(context).use { settings ->
                configure(settings, root)
                waitFor("isolated local storage") {
                    storage.getLocalSourceDirectory()?.uri == Uri.fromFile(File(root, "local"))
                }
                val directory = File(root, "local/$title/Chapter").apply { check(mkdirs()) }
                val pageFile = File(directory, "001.png").apply { writeBytes(engine.originalBytes) }
                val chapterDir = checkNotNull(UniFile.fromFile(directory))
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = LocalSource.ID,
                            url = title,
                            title = title,
                            initialized = true,
                        ),
                    ),
                ).single()
                mangaId = manga.id
                val viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue
                mangas.update(
                    MangaUpdate(
                        manga.id,
                        viewerFlags = viewerFlags.toLong(),
                    ),
                )
                val chapter = chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "$title/Chapter", name = "Chapter")),
                ).single()
                chapterId = chapter.id
                installScriptedEngine(service, engine)
                engineInstalled = true
                val translator = PageTranslator(context)
                val intent = ReaderActivity.newIntent(context, manga.id, chapter.id)
                var partialBytes = byteArrayOf()
                var partialRevision = ""
                ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                    val activity = ready(scenario)
                    val page = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.single()
                    val loader = page.chapter.pageLoader
                    assertArrayEquals(engine.originalBytes, streamBytes(page))
                    val reload = page.reloadFlow.value
                    expectToast(activity.stringResource(MR.strings.reader_translate_page_partial), engine) {
                        pageAction(scenario, page, MR.strings.reader_translate_this_page)
                    }
                    waitFor("partial page displayed") {
                        engine.failure.get()?.let { throw AssertionError("Scripted inference failed", it) }
                        page.status == Page.State.Ready && page.reloadFlow.value > reload &&
                            translator.hasDisplayableTranslation(chapterDir, "001.png") &&
                            TranslationBusy.active.value == 0
                    }
                    assertEquals(1, engine.translateCalls.get())
                    assertEquals(0, engine.resumeCalls.get())
                    assertFalse(translator.isPageTranslated(chapterDir, "001.png"))
                    assertFalse(translator.isChapterTranslated(chapterDir))
                    partialBytes = pageFile.readBytes()
                    assertFalse(engine.originalBytes.contentEquals(partialBytes))
                    assertArrayEquals(partialBytes, streamBytes(page))
                    assertSame(loader, page.chapter.pageLoader)
                    val pending = journal(directory).load("001.png", recover = false)!!
                    assertFalse(pending.complete)
                    assertNotNull("The service must store its actual engine context", pending.safeContextPayload)
                    partialRevision = pending.revision
                    compareOriginal(scenario, page, engine.originalBytes, partialBytes)
                    assertTrue(manager.queueState.value.isEmpty())
                    assertTrue(downloads.queueState.value.isEmpty())
                }

                ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                    val activity = ready(scenario)
                    val page = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.single()
                    val loader = page.chapter.pageLoader
                    assertArrayEquals(partialBytes, streamBytes(page))
                    assertFalse(translator.isPageTranslated(chapterDir, "001.png"))
                    assertEquals(partialRevision, journal(directory).load("001.png", recover = false)!!.revision)
                    assertEquals("Reopening must not silently restart recognition", 1, engine.translateCalls.get())
                    assertEquals(0, engine.resumeCalls.get())
                    val reload = page.reloadFlow.value
                    expectToast(activity.stringResource(MR.strings.reader_translate_page_done), engine) {
                        pageAction(scenario, page, MR.strings.reader_translate_this_page)
                    }
                    waitFor("same reopened page completed") {
                        engine.failure.get()?.let { throw AssertionError("Scripted resume failed", it) }
                        translator.isPageTranslated(chapterDir, "001.png") &&
                            page.status == Page.State.Ready && page.reloadFlow.value > reload &&
                            TranslationBusy.active.value == 0
                    }
                    assertSame(loader, page.chapter.pageLoader)
                    assertEquals(1, activity.viewModel.state.value.currentPage)
                    assertEquals(1, engine.translateCalls.get())
                    assertEquals(1, engine.resumeCalls.get())
                    val complete = journal(directory).load("001.png", recover = false)!!
                    assertTrue(complete.complete)
                    assertEquals(partialRevision, complete.baseRevision)
                    val progress = OcrProgressCodec.decode(complete.progressPayload, WIDTH, HEIGHT)
                    assertFalse(progress.hasPending)
                    assertEquals(engine.firstProgress.regions.first(), progress.regions.first())
                    assertTrue(translator.isChapterTranslated(chapterDir))
                    val finalBytes = pageFile.readBytes()
                    assertFalse(partialBytes.contentEquals(finalBytes))
                    assertArrayEquals(finalBytes, streamBytes(page))
                    compareOriginal(scenario, page, engine.originalBytes, finalBytes)
                }
                assertArrayEquals(engine.originalBytes, File(directory, ".yakuyomi/001.orig.png").readBytes())
                assertTrue(manager.queueState.value.isEmpty())
                assertTrue(downloads.queueState.value.isEmpty())
                assertFalse(manager.isPendingTranslate(chapter.id))
                report.put("partialAndCompleteToastsObserved", true).put("reopenedPendingWithoutInference", true)
                    .put("sameReopenedPageAndLoaderCompleted", true).put("originalComparisonPreserved", true)
                    .put("translateCalls", engine.translateCalls.get()).put("resumeCalls", engine.resumeCalls.get())
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val cleanupFailures = mutableListOf<Throwable>()
                suspend fun cleanup(label: String, action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (failure: Throwable) {
                        cleanupFailures += AssertionError(label, failure)
                    }
                }
                cleanup("Fixture queue cleanup failed") {
                    chapterId?.let(manager::cancelPendingTranslation)
                    waitFor("fixture translation cleanup", 10000) {
                        TranslationBusy.active.value == 0 && manager.queueState.value.isEmpty()
                    }
                }
                cleanup("Scripted engine cleanup failed") {
                    service.shutdown() // Ordinary work can rebuild after the scripted generation is cleared.
                    if (engineInstalled) assertTrue(engine.closed.get()) else engine.close()
                }
                cleanup("Fixture chapter cleanup failed") {
                    chapterId?.let { chapters.removeChaptersWithIds(listOf(it)) }
                }
                cleanup("Fixture manga cleanup failed") {
                    mangaId?.let { id ->
                        val created = mangas.getMangaById(id)
                        assertEquals(LocalSource.ID, created.source)
                        assertEquals(title, created.url)
                        val driver = Injekt.get<SqlDriver>()
                        val removed = driver.execute(
                            null,
                            "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?",
                            3,
                        ) {
                            bindLong(0, id)
                            bindLong(1, LocalSource.ID)
                            bindString(2, title)
                        }.await()
                        assertEquals(1L, removed)
                        driver.notifyListeners("mangas", "chapters", "history")
                    }
                }
                cleanup("Effective storage restoration failed") {
                    waitFor("restored local storage") {
                        storage.getLocalSourceDirectory()?.uri == previousLocalDirectory
                    }
                }
                cleanup("Fixture file cleanup failed") {
                    assertTrue(root.deleteRecursively()) // Only this invocation's private synthetic root.
                }
                if (cleanupFailures.isNotEmpty()) {
                    val prior = primaryFailure
                    if (prior != null) {
                        cleanupFailures.forEach(prior::addSuppressed)
                    } else {
                        throw AssertionError("Partial reader fixture cleanup failed").apply {
                            cleanupFailures.forEach(::addSuppressed)
                        }
                    }
                }
            }
        }
        report.put("passed", true).put("settingsAndFixtureCleanupVerified", true)
        reportFile.writeText(report.toString(2))
    }

    private fun configure(settings: OnlineReaderSettings, root: File) {
        val prefs = Injekt.get<TranslationPreferences>()
        val reader = Injekt.get<ReaderPreferences>()
        val base = Injekt.get<BasePreferences>()
        settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
        settings.set(base.shownOnboardingFlow, true)
        settings.set(base.incognitoMode, false)
        settings.set(base.downloadedOnly, false)
        settings.set(prefs.provider, "custom")
        settings.set(prefs.apiBase, "https://example.invalid/v1/")
        settings.set(prefs.apiKeyFor("custom"), "")
        settings.set(prefs.model, "scripted-partial-reader")
        settings.set(prefs.sourceLangName, "Japanese")
        settings.set(prefs.targetLangName, "Korean")
        settings.set(prefs.orientation, "horizontal")
        settings.set(prefs.translationMasterEnabled, true)
        settings.set(prefs.translationEnabled, false)
        settings.set(prefs.liveTranslate, false)
        settings.set(prefs.inpaintMethod, "boxfill")
        settings.set(prefs.keepMaterials, true)
        settings.set(prefs.privacyAcknowledged, true)
        settings.set(prefs.nightReadEnabled, false)
        settings.set(reader.nightReadMode, false)
        settings.set(reader.landscapeZoom, false)
        settings.set(reader.dualPageSplitPaged, false)
        settings.set(reader.pageTransitions, false)
        settings.set(reader.cropBorders, false)
        settings.set(reader.showNavigationOverlayNewUser, false)
        settings.set(reader.showNavigationOverlayOnStart, false)
    }

    private fun installScriptedEngine(service: TranslationEngineService, engine: TranslationEngine) {
        val signature = TranslationEngineService::class.java.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        ).apply { isAccessible = true }.invoke(service, "boxfill", Injekt.get<TranslationPreferences>().snapshot())
        fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        field("engine").set(service, engine)
        field("builtSignature").set(service, signature)
        field("warmedUp").setBoolean(service, true)
    }

    private fun ready(scenario: ActivityScenario<ReaderActivity>): ReaderActivity {
        lateinit var activity: ReaderActivity
        scenario.onActivity { activity = it }
        waitFor("real local reader") {
            val chapter = activity.viewModel.state.value.viewerChapters?.currChapter
            chapter?.pageLoader is DirectoryPageLoader && chapter.pages?.size == 1 &&
                chapter.pages!!.single().status == Page.State.Ready
        }
        return activity
    }

    /** Opens the real dialog; only its visible accessibility action invokes translation/comparison. */
    private fun pageAction(scenario: ActivityScenario<ReaderActivity>, page: ReaderPage, action: StringResource) {
        lateinit var label: String
        scenario.onActivity {
            label = it.stringResource(action)
            it.viewModel.openPageDialog(page)
        }
        val forbidden = when (action) {
            MR.strings.reader_show_original -> context.stringResource(MR.strings.reader_show_translation)
            MR.strings.reader_show_translation -> context.stringResource(MR.strings.reader_show_original)
            else -> context.stringResource(MR.strings.reader_stop_chapter_translate)
        }
        checkOnlineReaderAction(context.packageName, label, forbidden, click = true, beforeClick = {})
    }

    private fun expectToast(label: String, engine: ScriptedEngine, action: () -> Unit) {
        val event = try {
            instrumentation.uiAutomation.executeAndWaitForEvent(
                { action() },
                { observed ->
                    observed.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                        observed.text.any { it.toString() == label }
                },
                12000,
            )
        } catch (failure: Exception) {
            engine.failure.get()?.let {
                throw AssertionError("Scripted engine failed before the expected reader toast", it)
            }
            throw AssertionError("The actual reader did not announce its operation result toast", failure)
        }
        @Suppress("DEPRECATION")
        event.recycle()
    }

    private fun compareOriginal(
        scenario: ActivityScenario<ReaderActivity>,
        page: ReaderPage,
        original: ByteArray,
        translated: ByteArray,
    ) {
        pageAction(scenario, page, MR.strings.reader_show_original)
        waitFor("original comparison") { page.isOriginalPreview }
        assertArrayEquals(original, streamBytes(page))
        pageAction(scenario, page, MR.strings.reader_show_translation)
        waitFor("return to current translation") { !page.isOriginalPreview }
        assertArrayEquals(translated, streamBytes(page))
    }

    private fun journal(directory: File) = PartialPageStore.forUniFile(
        context,
        checkNotNull(UniFile.fromFile(directory)),
        checkNotNull(UniFile.fromFile(File(directory, ".yakuyomi"))),
    )

    private fun streamBytes(page: ReaderPage) = checkNotNull(page.displayStream).invoke().use { it.readBytes() }

    private fun waitFor(label: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < end) { "Partial reader timed out: $label" }
            SystemClock.sleep(50)
        }
    }

    private class ScriptedEngine(val originalBytes: ByteArray) : TranslationEngine {
        override val translationContext = TranslationContext("c".repeat(64))
        val translateCalls = AtomicInteger()
        val resumeCalls = AtomicInteger()
        val closed = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val firstProgress = progress(complete = false)
        private val originalPixels = checkNotNull(
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size),
        ).let {
            try {
                pixelDigest(it)
            } finally {
                it.recycle()
            }
        }

        override suspend fun translatePage(page: Bitmap): PageResult = checked {
            assertEquals(1, translateCalls.incrementAndGet())
            assertArrayEquals(originalPixels, pixelDigest(page))
            PageResult.Partial(render(page, complete = false), stats(1), resultAnalysis(firstProgress))
        }

        override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult = checked {
            assertEquals(1, resumeCalls.incrementAndGet())
            assertArrayEquals(originalPixels, pixelDigest(original))
            assertEquals(firstProgress, analysis.progress)
            val maskBefore = pixelDigest(analysis.mask)
            val completed = progress(complete = true)
            val result = PageResult.Translated(render(original, complete = true), stats(2), resultAnalysis(completed))
            assertArrayEquals(maskBefore, pixelDigest(analysis.mask))
            result
        }

        private inline fun checked(block: () -> PageResult): PageResult = try {
            check(!closed.get())
            block()
        } catch (error: Throwable) {
            failure.set(error)
            throw error
        }

        private fun resultAnalysis(progress: PageProgress): PageAnalysis {
            val mask = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            return PageAnalysis(mask, progress.toTextRegions(), progress)
        }

        override fun warmUp() = Unit
        override fun close() {
            closed.set(true)
        }
    }

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 960

        fun stats(kept: Int) = PageStats(2, 2, kept, 0, 0, 0, 0, 0)

        fun progress(complete: Boolean): PageProgress = PageProgress(
            WIDTH,
            HEIGHT,
            (0..1).map { id ->
                val top = if (id == 0) 180f else 580f
                val pending = id == 1 && !complete
                RegionProgress(
                    id, if (pending) RegionState.OCR_PENDING else RegionState.TRANSLATED,
                    listOf(
                        OcrLineProgress(
                            listOf(Pt(70f, top), Pt(650f, top), Pt(650f, top + 90f), Pt(70f, top + 90f)),
                            1f,
                            "h",
                            if (pending) {
                                ""
                            } else if (id == 0) {
                                "こんにちは"
                            } else {
                                "ありがとう"
                            },
                        ),
                    ),
                    "h", cx = 360f, cy = top + 45f, boxW = 580f, boxH = 90f,
                    translatedText = if (pending) {
                        ""
                    } else if (id == 0) {
                        "안녕하세요"
                    } else {
                        "감사합니다"
                    },
                )
            },
        )

        fun sample(): ByteArray {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 54f
                }
                canvas.drawText("こんにちは", 80f, 245f, paint)
                canvas.drawText("ありがとう", 80f, 645f, paint)
                return ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        fun render(original: Bitmap, complete: Boolean): Bitmap = checkNotNull(
            original.copy(Bitmap.Config.ARGB_8888, true),
        ).also {
            val canvas = Canvas(it)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRect(60f, 170f, 660f, 280f, paint)
            if (complete) canvas.drawRect(60f, 570f, 660f, 680f, paint)
            paint.color = Color.BLACK
            paint.textSize = 54f
            canvas.drawText("안녕하세요", 80f, 245f, paint)
            if (complete) canvas.drawText("감사합니다", 80f, 645f, paint)
        }

        fun pixelDigest(bitmap: Bitmap): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (pixel in row) {
                    digest.update((pixel ushr 24).toByte())
                    digest.update((pixel ushr 16).toByte())
                    digest.update((pixel ushr 8).toByte())
                    digest.update(pixel.toByte())
                }
            }
            return digest.digest()
        }
    }
}
