package mihon.translation

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.StartupEnginePrewarm
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStatsStore
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mihon.source.hitomi.HitomiClient
import mihon.source.hitomi.HitomiSource
import mihon.testprovider.HitomiOemPickerActivity
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
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
import java.io.InputStream
import java.lang.reflect.Type
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Opt-in actual capped Hitomi input -> real Downloader -> OEM SAF -> downloaded loader. */
@RunWith(AndroidJUnit4::class)
class HitomiOemDownloaderTest {
    @Test(timeout = 560_000)
    fun twoCappedHitomiPagesPublishToNewOemTreeAndDecode(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") { "ISOLATED_AUDIT_REQUIRED" }
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("hitomiOemDownload") == "true") { "HITOMI_OEM_EXPLICIT_OPT_IN_REQUIRED" }
        val galleryPath = arguments.getString("hitomiOemGalleryPath").orEmpty()
        val discoverArgument = arguments.getString("hitomiOemDiscoverMetadata")
        check(discoverArgument == null || discoverArgument in setOf("true", "false")) { "DISCOVERY_BOOLEAN_REQUIRED" }
        val discoverMetadata = discoverArgument == "true"
        check(galleryPath.isNotEmpty() xor discoverMetadata) { "EXACTLY_ONE_SOURCE_SELECTION_MODE_REQUIRED" }
        check(discoverMetadata || galleryPath.matches(Regex("/g/[1-9][0-9]{0,17}"))) {
            "CANONICAL_RELATIVE_GALLERY_PATH_REQUIRED"
        }
        NativeDiagnosticIdleGuard.requireIdle(target)
        val fixture = HitomiOemFixture(target, galleryPath, discoverMetadata)
        var failure: Throwable? = null
        try {
            fixture.pickTreeAndPrepare()
            fixture.downloadAndRead()
        } catch (error: Throwable) {
            // Do not export exception messages/causes containing source URLs or identities through the runner.
            fixture.reportFailure("operation", error)
            failure = AssertionError("HITOMI_OEM_${safeClass(error)}")
        } finally {
            withContext(NonCancellable) {
                try {
                    fixture.close()
                } catch (cleanup: Throwable) {
                    fixture.reportFailure("cleanup", cleanup)
                    val safe = AssertionError("HITOMI_OEM_CLEANUP_${safeClass(cleanup)}")
                    if (failure == null) failure = safe else failure!!.addSuppressed(safe)
                }
            }
        }
        failure?.let { throw it }
        fixture.report()
    }

    private fun safeClass(error: Throwable): String = when (error) {
        is kotlinx.coroutines.TimeoutCancellationException -> "TIMEOUT"
        is SecurityException -> "PERMISSION_REFUSED"
        is java.io.IOException -> "IO_FAILURE"
        is IllegalStateException -> "GUARD_FAILURE"
        is IllegalArgumentException -> "INPUT_OR_PROVIDER_FAILURE"
        is AssertionError -> "ASSERTION_FAILURE"
        else -> "UNCLASSIFIED_FAILURE"
    }
}

