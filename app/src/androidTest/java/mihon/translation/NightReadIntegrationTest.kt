package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.nightread.NightAvailability
import eu.kanade.tachiyomi.data.nightread.NightGovernor
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.RegionMaterial
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.NightReadRenderer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

/**
 * Opt in with nightReadNative=true; missing native models then fail explicitly. No translation API.
 * Native inference, synthetic chapter persistence, and the real reader menu are distinct evidence.
 */
@RunWith(AndroidJUnit4::class)
class NightReadIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val translation get() = Injekt.get<TranslationPreferences>()
    private val reader get() = Injekt.get<ReaderPreferences>()
    private val manager get() = Injekt.get<TranslationManager>()

    @Test
    fun realModelsDetectTextAndReturnNonemptyCharacterMaskForExplicitReference() = runBlocking<Unit>(Dispatchers.IO) {
        requireNativeModels()
        assumeTrue(
            "Requires nightReadCharacterFixture=true and an explicitly reviewed, separately seeded reference image",
            InstrumentationRegistry.getArguments().getString("nightReadCharacterFixture") == "true",
        )
        val file = File(context.filesDir, "nightread-character-fixture.jpg")
        assertTrue("The reviewed reference must be seeded separately; this test never downloads artwork", file.isFile)
        val page = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
        val report = JSONObject().put("referenceSha256", digest(file.readBytes()))
            .put("synthetic", false).put("translationApiUsed", false)
            .put(
                "claim",
                "Nonempty native character inference, not complete segmentation accuracy or pixel preservation",
            )
            .put("width", page.width).put("height", page.height)
        try {
            val start = SystemClock.elapsedRealtime()
            Detector(checkNotNull(TranslationEngineConfig.resolveDetectorModel(context))).use { detector ->
                val detected = detector.detect(page)
                try {
                    assertTrue("The reference must exercise actual text detection", detected.lines.isNotEmpty())
                    assertEquals(page.width, detected.textMask.width)
                    assertEquals(page.height, detected.textMask.height)
                    report.put("textLines", detected.lines.size)
                } finally {
                    detected.textMask.recycle()
                }
            }
            report.put("detectorCreateAndForwardMs", SystemClock.elapsedRealtime() - start)
            val (yolo, cseg) = TranslationEngineConfig.resolveCharSegModels(context)
            report.put("yoloPresent", yolo != null).put("csegPresent", cseg != null)
            val segmentStart = SystemClock.elapsedRealtime()
            checkNotNull(NightReadRenderer.charSegmenter(yolo, cseg)).use { segmenter ->
                val mask = segmenter.segment(page)
                assertEquals(
                    "Native charseg must return one value per source pixel",
                    page.width * page.height,
                    mask.size,
                )
                val count = mask.count { it }
                assertTrue("A blank mask cannot substantiate character inference", count > 0)
                assertTrue("The whole page is not a useful character mask", count < mask.size)
                report.put("characterPixels", count).put("totalPixels", mask.size)
            }
            report.put("segmenterCreateAndForwardMs", SystemClock.elapsedRealtime() - segmentStart)
            report.put("passed", true)
        } finally {
            page.recycle()
            writeReport("native-reference", report)
        }
    }

    @Test
    fun actualReaderMenuGeneratesNativeNightFilesAndFloatingControlsSwitchSavedStreams() = runBlocking<Unit> {
        requireNativeModels()
        val sources = Injekt.get<SourceManager>()
        withTimeout(20000) { sources.isInitialized.first { it } }
        assertTrue(sources.get(LocalSource.ID) is LocalSource)
        Injekt.get<GetFavorites>().await()
        val chapters = Injekt.get<ChapterRepository>()
        val root = File(context.filesDir, "nightread-reader-${System.nanoTime()}").apply { check(mkdirs()) }
        var chapterId: Long? = null
        val originalNightPaused = manager.isNightPaused.value
        val report = JSONObject().put("synthetic", true).put("translationApiUsed", false)
            .put("entryPoint", "Real ReaderPageActionsDialog accessibility action; real floating switch/tier actions")
        try {
            OnlineReaderSettings(context).use { settings ->
                configure(settings)
                settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
                waitFor("synthetic local storage") {
                    Injekt.get<StorageManager>().getLocalSourceDirectory()?.uri == Uri.fromFile(File(root, "local"))
                }
                val title = "Night reader fixture ${System.nanoTime()}"
                val directory = File(root, "local/$title/Chapter").apply { check(mkdirs()) }
                val protected = writeTranslatedFixture(directory, "001.png")
                val mangaRepository = Injekt.get<MangaRepository>()
                val manga = mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(source = LocalSource.ID, url = title, title = title, initialized = true),
                    ),
                ).single()
                mangaRepository.update(
                    MangaUpdate(
                        manga.id,
                        viewerFlags = (
                            ReadingMode.LEFT_TO_RIGHT.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue
                            ).toLong(),
                    ),
                )
                val chapter = chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "$title/Chapter", name = "Chapter")),
                ).single()
                chapterId = chapter.id
                val start = SystemClock.elapsedRealtime()
                ActivityScenario.launch<ReaderActivity>(
                    ReaderActivity.newIntent(context, manga.id, chapter.id),
                ).use { scenario ->
                    val activity = ready(scenario)
                    val page = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.single()
                    assertArrayEquals(protected.getValue("001.png"), streamBytes(page))
                    waitFor("night availability scan") {
                        activity.viewModel.state.value.nightAvailability == NightAvailability.NONE
                    }
                    scenario.onActivity { current -> current.viewModel.openPageDialog(page) }
                    action(activity.stringResource(MR.strings.action_nightread_chapter))
                    // The translation master is OFF. An actual night queue must still finish independently.
                    assertFalse(translation.translationMasterEnabled.get())
                    waitFor("native night chapter completion", 180000) {
                        activity.viewModel.state.value.nightAvailability == NightAvailability.READY &&
                            manager.queueState.value.none { item -> item.chapter.id == chapter.id }
                    }
                    val materials = File(directory, NightPages.DIR)
                    val standardName = NightPages.levelName("001.png", NightLevel.STANDARD)
                    val moreName = NightPages.levelName("001.png", NightLevel.MORE)
                    val standard = File(materials, standardName)
                    assertTrue(standard.isFile)
                    assertTrue(File(materials, NightPages.rulesStampName("001.png")).isFile)
                    assertFalse(materials.listFiles().orEmpty().any { file -> file.name.endsWith(".tmp") })
                    val nightBytes = standard.readBytes()
                    report.put("darkenedWhitePixels", assertDarkenedNight(nightBytes, protected.getValue("001.png")))
                    // Close the reader bars by their normal toggle callback so the floating controls are visible.
                    scenario.onActivity { current ->
                        if (current.viewModel.state.value.menuVisible) current.toggleMenu()
                    }
                    action(activity.stringResource(MR.strings.nightfab_mode))
                    waitFor("real floating night switch") { reader.nightReadMode.get() }
                    waitFor("current reader uses standard night stream") { streamBytes(page).contentEquals(nightBytes) }
                    action(activity.stringResource(MR.strings.nightfab_level_more))
                    waitFor("real floating more tier") { readerTier() == NightLevel.MORE }
                    val expectedMore = File(materials, if (File(materials, moreName).isFile) moreName else standardName)
                    waitFor("current reader uses more or deduplicated standard") {
                        streamBytes(page).contentEquals(expectedMore.readBytes())
                    }
                    // Master OFF must restore the actual stream and notify the same displayed page.
                    val revision = page.reloadFlow.value
                    settings.set(translation.nightReadEnabled, false)
                    waitFor("night master OFF reload") { page.reloadFlow.value > revision }
                    assertArrayEquals(protected.getValue("001.png"), streamBytes(page))
                    settings.set(translation.nightReadEnabled, true)
                    waitFor("night master ON restores saved tier") {
                        streamBytes(page).contentEquals(expectedMore.readBytes())
                    }
                    // Night -> day needs a wake tap when the control has faded; use its accessibility
                    // action again only if the first tap was the documented wake operation.
                    action(activity.stringResource(MR.strings.nightfab_mode))
                    SystemClock.sleep(150)
                    if (reader.nightReadMode.get()) action(activity.stringResource(MR.strings.nightfab_mode))
                    waitFor("real floating day switch") { !reader.nightReadMode.get() }
                    assertArrayEquals(protected.getValue("001.png"), streamBytes(page))
                    assertEquals(Page.State.Ready, page.status)
                    assertEquals(1, activity.viewModel.state.value.currentPage)
                    report.put("moreDistinct", File(materials, moreName).isFile)
                        .put("standardSha256", digest(nightBytes))
                        .put("rulesVersion", NightPages.RULES_VERSION)
                        .put("generationAndUiWallMs", SystemClock.elapsedRealtime() - start)
                        .put("translationMasterOffDuringGeneration", true)
                    assertProtected(directory, protected)
                }
                assertFalse(
                    "Night work must not load the OCR/translation engine",
                    Injekt.get<TranslationEngineService>().warm.value,
                )
                assertEquals(0, TranslationBusy.active.value)
                report.put("passed", true)
            }
        } finally {
            chapterId?.let { id ->
                manager.cancel(listOf(id), TranslationItem.Kind.NIGHT)
                waitFor("isolated night queue cleanup", 30000) { manager.queueState.value.none { it.chapter.id == id } }
                // Removal from the public queue is immediate; native close completes before this
                // consumer releases its mutex. Await that barrier before deleting its private input.
                val drain = TranslationManager::class.java.getDeclaredField("nightDrainMutex").apply {
                    isAccessible = true
                }.get(manager) as Mutex
                withTimeout(30000) { drain.withLock {} }
                chapters.removeChaptersWithIds(listOf(id))
            }
            if (originalNightPaused) manager.pause(night = true)
            root.deleteRecursively() // Only this invocation's private synthetic tree.
            writeReport("reader-native", report)
        }
    }

    @Test
    fun cooperativeStopPreservesCompletedPageAndResumeIsolatesUnreadablePage() = runBlocking<Unit>(Dispatchers.IO) {
        requireNativeModels()
        val root = File(context.cacheDir, "nightread-resume-${System.nanoTime()}").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val governor = NightGovernor(
            scope = scope,
            busy = MutableStateFlow(false),
            pagesCap = { 1 },
            heapFree = { Runtime.getRuntime().let { it.maxMemory() - it.totalMemory() + it.freeMemory() } },
            maxMemory = Runtime.getRuntime().maxMemory(),
            thermalThrottled = { false },
            setNice = { tid, nice -> Process.setThreadPriority(tid, nice) },
            myTid = Process::myTid,
            now = SystemClock::elapsedRealtime,
            log = {},
        )
        val report = JSONObject().put("synthetic", true).put("translationApiUsed", false)
            .put("entryPoint", "PageTranslator.renderNightChapter; cooperative page boundary stop and native resume")
        try {
            OnlineReaderSettings(context).use { settings ->
                configure(settings)
                val protected = writeTranslatedFixture(root, "001.png").toMutableMap()
                File(root, "003.png").writeBytes(sample(korean = false))
                protected["003.png"] = File(root, "003.png").readBytes()
                File(root, "002.png").writeText("deliberately unreadable synthetic image")
                protected["002.png"] = File(root, "002.png").readBytes()
                val translationError = "002.png\tExisting synthetic translation error"
                File(root, ".yakuyomi_errors.txt").writeText(translationError)
                val stop = AtomicBoolean(false)
                val translator = PageTranslator(context)
                val chapter = checkNotNull(UniFile.fromFile(root))
                val first = translator.renderNightChapter(
                    chapter,
                    governor,
                    onProgress = { done, _ -> if (done == 1) stop.set(true) },
                    shouldStop = stop::get,
                )
                assertEquals("Exactly the first page must commit before cooperative stop", 1, first)
                val standard = File(root, "${NightPages.DIR}/${NightPages.levelName("001.png", NightLevel.STANDARD)}")
                assertTrue(standard.isFile)
                val completed = standard.readBytes()
                val completedAt = standard.lastModified()
                assertFalse(
                    File(root, "${NightPages.DIR}/${NightPages.levelName("003.png", NightLevel.STANDARD)}").exists(),
                )
                assertProtected(root, protected)
                stop.set(false)
                val resumed = translator.renderNightChapter(chapter, governor)
                assertEquals("Fresh first page + successful third page; corrupt second page is not done", 2, resumed)
                assertArrayEquals("Resume must keep the already committed first page", completed, standard.readBytes())
                assertEquals("Fresh first page must not be re-encoded", completedAt, standard.lastModified())
                val materialNames = File(root, NightPages.DIR).listFiles().orEmpty().map { it.name }.toSet()
                assertTrue(NightPages.rulesStampName("003.png") in materialNames)
                assertFalse(NightPages.hasAnyArtifact(materialNames, "002.png"))
                assertFalse(materialNames.any { it.endsWith(".tmp") })
                val errors = File(root, ".yakuyomi_errors.txt").readLines()
                assertTrue("Night errors must not erase translation errors", translationError in errors)
                assertTrue(
                    "Failed night page must remain visible as a night error",
                    errors.any { it.startsWith("(night)\t002.png\t") },
                )
                assertProtected(root, protected)
                report.put("doneAtStop", first).put("doneAfterResume", resumed)
                    .put("corruptPageHasNoCompletion", true).put("freshPageNotRewritten", true).put("passed", true)
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
            writeReport("stop-resume", report)
        }
    }

    private suspend fun requireNativeModels() {
        assumeTrue(
            "Requires explicit nightReadNative=true; optional native models are not bundled into the APK",
            InstrumentationRegistry.getArguments().getString("nightReadNative") == "true",
        )
        assertTrue("Night native integration requires ARM64", Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
        assertTrue(
            "An existing user queue must not be touched by this isolated test",
            manager.queueState.value.isEmpty(),
        )
        assertEquals("An active translation must not be interrupted", 0, TranslationBusy.active.value)
        Injekt.get<TranslationEngineService>().shutdown()
        assertTrue("Explicit native test requires prepared DBNet", TranslationEngineConfig.detectorResolvable(context))
        assertTrue(
            "Explicit native test requires at least one complete charseg pair",
            TranslationEngineConfig.charSegResolvable(context),
        )
    }

    private fun configure(settings: OnlineReaderSettings) {
        settings.set(Injekt.get<BasePreferences>().incognitoMode, false)
        settings.set(Injekt.get<BasePreferences>().downloadedOnly, false)
        settings.set(translation.translationMasterEnabled, false)
        settings.set(translation.liveTranslate, false)
        settings.set(translation.nightReadEnabled, true)
        settings.set(translation.nightReadGenerate, false)
        settings.set(translation.nightReadSkipColor, false)
        settings.set(translation.nightReadPageConcurrency, "1")
        settings.set(translation.nightReadFillLevel, NightLevel.STANDARD.prefValue)
        settings.set(translation.nightReadBg, 16)
        settings.set(translation.nightReadInk, 205)
        settings.set(translation.nightReadEdgeInk, 170)
        settings.set(translation.nightReadStrokeObjV, 190)
        settings.set(translation.nightReadDimCeil, 140)
        settings.set(translation.nightReadGlowCap, 112)
        settings.set(reader.nightReadMode, false)
        settings.set(reader.pageTransitions, false)
        settings.set(reader.landscapeZoom, false)
        settings.set(reader.dualPageSplitPaged, false)
        settings.set(reader.cropBorders, false)
        settings.set(reader.showNavigationOverlayNewUser, false)
        settings.set(reader.showNavigationOverlayOnStart, false)
    }

    private fun writeTranslatedFixture(directory: File, name: String): Map<String, ByteArray> {
        val materials = File(directory, NightPages.DIR).apply { check(mkdirs()) }
        File(directory, name).writeBytes(sample(korean = true))
        File(materials, "001.orig.png").writeBytes(sample(korean = false))
        val region = RegionMaterial(
            quads = listOf(listOf(listOf(90f, 220f), listOf(660f, 220f), listOf(660f, 390f), listOf(90f, 390f))),
            angle = 0f,
            onArt = false,
            source = "こんにちは。今日もよろしく。",
            target = "안녕하세요. 오늘도 잘 부탁해요.",
            bbox = listOf(90f, 220f, 660f, 390f),
            direction = "h",
            cx = 375f,
            cy = 305f,
            boxW = 570f,
            boxH = 170f,
        )
        File(materials, "001.json").writeText(Json.encodeToString(PageMaterials("boxfill", listOf(region))))
        File(directory, ".yakuyomi_translated").writeText(name)
        return listOf(name, "${NightPages.DIR}/001.orig.png", "${NightPages.DIR}/001.json", ".yakuyomi_translated")
            .associateWith { File(directory, it).readBytes() }
    }

    private fun sample(korean: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeWidth = 7f
            }
            paint.style = Paint.Style.STROKE
            canvas.drawRect(30f, 30f, 1170f, 870f, paint)
            canvas.drawRoundRect(65f, 130f, 700f, 470f, 90f, 90f, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 52f
            canvas.drawText(if (korean) "안녕하세요." else "こんにちは。", 105f, 280f, paint)
            canvas.drawText(if (korean) "오늘도 잘 부탁해요." else "今日もよろしく。", 105f, 365f, paint)
            // Neutral, fully clothed schematic person, with no external artwork in the saved/UI fixture.
            paint.style = Paint.Style.STROKE
            canvas.drawOval(810f, 170f, 1070f, 480f, paint)
            canvas.drawPath(
                Path().apply {
                    moveTo(800f, 320f)
                    lineTo(820f, 150f)
                    lineTo(1000f, 110f)
                    lineTo(1090f, 210f)
                    lineTo(1080f, 360f)
                },
                paint,
            )
            canvas.drawOval(860f, 300f, 895f, 345f, paint)
            canvas.drawOval(990f, 300f, 1025f, 345f, paint)
            canvas.drawArc(910f, 375f, 980f, 420f, 0f, 180f, false, paint)
            canvas.drawPath(
                Path().apply {
                    moveTo(900f, 480f)
                    lineTo(900f, 530f)
                    lineTo(780f, 580f)
                    lineTo(750f, 810f)
                    lineTo(1130f, 810f)
                    lineTo(1100f, 580f)
                    lineTo(990f, 530f)
                    lineTo(990f, 480f)
                },
                paint,
            )
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertProtected(directory: File, expected: Map<String, ByteArray>) {
        expected.forEach { (name, bytes) ->
            assertArrayEquals("Night generation must preserve $name", bytes, File(directory, name).readBytes())
        }
    }

    private fun ready(scenario: ActivityScenario<ReaderActivity>): ReaderActivity {
        lateinit var activity: ReaderActivity
        waitFor("actual local reader") {
            scenario.onActivity { activity = it }
            val chapter = activity.viewModel.state.value.viewerChapters?.currChapter
            chapter?.pageLoader is DirectoryPageLoader && chapter.pages?.size == 1 &&
                chapter.pages?.single()?.status == Page.State.Ready && activity.viewModel.state.value.viewer != null
        }
        return activity
    }

    private fun readerTier() = NightLevel.fromPref(translation.nightReadFillLevel.get())

    /**
     * Floating controls are not a scrolling dialog. A missing tier during recomposition must only
     * be polled: scrolling the underlying one-page ViewPager selects its end-of-chapter transition,
     * opens the reader bars, and consequently hides the very control we are trying to inspect.
     */
    @Suppress("DEPRECATION")
    private fun action(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val packages = linkedSetOf<String>()
        val visibleLabels = linkedSetOf<String>()

        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser) {
                val text = node.text?.toString()
                val description = node.contentDescription?.toString()
                if (visibleLabels.size < 80) {
                    text?.takeIf { it.isNotBlank() }?.let(visibleLabels::add)
                    description?.takeIf { it.isNotBlank() }?.let(visibleLabels::add)
                }
                if (text == label || description == label) return AccessibilityNodeInfo.obtain(node)
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    find(child)?.let { return it }
                } finally {
                    child.recycle()
                }
            }
            return null
        }

        fun click(node: AccessibilityNodeInfo): Boolean {
            var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
            try {
                repeat(8) {
                    val current = candidate ?: return false
                    if (current.packageName?.toString() != context.packageName) return false
                    if (current.isVisibleToUser && current.isEnabled && current.isClickable) {
                        return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    candidate = current.parent
                    current.recycle()
                }
                return false
            } finally {
                candidate?.recycle()
            }
        }

        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            // API 34 added this public invalidation; reacquire every node after clearing the cache.
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    packages += root.packageName?.toString().orEmpty()
                    if (root.packageName?.toString() == context.packageName) {
                        val found = find(root)
                        if (found != null) {
                            try {
                                if (click(found)) return
                            } finally {
                                found.recycle()
                            }
                        }
                    }
                } finally {
                    root.recycle()
                }
            }
            // No scroll, coordinate guess, ViewModel action, or setting mutation as a fallback.
            SystemClock.sleep(100)
        }

        var readerState = "No resumed ReaderActivity"
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ReaderActivity>().firstOrNull()
            activity?.viewModel?.state?.value?.let { state ->
                readerState = "menu=${state.menuVisible}; availability=${state.nightAvailability}; " +
                    "dialog=${state.dialog?.javaClass?.simpleName}; page=${state.currentPage}"
            }
        }
        val diagnostic = JSONObject().put("expectedLabel", label)
            .put("observedPackages", JSONArray(packages.toList()))
            .put("visibleLabels", JSONArray(visibleLabels.toList()))
            .put("nightModePreference", reader.nightReadMode.get())
            .put("nightMasterPreference", translation.nightReadEnabled.get())
            .put("readerState", readerState)
            .put("scrollActionsSent", 0)
        writeReport("action-failure", diagnostic)
        error("Night reader action unavailable: $diagnostic")
    }

    private fun streamBytes(page: ReaderPage): ByteArray =
        checkNotNull(page.displayStream).invoke().use { it.readBytes() }

    private fun assertDarkenedNight(bytes: ByteArray, sourceBytes: ByteArray): Int {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val source = checkNotNull(BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size))
        assertNotNull(bitmap)
        try {
            assertEquals(1200, bitmap!!.width)
            assertEquals(900, bitmap.height)
            var darkened = 0
            // Count actual decoded pixel changes, not just PNG -> WebP byte differences.
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (Color.red(source.getPixel(x, y)) > 240 && Color.red(bitmap.getPixel(x, y)) < 200) {
                        darkened++
                    }
                }
            }
            assertTrue("Night output must visibly darken a substantial white region", darkened > 1000)
            return darkened
        } finally {
            bitmap?.recycle()
            source.recycle()
        }
    }

    private fun waitFor(label: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Night reader timeout: $label" }
            SystemClock.sleep(100)
        }
    }

    private fun writeReport(kind: String, report: JSONObject) {
        val file = File(context.getExternalFilesDir(null), "nightread-$kind-${System.currentTimeMillis()}.json")
        file.writeText(report.toString(2))
        println("NIGHTREAD_INTEGRATION_REPORT=${file.absolutePath}")
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
