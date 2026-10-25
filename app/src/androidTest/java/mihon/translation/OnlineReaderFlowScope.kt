package mihon.translation

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.tachiyomi.crash.TraceLog
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.SecretCipher
import eu.kanade.tachiyomi.data.translation.StartupEnginePrewarm
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationStatsStore
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.EngineTrace
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.Pipeline
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.StringCrypto
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Isolated dependencies for the real reader test; never changes the user's preference stores. */
internal class OnlineReaderFlowScope(private val target: Context) {
    val uuid: UUID = UUID.randomUUID()
    val prefix = "online-reader-flow-$uuid"
    private val rootOwner = lazy { File(target.cacheDir.canonicalFile, prefix).apply { check(mkdir()) } }
    val root: File by rootOwner
    private val privateCache by lazy { File(root, "cache").apply { check(mkdir()) } }
    private val privateFiles by lazy { File(root, "files").apply { check(mkdir()) } }
    val output: File by lazy { File(root, "evidence").apply { check(mkdir()) } }
    val storageRoot: File by lazy { File(root, "storage").apply { check(mkdir()) } }
    private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
    private val context = object : ContextWrapper(target) {
        override fun getCacheDir() = privateCache
        override fun getFilesDir() = privateFiles
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val ownedName = "$prefix-$name"
            preferenceNames += ownedName
            return target.getSharedPreferences(ownedName, mode)
        }
    }

    // Engine/manager model checks see existing models; their preferences/cache remain UUID-private.
    private val modelContext = object : ContextWrapper(context) {
        override fun getFilesDir() = target.filesDir
    }
    private val privateSettings by lazy { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val storeOwner = lazy { SecretSnapshotStore(AndroidPreferenceStore(context, privateSettings)) }
    private val store by storeOwner
    val preferences by lazy { TranslationPreferences(store, SecretCipher) }
    val readerPreferences by lazy { ReaderPreferences(store) }
    val downloadPreferences by lazy { DownloadPreferences(store) }
    private val libraryPreferences by lazy { LibraryPreferences(store) }
    private val storagePreferences by lazy {
        StoragePreferences(
            object : FolderProvider {
                override fun directory() = storageRoot
                override fun path() = Uri.fromFile(storageRoot).toString()
            },
            store,
        )
    }
    private val leases = mutableListOf<OnlineReaderFlowFactoryLease>()
    private val owners = mutableListOf<Any>()
    private val scenarios = mutableListOf<ActivityScenario<ReaderActivity>>()
    private val viewModelJobs = mutableListOf<Job>()
    private var prewarm: OnlineReaderFlowPrewarmLease? = null
    private var guardInstalled = false
    private var prepared = false
    private var configuredKeys: Set<String> = emptySet()
    private var server: OnlineReaderFixtureServer? = null
    private var registration: OnlineReaderSourceRegistration? = null
    private var originalPreferences: Map<String, *>? = null
    private var originalStoragePreferences: StoragePreferences? = null
    private var beforeWork: Map<String, Map<UUID, WorkInfo.State>>? = null
    private var workWitness: JSONObject? = null
    private var beforeNotifications: Set<Pair<String?, Int>>? = null
    private var beforeMangas: List<tachiyomi.data.Mangas>? = null
    private var ownedManga: Manga? = null
    private var ownedChapter: Chapter? = null
    private var chapterCache: ChapterCache? = null
    lateinit var sources: SourceManager
        private set
    lateinit var engine: TranslationEngineService
        private set
    lateinit var stats: TranslationStatsStore
        private set
    lateinit var provider: DownloadProvider
        private set
    lateinit var cache: TranslationCache
        private set
    lateinit var downloads: DownloadManager
        private set
    lateinit var translations: eu.kanade.tachiyomi.data.translation.TranslationManager
        private set

    suspend fun prepare() {
        check(target.packageName == "app.yomitori.yakuyomi.audit") { "ISOLATED_AUDIT_REQUIRED" }
        NativeDiagnosticIdleGuard.requireIdle(target)
        preferences.translationMasterEnabled.set(false)
        preferences.translationEnabled.set(false)
        preferences.liveTranslate.set(false)
        preferences.nightReadEnabled.set(false)
        lease(fullType<TranslationPreferences>().type, preferences)
        engine = TranslationEngineService(modelContext)
        lease(fullType<TranslationEngineService>().type, engine)
        prewarm = OnlineReaderFlowPrewarmLease()
        guardInstalled = true
        val original = PreferenceManager.getDefaultSharedPreferences(target).all.toMap()
        originalPreferences = original
        // No SourceManager, WorkManager, model resolution or other shared manager wait precedes the guard.
        check(!TraceLog.isEnabled && EngineTrace.sink == null) { "PROCESS_TRACE_MUST_ALREADY_BE_DISABLED" }
        // This object caches its first StoragePreferences forever; initialize only under the real binding.
        val originalStorage = Injekt.get<StoragePreferences>()
        originalStoragePreferences = originalStorage
        TranslationEngineConfig.downloadedDir(modelContext)
        check(readerField(TranslationEngineConfig, "storagePreferences") === originalStorage)
        requireVerifiedLocalModels()
        requireCredentialSnapshotContract()
        val typedPreferences = listOf(preferences, readerPreferences, downloadPreferences, libraryPreferences)
        val keys = typedPreferences.flatMap { instance ->
            instance.javaClass.declaredFields.mapNotNull { field ->
                if (!Preference::class.java.isAssignableFrom(field.type)) return@mapNotNull null
                (field.apply { isAccessible = true }.get(instance) as Preference<*>).key()
            }
        }.toSet()
        configuredKeys = keys
        check(original["translation_diagnostic_log"] != true)
        store.capture(original)
        check(
            store.getAll().filterKeys { it.startsWith("translation_api_key") } ==
                original.filterKeys { it.startsWith("translation_api_key") },
        )
        val edit = privateSettings.edit()
        for ((key, value) in original) {
            if (key !in keys || key.startsWith("translation_api_key")) continue
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> {
                    check(value.all { it is String })
                    @Suppress("UNCHECKED_CAST")
                    edit.putStringSet(key, (value as Set<String>).toSet())
                }
                else -> error("UNSUPPORTED_PRIVATE_PREFERENCE_TYPE")
            }
        }
        check(edit.commit())
        // Keep the private engine disabled until its complete private dependency graph exists.
        preferences.translationMasterEnabled.set(false)
        preferences.translationEnabled.set(false)
        preferences.liveTranslate.set(false)
        preferences.nightReadEnabled.set(false)
        // Real engine snapshots must retain the current profile and its RAM-only encrypted credentials.
        val profile = preferences.snapshot()
        check(
            profile.provider.get() == preferences.provider.get() && profile.apiBase.get() == preferences.apiBase.get(),
        )
        check(profile.model.get() == preferences.model.get() && profile.activeApiKey() == preferences.activeApiKey())
        sources = Injekt.get()
        withTimeout(20_000) { sources.isInitialized.first { it } }
        val work = WorkManager.getInstance(target)
        beforeWork = workNames.associateWith { name ->
            work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).also { rows ->
                check(rows.all { it.state.isFinished }) { "EXISTING_READER_WORK_MUST_BE_IDLE" }
            }.associate { it.id to it.state }
        }
        check(ownNotificationIds().isEmpty()) { "EXISTING_READER_NOTIFICATIONS_MUST_BE_ABSENT" }
        beforeNotifications = notificationKeys()
        lease(fullType<ReaderPreferences>().type, readerPreferences)
        lease(fullType<DownloadPreferences>().type, downloadPreferences)
        lease(fullType<LibraryPreferences>().type, libraryPreferences)
        storagePreferences.baseStorageDirectory.set(Uri.fromFile(storageRoot).toString())
        check(
            withTimeout(2_000) { storagePreferences.baseStorageDirectory.changes().first() } ==
                Uri.fromFile(storageRoot).toString(),
        )
        lease(fullType<StoragePreferences>().type, storagePreferences)
        val storage = StorageManager(context, storagePreferences).also(owners::add)
        lease(fullType<StorageManager>().type, storage)
        provider = DownloadProvider(context, storage, libraryPreferences)
        lease(fullType<DownloadProvider>().type, provider)
        val downloaded = DownloadCache(context, provider, sources, Injekt.get(), storage).also(owners::add)
        lease(fullType<DownloadCache>().type, downloaded)
        chapterCache = ChapterCache(context, Injekt.get()).also { lease(fullType<ChapterCache>().type, it) }
        stats = TranslationStatsStore(context).also { lease(fullType<TranslationStatsStore>().type, it) }
        cache = TranslationCache(context, provider, sources, preferences).also(owners::add)
        lease(fullType<TranslationCache>().type, cache)
        // Its PageTranslator also checks model paths; all non-model stores/cache remain private.
        check(TranslationEngineConfig.modelsResolvable(modelContext))
        check(readerField(TranslationEngineConfig, "storagePreferences") === originalStoragePreferences)
        translations = eu.kanade.tachiyomi.data.translation.TranslationManager(modelContext).also(owners::add)
        check(readerField(readerField(translations, "pageTranslator"), "context") === modelContext)
        lease(fullType<eu.kanade.tachiyomi.data.translation.TranslationManager>().type, translations)
        downloads =
            DownloadManager(
                context,
                provider,
                downloaded,
                sourceManager = sources,
                downloadPreferences = downloadPreferences,
            )
        owners += readerField(downloads, "downloader")
        lease(fullType<DownloadManager>().type, downloads)
        check(translations.queueState.value.isEmpty() && downloads.queueState.value.isEmpty() && !downloads.isRunning)
        check(!engine.warm.value && !engine.loading.value && readerNullableField(engine, "engine") == null)
        check(stats.allDays().isEmpty())
        prepared = true
    }

    suspend fun openServer(originals: List<ByteArray>): OnlineReaderFixtureServer {
        check(prepared && server == null)
        val result = OnlineReaderFixtureServer(originals)
        server = result
        val database = Injekt.get<Database>()
        val all = database.mangasQueries.getAllManga().awaitAsList()
        check(all.size <= 10_000 && all.none { it.source == result.source.id }) { "FIXTURE_SOURCE_DATABASE_COLLISION" }
        beforeMangas = all
        registration = OnlineReaderSourceRegistration(sources, result.source)
        return result
    }

    fun recordManga(manga: Manga) {
        check(ownedManga == null && manga.source == checkNotNull(server).source.id)
        check(manga.url == "/fixture/$uuid" && manga.title == "Online reader synthetic $uuid" && !manga.favorite)
        ownedManga = manga
        File(
            root,
            "owned.json",
        ).writeText(JSONObject().put("uuid", uuid.toString()).put("mangaId", manga.id).toString())
    }

    fun recordOwned(manga: Manga, chapter: Chapter) {
        check(ownedManga == manga && ownedChapter == null && chapter.mangaId == manga.id)
        ownedChapter = chapter
        File(root, "owned.json").writeText(
            JSONObject().put("uuid", uuid.toString()).put("mangaId", manga.id).put("chapterId", chapter.id).toString(),
        )
    }

    fun <T> set(preference: Preference<T>, value: T) {
        check(preference.key() in configuredKeys && !preference.key().startsWith("translation_api_key"))
        preference.set(value)
    }

    fun saveReport(report: JSONObject) {
        if (rootOwner.isInitialized()) File(output, "result.json").writeText(report.toString(2))
    }

    fun launchReader(intent: Intent): ActivityScenario<ReaderActivity> =
        ActivityScenario.launch<ReaderActivity>(intent).also { scenario ->
            scenarios += scenario
            scenario.onActivity { viewModelJobs += checkNotNull(it.viewModel.viewModelScope.coroutineContext[Job]) }
        }

    suspend fun close(): JSONObject {
        var failed = false
        var cleanupStep = 0
        suspend fun attempt(name: String, block: suspend () -> Unit): Boolean = try {
            cleanupStep++
            block()
            true
        } catch (error: Throwable) {
            failed = true
            emitOnlineReaderFlow(
                "onlineReaderFlowCleanupStepFailure",
                JSONObject()
                    .put("step", cleanupStep).put("stepName", name)
                    .put("failureClass", error.javaClass.name).put("failure", onlineReaderFlowFailure(error)),
            )
            false
        }
        server?.releaseDownload()
        if (::translations.isInitialized) ownedChapter?.let { translations.cancelPendingTranslation(it.id) }
        if (::downloads.isInitialized) downloads.downloaderStop()
        var quiescent = guardInstalled
        for (scenario in scenarios.asReversed()) {
            quiescent = attempt("reader_activity_close") {
                if (scenario.state != Lifecycle.State.DESTROYED) scenario.close()
            } && quiescent
        }
        for (job in viewModelJobs) {
            quiescent = attempt("reader_viewmodel_join") { withTimeout(30_000) { job.join() } } && quiescent
        }
        quiescent = attempt("process_lifecycle_background") { checkNotNull(prewarm).awaitBackground() } && quiescent
        for (owner in owners.asReversed()) {
            quiescent = attempt("owner_scope_${owner.javaClass.simpleName}") {
                withTimeout(30_000) { readerJob(owner).cancelAndJoin() }
            } && quiescent
        }
        quiescent = attempt("native_engine_client_quiescence") {
            withTimeout(30_000) { TranslationBusy.active.first { it == 0 } }
            if (::engine.isInitialized) {
                check((readerField(engine, "inFlight") as AtomicInteger).get() == 0)
                val pipeline = readerNullableField(engine, "engine") as? Pipeline
                val llm = pipeline?.let { readerNullableField(it, "ownedLlmTranslator") as? LlmTranslator }
                withTimeout(60_000) { engine.shutdown() }
                check(!engine.warm.value && !engine.loading.value && readerNullableField(engine, "engine") == null)
                if (pipeline != null) {
                    for (name in listOf("detector", "ocr", "inpainter")) {
                        val component = readerField(pipeline, name)
                        check(
                            component.javaClass.getDeclaredField("ncnnHandle").apply {
                                isAccessible = true
                            }.getLong(component) ==
                                0L,
                        )
                    }
                }
                if (llm != null) {
                    val dispatcher = readerField(llm, "ownedDispatcher") as Dispatcher
                    val pool = readerField(llm, "ownedConnectionPool") as ConnectionPool
                    withTimeout(10_000) {
                        while (dispatcher.queuedCallsCount() != 0 || dispatcher.runningCallsCount() != 0 ||
                            !dispatcher.executorService.isShutdown
                        ) {
                            delay(25)
                        }
                    }
                    check(pool.connectionCount() == 0)
                }
            }
        } && quiescent
        quiescent = attempt("fixture_http_server_close") { server?.close() } && quiescent
        quiescent = attempt("owned_work_finish") { finishOwnedWork() } && quiescent
        if (::engine.isInitialized) {
            quiescent =
                attempt("engine_scope_join") { withTimeout(10_000) { readerJob(engine).cancelAndJoin() } } && quiescent
        }
        if (!quiescent || failed) {
            emitOnlineReaderFlow(
                "onlineReaderFlowCleanup",
                JSONObject().put("passed", false)
                    .put("ownedGraphAndLedgerPreserved", true).put("needsAuditProcessStop", true),
            )
            error("ONLINE_READER_CALLBACK_QUIESCENCE_UNPROVEN")
        }
        // No writer/Activity/worker/engine can resolve a restored real dependency after this point.
        attempt("private_chapter_cache_close") {
            chapterCache?.let { (readerField(it, "diskCache") as Closeable).close() }
        }
        attempt("owned_database_rows_remove") { removeOwnedRows() }
        attempt("fixture_source_unregister") { registration?.close() }
        attempt("prewarm_restore") { prewarm?.restore() }
        for (lease in leases.asReversed()) attempt("dependency_factory_restore") { lease.close() }
        attempt("original_preferences_idle_trace_verify") {
            originalPreferences?.let { check(PreferenceManager.getDefaultSharedPreferences(target).all == it) }
            NativeDiagnosticIdleGuard.requireIdle(target)
            originalStoragePreferences?.let {
                check(readerField(TranslationEngineConfig, "storagePreferences") === it)
            }
            check(!TraceLog.isEnabled && EngineTrace.sink == null)
        }
        attempt("notification_baseline_restore") {
            val notifications = target.getSystemService(NotificationManager::class.java)
            beforeNotifications?.let { before ->
                val current = notificationKeys()
                check(current.containsAll(before))
                (current - before).forEach { (tag, id) -> notifications.cancel(tag, id) }
                check(notificationKeys() == before)
            }
        }
        if (failed) error("ONLINE_READER_RESTORE_OR_ROWS_UNPROVEN")
        for (name in preferenceNames) {
            check(name.startsWith("$prefix-"))
            check(target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
            check(target.deleteSharedPreferences(name))
        }
        if (storeOwner.isInitialized()) store.eraseSecrets()
        if (rootOwner.isInitialized()) {
            check(root.parentFile.canonicalFile == target.cacheDir.canonicalFile && root.name == prefix)
            val entries = root.walkTopDown().toList()
            check(entries.size <= 1_000 && entries.all { it.canonicalFile == it.absoluteFile })
            check(root.deleteRecursively() && !root.exists())
        }
        return JSONObject().put("passed", true).put("privateGraphQuiescent", true)
            .put("privateNativeEngineClosed", true)
            .put("originalPreferenceSnapshotCaptured", originalPreferences != null)
            .put("originalPreferenceValuesUnchanged", originalPreferences != null)
            .put("ownedRowsAndPrivateFilesRemoved", true).put("ownedWorkFinished", true)
            .put("work", workWitness)
            .put("originalRawPreferenceBytesRequireIndependentHostCheck", true)
    }

    /** Each required translation file resolves locally; optional night models remain untouched. */
    private fun requireVerifiedLocalModels() {
        val directory = TranslationEngineConfig.downloadedDir(modelContext).canonicalFile
        check(directory.name == "models" && directory.parentFile == target.filesDir.canonicalFile)
        val files = checkNotNull(directory.listFiles()).toList()
        // Production picks the first matching .param. Refuse any unverified alternate for those roles.
        val roleParameters = files.filter { file ->
            val name = file.name.lowercase()
            name.endsWith(".param") && listOf("dbnet", "ocr_48px_ctc.ncnn", "aot").any(name::contains)
        }.map { it.name }.toSet()
        check(
            roleParameters == setOf(
                "dbnet_detect.ncnn.param",
                "ocr_48px_ctc.ncnn.param",
                "mit_aot_fixed512.ncnn.param",
            ),
        )
        for ((name, pin) in localModels) {
            val file = File(directory, name)
            check(file.isFile && file.canRead() && file.canonicalFile == file.absoluteFile)
            check(file.length() == pin.first) { "VERIFIED_LOCAL_TRANSLATION_MODEL_SIZE_REQUIRED" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == pin.second) {
                "VERIFIED_LOCAL_TRANSLATION_MODELS_REQUIRED"
            }
        }
    }

    private suspend fun finishOwnedWork() {
        val before = beforeWork ?: return
        val work = WorkManager.getInstance(target)
        val originals = before.values.flatMap { it.entries }.associate { it.toPair() }
        check(originals.values.all { it.isFinished })
        var presentFinished = 0
        var missingFinished = 0
        for ((id, state) in originals) {
            val current = work.getWorkInfoById(id).get(5, TimeUnit.SECONDS)
            if (current == null) {
                missingFinished++ // REPLACE/pruning may remove terminal history; never claim equality.
            } else {
                check(current.state.isFinished && current.state == state)
                presentFinished++
            }
        }
        val owned = before.keys.flatMap { name -> work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS) }
            .distinctBy { it.id }.filter { it.id !in originals }
        val unfinished = owned.filter { !it.state.isFinished }
        unfinished.forEach { work.cancelWorkById(it.id).result.get(5, TimeUnit.SECONDS) }
        withTimeout(15_000) {
            while (before.keys.any { name ->
                    work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).any {
                        it.id !in originals && !it.state.isFinished
                    }
                }
            ) {
                delay(50)
            }
        }
        for ((id, state) in originals) {
            work.getWorkInfoById(id).get(5, TimeUnit.SECONDS)?.let {
                check(it.state.isFinished && it.state == state)
            }
        }
        workWitness = JSONObject().put("originalRowsAllFinishedAtStart", true)
            .put("originalPresentFinishedUnchangedCount", presentFinished)
            .put("originalMissingFinishedCount", missingFinished)
            .put("missingFinishedCauseNotDetermined", missingFinished > 0)
            .put("workHistoryEqualityClaimed", false)
            .put("newWorkObservedCount", owned.size).put("newWorkCancelledCount", unfinished.size)
            .put("newObservedWorkAllFinished", true)
    }

    private suspend fun removeOwnedRows() {
        val original = beforeMangas ?: return
        val source = checkNotNull(server).source
        val database = Injekt.get<Database>()
        val now = database.mangasQueries.getAllManga().awaitAsList()
        val owned = now.filter { it.source == source.id }
        val manga = ownedManga
        if (manga == null) {
            check(owned.isEmpty())
        } else {
            check(
                owned.size == 1 && owned.single()._id == manga.id && owned.single().url == manga.url &&
                    !owned.single().favorite,
            )
            database.mangasQueries.deleteNonLibraryManga(listOf(source.id), 0L)
        }
        check(database.mangasQueries.getAllManga().awaitAsList() == original) { "NONFIXTURE_MANGA_ROWS_CHANGED" }
    }

    private fun ownNotificationIds(): List<Int> = target.getSystemService(NotificationManager::class.java)
        .activeNotifications.map { it.id }.filter { it in notificationIds }

    private fun notificationKeys() = target.getSystemService(NotificationManager::class.java)
        .activeNotifications.map { it.tag to it.id }.toSet()

    private fun lease(type: Type, instance: Any) {
        leases += OnlineReaderFlowFactoryLease(type, instance)
    }

    private companion object {
        // Pinned translation entries from yakuyomi-engine/models.json; no model download or mutation.
        val localModels = mapOf(
            "dbnet_detect.ncnn.param" to
                Pair(13392L, "9e6db2f8c6b0662ab00eb2100b3373d3c984a235eaac0e61c0b2a484ee1ff7b5"),
            "dbnet_detect.ncnn.bin" to
                Pair(153010556L, "f57bdbede7764a534c56e88be0269602259a7fcd47e54e8b7d954fd0fcc55c3d"),
            "ocr_48px_ctc.ncnn.param" to
                Pair(18133L, "e701cfc5df9d3c55c9fd0a36725499d01d45c18e32946271fc26e580cd9901bd"),
            "ocr_48px_ctc_mixed.ncnn.param" to
                Pair(18438L, "32e298deca3acb8ba95897ca8cccbd028582ae42a697c3c05b4eaefe198309e3"),
            "ocr_48px_ctc.ncnn.bin" to
                Pair(83037664L, "3e0a809441f5284871d18a3d757a7ec098c4b9ee64bdfd7ac776a84aa057c7a9"),
            "mit_aot_fixed512.ncnn.param" to
                Pair(33810L, "f21ef860d21a6cdf60dfb1742c08d1c0d98837bceeb2ee3fe9c2dbbeee7d32b5"),
            "mit_aot_fixed512.ncnn.bin" to
                Pair(11366088L, "a52db45eafc1dd2aa4ce9a339c711917fa98fefb31ce4506d4c95e8b5e3560b6"),
        )
        val workNames = listOf("Downloader", "Translator", "TranslatorHeartbeat")
        val notificationIds =
            setOf(
                Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS,
                Notifications.ID_DOWNLOAD_CHAPTER_ERROR,
                Notifications.ID_TRANSLATION_PROGRESS,
            )
    }
}

