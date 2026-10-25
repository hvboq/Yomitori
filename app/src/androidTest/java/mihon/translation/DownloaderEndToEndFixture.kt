package mihon.translation

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStatsStore
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import mihon.testprovider.SafAliasDocumentsProvider
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.lang.reflect.Type
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class DownloaderEndToEndFixture(
    private val target: Context,
    private val archive: Boolean,
    private val cachedFirstPage: Boolean = false,
) {
    init {
        check(!cachedFirstPage || !archive)
    }

    private val uuid = UUID.randomUUID()
    private val prefix = "downloader-e2e-$uuid"
    private val root = File(target.cacheDir.canonicalFile, prefix).apply { check(mkdir()) }
    private val cacheDirectory = File(root, "cache").apply { check(mkdir()) }
    private val filesDirectory = File(root, "files").apply { check(mkdir()) }
    private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
    private val context = object : ContextWrapper(target) {
        override fun getCacheDir(): File = cacheDirectory
        override fun getFilesDir(): File = filesDirectory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            preferenceNames += isolated
            return target.getSharedPreferences(isolated, mode)
        }
    }
    private val server = DownloaderNeutralServer(listOf(encoded(Color.WHITE), encoded(Color.LTGRAY)))
    private val sources: SourceManager = Injekt.get()
    private val preferences = TranslationPreferences(InMemoryPreferenceStore()).apply {
        translationMasterEnabled.set(false)
        translationEnabled.set(false)
        liveTranslate.set(false)
        nightReadEnabled.set(false)
    }
    private val downloadPreferences = DownloadPreferences(
        AndroidPreferenceStore(context, context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)),
    ).apply {
        saveChaptersAsCBZ.set(archive)
        splitTallImages.set(false)
        parallelPageLimit.set(2)
        parallelSourceLimit.set(1)
    }
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val leases = mutableListOf<DownloaderFactoryLease>()
    private val scopeOwners = mutableListOf<Any>()
    private var registration: OnlineReaderSourceRegistration? = null
    private var session: SafAliasDocumentsProvider.Session? = null
    private var chapterCache: ChapterCache? = null
    private var cachedDescriptorProbe: CachedDownloadDescriptorProbe? = null
    private var manager: DownloadManager? = null
    private var privateTranslations: TranslationManager? = null
    private var privateEngine: TranslationEngineService? = null
    private var privateStats: TranslationStatsStore? = null
    private var beforeWork: Map<UUID, WorkInfo.State>? = null
    private var notificationsWereAbsent = false
    private var downloadStarted = false
    private var completed = false
    private var cleaned = false
    private var providerContractVerified = false
    private lateinit var provider: DownloadProvider
    private lateinit var cache: DownloadCache
    private lateinit var manga: Manga
    private lateinit var chapter: Chapter
    private lateinit var canonical: File
    private lateinit var mangaDirectory: File
    private lateinit var metadataBefore: Map<String, String>
    private lateinit var queued: Download

    suspend fun prepare() {
        val work = WorkManager.getInstance(target).getWorkInfosForUniqueWork("Downloader").get(5, TimeUnit.SECONDS)
        check(work.all { it.state.isFinished }) { "Existing downloader work must be finished" }
        beforeWork = work.associate { it.id to it.state }
        check(downloadNotifications().isEmpty()) { "Existing download notifications must remain untouched" }
        notificationsWereAbsent = true
        assertEquals(
            "DOWNLOADER_E2E_SOURCE_LIMIT_EMITS_INITIAL_VALUE",
            1,
            withTimeout(2_000) { downloadPreferences.parallelSourceLimit.changes().first() },
        )
        assertEquals("DOWNLOADER_E2E_PAGE_LIMIT", 2, downloadPreferences.parallelPageLimit.get())
        withTimeout(10_000) { sources.isInitialized.first { it } }
        registration = OnlineReaderSourceRegistration(sources, server.source)
        session = SafAliasDocumentsProvider.createSession(target, recursiveDelete = true)
        val ownedSession = requireNotNull(session)
        val tree = DocumentsContract.buildTreeDocumentUri(
            "${target.packageName}.safalias",
            "${ownedSession.id}|root-a",
        )
        verifyProviderDeleteContract(ownedSession, tree)
        val folder = object : FolderProvider {
            override fun directory(): File = ownedSession.directory
            override fun path(): String = tree.toString()
        }
        val storage = StorageManager(context, StoragePreferences(folder, InMemoryPreferenceStore()))
            .also(scopeOwners::add)
        provider = DownloadProvider(context, storage, libraryPreferences)
        cache = DownloadCache(context, provider, sources, Injekt.get(), storage).also(scopeOwners::add)
        lease(fullType<DownloadPreferences>().type, downloadPreferences)
        lease(fullType<DownloadProvider>().type, provider)
        lease(fullType<TranslationPreferences>().type, preferences)
        chapterCache = ChapterCache(context, Injekt.get()).also { lease(fullType<ChapterCache>().type, it) }
        privateStats = TranslationStatsStore(context).also { lease(fullType<TranslationStatsStore>().type, it) }
        privateEngine = TranslationEngineService(context).also {
            scopeOwners += it
            lease(fullType<TranslationEngineService>().type, it)
        }
        val translationsCache = TranslationCache(context, provider, sources, preferences).also(scopeOwners::add)
        lease(fullType<TranslationCache>().type, translationsCache)
        privateTranslations = TranslationManager(context).also {
            scopeOwners += it
            lease(fullType<TranslationManager>().type, it)
        }
        manager = DownloadManager(
            context,
            provider,
            cache,
            sourceManager = sources,
            downloadPreferences = downloadPreferences,
        )
            .also { scopeOwners += field(it, "downloader") }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val id = (uuid.mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(10_000_000L)
        manga = Manga.create().copy(
            id = id,
            source = server.source.id,
            title = "Neutral downloader $uuid",
            url = "/neutral/$uuid",
        )
        chapter = Chapter.create().copy(
            id = id xor 0x3000000000000000L,
            mangaId = manga.id,
            name = "Neutral chapter",
            url = "/chapter",
            sourceOrder = 1,
        )
        mangaDirectory = File(
            ownedSession.directory,
            "downloads/${provider.getSourceDirName(server.source)}/${provider.getMangaDirName(manga.title)}",
        ).apply { check(mkdirs()) }
        val name = provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url)
        canonical = File(mangaDirectory, name).apply { check(mkdir()) }
        File(canonical, ".nomedia").writeBytes(byteArrayOf())
        File(canonical, "ComicInfo").writeBytes(METADATA)
        if (archive) {
            ZipOutputStream(File(mangaDirectory, "$name.cbz").outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                zip.write(METADATA)
                zip.closeEntry()
            }
        }
        metadataBefore = protectedMetadata()
        renew()
        assertFalse("DOWNLOADER_E2E_METADATA_NOT_DOWNLOADED", downloaded(skipCache = false))
        assertFalse("DOWNLOADER_E2E_METADATA_FS_NOT_DOWNLOADED", downloaded(skipCache = true))
        assertEquals("DOWNLOADER_E2E_METADATA_COUNT_ZERO", 0, cache.getDownloadCount(manga))
        assertEquals("DOWNLOADER_E2E_NO_PREPARATION_HTTP", emptyList<String>(), server.requests.toList())
        if (cachedFirstPage) prepareCachedFirstPage(ownedSession)
    }

    suspend fun downloadAndRead() {
        val downloads = requireNotNull(manager)
        downloads.downloadChapters(manga, listOf(chapter), autoStart = false)
        queued = downloads.queueState.value.single()
        assertEquals("DOWNLOADER_E2E_PUBLIC_QUEUE", chapter.id, queued.chapter.id)
        assertEquals("DOWNLOADER_E2E_QUEUED_STATE", Download.State.QUEUE, queued.status)
        assertFalse("DOWNLOADER_E2E_NOT_STARTED_AT_ADMISSION", downloads.isRunning)
        assertEquals("DOWNLOADER_E2E_ADMISSION_HTTP_ZERO", emptyList<String>(), server.requests.toList())
        assertTrue("DOWNLOADER_E2E_PUBLIC_START", downloads.downloaderStart())
        downloadStarted = true
        try {
            withTimeout(25_000) {
                while (downloads.queueState.value.isNotEmpty() || downloads.isRunning) {
                    check(queued.status != Download.State.ERROR) { "Real downloader failed before publication" }
                    delay(20)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            try {
                timeoutDiagnostic(downloads)
            } catch (diagnostic: Exception) {
                timeout.addSuppressed(diagnostic)
            }
            throw timeout
        }
        // stop() clears its current job handle before the job's last finally blocks settle.
        val downloader = field(downloads, "downloader")
        withTimeout(10_000) { scopeJob(downloader).children.toList().forEach { it.join() } }
        assertEquals("DOWNLOADER_E2E_COMPLETED_STATE", Download.State.DOWNLOADED, queued.status)
        assertEquals(
            "DOWNLOADER_E2E_REAL_HTTP_PATHS",
            buildMap {
                put("/chapter", 1)
                if (!cachedFirstPage) put("/image/1.png", 1)
                put("/image/2.png", 1)
            },
            server.requests.groupingBy { it }.eachCount(),
        )
        assertEquals("DOWNLOADER_E2E_SERVER_HEALTH", null, server.failure)
        assertEquals("DOWNLOADER_E2E_PRESERVED_METADATA", metadataBefore, protectedMetadata())
        val expectedName = canonical.name + if (archive) "_dl.cbz" else "_dl"
        val readable = requireNotNull(
            provider.findReadableDownloadedChapterDir(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.title,
                server.source,
            ),
        )
        val found = requireNotNull(
            provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, server.source),
        )
        assertEquals("DOWNLOADER_E2E_ALIAS_NAME", expectedName, readable.name)
        assertEquals("DOWNLOADER_E2E_PROVIDER_SAME_PUBLISHED_URI", readable.uri, found.uri)
        assertEquals("DOWNLOADER_E2E_SAF_SCHEME", "content", readable.uri.scheme)
        assertEquals("DOWNLOADER_E2E_EXPECTED_CONTAINER_KIND", archive, readable.isFile)
        assertTrue("DOWNLOADER_E2E_CACHE_COMPLETED", downloaded(skipCache = false))
        assertTrue("DOWNLOADER_E2E_FILESYSTEM_COMPLETED", downloaded(skipCache = true))
        assertEquals("DOWNLOADER_E2E_ONE_CHAPTER", 1, cache.getDownloadCount(manga))
        val loader = DownloadPageLoader(ReaderChapter(chapter), manga, server.source, downloads, provider)
        try {
            val pages = loader.getPages()
            assertEquals("DOWNLOADER_E2E_READER_PAGE_COUNT", 2, pages.size)
            for ((index, page) in pages.withIndex()) {
                val actual = requireNotNull(page.stream).invoke().use { it.readBytes() }
                assertArrayEquals("DOWNLOADER_E2E_READER_EXACT_PAGE_$index", server.pages[index], actual)
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(actual, 0, actual.size))
                try {
                    assertEquals("DOWNLOADER_E2E_DECODE_WIDTH", 32, bitmap.width)
                    assertEquals("DOWNLOADER_E2E_DECODE_HEIGHT", 40, bitmap.height)
                    assertEquals(
                        "DOWNLOADER_E2E_DECODE_COLOR",
                        if (index == 0) Color.WHITE else Color.LTGRAY,
                        bitmap.getPixel(16, 20),
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            loader.recycle()
        }
        renew()
        assertTrue("DOWNLOADER_E2E_RESCAN_CACHE_COMPLETED", downloaded(skipCache = false))
        assertEquals("DOWNLOADER_E2E_RESCAN_COUNT", 1, cache.getDownloadCount(manga))
        assertEquals("DOWNLOADER_E2E_METADATA_AFTER_READER", metadataBefore, protectedMetadata())
        assertFalse("DOWNLOADER_E2E_STAGING_REMOVED", File(mangaDirectory, canonical.name + "_tmp").exists())
        assertFalse(
            "DOWNLOADER_E2E_RANDOM_ARCHIVE_STAGING_REMOVED",
            mangaDirectory.listFiles().orEmpty().any { it.name.startsWith(".yomitori-download-") },
        )
        assertTrue(
            "DOWNLOADER_E2E_PRIVATE_TRANSLATION_QUEUE_EMPTY",
            requireNotNull(privateTranslations).queueState.value.isEmpty(),
        )
        assertFalse("DOWNLOADER_E2E_NO_NATIVE_ENGINE_WARM", requireNotNull(privateEngine).warm.value)
        assertEquals("DOWNLOADER_E2E_NO_NATIVE_ENGINE", null, nullableField(requireNotNull(privateEngine), "engine"))
        assertEquals("DOWNLOADER_E2E_NO_TRANSLATION_STATS", emptyList<Any>(), requireNotNull(privateStats).allDays())
        cachedDescriptorProbe?.verifyClosedBeforeRename()
        completed = true
    }

    suspend fun close() {
        if (downloadStarted && manager?.isRunning == true) manager?.downloaderStop()
        // Never restore DI while an owned callback could still capture an unrelated production object.
        for (owner in scopeOwners.asReversed()) withTimeout(10_000) { scopeJob(owner).cancelAndJoin() }
        cachedDescriptorProbe?.close()
        chapterCache?.let { (field(it, "diskCache") as Closeable).close() }
        server.close()
        registration?.close()
        leases.asReversed().forEach { it.close() }
        beforeWork?.let {
            val after = WorkManager.getInstance(target).getWorkInfosForUniqueWork("Downloader").get(5, TimeUnit.SECONDS)
            assertEquals("DOWNLOADER_E2E_WORKER_NOT_SCHEDULED", it, after.associate { info -> info.id to info.state })
        }
        // IDs were absent before this exclusive fixture. Cancel only its download notifications.
        if (notificationsWereAbsent) {
            val notifications = target.getSystemService(NotificationManager::class.java)
            downloadNotifications().forEach { notifications.cancel(it) }
            assertTrue("DOWNLOADER_E2E_NO_DOWNLOAD_NOTIFICATION_LEFT", downloadNotifications().isEmpty())
        }
        for (name in preferenceNames) {
            check(name.startsWith("$prefix-"))
            check(target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
            check(target.deleteSharedPreferences(name))
        }
        session?.close()
        check(root.canonicalFile == root.absoluteFile && root.parentFile.canonicalFile == target.cacheDir.canonicalFile)
        check(root.name == prefix && root.name.startsWith("downloader-e2e-"))
        val entries = root.walkTopDown().toList()
        check(entries.size <= 100 && entries.all { it.canonicalFile == it.absoluteFile })
        check(root.deleteRecursively() && !root.exists())
        NativeDiagnosticIdleGuard.requireIdle(target)
        cleaned = true
    }

    fun report(method: String) {
        check(completed && cleaned && providerContractVerified)
        val evidence = JSONObject()
            .put("testMethod", method).put("format", if (archive) "cbz" else "loose").put("passed", true)
            .put("actualPublicQueueAndDownloaderStart", true).put("actualSafPublication", true)
            .put("realLoopbackGetRequests", server.requests.size).put("decodedReaderPages", 2)
            .put("readerPagesByteExact", true).put("cacheCountAfterPublicationAndRescan", 1)
            .put("canonicalMetadataBytesPreserved", true).put("publishedReadableAlias", true)
            .put("ownJobsDependenciesSourcePreferencesAndFilesCleaned", true)
            .put("privateDebugSafProvider", true).put("oemSafOrPersistedGrantCovered", false)
            .put("providerContractVerified", providerContractVerified)
            .put("workerSchedulingCovered", false).put("liveHitomiOrLlmOrNativeModelInferenceInvoked", false)
        if (cachedFirstPage) {
            evidence.put("cachedPageDescriptorEvidence", requireNotNull(cachedDescriptorProbe).evidence())
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("downloaderEndToEndEvidence", evidence.toString())
            },
        )
    }

    private fun prepareCachedFirstPage(ownedSession: SafAliasDocumentsProvider.Session) {
        val imageUrl = server.source.baseUrl + "/image/1.png"
        val privateCache = requireNotNull(chapterCache)
        Response.Builder()
            .request(Request.Builder().url(imageUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(server.pages.first().toResponseBody("image/png".toMediaType()))
            .build()
            .use { privateCache.putImageToCache(imageUrl, it) }
        assertTrue("CACHED_DOWNLOAD_FD_REAL_CACHE_ENTRY", privateCache.isImageInCache(imageUrl))
        val file = privateCache.getImageFile(imageUrl).canonicalFile
        assertArrayEquals("CACHED_DOWNLOAD_FD_CACHE_BYTES", server.pages.first(), file.readBytes())
        cachedDescriptorProbe = CachedDownloadDescriptorProbe(
            ownedSession,
            File(cacheDirectory, "chapter_disk_cache"),
            file,
            File(mangaDirectory, canonical.name + "_tmp/001.tmp"),
        ).also { it.prepare() }
    }

    private fun lease(type: Type, value: Any) {
        leases += DownloaderFactoryLease(type, value)
    }

    private fun verifyProviderDeleteContract(session: SafAliasDocumentsProvider.Session, tree: android.net.Uri) {
        val selected = session.checked(File(session.directory, "delete-contract")).apply { check(mkdir()) }
        val nested = session.checked(File(selected, "nested")).apply { check(mkdir()) }
        session.checked(File(selected, "first.bin")).writeBytes(byteArrayOf(1, 2, 3))
        session.checked(File(nested, "second.bin")).writeBytes(byteArrayOf(4, 5, 6))
        val sibling = session.checked(File(session.directory, "delete-contract-sibling.bin"))
        val preserved = byteArrayOf(7, 8, 9)
        sibling.writeBytes(preserved)
        val before = sha(sibling.readBytes())
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, session.documentId(selected))
        assertTrue(
            "DOWNLOADER_E2E_PROVIDER_DELETES_NONEMPTY_SUBTREE",
            DocumentsContract.deleteDocument(context.contentResolver, document),
        )
        assertFalse("DOWNLOADER_E2E_PROVIDER_DELETED_SUBTREE_ABSENT", selected.exists())
        assertEquals("DOWNLOADER_E2E_PROVIDER_SIBLING_SHA", before, sha(sibling.readBytes()))
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, "${session.id}|root-a")
        val rootDeleted = try {
            DocumentsContract.deleteDocument(context.contentResolver, rootDocument)
        } catch (_: Exception) {
            false
        }
        assertFalse("DOWNLOADER_E2E_PROVIDER_ROOT_REFUSED", rootDeleted)
        assertTrue("DOWNLOADER_E2E_PROVIDER_ROOT_PRESERVED", session.directory.isDirectory)
        assertArrayEquals("DOWNLOADER_E2E_PROVIDER_SIBLING_PRESERVED", preserved, sibling.readBytes())
        val siblingDocument = DocumentsContract.buildDocumentUriUsingTree(tree, session.documentId(sibling))
        assertTrue(
            "DOWNLOADER_E2E_PROVIDER_DELETES_REGULAR_FILE",
            DocumentsContract.deleteDocument(context.contentResolver, siblingDocument),
        )
        assertFalse("DOWNLOADER_E2E_PROVIDER_SIBLING_CLEANED", sibling.exists())
        providerContractVerified = true
    }

    private fun timeoutDiagnostic(downloads: DownloadManager) {
        val jobs = scopeJob(field(downloads, "downloader")).children.toList()
        val evidence = JSONObject()
            .put("stage", "completionTimeout")
            .put("format", if (archive) "cbz" else "loose")
            .put("queueStates", JSONArray(downloads.queueState.value.map { it.status.name }))
            .put("selectedStatus", queued.status.name)
            .put("isRunning", downloads.isRunning)
            .put("scopeChildren", jobs.size)
            .put("activeScopeChildren", jobs.count { it.isActive })
            .put("completedScopeChildren", jobs.count { it.isCompleted })
            .put("requestCount", server.requests.size)
            .put(
                "neutralRequestCounts",
                JSONObject(server.requests.groupingBy { it }.eachCount()),
            )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("downloaderEndToEndDiagnostic", evidence.toString())
            },
        )
    }

    private fun downloaded(skipCache: Boolean) = cache.isChapterDownloaded(
        chapter.name,
        chapter.scanlator,
        chapter.url,
        manga.title,
        server.source.id,
        skipCache,
    )

    private suspend fun renew() {
        cache.invalidateCache()
        val job = field(cache, "renewalJob") as Job
        withTimeout(10_000) { job.join() }
        check(!job.isCancelled)
    }

    private fun protectedMetadata(): Map<String, String> = buildMap {
        for (file in canonical.listFiles().orEmpty()) {
            check(file.isFile)
            put("${canonical.name}/${file.name}", sha(file.readBytes()))
        }
        if (archive) {
            val file = File(mangaDirectory, canonical.name + ".cbz")
            put(file.name, sha(file.readBytes()))
        }
    }

    private fun downloadNotifications(): Set<Int> = target.getSystemService(NotificationManager::class.java)
        .activeNotifications.map { it.id }.filterTo(mutableSetOf()) {
            it == Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS || it == Notifications.ID_DOWNLOAD_CHAPTER_ERROR
        }

    private fun scopeJob(owner: Any): Job = requireNotNull(
        (field(owner, "scope") as CoroutineScope).coroutineContext[Job],
    )

    private companion object {
        val METADATA = "<?xml version=\"1.0\"?><ComicInfo><Title>Neutral metadata</Title></ComicInfo>".toByteArray()

        fun field(owner: Any, name: String): Any = requireNotNull(
            nullableField(owner, name),
        )

        fun nullableField(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

        fun encoded(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 40, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}

/** Return one private object while retaining the exact original singleton factory and lazy cache. */
private class DownloaderFactoryLease(type: Type, private val instance: Any) : Closeable {
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
        assertSame("DOWNLOADER_E2E_SCOPED_FACTORY", replacement, factories[key])
    }

    override fun close() {
        check(factories.replace(key, replacement, original)) {
            "Owned dependency entry changed; refusing to overwrite another owner"
        }
        assertSame("DOWNLOADER_E2E_FACTORY_RESTORED", original, factories[key])
    }
}

/** Bounded real HTTP input, owned client, no internet, native engine or translation route. */
private class DownloaderNeutralServer(val pages: List<ByteArray>) : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(2)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val client = OkHttpClient.Builder().cache(null).build()
    val requests = CopyOnWriteArrayList<String>()

    @Volatile private var closed = false

    @Volatile var failure: Throwable? = null
        private set
    private val baseUrl = "http://127.0.0.1:${socket.localPort}"
    val source = object : HttpSource() {
        override val name = "Neutral downloader ${UUID.randomUUID()}"
        override val lang = "ja"
        override val baseUrl = this@DownloaderNeutralServer.baseUrl
        override val supportsLatest = false
        override val client: OkHttpClient = this@DownloaderNeutralServer.client
        override fun headersBuilder() = Headers.Builder().add("User-Agent", "Yomitori neutral downloader regression")
        override fun pageListParse(response: Response): List<Page> {
            val paths = JSONArray(response.body.string())
            check(paths.length() == 2)
            return (0 until paths.length()).map { i ->
                val url = baseUrl + paths.getString(i)
                Page(i, url, url)
            }
        }
    }
    private val acceptor = Thread({
        try {
            while (!closed) {
                val connection = socket.accept()
                clients += connection
                workers.execute { serve(connection) }
            }
        } catch (error: Exception) {
            if (!closed) failure = error
        }
    }, "downloader-e2e-http").apply {
        isDaemon = true
        start()
    }

    private fun serve(connection: Socket) {
        try {
            connection.use {
                it.soTimeout = 5_000
                val input = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                val request = requireNotNull(input.readLine()).also { line -> check(line.length <= 4096) }.split(' ')
                check(request.size == 3 && request[0] == "GET")
                var count = 0
                while (true) {
                    val line = requireNotNull(input.readLine())
                    if (line.isEmpty()) break
                    check(++count <= 64 && line.length <= 8192)
                }
                val path = request[1]
                check(path in listOf("/chapter", "/image/1.png", "/image/2.png"))
                requests += path
                val body = if (path == "/chapter") {
                    JSONArray(listOf("/image/1.png", "/image/2.png")).toString().toByteArray()
                } else {
                    pages[if (path == "/image/1.png") 0 else 1]
                }
                val contentType = if (path == "/chapter") "application/json" else "image/png"
                it.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n" +
                                "Content-Length: ${body.size}\r\nCache-Control: no-store\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    write(body)
                    flush()
                }
            }
        } catch (error: Exception) {
            if (!closed) failure = error
        } finally {
            clients -= connection
        }
    }

    override fun close() {
        closed = true
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        socket.close()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        acceptor.join(3_000)
        check(!acceptor.isAlive && workers.awaitTermination(3, TimeUnit.SECONDS))
        check(client.dispatcher.executorService.awaitTermination(3, TimeUnit.SECONDS))
        check(clients.isEmpty() && client.connectionPool.connectionCount() == 0)
    }
}

private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
