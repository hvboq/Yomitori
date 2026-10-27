// This test-only package permits delegation through UniFile's package-private constructor.
package com.hippo.unifile

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.translation.DownloaderEndToEndFixture
import mihon.translation.NativeDiagnosticIdleGuard
import mihon.translation.OnlineReaderSourceRegistration
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Actual Downloader storage boundaries, private fixture resources, controlled provider faults. */
@RunWith(AndroidJUnit4::class)
class DownloaderFaultRegressionTest {
    @Test(timeout = 60_000)
    fun malformedOrIncompletePartialResponsesNeverBecomeReady(): Unit = runBlocking {
        withFixture("malformedOrIncompletePartialResponsesNeverBecomeReady") { fixture, report ->
            val originalSource = source(fixture)
            val png = png(fixture)
            val prefix = png.copyOf(8)
            val rows = JSONArray()
            val cases = listOf(
                RangeCase("wrongStart", "bytes 0-${png.lastIndex}/${png.size}", png, false),
                RangeCase("endBeforeStart", "bytes 8-7/${png.size}", png.copyOfRange(8, png.size), false),
                RangeCase(
                    "totalNotBeyondEnd",
                    "bytes 8-${png.lastIndex}/${png.lastIndex}",
                    png.copyOfRange(8, png.size),
                    false,
                ),
                RangeCase("missingRange", null, png.copyOfRange(8, png.size), false),
                RangeCase("validButNotComplete", "bytes 8-15/${png.size}", png.copyOfRange(8, 16), true),
                RangeCase(
                    "declaredBodyMismatch",
                    "bytes 8-${png.lastIndex}/${png.size}",
                    png.copyOfRange(8, png.lastIndex),
                    false,
                ),
                RangeCase(
                    "shortBody",
                    "bytes 8-${png.lastIndex}/${png.size}",
                    png.copyOfRange(8, png.lastIndex),
                    true,
                    expectedTemporaryLength = png.lastIndex,
                    declaredLength = (png.size - 8).toLong(),
                ),
            )
            for (case in cases) {
                val closed = CompletableDeferred<Unit>()
                val responses = AtomicInteger()
                val transport = originalSource.client.newBuilder().addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    responses.incrementAndGet()
                    response.close()
                    val body = observeClose(bodyWithLength(case.body, case.declaredLength), closed)
                    response.newBuilder().code(206)
                        .header("Content-Length", (case.declaredLength ?: case.body.size.toLong()).toString())
                        .apply {
                            if (case.range == null) {
                                removeHeader("Content-Range")
                            } else {
                                header("Content-Range", case.range)
                            }
                        }
                        .body(body)
                        .build()
                }.build()
                val faultSource = sourceWithClient(originalSource, transport)
                val directory = caseDirectory(fixture, case.name)
                val temporary = File(directory, "001.tmp").apply { writeBytes(prefix) }
                val faults = ImageRenameFaults(refuseRename = false)
                val page = Page(0, originalSource.baseUrl + "/image/1.png", originalSource.baseUrl + "/image/1.png")
                val download = download(fixture, faultSource, page)
                var unexpected: Throwable? = null
                coroutineScope {
                    val task = launch(Dispatchers.IO) {
                        try {
                            invokeSuspend(
                                downloader(fixture),
                                "getOrDownloadImage",
                                page,
                                download,
                                FaultUniFile(requireNotNull(UniFile.fromFile(directory)), faults),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            unexpected = error
                        }
                    }
                    try {
                        withTimeout(5_000) { closed.await() }
                        // Observe the first fully closed response before cancelling any retry wait.
                        // This cancellation bounds the probe; it is not evidence of response rejection.
                        withTimeoutOrNull(250) { task.join() }
                    } finally {
                        withContext(NonCancellable) { task.cancelAndJoin() }
                    }
                }
                val expectedTemporary = png.copyOf(case.expectedTemporaryLength)
                val temporaryExact = temporary.exists() && temporary.readBytes().contentEquals(expectedTemporary)
                val unpromoted = !File(directory, "001.png").exists() && page.status != Page.State.Ready
                val appendMatches = faults.appendOpens.get() == if (case.validPrefix) 1 else 0
                rows.put(
                    JSONObject().put("case", case.name).put("responses", responses.get())
                        .put("firstResponseClosedBeforeProbeCancellation", closed.isCompleted)
                        .put(
                            "appendOpens",
                            faults.appendOpens.get(),
                        ).put("expectedAppendOpens", if (case.validPrefix) 1 else 0)
                        .put("temporaryExact", temporaryExact).put("unpromoted", unpromoted)
                        .put("unexpectedFailure", unexpected != null)
                        .put("passed", unexpected == null && temporaryExact && unpromoted && appendMatches),
                )
            }
            report.put("cases", rows).put("networkResponseFaultInjection", true)
                .put("actualServerSent206", false).put("retryWaitCancelledAfterObservation", true)
                .put("terminalRetryErrorPathProven", false)
            emit(report)
            assertTrue(
                "DL_FAULT_PARTIAL_RESPONSE_CONTRACT",
                (0 until rows.length()).all {
                    rows.getJSONObject(it).getBoolean("passed")
                },
            )
        }
    }

