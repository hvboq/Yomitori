package mihon.translation

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStore
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
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
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real queue and WorkManager recovery; scripted inference, no Activity, native model or HTTP. */
@RunWith(AndroidJUnit4::class)
class MasterToggleWorkerRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun masterOffOnDuringNativeCancellationRestartsWorkerAndHeartbeat() = runBlocking<Unit> {
        assumeTrue(
            "Requires explicit -e masterToggleWorkerAudit true",
            InstrumentationRegistry.getArguments().getString("masterToggleWorkerAudit") == "true",
        )
        // Before Injekt, preferences, DB, notifications, work inspection, or fixture files.
        assertEquals("Worker audit must never target a user app", AUDIT_PACKAGE, context.packageName)
        val manager = Injekt.get<TranslationManager>()
        val service = Injekt.get<TranslationEngineService>()
        val workManager = WorkManager.getInstance(context)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Harness requires an idle audit translation queue", manager.queueState.value.isEmpty())
        assertTrue("Harness requires idle audit downloads", Injekt.get<DownloadManager>().queueState.value.isEmpty())
        assertEquals("Harness requires no current page operation", 0, TranslationBusy.active.value)
        assertTrue(
            "Harness requires no active translator work",
            works(workManager, TRANSLATOR).all { it.state.isFinished },
        )
        assertTrue("Harness requires no active heartbeat", works(workManager, HEARTBEAT).all { it.state.isFinished })
        assertFalse("Harness requires a cold audit service before fake installation", service.warm.value)
        assertFalse("Harness requires no stale translator notification", hasProgressNotification(notificationManager))
        val store = TranslationStore(context)
        assertTrue("Harness refuses persisted queued audit work", store.restore().isEmpty())
        val marks = store.restorePendingMarks(Long.MIN_VALUE)
        assertTrue("Harness refuses pending audit downloads", marks.translate.isEmpty() && marks.night.isEmpty())
        val savedQueuePreferences = SavedQueuePreferences(context)
        manager.ensureRestored()
        assertTrue(manager.queueState.value.isEmpty())

