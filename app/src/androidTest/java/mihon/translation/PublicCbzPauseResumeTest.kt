package mihon.translation

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStatsStore
import eu.kanade.tachiyomi.data.translation.TranslationStore
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Real singleton queue + public translate/pauseChapter/resumeChapter + LocalSource + CBZ I/O.
 * Scripted inference only; no Activity, native model, HTTP, process death or external SAF claim.
 * Depends on the existing package-internal OnlineReaderSettings in OnlineReaderFixtures.kt.
 */
@RunWith(AndroidJUnit4::class)
class PublicCbzPauseResumeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 120_000)
    fun pauseAfterFirstCbzPageThenResumeDoesNotInferCompletedPageAgain(): Unit = runBlocking {
        assertEquals("Isolated audit installation required", AUDIT_PACKAGE, context.packageName)
        check(InstrumentationRegistry.getArguments().getString("cbzPauseAudit") == "true") {
            "Explicit -e cbzPauseAudit true is required; absent opt-in is not a pass/skip"
        }
        // No preferences, singleton service, DB, queue, or work mutation before this gate.
        val manager = Injekt.get<TranslationManager>()
        val service = Injekt.get<TranslationEngineService>()
        val workManager = WorkManager.getInstance(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.queueState.value.isEmpty())
        assertTrue(Injekt.get<DownloadManager>().queueState.value.isEmpty())
        assertEquals(0, TranslationBusy.active.value)
        assertFalse("Audit service must be cold before fake injection", service.warm.value)
        assertEquals(null, serviceField("engine").get(service))
        assertTrue(TAGS.all { tag -> work(workManager, tag).all { it.state.isFinished } })
        assertFalse(hasProgressNotification(notifications))
        val persisted = TranslationStore(context)
        assertTrue(persisted.restore().isEmpty())
        val pending = persisted.restorePendingMarks(Long.MIN_VALUE)
        assertTrue(pending.translate.isEmpty() && pending.night.isEmpty())
        val savedQueue = SavedEmptyQueue(context)

        val uuid = UUID.randomUUID().toString()
        val title = "CBZ pause fixture $uuid"
        val root = File(context.filesDir.canonicalFile, "cbz-pause-$uuid")
        val statsName = "cbz-pause-stats-$uuid"
        val preferences = Injekt.get<TranslationPreferences>()
        val storage = Injekt.get<StorageManager>()
        val settings = OnlineReaderSettings(context)
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val oldBase = effectiveBase(storage)
        val oldTranslatePaused = manager.isTranslatePaused.value
        val oldNightPaused = manager.isNightPaused.value
        val beforeWork = TAGS.associateWith { tag -> work(workManager, tag).mapTo(hashSetOf()) { it.id } }
        val queueTranslator = managerField("pageTranslator").get(manager) as PageTranslator
        val statsField = PageTranslator::class.java.getDeclaredField("statsStore").apply { isAccessible = true }
        val previousStats = statsField.get(queueTranslator)
        val privateStatsContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(statsName, mode)
        }
        val ledger = Ledger()
        val firstEngine = ScriptedEngine(ledger, holdSecond = true)
        val resumeEngine = ScriptedEngine(ledger, holdSecond = false)
        var rootCreated = false
        var mangaId: Long? = null
        var chapterId: Long? = null
        var caught: Throwable? = null
        var drainSettled = false
        val cleanupFailures = mutableListOf<Throwable>()
        try {
            manager.ensureRestored()
            assertTrue(manager.queueState.value.isEmpty())
            check(root.parentFile == context.filesDir.canonicalFile && root.mkdir())
            rootCreated = true
            // Disable automatic loading before opening the master gate. Never edit endpoint/key.
            settings.set(preferences.liveTranslate, false)
            settings.set(preferences.translationEnabled, false)
            settings.set(preferences.nightReadEnabled, false)
            settings.set(preferences.keepMaterials, false)
            settings.set(preferences.inpaintMethod, METHOD)
            settings.set(preferences.translationMasterEnabled, true)
            settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
            awaitCondition("own effective storage root") { effectiveBase(storage) == Uri.fromFile(root) }
            val models = File(root, "models").apply { check(mkdir()) }
            for (base in listOf("dbnet-cbz.ncnn", "ocr_48px_ctc.ncnn", "aot-cbz.ncnn")) {
                File(models, "$base.param").writeText("presence-only scripted-engine fixture")
                File(models, "$base.bin").writeBytes(byteArrayOf(0))
            }
            assertTrue(TranslationEngineConfig.modelsResolvable(context))
            withTimeout(10_000) { Injekt.get<SourceManager>().isInitialized.first { it } }
            val local = Injekt.get<SourceManager>().get(LocalSource.ID) as? LocalSource
                ?: error("Actual registered LocalSource required")
            val directory = File(root, "local/$title").apply { check(mkdirs()) }
            val archive = File(directory, "Chapter.cbz")
            val originalPages = linkedMapOf("001.png" to encoded(Color.WHITE), "002.png" to encoded(Color.BLUE))
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((name, bytes) in originalPages + ("note.txt" to NOTE)) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val originalArchive = archive.readBytes()
            val manga = withContext(NonCancellable) {
                mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(source = LocalSource.ID, url = title, title = title, initialized = true),
                    ),
                ).single().also { mangaId = it.id }
            }
            val chapter = withContext(NonCancellable) {
                chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "$title/Chapter.cbz", name = "Chapter.cbz")),
                ).single().also { chapterId = it.id }
            }
            // Actual source resolution; this is not a guessed download directory or private archive callback.
            val format = local.getFormat(chapter.toSChapter())
            assertTrue(format is Format.Archive)
            assertEquals(Uri.fromFile(archive), (format as Format.Archive).file.uri)
            statsField.set(queueTranslator, TranslationStatsStore(privateStatsContext))
            val existingStaging = context.cacheDir.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("yakutr_") }.map { it.canonicalFile }.toSet()
            withInjectedEngine(manager, service, preferences, firstEngine) {
                manager.translate(manga, listOf(chapter), method = METHOD)
            }
            withTimeout(10_000) { firstEngine.secondEntered.await() }
            awaitCondition("first page durably processed before pause") {
                manager.queueState.value.singleOrNull()?.let {
                    it.chapter.id == chapter.id && it.status == TranslationItem.Status.TRANSLATING &&
                        it.done == 1 && it.total == 2
                } == true
            }
            // Queue done is a processed-page count, which also counts failures. Confirm actual
            // completed storage before the pause, without invoking any private archive operation.
            val ownStaging = context.cacheDir.listFiles().orEmpty().single {
                it.isDirectory && it.name.startsWith("yakutr_") && it.canonicalFile !in existingStaging
            }
            check(ownStaging.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            val stagedPages = File(ownStaging, "pages")
            assertTrue(
                PageTranslator(context).isPageTranslated(requireNotNull(UniFile.fromFile(stagedPages)), "001.png"),
            )
            assertPixels(Color.GREEN, File(stagedPages, "001.png").readBytes())
            assertArrayEquals(
                originalPages.getValue("001.png"),
                File(stagedPages, ".yakuyomi/001.orig.png").readBytes(),
            )
            assertEquals(1, ledger.firstFresh.get())
            assertEquals(1, ledger.secondFresh.get())
            assertFalse(manager.isPendingTranslate(chapter.id))
            assertTrue(Injekt.get<DownloadManager>().queueState.value.isEmpty())
            val poolBeforePause = manager.isTranslatePaused.value
            manager.pauseChapter(chapter.id, TranslationItem.Kind.TRANSLATE)
            val requestedPause = manager.queueState.value.single()
            assertTrue(requestedPause.paused)
            assertEquals(poolBeforePause, manager.isTranslatePaused.value)
            firstEngine.releaseSecond.complete(Unit)
            awaitCondition("cooperative pause returns QUEUE with own paused flag") {
                manager.queueState.value.singleOrNull()?.let {
                    it.chapter.id == chapter.id && it.paused && it.status == TranslationItem.Status.QUEUE
                } == true && TranslationBusy.active.value == 0 && ledger.active.get() == 0
            }
            joinDrain(manager)
            assertFalse(service.warm.value)
            assertEquals(null, serviceField("engine").get(service))
            assertTrue(firstEngine.closed)
            assertFalse(manager.isPendingTranslate(chapter.id))
            assertFalse(manager.isTranslated(manga, chapter))
            assertValidZip(archive)
            // Both original-archive retention and partial-archive commit designs are allowed here.
            assertArrayEquals(originalPages.getValue("002.png"), zipBytes(archive, "002.png"))
            assertArrayEquals(NOTE, zipBytes(archive, "note.txt"))
            assertOriginalBackupIfPresent(directory, originalArchive)

            withInjectedEngine(manager, service, preferences, resumeEngine) {
                manager.resumeChapter(chapter.id, TranslationItem.Kind.TRANSLATE)
            }
            awaitCondition("resume finishes full public queue and verified CBZ") {
                manager.queueState.value.none { it.chapter.id == chapter.id } &&
                    TranslationBusy.active.value == 0 && manager.isTranslated(manga, chapter)
            }
            joinDrain(manager)
            assertValidZip(archive)
            assertTrue(PageTranslator(context).isArchiveTranslated(requireNotNull(UniFile.fromFile(archive))))
            assertTrue(chapter.id in manager.translatedIds.value)
            assertFalse(manager.isPendingTranslate(chapter.id))
            assertTrue(Injekt.get<DownloadManager>().queueState.value.isEmpty())
            assertArrayEquals(NOTE, zipBytes(archive, "note.txt"))
            for ((name, bytes) in originalPages) {
                assertArrayEquals(bytes, zipBytes(archive, ".yakuyomi/${name.substringBeforeLast('.')}.orig.png"))
            }
            assertPixels(Color.GREEN, zipBytes(archive, "001.png"))
            assertPixels(Color.YELLOW, zipBytes(archive, "002.png"))
            val backups = backups(directory)
            assertTrue("At least one exact original archive backup must survive", backups.isNotEmpty())
            backups.forEach(::assertValidZip)
            assertTrue(backups.any { sha(it.readBytes()) == sha(originalArchive) })
            assertTrue(firstEngine.outputs.all { it.isRecycled })
            assertTrue(resumeEngine.outputs.all { it.isRecycled })
            assertEquals("Paused second-page work must run once more on resume", 2, ledger.secondFresh.get())
            // Keep the primary regression assertion after integrity checks so a failing baseline
            // still reaches real public resume and verifies the original/ZIP preservation contract.
            assertEquals(
                "Completed CBZ page 001 must not enter fresh inference after public pause/resume",
                1,
                ledger.firstFresh.get(),
            )
        } catch (failure: Throwable) {
            caught = failure
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(label: String, operation: suspend () -> Unit) {
                    try {
                        operation()
                    } catch (failure: Throwable) {
                        cleanupFailures += AssertionError(label, failure)
                    }
                }
                cleanup("Close gate/remove only own queue before releasing scripted work") {
                    settings.set(preferences.translationMasterEnabled, false)
                    chapterId?.let(manager::cancelPendingTranslation)
                }
                firstEngine.releaseSecond.complete(Unit)
                resumeEngine.releaseSecond.complete(Unit)
                cleanup("Join own inference/drain") {
                    awaitCondition("owned operations settled", 20_000) {
                        ledger.active.get() == 0 && TranslationBusy.active.value == 0 &&
                            manager.queueState.value.none { it.chapter.id == chapterId }
                    }
                    joinDrain(manager)
                    service.shutdown()
                    assertFalse(service.warm.value)
                    drainSettled = true
                }
                cleanup("Cancel only newly created worker IDs, never preexisting work") {
                    for (tag in TAGS) {
                        work(workManager, tag).filter { it.id !in beforeWork.getValue(tag) && !it.state.isFinished }
                            .forEach { workManager.cancelWorkById(it.id).result.get(5, TimeUnit.SECONDS) }
                    }
                    awaitCondition("own workers and progress notification terminal") {
                        TAGS.all { tag ->
                            work(workManager, tag).filter { it.id !in beforeWork.getValue(tag) }
                                .all { it.state.isFinished }
                        } && !hasProgressNotification(notifications)
                    }
                }
                if (drainSettled) {
                    cleanup("Restore isolated statistics injection") {
                        statsField.set(queueTranslator, previousStats)
                        assertSame(previousStats, statsField.get(queueTranslator))
                        check(context.getSharedPreferences(statsName, Context.MODE_PRIVATE).edit().commit())
                        check(context.deleteSharedPreferences(statsName))
                    }
                    cleanup("Release remaining owned output bitmaps") {
                        (firstEngine.outputs + resumeEngine.outputs).filterNot {
                            it.isRecycled
                        }.forEach(Bitmap::recycle)
                    }
                    cleanup("Remove only own chapter DB row") {
                        chapterId?.let { chapters.removeChaptersWithIds(listOf(it)) }
                    }
                    cleanup("Remove only own UUID manga DB row") {
                        mangaId?.let { id ->
                            val own = mangas.getMangaById(id)
                            assertEquals(LocalSource.ID, own.source)
                            assertEquals(title, own.url)
                            val driver = Injekt.get<SqlDriver>()
                            assertEquals(
                                1L,
                                driver.execute(null, "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?", 3) {
                                    bindLong(0, id)
                                    bindLong(1, LocalSource.ID)
                                    bindString(2, title)
                                }.await(),
                            )
                            driver.notifyListeners("mangas", "chapters", "history")
                            Injekt.get<TranslationCache>().invalidate(id)
                        }
                        chapterId?.let { id ->
                            manager.forgetChapterOutputs(listOf(id))
                        }
                    }
                    cleanup("Restore existing pool flags") {
                        if (oldTranslatePaused) manager.pause(false) else manager.resume(false)
                        if (oldNightPaused) manager.pause(true) else manager.resume(true)
                        assertEquals(oldTranslatePaused, manager.isTranslatePaused.value)
                        assertEquals(oldNightPaused, manager.isNightPaused.value)
                    }
                    cleanup("Restore touched preferences including presence and selected persisted keys") {
                        settings.close()
                    }
                    cleanup("Restore initial empty-queue structural preference presence") { savedQueue.restore() }
                    cleanup("Wait for original effective storage and remove UUID files only") {
                        awaitCondition("restored effective storage") { effectiveBase(storage) == oldBase }
                        check(root.canonicalFile.parentFile == context.filesDir.canonicalFile)
                        check(root.name == "cbz-pause-$uuid")
                        if (rootCreated) check(!root.exists() || root.deleteRecursively())
                    }
                } else {
                    cleanupFailures += AssertionError(
                        "Owned drain did not settle; retain UUID stats/storage/files/DB isolation instead of restoring live dependencies",
                    )
                }
            }
        }
        caught?.let { failure ->
            cleanupFailures.forEach(failure::addSuppressed)
            throw failure
        }
        if (cleanupFailures.isNotEmpty()) {
            throw AssertionError("CBZ pause fixture cleanup failed").apply {
                cleanupFailures.forEach(::addSuppressed)
            }
        }
    }

    private class Ledger {
        val firstFresh = AtomicInteger()
        val secondFresh = AtomicInteger()
        val active = AtomicInteger()
    }

    private class ScriptedEngine(private val ledger: Ledger, private val holdSecond: Boolean) : TranslationEngine {
        override val translationContext = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val outputs = CopyOnWriteArrayList<Bitmap>()
        var closed = false

        override suspend fun translatePage(page: Bitmap): PageResult {
            check(!closed)
            ledger.active.incrementAndGet()
            return try {
                val source = page.getPixel(0, 0)
                val color = when (source) {
                    Color.WHITE -> {
                        ledger.firstFresh.incrementAndGet()
                        Color.GREEN
                    }
                    Color.BLUE -> {
                        ledger.secondFresh.incrementAndGet()
                        secondEntered.complete(Unit)
                        if (holdSecond) withContext(NonCancellable) { withTimeout(30_000) { releaseSecond.await() } }
                        Color.YELLOW
                    }
                    else -> error("Fresh inference received output or an unknown source")
                }
                val output = image(color).also(outputs::add)
                PageResult.Translated(output, PageStats(0, 0, 0, 0, 0, 0, 0, 0))
            } finally {
                ledger.active.decrementAndGet()
            }
        }
        override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
            error("Fixture has no partial OCR regions to resume")
        override fun warmUp() = error("Presence-only fixtures must not load native models")
        override fun close() {
            closed = true
        }
    }

    /** Empty-queue precondition permits saving only these documented structural values/presence. */
    private class SavedEmptyQueue(context: Context) {
        private val prefs = context.getSharedPreferences("active_translations", Context.MODE_PRIVATE)
        private val booleans = listOf("paused", "paused_night")
        private val sets = listOf("pending_translate", "pending_night")
        private val savedBooleans = booleans.associateWith {
            if (prefs.contains(it)) prefs.getBoolean(it, false) else null
        }
        private val savedSets = sets.associateWith {
            if (prefs.contains(it)) prefs.getStringSet(it, emptySet())!!.toSet() else null
        }
        init {
            check(prefs.all.keys.all { it in booleans || it in sets })
        }
        fun restore() {
            check(prefs.all.keys.all { it in booleans || it in sets })
            val editor = prefs.edit()
            savedBooleans.forEach { (key, value) ->
                if (value ==
                    null
                ) {
                    editor.remove(key)
                } else {
                    editor.putBoolean(key, value)
                }
            }
            savedSets.forEach { (key, value) ->
                if (value ==
                    null
                ) {
                    editor.remove(key)
                } else {
                    editor.putStringSet(key, value)
                }
            }
            check(editor.commit())
            savedBooleans.forEach { (key, value) ->
                check(prefs.contains(key) == (value != null))
                if (value != null) check(prefs.getBoolean(key, !value) == value)
            }
            savedSets.forEach { (key, value) ->
                check(prefs.contains(key) == (value != null))
                if (value != null) check(prefs.getStringSet(key, null) == value)
            }
        }
    }

    companion object {
        private const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        private const val METHOD = "boxfill"
        private val TAGS = listOf("Translator", "TranslatorHeartbeat")
        private val NOTE = "Neutral CBZ pause/resume fixture\n".toByteArray()
        private fun image(color: Int) = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        private fun encoded(color: Int): ByteArray = image(color).let { bitmap ->
            try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        private fun sha(
            bytes: ByteArray,
        ) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun serviceField(
            name: String,
        ) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible =
                true
        }
        private fun managerField(
            name: String,
        ) = TranslationManager::class.java.getDeclaredField(name).apply {
            isAccessible =
                true
        }
        private fun effectiveBase(storage: StorageManager): Uri? =
            (
                StorageManager::class.java.getDeclaredField("baseDir").apply {
                    isAccessible = true
                }.get(storage) as UniFile?
                )?.uri
        private fun installFake(
            service: TranslationEngineService,
            preferences: TranslationPreferences,
            engine: TranslationEngine,
        ) {
            assertEquals(null, serviceField("engine").get(service))
            val signature = TranslationEngineService::class.java.getDeclaredMethod(
                "configSignature",
                String::class.java,
                TranslationPreferences::class.java,
            )
                .apply { isAccessible = true }.invoke(service, METHOD, preferences.snapshot())
            serviceField("engine").set(service, engine)
            serviceField("builtSignature").set(service, signature)
            serviceField("warmedUp").setBoolean(service, true)
            @Suppress("UNCHECKED_CAST")
            (serviceField("_warm").get(service) as MutableStateFlow<Boolean>).value = true
        }
        private suspend fun joinDrain(manager: TranslationManager) {
            val mutex = managerField("drainMutex").get(manager) as Mutex
            withTimeout(20_000) { mutex.withLock { Unit } }
        }
        private suspend fun withInjectedEngine(
            manager: TranslationManager,
            service: TranslationEngineService,
            preferences: TranslationPreferences,
            engine: TranslationEngine,
            enqueue: () -> Unit,
        ) {
            // A previously queued empty drain must not close a newly injected fake between
            // installation and the real public enqueue/resume. The actual worker runs after unlock.
            val mutex = managerField("drainMutex").get(manager) as Mutex
            withTimeout(20_000) {
                mutex.withLock {
                    installFake(service, preferences, engine)
                    enqueue()
                }
            }
        }
        private suspend fun awaitCondition(label: String, timeout: Long = 15_000, condition: () -> Boolean) {
            withTimeout(timeout) {
                while (!condition()) {
                    currentCoroutineContext().ensureActive()
                    delay(25)
                }
            }
        }
        private fun work(
            manager: WorkManager,
            tag: String,
        ): List<WorkInfo> = manager.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS)
        private fun hasProgressNotification(manager: NotificationManager) = manager.activeNotifications.any {
            it.packageName == AUDIT_PACKAGE && it.id == Notifications.ID_TRANSLATION_PROGRESS
        }
        private fun zipBytes(file: File, name: String): ByteArray = ZipFile(file).use { zip ->
            val entry = requireNotNull(zip.getEntry(name)) { "Missing fixture archive entry: $name" }
            check(entry.size in 0L..(256L * 1024))
            zip.getInputStream(entry).use(::readBoundedEntry)
        }
        private fun assertValidZip(file: File) = ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            check(entries.size in 3..64)
            check(entries.map { it.name }.distinct().size == entries.size)
            check(entries.all { !it.name.startsWith('/') && it.name.split('/').none { part -> part == ".." } })
            for (entry in entries.filterNot { it.isDirectory }) {
                check(entry.size in 0L..(256L * 1024))
                val bytes = zip.getInputStream(entry).use(::readBoundedEntry)
                check(bytes.size.toLong() == entry.size)
                val crc = java.util.zip.CRC32().apply { update(bytes) }.value
                check(crc == entry.crc)
            }
        }
        private fun readBoundedEntry(input: java.io.InputStream): ByteArray = ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                check(output.size() + size <= 256 * 1024) { "Fixture archive entry exceeds its bound" }
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        private fun backups(directory: File) = directory.listFiles().orEmpty().filter {
            it.isFile && it.name.startsWith("Chapter.cbz.yomitori-original-") && it.name.endsWith(".bak")
        }
        private fun assertOriginalBackupIfPresent(directory: File, original: ByteArray) {
            val files = backups(directory)
            files.forEach(::assertValidZip)
            if (files.isNotEmpty()) assertTrue(files.any { sha(it.readBytes()) == sha(original) })
        }
        private fun assertPixels(expected: Int, bytes: ByteArray) {
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals(32, bitmap.width)
                assertEquals(32, bitmap.height)
                val pixels = IntArray(32 * 32)
                bitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                assertTrue(pixels.all { it == expected })
            } finally {
                bitmap.recycle()
            }
        }
    }
}