/** Credential slots stay in RAM; all other declared preferences retain Android's reactive flows. */
private class SecretSnapshotStore(private val delegate: PreferenceStore) : PreferenceStore by delegate {
    private var raw: Map<String, *> = emptyMap<String, Any>()
    private val slots = mutableMapOf<String, MemoryString>()

    @Synchronized
    fun capture(values: Map<String, *>) {
        raw = values.filterKeys { it.startsWith("translation_api_key") }
        slots.forEach { (key, slot) -> slot.capture(raw[key] as? String) }
    }

    @Synchronized
    override fun getString(key: String, defaultValue: String): Preference<String> =
        if (key.startsWith("translation_api_key")) {
            slots.getOrPut(key) { MemoryString(key, defaultValue, raw[key] as? String) }
        } else {
            delegate.getString(key, defaultValue)
        }

    @Synchronized
    override fun getAll(): Map<String, *> = delegate.getAll().filterKeys { !it.startsWith("translation_api_key") }
        .toMutableMap().apply {
            putAll(raw)
            for ((key, slot) in slots) {
                if (slot.isSet()) put(key, slot.get()) else remove(key)
            }
        }

    @Synchronized
    fun eraseSecrets() {
        raw = emptyMap<String, Any>()
        slots.values.forEach { it.delete() }
    }