        val uuid = UUID.randomUUID().toString()
        val root = File(context.filesDir, "master-toggle-$uuid")
        val title = "Master toggle fixture $uuid"
        val reportFile = File(context.getExternalFilesDir(null), "master-toggle-worker/$uuid.json")
        check(reportFile.parentFile!!.isDirectory || reportFile.parentFile!!.mkdirs())
        val report = JSONObject().put("passed", false).put("package", context.packageName)
            .put("nativeInference", false).put("http", false).put("activity", false)
            .put("publicManagerMethods", JSONArray(listOf("translate", "onMasterEnabledChanged")))
            .put("fullOnMasterEnabledChangedWrapper", true)
            .put("wrapperShutdownLifecycleCovered", true)
            .put("notificationIsObservationalOnly", true)
            .put("workManager", "Actual package WorkManager; no scheduler or worker substitution")
            .put("initialActiveWorkerObserved", false).put("failureClass", "harness_or_unclassified")
        val preferences = Injekt.get<TranslationPreferences>()
        val settings = LimitedSettings()
        val storage = Injekt.get<StorageManager>()
        val oldEffectiveBase = effectiveBaseUri(storage)
        val oldTranslatePaused = manager.isTranslatePaused.value
        val oldNightPaused = manager.isNightPaused.value
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val beforeTranslatorIds = works(workManager, TRANSLATOR).mapTo(hashSetOf()) { it.id }
        val beforeHeartbeatIds = works(workManager, HEARTBEAT).mapTo(hashSetOf()) { it.id }
        val fake = GatedEngine()
        var installedFake = false
        var mangaId: Long? = null
        var chapterId: Long? = null
        var fixturePage: File? = null
        var originalBytes: ByteArray? = null
        var failure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        fun recordWork(key: String, work: List<WorkInfo>) {
            report.put(
                key,
                JSONArray(
                    work.map {
                        JSONObject().put("id", it.id).put("state", it.state.name).put("attempts", it.runAttemptCount)
                    },
                ),
            )
        }
        try {
            check(root.mkdir())
            settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
            settings.set(preferences.translationMasterEnabled, true)
            settings.set(preferences.translationEnabled, false)
            settings.set(preferences.liveTranslate, false)
            settings.set(preferences.nightReadEnabled, false)
            settings.set(preferences.keepMaterials, false)
            settings.set(preferences.inpaintMethod, "boxfill")
            awaitCondition("private storage base") { effectiveBaseUri(storage) == Uri.fromFile(root) }
            // These files satisfy presence checks only. The injected engine must never load them.
            val modelDirectory = File(root, "models").apply { check(mkdir()) }
            listOf("dbnet-toggle.ncnn", "ocr_48px_ctc.ncnn", "aot-toggle.ncnn").forEach { name ->
                File(modelDirectory, "$name.param").writeText("presence-only audit fixture")
                File(modelDirectory, "$name.bin").writeBytes(byteArrayOf(0))
            }
            assertTrue(TranslationEngineConfig.modelsResolvable(context))
            awaitCondition("registered local source") { Injekt.get<SourceManager>().isInitialized.value }
            assertTrue(Injekt.get<SourceManager>().get(LocalSource.ID) is LocalSource)
            val directory = File(root, "local/$title/Chapter").apply { check(mkdirs()) }
            val page = File(directory, "001.png")
            fixturePage = page
            bitmap(Color.WHITE).let { image ->
                try {
                    page.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    image.recycle()
                }
            }
            val original = page.readBytes()
            originalBytes = original
            val manga = withContext(NonCancellable) {
                mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(source = LocalSource.ID, url = title, title = title, initialized = true),
                    ),
                )
                    .single().also { mangaId = it.id }
            }
            val chapter = withContext(NonCancellable) {
                chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "$title/Chapter", name = "Chapter")),
                )
                    .single().also { chapterId = it.id }
            }
            installFake(service, preferences, fake)
            installedFake = true
            manager.translate(manga, listOf(chapter), method = "boxfill")
            awaitCondition("first barrier-controlled inference") { fake.entered[0].count == 0L }
            assertRunningEntry(manager, chapter.id)
            awaitCondition("harness initial active worker and heartbeat", 15_000) {
                works(workManager, TRANSLATOR).any { it.id !in beforeTranslatorIds && it.state in ACTIVE_STATES } &&
                    works(workManager, HEARTBEAT).any { it.id !in beforeHeartbeatIds && it.state in ACTIVE_STATES }
            }
            val oldTranslator = works(workManager, TRANSLATOR).filter { it.id !in beforeTranslatorIds }
            val oldHeartbeat = works(workManager, HEARTBEAT).filter { it.id !in beforeHeartbeatIds }
            recordWork("firstTranslator", oldTranslator)
            recordWork("firstHeartbeat", oldHeartbeat)
            report.put("initialActiveWorkerObserved", true)
                .put("initialProgressNotificationObserved", hasProgressNotification(notificationManager))

            // Match MoreTab: write the preference, then invoke the full public toggle callback.
            // liveTranslate=false prevents ON from warming a second engine. The explicit OFF
            // release cannot finish while the first native-like inference remains registered.
            preferences.translationMasterEnabled.set(false)
            manager.onMasterEnabledChanged(false)
            awaitCondition("old worker and heartbeat terminal after actual public halt", 15_000) {
                oldTranslator.all { workManager.getWorkInfoById(it.id).get(5, TimeUnit.SECONDS)!!.state.isFinished } &&
                    oldHeartbeat.all { workManager.getWorkInfoById(it.id).get(5, TimeUnit.SECONDS)!!.state.isFinished }
            }
            assertRunningEntry(manager, chapter.id)
            assertEquals(1L, fake.release[0].count)
            recordWork(
                "stoppedTranslator",
                works(workManager, TRANSLATOR).filter { it.id in oldTranslator.map { old -> old.id } },
            )
            report.put("oldWorkTerminalWhileInferenceBlocked", true)

            preferences.translationMasterEnabled.set(true)
            manager.onMasterEnabledChanged(true)
            assertRunningEntry(manager, chapter.id)
            assertEquals("ON must be delivered before old inference can return", 1L, fake.release[0].count)
            assertEquals(1, fake.calls.get())
            report.put("onDeliveredWhileOldInferenceBlocked", true)
                .put("failureClass", "product_worker_recovery_assertion")
            val oldIds = oldTranslator.mapTo(hashSetOf()) { it.id }
            val oldHeartbeatIds = oldHeartbeat.mapTo(hashSetOf()) { it.id }
            try {
                awaitCondition("replacement active worker and heartbeat while old cancellation waits", 8_000) {
                    works(workManager, TRANSLATOR).any {
                        it.id !in beforeTranslatorIds && it.id !in oldIds && it.state in ACTIVE_STATES
                    } && works(workManager, HEARTBEAT).any {
                        it.id !in beforeHeartbeatIds && it.id !in oldHeartbeatIds && it.state in ACTIVE_STATES
                    }
                }
            } finally {
                val observedTranslator = works(workManager, TRANSLATOR).filter { it.id !in beforeTranslatorIds }
                val observedHeartbeat = works(workManager, HEARTBEAT).filter { it.id !in beforeHeartbeatIds }
                recordWork("replacementTranslator", observedTranslator)
                recordWork("replacementHeartbeat", observedHeartbeat)
                report.put("replacementProgressNotification", hasProgressNotification(notificationManager))
                if (observedTranslator.any { it.id !in oldIds && it.state == WorkInfo.State.FAILED } ||
                    observedHeartbeat.any { it.id !in oldHeartbeatIds && it.state == WorkInfo.State.FAILED }
                ) {
                    report.put("failureClass", "harness_replacement_worker_failed_after_dispatch")
                }
            }
            report.put("replacementWorkerAndHeartbeatObserved", true).put("failureClass", JSONObject.NULL)
        } catch (caught: Throwable) {
            failure = caught
            report.put("failureType", caught.javaClass.simpleName).put("failureMessage", caught.message)
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(label: String, action: suspend () -> Unit) {
                    try {
                        action()
                        report.put(label, true)
                    } catch (caught: Throwable) {
                        cleanupFailures += caught
                        report.put(label, false)
                    }
                }
                // Close the actual master gate and remove only our entry before releasing native
                // work. This prevents a second inference after explicit shutdown detaches fake.
                cleanup("masterGateClosedBeforeNativeRelease") {
                    settings.set(preferences.translationMasterEnabled, false)
                    manager.onMasterEnabledChanged(false)
                }
                cleanup("ownQueueRemoved") { chapterId?.let { manager.cancelTranslation(listOf(it)) } }
                fake.release.forEach { it.countDown() }
                cleanup("drainJoined") {
                    awaitCondition("owned inference and drain finish", 20_000) {
                        fake.active.get() == 0 && TranslationBusy.active.value == 0 &&
                            manager.queueState.value.none { it.chapter.id == chapterId }
                    }
                    val drain = managerField("drainMutex").get(manager) as Mutex
                    withTimeout(20_000) { drain.withLock { Unit } }
                }
                cleanup("ownedWorkCancelled") {
                    listOf(TRANSLATOR to beforeTranslatorIds, HEARTBEAT to beforeHeartbeatIds)
                        .forEach { (tag, before) ->
                            works(workManager, tag).filter { it.id !in before && !it.state.isFinished }.forEach {
                                workManager.cancelWorkById(it.id).result.get(5, TimeUnit.SECONDS)
                            }
                        }
                    awaitCondition("owned work and notification finish", 10_000) {
                        works(workManager, TRANSLATOR).filter { it.id !in beforeTranslatorIds }
                            .all { it.state.isFinished } &&
                            works(workManager, HEARTBEAT).filter { it.id !in beforeHeartbeatIds }
                                .all { it.state.isFinished } &&
                            !hasProgressNotification(notificationManager)
                    }
                }
                cleanup("fakeServiceReleased") {
                    if (installedFake) {
                        withTimeout(20_000) { service.shutdown() }
                        assertFalse(service.warm.value)
                        assertEquals(null, serviceField("engine").get(service))
                    }
                }
                cleanup("cancelledResultDiscardedAndOriginalPreserved") {
                    if (fake.entered[0].count == 0L) {
                        assertEquals("No replacement inference may start during cleanup", 1, fake.calls.get())
                        assertEquals(1, fake.outputs.size)
                        assertTrue("Cancelled native-like output must be recycled", fake.outputs.single().isRecycled)
                        val page = requireNotNull(fixturePage)
                        assertArrayEquals(requireNotNull(originalBytes), page.readBytes())
                        assertFalse(
                            PageTranslator(context).isPageTranslated(
                                requireNotNull(UniFile.fromFile(page.parentFile!!)),
                                "001.png",
                            ),
                        )
                    }
                }
                cleanup("remainingFixtureBitmapsReleased") { fake.outputs.forEach { if (!it.isRecycled) it.recycle() } }
                cleanup("ownChapterRemoved") { chapterId?.let { chapters.removeChaptersWithIds(listOf(it)) } }
                cleanup("ownMangaRemoved") {
                    mangaId?.let { ownId ->
                        val created = mangas.getMangaById(ownId)
                        assertEquals(LocalSource.ID, created.source)
                        assertEquals(title, created.url)
                        val driver = Injekt.get<SqlDriver>()
                        assertEquals(
                            1L,
                            driver.execute(null, "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?", 3) {
                                bindLong(0, ownId)
                                bindLong(1, LocalSource.ID)
                                bindString(2, title)
                            }.await(),
                        )
                        driver.notifyListeners("mangas", "chapters", "history")
                    }
                }
                cleanup("pauseStatesRestored") {
                    if (oldTranslatePaused) manager.pause(false) else manager.resume(false)
                    if (oldNightPaused) manager.pause(true) else manager.resume(true)
                    assertEquals(oldTranslatePaused, manager.isTranslatePaused.value)
                    assertEquals(oldNightPaused, manager.isNightPaused.value)
                }
                cleanup("preferencesRestoredIncludingPresence") { settings.restoreAndVerify(context) }
                cleanup("queuePreferencePresenceRestored") { savedQueuePreferences.restoreAndVerify() }
                cleanup("effectiveStorageRestored") {
                    awaitCondition("original effective storage base") {
                        effectiveBaseUri(storage) == oldEffectiveBase
                    }
                }
                cleanup("ownFilesRemoved") {
                    assertEquals(context.filesDir.canonicalFile, root.canonicalFile.parentFile)
                    assertTrue(!root.exists() || root.deleteRecursively())
                }
                report.put("cleanupFailureCount", cleanupFailures.size)
                    .put("passed", failure == null && cleanupFailures.isEmpty())
                reportFile.writeText(report.toString(2))
                println("MASTER_TOGGLE_WORKER_REPORT=${reportFile.absolutePath}")
                println(report.toString(2))
            }
        }
        failure?.let { caught ->
            cleanupFailures.forEach(caught::addSuppressed)
            throw caught
        }
        if (cleanupFailures.isNotEmpty()) {
            throw AssertionError("Master toggle worker fixture cleanup failed").apply {
                cleanupFailures.forEach(::addSuppressed)
            }
        }
    }

    private fun installFake(
        service: TranslationEngineService,
        preferences: TranslationPreferences,
        fake: TranslationEngine,
    ) {
        assertEquals(null, serviceField("engine").get(service))
        val signature = TranslationEngineService::class.java.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        )
            .apply { isAccessible = true }.invoke(service, "boxfill", preferences.snapshot())
        serviceField("engine").set(service, fake)
        serviceField("builtSignature").set(service, signature)
        serviceField("warmedUp").setBoolean(service, true)
        @Suppress("UNCHECKED_CAST")
        (serviceField("_warm").get(service) as MutableStateFlow<Boolean>).value = true
    }

    private fun assertRunningEntry(manager: TranslationManager, chapterId: Long) {
        val entry = manager.queueState.value.single()
        assertEquals(chapterId, entry.chapter.id)
        assertEquals(TranslationItem.Status.TRANSLATING, entry.status)
        assertEquals(TranslationItem.Kind.TRANSLATE, entry.kind)
    }

    private fun hasProgressNotification(manager: NotificationManager) = manager.activeNotifications.any {
        it.packageName == AUDIT_PACKAGE && it.id == Notifications.ID_TRANSLATION_PROGRESS
    }

    private fun works(manager: WorkManager, tag: String): List<WorkInfo> =
        manager.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS)

    private fun effectiveBaseUri(storage: StorageManager): Uri? =
        (
            StorageManager::class.java.getDeclaredField("baseDir")
                .apply { isAccessible = true }.get(storage) as UniFile?
            )?.uri

    private suspend fun awaitCondition(label: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "Master toggle audit timed out: $label" }
            delay(25)
        }
    }

    private class GatedEngine : TranslationEngine {
        override val translationContext = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
        val entered = List(1) { CountDownLatch(1) }
        val release = List(1) { CountDownLatch(1) }
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val outputs = CopyOnWriteArrayList<Bitmap>()
        override suspend fun translatePage(page: Bitmap): PageResult {
            val index = calls.getAndIncrement()
            check(index == 0) { "Unexpected replacement inference before fixture cleanup" }
            active.incrementAndGet()
            try {
                entered[index].countDown()
                check(release[index].await(90, TimeUnit.SECONDS)) { "Fixture native-like inference gate timed out" }
                val output = bitmap(Color.MAGENTA)
                outputs.add(output)
                return PageResult.Translated(output, PageStats(0, 0, 0, 0, 0, 0, 0, 0))
            } finally {
                active.decrementAndGet()
            }
        }
        override fun warmUp() = error("Scripted warm engine must not initialize native models")
        override fun close() = Unit
    }

    private class LimitedSettings {
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
        fun restoreAndVerify(context: Context) {
            val failures = mutableListOf<Throwable>()
            restores.values.toList().asReversed().forEach { action ->
                try {
                    action()
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
            check(PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
            verifies.forEach { action ->
                try {
                    action()
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
            if (failures.isNotEmpty()) {
                throw AssertionError("Touched settings differ after restore").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        }
    }

    /** The queue was proven empty above; preserve only its four documented structural keys. */
    private class SavedQueuePreferences(context: Context) {
        private val preferences = context.getSharedPreferences("active_translations", Context.MODE_PRIVATE)
        private val booleans = listOf("paused", "paused_night")
        private val sets = listOf("pending_translate", "pending_night")
        private val savedBooleans = booleans.associateWith { key ->
            if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        }
        private val savedSets = sets.associateWith { key ->
            if (preferences.contains(key)) preferences.getStringSet(key, emptySet())!!.toSet() else null
        }
        init {
            check(preferences.all.keys.all { it in booleans || it in sets }) {
                "Harness refuses unexpected persisted queue data"
            }
        }
        fun restoreAndVerify() {
            check(preferences.all.keys.all { it in booleans || it in sets }) {
                "Unexpected queue data remains; refusing structural preference restoration"
            }
            val editor = preferences.edit()
            savedBooleans.forEach { (key, value) ->
                if (value == null) editor.remove(key) else editor.putBoolean(key, value)
            }
            savedSets.forEach { (key, value) ->
                if (value == null) editor.remove(key) else editor.putStringSet(key, value)
            }
            check(editor.commit())
            savedBooleans.forEach { (key, value) ->
                check(preferences.contains(key) == (value != null))
                if (value != null) check(preferences.getBoolean(key, !value) == value)
            }
            savedSets.forEach { (key, value) ->
                check(preferences.contains(key) == (value != null))
                if (value != null) check(preferences.getStringSet(key, null) == value)
            }
        }
    }

    private companion object {
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val TRANSLATOR = "Translator"
        const val HEARTBEAT = "TranslatorHeartbeat"
        val ACTIVE_STATES = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)
        fun bitmap(color: Int) = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        fun serviceField(name: String) = TranslationEngineService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
        fun managerField(name: String) = TranslationManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
    }
}
