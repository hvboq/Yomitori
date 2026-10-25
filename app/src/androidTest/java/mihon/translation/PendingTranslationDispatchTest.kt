package mihon.translation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.crash.TraceLog
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.StartupEnginePrewarm
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStatsStore
import eu.kanade.tachiyomi.data.translation.TranslationStore
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.EngineTrace
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.io.File
import java.lang.reflect.Type
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Public completion/master/Stop boundaries; private stores and a held drain forbid inference. */
@RunWith(AndroidJUnit4::class)
class PendingTranslationDispatchTest {
    @Test(timeout = 60_000)
    fun downloadCompletedWhileMasterOffRetainsIntentAndQueuesExactlyOnceWhenEnabled(): Unit = runBlocking {
        withPendingMasterFixture { f ->
            f.withDrainHeld {
                f.preferences.translationMasterEnabled.set(true)
                f.manager.markForTranslate(f.chapter.id)
                assertTrue(f.manager.isPendingTranslate(f.chapter.id))

                f.preferences.translationMasterEnabled.set(false)
                f.manager.onMasterEnabledChanged(false)
                assertTrue(f.manager.onDownloadedForTranslation(f.manga, f.chapter))
                f.assertHardOff()
                assertTrue(
                    "PENDING_MASTER_OFF_EXPLICIT_INTENT_RETAINED",
                    f.hasDurableIntent(),
                )

                f.preferences.translationMasterEnabled.set(true)
                f.manager.onMasterEnabledChanged(true)
                f.awaitExactlyOneQueued()
                assertFalse(
                    "PENDING_MASTER_ON_CONSUMES_MARK_AFTER_ADMISSION",
                    f.manager.isPendingTranslate(f.chapter.id),
                )
                f.manager.onDownloadedForTranslation(f.manga, f.chapter)
                f.manager.onMasterEnabledChanged(true)
                f.assertExactlyOneQueued()
                f.assertColdAndUnchanged()
            }
        }
    }

    @Test(timeout = 60_000)
    fun enablingBeforePreferenceCommitStillWakesACompletionAdmittedBetweenCallbackAndCommit(): Unit = runBlocking {
        withPendingMasterFixture { f ->
            f.withDrainHeld {
                f.preferences.translationMasterEnabled.set(true)
                f.manager.markForTranslate(f.chapter.id)
                f.preferences.translationMasterEnabled.set(false)
                f.manager.onMasterEnabledChanged(false)

                // Match SettingsTranslationScreen: the callback runs before the preference is saved.
                // Download completion then arrives in that gap; no second ON callback follows.
                f.manager.onMasterEnabledChanged(true)
                assertFalse(f.preferences.translationMasterEnabled.get())
                f.assertHardOff()
                assertTrue(f.manager.onDownloadedForTranslation(f.manga, f.chapter))
                f.assertHardOff()
                assertTrue("PENDING_CALLBACK_ORDER_OFF_INTENT_RETAINED", f.hasDurableIntent())

                f.preferences.translationMasterEnabled.set(true)
                f.awaitExactlyOneQueued()
                f.awaitWorkerAndHeartbeatAdmission()
                f.assertExactlyOneQueued()
                assertFalse(f.manager.isPendingTranslate(f.chapter.id))
                f.assertColdAndUnchanged()
            }
        }
    }

    @Test(timeout = 60_000)
    fun explicitStopAfterOffCompletionPreventsResumeUntilTheReaderMarksAgain(): Unit = runBlocking {
        withPendingMasterFixture { f ->
            f.withDrainHeld {
                f.preferences.translationMasterEnabled.set(true)
                f.manager.markForTranslate(f.chapter.id)
                f.preferences.translationMasterEnabled.set(false)
                f.manager.onMasterEnabledChanged(false)
                assertTrue(f.manager.onDownloadedForTranslation(f.manga, f.chapter))
                f.assertHardOff()

                f.manager.cancelPendingTranslation(f.chapter.id)
                assertFalse(f.manager.isPendingTranslate(f.chapter.id))
                assertTrue(f.manager.queueState.value.isEmpty())
                assertFalse(f.hasDurableIntent())
                f.preferences.translationMasterEnabled.set(true)
                f.manager.onMasterEnabledChanged(true)
                assertTrue("PENDING_STOP_MASTER_ON_MUST_NOT_RECREATE_WORK", f.manager.queueState.value.isEmpty())

                f.manager.markForTranslate(f.chapter.id)
                assertTrue(f.manager.onDownloadedForTranslation(f.manga, f.chapter))
                f.awaitExactlyOneQueued()
                f.assertColdAndUnchanged()
            }
        }
    }

