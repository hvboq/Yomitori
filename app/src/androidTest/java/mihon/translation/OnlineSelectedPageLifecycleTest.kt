package eu.kanade.tachiyomi.ui.reader.loader

import android.os.Bundle
import android.os.Debug
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 24 bounded ownership cycles through the current HTTP loader, selected-page cache and real storage.
 * Processing is scripted: no Activity, native inference, translation server or download queue is used.
 * Reopening means a fresh loader at the same source identity. HTTP page-list background writes are
 * deliberately excluded, as in OnlineSelectedPageFixture; owned scope jobs and streams are joined.
 */
@RunWith(AndroidJUnit4::class)
class OnlineSelectedPageLifecycleTest {
    @Test
    fun repeatedSelectedPageOwnershipStaysBounded() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("selectedPageLifecycleAudit") == "true") { "Explicit lifecycle opt-in required" }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        val runId = checkNotNull(arguments.getString("diagnosticRunId")) { "Diagnostic UUID required" }
        check(runCatching { UUID.fromString(runId).toString() == runId }.getOrDefault(false)) {
            "Canonical diagnostic UUID required"
        }
        val started = System.nanoTime()
        val cycles = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("passed", false).put("terminal", false)
            .put("requestedCycles", CYCLES).put("completedCycles", 0).put("maximumWorkMs", 180_000)
            .put("cycles", cycles).put("activityInvoked", false).put("nativeInferenceCalls", 0)
            .put("nativeHandleCleanupTested", false).put("serverRequests", 0).put("downloadQueueCalls", 0)
            .put("scriptedProcessor", true).put("realLoaderCacheAndPersistence", true)
            .put("pageListBackgroundWriterExcluded", true).put("sharedImageCacheOpenUntilFinal", true)
            .put("memoryObservationsAreLeakProof", false).put("initialMemory", memory())
        fun emit() {
            report.put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply { putString("onlineSelectedPageLifecycleEvidence", report.toString()) },
            )
        }
        var failure: Throwable? = null
        try {
            withTimeout(180_000) {
                OnlineSelectedPageFixture.run { fixture ->
                    val harness = Harness(fixture, runId)
                    var primaryFailure: Throwable? = null
                    try {
                        harness.seedPending()
                        report.put("pendingSeeded", true)
                        emit()
                        for (cycle in 1..CYCLES) {
                            val row = JSONObject().put("cycle", cycle).put("passed", false)
                            cycles.put(row)
                            try {
                                harness.cycle(cycle, row)
                                row.put("memory", memory()).put("passed", true)
                                report.put("completedCycles", cycle)
                            } finally {
                                emit()
                            }
                        }
                        harness.finishPending(report)
                        report.put("functionalStagesPassed", true)
                    } catch (error: Throwable) {
                        primaryFailure = error
                        throw error
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                report.put("cleanup", harness.close())
                            } catch (error: Throwable) {
                                report.put("cleanupFailureClass", error.javaClass.simpleName)
                                if (primaryFailure == null || fatal(error)) throw error
                            }
                        }
                    }
                }
            }
            report.put("fixtureCleanupAndRawPreferencesPassed", true).put("passed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("errorClass", error.javaClass.simpleName)
        } finally {
            report.put("finalMemory", memory()).put("terminal", true)
            emit()
        }
        when (val error = failure) {
            is VirtualMachineError, is ThreadDeath, is LinkageError -> throw error
            is CancellationException -> throw CancellationException("Selected-page lifecycle diagnostic cancelled")
            null -> Unit
            else -> throw AssertionError("Selected-page lifecycle diagnostic failed; inspect numeric evidence")
        }
    }

    private class Harness(private val fixture: OnlineSelectedPageFixture, private val runId: String) {
        private val limits = OnlineSelectedPageFixture.loaderLimits(entries = 3)
            .copy(retainedBytes = 128L * 1024 * 1024)
        private val cache = OnlinePageCache(fixture.context, limits)
        private val cacheRoot = File(fixture.context.cacheDir.canonicalFile, "online-reader-pages-v1").canonicalFile
        private val imageCacheOwner = lazy { ChapterCache(fixture.context, Json) }
        private val imageCache by imageCacheOwner
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var current: Session? = null
        private var block: Block? = null
        private var pending: Pending? = null
        private val calls = AtomicIntegerArray(PAGE_COUNT)
        private val active = AtomicInteger()
        private val peak = AtomicInteger()
        private val imageRequests = AtomicInteger()
        private val openStreams = AtomicInteger()
        private var sessionsOpened = 0
        private var sessionsClosed = 0
        private var stoppedAttempts = 0
        private var pressureRejections = 0
        private var evictedCompleted = 0

        private class Block(val index: Int) {
            val entered = CompletableDeferred<Job>()
            val release = CompletableDeferred<Unit>()
        }

        private class Pending(
            val stored: OnlineTranslatedPageLoader.StoredPage,
            val store: PartialPageStore,
            val payload: String,
            val snapshot: Map<String, String>,
            val sourceSha: String,
        )

        private class Session(
            val chapter: ReaderChapter,
            val delegate: HttpPageLoader,
            val loader: OnlineTranslatedPageLoader,
            val page: ReaderPage,
            val load: Job,
            val client: OkHttpClient,
        )

        private suspend fun open(index: Int): Session {
            check(current == null && index in 0 until PAGE_COUNT)
            val httpClient = OkHttpClient.Builder().connectionPool(ConnectionPool()).addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "selected-lifecycle.invalid")
                val requestedIndex = request.url.pathSegments.last().removeSuffix(".png").toInt()
                check(requestedIndex in 0 until PAGE_COUNT)
                check(imageRequests.incrementAndGet() <= 512)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("neutral")
                    .body(OnlineSelectedPageFixture.original(requestedIndex).toResponseBody("image/png".toMediaType()))
                    .build()
            }.build()
            var delegate: HttpPageLoader? = null
            var loader: OnlineTranslatedPageLoader? = null
            var chapterOwner: ReaderChapter? = null
            try {
                val source = object : HttpSource() {
                    override val id = 9_810_001L
                    override val name = "Neutral bounded lifecycle"
                    override val lang = "ja"
                    override val supportsLatest = false
                    override val baseUrl = "https://selected-lifecycle.invalid"
                    override val client = httpClient
                    override fun headersBuilder(): Headers.Builder = Headers.Builder()
                    override suspend fun getPageList(chapter: SChapter): List<Page> = (0 until PAGE_COUNT).map {
                        Page(it, "/logical/$it", "$baseUrl/$runId/$it.png")
                    }
                }
                val manga = Manga.create().copy(
                    id = 9_810_002L,
                    source = source.id,
                    title = "Neutral bounded lifecycle",
                    url = "/neutral/$runId",
                )
                val chapter = ReaderChapter(
                    Chapter.create().copy(
                        id = 9_810_003L,
                        mangaId = manga.id,
                        url = "/chapter/$runId",
                        name = "Neutral chapter",
                    ),
                ).also { chapterOwner = it }
                val http = HttpPageLoader(chapter, source, imageCache).also { delegate = it }
                val pages = mutableListOf<ReaderPage>()
                val online = OnlineTranslatedPageLoader(http, chapter, manga, fixture.context, cache) { stored ->
                    val selected = pages.single { it.uri == stored.pageFile.uri }.index
                    check(selected == index)
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    try {
                        check(count == 1)
                        calls.incrementAndGet(selected)
                        block?.takeIf { it.index == selected && !it.entered.isCompleted }?.let { held ->
                            held.entered.complete(checkNotNull(currentCoroutineContext()[Job]))
                            withContext(NonCancellable) { held.release.await() }
                            currentCoroutineContext().ensureActive()
                        }
                        fixture.complete(stored, selected)
                        PageTranslator.SinglePageOutcome(PageTranslator.SinglePageResult.TRANSLATED, true)
                    } finally {
                        active.decrementAndGet()
                    }
                }.also { loader = it }
                pages += online.getPages()
                chapter.state = ReaderChapter.State.Loaded(pages)
                chapter.pageLoader = online
                val page = pages[index]
                val load = scope.launch { online.loadPage(page) }
                val session = Session(chapter, http, online, page, load, httpClient)
                current = session
                sessionsOpened++
                val state = withTimeout(5000) {
                    page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                }
                check(state == Page.State.Ready)
                return session
            } catch (error: Throwable) {
                if (current == null) {
                    withContext(NonCancellable) {
                        var fatalCleanup: Throwable? = null
                        suspend fun cleanup(action: suspend () -> Unit) {
                            try {
                                action()
                            } catch (cleanup: Throwable) {
                                if (fatal(cleanup)) fatalCleanup = cleanup
                            }
                        }
                        chapterOwner?.state = ReaderChapter.State.Wait
                        cleanup { loader?.recycle() }
                        cleanup { delegate?.recycle() }
                        cleanup {
                            withTimeout(5000) {
                                loader?.let { ownerJob(it).join() }
                                delegate?.let { ownerJob(it).join() }
                            }
                        }
                        cleanup { httpClient.dispatcher.cancelAll() }
                        cleanup { httpClient.connectionPool.evictAll() }
                        cleanup { httpClient.dispatcher.executorService.shutdownNow() }
                        cleanup { check(httpClient.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)) }
                        cleanup { httpClient.connectionPool.evictAll() }
                        fatalCleanup?.let { throw it }
                    }
                }
                throw error
            }
        }

        private suspend fun closeSession() {
            val session = current ?: return
            block?.release?.complete(Unit)
            var failure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    if (failure == null || fatal(error)) failure = error
                }
            }
            cleanup { withTimeout(5000) { session.loader.stopAndJoin() } }
            cleanup { session.load.cancelAndJoin() }
            // Exclude HttpPageLoader's unowned page-list writer, exactly as the existing loader fixtures do.
            session.chapter.state = ReaderChapter.State.Wait
            cleanup { session.loader.recycle() }
            cleanup {
                withTimeout(5000) {
                    ownerJob(session.loader).join()
                    ownerJob(session.delegate).join()
                }
                check(ownerJob(session.loader).isCompleted && ownerJob(session.delegate).isCompleted)
                check(session.load.isCompleted && session.loader.progress.value == null && active.get() == 0)
            }
            cleanup { session.client.dispatcher.cancelAll() }
            cleanup { session.client.connectionPool.evictAll() }
            cleanup { session.client.dispatcher.executorService.shutdownNow() }
            cleanup { check(session.client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)) }
            cleanup {
                session.client.connectionPool.evictAll()
                check(session.client.dispatcher.runningCallsCount() == 0)
                check(
                    session.client.dispatcher.queuedCallsCount() == 0 &&
                        session.client.connectionPool.connectionCount() == 0,
                )
            }
            current = null
            if (failure != null) throw checkNotNull(failure)
            sessionsClosed++
        }

        suspend fun seedPending() {
            val session = open(0)
            try {
                session.loader.select(session.page, translate = false)
                await { session.loader.resolvePage(session.page) != null }
                OnlineSelectedPageFixture.settled(session.loader)
                val stored = checkNotNull(session.loader.resolvePage(session.page))
                val directory = File(checkNotNull(stored.chapterDir.uri.path))
                val source = File(directory, "source.bin").readBytes()
                val pageName = checkNotNull(stored.pageFile.name)
                val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
                val original = File(materials, OriginalMaterial.exactName(pageName)).apply { writeBytes(source) }
                val store = PartialPageStore.forUniFile(
                    fixture.context,
                    stored.chapterDir,
                    checkNotNull(UniFile.fromFile(materials)),
                )
                val payload = OcrProgressCodec.encode(OnlineSelectedPageFixture.progress(pending = true))
                store.commit(
                    pageName,
                    PartialPageStore.Original(original.name, sha(source), source.size.toLong()),
                    OnlineSelectedPageFixture.WIDTH,
                    OnlineSelectedPageFixture.HEIGHT,
                    payload,
                    OnlineSelectedPageFixture.translated(0),
                    OnlineSelectedPageFixture.encoded(android.graphics.Color.WHITE),
                    baseRevision = null,
                    complete = false,
                )
                pending = Pending(stored, store, payload, snapshot(directory), sha(source))
                check(calls.get(0) == 0)
            } finally {
                closeSession()
            }
            verifyPending()
            requireReleased()
        }

        suspend fun cycle(index: Int, row: JSONObject) {
            val beforeCalls = calls.get(index)
            check(beforeCalls == 0)
            val stop = index % 3 == 0
            val pressure = index % 4 == 0
            var stream: InputStream? = null
            var pressureLease: OnlinePageCache.Lease? = null
            try {
                val first = open(index)
                if (stop) block = Block(index)
                first.loader.select(first.page, translate = true)
                if (stop) {
                    val held = checkNotNull(block)
                    val job = withTimeout(5000) { held.entered.await() }
                    first.loader.stop()
                    val draining = scope.async(start = CoroutineStart.UNDISPATCHED) {
                        first.loader.stopAndJoin(keepSelection = true)
                    }
                    await { job.isCancelled }
                    check(!draining.isCompleted && active.get() == 1)
                    held.release.complete(Unit)
                    withTimeout(5000) { draining.await() }
                    val stored = checkNotNull(first.loader.resolvePage(first.page))
                    val stoppedSourceSha = sha(stored.pageFile.openInputStream().use { it.readBytes() })
                    check(stoppedSourceSha == sha(OnlineSelectedPageFixture.original(index)))
                    first.loader.select(first.page, translate = true)
                    OnlineSelectedPageFixture.settled(first.loader)
                    check(calls.get(index) == 1 && first.loader.progress.value == null)
                    first.loader.select(first.page, translate = true, explicitRetry = true, retryVersion = 1)
                    stoppedAttempts++
                    row.put("stopDrainedBeforeRetry", true).put("stoppedReselectionDidNotProcess", true)
                }
                finished(first, retryVersion = if (stop) 1 else 0)
                OnlineSelectedPageFixture.settled(first.loader)
                val originalAuthority = checkNotNull(first.loader.resolvePage(first.page))
                val originalUri = originalAuthority.pageFile.uri
                val completedSha = sha(originalAuthority.pageFile.openInputStream().use { it.readBytes() })
                check(completedSha != sha(OnlineSelectedPageFixture.original(index)))
                val completedCalls = calls.get(index)
                check(completedCalls == if (stop) 2 else 1)
                closeSession()

                val reopened = open(index)
                reopened.loader.select(reopened.page, translate = true)
                finished(reopened)
                OnlineSelectedPageFixture.settled(reopened.loader)
                val reused = checkNotNull(reopened.loader.resolvePage(reopened.page))
                check(reused.pageFile.uri == originalUri && calls.get(index) == completedCalls)
                check(sha(reused.pageFile.openInputStream().use { it.readBytes() }) == completedSha)
                val directory = File(checkNotNull(reused.chapterDir.uri.path))
                check(sha(File(directory, "source.bin").readBytes()) == sha(OnlineSelectedPageFixture.original(index)))
                row.put("stableAuthorityReused", true).put("completedReopenSkippedProcessor", true)
                    .put("originalAndDisplayPreserved", true)
                if (pressure) {
                    stream = track(checkNotNull(reopened.page.stream).invoke())
                    check(sha(checkNotNull(stream).readBytes()) == completedSha)
                }
                closeSession()
                if (pressure) {
                    val input = OnlineSelectedPageFixture.original(PAGE_COUNT - 1)
                    pressureLease = cache.capture(OnlinePageCache.sha("$runId-pressure-a-$index")) {
                        ByteArrayInputStream(input)
                    }
                    var rejected = false
                    try {
                        cache.capture(OnlinePageCache.sha("$runId-pressure-b-$index")) {
                            ByteArrayInputStream(input)
                        }.close()
                    } catch (_: IOException) {
                        rejected = true
                    }
                    check(rejected && directory.isDirectory && openStreams.get() == 1)
                    pressureRejections++
                    verifyPending()
                    stream?.close()
                    stream = null
                    cache.capture(OnlinePageCache.sha("$runId-pressure-b-$index")) { ByteArrayInputStream(input) }.use {
                        check(!directory.exists())
                    }
                    pressureLease?.close()
                    pressureLease = null
                    evictedCompleted++
                    val revisited = open(index)
                    revisited.loader.select(revisited.page, translate = true)
                    finished(revisited)
                    check(calls.get(index) == completedCalls + 1)
                    closeSession()
                    row.put("openStreamAndPendingBlockedPressure", true).put("releasedCompletedEntryEvicted", true)
                        .put("evictedRevisitProcessedExactlyOnce", true)
                }
                verifyPending()
                row.put("pendingJournalAndSuccessfulTargetPreserved", true).put("processorCalls", calls.get(index))
                    .put("ownership", requireReleased()).put("cache", cacheFacts())
            } finally {
                block?.release?.complete(Unit)
                stream?.close()
                pressureLease?.close()
                withContext(NonCancellable) { closeSession() }
                block = null
            }
        }

        suspend fun finishPending(report: JSONObject) {
            verifyPending()
            val preserved = checkNotNull(pending)
            val before = checkNotNull(
                preserved.store.load(checkNotNull(preserved.stored.pageFile.name), recover = false),
            )
            val beforeProgress = OcrProgressCodec.decode(before.progressPayload, before.width, before.height)
            val target = beforeProgress.regions[0].translatedText
            check(target == "중립 0" && beforeProgress.hasPending && calls.get(0) == 0)
            val session = open(0)
            try {
                session.loader.select(session.page, translate = false)
                await { session.loader.resolvePage(session.page) != null }
                OnlineSelectedPageFixture.settled(session.loader)
                check(
                    checkNotNull(session.loader.resolvePage(session.page)).pageFile.uri ==
                        preserved.stored.pageFile.uri,
                )
                check(calls.get(0) == 0)
                session.loader.select(session.page, translate = true, explicitRetry = true, retryVersion = 1)
                finished(session, retryVersion = 1)
                val after = checkNotNull(
                    preserved.store.load(checkNotNull(preserved.stored.pageFile.name), recover = false),
                )
                val progress = OcrProgressCodec.decode(after.progressPayload, after.width, after.height)
                check(after.complete && !progress.hasPending && progress.regions[0].translatedText == target)
                check(progress.regions[0] == beforeProgress.regions[0])
                val directory = File(checkNotNull(preserved.stored.chapterDir.uri.path))
                check(sha(File(directory, "source.bin").readBytes()) == preserved.sourceSha)
                check(calls.get(0) == 1)
                report.put("pendingExplicitRetryCompleted", true).put("priorSuccessfulTargetPreserved", true)
                    .put("pendingRetryUsedScriptedCompletion", true).put("nativePartialResumeVerified", false)
            } finally {
                closeSession()
            }
            check(stoppedAttempts == 8 && pressureRejections == 6 && evictedCompleted == 6 && peak.get() == 1)
            check((0 until PAGE_COUNT).sumOf(calls::get) == 39)
        }

        private fun verifyPending() {
            val preserved = checkNotNull(pending)
            val directory = File(checkNotNull(preserved.stored.chapterDir.uri.path))
            check(snapshot(directory) == preserved.snapshot)
            check(sha(File(directory, "source.bin").readBytes()) == preserved.sourceSha)
            val current = checkNotNull(
                preserved.store.load(checkNotNull(preserved.stored.pageFile.name), recover = false),
            )
            check(!current.complete && current.progressPayload == preserved.payload)
            val progress = OcrProgressCodec.decode(current.progressPayload, current.width, current.height)
            check(progress.hasPending && progress.regions[0].translatedText == "중립 0")
        }

        private fun track(input: InputStream): InputStream {
            openStreams.incrementAndGet()
            return object : FilterInputStream(input) {
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        super.close()
                    } finally {
                        openStreams.decrementAndGet()
                    }
                }
            }
        }

        private fun requireReleased(): JSONObject {
            check(current == null && active.get() == 0 && openStreams.get() == 0)
            val owner = OnlinePageCache::class.java
            val monitor = owner.getDeclaredField("monitor").apply { isAccessible = true }.get(null)
            val counts = synchronized(monitor) {
                val pins = owner.getDeclaredField("pins").apply { isAccessible = true }.get(null) as Map<*, *>
                val reservations = owner.getDeclaredField("reservations").apply { isAccessible = true }
                    .get(null) as Map<*, *>
                val captures = owner.getDeclaredField("activeCaptures").apply { isAccessible = true }
                    .get(null) as Set<*>
                val prefix = cacheRoot.path + File.separator
                val ownPins = pins.filterKeys { (it as String).startsWith(prefix) }.values.sumOf { it as Int }
                val ownReservations = reservations.values.count { value ->
                    checkNotNull(value).javaClass.getDeclaredField("root").apply { isAccessible = true }
                        .get(value) == cacheRoot.path
                }
                val ownCaptures = captures.count { (it as String).startsWith(prefix) }
                Triple(ownPins, ownReservations, ownCaptures)
            }
            check(counts == Triple(0, 0, 0))
            check(sessionsOpened == sessionsClosed)
            return JSONObject().put("activeProcessorCalls", active.get()).put("openDisplayStreams", openStreams.get())
                .put("cachePins", counts.first).put("cacheReservations", counts.second)
                .put("activeCaptures", counts.third)
                .put("sessionsOpened", sessionsOpened).put("sessionsClosed", sessionsClosed)
                .put("ownedLoaderAndDelegateJobsJoined", true)
        }

        private fun cacheFacts(): JSONObject {
            val entries = cacheRoot.listFiles().orEmpty().count {
                it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}"))
            }
            val files = cacheRoot.walkTopDown().filter { it.isFile }.toList()
            val bytes = files.sumOf { it.length() }
            check(entries <= 3 && bytes <= limits.bytes)
            check(
                cacheRoot.listFiles().orEmpty().none {
                    it.name.startsWith("capture-") || it.name.startsWith("prepare-")
                },
            )
            return JSONObject().put("entries", entries).put("files", files.size).put("bytes", bytes)
                .put("entryLimit", 3).put("byteLimit", limits.bytes)
        }

        suspend fun close(): JSONObject {
            block?.release?.complete(Unit)
            var failure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    if (failure == null || fatal(error)) failure = error
                }
            }
            cleanup { closeSession() }
            cleanup { checkNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
            cleanup {
                if (imageCacheOwner.isInitialized()) {
                    val field = ChapterCache::class.java.getDeclaredField("diskCache").apply { isAccessible = true }
                    (field.get(imageCache) as DiskLruCache).close()
                }
            }
            var ownership: JSONObject? = null
            cleanup { ownership = requireReleased() }
            val result = JSONObject().put("passed", failure == null).put("ownership", ownership ?: JSONObject.NULL)
                .put("processorCalls", (0 until PAGE_COUNT).sumOf(calls::get))
                .put("maxConcurrentProcessorCalls", peak.get())
                .put("scriptedImageRequests", imageRequests.get()).put("stoppedAttempts", stoppedAttempts)
                .put("pressureRejections", pressureRejections).put("completedEvictions", evictedCompleted)
                .put("sharedImageCacheClosed", failure == null).put("nativeHandlesCreated", 0)
            if (failure != null) throw checkNotNull(failure)
            return result
        }

        private suspend fun finished(session: Session, retryVersion: Long = 0) {
            val progress = withTimeout(10000) {
                session.loader.progress.first {
                    it?.page === session.page && it.retryVersion == retryVersion &&
                        (
                            it.stage == OnlineTranslatedPageLoader.Stage.FINISHED ||
                                it.stage == OnlineTranslatedPageLoader.Stage.FAILED
                            )
                }
            }
            val terminal = checkNotNull(progress)
            check(terminal.stage == OnlineTranslatedPageLoader.Stage.FINISHED)
            check(terminal.result == PageTranslator.SinglePageResult.TRANSLATED)
        }
    }

    private companion object {
        const val CYCLES = 24
        const val PAGE_COUNT = 30

        fun fatal(error: Throwable) = error is VirtualMachineError || error is ThreadDeath || error is LinkageError

        suspend fun await(condition: () -> Boolean) = withTimeout(5000) {
            while (!condition()) delay(5)
        }

        fun ownerJob(owner: Any): Job {
            val scope = owner.javaClass.getDeclaredField("scope").apply { isAccessible = true }
                .get(owner) as CoroutineScope
            return checkNotNull(scope.coroutineContext[Job])
        }

        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

        fun snapshot(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(directory).path to sha(it.readBytes()) }

        fun memory(): JSONObject {
            val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val runtime = Runtime.getRuntime()
            return JSONObject().put("pid", Process.myPid()).put("pssKiB", info.totalPss)
                .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("fdCount", File("/proc/self/fd").list()?.size ?: -1)
                .put("threadCount", File("/proc/self/task").list()?.size ?: -1)
        }
    }
}