    @Test(timeout = 60_000)
    fun validPartialAndIgnoredRangeResponsesProduceExactCompleteImages(): Unit = runBlocking {
        withFixture("validPartialAndIgnoredRangeResponsesProduceExactCompleteImages") { fixture, report ->
            val originalSource = source(fixture)
            val png = png(fixture)
            val rows = JSONArray()
            for (partial in listOf(true, false)) {
                val transport = originalSource.client.newBuilder().addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    check(response.request.header("Range") == "bytes=8-")
                    response.close()
                    val body = if (partial) png.copyOfRange(8, png.size) else png
                    response.newBuilder().code(if (partial) 206 else 200).header("Content-Length", body.size.toString())
                        .apply {
                            if (partial) {
                                header(
                                    "Content-Range",
                                    "bytes 8-${png.lastIndex}/${png.size}",
                                )
                            } else {
                                removeHeader("Content-Range")
                            }
                        }.body(body.toResponseBody("image/png".toMediaType())).build()
                }.build()
                val directory = caseDirectory(fixture, if (partial) "valid-range" else "ignored-range")
                File(directory, "001.tmp").writeBytes(png.copyOf(8))
                val page = Page(0, originalSource.baseUrl + "/image/1.png", originalSource.baseUrl + "/image/1.png")
                invokeSuspend(
                    downloader(fixture),
                    "getOrDownloadImage",
                    page,
                    download(fixture, sourceWithClient(originalSource, transport), page),
                    requireNotNull(UniFile.fromFile(directory)),
                )
                val saved = File(directory, "001.png")
                assertEquals("DL_FAULT_VALID_READY", Page.State.Ready, page.status)
                assertArrayEquals("DL_FAULT_VALID_IMAGE_BYTES", png, saved.readBytes())
                assertFalse("DL_FAULT_VALID_NO_TEMP", File(directory, "001.tmp").exists())
                rows.put(JSONObject().put("status", if (partial) 206 else 200).put("completeBytesExact", true))
            }
            report.put("cases", rows).put("networkResponseFaultInjection", true).put("actualServerSent206", false)
        }
    }

    @Test(timeout = 60_000)
    fun pauseThenStartNeverRequestsReplacementBeforePreviousWriterDrains(): Unit = runBlocking {
        pauseStartCase("pauseThenStartNeverRequestsReplacementBeforePreviousWriterDrains", restarts = 1)
    }

    @Test(timeout = 60_000)
    fun repeatedPauseStartRetainsTheFirstWriterUntilItDrains(): Unit = runBlocking {
        pauseStartCase("repeatedPauseStartRetainsTheFirstWriterUntilItDrains", restarts = 3)
    }

    @Test(timeout = 60_000)
    fun unknownRangeTotalFallsBackToACompleteResponseBeforeReady(): Unit = runBlocking {
        withFixture("unknownRangeTotalFallsBackToACompleteResponseBeforeReady") { fixture, report ->
            val originalSource = source(fixture)
            val png = png(fixture)
            val count = AtomicInteger()
            val client = originalSource.client.newBuilder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                when (count.incrementAndGet()) {
                    1 -> {
                        check(response.request.header("Range") == "bytes=8-")
                        response.close()
                        val body = png.copyOfRange(8, png.size)
                        response.newBuilder().code(206).header("Content-Range", "bytes 8-${png.lastIndex}/*")
                            .header("Content-Length", body.size.toString())
                            .body(body.toResponseBody("image/png".toMediaType())).build()
                    }
                    2 -> {
                        check(response.request.header("Range") == null)
                        response
                    }
                    else -> {
                        response.close()
                        throw IOException("Unexpected neutral fallback retry")
                    }
                }
            }.build()
            val directory = caseDirectory(fixture, "unknown-total")
            File(directory, "001.tmp").writeBytes(png.copyOf(8))
            val page = Page(0, originalSource.baseUrl + "/image/1.png", originalSource.baseUrl + "/image/1.png")
            invokeSuspend(
                downloader(fixture),
                "getOrDownloadImage",
                page,
                download(fixture, sourceWithClient(originalSource, client), page),
                requireNotNull(UniFile.fromFile(directory)),
            )
            assertEquals("DL_FAULT_UNKNOWN_TOTAL_FULL_RETRY", 2, count.get())
            assertEquals("DL_FAULT_UNKNOWN_TOTAL_READY_AFTER_FULL", Page.State.Ready, page.status)
            assertArrayEquals("DL_FAULT_UNKNOWN_TOTAL_COMPLETE_BYTES", png, File(directory, "001.png").readBytes())
            report.put("httpImageRequests", count.get()).put("unknownTotalIsValidPartial", true)
                .put("fullResponseRequestedBeforePromotion", true).put("completeBytesExact", true)
                .put("networkResponseFaultInjection", true).put("actualServerSent206", false)
        }
    }

    private suspend fun pauseStartCase(method: String, restarts: Int) {
        withFixture(method) { fixture, report ->
            val originalSource = source(fixture)
            val gate = CountDownLatch(1)
            val entered = CompletableDeferred<Unit>()
            val replacement = CompletableDeferred<Boolean>()
            val requests = AtomicInteger()
            val previous = AtomicReference<Job>()
            val client = originalSource.client.newBuilder().addNetworkInterceptor { chain ->
                val attempt = requests.incrementAndGet()
                if (attempt > 1) replacement.complete(previous.get()?.isCompleted == true)
                val response = chain.proceed(chain.request())
                if (attempt != 1) return@addNetworkInterceptor response
                val body = response.body
                val gated = object : ForwardingSource(body.source()) {
                    private var first = true
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (first) {
                            first = false
                            entered.complete(Unit)
                            if (!gate.await(10, TimeUnit.SECONDS)) throw IOException("Neutral body gate expired")
                        }
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(wrappedBody(body, gated)).build()
            }.build()
            val faultSource = sourceWithClient(originalSource, client)
            val manager = field(fixture, "manager") as DownloadManager
            val owner = downloader(fixture)
            val registration = OnlineReaderSourceRegistration(field(fixture, "sources") as SourceManager, faultSource)
            val manga = (field(fixture, "manga") as Manga).copy(source = faultSource.id)
            val chapter = field(fixture, "chapter") as Chapter
            try {
                manager.downloadChapters(manga, listOf(chapter), autoStart = false)
                assertTrue("DL_FAULT_FIRST_START", manager.downloaderStart())
                withTimeout(5_000) { entered.await() }
                val firstJob = field(owner, "downloaderJob") as Job
                previous.set(firstJob)
                assertFalse("DL_FAULT_OLD_WRITER_HELD", firstJob.isCompleted)
                var resumed = false
                repeat(restarts) {
                    manager.pauseDownloads()
                    resumed = manager.downloaderStart()
                }
                val observedWhileHeld = if (resumed) withTimeoutOrNull(750) { replacement.await() } else null
                report.put("replacementRequestedWhileOldBodyHeld", observedWhileHeld != null)
                    .put("pauseStartPairs", restarts)
                    .put("initialResumeAccepted", resumed).put("httpImageRequests", requests.get())
                    .put("oldJobCompletedAtReplacementRequest", observedWhileHeld ?: JSONObject.NULL)
                    .put("publicQueuePauseStart", true).put("bodyGateControlled", true)
                emit(report)
                gate.countDown()
                withTimeout(5_000) { firstJob.join() }
                assertFalse("DL_FAULT_REPLACEMENT_MUST_WAIT_FOR_OLD_WRITER", observedWhileHeld == false)
                if (!resumed) assertTrue("DL_FAULT_START_AFTER_DRAIN", manager.downloaderStart())
                withTimeout(10_000) {
                    while (manager.queueState.value.isNotEmpty() || manager.isRunning) delay(10)
                }
                val oldCompletedAtReplacement = if (replacement.isCompleted) replacement.await() else true
                report.put("httpImageRequests", requests.get())
                    .put("oldJobCompletedAtReplacementRequest", oldCompletedAtReplacement)
                    .put("oldJobJoined", firstJob.isCompleted).put("completedQueueEmpty", true)
                    .put("publicQueuePauseStart", true).put("bodyGateControlled", true)
                emit(report)
                assertTrue("DL_FAULT_REPLACEMENT_MUST_WAIT_FOR_OLD_WRITER", oldCompletedAtReplacement)
            } finally {
                gate.countDown()
                withContext(NonCancellable) {
                    manager.pauseDownloads()
                    val scope = field(owner, "scope") as CoroutineScope
                    withTimeout(10_000) { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
                }
                registration.close()
            }
        }
    }

    @Test(timeout = 60_000)
    fun cachedRenameFailurePreservesCacheAndDoesNotMarkReady(): Unit = runBlocking {
        withFixture("cachedRenameFailurePreservesCacheAndDoesNotMarkReady") { fixture, report ->
            val server = field(fixture, "server")
            val source = field(server, "source") as HttpSource

            @Suppress("UNCHECKED_CAST")
            val png = (field(server, "pages") as List<ByteArray>).first()
            val url = source.baseUrl + "/image/1.png"
            val cache = field(fixture, "chapterCache") as ChapterCache
            cache.putImageToCache(
                url,
                Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(png.toResponseBody("image/png".toMediaType())).build(),
            )
            val cached = cache.getImageFile(url)
            assertTrue("DL_FAULT_CACHE_PREPARED", cache.isImageInCache(url))
            assertArrayEquals("DL_FAULT_CACHE_INPUT", png, cached.readBytes())
            val directory = File(field(fixture, "root") as File, "rename-fault").apply { check(mkdir()) }
            val sibling = File(directory, "unrelated.bin").apply { writeBytes(byteArrayOf(4, 8, 15, 16)) }
            val faults = ImageRenameFaults()
            val page = Page(0, url, url)
            val download = Download(source, field(fixture, "manga") as Manga, field(fixture, "chapter") as Chapter)
                .apply { pages = listOf(page) }
            invokeSuspend(
                downloader(fixture),
                "getOrDownloadImage",
                page,
                download,
                FaultUniFile(requireNotNull(UniFile.fromFile(directory)), faults),
            )
            val cachePreserved = cached.exists() && cached.readBytes().contentEquals(png)
            report.put("renameRefused", faults.refusals == 1)
                .put("cachedBytesPreserved", cachePreserved)
                .put("pageReady", page.status == Page.State.Ready)
                .put("pageError", page.status is Page.State.Error)
                .put("httpImageRequests", (field(server, "requests") as Collection<*>).size)
            emit(report)
            assertTrue("DL_FAULT_RENAME_REACHED", faults.refusals == 1)
            assertArrayEquals("DL_FAULT_UNRELATED_BYTES", byteArrayOf(4, 8, 15, 16), sibling.readBytes())
            assertTrue("DL_FAULT_RENAME_MUST_KEEP_VALID_CACHE", cachePreserved)
            assertFalse("DL_FAULT_RENAME_MUST_NOT_MARK_READY", page.status == Page.State.Ready)
            assertTrue("DL_FAULT_RENAME_MUST_REPORT_PAGE_ERROR", page.status is Page.State.Error)
        }
    }

    private suspend fun withFixture(
        method: String,
        block: suspend (DownloaderEndToEndFixture, JSONObject) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        check(InstrumentationRegistry.getArguments().getString("downloaderFaultRegression") == "true") {
            "Explicit downloaderFaultRegression opt-in required"
        }
        NativeDiagnosticIdleGuard.requireIdle(target)
        val report = JSONObject().put("testMethod", method).put("terminal", false).put("passed", false)
            .put("actualDownloaderBoundary", true).put("actualActivity", false).put("nativeCalls", 0)
            .put("liveSiteOrTranslationServerCalls", 0)
        val fixture = DownloaderEndToEndFixture(target, archive = false)
        var failure: Throwable? = null
        try {
            fixture.prepare()
            block(fixture, report)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    fixture.close()
                    report.put("fixtureClosed", true)
                } catch (cleanup: Throwable) {
                    report.put("fixtureClosed", false)
                    if (failure == null || cleanup is VirtualMachineError || cleanup is ThreadDeath ||
                        cleanup is LinkageError
                    ) {
                        throw cleanup
                    } else {
                        failure.addSuppressed(cleanup)
                    }
                } finally {
                    report.put("terminal", true).put("passed", failure == null && report.optBoolean("fixtureClosed"))
                    emit(report)
                }
            }
        }
    }

    private data class RangeCase(
        val name: String,
        val range: String?,
        val body: ByteArray,
        val validPrefix: Boolean,
        val expectedTemporaryLength: Int = if (validPrefix) 16 else 8,
        val declaredLength: Long? = null,
    )

    private class ImageRenameFaults(private val refuseRename: Boolean = true) {
        var refusals = 0
        val appendOpens = AtomicInteger()
        fun rename(file: UniFile, name: String): Boolean {
            if (refuseRename && file.name == "001.tmp" && name == "001.png") {
                refusals++
                return false
            }
            return file.renameTo(name)
        }
    }

    private class FaultUniFile(private val delegate: UniFile, private val faults: ImageRenameFaults) :
        UniFile(delegate.parentFile) {
        private fun wrap(file: UniFile?): UniFile? = file?.let { FaultUniFile(it, faults) }
        override fun createFile(name: String): UniFile? = wrap(delegate.createFile(name))
        override fun createDirectory(name: String): UniFile? = wrap(delegate.createDirectory(name))
        override fun getUri(): Uri = delegate.uri
        override fun getName(): String? = delegate.name
        override fun getType(): String? = delegate.type
        override fun getFilePath(): String? = delegate.filePath
        override fun isDirectory(): Boolean = delegate.isDirectory
        override fun isFile(): Boolean = delegate.isFile
        override fun lastModified(): Long = delegate.lastModified()
        override fun length(): Long = delegate.length()
        override fun canRead(): Boolean = delegate.canRead()
        override fun canWrite(): Boolean = delegate.canWrite()
        override fun delete(): Boolean = delegate.delete()
        override fun exists(): Boolean = delegate.exists()
        override fun listFiles(): Array<UniFile>? = delegate.listFiles()?.map {
            requireNotNull(wrap(it))
        }?.toTypedArray()
        override fun listFiles(filter: FilenameFilter): Array<UniFile>? =
            delegate.listFiles(filter)?.map { requireNotNull(wrap(it)) }?.toTypedArray()
        override fun findFile(name: String): UniFile? = wrap(delegate.findFile(name))
        override fun renameTo(name: String): Boolean = faults.rename(delegate, name)
        override fun openOutputStream(): OutputStream = delegate.openOutputStream()
        override fun openOutputStream(append: Boolean): OutputStream {
            if (append) faults.appendOpens.incrementAndGet()
            return delegate.openOutputStream(append)
        }
        override fun openInputStream(): InputStream = delegate.openInputStream()
        override fun createRandomAccessFile(mode: String): UniRandomAccessFile = delegate.createRandomAccessFile(mode)
    }

    private companion object {
        fun field(owner: Any, name: String): Any = requireNotNull(
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner),
        )

        fun downloader(fixture: DownloaderEndToEndFixture): Downloader =
            field(field(fixture, "manager"), "downloader") as Downloader

        fun source(fixture: DownloaderEndToEndFixture): HttpSource = field(
            field(fixture, "server"),
            "source",
        ) as HttpSource

        @Suppress("UNCHECKED_CAST")
        fun png(
            fixture: DownloaderEndToEndFixture,
        ): ByteArray = (field(field(fixture, "server"), "pages") as List<ByteArray>).first()

        fun caseDirectory(fixture: DownloaderEndToEndFixture, name: String): File =
            File(field(fixture, "root") as File, name).apply { check(mkdir()) }

        fun download(fixture: DownloaderEndToEndFixture, source: HttpSource, page: Page) =
            Download(source, field(fixture, "manga") as Manga, field(fixture, "chapter") as Chapter).apply {
                pages =
                    listOf(page)
            }

        fun sourceWithClient(original: HttpSource, transport: OkHttpClient): HttpSource = object : HttpSource() {
            override val name = "Neutral downloader fault ${UUID.randomUUID()}"
            override val lang = "ja"
            override val baseUrl = original.baseUrl
            override val supportsLatest = false
            override val client = transport
            override fun headersBuilder() = Headers.Builder().add("User-Agent", "Yomitori neutral downloader fault")
            override suspend fun getPageList(chapter: eu.kanade.tachiyomi.source.model.SChapter) =
                listOf(Page(0, "$baseUrl/image/1.png", "$baseUrl/image/1.png"))
        }

        fun wrappedBody(body: ResponseBody, source: BufferedSource): ResponseBody = object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }

        fun bodyWithLength(bytes: ByteArray, declaredLength: Long?): ResponseBody = object : ResponseBody() {
            private val input = Buffer().write(bytes)
            override fun contentType() = "image/png".toMediaType()
            override fun contentLength() = declaredLength ?: bytes.size.toLong()
            override fun source(): BufferedSource = input
        }

        fun observeClose(body: ResponseBody, closed: CompletableDeferred<Unit>): ResponseBody =
            wrappedBody(
                body,
                object : ForwardingSource(body.source()) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            closed.complete(Unit)
                        }
                    }
                }.buffer(),
            )

        suspend fun invokeSuspend(owner: Any, name: String, vararg arguments: Any): Any? =
            suspendCoroutineUninterceptedOrReturn { continuation ->
                val method = owner.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }
                try {
                    method.invoke(owner, *arguments, continuation)
                } catch (error: InvocationTargetException) {
                    throw requireNotNull(error.cause)
                }
            }

        fun emit(report: JSONObject) {
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply { putString("downloaderFaultRegressionEvidence", report.toString()) },
            )
        }
    }
}
