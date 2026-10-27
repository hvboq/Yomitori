package mihon.translation

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.security.MessageDigest

/** Real publication followed by a controlled cache-lock wait; no live site, native model or Activity. */
@RunWith(AndroidJUnit4::class)
class DownloaderPublicationCancellationTest {
    @Test(timeout = 60_000)
    fun committedPublicationSurvivesPauseStartWhileCacheUpdateWaits(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        check(InstrumentationRegistry.getArguments().getString("downloaderPublicationCancellation") == "true") {
            "Explicit downloaderPublicationCancellation opt-in required"
        }
        NativeDiagnosticIdleGuard.requireIdle(target)
        val preferencesBefore = preferenceSnapshot(target)
        val report = JSONObject().put("schemaVersion", 1).put("terminal", false).put("passed", false)
            .put("actualPublicQueuePauseStart", true).put("actualSafPublication", true)
            .put("cacheMutexBarrier", true).put("actualActivity", false)
            .put("modelInferenceCalls", 0).put("liveSiteOrTranslationServerCalls", 0)
        val fixture = DownloaderEndToEndFixture(target, archive = false)
        var failure: Throwable? = null
        var heldMutex: Mutex? = null
        val lockOwner = Any()
        try {
            fixture.prepare()
            val manager = field(fixture, "manager") as DownloadManager
            val downloader = field(manager, "downloader")
            val cache = field(fixture, "cache") as DownloadCache
            val provider = field(fixture, "provider") as DownloadProvider
            val manga = field(fixture, "manga") as Manga
            val chapter = field(fixture, "chapter") as Chapter
            val server = field(fixture, "server")
            val source = field(server, "source") as HttpSource
            val originalPages = (field(server, "pages") as List<*>).map { it as ByteArray }
            val canonical = field(fixture, "canonical") as File
            val mangaDirectory = field(fixture, "mangaDirectory") as File
            val metadataBefore = snapshot(canonical)
            val published = File(mangaDirectory, canonical.name + "_dl")
            val staged = File(mangaDirectory, canonical.name + "_tmp")

            manager.downloadChapters(manga, listOf(chapter), autoStart = false)
            val queued = manager.queueState.value.single()
            assertEquals("PUBLICATION_QUEUED", Download.State.QUEUE, queued.status)
            assertFalse("PUBLICATION_NOT_STARTED", manager.isRunning)
            assertEquals("PUBLICATION_NO_PREPARATION_HTTP", 0, requests(server).size)
            val mutex = field(cache, "rootDownloadsDirMutex") as Mutex
            withTimeout(5_000) { mutex.lock(lockOwner) }
            heldMutex = mutex
            assertTrue("PUBLICATION_FIRST_START", manager.downloaderStart())
            val firstGeneration = field(downloader, "downloaderJob") as Job

            // The private provider has actually renamed the complete staging folder. Cache update
            // cannot finish because this test owns its mutex; no fake completion signal is injected.
            withTimeout(20_000) {
                while (!published.isDirectory || staged.exists() ||
                    originalPages.indices.any { !File(published, pageName(it)).isFile }
                ) {
                    check(queued.status != Download.State.ERROR) { "Download failed before publication barrier" }
                    delay(10)
                }
            }
            assertPages(published, originalPages)
            assertEquals("PUBLICATION_NOT_COMPLETED_BEFORE_CACHE", Download.State.DOWNLOADING, queued.status)
            assertFalse("PUBLICATION_FIRST_GENERATION_HELD", firstGeneration.isCompleted)
            // The normal commit adds .nomedia after cache insertion; image and metadata bytes
            // already published before pause must remain identical across that finalization.
            val publishedBefore = snapshot(published).filterKeys { it != ".nomedia" }
            val requestsBefore = requestCounts(server)
            assertEquals("PUBLICATION_INITIAL_HTTP", EXPECTED_REQUESTS, requestsBefore)
            report.put("publishedBeforePause", true).put("cacheLockHeldAtPause", mutex.holdsLock(lockOwner))
                .put("initialHttpRequests", requests(server).size)

            manager.pauseDownloads()
            assertTrue("PUBLICATION_PAUSE_CANCELLED_OLD_GENERATION", firstGeneration.isCancelled)
            assertTrue("PUBLICATION_SUCCESSOR_ACCEPTED", manager.downloaderStart())
            val successor = field(downloader, "downloaderJob") as Job
            assertTrue("PUBLICATION_DISTINCT_SUCCESSOR", successor !== firstGeneration)
            mutex.unlock(lockOwner)
            heldMutex = null
            withTimeout(20_000) {
                while (manager.queueState.value.isNotEmpty() || manager.isRunning) delay(10)
                firstGeneration.join()
                successor.join()
                val scope = field(downloader, "scope") as CoroutineScope
                requireNotNull(scope.coroutineContext[Job]).children.toList().forEach { it.join() }
            }

            assertEquals("PUBLICATION_COMMITTED_COMPLETION", Download.State.DOWNLOADED, queued.status)
            assertEquals("PUBLICATION_NO_DUPLICATE_HTTP", requestsBefore, requestCounts(server))
            assertEquals("PUBLICATION_ORIGINAL_METADATA_PRESERVED", metadataBefore, snapshot(canonical))
            assertEquals(
                "PUBLICATION_COMMITTED_BYTES_PRESERVED",
                publishedBefore,
                snapshot(published).filterKeys { it != ".nomedia" },
            )
            assertPages(published, originalPages)
            assertEquals(
                "PUBLICATION_NO_SECOND_CONTAINER_OR_STAGING",
                setOf(canonical.name, published.name),
                mangaDirectory.listFiles().orEmpty().map { it.name }.toSet(),
            )
            val readable = requireNotNull(
                provider.findReadableDownloadedChapterDir(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    source,
                ),
            )
            val resolved = requireNotNull(
                provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source),
            )
            assertEquals("PUBLICATION_PROVIDER_ALIAS", published.name, readable.name)
            assertEquals("PUBLICATION_PROVIDER_SAME_URI", readable.uri, resolved.uri)
            assertEquals("PUBLICATION_REAL_SAF_URI", "content", readable.uri.scheme)
            for (skipCache in listOf(false, true)) {
                assertTrue(
                    "PUBLICATION_CACHE_AND_FILESYSTEM_AGREE",
                    cache.isChapterDownloaded(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.title,
                        source.id,
                        skipCache,
                    ),
                )
            }
            assertEquals("PUBLICATION_SINGLE_CACHED_CHAPTER", 1, cache.getDownloadCount(manga))
            check(nullableField(server, "failure") == null) { "Neutral loopback server failed" }
            report.put("oldGenerationJoined", firstGeneration.isCompleted)
                .put("successorJoined", successor.isCompleted).put("queueEmpty", true)
                .put("downloaderIdle", !manager.isRunning).put("httpRequests", requests(server).size)
                .put("httpImageRequests", originalPages.size).put("duplicateHttpRequests", 0)
                .put("committedBytesPreserved", true).put("originalMetadataPreserved", true)
                .put("cacheAndFilesystemAgree", true).put("cachedChapters", 1)
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                try {
                    heldMutex?.unlock(lockOwner)
                    heldMutex = null
                } catch (cleanup: Throwable) {
                    failure = preserveFailure(failure, cleanup)
                }
                try {
                    fixture.close()
                    assertTrue("PUBLICATION_RAW_PREFERENCES", preferencesBefore == preferenceSnapshot(target))
                    report.put("fixtureClosed", true).put("rawPreferencesUnchanged", true)
                } catch (cleanup: Throwable) {
                    failure = preserveFailure(failure, cleanup)
                }
                report.put("terminal", true).put("passed", failure == null)
                instrumentation.sendStatus(
                    2,
                    Bundle().apply { putString("downloaderPublicationCancellationEvidence", report.toString()) },
                )
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        val EXPECTED_REQUESTS = mapOf("/chapter" to 1, "/image/1.png" to 1, "/image/2.png" to 1)

        fun nullableField(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

        fun field(owner: Any, name: String): Any = requireNotNull(nullableField(owner, name))

        fun requests(server: Any): Collection<*> = field(server, "requests") as Collection<*>

        fun requestCounts(server: Any) = requests(server).groupingBy { it }.eachCount()

        fun pageName(index: Int) = "%03d.png".format(java.util.Locale.ENGLISH, index + 1)

        fun assertPages(directory: File, expected: List<ByteArray>) {
            expected.forEachIndexed { index, bytes ->
                assertArrayEquals("PUBLICATION_EXACT_NEUTRAL_PAGE", bytes, File(directory, pageName(index)).readBytes())
            }
        }

        fun snapshot(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
                .digest(it.readBytes()).joinToString("") { value -> "%02x".format(value.toInt() and 255) }
        }

        fun preferenceSnapshot(context: Context) = snapshot(File(context.applicationInfo.dataDir, "shared_prefs"))

        fun preserveFailure(previous: Throwable?, cleanup: Throwable): Throwable {
            if (previous == null || cleanup is VirtualMachineError || cleanup is ThreadDeath ||
                cleanup is LinkageError
            ) {
                previous?.let(cleanup::addSuppressed)
                return cleanup
            }
            previous.addSuppressed(cleanup)
            return previous
        }
    }
}
