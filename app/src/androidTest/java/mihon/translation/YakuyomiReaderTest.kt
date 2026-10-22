package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.util.Xml
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.TranslatingPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.source.hitomi.HitomiSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import tachiyomi.core.common.preference.Preference
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class YakuyomiReaderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val restorePreferences = mutableListOf<() -> Unit>()
    private val verifyPreferences = mutableListOf<() -> Unit>()
    private val restoredKeys = mutableSetOf<String>()

    private fun <T> preserve(preference: Preference<T>) {
        val existed = preference.isSet()
        val value = preference.get()
        restorePreferences += { if (existed) preference.set(value) else preference.delete() }
        restoredKeys += preference.key()
        verifyPreferences += {
            // Do not print expected/actual values: a restored preference may contain credentials.
            assertTrue("Restore presence mismatch: ${preference.key()}", preference.isSet() == existed)
            assertTrue("Restore value mismatch: ${preference.key()}", preference.get() == value)
        }
    }

    @Before
    fun preserveUserSettings() {
        val prefs = Injekt.get<TranslationPreferences>()
        val reader = Injekt.get<ReaderPreferences>()
        preserve(Injekt.get<StoragePreferences>().baseStorageDirectory)
        preserve(prefs.provider)
        preserve(prefs.model)
        preserve(prefs.apiBase)
        preserve(prefs.apiKeyFor("custom"))
        preserve(prefs.targetLangName)
        preserve(prefs.orientation)
        preserve(prefs.translationMasterEnabled)
        preserve(prefs.translationEnabled)
        preserve(prefs.liveTranslate)
        preserve(prefs.liveInpaintMethod)
        preserve(prefs.keepMaterials)
        preserve(prefs.privacyAcknowledged)
        preserve(prefs.nightReadEnabled)
        preserve(reader.fullscreen)
        preserve(reader.landscapeZoom)
        preserve(reader.showNavigationOverlayNewUser)
        preserve(reader.showNavigationOverlayOnStart)
    }

    @After
    fun restoreUserSettings() {
        val failures = mutableListOf<Throwable>()
        fun attempt(label: String, action: () -> Unit) {
            try {
                action()
            } catch (_: Throwable) {
                failures += AssertionError(label) // Never include preference values or encrypted key contents.
            }
        }
        try {
            restorePreferences.asReversed().forEachIndexed { index, action ->
                attempt("Preference restoration failed: ${restoredKeys.toList().asReversed()[index]}", action)
            }
            val shared = PreferenceManager.getDefaultSharedPreferences(context)
            // AndroidPreference.set/delete use apply(). An instrumentation process can terminate
            // before those asynchronous writes finish. Same-store commit waits for outstanding apply.
            attempt("Default preference store cleanup was not persisted") {
                assertTrue(shared.edit().commit())
            }
            verifyPreferences.forEachIndexed { index, action ->
                attempt("Preference restoration assertion failed: ${restoredKeys.elementAt(index)}", action)
            }
            attempt("Persisted preference cleanup differs from restored in-memory state") {
                val expected = shared.all.filterKeys { it in restoredKeys }
                val actual = mutableMapOf<String, Any>()
                val preferencesFile = File(
                    context.applicationInfo.dataDir,
                    "shared_prefs/${context.packageName}_preferences.xml",
                )
                preferencesFile.inputStream().use { input ->
                    val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG) {
                            val key = parser.getAttributeValue(null, "name")
                            if (key in restoredKeys) {
                                // Every preference changed by this class is a string or boolean.
                                actual[checkNotNull(key)] = when (parser.name) {
                                    "string" -> parser.nextText()
                                    "boolean" -> parser.getAttributeValue(null, "value").toBooleanStrict()
                                    else -> error("Unexpected restored preference type")
                                }
                            }
                        }
                        parser.next()
                    }
                }
                assertTrue(expected == actual)
            }
            if (failures.isNotEmpty()) {
                throw AssertionError("Reader test settings cleanup failed (${failures.size} checks)").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        } finally {
            restorePreferences.clear()
            verifyPreferences.clear()
            restoredKeys.clear()
        }
    }

    private fun sample(): Bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawColor(Color.LTGRAY)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            drawRoundRect(50f, 100f, 1150f, 800f, 100f, 100f, ink)
            ink.color = Color.BLACK
            ink.textSize = 72f
            drawText("こんにちは。", 170f, 350f, ink)
            drawText("今日もよろしくお願いします。", 100f, 490f, ink)
        }
    }

    private fun waitFor(timeout: Long, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < end) { "Timed out after ${timeout}ms" }
            SystemClock.sleep(100)
        }
    }

    private fun useFixtureStorage(root: File) {
        val manager = Injekt.get<StorageManager>()
        Injekt.get<StoragePreferences>().baseStorageDirectory.set(Uri.fromFile(root).toString())
        val expected = File(root, "downloads").canonicalFile
        // Preference writes are synchronous in memory, but StorageManager observes them on IO.
        // A preceding reader test may still be restoring its root. Never write this fixture until
        // the provider's effective directory, rather than only the preference, is our own root.
        waitFor(10000) {
            val uri = manager.getDownloadsDirectory()?.uri
            uri?.scheme == "file" && uri.path?.let { File(it).canonicalFile == expected } == true
        }
    }

    private suspend fun assertUnavailableOriginalShareHandled(
        scenario: ActivityScenario<ReaderActivity>,
        page: ReaderPage,
        copyToClipboard: Boolean,
    ) {
        val releaseFailure = CountDownLatch(1)
        lateinit var operation: Job
        try {
            scenario.onActivity { activity ->
                page.showOriginalPreview {
                    check(releaseFailure.await(10, TimeUnit.SECONDS)) { "Share fixture gate timed out" }
                    throw FileNotFoundException("Original preview fixture is unavailable")
                }
                activity.viewModel.openPageDialog(page)
                val parentJob = checkNotNull(activity.viewModel.viewModelScope.coroutineContext[Job])
                val previousJobs = parentJob.children.toSet()
                activity.viewModel.shareImage(copyToClipboard)
                // Hold stream failure until the launched share job is captured. Joining it waits for
                // the error event to reach the real activity; no competing eventFlow collector is used.
                operation = parentJob.children.single { it !in previousJobs }
                activity.viewModel.closeDialog()
            }
            releaseFailure.countDown()
            withTimeout(10000) { operation.join() }
            assertFalse("The share error must be handled within its coroutine", operation.isCancelled)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                assertTrue(page.isOriginalPreview)
                activity.viewModel.openPageDialog(page)
                activity.viewModel.toggleOriginalPreview()
            }
            waitFor(10000) { !page.isOriginalPreview }
            assertEquals(Page.State.Ready, page.status)
        } finally {
            releaseFailure.countDown()
            scenario.onActivity { activity ->
                page.clearOriginalPreview()
                activity.viewModel.closeDialog()
            }
        }
    }

    @Test
    fun nativeModelsThroughLiveReaderQueuePreserveOriginalAndRenderKorean() = runBlocking<Unit> {
        val prefs = Injekt.get<TranslationPreferences>()
        val storage = Injekt.get<StoragePreferences>()
        val reader = Injekt.get<ReaderPreferences>()
        val sources = Injekt.get<SourceManager>()
        withTimeout(20000) { sources.isInitialized.first { it } }
        val source = sources.get(HitomiSource.ID) as HitomiSource
        Injekt.get<GetFavorites>().await()
        val originalStorage = storage.baseStorageDirectory.get()
        val testRoot = File(context.filesDir, "reader-test-${System.nanoTime()}").apply { mkdirs() }
        useFixtureStorage(testRoot)
        prefs.provider.set("custom")
        prefs.model.set("fixture-model")
        prefs.targetLangName.set("Korean")
        prefs.orientation.set("horizontal")
        prefs.translationMasterEnabled.set(true)
        prefs.translationEnabled.set(false)
        prefs.keepMaterials.set(true)
        prefs.privacyAcknowledged.set(true)
        prefs.nightReadEnabled.set(false)
        reader.fullscreen.set(false)
        reader.landscapeZoom.set(false) // Show the entire wide fixture page in portrait screenshots.
        reader.showNavigationOverlayNewUser.set(false)
        reader.showNavigationOverlayOnStart.set(false)
        val mangaRepository = Injekt.get<MangaRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()
        val title = "Yakuyomi OCR fixture ${System.nanoTime()}"
        val url = "/fixture/${System.nanoTime()}"
        var testChapterId: Long? = null
        FixtureTranslationServer().use { server ->
            try {
                prefs.apiBase.set(server.base)
                prefs.apiKeyFor("custom").set("")
                val manga = mangaRepository.insertNetworkManga(
                    listOf(Manga.create().copy(source = source.id, url = url, title = title, initialized = true)),
                ).single()
                val mangaId = manga.id
                val chapter = chapterRepository.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = mangaId,
                            url = "$url/chapter",
                            name = "Fixture",
                            chapterNumber = 1.0,
                        ),
                    ),
                ).single()
                val chapterId = chapter.id
                testChapterId = chapterId
                val provider = Injekt.get<DownloadProvider>()
                val mangaDir = provider.getMangaDir(title, source).getOrThrow()
                val chapterDir = mangaDir.createDirectory(provider.getChapterDirName("Fixture", null, "$url/chapter"))!!
                val page = chapterDir.createFile("001.png")!!
                sample().let { bitmap ->
                    context.contentResolver.openOutputStream(page.uri)!!.use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                val originalBytes = context.contentResolver.openInputStream(page.uri)!!.use { it.readBytes() }
                assertEquals(
                    "Fixture must be discoverable through the reader's download provider before launch",
                    chapterDir.uri,
                    provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)?.uri,
                )
                prefs.liveTranslate.set(true)
                for (mode in listOf(ReadingMode.LEFT_TO_RIGHT, ReadingMode.WEBTOON, ReadingMode.DOUBLE_PAGE_LTR)) {
                    mangaRepository.update(MangaUpdate(mangaId, viewerFlags = mode.flagValue.toLong()))
                    val intent = ReaderActivity.newIntent(context, mangaId, chapterId)
                    ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                        lateinit var activity: ReaderActivity
                        scenario.onActivity { activity = it }
                        waitFor(300000) {
                            server.failure?.let { throw AssertionError("Fixture HTTP failure", it) }
                            PageTranslator(context).isPageTranslated(chapterDir, "001.png") &&
                                activity.viewModel.state.value.viewerChapters?.currChapter?.pages
                                    ?.firstOrNull()?.status == Page.State.Ready
                        }
                        val saved = chapterDir.findFile(".yakuyomi")!!.findFile("001.orig.png")!!
                        val preserved = context.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() }
                        assertArrayEquals(originalBytes, preserved)
                        val materials = chapterDir.findFile(".yakuyomi")!!.findFile("001.json")!!
                        val metadata = context.contentResolver.openInputStream(materials.uri)!!
                            .bufferedReader().use { it.readText() }
                        assertTrue(metadata.contains("안녕하세요"))
                        val rendered = context.contentResolver.openInputStream(page.uri)!!.use { it.readBytes() }
                        val originalHash = MessageDigest.getInstance("SHA-256").digest(originalBytes)
                        val renderedHash = MessageDigest.getInstance("SHA-256").digest(rendered)
                        assertFalse(originalHash.contentEquals(renderedHash))
                        BitmapFactory.decodeByteArray(rendered, 0, rendered.size).let {
                            assertEquals(1200, it.width)
                            assertEquals(900, it.height)
                            it.recycle()
                        }
                        SystemClock.sleep(500)
                        val output = context.getExternalFilesDir(null)!!
                        File(output, "yakuyomi-translated.png").writeBytes(rendered)
                        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
                            File(output, "yakuyomi-reader-${mode.name}.png").outputStream().use { stream ->
                                it.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            }
                            it.recycle()
                        }
                        val readerPage = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.first()
                        val manifestFile = chapterDir.findFile(".yakuyomi_translated")!!
                        val manifestBefore = manifestFile.openInputStream().use { it.readBytes() }
                        scenario.onActivity {
                            it.viewModel.openPageDialog(readerPage)
                            it.viewModel.toggleOriginalPreview()
                        }
                        waitFor(10000) { readerPage.isOriginalPreview }
                        assertArrayEquals(originalBytes, readerPage.displayStream!!().use { it.readBytes() })
                        SystemClock.sleep(500)
                        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
                            File(output, "yakuyomi-original-preview-${mode.name}.png").outputStream().use { stream ->
                                it.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            }
                            it.recycle()
                        }
                        scenario.onActivity {
                            it.viewModel.openPageDialog(readerPage)
                            it.viewModel.toggleOriginalPreview()
                        }
                        waitFor(10000) { !readerPage.isOriginalPreview }
                        assertArrayEquals(rendered, readerPage.displayStream!!().use { it.readBytes() })
                        if (mode == ReadingMode.LEFT_TO_RIGHT) {
                            for (copyToClipboard in listOf(false, true)) {
                                assertUnavailableOriginalShareHandled(scenario, readerPage, copyToClipboard)
                                assertArrayEquals(rendered, readerPage.displayStream!!().use { it.readBytes() })
                            }
                        }
                        assertArrayEquals(rendered, page.openInputStream().use { it.readBytes() })
                        assertArrayEquals(originalBytes, saved.openInputStream().use { it.readBytes() })
                        assertArrayEquals(manifestBefore, manifestFile.openInputStream().use { it.readBytes() })
                    }
                }
                assertEquals("Reopening a translated chapter must not call the LLM again", 1, server.requests.size)
                assertTrue(server.requests.first().contains("こんにちは"))
            } finally {
                prefs.liveTranslate.set(false)
                prefs.apiBase.set("")
                prefs.model.set("")
                storage.baseStorageDirectory.set(originalStorage)
                testChapterId?.let { chapterRepository.removeChaptersWithIds(listOf(it)) }
            }
        }
    }

    @Test
    fun sameReaderCanRestartCancelledAndFailedTranslationWithoutDuplicateWork() = runReaderRestart(normalRetry = false)

    @Test
    fun chapterListEnqueueRetriesFailedTranslationWithoutDuplicateWork() = runReaderRestart(normalRetry = true)

    private fun runReaderRestart(normalRetry: Boolean) = runBlocking<Unit> {
        val prefs = Injekt.get<TranslationPreferences>()
        val sources = Injekt.get<SourceManager>()
        val manager = Injekt.get<TranslationManager>()
        withTimeout(20000) { sources.isInitialized.first { it } }
        val source = sources.get(HitomiSource.ID) as HitomiSource
        Injekt.get<GetFavorites>().await()
        val testRoot = File(context.filesDir, "reader-restart-${System.nanoTime()}").apply { mkdirs() }
        useFixtureStorage(testRoot)
        prefs.provider.set("custom")
        prefs.model.set("fixture-model")
        prefs.targetLangName.set("Korean")
        prefs.orientation.set("horizontal")
        prefs.translationMasterEnabled.set(true)
        prefs.translationEnabled.set(false)
        prefs.liveTranslate.set(false)
        prefs.liveInpaintMethod.set("boxfill")
        prefs.keepMaterials.set(true)
        prefs.privacyAcknowledged.set(true)
        prefs.nightReadEnabled.set(false)
        val mangaRepository = Injekt.get<MangaRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()
        val releaseFirst = CountDownLatch(1)
        val firstRequest = CountDownLatch(1)
        var chapterId: Long? = null
        FixtureTranslationServer().use { server ->
            try {
                prefs.apiBase.set(server.base)
                prefs.apiKeyFor("custom").set("")
                server.expectClientDisconnect = setOf(1)
                server.beforeResponse = { number ->
                    server.malformedResponse = number <= 2
                    if (number == 1) {
                        firstRequest.countDown()
                        check(releaseFirst.await(30, TimeUnit.SECONDS)) { "Reader restart fixture gate timed out" }
                    }
                }
                val url = "/restart-fixture/${System.nanoTime()}"
                val manga = mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = source.id,
                            url = url,
                            title = "Reader restart ${System.nanoTime()}",
                            initialized = true,
                        ),
                    ),
                ).single()
                val chapter = chapterRepository.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "$url/chapter",
                            name = "Restart",
                            chapterNumber = 1.0,
                        ),
                    ),
                ).single()
                chapterId = chapter.id
                val provider = Injekt.get<DownloadProvider>()
                val chapterDir = provider.getMangaDir(manga.title, source).getOrThrow()
                    .createDirectory(provider.getChapterDirName(chapter.name, null, chapter.url))!!
                val file = chapterDir.createFile("001.png")!!
                sample().let { bitmap ->
                    context.contentResolver.openOutputStream(file.uri)!!.use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                val original = file.openInputStream().use { it.readBytes() }
                assertEquals(
                    "Restart fixture must be discoverable before launching the reader",
                    chapterDir.uri,
                    provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)?.uri,
                )
                val translator = PageTranslator(context)
                prefs.liveTranslate.set(true)
                ActivityScenario.launch<ReaderActivity>(
                    ReaderActivity.newIntent(context, manga.id, chapter.id),
                ).use { scenario ->
                    lateinit var activity: ReaderActivity
                    scenario.onActivity { activity = it }
                    suspend fun startRepeatedly() {
                        var started = emptyList<Job>()
                        scenario.onActivity {
                            val parent = checkNotNull(it.viewModel.viewModelScope.coroutineContext[Job])
                            val previous = parent.children.toSet()
                            repeat(3) { _ -> it.viewModel.startChapterTranslate() }
                            started = parent.children.filter { job -> job !in previous }.toList()
                        }
                        // All taps must finish enqueueing before releasing the blocked old request.
                        withTimeout(10000) { started.forEach { it.join() } }
                    }
                    waitFor(300000) {
                        server.failure?.let { throw AssertionError("Restart fixture HTTP failure", it) }
                        firstRequest.count == 0L
                    }
                    val loader = activity.viewModel.state.value.viewerChapters!!.currChapter.pageLoader
                    assertTrue(
                        "Must exercise the live wrapper, not direct PageTranslator calls",
                        loader is TranslatingPageLoader,
                    )
                    fun entries() = manager.queueState.value.filter {
                        it.chapter.id == chapter.id &&
                            it.kind != TranslationItem.Kind.NIGHT
                    }
                    scenario.onActivity { it.viewModel.stopChapterTranslate() }
                    assertTrue("Cancellation must remove the old queue entry", entries().isEmpty())
                    startRepeatedly()
                    waitFor(10000) {
                        val pending = entries()
                        assertTrue("Repeated taps must not add duplicate entries", pending.size <= 1)
                        assertTrue(
                            "Old and restarted chapter drains must not overlap",
                            TranslationBusy.active.value <= 1,
                        )
                        pending.singleOrNull()?.status in listOf(
                            TranslationItem.Status.QUEUE,
                            TranslationItem.Status.TRANSLATING,
                        )
                    }
                    // Stop cancels HTTP within the old drain. The replacement can already be
                    // TRANSLATING, but this fixture has not accepted its second request yet.
                    assertEquals(1, server.requests.size)
                    assertFalse(translator.isPageTranslated(chapterDir, "001.png"))
                    assertArrayEquals(original, file.openInputStream().use { it.readBytes() })
                    releaseFirst.countDown()
                    waitFor(300000) {
                        server.failure?.let { throw AssertionError("Restart fixture HTTP failure", it) }
                        entries().singleOrNull()?.status == TranslationItem.Status.ERROR
                    }
                    assertEquals(
                        "Cancelled entry must not erase the new entry; one retry must run",
                        2,
                        server.requests.size,
                    )
                    assertEquals(
                        "Stop must close the first HTTP request before headers",
                        listOf(1),
                        server.disconnectedRequests.toList(),
                    )
                    assertFalse(translator.isPageTranslated(chapterDir, "001.png"))
                    assertArrayEquals(original, file.openInputStream().use { it.readBytes() })
                    (loader as TranslatingPageLoader).onActivated()
                    assertEquals(
                        "Automatic reactivation must not retry an error",
                        TranslationItem.Status.ERROR,
                        entries().single().status,
                    )
                    if (normalRetry) {
                        // The chapter-list action uses normal enqueue, not the reader's atFront path.
                        repeat(3) { manager.translate(manga, listOf(chapter), method = "boxfill") }
                    } else {
                        startRepeatedly()
                    }
                    waitFor(300000) {
                        server.failure?.let { throw AssertionError("Restart fixture HTTP failure", it) }
                        translator.isPageTranslated(chapterDir, "001.png") && entries().isEmpty()
                    }
                    assertEquals("Each explicit retry batch must issue exactly one request", 3, server.requests.size)
                    val page = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!.first()
                    waitFor(10000) { page.status == Page.State.Ready }
                    val rendered = file.openInputStream().use { it.readBytes() }
                    assertFalse(original.contentEquals(rendered))
                    assertArrayEquals(rendered, page.displayStream!!().use { it.readBytes() })
                    BitmapFactory.decodeByteArray(rendered, 0, rendered.size).let {
                        assertEquals(1200, it.width)
                        assertEquals(900, it.height)
                        it.recycle()
                    }
                    assertArrayEquals(
                        original,
                        chapterDir.findFile(".yakuyomi")!!.findFile("001.orig.png")!!
                            .openInputStream().use { it.readBytes() },
                    )
                    repeat(3) { loader.onActivated() }
                    assertTrue("Completed activation must not enqueue again", entries().isEmpty())
                    assertEquals(3, server.requests.size)
                    assertTrue(
                        "Same loader must survive every retry",
                        loader === activity.viewModel.state.value.viewerChapters!!.currChapter.pageLoader,
                    )
                }
            } finally {
                releaseFirst.countDown()
                chapterId?.let { manager.cancelTranslation(listOf(it)) }
                prefs.liveTranslate.set(false)
                // Cancellation is cooperative: finish old native/HTTP work before @After restores
                // the user's endpoint and before removing the chapter used by the running drain.
                withTimeout(120000) { TranslationBusy.active.first { it == 0 } }
                chapterId?.let { chapterRepository.removeChaptersWithIds(listOf(it)) }
            }
        }
    }

    @Test
    fun malformedTranslationLeavesOriginalUnmarkedAndRetryCompletes() = runBlocking<Unit> {
        val prefs = Injekt.get<TranslationPreferences>()
        val oldBase = prefs.apiBase.get()
        val oldModel = prefs.model.get()
        val oldProvider = prefs.provider.get()
        val oldLang = prefs.targetLangName.get()
        val oldKey = prefs.apiKeyFor("custom").get()
        val oldLive = prefs.liveTranslate.get()
        val dir = File(context.cacheDir, "translation-retry-${System.nanoTime()}").apply { mkdirs() }
        val page = File(dir, "001.png")
        sample().let { bitmap ->
            page.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val original = page.readBytes()
        val chapterDir = requireNotNull(UniFile.fromFile(dir))
        FixtureTranslationServer().use { server ->
            try {
                prefs.provider.set("custom")
                prefs.model.set("fixture-model")
                prefs.targetLangName.set("Korean")
                prefs.apiBase.set(server.base)
                prefs.apiKeyFor("custom").set("")
                prefs.liveTranslate.set(false)
                val translator = PageTranslator(context)
                server.malformedResponse = true
                assertEquals(0, translator.translateChapter(chapterDir, "boxfill", nightMaterials = false))
                assertEquals(1, server.requests.size)
                assertFalse(translator.isPageTranslated(chapterDir, "001.png"))
                assertArrayEquals(original, page.readBytes())
                server.malformedResponse = false
                assertEquals(1, translator.translateChapter(chapterDir, "boxfill", nightMaterials = false))
                assertEquals(2, server.requests.size)
                assertTrue(translator.isPageTranslated(chapterDir, "001.png"))
                assertArrayEquals(original, File(dir, ".yakuyomi/001.orig.png").readBytes())
                assertFalse(original.contentEquals(page.readBytes()))
            } finally {
                prefs.provider.set(oldProvider)
                prefs.apiBase.set(oldBase)
                prefs.model.set(oldModel)
                prefs.targetLangName.set(oldLang)
                prefs.apiKeyFor("custom").set(oldKey)
                prefs.liveTranslate.set(oldLive)
            }
        }
    }

    @Test
    fun failedOriginalBackupLeavesDownloadUntouched() = runBlocking<Unit> {
        val directory = File(context.cacheDir, "failed-backup-${System.nanoTime()}").apply { mkdirs() }
        File(directory, ".yakuyomi").writeText("block directory creation")
        val original = sample()
        val page = File(directory, "001.png")
        page.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val bytes = page.readBytes()
        val translated = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        try {
            val saved = PageTranslator(context).persistLivePage(
                requireNotNull(UniFile.fromFile(directory)),
                requireNotNull(UniFile.fromFile(page)),
                original,
                translated,
                null,
                "boxfill",
            )
            assertFalse(saved)
            assertArrayEquals(bytes, page.readBytes())
        } finally {
            original.recycle()
            translated.recycle()
        }
    }
}