    private class MemoryString(
        private val key: String,
        private val default: String,
        initial: String?,
    ) : Preference<String> {
        private val value = MutableStateFlow(initial)
        fun capture(raw: String?) {
            value.value = raw
        }
        override fun key() = key
        override fun get() = value.value ?: default
        override fun set(value: String) {
            this.value.value = value
        }
        override fun isSet() = value.value != null
        override fun delete() {
            value.value = null
        }
        override fun defaultValue() = default
        override fun changes(): Flow<String> = value.map { it ?: default }
        override fun stateIn(
            scope: CoroutineScope,
        ): StateFlow<String> = changes().stateIn(scope, SharingStarted.Eagerly, get())
    }
}

/** Exercises real TranslationPreferences.snapshot without user credentials, disk writes or Android Keystore. */
private fun requireCredentialSnapshotContract() {
    val delegate = InMemoryPreferenceStore()
    val store = SecretSnapshotStore(delegate)
    val crypto = object : StringCrypto {
        override fun encrypt(plain: String) = "fixture-cipher:$plain"
        override fun decrypt(encrypted: String) = encrypted.takeIf { it.startsWith("fixture-cipher:") }
            ?.removePrefix("fixture-cipher:")
    }
    store.capture(
        mapOf(
            "translation_api_key_enc__custom" to crypto.encrypt("fixture-custom-one"),
            "translation_api_key_enc__sakura" to crypto.encrypt("fixture-sakura"),
            "translation_api_key" to "fixture-legacy",
        ),
    )
    val preferences = TranslationPreferences(store, crypto)
    // The untouched sakura slot tests enumeration even before getString materializes that slot.
    val first = preferences.snapshot()
    check(first.activeApiKey() == "fixture-custom-one")
    check(first.apiKeyFor("sakura").get() == "fixture-sakura")
    check(first.apiKey.get() == "fixture-legacy")
    check("translation_api_key" !in store.getAll())
    check(store.getAll()["translation_api_key_enc"] == crypto.encrypt("fixture-legacy"))
    preferences.apiKeyFor("custom").set("fixture-custom-two")
    check(preferences.snapshot().activeApiKey() == "fixture-custom-two")
    check(first.activeApiKey() == "fixture-custom-one")
    preferences.apiKeyFor("custom").delete()
    check("translation_api_key_enc__custom" !in store.getAll())
    check(preferences.snapshot().activeApiKey().isEmpty())
    preferences.apiKey.delete()
    check("translation_api_key_enc" !in store.getAll() && "translation_api_key" !in store.getAll())
    check(preferences.snapshot().apiKey.get().isEmpty())
    check(delegate.getAll().keys.none { it.startsWith("translation_api_key") })
    store.eraseSecrets()
    check(store.getAll().keys.none { it.startsWith("translation_api_key") })
}

