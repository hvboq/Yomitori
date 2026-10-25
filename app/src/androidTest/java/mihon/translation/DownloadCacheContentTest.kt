package mihon.translation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Public download/cache/reader entry points, private files, and no OCR or remote source calls. */
@RunWith(AndroidJUnit4::class)
class DownloadCacheContentTest {
    @Test(timeout = 40_000)
    fun emptyAndMetadataOnlyDirectoriesAreNotDownloaded(): Unit = runBlocking {
        withFixture { f ->
            val empty = f.chapter(1)
            val metadata = f.chapter(2)
            f.directory(empty)
            f.metadata(f.directory(metadata))
            val before = f.storageSnapshot()
            val cache = f.cache()
            f.renew(cache)
            val actual = listOf(f.status(cache, empty), f.status(cache, metadata), cache.getDownloadCount(f.manga))
            f.assertStorageUnchanged(before)
            assertEquals("DL_CONTENT_EMPTY_METADATA", listOf(listOf(false, false), listOf(false, false), 0), actual)
        }
    }

    @Test(timeout = 40_000)
    fun preservedOriginalAloneIsNotADownloadedDisplayPage(): Unit = runBlocking {
        withFixture { f ->
            val chapter = f.chapter(1)
            val directory = f.directory(chapter)
            f.metadata(directory)
            val originals = File(directory, ".yakuyomi").apply { check(mkdir()) }
            File(originals, "001.orig.png").writeBytes(f.png)
            val before = f.storageSnapshot()
            val cache = f.cache()
            f.renew(cache)
            val actual = f.status(cache, chapter) to cache.getDownloadCount(f.manga)
            f.assertStorageUnchanged(before)
            assertEquals("DL_CONTENT_ORIGINAL_ONLY", listOf(false, false) to 0, actual)
        }
    }

    @Test(timeout = 40_000)
    fun regularAndExtensionlessImagesRemainDownloadedAndTemporaryChaptersDoNot(): Unit = runBlocking {
        withFixture { f ->
            val png = f.chapter(1)
            val extensionlessPng = f.chapter(2)
            val extensionlessAvif = f.chapter(3)
            val temporary = f.chapter(4)
            File(f.directory(png), "001.png").writeBytes(f.png)
            File(f.directory(extensionlessPng), "001").writeBytes(f.png)
            File(f.directory(extensionlessAvif), "001").writeBytes(f.avif)
            File(f.directory(temporary, suffix = "_tmp"), "001.png").writeBytes(f.png)
            val before = f.storageSnapshot()
            val cache = f.cache()
            f.renew(cache)
            val actual = listOf(png, extensionlessPng, extensionlessAvif, temporary).map { f.status(cache, it) }
            val count = cache.getDownloadCount(f.manga)
            f.assertStorageUnchanged(before)
            assertEquals(
                "DL_CONTENT_VALID_IMAGES_CONTROL",
                listOf(listOf(true, true), listOf(true, true), listOf(true, true), listOf(false, false)),
                actual,
            )
            assertEquals("DL_CONTENT_VALID_IMAGES_COUNT_CONTROL", 3, count)
        }
    }

    @Test(timeout = 40_000)
    fun imageArchiveRemainsDownloadedWithoutChangingArchiveBytes(): Unit = runBlocking {
        withFixture { f ->
            val chapter = f.chapter(1)
            f.archive(chapter, linkedMapOf("001.png" to f.png, "ComicInfo.xml" to METADATA))
            val before = f.storageSnapshot()
            val cache = f.cache()
            f.renew(cache)
            val actual = f.status(cache, chapter) to cache.getDownloadCount(f.manga)
            f.assertStorageUnchanged(before)
            assertEquals("DL_CONTENT_VALID_ARCHIVE_CONTROL", listOf(true, true) to 1, actual)
        }
    }

    @Test(timeout = 40_000)
    fun emptyCorruptAndMetadataOnlyArchivesAreNotDownloaded(): Unit = runBlocking {
        withFixture { f ->
            val empty = f.chapter(1)
            val corrupt = f.chapter(2)
            val metadata = f.chapter(3)
            f.archive(empty, emptyMap())
            f.archiveFile(corrupt).writeBytes("invalid neutral archive".toByteArray())
            f.archive(metadata, linkedMapOf("ComicInfo.xml" to METADATA, ".yakuyomi/001.orig.png" to f.png))
            val before = f.storageSnapshot()
            val cache = f.cache()
            f.renew(cache)
            val actual = listOf(empty, corrupt, metadata).map { f.status(cache, it) } to cache.getDownloadCount(f.manga)
            f.assertStorageUnchanged(before)
            assertEquals(
                "DL_CONTENT_INVALID_ARCHIVES",
                List(3) { listOf(false, false) } to 0,
                actual,
            )
        }
    }

