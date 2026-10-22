package mihon.translation

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.children
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
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
import java.util.UUID
import kotlin.math.abs

/** Actual Activity recreation and page-menu integration; synthetic completed pages, no native/HTTP. */
@RunWith(AndroidJUnit4::class)
class ReaderRotationStateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun middlePageSurvivesLandscapeAndPortraitActivityRecreation() = runBlocking<Unit> {
        fixture("position") { fixture, report ->
            ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                portrait(scenario)
                moveToMiddle(scenario)
                val before = ready(scenario)
                val viewModel = before.viewModel
                assertMiddle(before, fixture.translated[1])
                observeTouchZoomAndPan(scenario, report)
                val landscape = rotate(scenario, ReaderOrientation.LOCKED_LANDSCAPE, report)
                assertSame("Configuration recreation must retain this reader session", viewModel, landscape.viewModel)
                assertMiddle(landscape, fixture.translated[1])
                assertFilesUnchanged(fixture)
                val portrait = rotate(scenario, ReaderOrientation.LOCKED_PORTRAIT, report)
                assertSame(viewModel, portrait.viewModel)
                assertMiddle(portrait, fixture.translated[1])
                report.put("zoomAfterPortrait", zoomJson(captureZoomState(scenario)))
                assertFilesUnchanged(fixture)
            }
            report.put("middlePagePreservedAcrossBothRecreations", true)
        }
    }

    @Test
    fun originalPreviewSurvivesBothRecreationsWithoutChangingCompletedFiles() = runBlocking<Unit> {
        fixture("original-preview") { fixture, report ->
            ActivityScenario.launch<ReaderActivity>(fixture.intent()).use { scenario ->
                portrait(scenario)
                moveToMiddle(scenario)
                val page = middle(ready(scenario))
                assertFalse(page.isOriginalPreview)
                compareThroughPageMenu(scenario, original = true)
                waitFor("original preview stream") { page.isOriginalPreview }
                assertMiddle(ready(scenario), fixture.originals[1])
                assertFilesUnchanged(fixture)

                val landscape = rotate(scenario, ReaderOrientation.LOCKED_LANDSCAPE, report)
                assertSame("The session's original-preview page must survive recreation", page, middle(landscape))
                assertTrue(middle(landscape).isOriginalPreview)
                assertMiddle(landscape, fixture.originals[1])
                assertFilesUnchanged(fixture)

                val portrait = rotate(scenario, ReaderOrientation.LOCKED_PORTRAIT, report)
                assertSame(page, middle(portrait))
                assertTrue(middle(portrait).isOriginalPreview)
                assertMiddle(portrait, fixture.originals[1])
                compareThroughPageMenu(scenario, original = false)
                waitFor("translated preview stream") { !page.isOriginalPreview }
                assertMiddle(ready(scenario), fixture.translated[1])
                assertFilesUnchanged(fixture)
            }
            report.put("actualLongPressAndVisibleComparisonActions", true)
                .put("originalPreviewPreservedWithinOneReaderSession", true)
                .put("returnToTranslationVerified", true)
        }
    }

    private suspend fun fixture(name: String, action: suspend (Fixture, JSONObject) -> Unit) {
        val report = JSONObject().put("passed", false).put("actualReaderActivity", true)
            .put("nativeOcr", false).put("http", false).put("readerReopeningTest", false)
            .put(
                "displayEvidence",
                "ReaderPage displayStream bytes and visible SSIV ready/image-loaded state; no pixel capture",
            )
            .put(
                "orientationEntryPoint",
                "fixture manga setMangaOrientationType; Activity requestedOrientation observed",
            )
            .put("zoomRetentionContractAsserted", false).put("rotations", JSONArray())
        val reportFile = File(context.getExternalFilesDir(null), "reader-rotation-state/$name.json")
        check(reportFile.parentFile!!.isDirectory || reportFile.parentFile!!.mkdirs())
        reportFile.writeText(report.toString(2))
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir, "reader-rotation-$id")
        val title = "Rotation fixture $id"
        val settings = LimitedSettings(context)
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val manager = Injekt.get<TranslationManager>()
        val storage = Injekt.get<StorageManager>()
        val previousLocalDirectory = storage.getLocalSourceDirectory()?.uri
        var mangaId: Long? = null
        var chapterId: Long? = null
        var failure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        try {
            withTimeout(120_000) {
                assertIdle()
                val sources = Injekt.get<SourceManager>()
                withTimeout(20_000) { sources.isInitialized.first { it } }
                assertTrue(sources.get(LocalSource.ID) is LocalSource)
                check(root.mkdirs())
                configure(settings, root)
                waitFor("fixture local storage") {
                    storage.getLocalSourceDirectory()?.uri == Uri.fromFile(File(root, "local"))
                }
                val directory = File(root, "local/$title/Chapter").apply { check(mkdirs()) }
                val chapterDirectory = checkNotNull(UniFile.fromFile(directory))
                val translator = PageTranslator(context)
                val originals = (1..PAGE_COUNT).map { number ->
                    val original = sample(number, translated = false)
                    val translated = sample(number, translated = true)
                    try {
                        val file = File(directory, pageName(number - 1))
                        file.writeBytes(png(original))
                        val originalBytes = file.readBytes()
                        assertTrue(
                            translator.persistLivePage(
                                chapterDirectory,
                                checkNotNull(UniFile.fromFile(file)),
                                original,
                                translated,
                                null,
                                "boxfill",
                            ),
                        )
                        assertArrayEquals(
                            originalBytes,
                            File(directory, ".yakuyomi/${number.toString().padStart(3, '0')}.orig.png").readBytes(),
                        )
                        originalBytes
                    } finally {
                        original.recycle()
                        translated.recycle()
                    }
                }
                assertTrue(translator.isChapterTranslated(chapterDirectory))
                // Record every inserted ID even if the outer deadline expires during the DB write.
                val manga = withContext(NonCancellable) {
                    mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = LocalSource.ID,
                                url = title,
                                title = title,
                                initialized = true,
                            ),
                        ),
                    ).single().also { mangaId = it.id }
                }
                val viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue or ReaderOrientation.FREE.flagValue
                assertTrue(
                    mangas.update(
                        MangaUpdate(
                            manga.id,
                            viewerFlags = viewerFlags.toLong(),
                        ),
                    ),
                )
                val chapter = withContext(NonCancellable) {
                    chapters.addAll(
                        listOf(Chapter.create().copy(mangaId = manga.id, url = "$title/Chapter", name = "Chapter")),
                    ).single().also { chapterId = it.id }
                }
                val baseline = fileSnapshot(directory)
                report.put("fixturePages", PAGE_COUNT).put("baselineFileCount", baseline.size)
                    .put("baselineTreeSha256", snapshotDigest(baseline))
                val fixture = Fixture(
                    manga.id,
                    chapter.id,
                    directory,
                    originals,
                    (0 until PAGE_COUNT).map { File(directory, pageName(it)).readBytes() },
                    baseline,
                )
                action(fixture, report)
                assertFilesUnchanged(fixture)
                assertIdle()
                report.put("originalsTranslationsAndCompletionFilesUnchanged", true)
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(label: String, block: suspend () -> Unit) {
                    try {
                        withTimeout(15_000) { block() }
                        report.put(label, true)
                    } catch (caught: Throwable) {
                        cleanupFailures += AssertionError(label, caught)
                        report.put(label, false)
                    }
                }
                cleanup("fixtureWorkStopped") {
                    chapterId?.let { if (manager.isPendingTranslate(it)) manager.cancelPendingTranslation(it) }
                    waitFor("idle after fixture", 10_000) { TranslationBusy.active.value == 0 }
                    assertIdle()
                }
                cleanup("fixtureChapterRemoved") {
                    chapterId?.let { id ->
                        assertEquals(mangaId, chapters.getChapterById(id)?.mangaId)
                        chapters.removeChaptersWithIds(listOf(id))
                        assertEquals(null, chapters.getChapterById(id))
                    }
                }
                cleanup("fixtureMangaRemoved") {
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
                cleanup("settingsRestoredIncludingPresence") { settings.restoreAndVerify() }
                cleanup("effectiveStorageRestored") {
                    waitFor("restored local storage") {
                        storage.getLocalSourceDirectory()?.uri == previousLocalDirectory
                    }
                }
                cleanup("fixtureFilesRemoved") {
                    assertEquals(context.filesDir.canonicalFile, root.canonicalFile.parentFile)
                    assertTrue(!root.exists() || root.deleteRecursively())
                    assertFalse(root.exists())
                }
                report.put("cleanupFailureCount", cleanupFailures.size)
                    .put("passed", failure == null && cleanupFailures.isEmpty())
                failure?.let { report.put("failureType", it.javaClass.simpleName) }
                reportFile.writeText(report.toString(2))
                println("READER_ROTATION_REPORT=${reportFile.absolutePath}")
                println(report.toString(2))
            }
        }
        failure?.let { caught ->
            cleanupFailures.forEach(caught::addSuppressed)
            throw caught
        }
        if (cleanupFailures.isNotEmpty()) {
            throw AssertionError("Reader rotation fixture cleanup failed").apply {
                cleanupFailures.forEach(::addSuppressed)
            }
        }
    }

    private fun configure(settings: LimitedSettings, root: File) {
        val reader = Injekt.get<ReaderPreferences>()
        val translation = Injekt.get<TranslationPreferences>()
        val base = Injekt.get<BasePreferences>()
        settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
        settings.set(base.shownOnboardingFlow, true)
        settings.set(base.incognitoMode, false)
        settings.set(base.downloadedOnly, false)
        settings.set(translation.translationMasterEnabled, false)
        settings.set(translation.translationEnabled, false)
        settings.set(translation.liveTranslate, false)
        settings.set(translation.nightReadEnabled, false)
        settings.set(reader.nightReadMode, false)
        settings.set(reader.defaultOrientationType, ReaderOrientation.FREE.flagValue)
        settings.set(reader.pageTransitions, false)
        settings.set(reader.landscapeZoom, false)
        settings.set(reader.dualPageSplitPaged, false)
        settings.set(reader.dualPageRotateToFit, false)
        settings.set(reader.cropBorders, false)
        settings.set(reader.imageScaleType, 1)
        settings.set(reader.doubleTapAnimSpeed, 100)
        settings.set(reader.readWithLongTap, true)
        settings.set(reader.showNavigationOverlayNewUser, false)
        settings.set(reader.showNavigationOverlayOnStart, false)
    }

    private suspend fun portrait(scenario: ActivityScenario<ReaderActivity>) {
        ready(scenario)
        scenario.onActivity { it.viewModel.setMangaOrientationType(ReaderOrientation.LOCKED_PORTRAIT) }
        waitFor("initial portrait") {
            var matches = false
            scenario.onActivity {
                matches = it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT &&
                    it.window.decorView.height > it.window.decorView.width
            }
            matches
        }
        ready(scenario)
    }

    private suspend fun moveToMiddle(scenario: ActivityScenario<ReaderActivity>) {
        scenario.onActivity {
            val page = middle(it)
            checkNotNull(it.viewModel.state.value.viewer).moveToPage(page)
            it.hideMenu()
        }
        waitFor("middle page selected") {
            var selected = false
            scenario.onActivity { selected = it.viewModel.state.value.currentPage == 2 }
            selected
        }
        ready(scenario, expectedPage = 2)
    }

    private suspend fun rotate(
        scenario: ActivityScenario<ReaderActivity>,
        orientation: ReaderOrientation,
        report: JSONObject,
    ): ReaderActivity {
        val before = ready(scenario, expectedPage = 2)
        scenario.onActivity { it.viewModel.setMangaOrientationType(orientation) }
        val landscape = orientation == ReaderOrientation.LOCKED_LANDSCAPE
        waitFor("${orientation.name} Activity recreation") {
            var matches = false
            scenario.onActivity {
                val view = it.window.decorView
                val geometryMatches = if (landscape) view.width > view.height else view.height > view.width
                matches = it !== before && it.requestedOrientation == orientation.flag && geometryMatches
            }
            matches
        }
        val after = ready(scenario, expectedPage = 2)
        assertNotSame("Orientation must replace the Activity", before, after)
        assertEquals(orientation.flag, after.requestedOrientation)
        val view = after.window.decorView
        assertTrue(
            "Orientation must change actual window geometry",
            if (landscape) view.width > view.height else view.height > view.width,
        )
        report.getJSONArray("rotations").put(
            JSONObject().put("orientation", orientation.name).put("activityRecreated", true)
                .put("width", view.width).put("height", view.height)
                .put("currentPage", after.viewModel.state.value.currentPage)
                .put("zoomObservation", zoomJson(captureZoomState(scenario))),
        )
        return after
    }

    private suspend fun ready(
        scenario: ActivityScenario<ReaderActivity>,
        expectedPage: Int? = null,
    ): ReaderActivity {
        var activity: ReaderActivity? = null
        waitFor("reader and visible decoded image ready") {
            var loaded = false
            // A previous Activity reference is never reused to wait for recreation.
            scenario.onActivity {
                activity = it
                val chapter = it.viewModel.state.value.viewerChapters?.currChapter
                val page = it.viewModel.state.value.currentPage
                loaded = chapter?.pageLoader is DirectoryPageLoader && chapter.pages?.size == PAGE_COUNT &&
                    chapter.pages.orEmpty().all { candidate -> candidate.status == Page.State.Ready } &&
                    (expectedPage == null || page == expectedPage) &&
                    displayedZoom(it) != null
            }
            loaded
        }
        return checkNotNull(activity)
    }

    private fun middle(activity: ReaderActivity): ReaderPage =
        checkNotNull(activity.viewModel.state.value.viewerChapters?.currChapter?.pages)[1]

    private fun assertMiddle(activity: ReaderActivity, expected: ByteArray) {
        assertEquals(2, activity.viewModel.state.value.currentPage)
        val page = middle(activity)
        assertEquals(1, page.index)
        assertArrayEquals(expected, checkNotNull(page.displayStream).invoke().use { it.readBytes() })
    }

    private suspend fun compareThroughPageMenu(scenario: ActivityScenario<ReaderActivity>, original: Boolean) {
        val activity = ready(scenario)
        var x = 0f
        var y = 0f
        scenario.onActivity {
            it.hideMenu()
            val view = checkNotNull(it.viewModel.state.value.viewer).getView()
            val location = IntArray(2).also(view::getLocationOnScreen)
            x = location[0] + view.width * 0.5f
            y = location[1] + view.height * 0.45f
        }
        touch(x, y, ViewConfiguration.getLongPressTimeout().toLong() + 150)
        waitFor("actual long-press page actions") {
            val dialog = activity.viewModel.state.value.dialog as? ReaderViewModel.Dialog.PageActions
            dialog?.page === middle(activity)
        }
        val expected = if (original) MR.strings.reader_show_original else MR.strings.reader_show_translation
        val forbidden = if (original) MR.strings.reader_show_translation else MR.strings.reader_show_original
        checkOnlineReaderAction(
            context.packageName,
            activity.stringResource(expected),
            activity.stringResource(forbidden),
            click = true,
            beforeClick = {},
        )
    }

    /** Actual zoom/pan outcomes; holder-local zoom is not promised to survive Activity recreation. */
    private suspend fun observeTouchZoomAndPan(scenario: ActivityScenario<ReaderActivity>, report: JSONObject) {
        ready(scenario, expectedPage = 2)
        val initial = checkNotNull(displayedZoom(scenario))
        val x = initial.bounds.centerX()
        val y = initial.bounds.top + (initial.bounds.height() * 0.45f).toInt()
        val endY = (y + (initial.bounds.height() * 0.08f).toInt()).coerceAtMost(initial.bounds.bottom - 1)
        assertTrue(initial.bounds.contains(x, y) && initial.bounds.contains(x, endY))
        val calls = JSONArray()
        val observation = JSONObject().put("inputEntryPoint", "device shell input tap/swipe")
            .put("deviceEventTimestampsMeasured", false).put("shellCalls", calls)
            .put("initial", zoomJson(initial.state)).put("initialDisplay", displayJson(initial))
        report.put("touchZoomAndPan", observation)

        // Each command uses only numeric coordinates inside this fixture's visible image.
        // The shell path avoids UiAutomation.injectInputEvent's window-animation transaction waits.
        shellInput("input tap $x $y", "tap", calls)
        delay(60) // Keep distinct taps even when the shell command returns almost immediately.
        shellInput("input tap $x $y", "tap", calls)
        observation.put("doubleTapInjected", true).put("requestedInterTapPauseMs", 60)
        var zoomed: DisplayedZoom? = null
        var lastScale = initial.state.scale
        var stableSince = SystemClock.elapsedRealtime()
        waitFor("shell double tap increased and settled actual image scale", 5_000) {
            zoomed = displayedZoom(scenario)
            val scale = zoomed?.state?.scale
            val now = SystemClock.elapsedRealtime()
            if (scale == null || abs(scale - lastScale) > 0.001f) stableSince = now
            if (scale != null) lastScale = scale
            scale != null && scale > initial.state.scale * 1.1f && now - stableSince >= 150
        }
        val enlarged = checkNotNull(zoomed)
        assertTrue("Shell double tap must visibly increase scale", enlarged.state.scale > initial.state.scale * 1.1f)
        observation.put("scaleIncreaseVerified", true).put("afterDoubleTap", zoomJson(enlarged.state))
            .put("afterDoubleTapDisplay", displayJson(enlarged))

        shellInput("input swipe $x $y $x $endY 250", "swipe", calls)
        observation.put("panInjected", true)
        var panned: DisplayedZoom? = null
        waitFor("shell swipe changed actual image center", 5_000) {
            panned = displayedZoom(scenario)
            panned?.state?.centerY?.let { abs(it - enlarged.state.centerY) > 1f } == true
        }
        val moved = checkNotNull(panned)
        assertTrue(
            "Shell swipe must pan by more than one source pixel",
            abs(moved.state.centerY - enlarged.state.centerY) > 1f,
        )
        observation.put("panCenterChangeVerified", true).put("afterPan", zoomJson(moved.state))
            .put("afterPanDisplay", displayJson(moved))
    }

    private fun shellInput(command: String, action: String, calls: JSONArray) {
        val started = SystemClock.uptimeMillis()
        val call = JSONObject().put("action", action).put("startedUptimeMs", started)
        calls.put(call)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
                check(it.readBytes().isEmpty()) { "Fixture shell input reported unexpected output" }
            }
            call.put("eofReadAndDescriptorClosed", true)
        } finally {
            val finished = SystemClock.uptimeMillis()
            call.put("finishedUptimeMs", finished).put("elapsedMs", finished - started)
        }
    }

    private fun captureZoomState(scenario: ActivityScenario<ReaderActivity>): ReaderPageImageView.ZoomState? =
        displayedZoom(scenario)?.state

    private fun displayedZoom(scenario: ActivityScenario<ReaderActivity>): DisplayedZoom? {
        var displayed: DisplayedZoom? = null
        scenario.onActivity { displayed = displayedZoom(it) }
        return displayed
    }

    /** Reads the real image child, never captureState's pendingRestore fallback. Called on the UI thread. */
    private fun displayedZoom(activity: ReaderActivity): DisplayedZoom? {
        val viewer = activity.viewModel.state.value.viewer as? L2RPagerViewer ?: return null
        val holder = viewer.pager.children.filterIsInstance<PagerPageHolder>()
            .firstOrNull { it.page.number == activity.viewModel.state.value.currentPage } ?: return null
        val image = holder.children.filterIsInstance<SubsamplingScaleImageView>().singleOrNull() ?: return null
        val bounds = Rect()
        if (!image.isReady || !image.isImageLoaded || !image.getGlobalVisibleRect(bounds) || bounds.isEmpty) return null
        // currentPage changes before a smooth pager movement finishes. A partly visible next
        // page has moving local touch coordinates, so two screen taps are not yet a double tap.
        val viewport = Rect()
        if (!viewer.pager.getGlobalVisibleRect(viewport) || bounds != viewport) return null
        val center = image.center ?: return null
        return DisplayedZoom(
            ReaderPageImageView.ZoomState(image.scale, center.x, center.y, image.sWidth, image.sHeight),
            bounds,
            System.identityHashCode(image),
        )
    }

    private fun displayJson(displayed: DisplayedZoom) = JSONObject().put("ssivReady", true)
        .put("ssivImageLoaded", true).put("imageViewIdentity", displayed.imageViewIdentity)
        .put(
            "visibleRect",
            JSONArray(
                listOf(displayed.bounds.left, displayed.bounds.top, displayed.bounds.right, displayed.bounds.bottom),
            ),
        )

    private fun zoomJson(state: ReaderPageImageView.ZoomState?): Any = state?.let {
        JSONObject().put("scale", it.scale).put("centerX", it.centerX).put("centerY", it.centerY)
            .put("sourceWidth", it.sWidth).put("sourceHeight", it.sHeight)
    } ?: JSONObject.NULL

    private suspend fun touch(x: Float, y: Float, duration: Long, endY: Float = y) {
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, positionY: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, positionY, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Touch injection rejected" }
            } finally {
                event.recycle()
            }
        }
        inject(MotionEvent.ACTION_DOWN, y)
        var released = false
        try {
            repeat(5) { step ->
                delay(duration / 5)
                if (endY != y) inject(MotionEvent.ACTION_MOVE, y + (endY - y) * (step + 1) / 5)
            }
            inject(MotionEvent.ACTION_UP, endY)
            released = true
        } finally {
            if (!released) inject(MotionEvent.ACTION_CANCEL, endY)
        }
    }

    private fun assertIdle() {
        assertTrue("Translation queue must be empty", Injekt.get<TranslationManager>().queueState.value.isEmpty())
        assertTrue("Download queue must be empty", Injekt.get<DownloadManager>().queueState.value.isEmpty())
        assertEquals("Translation work must be idle", 0, TranslationBusy.active.value)
    }

    private fun assertFilesUnchanged(fixture: Fixture) {
        assertTrue(PageTranslator(context).isChapterTranslated(checkNotNull(UniFile.fromFile(fixture.directory))))
        val after = fileSnapshot(fixture.directory)
        assertEquals("Rotation changed the set of stored files", fixture.baseline.keys, after.keys)
        fixture.baseline.forEach { (name, bytes) -> assertArrayEquals("Rotation changed $name", bytes, after[name]) }
    }

    private fun fileSnapshot(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).invariantSeparatorsPath to it.readBytes()
    }

    private fun snapshotDigest(snapshot: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        snapshot.toSortedMap().forEach { (name, bytes) ->
            digest.update(name.toByteArray())
            digest.update(0.toByte())
            digest.update(MessageDigest.getInstance("SHA-256").digest(bytes))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sample(number: Int, translated: Boolean): Bitmap =
        Bitmap.createBitmap(900, 1800, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(if (translated) Color.rgb(180, 210 - number * 10, 240) else Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 90f
                }
                drawText("${if (translated) "Result" else "Source"} $number", 100f, 400f, paint)
                drawRect(100f, 800f, 800f, 1000f + number * 70, paint)
            }
        }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        it.toByteArray()
    }

    private suspend fun waitFor(label: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "Reader rotation timed out: $label" }
            delay(50)
        }
    }

    private data class Fixture(
        val mangaId: Long,
        val chapterId: Long,
        val directory: File,
        val originals: List<ByteArray>,
        val translated: List<ByteArray>,
        val baseline: Map<String, ByteArray>,
    ) {
        fun intent() = ReaderActivity.newIntent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            mangaId,
            chapterId,
        )
    }

    private data class DisplayedZoom(
        val state: ReaderPageImageView.ZoomState,
        val bounds: Rect,
        val imageViewIdentity: Int,
    )

    /** OnlineReaderSettings' value/isSet restore contract, restricted to the keys this test changes. */
    private class LimitedSettings(private val context: Context) {
        private val restores = linkedMapOf<String, () -> Unit>()
        private val verifies = mutableListOf<() -> Unit>()

        fun <T> set(preference: Preference<T>, value: T) {
            if (preference.key() !in restores) {
                val existed = preference.isSet()
                val previous = preference.get()
                restores[preference.key()] = { if (existed) preference.set(previous) else preference.delete() }
                verifies += { check(preference.isSet() == existed && preference.get() == previous) }
            }
            preference.set(value)
        }

        fun restoreAndVerify() {
            val failures = mutableListOf<Throwable>()
            restores.values.toList().asReversed().forEach { restore ->
                try {
                    restore()
                } catch (_: Throwable) {
                    failures += AssertionError("A touched reader preference could not be restored")
                }
            }
            check(PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
            verifies.forEach { verify ->
                try {
                    verify()
                } catch (_: Throwable) {
                    failures += AssertionError("A touched reader preference value or presence differs")
                }
            }
            if (failures.isNotEmpty()) {
                throw AssertionError("Reader rotation preference cleanup failed").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        }
    }

    private companion object {
        const val PAGE_COUNT = 3

        fun pageName(index: Int) = "%03d.png".format(index + 1)
    }
}