    @Test(timeout = 60_000)
    fun disabledAutomaticTranslationAllowsOnlyAnExplicitCompletedDownloadIntent(): Unit = runBlocking {
        withPendingMasterFixture { f ->
            f.withDrainHeld {
                f.preferences.translationMasterEnabled.set(true)
                assertFalse(f.preferences.translationEnabled.get())
                assertFalse(f.manager.onDownloadedForTranslation(f.manga, f.chapter))
                assertTrue(f.manager.queueState.value.isEmpty())
                assertFalse(f.hasDurableIntent())

                f.manager.markForTranslate(f.chapter.id)
                assertTrue(f.manager.onDownloadedForTranslation(f.manga, f.chapter))
                f.awaitExactlyOneQueued()
                assertFalse(f.manager.isPendingTranslate(f.chapter.id))
                f.manager.onDownloadedForTranslation(f.manga, f.chapter)
                f.assertExactlyOneQueued()
                f.assertColdAndUnchanged()
            }
        }
    }
}

private suspend fun withPendingMasterFixture(block: suspend (PendingMasterFixture) -> Unit) {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    check(target.packageName == "app.yomitori.yakuyomi.audit") { "ISOLATED_AUDIT_REQUIRED" }
    check(InstrumentationRegistry.getArguments().getString("pendingMasterDispatchAudit") == "true") {
        "EXPLICIT_PENDING_MASTER_DISPATCH_AUDIT_REQUIRED"
    }
    NativeDiagnosticIdleGuard.requireIdle(target)
    val fixture = PendingMasterFixture(target)
    var failure: Throwable? = null
    try {
        fixture.prepare()
        block(fixture)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        withContext(NonCancellable) {
            var cleanupFailure: Throwable? = null
            try {
                fixture.close()
            } catch (cleanup: Throwable) {
                cleanupFailure = cleanup
            }
            try {
                val result = JSONObject()
                    .put("sessionUuid", fixture.sessionUuid)
                    .put("passed", cleanupFailure == null)
                cleanupFailure?.let { result.put("failureClass", it.javaClass.simpleName) }
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    2,
                    Bundle().apply { putString("pendingMasterDispatchCleanup", result.toString()) },
                )
            } catch (report: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = report else previous.addSuppressed(report)
            }
            cleanupFailure?.let { cleanup ->
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }
}

private class PendingMasterFixture(private val target: Context) {
    private val uuid = UUID.randomUUID().toString()
    val sessionUuid: String get() = uuid
    private val prefix = "pending-master-$uuid"
    private val root = File(target.cacheDir, prefix)
    private val files = File(root, "files")
    private val cache = File(root, "cache")
    private val preferencesNames = ConcurrentHashMap.newKeySet<String>()
    private val ownedDirectories = mutableSetOf<File>()
    private val leases = mutableListOf<PendingMasterFactoryLease>()
    private val scopeOwners = mutableListOf<Any>()
    private val context = object : ContextWrapper(target) {
        override fun getFilesDir() = files
        override fun getCacheDir() = cache
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val owned = "$prefix-$name"
            preferencesNames += owned
            return target.getSharedPreferences(owned, mode)
        }
    }
    lateinit var preferences: TranslationPreferences
        private set
    private lateinit var engine: TranslationEngineService
    lateinit var manager: TranslationManager
    private lateinit var store: TranslationStore
    private lateinit var stats: TranslationStatsStore
    lateinit var manga: Manga
    lateinit var chapter: Chapter
    private lateinit var page: File
    private lateinit var original: ByteArray
    private lateinit var source: StubSource
    private var registration: OnlineReaderSourceRegistration? = null
    private var prewarm: PendingMasterPrewarmLease? = null
    private var beforeWork: Map<String, Set<UUID>>? = null
    private var mangaId: Long? = null
    private var chapterId: Long? = null
    private var prewarmGuardInstalled = false

    suspend fun prepare() {
        check(!TraceLog.isEnabled && EngineTrace.sink == null) { "PENDING_GLOBAL_TRACE_MUST_BE_DISABLED" }
        prewarm = PendingMasterPrewarmLease()
        prewarmGuardInstalled = true
        // This singleton caches its first StoragePreferences. Bind it to the original dependency;
        // initialization alone does not inspect models or copy files from SAF.
        val originalStorage = Injekt.get<StoragePreferences>()
        check(field(TranslationEngineConfig, "storagePreferences") === originalStorage) {
            "PENDING_ENGINE_CONFIG_MUST_RETAIN_ORIGINAL_STORAGE"
        }
        preferences = prepareReactivePreferences()
        lease(fullType<TranslationPreferences>().type, preferences)
        engine = TranslationEngineService(context).also {
            scopeOwners += it
            lease(fullType<TranslationEngineService>().type, it)
        }
        check(root.parentFile.canonicalFile == target.cacheDir.canonicalFile)
        mkdir(root)
        mkdir(files)
        mkdir(cache)
        stats = TranslationStatsStore(context).also { lease(fullType<TranslationStatsStore>().type, it) }
        val sources = Injekt.get<SourceManager>()
        withTimeout(10_000) { sources.isInitialized.first { it } }
        val work = WorkManager.getInstance(target)
        beforeWork = PENDING_MASTER_TAGS.associateWith { tag ->
            work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS).also { infos ->
                check(infos.all { it.state.isFinished }) { "EXISTING_TRANSLATION_WORK_MUST_BE_IDLE" }
            }.mapTo(hashSetOf()) { it.id }
        }
        val storageRoot = File(root, "storage").also(::mkdir)
        val storagePreferences = StoragePreferences(
            object : FolderProvider {
                override fun directory() = storageRoot
                override fun path() = Uri.fromFile(storageRoot).toString()
            },
            InMemoryPreferenceStore(),
        )
        val storage = StorageManager(context, storagePreferences).also(scopeOwners::add)
        val provider = DownloadProvider(context, storage, LibraryPreferences(InMemoryPreferenceStore()))
        lease(fullType<DownloadProvider>().type, provider)
        val translationCache = TranslationCache(context, provider, sources, preferences).also(scopeOwners::add)
        lease(fullType<TranslationCache>().type, translationCache)
        manager = TranslationManager(context).also {
            scopeOwners += it
            lease(fullType<TranslationManager>().type, it)
        }
        store = field(manager, "translationStore") as TranslationStore
        manager.ensureRestored()
        check(manager.queueState.value.isEmpty())
        source = StubSource(
            (UUID.fromString(uuid).mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(10_000_000),
            "all",
            "Pending audit $uuid",
        )
        registration = OnlineReaderSourceRegistration(sources, source)
        manga = Injekt.get<MangaRepository>().insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = source.id,
                    title = "Pending audit $uuid",
                    url = "/pending/$uuid",
                    initialized = true,
                ),
            ),
        ).single().also { mangaId = it.id }
        chapter = Injekt.get<ChapterRepository>().addAll(
            listOf(Chapter.create().copy(mangaId = manga.id, name = "Pending chapter", url = "/pending/chapter/$uuid")),
        ).single().also { chapterId = it.id }
        val directory = File(
            storageRoot,
            "downloads/${provider.getSourceDirName(source)}/${provider.getMangaDirName(manga.title)}/" +
                provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url),
        )
        mkdirParents(directory, storageRoot)
        page = File(directory, "001.png")
        check(!page.exists())
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        original = page.readBytes()
        assertColdAndUnchanged()
    }

    private suspend fun prepareReactivePreferences(): TranslationPreferences = coroutineScope {
        val raw = context.getSharedPreferences("translation-settings", Context.MODE_PRIVATE)
        val listenerReady = CompletableDeferred<Unit>()
        val observed = object : SharedPreferences by raw {
            override fun registerOnSharedPreferenceChangeListener(
                listener: SharedPreferences.OnSharedPreferenceChangeListener,
            ) {
                raw.registerOnSharedPreferenceChangeListener(listener)
                listenerReady.complete(Unit)
            }
        }
        val prefs = TranslationPreferences(AndroidPreferenceStore(context, observed)).apply {
            translationMasterEnabled.set(false)
            translationEnabled.set(false)
            liveTranslate.set(false)
            nightReadEnabled.set(false)
            keepMaterials.set(false)
            inpaintMethod.set("boxfill")
            liveInpaintMethod.set("boxfill")
        }
        assertFalse("PENDING_PREFERENCE_INITIAL_GET", prefs.translationMasterEnabled.get())
        val emissions = Channel<Boolean>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            prefs.translationMasterEnabled.changes().collect { emissions.send(it) }
        }
        try {
            withTimeout(5_000) {
                assertFalse("PENDING_PREFERENCE_INITIAL_EMISSION", emissions.receive())
                listenerReady.await()
                prefs.translationMasterEnabled.set(true)
                assertTrue("PENDING_PREFERENCE_CHANGED_GET", prefs.translationMasterEnabled.get())
                assertTrue("PENDING_PREFERENCE_CHANGED_EMISSION", emissions.receive())
                prefs.translationMasterEnabled.set(false)
                assertFalse("PENDING_PREFERENCE_RESET_GET", prefs.translationMasterEnabled.get())
                assertFalse("PENDING_PREFERENCE_RESET_EMISSION", emissions.receive())
            }
        } finally {
            collector.cancelAndJoin()
            emissions.cancel()
        }
        prefs
    }

    suspend fun withDrainHeld(action: suspend () -> Unit) {
        val mutex = field(manager, "drainMutex") as Mutex
        withTimeout(15_000) {
            mutex.withLock {
                try {
                    action()
                } finally {
                    preferences.translationMasterEnabled.set(false)
                    manager.cancelPendingTranslation(chapter.id)
                    assertTrue(manager.queueState.value.isEmpty())
                    assertFalse(manager.isPendingTranslate(chapter.id))
                    assertColdAndUnchanged()
                    withContext(NonCancellable) {
                        val scope = field(manager, "scope") as CoroutineScope
                        withTimeout(10_000) { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
                    }
                    assertTrue(manager.queueState.value.isEmpty())
                    assertColdAndUnchanged()
                }
            }
        }
    }

    suspend fun hasDurableIntent(): Boolean {
        val queued = store.restore().any { it.chapter.id == chapter.id && !it.nightRender }
        val marked = chapter.id in store.restorePendingMarks(Long.MIN_VALUE).translate
        return queued || marked
    }

    fun assertHardOff() {
        assertFalse(preferences.translationMasterEnabled.get())
        assertColdAndUnchanged()
        val work = WorkManager.getInstance(target)
        for (tag in PENDING_MASTER_TAGS) {
            assertTrue(
                "PENDING_MASTER_OFF_DOES_NOT_START_WORKER_$tag",
                work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS).none {
                    it.id !in requireNotNull(beforeWork).getValue(tag) && !it.state.isFinished
                },
            )
        }
    }

    suspend fun awaitExactlyOneQueued() {
        withTimeout(5_000) {
            while (manager.queueState.value.none { it.chapter.id == chapter.id }) delay(10)
        }
        assertExactlyOneQueued()
    }

    suspend fun assertExactlyOneQueued() {
        val entry = manager.queueState.value.single()
        assertEquals("PENDING_ON_EXACT_CHAPTER", chapter.id, entry.chapter.id)
        assertEquals("PENDING_ON_HELD_QUEUE", TranslationItem.Status.QUEUE, entry.status)
        assertEquals("PENDING_ON_STORED_EXACTLY_ONCE", listOf(chapter.id), store.restore().map { it.chapter.id })
        assertTrue(stats.allDays().isEmpty())
    }

    suspend fun awaitWorkerAndHeartbeatAdmission() {
        val work = WorkManager.getInstance(target)
        withTimeout(8_000) {
            while (PENDING_MASTER_TAGS.any { tag ->
                    work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS).none {
                        it.id !in requireNotNull(beforeWork).getValue(tag) && it.state in PENDING_ACTIVE_WORK_STATES
                    }
                }
            ) {
                delay(25)
            }
        }
    }

    fun assertColdAndUnchanged() {
        assertEquals("PENDING_NO_ACTIVE_INFERENCE", 0, TranslationBusy.active.value)
        assertFalse(engine.warm.value)
        assertFalse(engine.loading.value)
        assertEquals(null, nullableField(engine, "engine"))
        assertEquals(0, (field(engine, "inFlight") as AtomicInteger).get())
        if (::page.isInitialized && ::original.isInitialized) assertArrayEquals(original, page.readBytes())
    }

    suspend fun close() {
        check(prewarmGuardInstalled) { "PENDING_PREWARM_GUARD_UNPROVEN_REQUIRES_AUDIT_PROCESS_STOP" }
        if (::manager.isInitialized) {
            preferences.translationMasterEnabled.set(false)
            chapterId?.let(manager::cancelPendingTranslation)
        }
        // No dependency or own storage is restored/deleted until every private callback is stopped.
        for (owner in scopeOwners.asReversed()) {
            val scope = field(owner, "scope") as CoroutineScope
            withTimeout(10_000) { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
        }
        if (::engine.isInitialized) {
            engine.shutdown()
            assertColdAndUnchanged()
        }
        beforeWork?.let { before ->
            val work = WorkManager.getInstance(target)
            for (tag in PENDING_MASTER_TAGS) {
                work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS)
                    .filter { it.id !in before.getValue(tag) && !it.state.isFinished }
                    .forEach { work.cancelWorkById(it.id).result.get(5, TimeUnit.SECONDS) }
            }
            withTimeout(10_000) {
                while (PENDING_MASTER_TAGS.any { tag ->
                        work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS).any { !it.state.isFinished }
                    }
                ) {
                    delay(25)
                }
            }
        }
        chapterId?.let { id -> Injekt.get<ChapterRepository>().removeChaptersWithIds(listOf(id)) }
        mangaId?.let { id ->
            val own = Injekt.get<MangaRepository>().getMangaById(id)
            check(own.source == source.id && own.url == "/pending/$uuid")
            val driver = Injekt.get<SqlDriver>()
            check(
                driver.execute(null, "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?", 3) {
                    bindLong(0, id)
                    bindLong(1, source.id)
                    bindString(2, "/pending/$uuid")
                }.await() == 1L,
            )
            driver.notifyListeners("mangas", "chapters", "history")
        }
        registration?.close()
        prewarm?.restoreBackground()
        leases.asReversed().forEach(Closeable::close)
        NativeDiagnosticIdleGuard.requireIdle(target)
        for (name in preferencesNames) {
            check(name.startsWith("$prefix-"))
            check(target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
            check(target.deleteSharedPreferences(name))
        }
        if (root.exists()) {
            check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile && root.name == prefix)
            val entries = root.walkTopDown().toList()
            check(entries.filter { it.isFile }.toSet() == if (::page.isInitialized) setOf(page) else emptySet<File>()) {
                "UNKNOWN_FIXTURE_FILE_RETAINED"
            }
            check(
                entries.filter {
                    it.isDirectory
                }.toSet() == ownedDirectories,
            ) { "UNKNOWN_FIXTURE_DIRECTORY_RETAINED" }
            if (::page.isInitialized) {
                assertArrayEquals(original, page.readBytes())
                check(page.delete())
            }
            ownedDirectories.sortedByDescending { it.path.length }.forEach { check(it.delete()) }
            check(!root.exists())
        }
    }

    private fun lease(type: Type, value: Any) {
        leases += PendingMasterFactoryLease(type, value)
    }

    private fun mkdir(directory: File) {
        check(directory == root || directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        check(!directory.exists() && directory.mkdir())
        ownedDirectories += directory
    }

    private fun mkdirParents(directory: File, stop: File) {
        if (directory == stop) return
        mkdirParents(requireNotNull(directory.parentFile), stop)
        mkdir(directory)
    }
}