private class HitomiOemFixture(
    private val target: Context,
    private val galleryPath: String,
    private val discoverMetadata: Boolean,
) {
    private val uuid = UUID.randomUUID()
    private val prefix = "hitomi-downloader-$uuid"
    private val privateRootOwner = lazy { File(target.cacheDir.canonicalFile, prefix).apply { check(mkdir()) } }
    private val privateRoot by privateRootOwner
    private val cacheDirectory by lazy { File(privateRoot, "cache").apply { check(mkdir()) } }
    private val filesDirectory by lazy { File(privateRoot, "files").apply { check(mkdir()) } }
    private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
    private val context = object : ContextWrapper(target) {
        override fun getCacheDir() = cacheDirectory
        override fun getFilesDir() = filesDirectory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val owned = "$prefix-$name"
            preferenceNames += owned
            return target.getSharedPreferences(owned, mode)
        }
    }
    private val tree by lazy { HitomiOemTree(target, uuid, File(privateRoot, "owned-tree-ledger.json")) }
    private val pickerSession = HitomiOemPickerActivity.Session(uuid)
    private var pickerInstalled = false
    private var scenario: ActivityScenario<HitomiOemPickerActivity>? = null
    private lateinit var sources: SourceManager
    private val leases = mutableListOf<HitomiOemFactoryLease>()
    private val scopeOwners = mutableListOf<Any>()
    private var registration: OnlineReaderSourceRegistration? = null
    private var manager: DownloadManager? = null
    private var chapterCache: ChapterCache? = null
    private var translations: TranslationManager? = null
    private var engine: TranslationEngineService? = null
    private var stats: TranslationStatsStore? = null
    private var prewarm: HitomiOemPrewarmLease? = null
    private var prewarmGuardInstallationFailed = false
    private var treeAccepted = false
    private var writersQuiescentBeforeSafCleanup = false
    private val selectionEvidence = JSONObject()
        .put("mode", if (discoverMetadata) "boundedJapaneseMetadata" else "explicitRelativePath")
        .put("logicalSearchCalls", 0).put("candidateMetadataCalls", 0)
        .put("sourceJapaneseClassificationVerified", false).put("contentNotVisuallyReviewed", true)
        .put("sourceClassificationIsNotVisualSafetyGuarantee", true)
    private var beforeWork: Map<UUID, WorkInfo.State>? = null
    private var notificationsOwned = false
    private var completed = false
    private var cleaned = false
    private lateinit var source: CappedHitomiSource
    private lateinit var provider: DownloadProvider
    private lateinit var cache: DownloadCache
    private lateinit var manga: Manga
    private lateinit var chapter: Chapter
    private lateinit var queued: Download
    private val pageEvidence = JSONArray()
    private var currentPhase = "constructed"

    suspend fun pickTreeAndPrepare() {
        // No SourceManager, WorkManager, storage/grant I/O or network operation precedes this guard.
        val translationPreferences = TranslationPreferences(InMemoryPreferenceStore()).apply {
            translationMasterEnabled.set(false)
            translationEnabled.set(false)
            liveTranslate.set(false)
            nightReadEnabled.set(false)
        }
        lease(fullType<TranslationPreferences>().type, translationPreferences)
        engine = TranslationEngineService(context).also {
            scopeOwners += it
            lease(fullType<TranslationEngineService>().type, it)
        }
        try {
            prewarm = HitomiOemPrewarmLease()
        } catch (error: Throwable) {
            prewarmGuardInstallationFailed = true
            throw error
        }
        phase("privateGuardInstalled")
        check(privateRoot.isDirectory && cacheDirectory.isDirectory && filesDirectory.isDirectory)
        check(tree.uuid == uuid) // Capture the original grant baseline before discovery or picker activity.
        stats = TranslationStatsStore(context).also { lease(fullType<TranslationStatsStore>().type, it) }
        sources = Injekt.get()
        withTimeout(10_000) { sources.isInitialized.first { it } }
        val hitomi = sources.get(HitomiSource.ID) as? HitomiSource
            ?: error("REGISTERED_HITOMI_REQUIRED_WITHOUT_PREFERENCE_MUTATION")
        val work = WorkManager.getInstance(target).getWorkInfosForUniqueWork("Downloader").get(5, TimeUnit.SECONDS)
        check(work.all { it.state.isFinished }) { "EXISTING_DOWNLOADER_WORK_MUST_BE_IDLE" }
        beforeWork = work.associate { it.id to it.state }
        check(downloadNotifications().isEmpty()) { "EXISTING_DOWNLOAD_NOTIFICATIONS_MUST_BE_ABSENT" }
        notificationsOwned = true
        // Private lifecycle dependencies and prewarm guard also cover discovery's first network request.
        requirePrivateEngineCold()
        val selectedPath = selectPathInDeviceMemory(hitomi)
        requirePrivateEngineCold()
        HitomiOemPickerActivity.install(pickerSession)
        pickerInstalled = true
        scenario = ActivityScenario.launch(
            Intent(target, HitomiOemPickerActivity::class.java)
                .putExtra(HitomiOemPickerActivity.EXTRA_SESSION, uuid.toString()),
        )
        phase("awaitingPicker", JSONObject().put("ownedRootName", prefix).put("timeoutSeconds", 180))
        val pick = withTimeout(180_000) { pickerSession.result.await() }
        tree.accept(pick.uri, pick.flags)
        treeAccepted = true
        phase("exactPersistedGrantAccepted")

        val downloadPreferences = DownloadPreferences(
            AndroidPreferenceStore(context, context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)),
        ).apply {
            saveChaptersAsCBZ.set(false)
            splitTallImages.set(false)
            parallelPageLimit.set(1)
            parallelSourceLimit.set(1)
        }
        check(withTimeout(2_000) { downloadPreferences.parallelSourceLimit.changes().first() } == 1)
        val storagePreferences = StoragePreferences(
            object : FolderProvider {
                override fun directory() = privateRoot
                override fun path() = tree.tree.toString()
            },
            AndroidPreferenceStore(context, context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE)),
        ).apply { baseStorageDirectory.set(tree.tree.toString()) }
        check(storagePreferences.baseStorageDirectory.get() == tree.tree.toString())
        check(withTimeout(2_000) { storagePreferences.baseStorageDirectory.changes().first() } == tree.tree.toString())
        val storage = StorageManager(context, storagePreferences).also(scopeOwners::add)
        val library = LibraryPreferences(InMemoryPreferenceStore())
        source = CappedHitomiSource(hitomi, uuid, selectedPath)
        registration = OnlineReaderSourceRegistration(sources, source)
        provider = DownloadProvider(context, storage, library)
        val id = (uuid.mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(10_000_000L)
        manga = Manga.create().copy(
            id = id,
            source = source.id,
            title = "Neutral audit $uuid",
            url = "/audit/$uuid",
        )
        chapter = Chapter.create().copy(
            id = id xor 0x3000000000000000L,
            mangaId = manga.id,
            name = "Neutral chapter",
            url = "/audit/chapter/$uuid",
            sourceOrder = 1,
        )
        tree.reserveProducts(
            provider.getSourceDirName(source),
            provider.getMangaDirName(manga.title),
            provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url),
        )
        cache = DownloadCache(context, provider, sources, Injekt.get(), storage).also(scopeOwners::add)
        lease(fullType<DownloadPreferences>().type, downloadPreferences)
        lease(fullType<DownloadProvider>().type, provider)
        chapterCache = ChapterCache(context, Injekt.get()).also { lease(fullType<ChapterCache>().type, it) }
        val translationCache = TranslationCache(context, provider, sources, translationPreferences)
            .also(scopeOwners::add)
        lease(fullType<TranslationCache>().type, translationCache)
        translations = TranslationManager(context).also {
            scopeOwners += it
            lease(fullType<TranslationManager>().type, it)
        }
        manager = DownloadManager(
            context,
            provider,
            cache,
            sourceManager = sources,
            downloadPreferences = downloadPreferences,
        ).also { scopeOwners += field(it, "downloader") }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        requirePrivateIdle()
        renew()
        check(cache.getDownloadCount(manga) == 0 && !downloaded(false) && !downloaded(true))
        check(source.pageListCalls == 0 && source.imageUrlCalls == 0)
    }

    private suspend fun selectPathInDeviceMemory(hitomi: HitomiSource): String {
        val path = if (!discoverMetadata) {
            galleryPath
        } else {
            phase("sourceSelectionStarting", JSONObject().put("timeoutSeconds", 60).put("maximumCandidates", 5))
            val api = HitomiClient(hitomi.client) { hitomi.headers }
            withTimeout(60_000) {
                selectionEvidence.put("logicalSearchCalls", 1)
                val candidates = withTimeout(15_000) {
                    api.search("language:japanese", "all", 1)
                }.first.take(5)
                check(candidates.isNotEmpty() && candidates.distinct().size == candidates.size)
                for ((index, id) in candidates.withIndex()) {
                    check(id > 0)
                    selectionEvidence.put("candidateMetadataCalls", index + 1)
                    val gallery = withTimeout(35_000) { api.gallery(id) }
                    check(gallery["id"]?.jsonPrimitive?.content?.toLongOrNull() == id) {
                        "DISCOVERY_RESPONSE_ID_MISMATCH"
                    }
                    val japanese = gallery["language"]?.jsonPrimitive?.content == "japanese"
                    val hasPages = (gallery["files"]?.jsonArray?.size ?: 0) >= 2
                    if (japanese && hasPages) {
                        selectionEvidence.put("sourceJapaneseClassificationVerified", true)
                            .put("selectedResponseIdMatched", true)
                            .put("selectedMetadataHasAtLeastTwoFiles", true)
                        return@withTimeout "/g/$id"
                    }
                }
                error("JAPANESE_METADATA_SAMPLE_NOT_FOUND_WITHIN_BUDGET")
            }
        }
        check(hitomi.getMangaUrl(SManga.create().apply { url = path }) == "${hitomi.baseUrl}$path.html") {
            "SELECTED_CANONICAL_PATH_MISMATCH"
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest("$uuid|$path".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        selectionEvidence.put("sessionSelectionSha256", fingerprint)
        phase("sourceSelected", JSONObject(selectionEvidence.toString()))
        return path
    }

    suspend fun downloadAndRead() {
        phase("downloadStarting")
        val downloads = checkNotNull(manager)
        downloads.downloadChapters(manga, listOf(chapter), autoStart = false)
        queued = downloads.queueState.value.single()
        check(queued.source === source && queued.chapter.id == chapter.id && queued.pages == null)
        check(queued.status == Download.State.QUEUE && !downloads.isRunning)
        check(downloads.downloaderStart()) { "PUBLIC_DOWNLOADER_START_REJECTED" }
        withTimeout(120_000) {
            while (downloads.queueState.value.isNotEmpty() || downloads.isRunning) {
                check(queued.status != Download.State.ERROR) { "ACTUAL_DOWNLOADER_ERROR" }
                delay(50)
            }
        }
        withTimeout(10_000) { scopeJob(field(downloads, "downloader")).children.toList().forEach { it.join() } }
        check(queued.status == Download.State.DOWNLOADED)
        check(source.pageListCalls == 1 && source.imageUrlCalls == 2)
        check(queued.pages?.size == 2 && queued.downloadedImages == 2)
        source.budget.requireClosed()
        val publication = checkNotNull(
            provider.findReadableDownloadedChapterDir(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.title,
                source,
            ),
        )
        check(publication.uri.scheme == "content" && publication.isDirectory)
        check(downloaded(false) && downloaded(true) && cache.getDownloadCount(manga) == 1)
        // OEM path-based document IDs can change when the staging parent is renamed. Resolve publication anew.
        val publishedPages = downloads.buildPageList(source, manga, chapter)
        check(publishedPages.size == 2)
        val stored = publishedPages.map { page ->
            val uri = checkNotNull(page.uri)
            check(uri.scheme == "content")
            checkNotNull(target.contentResolver.openInputStream(uri)).use(::hash)
        }
        val loader = DownloadPageLoader(ReaderChapter(chapter), manga, source, downloads, provider)
        try {
            val pages = loader.getPages()
            check(pages.size == 2)
            for ((index, page) in pages.withIndex()) {
                val stream = checkNotNull(page.stream)
                val digest = stream.invoke().use(::hash)
                check(digest == stored[index] && digest.bytes in 1..HitomiOemRequestBudget.MAX_IMAGE_BYTES)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                stream.invoke().use { BitmapFactory.decodeStream(it, null, bounds) }
                check(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384)
                check(bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000L) { "DECODE_PIXEL_BUDGET" }
                val bitmap = checkNotNull(stream.invoke().use { BitmapFactory.decodeStream(it) })
                try {
                    check(bitmap.width == bounds.outWidth && bitmap.height == bounds.outHeight)
                    pageEvidence.put(
                        JSONObject().put("index", index).put("sha256", digest.sha256).put("bytes", digest.bytes)
                            .put("width", bitmap.width).put("height", bitmap.height).put("androidDecoded", true),
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            loader.recycle()
        }
        renew()
        check(downloaded(false) && downloaded(true) && cache.getDownloadCount(manga) == 1)
        val documents = tree.checkpoint()
        check(documents.none { it.path.endsWith("_tmp") || it.path.endsWith(".tmp") })
        requirePrivateIdle()
        completed = true
        phase("downloadAndLoaderDecodeComplete", JSONObject().put("decodedPages", 2))
    }

    suspend fun close() {
        phase("cleanupStarting")
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit): Boolean =
            try {
                block()
                true
            } catch (error: Throwable) {
                // Do not forward provider paths, input IDs, or transport messages to instrumentation output.
                reportFailure("cleanupStep", error)
                val safe = IllegalStateException("OWNED_CLEANUP_STEP_FAILED")
                if (failure == null) failure = safe else failure!!.addSuppressed(safe)
                false
            }
        attempt { manager?.takeIf { it.isRunning }?.downloaderStop() }
        if (::source.isInitialized) source.budget.cancelOwnedCalls()
        // Keep private engine/prefs and prewarm guard through delayed App.onStop callbacks.
        val hostClosed = attempt { scenario?.close() }
        val backgroundObserved = attempt {
            check(!prewarmGuardInstallationFailed) { "PREWARM_GUARD_INSTALLATION_UNPROVEN" }
            prewarm?.awaitHostBackground()
        }
        var jobsStopped = true
        for (owner in scopeOwners.asReversed()) {
            val stopped = attempt { withTimeout(10_000) { scopeJob(owner).cancelAndJoin() } }
            jobsStopped = jobsStopped && stopped
        }
        val callsClosed = attempt { if (::source.isInitialized) source.budget.requireClosed() }
        if (!hostClosed || !backgroundObserved || !jobsStopped || !callsClosed) {
            // A late callback must never resolve real dependencies after an early DI restore.
            phase(
                "cleanupNeedsAttention",
                JSONObject().put("privateLedgerPreserved", privateLedgerExists())
                    .put("privateStateDirectoryPreserved", privateStateDirectoryExists())
                    .put("ownedDocumentsAbsentAndGrantReleased", false).put("needsAuditProcessStop", true)
                    .put("dependencyLeasesPreserved", true),
            )
            throw IllegalStateException("OWNED_CALLBACK_QUIESCENCE_UNPROVEN")
        }
        writersQuiescentBeforeSafCleanup = true
        attempt {
            engine?.let {
                check(!it.warm.value && !it.loading.value && nullableField(it, "engine") == null)
            }
        }
        attempt { chapterCache?.let { (field(it, "diskCache") as Closeable).close() } }
        attempt { registration?.close() }
        attempt { prewarm?.restore() }
        for (lease in leases.asReversed()) attempt { lease.close() }
        attempt {
            beforeWork?.let { before ->
                val after = WorkManager.getInstance(target).getWorkInfosForUniqueWork("Downloader")
                    .get(5, TimeUnit.SECONDS)
                check(before == after.associate { it.id to it.state }) { "EXISTING_WORK_CHANGED" }
            }
        }
        attempt {
            if (notificationsOwned) {
                val notifications = target.getSystemService(NotificationManager::class.java)
                downloadNotifications().forEach { notifications.cancel(it) }
                check(downloadNotifications().isEmpty())
            }
        }
        // If owned jobs failed to stop, preserve all external documents and the grant for investigation.
        if (failure == null) attempt { tree.cleanup() }
        attempt { if (pickerInstalled) HitomiOemPickerActivity.uninstall(pickerSession) }
        for (name in preferenceNames) {
            attempt {
                check(name.startsWith("$prefix-"))
                check(target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                check(target.deleteSharedPreferences(name))
            }
        }
        attempt { NativeDiagnosticIdleGuard.requireIdle(target) }
        if (tree.released && failure == null) {
            attempt {
                check(privateRoot.parentFile.canonicalFile == target.cacheDir.canonicalFile)
                check(privateRoot.canonicalFile == privateRoot.absoluteFile && privateRoot.name == prefix)
                val entries = privateRoot.walkTopDown().toList()
                check(entries.size < 100 && entries.all { it.canonicalFile == it.absoluteFile })
                check(privateRoot.deleteRecursively() && !privateRoot.exists())
            }
        }
        if (failure != null) {
            phase(
                "cleanupNeedsAttention",
                JSONObject().put("privateLedgerPreserved", privateLedgerExists())
                    .put("privateStateDirectoryPreserved", privateStateDirectoryExists())
                    .put("ownedDocumentsAbsentAndGrantReleased", tree.released),
            )
            throw checkNotNull(failure)
        }
        check(tree.released)
        cleaned = true
        phase(
            "cleaned",
            JSONObject().put("emptyUuidFolderLeftForController", true).put("oemTreeAccepted", treeAccepted)
                .put("ownedWritersQuiescentBeforeSafCleanup", writersQuiescentBeforeSafCleanup)
                .put("strictRootEmptyBeforeExactGrantRelease", tree.strictRootEmptyBeforeExactGrantRelease)
                .put("priorPersistedGrantsEqualAfterRelease", tree.priorPersistedGrantsEqualAfterRelease)
                .put("nativeEngineColdAndPrivateDependenciesRestored", true),
        )
    }

    fun report() {
        check(completed && cleaned)
        val evidence = JSONObject().put("passed", true).put("decodedPages", pageEvidence)
            .put("sourceSelection", selectionEvidence)
            .put("actualDownloaderPublicAdmissionAndStart", true).put("actualOemSafProvider", true)
            .put("exactNewPersistedReadWriteGrant", true).put("preexistingGrantBaselineRestored", true)
            .put("cappedSourceFixture", true).put("uniqueImageLimit", 2).put("pageListDelegateCalls", 1)
            .put("imageUrlDelegateCalls", source.imageUrlCalls).put("wholeGalleryCovered", false)
            .put("derivedImageApplicationAttempts", source.budget.applicationAttempts.get())
            .put("derivedImageNetworkExchanges", source.budget.networkExchanges.get())
            .put("derivedImageBodyBytesRead", source.budget.bytesRead.get())
            .put("originalHitomiMetadataTransportRequestCountObserved", false)
            .put("sourceRoutingDnsTlsInterceptorsRetained", true).put("cacheCountAfterRescan", 1)
            .put("privateTranslationQueueEmptyAndEngineCold", true).put("privateStatisticsEmpty", true)
            .put("hostLifecyclePrewarmGuardRestored", true)
            .put("ownedWritersQuiescentBeforeSafCleanup", writersQuiescentBeforeSafCleanup)
            .put("strictRootEmptyBeforeExactGrantRelease", tree.strictRootEmptyBeforeExactGrantRelease)
            .put("priorPersistedGrantsEqualAfterRelease", tree.priorPersistedGrantsEqualAfterRelease)
            .put("ownedDocumentsAbsentAndNewGrantReleased", true).put("emptyUuidFolderPreserved", true)
            .put("readerActivityOrWorkerOrNativeInferenceCovered", false)
            .put("cbzOrProcessRestartPermissionCovered", false).put("allWebViewStateIsolated", false)
        emit("hitomiOemDownloaderEvidence", evidence)
    }

    /** Structural diagnostics only: never include messages, causes, response text, URLs, or input values. */
    fun reportFailure(kind: String, error: Throwable) {
        runCatching {
            check(kind in setOf("operation", "cleanup", "cleanupStep"))
            val ownFiles = setOf(
                "HitomiOemDownloaderTest.kt",
                "HitomiOemTree.kt",
                "HitomiOemRequestBudget.kt",
                "HitomiOemPickerActivity.kt",
            )
            val ownFrame = error.stackTrace.firstOrNull {
                it.fileName in ownFiles &&
                    (
                        it.className.startsWith("mihon.translation.") ||
                            it.className.startsWith("mihon.testprovider.HitomiOem")
                        )
            }
            val frame = ownFrame?.let {
                JSONObject().put("class", it.className).put("method", it.methodName)
                    .put("file", it.fileName).put("line", it.lineNumber)
            } ?: JSONObject.NULL
            val safe = JSONObject().put("sessionUuid", uuid.toString()).put("currentPhase", currentPhase)
                .put("failureKind", kind).put("originalFailureClass", error.javaClass.name)
                .put("firstOwnFixtureFrame", frame)
                .put("selectionLogicalSearchCalls", selectionEvidence.getInt("logicalSearchCalls"))
                .put("selectionCandidateMetadataCalls", selectionEvidence.getInt("candidateMetadataCalls"))
            if (::source.isInitialized) safe.put("imageNetworkExchanges", source.budget.networkExchanges.get())
            if (::queued.isInitialized) safe.put("selectedDownloadState", queued.status.name)
            emit("hitomiOemDownloaderFailure", safe)
        }
    }

    private fun requirePrivateIdle() {
        check(checkNotNull(translations).queueState.value.isEmpty())
        requirePrivateEngineCold()
        check(checkNotNull(stats).allDays().isEmpty())
    }

    private fun requirePrivateEngineCold() {
        val privateEngine = checkNotNull(engine)
        check(!privateEngine.warm.value && !privateEngine.loading.value)
        check(nullableField(privateEngine, "engine") == null)
    }

    private fun downloaded(skipCache: Boolean) = cache.isChapterDownloaded(
        chapter.name,
        chapter.scanlator,
        chapter.url,
        manga.title,
        source.id,
        skipCache,
    )

    private suspend fun renew() {
        cache.invalidateCache()
        val job = field(cache, "renewalJob") as Job
        withTimeout(10_000) { job.join() }
        check(!job.isCancelled)
    }

    private fun lease(type: Type, instance: Any) {
        leases += HitomiOemFactoryLease(type, instance)
    }

    private fun privateStateDirectoryExists() = privateRootOwner.isInitialized() && privateRoot.exists()

    private fun privateLedgerExists() =
        privateRootOwner.isInitialized() && File(privateRoot, "owned-tree-ledger.json").isFile

    private fun phase(value: String, extra: JSONObject = JSONObject()) {
        currentPhase = value
        emit("hitomiOemDownloaderProgress", extra.put("phase", value).put("sessionUuid", uuid.toString()))
    }

    private fun downloadNotifications() = target.getSystemService(NotificationManager::class.java)
        .activeNotifications.map { it.id }.filterTo(mutableSetOf()) {
            it == Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS || it == Notifications.ID_DOWNLOAD_CHAPTER_ERROR
        }

    private fun hash(input: InputStream): Digest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            check(bytes <= HitomiOemRequestBudget.MAX_IMAGE_BYTES)
            digest.update(buffer, 0, count)
        }
        return Digest(bytes, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private data class Digest(val bytes: Long, val sha256: String)
}

/** Internal URLs stay in memory; neutral objects and ComicInfo never receive the gallery identity. */
private class CappedHitomiSource(
    private val hitomi: HitomiSource,
    uuid: UUID,
    private val galleryPath: String,
) : HttpSource() {
    override val name = "Neutral audit $uuid"
    override val lang = "en"
    override val supportsLatest = false
    override val baseUrl = hitomi.baseUrl
    val budget = HitomiOemRequestBudget()
    override val client: OkHttpClient = hitomi.client.newBuilder()
        .cache(null)
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor(budget.application)
        .addNetworkInterceptor(budget.network)
        .build()
    var pageListCalls = 0
        private set
    var imageUrlCalls = 0
        private set
    private var selected: Set<String> = emptySet()

    override fun headersBuilder(): Headers.Builder = hitomi.headers.newBuilder()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        check(++pageListCalls == 1) { "ONE_GALLERY_DELEGATION_REQUIRED" }
        val original = SChapter.create().apply { url = "/reader/${galleryPath.substringAfterLast('/')}.html" }
        val pages = withTimeout(15_000) { hitomi.getPageList(original) }.take(2)
        check(pages.size == 2 && pages.map { it.url }.distinct().size == 2) { "TWO_UNIQUE_PAGES_REQUIRED" }
        selected = pages.map { it.url }.toSet()
        return pages
    }

    override suspend fun getImageUrl(page: Page): String {
        check(page.url in selected && ++imageUrlCalls <= 2) { "IMAGE_RESOLUTION_BUDGET" }
        return withTimeout(15_000) { hitomi.getImageUrl(page) }
    }

    override fun getMangaUrl(manga: SManga) = "https://example.invalid/neutral-audit"
    override fun getChapterUrl(chapter: SChapter) = "https://example.invalid/neutral-audit"
    override suspend fun getPopularManga(page: Int): MangasPage = error("UNSUPPORTED_AUDIT_OPERATION")
    override suspend fun getLatestUpdates(page: Int): MangasPage = error("UNSUPPORTED_AUDIT_OPERATION")
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        error("UNSUPPORTED_AUDIT_OPERATION")
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = error("UNSUPPORTED_AUDIT_OPERATION")
}

/** Retain and restore only an exact typed factory; do not replace the singleton cache or registrar. */
private class HitomiOemFactoryLease(type: Type, instance: Any) : Closeable {
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
        check(factories[key] === replacement)
    }

    override fun close() {
        check(factories.replace(key, replacement, original)) { "DEPENDENCY_LEASE_OWNER_CHANGED" }
        check(factories[key] === original)
    }
}

/** Freeze only the pinned prewarm decision while retaining its exact original state and lazy environment. */
private class HitomiOemPrewarmLease {
    private lateinit var controller: Any
    private var originalFinished = false
    private var originalEverForeground = false
    private var originalPending: Job? = null
    private var backgroundObserved = false

    init {
        onMain {
            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                "AUDIT_PROCESS_MUST_START_IN_BACKGROUND"
            }
            controller = field(StartupEnginePrewarm, "controller")
            check(controller.javaClass.name.endsWith(".StartupPrewarmController"))
            originalPending = nullableField(controller, "pending") as Job?
            check(originalPending?.isActive != true) { "EXISTING_PREWARM_JOB_MUST_BE_IDLE" }
            originalFinished = field(controller, "finished") as Boolean
            originalEverForeground = field(controller, "everForeground") as Boolean
            set("finished", true)
            check(field(controller, "finished") == true)
        }
    }

    suspend fun awaitHostBackground() {
        withTimeout(5_000) {
            while (withContext(Dispatchers.Main.immediate) {
                    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                }
            ) {
                delay(25)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain {
            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            check(field(controller, "finished") == true)
            check((nullableField(controller, "pending") as Job?)?.isActive != true)
            backgroundObserved = true
        }
    }

    fun restore() {
        check(backgroundObserved) { "HOST_BACKGROUND_CALLBACKS_MUST_SETTLE_BEFORE_PREWARM_RESTORE" }
        onMain {
            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            check(field(StartupEnginePrewarm, "controller") === controller)
            check(field(controller, "finished") == true)
            check((nullableField(controller, "pending") as Job?)?.isActive != true)
            set("pending", originalPending)
            set("everForeground", originalEverForeground)
            set("finished", originalFinished)
            check(nullableField(controller, "pending") === originalPending)
            check(field(controller, "everForeground") == originalEverForeground)
            check(field(controller, "finished") == originalFinished)
        }
    }

    private fun set(name: String, value: Any?) {
        controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }
}

private fun scopeJob(owner: Any): Job = checkNotNull(
    (field(owner, "scope") as CoroutineScope).coroutineContext[Job],
)

private fun field(owner: Any, name: String): Any = checkNotNull(nullableField(owner, name))
private fun nullableField(owner: Any, name: String): Any? =
    owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

private fun emit(key: String, value: JSONObject) {
    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value.toString()) })
}