    @Test(timeout = 40_000)
    fun persistedIndexCannotImportDownloadsFromADifferentStorageRoot(): Unit = runBlocking {
        withFixture { f ->
            val chapter = f.chapter(1)
            File(f.directory(chapter), "001.png").writeBytes(f.png)
            val before = f.storageSnapshot()
            val cacheA = f.cache()
            f.renew(cacheA)
            assertEquals("DL_CONTENT_ROOT_A_SEED_CONTROL", listOf(true, true), f.status(cacheA, chapter))
            assertEquals("DL_CONTENT_ROOT_A_COUNT_CONTROL", 1, cacheA.getDownloadCount(f.manga))
            f.persist(cacheA)
            val backgroundJobs = f.activeChildren(cacheA)
            assertTrue(
                "Cache seed must have an actual nonempty persisted index",
                f.indexFiles().any { it.length() > 0 },
            )
            f.closeScope(cacheA)

            // Same cache directory, different private storage directory: exercise actual disk restore.
            val cacheB = f.cache(f.rootB)
            f.awaitConstructorJobs(cacheB, backgroundJobs)
            val restoredStatus = f.status(cacheB, chapter)
            f.awaitRenewalIfPresent(cacheB)
            val count = cacheB.getDownloadCount(f.manga)
            f.assertStorageUnchanged(before)
            assertEquals(
                "DL_CONTENT_DIFFERENT_ROOT",
                listOf(false, false, 0),
                listOf(restoredStatus.first(), restoredStatus.last(), count),
            )
        }
    }

    @Test(timeout = 40_000)
    fun metadataDirectoryDoesNotBlockExplicitDownloadQueueAdmission(): Unit = runBlocking {
        withFixture { f ->
            val empty = f.chapter(1)
            val metadata = f.chapter(2)
            val downloaded = f.chapter(3)
            f.directory(empty)
            f.metadata(f.directory(metadata))
            File(f.directory(downloaded), "001.png").writeBytes(f.png)
            val before = f.storageSnapshot()
            val cache = f.cache()
            val downloader = f.downloader(cache)
            downloader.queueChapters(f.manga, listOf(empty, metadata, downloaded), autoStart = false)
            val queued = downloader.queueState.value.map { it.chapter.id }.toSet()
            val running = downloader.isRunning
            f.assertStorageUnchanged(before)
            assertEquals("DL_CONTENT_QUEUE_ADMISSION", setOf(empty.id, metadata.id), queued)
            assertFalse("An admission-only test must never start the downloader", running)
            assertEquals("No source endpoint may be invoked by queue admission", 0, f.source.calls.get())
        }
    }

    @Test(timeout = 40_000)
    fun readerFindsReadableLegacyDirectoryAndArchiveAfterMetadataOnlyPrimary(): Unit = runBlocking {
        withFixture { f ->
            val directoryChapter = f.chapter(1)
            val archiveChapter = f.chapter(2)
            f.metadata(f.directory(directoryChapter))
            f.metadata(f.directory(archiveChapter))
            val legacy = File(f.mangaDirectory(), directoryChapter.name).apply { check(mkdir()) }
            File(legacy, "001.png").writeBytes(f.png)
            f.archive(archiveChapter, linkedMapOf("001.png" to f.png, ".yakuyomi/001.orig.png" to f.png))
            val before = f.storageSnapshot()
            val cache = f.cache()
            val manager = f.manager(cache)
            val directoryBytes = runCatching {
                val page = manager.buildPageList(f.source, f.manga, directoryChapter).single()
                requireNotNull(f.context.contentResolver.openInputStream(requireNotNull(page.uri))).use {
                    it.readBytes()
                }
            }.getOrNull()
            val loader = DownloadPageLoader(ReaderChapter(archiveChapter), f.manga, f.source, manager, f.provider())
            val archiveBytes = try {
                runCatching {
                    val page = loader.getPages().single()
                    requireNotNull(page.stream).invoke().use { it.readBytes() }
                }.getOrNull()
            } finally {
                loader.recycle()
            }
            f.assertStorageUnchanged(before)
            assertEquals(
                "DL_CONTENT_READER_ALIASES",
                listOf(sha(f.png), sha(f.png)),
                listOf(directoryBytes?.let(::sha), archiveBytes?.let(::sha)),
            )
            assertEquals("Local reader loading must invoke no source endpoint", 0, f.source.calls.get())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") {
            "Download content tests require the isolated audit installation"
        }
        withTimeout(10_000) { Injekt.get<ExtensionManager>().isInitialized.first { it } }
        val fixture = Fixture(target)
        var failure: Throwable? = null
        try {
            block(fixture)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    fixture.close()
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }
    }