private val PENDING_MASTER_TAGS = listOf("Translator", "TranslatorHeartbeat")
private val PENDING_ACTIVE_WORK_STATES = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)

private class PendingMasterFactoryLease(type: Type, instance: Any) : Closeable {
    private val registrar = Injekt.registrar

    @Suppress("UNCHECKED_CAST")
    private val factories = registrar.javaClass.getDeclaredField("factories").apply { isAccessible = true }
        .get(registrar) as ConcurrentHashMap<Type, () -> Any>
    private val original = checkNotNull(factories[type])
    private val key = type
    private val replacement: () -> Any = { instance }

    init {
        check(registrar.javaClass.name == "dev.mihon.injekt.PatchedDefaultRegister")
        check(factories.replace(key, original, replacement))
    }

    override fun close() {
        check(factories.replace(key, replacement, original)) { "PENDING_DEPENDENCY_LEASE_OWNER_CHANGED" }
    }
}

private class PendingMasterPrewarmLease {
    private lateinit var controller: Any
    private var finished = false
    private var everForeground = false
    private var pending: Job? = null

    init {
        onMain {
            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            controller = field(StartupEnginePrewarm, "controller")
            check(controller.javaClass.name.endsWith(".StartupPrewarmController"))
            pending = nullableField(controller, "pending") as Job?
            check(pending?.isActive != true)
            finished = field(controller, "finished") as Boolean
            everForeground = field(controller, "everForeground") as Boolean
            set("finished", true)
        }
    }

    suspend fun restoreBackground() {
        withContext(Dispatchers.Main.immediate) {
            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain {
            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            check(field(StartupEnginePrewarm, "controller") === controller)
            check(field(controller, "finished") == true)
            check((nullableField(controller, "pending") as Job?)?.isActive != true)
            set("pending", pending)
            set("everForeground", everForeground)
            set("finished", finished)
        }
    }

    private fun set(name: String, value: Any?) {
        controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
    }

    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }
}

private fun field(owner: Any, name: String): Any = checkNotNull(nullableField(owner, name))
private fun nullableField(owner: Any, name: String): Any? =
    owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
