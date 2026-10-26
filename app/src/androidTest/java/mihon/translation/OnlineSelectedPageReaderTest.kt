package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.translation.PageProcessingReason
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.model.TranslationFailureCode
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.OnlineSelectedPageFixture
import eu.kanade.tachiyomi.ui.reader.loader.OnlineTranslatedPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.FailureKind
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.ProcessingFailure
import li.joye.yakuyomi.engine.ProcessingStage
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Real ReaderActivity + real localhost source/cache/storage, scripted inference only.
 * Opt in with onlineSelectedPageReader=true in the isolated audit app. No native OCR, remote LLM,
 * Hitomi, or whole-chapter download is performed here. Existing OnlineReaderFlowTest retains the
 * explicit whole-download/real-server contract; LocalReaderTranslationTest covers local/archive.
 */
@RunWith(AndroidJUnit4::class)
class OnlineSelectedPageReaderTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val renderChecks = JSONArray()
    private val resultNoticeChecks = JSONArray()
    private var lastResultRetryVersion = 0L

    @Test(timeout = 300_000)
    fun currentPageMenusTranslateStopAndRefreshWithoutDownloadingTheChapter() = runBlocking<Unit> {
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "ISOLATED_AUDIT_REQUIRED" }
        check(InstrumentationRegistry.getArguments().getString("onlineSelectedPageReader") == "true") {
            "ONLINE_SELECTED_PAGE_EXPLICIT_OPT_IN_REQUIRED"
        }
        val scope = OnlineReaderFlowScope(context)
        val fake = SelectedPageEngine()
        val cacheGuard = CacheGuard(File(context.cacheDir.canonicalFile, "online-reader-pages-v1"))
        val report = JSONObject().put("success", false).put("pageCount", PAGE_COUNT)
            .put("source", "neutral localhost HTTP fixture")
            .put("inference", "scripted engine; no native OCR or remote translation server")
            .put("navigation", "real reader controller; long-press and action clicks use actual UI")
            .put("renderChecks", renderChecks)
            .put("manualResultNotices", resultNoticeChecks)
        var primaryFailure: Throwable? = null
        var prepared = false
        val unrelatedPendingId = Long.MIN_VALUE + 1964
        try {
            scope.prepare()
            prepared = true
            configure(scope)
            installFake(scope.engine, scope.preferences, fake)
            val samples = List(5, ::sample)
            val patterns = (0..4).flatMap { index -> listOf(markerPattern(index, false), markerPattern(index, true)) }
            assertEquals("Every source and translated fixture has a distinct marker pattern", 10, patterns.toSet().size)
            assertTrue("Every pattern contains both black and white", patterns.all { it in 1..14 })
            report.put(
                "fixtureSamples",
                JSONArray(
                    samples.mapIndexed { index, bytes ->
                        JSONObject().put("index", index).put("sourceSha256", sha(bytes)).put("sourceBytes", bytes.size)
                            .put("sourceArgb", originalColor(index)).put("translatedArgb", translatedColor(index))
                            .put("sourceMarkerPattern", markerPattern(index, false))
                            .put("translatedMarkerPattern", markerPattern(index, true))
                    },
                ),
            )
            val originals = List(PAGE_COUNT) { samples[it.coerceAtMost(4)] }
            val server = scope.openServer(originals)
            val mangaRepository = Injekt.get<MangaRepository>()
            val manga = mangaRepository.insertNetworkManga(
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
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter",
                        name = "Fixture",
                        chapterNumber = 1.0,
                    ),
                ),
            ).single()
            scope.recordOwned(manga, chapter)
            mangaRepository.update(
                MangaUpdate(
                    manga.id,
                    viewerFlags = (
                        ReadingMode.LEFT_TO_RIGHT.flagValue or
                            ReaderOrientation.LOCKED_PORTRAIT.flagValue
                        ).toLong(),
                ),
            )
            val translator = PageTranslator(context)
            scope.translations.markForTranslate(unrelatedPendingId)
            fun assertNoWholeChapterWork(activity: ReaderActivity) {
                fake.failure.get()?.let { throw AssertionError("SCRIPTED_INFERENCE_FAILED", it) }
                server.failure?.let { throw AssertionError("FIXTURE_HTTP_FAILED", it) }
                assertTrue(scope.downloads.queueState.value.isEmpty())
                assertFalse(scope.downloads.isRunning)
                assertTrue(scope.translations.queueState.value.isEmpty())
                assertFalse(scope.translations.isPendingTranslate(chapter.id))
                assertTrue(
                    "Current-page Stop must preserve unrelated work",
                    scope.translations.isPendingTranslate(
                        unrelatedPendingId,
                    ),
                )
                assertFalse(activity.viewModel.state.value.translationDownloadPending)
                assertEquals(null, activity.viewModel.state.value.liveTranslateProgress)
                assertEquals(
                    null,
                    scope.provider.findChapterDir(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.title,
                        server.source,
                    ),
                )
                assertEquals(0, scope.cache.getTranslatedCount(manga))
            }

            scope.launchReader(ReaderActivity.newIntent(context, manga.id, chapter.id)).use { scenario ->
                lateinit var activity: ReaderActivity
                scenario.onActivity { activity = it }
                waitFor("complete remote page list") {
                    activity.viewModel.state.value.viewerChapters?.currChapter?.let {
                        it.pageLoader is OnlineTranslatedPageLoader && it.pages?.size == PAGE_COUNT &&
                            it.pages!![0].status == Page.State.Ready
                    } == true
                }
                val readerChapter = activity.viewModel.state.value.viewerChapters!!.currChapter
                val pages = readerChapter.pages!!
                val loader = readerChapter.pageLoader as OnlineTranslatedPageLoader
                cacheGuard.own(manga, chapter, pages)
                awaitStored(loader, pages[0])
                assertEquals("Live translation is off", 0, fake.calls.get())
                assertArrayEquals(originals[0], stream(pages[0]))
                assertNoWholeChapterWork(activity)

                // The full-chapter action stays explicit and accurately names its download cost.
                openActions(scenario, pages[0])
                checkAction(
                    MR.strings.reader_download_translate_chapter,
                    MR.strings.reader_stop_chapter_translate,
                    false,
                )
                dismissDialog(activity)

                val firstReload = pages[0].reloadFlow.value
                // Consume this earlier manual result before later actions can observe its queued toast.
                expectResultToast(MR.strings.reader_translate_page_done, fake, pages[0]) {
                    pageAction(
                        scenario,
                        pages[0],
                        MR.strings.reader_translate_this_page,
                        MR.strings.reader_stop_page_translate,
                    )
                }
                awaitTranslated(loader, pages[0], translator, firstReload)
                awaitRendered(scenario, pages[0], translated = true)
                assertEquals(listOf(0), fake.inputPages.toList())
                assertNoWholeChapterWork(activity)
                val firstStored = checkNotNull(loader.resolvePage(pages[0]))
                val translatedBytes = firstStored.pageFile.openInputStream().use { it.readBytes() }
                assertFalse(originals[0].contentEquals(translatedBytes))
                assertArrayEquals(translatedBytes, stream(pages[0]))
                pageAction(scenario, pages[0], MR.strings.reader_show_original, MR.strings.reader_show_translation)
                waitFor("original comparison") { pages[0].isOriginalPreview }
                assertArrayEquals(originals[0], stream(pages[0]))
                awaitRendered(scenario, pages[0], translated = false)
                pageAction(scenario, pages[0], MR.strings.reader_show_translation, MR.strings.reader_show_original)
                waitFor("translated comparison") { !pages[0].isOriginalPreview }
                assertArrayEquals(translatedBytes, stream(pages[0]))
                awaitRendered(scenario, pages[0], translated = true)
                pageAction(
                    scenario,
                    pages[0],
                    MR.strings.reader_processing_record,
                    MR.strings.reader_stop_page_translate,
                )
                waitFor("exact private page processing record") {
                    (activity.viewModel.state.value.dialog as? ReaderViewModel.Dialog.ProcessingSummary)
                        ?.let { !it.isLoading && it.details != null } == true
                }
                val summary = activity.viewModel.state.value.dialog as ReaderViewModel.Dialog.ProcessingSummary
                val details = checkNotNull(summary.details)
                assertEquals(PageProcessingReason.TRANSLATED, details.outcome.reason)
                assertEquals(1, details.outcome.counts!!.translatedRegions)
                dismissProcessingRecordDialog(activity)

                // Enabling live mode processes only the newly selected page; HTTP prefetch is free
                // to fetch neighbors, but it must never schedule them for OCR or DownloadManager.
                scope.set(scope.preferences.liveTranslate, true)
                select(scenario, pages[1])
                waitFor("selected page automatic inference") { fake.entered[1].count == 0L }
                val stoppedStored = awaitStored(loader, pages[1])
                assertEquals(1, activity.viewModel.state.value.onlinePageIndex)
                assertEquals(1, activity.viewModel.state.value.onlinePageProgress!!.total)
                assertEquals(listOf(0, 1), fake.inputPages.toList())
                assertNoWholeChapterWork(activity)
                pageAction(
                    scenario,
                    pages[1],
                    MR.strings.reader_stop_page_translate,
                    MR.strings.reader_translate_this_page,
                )
                waitFor("current-page Stop registered before native return") {
                    loader.javaClass.getDeclaredField("requested").apply { isAccessible = true }.get(loader) == null
                }
                fake.release[1].countDown()
                waitFor("stopped inference drains") {
                    fake.active.get() == 0 && TranslationBusy.active.value == 0 &&
                        activity.viewModel.state.value.onlinePageProgress == null
                }
                assertEquals(2, fake.calls.get())
                assertFalse(translator.isPageTranslated(stoppedStored.chapterDir, stoppedStored.pageFile.name!!))
                assertArrayEquals(originals[1], stoppedStored.pageFile.openInputStream().use { it.readBytes() })
                assertArrayEquals(originals[1], stream(pages[1]))
                awaitRendered(scenario, pages[1], translated = false)
                assertNoWholeChapterWork(activity)

                // A user retry overrides Stop, still using the same reader, page and loader.
                val secondReload = pages[1].reloadFlow.value
                // Consume this earlier manual result before later actions can observe its queued toast.
                expectResultToast(MR.strings.reader_translate_page_done, fake, pages[1]) {
                    pageAction(
                        scenario,
                        pages[1],
                        MR.strings.reader_translate_this_page,
                        MR.strings.reader_stop_page_translate,
                    )
                }
                awaitTranslated(loader, pages[1], translator, secondReload)
                awaitRendered(scenario, pages[1], translated = true)
                assertEquals(listOf(0, 1, 1), fake.inputPages.toList())

                // Non-cooperative native-like work from the old selection must finish before the
                // next selected page enters the service. Its late result must not replace either.
                select(scenario, pages[2])
                waitFor("old selection enters inference") { fake.entered[3].count == 0L }
                val cancelledStored = awaitStored(loader, pages[2])
                waitFor("old selection has active reader progress") {
                    activity.viewModel.state.value.onlinePageIndex == 2 &&
                        activity.viewModel.state.value.onlinePageProgress != null
                }
                val activeProgress = checkNotNull(activity.viewModel.state.value.onlinePageProgress)
                // This is a model callback positive control, not an additional physical gesture:
                // a repeated callback for the same page must not erase its active indicator.
                scenario.onActivity {
                    it.viewModel.onPageSelected(pages[2])
                    assertEquals(activeProgress, it.viewModel.state.value.onlinePageProgress)
                    assertEquals(2, it.viewModel.state.value.onlinePageIndex)
                }
                select(scenario, pages[3])
                // Observe ownership invalidation before releasing the non-cooperative old call.
                // currentPage changes before the asynchronous selection flow necessarily drains.
                waitFor("new selection invalidates old inference") {
                    loader.javaClass.getDeclaredField("selectedPage")
                        .apply { isAccessible = true }.get(loader) === pages[3]
                }
                assertEquals(4, fake.calls.get())
                assertEquals(1, fake.active.get())
                // The new selected page has no processing yet: the old native-like call is
                // still held. Its previously accepted progress must already be cleared.
                assertEquals(null, activity.viewModel.state.value.onlinePageProgress)
                assertEquals(null, activity.viewModel.state.value.onlinePageIndex)
                fake.release[3].countDown()
                awaitTranslated(loader, pages[3], translator, 0)
                awaitRendered(scenario, pages[3], translated = true)
                assertEquals(listOf(0, 1, 1, 2, 3), fake.inputPages.toList())
                assertEquals("Inference must drain before the next selected page", 1, fake.peak.get())
                assertFalse(translator.isPageTranslated(cancelledStored.chapterDir, cancelledStored.pageFile.name!!))
                assertArrayEquals(originals[2], cancelledStored.pageFile.openInputStream().use { it.readBytes() })

                scope.set(scope.preferences.liveTranslate, false)
                select(scenario, pages.last())
                awaitStored(loader, pages.last())
                awaitRendered(scenario, pages.last(), translated = false)
                assertEquals(PAGE_COUNT, activity.viewModel.state.value.currentPage)
                assertEquals(PAGE_COUNT, readerChapter.pages!!.size)
                assertEquals(5, fake.calls.get())
                scenario.onActivity { assertSame("Reader Activity must remain alive", activity, it) }
                assertSame(loader, readerChapter.pageLoader)
                assertNoWholeChapterWork(activity)

                // A genuine engine Skipped result is a normal manual outcome, even though this
                // fresh forced attempt deliberately creates no translated-completion manifest.
                select(scenario, pages[4])
                awaitStored(loader, pages[4])
                expectResultToast(MR.strings.reader_translate_page_skipped, fake, pages[4]) {
                    pageAction(
                        scenario,
                        pages[4],
                        MR.strings.reader_translate_this_page,
                        MR.strings.reader_stop_page_translate,
                    )
                }
                awaitPageTerminal(loader, pages[4], PageTranslator.SinglePageResult.SKIPPED)
                assertNull(loader.progress.value?.failureCode)
                assertUnchangedPage(loader, pages[4], translator, originals[4])
                awaitRendered(scenario, pages[4], translated = false)
                assertProcessingReason(scenario, pages[4], PageProcessingReason.NO_TEXT)
                assertEquals(6, fake.calls.get())

                // Real reader selections with live mode enabled must reuse the terminal skipped
                // attempt. A normal no-text outcome must not become an automatic retry loop.
                scope.set(scope.preferences.liveTranslate, true)
                select(scenario, pages[3])
                awaitPageTerminal(loader, pages[3], PageTranslator.SinglePageResult.TRANSLATED)
                awaitRendered(scenario, pages[3], translated = true)
                select(scenario, pages[4])
                awaitPageTerminal(loader, pages[4], PageTranslator.SinglePageResult.SKIPPED)
                OnlineSelectedPageFixture.settled(loader)
                assertEquals("Automatic revisit must not repeat a finished skipped attempt", 6, fake.calls.get())

                // Explicit retry remains available and a policy-kept result is also neutral.
                expectResultToast(MR.strings.reader_translate_page_skipped, fake, pages[4]) {
                    pageAction(
                        scenario,
                        pages[4],
                        MR.strings.reader_translate_this_page,
                        MR.strings.reader_stop_page_translate,
                    )
                }
                awaitPageTerminal(loader, pages[4], PageTranslator.SinglePageResult.SKIPPED)
                assertNull(loader.progress.value?.failureCode)
                assertUnchangedPage(loader, pages[4], translator, originals[4])
                assertProcessingReason(scenario, pages[4], PageProcessingReason.OCR_POLICY_KEPT)

                // A later real failure on that same source must not borrow the saved kept summary
                // or its earlier neutral toast. The first result notice must be the failure notice.
                expectResultToast(MR.strings.reader_translate_page_failed, fake, pages[4]) {
                    pageAction(
                        scenario,
                        pages[4],
                        MR.strings.reader_translate_this_page,
                        MR.strings.reader_stop_page_translate,
                    )
                }
                awaitPageTerminal(loader, pages[4], PageTranslator.SinglePageResult.NOT_TRANSLATED)
                assertEquals(TranslationFailureCode.UNKNOWN, loader.progress.value?.failureCode)
                assertUnchangedPage(loader, pages[4], translator, originals[4])
                awaitRendered(scenario, pages[4], translated = false)
                OnlineSelectedPageFixture.settled(loader)
                assertEquals(listOf(0, 1, 1, 2, 3, 4, 4, 4), fake.inputPages.toList())

                // A typed request-boundary timeout keeps the same failure/storage semantics,
                // but the actual public action must announce the predefined specific reason.
                expectResultToast(MR.strings.translation_failure_server_timeout, fake, pages[4]) {
                    pageAction(
                        scenario,
                        pages[4],
                        MR.strings.reader_translate_this_page,
                        MR.strings.reader_stop_page_translate,
                    )
                }
                awaitPageTerminal(loader, pages[4], PageTranslator.SinglePageResult.NOT_TRANSLATED)
                assertEquals(TranslationFailureCode.SERVER_TIMEOUT, loader.progress.value?.failureCode)
                assertUnchangedPage(loader, pages[4], translator, originals[4])
                awaitRendered(scenario, pages[4], translated = false)
                OnlineSelectedPageFixture.settled(loader)
                assertEquals(listOf(0, 1, 1, 2, 3, 4, 4, 4, 4), fake.inputPages.toList())
                assertNoWholeChapterWork(activity)
                val imageRequests = server.requests.filter { it.startsWith("/image/") }
                assertTrue("The last page remains navigable", "/image/$PAGE_COUNT.png" in imageRequests)
                assertTrue("Selected-page translation must not fetch an entire chapter", imageRequests.size < 64)
                report.put("inputPageIndices", JSONArray(fake.inputPages)).put("maxInFlight", fake.peak.get())
                    .put("imageRequests", imageRequests.size).put("wholeDownloadQueueEntries", 0)
                    .put("sameReaderRenderedManualAndAutomaticResults", true)
                    .put("originalAndProcessingRecordMenuVerified", true)
                    .put("cancelledSelectionResultDiscarded", true)
                    .put("samePageSelectionCallbackPreservedActiveProgress", true)
                    .put("oldSelectionProgressClearedBeforePriorInferenceDrained", true)
                    .put("manualNoTextAndPolicyKeptNeutralNotFailure", true)
                    .put("actualFailureNotInferredFromPriorSummary", true)
                    .put("automaticSkippedRevisitWithoutAnotherInference", true)
                    .put("typedTimeoutManualNoticeVerified", true)
            }
            report.put("functionalStagesPassed", true)
        } catch (failure: Throwable) {
            primaryFailure = failure
            report.put("failureType", failure.javaClass.name).put("failureStructure", onlineReaderFlowFailure(failure))
        } finally {
            fake.release.forEach { it.countDown() }
            runCatching { scope.saveReport(report) }
            withContext(NonCancellable) {
                if (prepared) scope.translations.cancelPendingTranslation(unrelatedPendingId)
                try {
                    report.put("cleanup", scope.close())
                    cacheGuard.close()
                    if (fake.calls.get() > 0) {
                        assertTrue(fake.closed.get())
                        assertEquals(0, fake.active.get())
                        assertTrue(
                            "All scripted outputs and masks must be recycled",
                            fake.outputs.all { it.isRecycled },
                        )
                    }
                } catch (cleanup: Throwable) {
                    primaryFailure?.addSuppressed(cleanup) ?: run { primaryFailure = cleanup }
                    report.put("cleanupFailureType", cleanup.javaClass.name)
                }
            }
        }
        report.put("success", primaryFailure == null)
            .put("scriptedResultDiagnostics", JSONArray(fake.resultDiagnostics))
        emitOnlineReaderFlow("onlineSelectedPageReaderEvidence", report)
        primaryFailure?.let { throw it }
    }

    private fun configure(scope: OnlineReaderFlowScope) {
        val prefs = scope.preferences
        scope.set(prefs.provider, "custom")
        // A signature-only placeholder. The scripted engine never constructs a network client.
        scope.set(prefs.apiBase, "http://127.0.0.1:1/v1/")
        scope.set(prefs.model, "neutral-scripted-selected-page")
        scope.set(prefs.inpaintMethod, "boxfill")
        scope.set(prefs.liveInpaintMethod, "boxfill")
        scope.set(prefs.translationEnabled, false)
        scope.set(prefs.liveTranslate, false)
        scope.set(prefs.keepMaterials, true)
        scope.set(prefs.privacyAcknowledged, true)
        scope.set(prefs.nightReadEnabled, false)
        scope.set(prefs.sourceLangName, "Japanese")
        scope.set(prefs.targetLangName, "Korean")
        scope.set(prefs.orientation, "horizontal")
        scope.set(prefs.liveTranslateCategories, emptySet())
        scope.set(prefs.liveTranslateCategoriesExclude, emptySet())
        val download = scope.downloadPreferences
        scope.set(download.downloadOnlyOverWifi, false)
        scope.set(download.autoDownloadWhileReading, 0)
        scope.set(download.saveChaptersAsCBZ, false)
        scope.set(download.splitTallImages, false)
        scope.set(download.removeAfterReadSlots, -1)
        scope.set(download.removeAfterMarkedAsRead, false)
        val reader = scope.readerPreferences
        scope.set(reader.fullscreen, false)
        scope.set(reader.landscapeZoom, false)
        scope.set(reader.showNavigationOverlayNewUser, false)
        scope.set(reader.showNavigationOverlayOnStart, false)
        scope.set(prefs.translationMasterEnabled, true)
    }

    private fun installFake(service: TranslationEngineService, prefs: TranslationPreferences, fake: TranslationEngine) {
        fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
        assertEquals(null, field("engine").get(service))
        val signature = TranslationEngineService::class.java.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        ).apply { isAccessible = true }.invoke(service, "boxfill", prefs.snapshot())
        field("engine").set(service, fake)
        field("builtSignature").set(service, signature)
        field("warmedUp").setBoolean(service, true)
        @Suppress("UNCHECKED_CAST")
        (field("_warm").get(service) as MutableStateFlow<Boolean>).value = true
    }

    private fun awaitStored(
        loader: OnlineTranslatedPageLoader,
        page: ReaderPage,
    ): OnlineTranslatedPageLoader.StoredPage {
        waitFor("selected page captured") { loader.resolvePage(page) != null }
        return checkNotNull(loader.resolvePage(page))
    }

    private fun awaitTranslated(
        loader: OnlineTranslatedPageLoader,
        page: ReaderPage,
        translator: PageTranslator,
        reload: Int,
    ) {
        waitFor("selected page translated and refreshed") {
            loader.resolvePage(page)?.let {
                translator.isPageTranslated(it.chapterDir, it.pageFile.name!!) &&
                    page.reloadFlow.value > reload && page.status == Page.State.Ready &&
                    TranslationBusy.active.value == 0
            } == true
        }
    }

    private fun awaitPageTerminal(
        loader: OnlineTranslatedPageLoader,
        page: ReaderPage,
        result: PageTranslator.SinglePageResult,
    ) {
        val stage = if (result == PageTranslator.SinglePageResult.NOT_TRANSLATED) {
            OnlineTranslatedPageLoader.Stage.FAILED
        } else {
            OnlineTranslatedPageLoader.Stage.FINISHED
        }
        waitFor("selected page terminal outcome $result") {
            loader.progress.value?.let {
                it.pageIndex == page.index && it.stage == stage && it.result == result &&
                    TranslationBusy.active.value == 0 && page.status == Page.State.Ready
            } == true
        }
    }

    private fun assertUnchangedPage(
        loader: OnlineTranslatedPageLoader,
        page: ReaderPage,
        translator: PageTranslator,
        original: ByteArray,
    ) {
        val stored = checkNotNull(loader.resolvePage(page))
        val directory = File(checkNotNull(stored.chapterDir.uri.path))
        assertArrayEquals(original, File(directory, "source.bin").readBytes())
        assertArrayEquals(original, stored.pageFile.openInputStream().use { it.readBytes() })
        assertArrayEquals(original, stream(page))
        assertFalse(translator.isPageTranslated(stored.chapterDir, stored.pageFile.name!!))
        assertFalse(translator.isChapterTranslated(stored.chapterDir))
    }

    private fun assertProcessingReason(
        scenario: ActivityScenario<ReaderActivity>,
        page: ReaderPage,
        reason: PageProcessingReason,
    ) {
        pageAction(scenario, page, MR.strings.reader_processing_record, MR.strings.reader_stop_page_translate)
        lateinit var activity: ReaderActivity
        scenario.onActivity { activity = it }
        waitFor("saved skipped processing details") {
            (activity.viewModel.state.value.dialog as? ReaderViewModel.Dialog.ProcessingSummary)
                ?.let { !it.isLoading && it.details != null } == true
        }
        val details = checkNotNull(
            (activity.viewModel.state.value.dialog as ReaderViewModel.Dialog.ProcessingSummary).details,
        )
        assertEquals(reason, details.outcome.reason)
        val counts = checkNotNull(details.outcome.counts)
        assertEquals(0, counts.translatedRegions)
        assertEquals(0, counts.pendingRegions)
        val label = when (reason) {
            PageProcessingReason.NO_TEXT -> MR.strings.reader_processing_record_no_text
            PageProcessingReason.OCR_POLICY_KEPT -> MR.strings.reader_processing_record_ocr_kept
            else -> error("Unexpected scripted skipped reason")
        }
        checkAction(label, MR.strings.reader_translate_this_page, false)
        dismissProcessingRecordDialog(activity)
    }

    private fun expectResultToast(
        label: StringResource,
        engine: SelectedPageEngine,
        page: ReaderPage,
        action: () -> Unit,
    ) {
        val expected = context.stringResource(label)
        val results = setOf(
            context.stringResource(MR.strings.reader_translate_page_skipped),
            context.stringResource(MR.strings.reader_translate_page_failed),
            context.stringResource(MR.strings.reader_translate_page_done),
            context.stringResource(MR.strings.reader_translate_page_partial),
            context.stringResource(MR.strings.translation_failure_server_timeout),
        )
        val startedLabel = context.stringResource(MR.strings.reader_translating_page)
        val observedNotices = JSONArray()
        val check = JSONObject().put("expectedLabel", expected).put("pageIndex", page.index)
            .put("actionStartedUptimeMs", SystemClock.uptimeMillis()).put("matched", false)
            .put("observedNotices", observedNotices)
        resultNoticeChecks.put(check)
        fun recordState(key: String) {
            val progress = (page.chapter.pageLoader as? OnlineTranslatedPageLoader)?.progress?.value
            check.put(
                key,
                JSONObject().put("calls", engine.calls.get()).put("active", engine.active.get())
                    .put("busy", TranslationBusy.active.value)
                    .put("pageStatus", page.status.javaClass.simpleName)
                    .put("loaderPageIndex", progress?.pageIndex ?: JSONObject.NULL)
                    .put("loaderRetryVersion", progress?.retryVersion ?: JSONObject.NULL)
                    .put("loaderPageIsRequestedPage", progress?.page === page)
                    .put("loaderStage", progress?.stage?.name ?: JSONObject.NULL)
                    .put("loaderResult", progress?.result?.name ?: JSONObject.NULL)
                    .put("loaderFailureCode", progress?.failureCode?.wireValue ?: JSONObject.NULL),
            )
        }
        recordState("beforeAction")
        val event = try {
            instrumentation.uiAutomation.executeAndWaitForEvent(
                { action() },
                { observed ->
                    if (observed.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                        false
                    } else {
                        // Only known public fixture notices are recorded. Other accessibility text
                        // must not enter the report, and an unexpected result must still fail.
                        val allowed = observed.text.map { it.toString() }
                            .filter { it in results || it == startedLabel }.toSet()
                        if (allowed.isNotEmpty() && observedNotices.length() < 16) {
                            val packageName = observed.packageName?.toString().orEmpty()
                            observedNotices.put(
                                JSONObject().put("eventTimeUptimeMs", observed.eventTime)
                                    .put("receivedUptimeMs", SystemClock.uptimeMillis())
                                    .put(
                                        "package",
                                        packageName.takeIf { it == context.packageName || it == "android" } ?: "OTHER",
                                    )
                                    .put("packageSha256", sha(packageName.toByteArray()))
                                    .put("allowedLabels", JSONArray(allowed.toList()))
                                    .put("calls", engine.calls.get()).put("active", engine.active.get()),
                            )
                        }
                        allowed.any { it in results }
                    }
                },
                12_000,
            )
        } catch (failure: Exception) {
            check.put("waitFailureClass", failure.javaClass.name)
            recordState("atWaitFailure")
            engine.failure.get()?.let { throw AssertionError("SCRIPTED_INFERENCE_FAILED", it) }
            throw AssertionError("Reader did not announce its manual operation result", failure)
        }
        try {
            val actual = event.text.map { it.toString() }.filter { it in results }.toSet()
            check.put("allowedResultLabelsObserved", JSONArray(actual.toList()))
                .put("matched", actual == setOf(expected))
            recordState("atResultNotice")
            assertEquals(
                "The actual manual action must announce exactly its semantic outcome",
                setOf(expected),
                actual,
            )
            val progress = checkNotNull((page.chapter.pageLoader as? OnlineTranslatedPageLoader)?.progress?.value)
            assertTrue("A manual result must still belong to its exact reader page", progress.page === page)
            assertTrue(
                "Each new public manual action must expose a newer request token",
                progress.retryVersion > lastResultRetryVersion,
            )
            lastResultRetryVersion = progress.retryVersion
        } finally {
            @Suppress("DEPRECATION")
            event.recycle()
        }
    }

    private fun select(scenario: ActivityScenario<ReaderActivity>, page: ReaderPage) {
        scenario.onActivity { it.viewModel.state.value.viewer!!.moveToPage(page) }
        waitFor("reader selected page ${page.index}") {
            var selected = false
            scenario.onActivity { selected = it.viewModel.state.value.currentPage == page.index + 1 }
            selected
        }
    }

    private fun pageAction(
        scenario: ActivityScenario<ReaderActivity>,
        page: ReaderPage,
        label: StringResource,
        forbidden: StringResource,
    ) {
        openActions(scenario, page)
        checkAction(label, forbidden, true)
    }

    private fun checkAction(label: StringResource, forbidden: StringResource, click: Boolean) = checkOnlineReaderAction(
        context.packageName,
        context.stringResource(label),
        context.stringResource(forbidden),
        click,
        {},
    )

    private fun openActions(scenario: ActivityScenario<ReaderActivity>, page: ReaderPage) {
        awaitRendered(scenario, page, null)
        val bounds = Rect()
        scenario.onActivity {
            val holder = viewTree(it.window.decorView).filterIsInstance<PagerPageHolder>().single { item ->
                item.page === page && item.getGlobalVisibleRect(bounds) && bounds.width() >= item.width * 0.95f
            }
            check(holder.isShown)
        }
        val downTime = SystemClock.uptimeMillis()
        fun pointer(action: Int) {
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0,
            ).let { event ->
                try {
                    instrumentation.sendPointerSync(event)
                } finally {
                    event.recycle()
                }
            }
        }
        pointer(MotionEvent.ACTION_DOWN)
        try {
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        } finally {
            pointer(MotionEvent.ACTION_UP)
        }
        waitFor("real long-press page actions") {
            var open = false
            scenario.onActivity {
                open = (it.viewModel.state.value.dialog as? ReaderViewModel.Dialog.PageActions)?.page === page
            }
            open
        }
    }

    private fun dismissDialog(activity: ReaderActivity) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitFor("dialog dismissed") { activity.viewModel.state.value.dialog == null }
    }

    private fun dismissProcessingRecordDialog(activity: ReaderActivity) {
        // Details can finish before AlertDialog is composed/focused. Click its visible public
        // confirm action, as ProcessingSummaryReaderUiTest does, rather than sending an early Back.
        checkAction(MR.strings.action_ok, MR.strings.reader_translate_this_page, true)
        waitFor("processing record dialog dismissed") { activity.viewModel.state.value.dialog == null }
    }

    private data class MarkerProbe(val index: Int, val x: Int, val y: Int, val expectedArgb: Int)

    /** Read the actual hardware-composited fixture markers; no color tolerance or software fallback. */
    private fun awaitRendered(scenario: ActivityScenario<ReaderActivity>, page: ReaderPage, translated: Boolean?) {
        val fixtureIndex = page.index.coerceAtMost(4)
        val color = translated?.let { if (it) translatedColor(fixtureIndex) else originalColor(fixtureIndex) }
        val pattern = translated?.let { markerPattern(fixtureIndex, it) }
        val observations = JSONArray()
        val diagnostic = JSONObject().put("pageIndex", page.index).put("expectedArgb", color ?: JSONObject.NULL)
            .put("expectedMarkerPattern", pattern ?: JSONObject.NULL).put("oracle", "actual-compositor-four-markers")
            .put("initialReload", page.reloadFlow.value).put("matched", false)
            .put("exactArgbEqualityRequired", true).put("waitDeadlineMs", 25_000)
            .put("observations", observations).put("filesBefore", pageFileDiagnostics(page))
        renderChecks.put(diagnostic)
        var attempts = 0
        var lastFrame = JSONObject()
        try {
            waitFor("selected page pixels rendered") {
                attempts++
                var ready = false
                var probes = emptyList<MarkerProbe>()
                scenario.onActivity {
                    val state = it.viewModel.state.value
                    val targets = viewTree(it.window.decorView).filterIsInstance<PagerPageHolder>()
                        .filter { item -> item.page === page }.toList()
                    val holder = targets.firstOrNull { item ->
                        val visible = Rect()
                        item.getGlobalVisibleRect(visible) && visible.width() >= item.width * 0.95f
                    }
                    val image = holder?.let { item ->
                        viewTree(item).filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
                    }
                    lastFrame = JSONObject().put("currentPage", state.currentPage).put("pageIndex", page.index)
                        .put("pageStatus", page.status.javaClass.simpleName).put("reload", page.reloadFlow.value)
                        .put("originalPreview", page.isOriginalPreview).put("holderCount", targets.size)
                        .put("dialogAbsent", state.dialog == null)
                        .put("holderEligible", holder != null).put("subsamplingPresent", image != null)
                        .put("brightnessOverlay", state.brightnessOverlayValue)
                        .put("onlinePageIndex", state.onlinePageIndex ?: JSONObject.NULL)
                    targets.firstOrNull()?.let { item -> lastFrame.put("firstTargetHolder", viewGeometry(item)) }
                    holder?.let { item -> lastFrame.put("eligibleHolder", viewGeometry(item)) }
                    image?.let { item ->
                        lastFrame.put(
                            "subsampling",
                            viewGeometry(item).put("ready", item.isReady).put("imageLoaded", item.isImageLoaded)
                                .put("sourceWidth", item.sWidth).put("sourceHeight", item.sHeight)
                                .put(
                                    "scale",
                                    item.scale.toDouble().takeIf { value -> value.isFinite() } ?: JSONObject.NULL,
                                )
                                .put("decoderBitmap", decoderBitmapDiagnostics(item)),
                        )
                    }
                    if (holder != null && holder.width > 0 && holder.height > 0 &&
                        image?.isReady == true && image.isImageLoaded && state.currentPage == page.index + 1 &&
                        state.currentChapter === page.chapter && state.dialog == null &&
                        page.status == Page.State.Ready && image.sWidth == WIDTH && image.sHeight == HEIGHT
                    ) {
                        if (pattern == null) {
                            ready = true
                        } else {
                            val origin = IntArray(2)
                            image.getLocationOnScreen(origin)
                            val visible = Rect()
                            if (image.getLocalVisibleRect(visible)) {
                                visible.offset(origin[0], origin[1])
                                lastFrame.put("imageVisibleBoundsOnScreen", rectJson(visible))
                                val candidates = (0..3).mapNotNull { marker ->
                                    image.sourceToViewCoord(markerX(marker).toFloat(), markerY(marker).toFloat())
                                        ?.let { point ->
                                            MarkerProbe(
                                                marker,
                                                (origin[0] + point.x).roundToInt(),
                                                (origin[1] + point.y).roundToInt(),
                                                markerColor(pattern, marker),
                                            )
                                        }
                                }
                                val inside = candidates.size == 4 && candidates.all { point ->
                                    visible.contains(point.x, point.y)
                                }
                                lastFrame.put("allMarkersInsideVisibleImage", inside)
                                if (inside) probes = candidates
                            }
                        }
                    }
                }
                if (probes.size == 4) {
                    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        val pixels = JSONArray()
                        ready = probes.all { point ->
                            point.x in 0 until screenshot.width &&
                                point.y in 0 until screenshot.height
                        }
                        probes.forEach { point ->
                            val inside = point.x in 0 until screenshot.width && point.y in 0 until screenshot.height
                            val actual = if (inside) screenshot.getPixel(point.x, point.y) else null
                            pixels.put(
                                JSONObject().put("index", point.index).put("sourceX", markerX(point.index))
                                    .put("sourceY", markerY(point.index)).put("screenX", point.x)
                                    .put("screenY", point.y)
                                    .put("insideScreenshot", inside).put("expectedArgb", point.expectedArgb)
                                    .put("actualArgb", actual ?: JSONObject.NULL),
                            )
                            ready = ready && actual == point.expectedArgb
                        }
                        lastFrame.put("screenshotWidth", screenshot.width).put("screenshotHeight", screenshot.height)
                            .put("screenshotColorSpace", screenshot.colorSpace?.name ?: "absent")
                            .put("markerPixels", pixels).put("allFourMarkerPixelsExact", ready)
                    } finally {
                        screenshot.recycle()
                    }
                }
                if (observations.length() < 3) observations.put(lastFrame)
                ready
            }
            diagnostic.put("matched", true)
        } finally {
            diagnostic.put("attempts", attempts).put("lastFrame", lastFrame)
                .put("filesAfter", pageFileDiagnostics(page)).put("readerSettings", readerSettingsDiagnostics())
            if (pattern != null) {
                diagnostic.put("screenProbe", screenProbe(scenario, page))
                    .put("softwareHolderDrawProbe", softwareHolderDrawProbe(scenario, page))
            }
            try {
                emitOnlineReaderFlow("onlineSelectedPageRenderDiagnostic", diagnostic)
            } catch (error: Exception) {
                // Reporting must not replace the original pixel assertion/timeout failure.
                diagnostic.put("emissionFailureClass", error.javaClass.name)
            }
        }
    }

    /** Read only this synthetic page's exact cache authority. No paths, URLs or source text escape. */
    private fun pageFileDiagnostics(page: ReaderPage): JSONObject = diagnosticRead {
        val result = JSONObject().put("pageIndex", page.index).put("uriScheme", page.uri?.scheme ?: "absent")
        val online = page.chapter.pageLoader as? OnlineTranslatedPageLoader
        val stored = online?.resolvePage(page)
        result.put("exactPrivateAuthorityResolved", stored != null)
        if (stored != null) {
            result.put("storedOutput", imageBytesDiagnostics(stored.pageFile.openInputStream().use(::readBounded)))
            stored.chapterDir.findFile("source.bin")?.let {
                result.put("cachedSource", imageBytesDiagnostics(it.openInputStream().use(::readBounded)))
            }
        }
        page.displayStream?.let { result.put("displayStream", imageBytesDiagnostics(it().use(::readBounded))) }
        result
    }

    private fun imageBytesDiagnostics(bytes: ByteArray): JSONObject {
        val result = JSONObject().put("bytes", bytes.size).put("sha256", sha(bytes))
        val types = JSONArray()
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            var offset = 8
            while (offset + 12 <= bytes.size && types.length() < 32) {
                val length = ByteBuffer.wrap(bytes, offset, 4).int
                if (length < 0 || length > bytes.size - offset - 12) break
                types.put(bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII))
                offset += length + 12
            }
        }
        result.put("pngChunkTypes", types)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            result.put("defaultDecode", decoded?.let(::bitmapDiagnostics) ?: JSONObject.NULL)
        } finally {
            decoded?.recycle()
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val srgb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        try {
            result.put("explicitSrgbDecode", srgb?.let(::bitmapDiagnostics) ?: JSONObject.NULL)
        } finally {
            srgb?.recycle()
        }
        return result
    }

    private fun bitmapDiagnostics(bitmap: Bitmap): JSONObject {
        val result = JSONObject().put("width", bitmap.width).put("height", bitmap.height)
            .put("config", bitmap.config?.name ?: "absent").put("density", bitmap.density)
            .put("colorSpace", bitmap.colorSpace?.name ?: "absent").put("hasAlpha", bitmap.hasAlpha())
            .put("recycled", bitmap.isRecycled)
        if (!bitmap.isRecycled && bitmap.config != Bitmap.Config.HARDWARE) {
            result.put("centerArgb", bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                .put("cornerArgb", bitmap.getPixel(0, 0))
            if (bitmap.width == WIDTH && bitmap.height == HEIGHT) {
                result.put("markerArgb", JSONArray((0..3).map { bitmap.getPixel(markerX(it), markerY(it)) }))
            }
        }
        return result
    }

    private fun decoderBitmapDiagnostics(image: SubsamplingScaleImageView): JSONObject = diagnosticRead {
        val field = SubsamplingScaleImageView::class.java.getDeclaredField("bitmap").apply { isAccessible = true }
        val bitmap = field.get(image) as? Bitmap
        bitmap?.let(::bitmapDiagnostics) ?: JSONObject().put("present", false)
    }

    private fun readerSettingsDiagnostics(): JSONObject = diagnosticRead {
        val prefs = Injekt.get<ReaderPreferences>()
        val profile = TachiyomiImageDecoder.displayProfile
        JSONObject().put("imageScaleType", prefs.imageScaleType.get()).put("cropBorders", prefs.cropBorders.get())
            .put("colorFilter", prefs.colorFilter.get()).put("grayscale", prefs.grayscale.get())
            .put("invertedColors", prefs.invertedColors.get()).put("customBrightness", prefs.customBrightness.get())
            .put("nightReadMode", prefs.nightReadMode.get())
            .put("displayProfileConfigured", Injekt.get<BasePreferences>().displayProfile.get().isNotBlank())
            .put("decoderDisplayProfileBytes", profile?.size ?: 0)
            .put("decoderDisplayProfileSha256", profile?.let(::sha) ?: JSONObject.NULL)
    }

    private fun screenProbe(scenario: ActivityScenario<ReaderActivity>, page: ReaderPage): JSONObject = diagnosticRead {
        val bounds = Rect()
        var found = false
        scenario.onActivity {
            val holder = viewTree(it.window.decorView).filterIsInstance<PagerPageHolder>()
                .firstOrNull { item -> item.page === page && item.getGlobalVisibleRect(bounds) }
            if (holder != null) {
                val rootOrigin = IntArray(2)
                holder.rootView.getLocationOnScreen(rootOrigin)
                bounds.offset(rootOrigin[0], rootOrigin[1])
                found = true
            }
        }
        val result = JSONObject().put("targetBoundsFound", found)
        if (!found) return@diagnosticRead result
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            result.put("width", screenshot.width).put("height", screenshot.height)
                .put("colorSpace", screenshot.colorSpace?.name ?: "absent").put("centerX", bounds.centerX())
                .put("centerY", bounds.centerY())
            if (bounds.centerX() in 0 until screenshot.width && bounds.centerY() in 0 until screenshot.height) {
                result.put("centerArgb", screenshot.getPixel(bounds.centerX(), bounds.centerY()))
            }
        } finally {
            screenshot.recycle()
        }
        result
    }

    /** Retain the old software draw measurement as a diagnostic, never as the display oracle. */
    private fun softwareHolderDrawProbe(
        scenario: ActivityScenario<ReaderActivity>,
        page: ReaderPage,
    ): JSONObject = diagnosticRead {
        var result = JSONObject().put("targetHolderPresent", false)
        scenario.onActivity {
            val holder = viewTree(it.window.decorView).filterIsInstance<PagerPageHolder>()
                .firstOrNull { item -> item.page === page && item.isShown && item.width > 0 && item.height > 0 }
            if (holder != null) {
                val bitmap = Bitmap.createBitmap(holder.width, holder.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    holder.draw(canvas)
                    result = JSONObject().put("targetHolderPresent", true)
                        .put("canvasHardwareAccelerated", canvas.isHardwareAccelerated)
                        .put("centerArgb", bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                        .put("colorSpace", bitmap.colorSpace?.name ?: "absent")
                } finally {
                    bitmap.recycle()
                }
            }
        }
        result
    }

    private fun viewGeometry(view: View): JSONObject {
        val visible = Rect()
        return JSONObject().put("width", view.width).put("height", view.height).put("shown", view.isShown)
            .put("attached", view.isAttachedToWindow).put("alpha", view.alpha.toDouble())
            .put("layerType", view.layerType).put("hardwareAccelerated", view.isHardwareAccelerated)
            .put("visible", view.getGlobalVisibleRect(visible))
            .put("visibleBounds", rectJson(visible))
    }

    private fun rectJson(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

    private inline fun diagnosticRead(block: () -> JSONObject): JSONObject = try {
        block()
    } catch (error: Exception) {
        JSONObject().put("diagnosticFailureClass", error.javaClass.name)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return bytes.toByteArray()
            check(bytes.size() + count <= 2 * 1024 * 1024) { "Neutral render diagnostic exceeded byte bound" }
            bytes.write(buffer, 0, count)
        }
    }

    private fun viewTree(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(viewTree(view.getChildAt(index)))
    }

    private fun stream(page: ReaderPage) = checkNotNull(page.displayStream).invoke().use { it.readBytes() }

    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Selected-page reader timed out: $label" }
            SystemClock.sleep(50)
        }
    }

    /** Blocks like JNI: cancellation alone cannot release the owned input/output lifetime. */
    private class SelectedPageEngine : TranslationEngine {
        override val translationContext = TranslationContext("a".repeat(64))
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val closed = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val entered = List(9) { CountDownLatch(1) }
        val release = List(9) { CountDownLatch(if (it == 1 || it == 3) 1 else 0) }
        val inputPages = CopyOnWriteArrayList<Int>()
        val outputs = CopyOnWriteArrayList<Bitmap>()
        val resultDiagnostics = CopyOnWriteArrayList<JSONObject>()

        override suspend fun translatePage(page: Bitmap): PageResult {
            val index = calls.getAndIncrement()
            val running = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, running) }
            try {
                check(!closed.get() && index < 9)
                val expected = listOf(0, 1, 1, 2, 3, 4, 4, 4, 4)[index]
                assertEquals(originalColor(expected), page.getPixel(0, 0))
                (0..3).forEach { marker ->
                    assertEquals(
                        "Original marker $marker must identify selected source page $expected",
                        markerColor(markerPattern(expected, false), marker),
                        page.getPixel(markerX(marker), markerY(marker)),
                    )
                }
                inputPages += expected
                entered[index].countDown()
                check(release[index].await(45, TimeUnit.SECONDS)) { "Scripted inference gate timed out" }
                if (index >= 5) {
                    if (index == 7 || index == 8) {
                        val failure = if (index == 8) {
                            ProcessingFailure(ProcessingStage.TRANSLATE, FailureKind.TIMEOUT)
                        } else {
                            null
                        }
                        resultDiagnostics += JSONObject().put("callIndex", index).put("pageIndex", expected)
                            .put("result", "FAILED").put("typedStage", failure?.stage?.name ?: JSONObject.NULL)
                            .put("typedKind", failure?.kind?.name ?: JSONObject.NULL)
                        return PageResult.Failed("Neutral scripted manual failure", failure)
                    }
                    val progress = if (index == 5) {
                        PageProgress(WIDTH, HEIGHT, emptyList(), noTextDetected = true)
                    } else {
                        PageProgress(
                            WIDTH,
                            HEIGHT,
                            listOf(
                                RegionProgress(
                                    0,
                                    RegionState.INTENTIONALLY_KEPT,
                                    listOf(
                                        OcrLineProgress(
                                            listOf(Pt(20f, 20f), Pt(180f, 20f), Pt(180f, 60f), Pt(20f, 60f)),
                                            1f,
                                            "h",
                                            "neutral kept source",
                                            intentionallyKept = true,
                                        ),
                                    ),
                                    "h",
                                ),
                            ),
                        )
                    }
                    resultDiagnostics += JSONObject().put("callIndex", index).put("pageIndex", expected)
                        .put("result", "SKIPPED").put("noTextDetected", progress.noTextDetected)
                    // Identical reason text to the no-text case intentionally cannot determine
                    // whether the engine kept a policy region or found no text.
                    return PageResult.Skipped(
                        "No text detected",
                        PageStats(progress.regions.size, progress.regions.size, 0, 0, 0, 0, 0, 0),
                        PageAnalysis(owned(Color.BLACK), progress.toTextRegions(), progress),
                    )
                }
                val progress = PageProgress(
                    WIDTH,
                    HEIGHT,
                    listOf(
                        RegionProgress(
                            0, RegionState.TRANSLATED,
                            listOf(
                                OcrLineProgress(
                                    listOf(Pt(20f, 20f), Pt(180f, 20f), Pt(180f, 60f), Pt(20f, 60f)),
                                    1f,
                                    "h",
                                    "neutral source",
                                ),
                            ),
                            "h", cx = 100f, cy = 40f, boxW = 160f, boxH = 40f, translatedText = "중립",
                        ),
                    ),
                )
                val output = owned(translatedColor(expected), markerPattern(expected, true))
                resultDiagnostics += JSONObject().put("callIndex", index).put("pageIndex", expected)
                    .put("sourceWidth", page.width).put("sourceHeight", page.height)
                    .put("sourceCenterArgb", page.getPixel(page.width / 2, page.height / 2))
                    .put("resultWidth", output.width).put("resultHeight", output.height)
                    .put("resultCenterArgb", output.getPixel(output.width / 2, output.height / 2))
                    .put("resultDensity", output.density).put("resultColorSpace", output.colorSpace?.name ?: "absent")
                    .put("sourceMarkerPattern", markerPattern(expected, false))
                    .put("resultMarkerPattern", markerPattern(expected, true))
                    .put("sourceMarkerArgb", JSONArray((0..3).map { page.getPixel(markerX(it), markerY(it)) }))
                    .put("resultMarkerArgb", JSONArray((0..3).map { output.getPixel(markerX(it), markerY(it)) }))
                return PageResult.Translated(
                    output,
                    PageStats(1, 1, 1, 0, 0, 0, 0, 0),
                    PageAnalysis(owned(Color.BLACK), progress.toTextRegions(), progress),
                )
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                throw error
            } finally {
                active.decrementAndGet()
            }
        }

        private fun owned(color: Int, pattern: Int? = null) = fixtureBitmap(color, pattern).also { outputs += it }
        override fun warmUp() = error("Scripted warm engine must not initialize native models")
        override fun close() {
            check(active.get() == 0)
            closed.set(true)
        }
    }

    /** Preserve other audit cache entries byte-for-byte; remove only this source's exact identities. */
    private class CacheGuard(private val root: File) {
        private val before = hashes(root)
        private val beforeNames = root.listFiles().orEmpty().map { it.name }.toSet()
        private val identities = mutableSetOf<String>()

        init {
            // Leave enough headroom that the production cache never has to evict another fixture.
            val existingBytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            check(beforeNames.size <= 16 && existingBytes < 16L * 1024 * 1024) {
                "AUDIT_ONLINE_CACHE_REQUIRES_HEADROOM"
            }
        }

        fun own(manga: Manga, chapter: Chapter, pages: List<ReaderPage>) {
            pages.forEach { page ->
                identities += sha(
                    listOf(
                        "online-reader-v1",
                        manga.source.toString(),
                        manga.url,
                        chapter.url,
                        page.index.toString(),
                        page.url,
                    ).joinToString("") { "${it.length}:$it" }.toByteArray(),
                )
            }
        }

        fun close() {
            if (root.exists()) {
                check(root.canonicalFile == root && root.parentFile?.name == "cache")
                root.listFiles().orEmpty().filter { it.name !in beforeNames }.forEach { directory ->
                    check(directory.isDirectory && directory.canonicalFile.parentFile == root)
                    val metadata = File(directory, "origin.json")
                    val identity = JSONObject(metadata.readText()).getString("identity")
                    check(identity in identities) { "Refusing to remove an unowned online cache entry" }
                    val sourceSha = JSONObject(metadata.readText()).getString("sourceSha256")
                    check(directory.name == sha("online-page-v1\n$identity\n$sourceSha".toByteArray()))
                    directory.walkBottomUp().forEach { file ->
                        check(file.canonicalFile.toPath().startsWith(directory.toPath()))
                        check(file.delete())
                    }
                }
            }
            assertEquals("Existing online cache bytes must be preserved", before, hashes(root))
        }

        private fun hashes(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(directory).invariantSeparatorsPath to sha(it.readBytes()) }
    }

    private companion object {
        const val PAGE_COUNT = 1964
        const val WIDTH = 240
        const val HEIGHT = 320
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        fun originalColor(page: Int) = Color.rgb(220 - page * 8, 230 - page * 5, 240 - page * 3)
        fun translatedColor(page: Int) = Color.rgb(80 + page * 20, 20, 160)
        fun markerPattern(page: Int, translated: Boolean) = (page + 1) or if (translated) 8 else 0
        fun markerColor(pattern: Int, marker: Int) = if (pattern and (1 shl marker) != 0) Color.WHITE else Color.BLACK
        fun markerX(marker: Int) = WIDTH * (if (marker and 1 == 0) 1 else 3) / 4
        fun markerY(marker: Int) = HEIGHT * (if (marker and 2 == 0) 1 else 3) / 4
        fun fixtureBitmap(color: Int, pattern: Int? = null): Bitmap {
            val bitmap = Bitmap.createBitmap(
                WIDTH,
                HEIGHT,
                Bitmap.Config.ARGB_8888,
                false,
                ColorSpace.get(ColorSpace.Named.SRGB),
            )
            bitmap.eraseColor(color)
            if (pattern != null) {
                val paint = Paint().apply { isAntiAlias = false }
                val canvas = Canvas(bitmap)
                (0..3).forEach { marker ->
                    paint.color = markerColor(pattern, marker)
                    val x = markerX(marker).toFloat()
                    val y = markerY(marker).toFloat()
                    canvas.drawRect(x - 12f, y - 12f, x + 12f, y + 12f, paint)
                }
            }
            return bitmap
        }
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun sample(page: Int): ByteArray {
            val bitmap = fixtureBitmap(originalColor(page), markerPattern(page, false))
            try {
                return ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
