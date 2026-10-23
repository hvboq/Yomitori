package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.core.view.children
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonAdapter
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Actual preference subscription, adapter notification, layout, and local image decode; no OCR/HTTP. */
@RunWith(AndroidJUnit4::class)
class WebtoonRefreshRangeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun middlePageCropRefreshStaysWithinSevenAdapterItemsAndPreservesOffset() = runBlocking<Unit> {
        fixture("middle", pageIndex = 5)
    }

    @Test
    fun latePageCropRefreshStaysWithinAdapterBoundsAndPreservesOffset() = runBlocking<Unit> {
        fixture("late", pageIndex = 9)
    }

    private suspend fun fixture(name: String, pageIndex: Int) {
        assumeTrue(
            "Requires explicit -e webtoonRefreshAudit true",
            InstrumentationRegistry.getArguments().getString("webtoonRefreshAudit") == "true",
        )
        // The package guard precedes Injekt, preferences, database, report, and fixture file access.
        assertEquals("Webtoon audit must never target a user app", AUDIT_PACKAGE, context.packageName)
        assertIdle()

        val id = UUID.randomUUID().toString()
        val title = "Webtoon refresh fixture $id"
        val root = File(context.filesDir, "webtoon-refresh-$id")
        val reportFile = File(context.getExternalFilesDir(null), "webtoon-refresh/$name-$id.json")
        check(reportFile.parentFile!!.isDirectory || reportFile.parentFile!!.mkdirs())
        val report = JSONObject().put("passed", false).put("case", name)
            .put("targetPackage", context.packageName).put("actualReaderActivity", true)
            .put("actualDirectoryPageLoader", true).put("actualAdapterDataObserver", true)
            .put("nativeOcr", false).put("http", false).put("cropMenuUiVerified", false)
            .put("touchNavigationVerified", false).put("zoomRetentionVerified", false)
            .put("fixturePages", PAGE_COUNT).put("requestedPageNumber", pageIndex + 1)
            .put("trigger", "ReaderPreferences.cropBordersWebtoon -> live WebtoonConfig subscription")
            .put("positionSetup", "Viewer.moveToPage then real layoutManager scrollToPositionWithOffset")
            .put("imagePath", "actual ReaderPageImageView/SSIV; fixture opts into direct long-strip decode")
            .put("offsetContract", "same-size patterned-edge fixture; no arbitrary crop geometry claim")
            .put("notifications", JSONArray()).put("decodeEvents", JSONArray())
        reportFile.writeText(report.toString(2))

        val settings = LimitedSettings(context)
        val storage = Injekt.get<StorageManager>()
        val basePreference = Injekt.get<StoragePreferences>().baseStorageDirectory
        val previousBaseValue = basePreference.get()
        val previousBasePresence = basePreference.isSet()
        val previousEffectiveBaseUri = effectiveBaseUri(storage)
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        var mangaId: Long? = null
        var chapterId: Long? = null
        var failure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        try {
            withTimeout(120_000) {
                val sources = Injekt.get<SourceManager>()
                withTimeout(20_000) { sources.isInitialized.first { it } }
                assertTrue(sources.get(LocalSource.ID) is LocalSource)
                check(root.mkdirs())
                configure(settings, root)
                waitFor("effective private fixture storage base") {
                    effectiveBaseUri(storage) == Uri.fromFile(root)
                }
                // This accessor creates a directory; only call it after the private base is effective.
                assertEquals(Uri.fromFile(File(root, "local")), storage.getLocalSourceDirectory()?.uri)
                val directory = File(root, "local/$title/Chapter").apply { check(mkdirs()) }
                (1..PAGE_COUNT).forEach { number ->
                    File(directory, pageName(number - 1)).writeBytes(sample(number))
                }
                val baseline = fileHashes(directory)
                assertEquals(PAGE_COUNT, baseline.size)
                report.put("fixtureFileCount", baseline.size)
                    .put("fixtureTreeSha256", snapshotDigest(baseline))

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
                assertTrue(
                    mangas.update(
                        MangaUpdate(
                            manga.id,
                            viewerFlags = (
                                ReadingMode.WEBTOON.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue
                                ).toLong(),
                        ),
                    ),
                )
                val chapter = withContext(NonCancellable) {
                    chapters.addAll(
                        listOf(Chapter.create().copy(mangaId = manga.id, url = "$title/Chapter", name = "Chapter")),
                    ).single().also { chapterId = it.id }
                }
                val intent = ReaderActivity.newIntent(context, manga.id, chapter.id)
                ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                    awaitReader(scenario)
                    preparePosition(scenario, pageIndex)
                    verifyRefresh(scenario, settings, pageIndex, report)
                }
                assertEquals("Reader changed fixture files", baseline, fileHashes(directory))
                report.put("fixtureFilesUnchanged", true)
                assertIdle()
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(label: String, action: suspend () -> Unit) {
                    try {
                        withTimeout(15_000) { action() }
                        report.put(label, true)
                    } catch (caught: Throwable) {
                        cleanupFailures += AssertionError(label, caught)
                        report.put(label, false)
                    }
                }
                cleanup("fixtureWorkIdle") { assertIdle() }
                cleanup("fixtureChapterRemoved") {
                    chapterId?.let { ownedId ->
                        assertEquals(mangaId, chapters.getChapterById(ownedId)?.mangaId)
                        chapters.removeChaptersWithIds(listOf(ownedId))
                        assertEquals(null, chapters.getChapterById(ownedId))
                    }
                }
                cleanup("fixtureMangaRemoved") {
                    mangaId?.let { ownedId ->
                        val created = mangas.getMangaById(ownedId)
                        assertEquals(LocalSource.ID, created.source)
                        assertEquals(title, created.url)
                        val driver = Injekt.get<SqlDriver>()
                        val removed = driver.execute(
                            null,
                            "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?",
                            3,
                        ) {
                            bindLong(0, ownedId)
                            bindLong(1, LocalSource.ID)
                            bindString(2, title)
                        }.await()
                        assertEquals(1L, removed)
                        driver.notifyListeners("mangas", "chapters", "history")
                    }
                }
                cleanup("settingsRestoredIncludingPresence") { settings.restoreAndVerify() }
                cleanup("effectiveStorageRestored") {
                    assertEquals(previousBaseValue, basePreference.get())
                    assertEquals(previousBasePresence, basePreference.isSet())
                    waitFor("restored effective storage base without directory creation") {
                        effectiveBaseUri(storage) == previousEffectiveBaseUri
                    }
                }
                cleanup("fixtureFilesRemoved") {
                    assertEquals(context.filesDir.canonicalFile, root.canonicalFile.parentFile)
                    assertTrue(!root.exists() || root.deleteRecursively())
                    assertFalse(root.exists())
                }
                report.put("cleanupFailureCount", cleanupFailures.size)
                    .put("passed", failure == null && cleanupFailures.isEmpty())
                failure?.let {
                    report.put("failureType", it.javaClass.simpleName).put("failureMessage", it.message)
                }
                reportFile.writeText(report.toString(2))
                println("WEBTOON_REFRESH_REPORT=${reportFile.absolutePath}")
                println(report.toString(2))
            }
        }
        failure?.let { caught ->
            cleanupFailures.forEach(caught::addSuppressed)
            throw caught
        }
        if (cleanupFailures.isNotEmpty()) {
            throw AssertionError("Webtoon refresh fixture cleanup failed").apply {
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
        settings.set(base.alwaysDecodeLongStripWithSSIV, true)
        settings.set(translation.translationMasterEnabled, false)
        settings.set(translation.translationEnabled, false)
        settings.set(translation.liveTranslate, false)
        settings.set(translation.nightReadEnabled, false)
        settings.set(reader.nightReadMode, false)
        settings.set(reader.pageTransitions, false)
        settings.set(reader.dualPageSplitWebtoon, false)
        settings.set(reader.dualPageRotateToFitWebtoon, false)
        settings.set(reader.cropBordersWebtoon, false)
        settings.set(reader.webtoonSidePadding, 0)
        settings.set(reader.showNavigationOverlayNewUser, false)
        settings.set(reader.showNavigationOverlayOnStart, false)
    }

    private suspend fun awaitReader(scenario: ActivityScenario<ReaderActivity>) {
        waitFor("12 local pages and continuous WebtoonViewer") {
            var ready = false
            scenario.onActivity { activity ->
                val state = activity.viewModel.state.value
                val chapter = state.viewerChapters?.currChapter
                val viewer = state.viewer as? WebtoonViewer
                ready = chapter?.pageLoader is DirectoryPageLoader && chapter.pages?.size == PAGE_COUNT &&
                    chapter.pages.orEmpty().all { it.status == Page.State.Ready } &&
                    viewer?.isContinuous == true && viewer.recycler.height > 0 &&
                    !viewer.config.imageCropBorders && viewer.recycler.adapter is WebtoonAdapter
            }
            ready
        }
    }

    private suspend fun preparePosition(scenario: ActivityScenario<ReaderActivity>, pageIndex: Int) {
        scenario.onActivity { activity ->
            val page = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!![pageIndex]
            val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
            val adapter = viewer.recycler.adapter as WebtoonAdapter
            val layout = viewer.recycler.layoutManager as WebtoonLayoutManager
            viewer.moveToPage(page)
            // The real layout must settle with a non-zero internal page offset.
            layout.scrollToPositionWithOffset(adapter.items.indexOf(page), -viewer.recycler.height / 8)
            activity.hideMenu()
        }
        var stable: DisplayedPage? = null
        var stableSince = 0L
        waitFor("selected page decoded with a stable non-zero Webtoon offset") {
            var now: DisplayedPage? = null
            scenario.onActivity { now = displayed(it, pageIndex) }
            val current = now
            if (current == null || current.decoratedTop >= -8) {
                stable = null
                stableSince = 0L
                false
            } else if (current == stable) {
                SystemClock.elapsedRealtime() - stableSince >= 250
            } else {
                stable = current
                stableSince = SystemClock.elapsedRealtime()
                false
            }
        }
    }

    private suspend fun verifyRefresh(
        scenario: ActivityScenario<ReaderActivity>,
        settings: LimitedSettings,
        pageIndex: Int,
        report: JSONObject,
    ) {
        val notifications = mutableListOf<ChangedRange>()
        val decodeEvents = mutableListOf<DecodedPage>()
        val wrappedImages = linkedMapOf<ReaderPageImageView, ImageCallback>()
        var active = false
        lateinit var recycler: RecyclerView
        lateinit var adapter: WebtoonAdapter
        lateinit var before: DisplayedPage
        var observerRegistered = false
        var attachListenerRegistered = false
        var refreshFailure: Throwable? = null

        fun wrapImage(view: View) {
            val image = view as? ReaderPageImageView ?: return
            if (image in wrappedImages) return
            val previous = image.onImageLoaded
            val wrapped: () -> Unit = {
                previous?.invoke()
                if (active) {
                    val position = recycler.getChildAdapterPosition(image)
                    val item = adapter.items.getOrNull(position) as? ReaderPage
                    decodeEvents += DecodedPage(position, item?.index, SystemClock.elapsedRealtime())
                }
            }
            wrappedImages[image] = ImageCallback(previous, wrapped)
            image.onImageLoaded = wrapped
        }

        val attachListener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = wrapImage(view)
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        }
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun record(positionStart: Int, itemCount: Int) {
                if (!active) return
                val anchor = (recycler.layoutManager as WebtoonLayoutManager).findLastEndVisibleItemPosition()
                notifications += ChangedRange(positionStart, itemCount, adapter.itemCount, anchor)
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = record(positionStart, itemCount)

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
                record(positionStart, itemCount)
        }
        try {
            scenario.onActivity { activity ->
                val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
                recycler = viewer.recycler
                adapter = recycler.adapter as WebtoonAdapter
                before = checkNotNull(displayed(activity, pageIndex))
                assertEquals(pageIndex + 1, activity.viewModel.state.value.currentPage)
                assertTrue(before.decoratedTop < -8)
                assertEquals(PAGE_COUNT, adapter.items.filterIsInstance<ReaderPage>().size)
                assertEquals("Twelve pages plus actual leading/trailing transitions", PAGE_COUNT + 2, adapter.itemCount)
                report.put("before", before.json())
                recycler.children.forEach(::wrapImage)
                recycler.addOnChildAttachStateChangeListener(attachListener)
                attachListenerRegistered = true
                adapter.registerAdapterDataObserver(observer)
                observerRegistered = true
                active = true
                // No refreshAdapter call, mock adapter, or copied production range arithmetic.
                settings.set(Injekt.get<ReaderPreferences>().cropBordersWebtoon, true)
            }
            waitFor("actual crop preference subscription and adapter range notification") {
                var observed = false
                scenario.onActivity { activity ->
                    val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
                    observed = viewer.config.imageCropBorders && notifications.isNotEmpty()
                }
                observed
            }
            scenario.onActivity {
                notifications.forEach { range ->
                    report.getJSONArray("notifications").put(range.json())
                    assertTrue("Range must start within actual adapter: $range", range.start in 0 until range.total)
                    assertTrue(
                        "Range exclusive end must stay within actual adapter: $range",
                        range.start.toLong() + range.count <= range.total,
                    )
                    assertTrue("At most seven neighboring items may be rebound: $range", range.count in 1..7)
                    assertTrue(
                        "Refresh must include the actual current viewport anchor: $range",
                        range.anchor in range.start until (range.start + range.count),
                    )
                    assertTrue(
                        "Only items within three positions of the viewport anchor may be rebound: $range",
                        (range.start until range.start + range.count).all { abs(it - range.anchor) <= 3 },
                    )
                }
                report.put("boundedActualNotificationVerified", true)
            }
            var after: DisplayedPage? = null
            var stableAfter: DisplayedPage? = null
            var stableSince = 0L
            waitFor("rebound target page decoded and stable at the same page and offset") {
                var ready = false
                scenario.onActivity { activity ->
                    after = displayed(activity, pageIndex)
                    ready = after != null && decodeEvents.any { it.pageIndex == pageIndex } &&
                        !recycler.isComputingLayout && !recycler.hasPendingAdapterUpdates()
                }
                if (!ready) {
                    stableAfter = null
                    stableSince = 0L
                    false
                } else if (after == stableAfter) {
                    SystemClock.elapsedRealtime() - stableSince >= 250
                } else {
                    stableAfter = after
                    stableSince = SystemClock.elapsedRealtime()
                    false
                }
            }
            scenario.onActivity {
                val displayed = checkNotNull(after)
                assertEquals("Refresh changed the viewport page", before.pageIndex, displayed.pageIndex)
                assertEquals("Refresh changed adapter anchor", before.adapterPosition, displayed.adapterPosition)
                assertEquals(before.sourceWidth, displayed.sourceWidth)
                assertEquals(before.sourceHeight, displayed.sourceHeight)
                assertTrue(
                    "Same-size crop rebind changed the real child offset: before=$before after=$displayed",
                    abs(before.decoratedTop - displayed.decoratedTop) <= 2,
                )
                report.put("after", displayed.json()).put("samePageAndInternalOffsetPreserved", true)
                    .put("reboundSelectedImageDecoded", true)
            }
        } catch (caught: Throwable) {
            refreshFailure = caught
            throw caught
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            try {
                // Saved references remain usable even if ActivityScenario has already lost its Activity.
                instrumentation.runOnMainSync {
                    active = false
                    fun cleanup(label: String, action: () -> Unit) {
                        try {
                            action()
                        } catch (caught: Throwable) {
                            cleanupFailures += AssertionError(label, caught)
                        }
                    }
                    if (observerRegistered) {
                        cleanup("Detach adapter observer") { adapter.unregisterAdapterDataObserver(observer) }
                    }
                    if (attachListenerRegistered) {
                        cleanup("Detach child listener") {
                            recycler.removeOnChildAttachStateChangeListener(attachListener)
                        }
                    }
                    wrappedImages.forEach { (image, callbacks) ->
                        cleanup("Restore an image callback") {
                            if (image.onImageLoaded === callbacks.wrapped) image.onImageLoaded = callbacks.previous
                        }
                    }
                }
            } catch (caught: Throwable) {
                cleanupFailures += AssertionError("Run observer cleanup on the main thread", caught)
            }
            try {
                // Retain the first failing notification even when an assertion interrupts the normal path.
                if (report.getJSONArray("notifications").length() == 0) {
                    notifications.forEach { report.getJSONArray("notifications").put(it.json()) }
                }
                decodeEvents.forEach { report.getJSONArray("decodeEvents").put(it.json()) }
                report.put("observerCleanupFailureCount", cleanupFailures.size)
                    .put("observerCallbacksDetached", cleanupFailures.isEmpty())
            } catch (caught: Throwable) {
                cleanupFailures += AssertionError("Retain observer evidence", caught)
            }
            if (cleanupFailures.isNotEmpty()) {
                val original = refreshFailure
                if (original != null) {
                    cleanupFailures.forEach(original::addSuppressed)
                } else {
                    throw AssertionError("Webtoon observer cleanup failed").apply {
                        cleanupFailures.forEach(::addSuppressed)
                    }
                }
            }
        }
    }

    /** StorageManager has no read-only base getter; unlike its directory accessors this never creates files. */
    private fun effectiveBaseUri(storage: StorageManager): Uri? {
        val field = StorageManager::class.java.getDeclaredField("baseDir").apply { isAccessible = true }
        return (field.get(storage) as? UniFile)?.uri
    }

    /** Reads actual laid-out child + actual decoded SSIV, not pending captureState values. */
    private fun displayed(activity: ReaderActivity, pageIndex: Int): DisplayedPage? {
        val state = activity.viewModel.state.value
        if (state.currentPage != pageIndex + 1) return null
        val viewer = state.viewer as? WebtoonViewer ?: return null
        val recycler = viewer.recycler
        val adapter = recycler.adapter as? WebtoonAdapter ?: return null
        val layout = recycler.layoutManager as? WebtoonLayoutManager ?: return null
        if (recycler.isComputingLayout || recycler.hasPendingAdapterUpdates() || recycler.isLayoutRequested) return null
        val position = layout.findLastEndVisibleItemPosition()
        val page = adapter.items.getOrNull(position) as? ReaderPage ?: return null
        if (page.index != pageIndex || page.status != Page.State.Ready) return null
        val child = layout.findViewByPosition(position) as? ReaderPageImageView ?: return null
        val image = child.children.filterIsInstance<SubsamplingScaleImageView>().singleOrNull() ?: return null
        if (!child.isShown || !image.isShown || !image.isReady || !image.isImageLoaded) return null
        val bounds = Rect()
        if (!image.getGlobalVisibleRect(bounds) || bounds.isEmpty) return null
        if (image.sWidth != IMAGE_WIDTH || image.sHeight != IMAGE_HEIGHT) return null
        return DisplayedPage(
            page.index,
            position,
            layout.getDecoratedTop(child) - recycler.paddingTop,
            image.sWidth,
            image.sHeight,
        )
    }

    private fun assertIdle() {
        assertTrue("Translation queue must be empty", Injekt.get<TranslationManager>().queueState.value.isEmpty())
        assertTrue("Download queue must be empty", Injekt.get<DownloadManager>().queueState.value.isEmpty())
        assertEquals("Translation work must be idle", 0, TranslationBusy.active.value)
    }

    private fun sample(number: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 70f
                }
                // Mixed edge pixels prevent either a white or black uniform margin. A notification
                // is still necessary, and real holder binding + decode must run through crop=true.
                for (row in 0 until IMAGE_HEIGHT / 60) {
                    for (column in 0 until IMAGE_WIDTH / 60) {
                        if ((row + column) % 2 == 0) {
                            drawRect(column * 60f, row * 60f, (column + 1) * 60f, (row + 1) * 60f, paint)
                        }
                    }
                }
                paint.color = Color.WHITE
                drawRect(20f, 240f, IMAGE_WIDTH - 20f, 370f, paint)
                paint.color = Color.BLACK
                drawText("Neutral page $number", 35f, 330f, paint)
                drawRect(40f, 700f, IMAGE_WIDTH - 40f, 1000f + number * 20, paint)
            }
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun fileHashes(directory: File): Map<String, String> = directory.walkTopDown()
        .filter { it.isFile }
        .associate { file ->
            file.relativeTo(directory).invariantSeparatorsPath to sha256(file.readBytes())
        }

    private fun snapshotDigest(snapshot: Map<String, String>): String = sha256(
        snapshot.toSortedMap().entries.joinToString("\n") { "${it.key}\u0000${it.value}" }.toByteArray(),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private suspend fun waitFor(label: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "Webtoon refresh timed out: $label" }
            delay(50)
        }
    }

    private data class ChangedRange(val start: Int, val count: Int, val total: Int, val anchor: Int) {
        fun json() = JSONObject().put("positionStart", start).put("itemCount", count)
            .put("actualAdapterItemCount", total).put("actualViewportAnchor", anchor)
    }

    private data class DecodedPage(val adapterPosition: Int, val pageIndex: Int?, val elapsedMs: Long) {
        fun json() = JSONObject().put("adapterPosition", adapterPosition)
            .put("pageIndex", pageIndex).put("elapsedRealtimeMs", elapsedMs)
    }

    private data class DisplayedPage(
        val pageIndex: Int,
        val adapterPosition: Int,
        val decoratedTop: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    ) {
        fun json() = JSONObject().put("pageNumber", pageIndex + 1).put("adapterPosition", adapterPosition)
            .put("decoratedTopRelativeToPaddingPx", decoratedTop)
            .put("ssivReady", true).put("ssivImageLoaded", true)
            .put("sourceWidth", sourceWidth).put("sourceHeight", sourceHeight)
    }

    private data class ImageCallback(val previous: (() -> Unit)?, val wrapped: () -> Unit)

    /** Only the explicitly touched keys; restores both previous values and previous isSet states. */
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
                    failures += AssertionError("A touched Webtoon preference could not be restored")
                }
            }
            check(PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
            verifies.forEach { verify ->
                try {
                    verify()
                } catch (_: Throwable) {
                    failures += AssertionError("A touched Webtoon preference value or presence differs")
                }
            }
            if (failures.isNotEmpty()) {
                throw AssertionError("Webtoon preference cleanup failed").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        }
    }

    private companion object {
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val PAGE_COUNT = 12
        const val IMAGE_WIDTH = 600
        const val IMAGE_HEIGHT = 1800

        fun pageName(index: Int) = "%03d.png".format(index + 1)
    }
}