private class OnlineReaderFlowFactoryLease(type: Type, instance: Any) : Closeable {
    private val registrar = Injekt.registrar

    @Suppress("UNCHECKED_CAST")
    private val factories = readerField(registrar, "factories") as ConcurrentHashMap<Type, () -> Any>
    private val original = checkNotNull(factories[type])
    private val key = type
    private val replacement: () -> Any = { instance }
    init {
        check(registrar.javaClass.name == "dev.mihon.injekt.PatchedDefaultRegister")
        check(factories.replace(key, original, replacement))
    }
    override fun close() {
        check(factories.replace(key, replacement, original))
    }
}

internal class OnlineReaderFlowPrewarmLease {
    private lateinit var controller: Any
    private var finished = false
    private var everForeground = false
    private var pending: Job? = null
    private var background = false
    init {
        readerMain {
            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            controller = readerField(StartupEnginePrewarm, "controller")
            check(controller.javaClass.name.endsWith(".StartupPrewarmController"))
            pending = readerNullableField(controller, "pending") as Job?
            check(pending?.isActive != true)
            finished = readerField(controller, "finished") as Boolean
            everForeground = readerField(controller, "everForeground") as Boolean
            set("finished", true)
        }
    }
    suspend fun awaitBackground() {
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main.immediate) {
                    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                }
            ) {
                delay(25)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        readerMain {
            check(readerField(controller, "finished") == true)
            check((readerNullableField(controller, "pending") as Job?)?.isActive != true)
            background = true
        }
    }
    fun restore() {
        check(background)
        readerMain {
            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            check(readerField(StartupEnginePrewarm, "controller") === controller)
            check(readerField(controller, "finished") == true)
            check((readerNullableField(controller, "pending") as Job?)?.isActive != true)
            set("pending", pending)
            set("everForeground", everForeground)
            set("finished", finished)
        }
    }
    private fun set(name: String, value: Any?) {
        controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
    }
}

