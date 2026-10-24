package mihon.translation

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.RegionMaterial
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStore
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.testprovider.SafAliasDocumentsProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Actual ReaderActivity, its localized method dialog, real AOT/boxfill path and an owned provider
 * truncate fault. No fake re-render result, manual reload, OCR, translation server or OS picker.
 */
@RunWith(AndroidJUnit4::class)
class ReRenderRollbackUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 180_000)
    fun interruptedRerenderRestoresOriginalAndRefreshesTheSameVisibleHolder(): Unit = runBlocking {
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && Process.is64Bit()) { "ARM64 native required" }
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("reRenderRollbackUi") == "true") { "Explicit UI/native opt-in required" }
        val uuid = checkNotNull(args.getString("reRenderRollbackUiRunId"))
        check(UUID.fromString(uuid).toString() == uuid)
        val root = context.filesDir.canonicalFile
        val output = File(root, "rerender-rollback-ui-$uuid")
        check(output.canonicalFile == output.absoluteFile && output.parentFile == root && output.mkdir())
        val report = JSONObject().put("schema", 1).put("runId", uuid).put("passed", false)
            .put("activityPixelsMeasured", false).put("manualRefreshCallsByTest", 0)
            .put("fakeRerenderResults", 0).put("ocrCallsByTest", 0).put("serverCallsByTest", 0)
            .put("osPickerTested", false).put("productionSourceChangedByTest", false)
        fun checkpoint(stage: String) {
            report.put("stage", stage)
            File(output, "result.json").writeText(report.toString(2))
            instrumentation.sendStatus(
                2,
                android.os.Bundle().apply {
                    putString("stream", "RERENDER_ROLLBACK_UI=$stage;runId=$uuid\n")
                },
            )
        }
        checkpoint("PRECONDITIONS")
        val storage = Injekt.get<StorageManager>()
        val oldStorage = effectiveStorage(storage)
        val settings = OnlineReaderSettings(context)
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val manager = Injekt.get<TranslationManager>()
        val cache = Injekt.get<TranslationCache>()
        val title = "Neutral rerender $uuid"
        var mangaId: Long? = null
        var chapterId: Long? = null
        var session: SafAliasDocumentsProvider.Session? = null
        var priorModels: Map<String, String>? = null
        var settingsTouched = false
        var caught: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        try {
            requireIdle()
            val sources = Injekt.get<SourceManager>()
            withTimeout(20_000) { sources.isInitialized.first { it } }
            check(sources.get(LocalSource.ID) is LocalSource)
            priorModels = inpaintModelHashes()
            val owned = SafAliasDocumentsProvider.createSession(context).also { session = it }
            report.put("privateProviderSessionId", owned.id)
            val authority = context.packageManager.getProviderInfo(
                ComponentName(context, SafAliasDocumentsProvider::class.java),
                0,
            ).authority
            val tree = DocumentsContract.buildTreeDocumentUri(authority, "${owned.id}|root-a")
            val local = owned.checked(File(owned.directory, "local")).apply { check(mkdir()) }
            val book = owned.checked(File(local, title)).apply { check(mkdir()) }
            val chapterDirectory = owned.checked(File(book, "Chapter")).apply { check(mkdir()) }
            val pageFile = owned.checked(File(chapterDirectory, PAGE))
            val originalBytes = imageBytes(Color.WHITE)
            val displayBytes = imageBytes(Color.BLUE)
            val otherBytes = imageBytes(Color.GREEN)
            pageFile.writeBytes(displayBytes)
            File(chapterDirectory, "002.png").writeBytes(otherBytes)
            val materials = File(chapterDirectory, ".yakuyomi").apply { check(mkdir()) }
            File(materials, "001.orig.png").writeBytes(originalBytes)
            File(materials, "001.mask.png").writeBytes(imageBytes(Color.WHITE))
            File(materials, "001.json").writeText(Json.encodeToString(PageMaterials("boxfill", listOf(region()))))
            val completion = CompletionManifestCodec.encode(setOf(PAGE, "002.png"))
            File(chapterDirectory, CompletionManifestCodec.PRIMARY).writeBytes(completion)
            File(chapterDirectory, CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID())
                .writeBytes(completion)
            val beforeFiles = fileHashes(chapterDirectory)
            val expectedBaseUri = checkNotNull(UniFile.fromUri(context, tree)).uri
            settingsTouched = true
            configure(settings, tree)
            waitUntil(20_000) {
                effectiveStorage(storage) == expectedBaseUri && storage.getLocalSourceDirectory()?.let {
                    DocumentsContract.getDocumentId(it.uri) == owned.documentId(local)
                } == true
            }
            val chapterUri = DocumentsContract.buildDocumentUriUsingTree(tree, owned.documentId(chapterDirectory))
            val chapterFile = checkNotNull(UniFile.fromUri(context, chapterUri))
            val translator = PageTranslator(context)
            check(translator.hasReRenderMaterials(chapterFile, PAGE))
            check(translator.isPageTranslated(chapterFile, PAGE))
            val resolved = checkNotNull(TranslationEngineConfig.resolveInpaintModel(context))
            check(File(resolved).canonicalFile == File(modelDirectory(), MODEL_NAMES.first()).canonicalFile)
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
            mangas.update(MangaUpdate(manga.id, viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue.toLong()))
            val chapter = withContext(NonCancellable) {
                chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "$title/Chapter", name = "Chapter")),
                ).single().also { chapterId = it.id }
            }
            report.put("sourceSha256", sha(originalBytes)).put("priorDisplaySha256", sha(displayBytes))
                .put("otherPageSha256", sha(otherBytes)).put("chapterFilesBefore", JSONObject(beforeFiles))
                .put("nativeModelHashesBefore", JSONObject(priorModels))
            checkpoint("READER_START")
            ActivityScenario.launch<ReaderActivity>(
                ReaderActivity.newIntent(context, manga.id, chapter.id),
            ).use { scenario ->
                lateinit var activity: ReaderActivity
                scenario.onActivity { activity = it }
                val preparationDeadline = SystemClock.elapsedRealtime() + 20_000
                waitUntil(20_000) {
                    activity.viewModel.state.value.viewerChapters?.currChapter?.let {
                        it.pageLoader is DirectoryPageLoader && it.pages?.size == 2 &&
                            it.pages!!.all { page -> page.status == Page.State.Ready }
                    } == true
                }
                val page = checkNotNull(activity.viewModel.state.value.viewerChapters).currChapter.pages!!.first()
                check(DocumentsContract.getDocumentId(checkNotNull(page.uri)) == owned.documentId(pageFile))
                val preparation = JSONObject()
                report.put("readerPreparation", preparation)
                val streamBytes = checkNotNull(page.displayStream).invoke().use { it.readBytes() }
                preparation.put("targetStreamSha256", sha(streamBytes))
                assertArrayEquals("The target loader must read the blue PNG", displayBytes, streamBytes)
                val decoded = checkNotNull(BitmapFactory.decodeByteArray(streamBytes, 0, streamBytes.size))
                try {
                    preparation.put("targetPngWidth", decoded.width).put("targetPngHeight", decoded.height)
                    assertEquals(256, decoded.width)
                    assertEquals(384, decoded.height)
                } finally {
                    decoded.recycle()
                }
                // Data Ready precedes asynchronous holder decode. Select the exact test page once;
                // this is initial positioning, never a reload or a recovery action.
                scenario.onActivity { checkNotNull(it.viewModel.state.value.viewer).moveToPage(page) }
                var holderReady = false
                try {
                    val remaining = preparationDeadline - SystemClock.elapsedRealtime()
                    check(remaining > 0) { "Reader preparation deadline exhausted" }
                    waitUntil(remaining) {
                        holderReady = targetHolderReady(scenario, page)
                        holderReady
                    }
                } finally {
                    preparation.put("targetHolderDecodedAndVisible", holderReady)
                        .put("readerState", readerDiagnostics(scenario, page))
                    val frame = JSONObject()
                    preparation.put("lastFrame", frame)
                    savePatch(scenario, File(output, "01-before.png"), frame)
                    report.put("activityPixelsMeasured", true)
                    checkpoint("READER_PREPARATION_MEASURED")
                }
                val initialPixels = JSONObject()
                preparation.put("blueObservation", initialPixels)
                val initiallyBlue = awaitColor(scenario, Color.BLUE, initialPixels)
                savePatch(scenario, File(output, "01-before.png"), preparation.getJSONObject("lastFrame"))
                preparation.put("readerState", readerDiagnostics(scenario, page))
                checkpoint("READER_PREPARATION_MEASURED")
                assertTrue("The actual holder must first display the blue fixture", initiallyBlue)
                // Cancel the actual Compose method dialog before arming the storage fault.
                openMethodDialog(scenario)
                click(context.stringResource(MR.strings.action_cancel))
                waitUntil { activity.viewModel.state.value.dialog == null }
                assertEquals(
                    "Cancel must not mutate source, completion or materials",
                    beforeFiles,
                    fileHashes(chapterDirectory),
                )
                assertTrue("Cancel keeps the actual visible page", awaitColor(scenario, Color.BLUE))
                openMethodDialog(scenario)
                val reloadBefore = page.reloadFlow.value
                val writesBefore = owned.writeModes.size
                val pageId = owned.documentId(pageFile)
                check(owned.failNextWriteDocumentId.compareAndSet(null, pageId))
                checkpoint("FAULT_ARMED_REAL_METHOD_SELECTION")
                val event = instrumentation.uiAutomation.executeAndWaitForEvent(
                    { click(context.stringResource(MR.strings.rerender_boxfill)) },
                    { observed ->
                        observed.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                            observed.text.any { text ->
                                text.toString() == context.stringResource(MR.strings.reader_rerender_failed)
                            }
                    },
                    60_000,
                )
                @Suppress("DEPRECATION")
                event.recycle()
                waitUntil {
                    owned.failNextWriteDocumentId.get() == null && TranslationBusy.active.value == 0 &&
                        activity.viewModel.state.value.dialog == null
                }
                scenario.onActivity {
                    assertSame("Failure must retain this Activity", activity, it)
                    assertSame(
                        "Failure must retain the actual current ReaderPage",
                        page,
                        it.viewModel.state.value.viewerChapters!!.currChapter.pages!!.first(),
                    )
                }
                val pageWrites = owned.writeModes.drop(writesBefore).filter { it.first == pageId }
                assertEquals(
                    "One actual failed replacement and one actual original restoration",
                    2,
                    pageWrites.size,
                )
                assertTrue("Both provider writes must truncate", pageWrites.all { it.second == "wt" })
                assertArrayEquals(
                    "The failed write must restore the source to the visible file",
                    originalBytes,
                    pageFile.readBytes(),
                )
                assertArrayEquals(
                    "The preserved original must not change",
                    originalBytes,
                    File(materials, "001.orig.png").readBytes(),
                )
                assertArrayEquals(
                    "A different page must not change",
                    otherBytes,
                    File(chapterDirectory, "002.png").readBytes(),
                )
                val afterFiles = fileHashes(chapterDirectory)
                assertEquals(beforeFiles.keys, afterFiles.keys)
                beforeFiles.filterKeys { it != PAGE }.forEach { (name, hash) ->
                    assertEquals("Unrelated completion/material bytes changed: $name", hash, afterFiles[name])
                }
                assertTrue(translator.isPageTranslated(chapterFile, PAGE))
                assertFalse(page.isOriginalPreview)
                assertEquals(Page.State.Ready, page.status)
                // displayStream reads current disk bytes even when an already decoded holder is stale.
                assertArrayEquals(originalBytes, checkNotNull(page.displayStream).invoke().use { it.readBytes() })
                val failurePixels = JSONObject()
                report.put("afterFailurePixels", failurePixels)
                    .put("afterFailureReaderState", readerDiagnostics(scenario, page))
                val visibleOriginal = awaitColor(scenario, Color.WHITE, failurePixels)
                savePatch(scenario, File(output, "02-after-failure.png"))
                report.put("actualMethodSelection", "boxfill").put("actualFailureToastObserved", true)
                    .put("actualProviderWriteAttempts", pageWrites.size).put("oneShotTruncateFaultConsumed", true)
                    .put("diskRestoredSourceExact", true).put("preservedOriginalExact", true)
                    .put("otherPageAndMaterialsExact", true).put("pageStatusReady", true)
                    .put("diskStreamAlreadyReadsOriginal", true).put("reloadBefore", reloadBefore)
                    .put("reloadAfter", page.reloadFlow.value)
                    .put("actualHolderShowsRestoredOriginal", visibleOriginal)
                    .put("sameActivityAndReaderPage", true).put("chapterFilesAfter", JSONObject(afterFiles))
                checkpoint("STORAGE_RECOVERED_VERIFY_VISIBLE_HOLDER")
                assertTrue(
                    "The same visible holder must show the restored source without manual refresh",
                    visibleOriginal,
                )
                assertTrue(
                    "The same page must publish a reload after recovery",
                    page.reloadFlow.value > reloadBefore,
                )
            }
            report.put("passed", true)
        } catch (failure: Throwable) {
            caught = failure
            report.put("failureClass", failure.javaClass.simpleName)
        } finally {
            suspend fun cleanup(label: String, block: suspend () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    cleanupFailures += AssertionError(
                        "Owned cleanup failed: $label (${failure.javaClass.simpleName})",
                    )
                }
            }
            withContext(NonCancellable) {
                cleanup("activity operation idle") { waitUntil(30_000) { TranslationBusy.active.value == 0 } }
                val idle = TranslationBusy.active.value == 0
                if (idle) {
                    var storageRestored = !settingsTouched
                    cleanup("owned DB rows") {
                        chapterId?.let { id ->
                            check(chapters.getChapterById(id)?.mangaId == mangaId)
                            chapters.removeChaptersWithIds(listOf(id))
                            manager.forgetChapterOutputs(listOf(id))
                        }
                        mangaId?.let { id ->
                            val row = mangas.getMangaById(id)
                            check(row.source == LocalSource.ID && row.url == title)
                            val driver = Injekt.get<SqlDriver>()
                            check(
                                driver.execute(
                                    null,
                                    "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?",
                                    3,
                                ) {
                                    bindLong(0, id)
                                    bindLong(1, LocalSource.ID)
                                    bindString(2, title)
                                }.await() == 1L,
                            )
                            driver.notifyListeners("mangas", "chapters", "history")
                            cache.invalidate(id)
                        }
                    }
                    cleanup("preference value/presence/disk and effective storage") {
                        if (settingsTouched) {
                            settings.close()
                            waitUntil(20_000) { effectiveStorage(storage) == oldStorage }
                        }
                        storageRestored = true
                        report.put("settingsValuePresenceDiskAndEffectiveStorageRestored", true)
                    }
                    if (storageRestored) {
                        cleanup("private provider session") {
                            session?.close()
                            report.put("privateProviderSessionRemoved", true)
                        }
                    } else {
                        report.put("privateProviderSessionRetained", session != null)
                        cleanupFailures += AssertionError("Storage not restored; retain owned provider session")
                    }
                    cleanup("model files preserved") {
                        priorModels?.let { assertEquals(it, inpaintModelHashes()) }
                        report.put("inpaintModelFilesUnchanged", priorModels != null)
                    }
                } else {
                    cleanupFailures += AssertionError("Operation active; retain owned storage/session")
                }
            }
            report.put("cleanupPassed", cleanupFailures.isEmpty())
            if (cleanupFailures.isNotEmpty()) report.put("passed", false)
            checkpoint("FINALIZED")
        }
        caught?.let { failure ->
            cleanupFailures.forEach(failure::addSuppressed)
            throw failure
        }
        if (cleanupFailures.isNotEmpty()) {
            throw AssertionError("Owned UI fixture cleanup failed").apply {
                cleanupFailures.forEach(::addSuppressed)
            }
        }
    }

    private fun configure(settings: OnlineReaderSettings, tree: Uri) {
        val preferences = Injekt.get<TranslationPreferences>()
        settings.set(preferences.translationMasterEnabled, true)
        settings.set(preferences.translationEnabled, false)
        settings.set(preferences.liveTranslate, false)
        settings.set(preferences.nightReadEnabled, false)
        settings.set(preferences.keepMaterials, true)
        val reader = Injekt.get<ReaderPreferences>()
        settings.set(reader.nightReadMode, false)
        settings.set(reader.landscapeZoom, false)
        settings.set(reader.imageScaleType, 1)
        settings.set(reader.cropBorders, false)
        settings.set(reader.colorFilter, false)
        settings.set(reader.grayscale, false)
        settings.set(reader.invertedColors, false)
        settings.set(reader.customBrightness, false)
        settings.set(reader.showNavigationOverlayNewUser, false)
        settings.set(reader.showNavigationOverlayOnStart, false)
        settings.set(reader.readWithLongTap, true)
        val downloads = Injekt.get<DownloadPreferences>()
        settings.set(downloads.autoDownloadWhileReading, 0)
        settings.set(downloads.removeAfterReadSlots, -1)
        settings.set(downloads.removeAfterMarkedAsRead, false)
        settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, tree.toString())
    }

    private suspend fun requireIdle() {
        // Reject saved work before an idempotent startup restore can start its normal drain.
        // Inspect only the translation/night queue store; never credentials or all app settings.
        requireStoredQueuesEmpty()
        val store = TranslationStore(context)
        withTimeout(10_000) { check(store.restore().isEmpty()) }
        val marks = store.restorePendingMarks(Long.MIN_VALUE)
        check(marks.translate.isEmpty() && marks.night.isEmpty())
        val manager = Injekt.get<TranslationManager>()
        withTimeout(10_000) { manager.ensureRestored() }
        requireStoredQueuesEmpty()
        check(TranslationBusy.active.value == 0)
        check(manager.queueState.value.isEmpty())
        check(Injekt.get<DownloadManager>().queueState.value.isEmpty())
        check(!Injekt.get<TranslationEngineService>().warm.value)
        val workManager = WorkManager.getInstance(context)
        for (tag in listOf("Translator", "TranslatorHeartbeat")) {
            check(workManager.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS).all { it.state.isFinished })
        }
    }

    private fun requireStoredQueuesEmpty() {
        val queue = context.getSharedPreferences("active_translations", Context.MODE_PRIVATE)
        val known = setOf("paused", "paused_night", "pending_translate", "pending_night")
        // Unknown/malformed saved entries must not disappear through typed decoder filtering.
        check(queue.all.keys.all { it in known }) { "Stored work must be absent" }
        for (key in listOf("pending_translate", "pending_night")) {
            check(queue.getStringSet(key, emptySet()).orEmpty().isEmpty()) { "Stored pending marks must be absent" }
        }
        for (key in listOf("paused", "paused_night")) {
            if (queue.contains(key)) queue.getBoolean(key, false)
        }
    }

    private fun openMethodDialog(scenario: ActivityScenario<ReaderActivity>) {
        val bounds = viewerBounds(scenario)
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
        click(context.stringResource(MR.strings.reader_change_removal))
        var methodShown = false
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!methodShown && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity {
                methodShown = it.viewModel.state.value.dialog is ReaderViewModel.Dialog.ReRenderMethod
            }
            if (!methodShown) SystemClock.sleep(50)
        }
        check(methodShown) { "Actual method dialog did not open" }
    }

    private fun click(label: String) {
        checkOnlineReaderAction(
            context.packageName,
            label,
            "OWNED_UI_ACTION_MUST_NOT_EXIST",
            click = true,
            beforeClick = {},
        )
    }

    private fun viewerBounds(scenario: ActivityScenario<ReaderActivity>): Rect {
        val rect = Rect()
        scenario.onActivity {
            val viewer = checkNotNull(it.viewModel.state.value.viewer).getView()
            check(viewer.getGlobalVisibleRect(rect))
            // getGlobalVisibleRect is in the root View coordinate space; screenshots/touches
            // use screen coordinates, including any non-fullscreen window origin.
            val rootOrigin = IntArray(2)
            viewer.rootView.getLocationOnScreen(rootOrigin)
            rect.offset(rootOrigin[0], rootOrigin[1])
            check(rect.width() >= 80 && rect.height() >= 80)
        }
        return rect
    }

    private fun patch(scenario: ActivityScenario<ReaderActivity>, metrics: JSONObject? = null): Bitmap {
        val bounds = viewerBounds(scenario)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val x = bounds.centerX() - PATCH_SIZE / 2
            val y = bounds.centerY() - PATCH_SIZE / 2
            check(x >= 0 && y >= 0 && x + PATCH_SIZE <= screenshot.width && y + PATCH_SIZE <= screenshot.height)
            metrics?.put("screenshotWidth", screenshot.width)?.put("screenshotHeight", screenshot.height)
                ?.put("viewerBoundsOnScreen", rectJson(bounds))
                ?.put("patchBoundsOnScreen", rectJson(Rect(x, y, x + PATCH_SIZE, y + PATCH_SIZE)))
            if (metrics != null) {
                val grid = IntArray(16 * 16)
                for (gy in 0 until 16) {
                    for (gx in 0 until 16) {
                        val px = bounds.left + (gx + 0.5f) * bounds.width() / 16
                        val py = bounds.top + (gy + 0.5f) * bounds.height() / 16
                        check(px >= 0 && py >= 0 && px < screenshot.width && py < screenshot.height)
                        grid[gy * 16 + gx] = screenshot.getPixel(px.toInt(), py.toInt())
                    }
                }
                metrics.put("viewerGrid16x16", pixelSummary(grid))
            }
            return Bitmap.createBitmap(screenshot, x, y, PATCH_SIZE, PATCH_SIZE)
        } finally {
            screenshot.recycle()
        }
    }

    private fun awaitColor(
        scenario: ActivityScenario<ReaderActivity>,
        color: Int,
        diagnostics: JSONObject? = null,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var consecutive = 0
        val observations = JSONArray()
        diagnostics?.put("expectedArgb", color)?.put("rgbTolerance", 8)?.put("requiredFraction", 0.99)
            ?.put("requiredConsecutiveFrames", 2)?.put("observations", observations)
        while (SystemClock.elapsedRealtime() < deadline) {
            val frame = JSONObject()
            val bitmap = patch(scenario, frame)
            val pixels = IntArray(PATCH_SIZE * PATCH_SIZE)
            try {
                bitmap.getPixels(pixels, 0, PATCH_SIZE, 0, 0, PATCH_SIZE, PATCH_SIZE)
            } finally {
                bitmap.recycle()
            }
            val matches = pixels.count {
                kotlin.math.abs(Color.red(it) - Color.red(color)) <= 8 &&
                    kotlin.math.abs(Color.green(it) - Color.green(color)) <= 8 &&
                    kotlin.math.abs(Color.blue(it) - Color.blue(color)) <= 8
            }
            frame.put("patchPixels", pixelSummary(pixels)).put("expectedMatches", matches)
            if (observations.length() < 48) observations.put(frame)
            consecutive = if (matches >= pixels.size * 0.99) consecutive + 1 else 0
            if (consecutive >= 2) {
                diagnostics?.put("matched", true)
                return true
            }
            SystemClock.sleep(180)
        }
        diagnostics?.put("matched", false)
        return false
    }

    private fun savePatch(
        scenario: ActivityScenario<ReaderActivity>,
        destination: File,
        metrics: JSONObject? = null,
    ) {
        val bitmap = patch(scenario, metrics)
        try {
            val pixels = IntArray(PATCH_SIZE * PATCH_SIZE)
            bitmap.getPixels(pixels, 0, PATCH_SIZE, 0, 0, PATCH_SIZE, PATCH_SIZE)
            metrics?.put("patchPixels", pixelSummary(pixels))
            destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun targetHolderReady(scenario: ActivityScenario<ReaderActivity>, page: ReaderPage): Boolean {
        var ready = false
        scenario.onActivity {
            val view = checkNotNull(it.viewModel.state.value.viewer).getView()
            val holder = viewTree(view).filterIsInstance<PagerPageHolder>().firstOrNull { it.page === page }
            val image = holder?.let { item ->
                viewTree(item).filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
            }
            val visible = holder?.getGlobalVisibleRect(Rect()) == true
            ready = it.viewModel.state.value.currentPage == 1 && visible && image?.isReady == true &&
                image.isImageLoaded && image.sWidth == 256 && image.sHeight == 384
        }
        return ready
    }

    private fun readerDiagnostics(scenario: ActivityScenario<ReaderActivity>, page: ReaderPage): JSONObject {
        val result = JSONObject()
        scenario.onActivity {
            val state = it.viewModel.state.value
            val viewer = checkNotNull(state.viewer).getView()
            val holder = viewTree(viewer).filterIsInstance<PagerPageHolder>()
                .firstOrNull { item -> item.page === page }
            val image = holder?.let { item ->
                viewTree(item).filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
            }
            result.put("currentPage", state.currentPage).put("targetPageIndex", page.index)
                .put("targetStatus", page.status.javaClass.simpleName).put("reload", page.reloadFlow.value)
                .put("viewerClass", state.viewer?.javaClass?.simpleName).put("viewer", viewGeometry(viewer))
                .put("targetHolderPresent", holder != null).put("subsamplingPresent", image != null)
                .put("brightnessOverlay", state.brightnessOverlayValue)
                .put("windowFlags", it.window.attributes.flags)
                .put("secureWindow", it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                .put("windowHasFocus", it.window.decorView.hasWindowFocus())
            holder?.let { item -> result.put("targetHolder", viewGeometry(item)) }
            image?.let { item ->
                result.put(
                    "subsampling",
                    viewGeometry(item).put("ready", item.isReady).put("imageLoaded", item.isImageLoaded)
                        .put("sourceWidth", item.sWidth).put("sourceHeight", item.sHeight)
                        .put(
                            "scale",
                            item.scale.toDouble().takeIf { number -> number.isFinite() } ?: JSONObject.NULL,
                        ),
                )
            }
        }
        return result
    }

    private fun viewTree(root: View): List<View> {
        val views = mutableListOf(root)
        var index = 0
        while (index < views.size) {
            val parent = views[index++] as? ViewGroup ?: continue
            check(views.size + parent.childCount <= 128) { "Bounded reader view diagnostic exceeded" }
            for (child in 0 until parent.childCount) views += parent.getChildAt(child)
        }
        return views
    }

    private fun viewGeometry(view: View): JSONObject {
        val visible = Rect()
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        val rootOrigin = IntArray(2)
        view.rootView.getLocationOnScreen(rootOrigin)
        return JSONObject().put("width", view.width).put("height", view.height)
            .put("screenX", origin[0]).put("screenY", origin[1])
            .put("rootScreenX", rootOrigin[0]).put("rootScreenY", rootOrigin[1])
            .put("visible", view.getGlobalVisibleRect(visible)).put("globalVisibleRect", rectJson(visible))
            .put("shown", view.isShown).put("attached", view.isAttachedToWindow).put("alpha", view.alpha)
    }

    private fun rectJson(rect: Rect): JSONArray = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

    private fun pixelSummary(pixels: IntArray): JSONObject {
        fun near(pixel: Int, color: Int) = kotlin.math.abs(Color.red(pixel) - Color.red(color)) <= 8 &&
            kotlin.math.abs(Color.green(pixel) - Color.green(color)) <= 8 &&
            kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) <= 8
        val top = pixels.asSequence().groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
        return JSONObject().put("count", pixels.size).put("blue", pixels.count { near(it, Color.BLUE) })
            .put("white", pixels.count { near(it, Color.WHITE) })
            .put("green", pixels.count { near(it, Color.GREEN) })
            .put("black", pixels.count { near(it, Color.BLACK) })
            .put("topArgbCounts", JSONObject(top.associate { "%08x".format(it.key) to it.value }))
    }

    private suspend fun waitUntil(timeout: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(50) }
    }

    private fun effectiveStorage(storage: StorageManager): Uri? = (
        StorageManager::class.java.getDeclaredField("baseDir").apply { isAccessible = true }
            .get(storage) as UniFile?
        )?.uri

    private fun modelDirectory(): File {
        val files = context.filesDir.canonicalFile
        check(files.name == "files" && files.parentFile == File(context.applicationInfo.dataDir).canonicalFile)
        return File(files, "models").also { check(it.canonicalFile == it.absoluteFile && it.isDirectory) }
    }

    private fun inpaintModelHashes(): Map<String, String> = MODEL_NAMES.associateWith { name ->
        val file = File(modelDirectory(), name)
        check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() > 0)
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun imageBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 384, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try {
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun region() = RegionMaterial(
        quads = listOf(listOf(listOf(80f, 120f), listOf(176f, 120f), listOf(176f, 200f), listOf(80f, 200f))),
        angle = 0f, onArt = false, source = "こんにちは。", target = "안녕하세요.",
        bbox = listOf(80f, 120f, 176f, 200f), direction = "h", cx = 128f, cy = 160f, boxW = 96f, boxH = 80f,
    )

    private fun fileHashes(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to sha(it.readBytes()) }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PAGE = "001.png"
        const val PATCH_SIZE = 40
        val MODEL_NAMES = listOf("mit_aot_fixed512.ncnn.param", "mit_aot_fixed512.ncnn.bin")
    }
}