    private class Fixture(private val target: Context) {
        private val prefix = "download-cache-content-${UUID.randomUUID()}"
        private val owned = File(target.cacheDir, prefix).apply { check(mkdir()) }
        private val privateCache = File(owned, "cache").apply { check(mkdir()) }
        val rootA = File(owned, "root-a").apply { check(mkdir()) }
        val rootB = File(owned, "root-b").apply { check(mkdir()) }
        private val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(target) {
            override fun getCacheDir(): File = privateCache

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$prefix-$name"
                synchronized(preferenceNames) { preferenceNames += isolatedName }
                return target.getSharedPreferences(isolatedName, mode)
            }
        }
        val source = NeutralHttpSource()
        private val sources = object : SourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val sources = MutableStateFlow<List<Source>>(listOf(source))
            override fun get(sourceKey: Long): Source? = source.takeIf { it.id == sourceKey }
            override fun getOrStub(sourceKey: Long): Source = requireNotNull(get(sourceKey))
            override fun getAll(): List<Source> = listOf(source)
            override fun getOnlineSources(): List<HttpSource> = listOf(source)
            override fun getStubSources(): List<StubSource> = emptyList()
        }
        val manga = Manga.create().copy(
            id = 900_001,
            source = source.id,
            title = "Neutral download fixture",
            url = "/neutral",
        )
        val png = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(Color.WHITE)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        val avif = InstrumentationRegistry.getInstrumentation().context.assets
            .open("translation-formats/white-32.avif").use { it.readBytes() }.also {
                check(it.size == 300 && sha(it) == "c03ca656b1ca74938cc7b270a34caa9e0147f2038ed1225464a4a2ed62c5594d")
            }
        private val managers = mutableMapOf<File, StorageManager>()
        private val providers = mutableMapOf<File, DownloadProvider>()
        private val scopeOwners = mutableListOf<Any>()

        fun chapter(number: Int): Chapter = Chapter.create().copy(
            id = 910_000L + number,
            mangaId = manga.id,
            name = "Chapter $number",
            url = "/neutral/chapter/$number",
            sourceOrder = number.toLong(),
        )

        fun provider(root: File = rootA): DownloadProvider = providers.getOrPut(root) {
            val folder = object : FolderProvider {
                override fun directory(): File = root
                override fun path(): String = Uri.fromFile(root).toString()
            }
            val storage = StorageManager(context, StoragePreferences(folder, InMemoryPreferenceStore()))
            managers[root] = storage
            scopeOwners += storage
            DownloadProvider(context, storage, LibraryPreferences(InMemoryPreferenceStore()))
        }

        fun mangaDirectory(): File = File(
            rootA,
            "downloads/${provider().getSourceDirName(source)}/${provider().getMangaDirName(manga.title)}",
        ).apply { check(isDirectory || mkdirs()) }

        fun directory(chapter: Chapter, suffix: String = ""): File = File(
            mangaDirectory(),
            provider().getChapterDirName(chapter.name, chapter.scanlator, chapter.url) + suffix,
        ).apply { check(isDirectory || mkdir()) }

        fun metadata(directory: File) {
            File(directory, ".nomedia").writeBytes(byteArrayOf())
            File(directory, "ComicInfo").writeBytes(METADATA)
        }

        fun archiveFile(chapter: Chapter): File = File(
            mangaDirectory(),
            provider().getChapterDirName(chapter.name, chapter.scanlator, chapter.url) + ".cbz",
        )

        fun archive(chapter: Chapter, entries: Map<String, ByteArray>) {
            ZipOutputStream(archiveFile(chapter).outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

        fun cache(root: File = rootA): DownloadCache {
            val provider = provider(root)
            return DownloadCache(context, provider, sources, Injekt.get(), managers.getValue(root)).also {
                scopeOwners += it
            }
        }

        suspend fun downloader(cache: DownloadCache): Downloader = Downloader(
            context,
            provider(),
            cache,
            sourceManager = sources,
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        ).also {
            scopeOwners += it
            // Downloader restores its UUID-only store in a global Main launch; drain that initialization.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        suspend fun manager(cache: DownloadCache): DownloadManager = DownloadManager(
            context,
            provider(),
            cache,
            sourceManager = sources,
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        ).also {
            scopeOwners += requireNotNull(field(it, "downloader"))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        fun status(cache: DownloadCache, chapter: Chapter): List<Boolean> = listOf(true, false).map { skipCache ->
            cache.isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, manga.title, source.id, skipCache)
        }

        suspend fun renew(cache: DownloadCache) {
            cache.invalidateCache()
            val renewal = field(cache, "renewalJob") as? Job
            check(renewal != null) { "Public invalidation must launch a renewal" }
            withTimeout(10_000) { renewal.join() }
            check(!renewal.isCancelled) { "Fresh indexing must finish successfully" }
        }

        suspend fun awaitRenewalIfPresent(cache: DownloadCache) {
            (field(cache, "renewalJob") as? Job)?.let { withTimeout(10_000) { it.join() } }
        }

        suspend fun persist(cache: DownloadCache) {
            val writer = field(cache, "updateDiskCacheJob") as? Job
            check(writer != null) { "Fresh indexing must schedule the actual persisted index" }
            withTimeout(10_000) { writer.join() }
            check(!writer.isCancelled) { "The actual index writer must finish" }
            // The remaining children are the changes sharing, initialization state sharing, and storage listener.
            withTimeout(10_000) { while (activeChildren(cache) > 3) delay(10) }
        }

        fun indexFiles(): List<File> = privateCache.listFiles().orEmpty()
            .filter { it.name.startsWith("dl_index_cache_") }

        fun activeChildren(owner: Any): Int = scopeJob(owner).children.count { !it.isCompleted }

        suspend fun awaitConstructorJobs(cache: DownloadCache, backgroundJobs: Int) {
            // Observe finite restore work ending, rather than sleeping for an assumed I/O duration.
            withTimeout(10_000) { while (activeChildren(cache) > backgroundJobs) delay(10) }
        }

        suspend fun closeScope(owner: Any) {
            withTimeout(10_000) { scopeJob(owner).cancelAndJoin() }
        }

        fun storageSnapshot(): Map<String, String> = listOf(rootA, rootB).flatMap { root ->
            root.walkTopDown().filter { it.isFile }.map { file ->
                "${root.name}/${file.relativeTo(root).invariantSeparatorsPath}" to sha(file.readBytes())
            }.toList()
        }.toMap()

        fun assertStorageUnchanged(before: Map<String, String>) {
            assertEquals("DL_CONTENT_FILE_PRESERVATION", before, storageSnapshot())
        }

        suspend fun close() {
            scopeOwners.asReversed().forEach { closeScope(it) }
            assertEquals("DL_CONTENT_NO_SOURCE_CALLS", 0, source.calls.get())
            synchronized(preferenceNames) { preferenceNames.toList() }.forEach { name ->
                check(target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                check(target.deleteSharedPreferences(name))
            }
            val parent = target.cacheDir.canonicalFile
            val directory = owned.canonicalFile
            check(
                directory.parentFile == parent && directory.name == prefix &&
                    directory.name.startsWith("download-cache-content-"),
            )
            check(directory.deleteRecursively()) { "Cannot remove the owned download-content fixture" }
        }

        private fun scopeJob(owner: Any): Job = requireNotNull(
            (field(owner, "scope") as CoroutineScope).coroutineContext[Job],
        )
    }

    private class NeutralHttpSource : HttpSource() {
        override val id = 9_000_001L
        override val name = "Neutral download fixture"
        override val lang = "all"
        override val baseUrl = "https://invalid.example"
        override val supportsLatest = false
        val calls = AtomicInteger()

        private fun unexpectedCall(): Nothing {
            calls.incrementAndGet()
            error("Download-content fixture must never access a source endpoint")
        }

        override suspend fun getPopularManga(page: Int): MangasPage = unexpectedCall()
        override suspend fun getLatestUpdates(page: Int): MangasPage = unexpectedCall()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            unexpectedCall()
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = unexpectedCall()
        override suspend fun getPageList(chapter: SChapter): List<Page> = unexpectedCall()
    }

    companion object {
        private val METADATA =
            "<?xml version=\"1.0\"?><ComicInfo><Title>Neutral fixture</Title></ComicInfo>".toByteArray()

        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(owner)

        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