private fun readerJob(owner: Any): Job = checkNotNull(
    (readerField(owner, "scope") as CoroutineScope).coroutineContext[Job],
)
private fun readerField(owner: Any, name: String): Any = checkNotNull(readerNullableField(owner, name))
private fun readerNullableField(
    owner: Any,
    name: String,
): Any? = owner.javaClass.getDeclaredField(name).apply {
    isAccessible =
        true
}.get(owner)
private fun readerMain(block: () -> Unit) {
    var failure: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        try {
            block()
        } catch (error: Throwable) {
            failure =
                error
        }
    }
    failure?.let { throw it }
}
internal fun emitOnlineReaderFlow(key: String, value: JSONObject) {
    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value.toString()) })
}

/** Bounded structural exception evidence; never includes messages, URLs, response text or credentials. */
internal fun onlineReaderFlowFailure(error: Throwable): JSONObject {
    fun frame(value: StackTraceElement) = JSONObject().put("class", value.className)
        .put("method", value.methodName).put("file", value.fileName).put("line", value.lineNumber)
    fun structural(value: Throwable) = JSONObject().put("class", value.javaClass.name)
        .put("frames", JSONArray(value.stackTrace.take(8).map(::frame)))
    val result = structural(error)
    val causes = JSONArray()
    var cause = error.cause
    repeat(3) {
        val current = cause ?: return@repeat
        causes.put(structural(current))
        cause = current.cause.takeUnless { it === current }
    }
    return result.put("causes", causes)
}
