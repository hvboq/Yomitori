package eu.kanade.tachiyomi.ui.reader.loader

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP loader/cache and storage with injected processing; no inference, server or Activity. */
@RunWith(AndroidJUnit4::class)
class OnlineSelectedPageLoaderTest {
    @Test
    fun httpPrefetchNeverProcessesUnselectedPagesAndCompletedReselectionDoesNotRepeat() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val calls = CopyOnWriteArrayList<Int>()
            val session = fixture.session(count = 1964) { index, stored ->
                calls += index
                fixture.complete(stored, index)
                PageTranslator.SinglePageResult.TRANSLATED
            }
            val loader = session.loader
            val pages = session.pages
            assertEquals(1964, pages.size)
            val load = launch(Dispatchers.IO) { loader.loadPage(pages[0]) }
            try {
                pages.take(5).forEach { ready(it) }
                assertEquals(setOf(0, 1, 2, 3, 4), session.imageRequests.toSet())
                assertEquals(5, session.imageRequests.size)
                assertTrue(calls.isEmpty())
                assertNull(loader.progress.value)
                assertTrue(pages.all { it.uri == null })

                loader.select(pages[0], translate = true)
                finished(loader, 0)
                await { pages[0].reloadFlow.value > 0 }
                assertEquals(listOf(0), calls.toList())
                val first = checkNotNull(loader.resolvePage(pages[0]))
                val directory = File(checkNotNull(first.chapterDir.uri.path))
                val original = OnlineSelectedPageFixture.original(0)
                assertArrayEquals(original, File(directory, "source.bin").readBytes())
                assertArrayEquals(
                    original,
                    File(directory, ".yakuyomi/${OriginalMaterial.exactName(checkNotNull(first.pageFile.name))}")
                        .readBytes(),
                )
                val display = checkNotNull(pages[0].stream).invoke().use { it.readBytes() }
                assertArrayEquals(File(checkNotNull(first.pageFile.uri.path)).readBytes(), display)
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(display, 0, display.size))
                try {
                    assertEquals(Color.rgb(20, 60, 120), bitmap.getPixel(0, 0))
                } finally {
                    bitmap.recycle()
                }
                assertTrue(pages.drop(1).all { it.uri == null })

                loader.select(pages[1], translate = true)
                finished(loader, 1)
                await { pages[1].reloadFlow.value > 0 }
                assertEquals(listOf(0, 1), calls.toList())
                loader.select(pages[0], translate = true)
                finished(loader, 0)
                OnlineSelectedPageFixture.settled(loader)
                assertEquals(listOf(0, 1), calls.toList())
                assertEquals(first.pageFile.uri, checkNotNull(loader.resolvePage(pages[0])).pageFile.uri)
                assertTrue(pages.drop(2).all { it.uri == null })
                assertEquals(5, session.imageRequests.size)
            } finally {
                load.cancelAndJoin()
            }
        }
    }

    @Test
    fun rapidSelectionCancelsAndJoinsBeforeOnlyTheLatestPageStarts() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val calls = CopyOnWriteArrayList<Int>()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val firstEntered = CompletableDeferred<Job>()
            val firstExited = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val session = fixture.session(
                count = 3,
                limits = OnlineSelectedPageFixture.loaderLimits(entries = 1),
            ) { index, stored ->
                val concurrent = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, concurrent) }
                calls += index
                try {
                    if (index == 0) {
                        firstEntered.complete(checkNotNull(currentCoroutineContext()[Job]))
                        // Simulates a JNI call which cannot finish until its owner drains it.
                        withContext(NonCancellable) { releaseFirst.await() }
                    } else {
                        assertTrue(firstExited.isCompleted)
                        fixture.complete(stored, index)
                    }
                    PageTranslator.SinglePageResult.TRANSLATED
                } finally {
                    active.decrementAndGet()
                    if (index == 0) firstExited.complete(Unit)
                }
            }
            val loader = session.loader
            val pages = session.pages
            val load = launch(Dispatchers.IO) { loader.loadPage(pages[0]) }
            try {
                pages.forEach { ready(it) }
                loader.select(pages[0], translate = true)
                val oldJob = withTimeout(5000) { firstEntered.await() }
                val old = checkNotNull(loader.resolvePage(pages[0]))
                val oldDirectory = File(checkNotNull(old.chapterDir.uri.path))
                loader.select(pages[1], translate = true)
                loader.select(pages[2], translate = true)
                await { oldJob.isCancelled }
                assertEquals(listOf(0), calls.toList())
                assertEquals(1, active.get())
                assertEquals(1, maximum.get())
                assertTrue(oldDirectory.exists())
                assertArrayEquals(
                    OnlineSelectedPageFixture.original(0),
                    File(oldDirectory, "source.bin").readBytes(),
                )
                releaseFirst.complete(Unit)
                finished(loader, 2)
                OnlineSelectedPageFixture.settled(loader)
                assertEquals(listOf(0, 2), calls.toList())
                assertEquals(1, maximum.get())
                assertEquals(0, active.get())
                assertNull(loader.resolvePage(pages[1]))
                assertEquals(0, pages[0].reloadFlow.value)
                assertEquals(2, checkNotNull(loader.progress.value).pageIndex)
                assertNotNull(loader.resolvePage(pages[2]))
            } finally {
                releaseFirst.complete(Unit)
                load.cancelAndJoin()
            }
        }
    }

    @Test
    fun explicitStopDrainsWorkAndRequiresRetryWhileCompletedReselectionStaysIdle() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val attempts = AtomicInteger()
            val firstEntered = CompletableDeferred<Job>()
            val releaseFirst = CompletableDeferred<Unit>()
            val session = fixture.session(count = 2) { _, stored ->
                if (attempts.incrementAndGet() == 1) {
                    firstEntered.complete(checkNotNull(currentCoroutineContext()[Job]))
                    withContext(NonCancellable) { releaseFirst.await() }
                } else {
                    fixture.complete(stored, 0)
                }
                PageTranslator.SinglePageResult.TRANSLATED
            }
            val loader = session.loader
            val pages = session.pages
            val load = launch(Dispatchers.IO) { loader.loadPage(pages[0]) }
            try {
                pages.forEach { ready(it) }
                loader.select(pages[0], translate = true)
                val oldJob = withTimeout(5000) { firstEntered.await() }
                loader.stop()
                val drained = async(Dispatchers.IO) { loader.stopAndJoin(keepSelection = true) }
                await { oldJob.isCancelled }
                assertFalse(drained.isCompleted)
                releaseFirst.complete(Unit)
                withTimeout(5000) { drained.await() }
                assertNull(loader.progress.value)
                val captured = checkNotNull(loader.resolvePage(pages[0]))
                assertArrayEquals(
                    OnlineSelectedPageFixture.original(0),
                    File(checkNotNull(captured.pageFile.uri.path)).readBytes(),
                )
                loader.select(pages[0], translate = true)
                OnlineSelectedPageFixture.settled(loader)
                assertEquals(1, attempts.get())
                assertNull(loader.progress.value)

                loader.select(pages[0], translate = true, explicitRetry = true)
                finished(loader, 0)
                await { pages[0].reloadFlow.value > 0 }
                assertEquals(2, attempts.get())
                loader.select(pages[1], translate = false)
                OnlineSelectedPageFixture.settled(loader)
                assertNull(loader.progress.value)
                loader.select(pages[0], translate = true)
                finished(loader, 0)
                OnlineSelectedPageFixture.settled(loader)
                assertEquals(2, attempts.get())
            } finally {
                releaseFirst.complete(Unit)
                load.cancelAndJoin()
            }
        }
    }

    @Test
    fun completedPageEvictionAllowsAutomaticProcessingAgainOnRevisit() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val calls = CopyOnWriteArrayList<Int>()
            val session = fixture.session(
                count = 2,
                limits = OnlineSelectedPageFixture.loaderLimits(entries = 1),
            ) { index, stored ->
                calls += index
                fixture.complete(stored, index)
                PageTranslator.SinglePageResult.TRANSLATED
            }
            val loader = session.loader
            val pages = session.pages
            val load = launch(Dispatchers.IO) { loader.loadPage(pages[0]) }
            try {
                pages.forEach { ready(it) }
                loader.select(pages[0], translate = true)
                finished(loader, 0)
                OnlineSelectedPageFixture.settled(loader)
                val first = checkNotNull(loader.resolvePage(pages[0]))
                val firstDirectory = File(checkNotNull(first.chapterDir.uri.path))
                loader.select(pages[1], translate = true)
                finished(loader, 1)
                OnlineSelectedPageFixture.settled(loader)
                assertFalse("A completed unpinned authority must be evictable", firstDirectory.exists())
                assertNull(loader.resolvePage(pages[0]))
                loader.select(pages[0], translate = true)
                finished(loader, 0)
                OnlineSelectedPageFixture.settled(loader)
                assertEquals(listOf(0, 1, 0), calls.toList())
                val revisited = checkNotNull(loader.resolvePage(pages[0]))
                assertEquals(first.pageFile.uri, revisited.pageFile.uri)
                assertArrayEquals(
                    OnlineSelectedPageFixture.original(0),
                    File(checkNotNull(revisited.chapterDir.uri.path), "source.bin").readBytes(),
                )
            } finally {
                load.cancelAndJoin()
            }
        }
    }

    @Test
    fun recycleWhileStopIsDrainingAcknowledgesOnlyAfterTheOwnedCallEnds() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val entered = CompletableDeferred<Job>()
            val release = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val session = fixture.session(count = 1) { _, _ ->
                calls.incrementAndGet()
                entered.complete(checkNotNull(currentCoroutineContext()[Job]))
                try {
                    withContext(NonCancellable) { release.await() }
                    PageTranslator.SinglePageResult.TRANSLATED
                } finally {
                    exited.complete(Unit)
                }
            }
            val loader = session.loader
            val page = session.pages.single()
            val load = launch(Dispatchers.IO) { loader.loadPage(page) }
            try {
                ready(page)
                loader.select(page, translate = true)
                val ownedJob = withTimeout(5000) { entered.await() }
                val drained = async(Dispatchers.IO) { loader.stopAndJoin() }
                await { ownedJob.isCancelled }
                assertFalse(drained.isCompleted)
                // Prevent the delegate's global page-list writer, which is outside this test.
                page.chapter.state = ReaderChapter.State.Wait
                loader.recycle()
                assertFalse(drained.isCompleted)
                assertFalse(exited.isCompleted)
                release.complete(Unit)
                withTimeout(5000) { drained.await() }
                assertTrue(exited.isCompleted)
                assertEquals(1, calls.get())
                assertNull(loader.progress.value)
                assertEquals(0, page.reloadFlow.value)
                withTimeout(5000) { loader.stopAndJoin() }
            } finally {
                release.complete(Unit)
                load.cancelAndJoin()
            }
        }
    }

    @Test
    fun manualOperationHandoffThenRecycleDrainsBeforeTheNextLoaderCanProcess() = runBlocking {
        OnlineSelectedPageFixture.run { firstFixture ->
            OnlineSelectedPageFixture.run { nextFixture ->
                val active = AtomicInteger()
                val maximum = AtomicInteger()
                val manualCalls = AtomicInteger()
                val nextCalls = AtomicInteger()
                val entered = CompletableDeferred<Unit>()
                val exited = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val first = firstFixture.session(count = 1) { _, _ ->
                    throw AssertionError("Capture-only selection must not start translation")
                }
                val next = nextFixture.session(count = 1) { index, stored ->
                    val concurrent = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, concurrent) }
                    nextCalls.incrementAndGet()
                    try {
                        assertTrue(exited.isCompleted)
                        nextFixture.complete(stored, index)
                        PageTranslator.SinglePageResult.TRANSLATED
                    } finally {
                        active.decrementAndGet()
                    }
                }
                val firstPage = first.pages.single()
                val nextPage = next.pages.single()
                val firstLoad = launch(Dispatchers.IO) { first.loader.loadPage(firstPage) }
                val nextLoad = launch(Dispatchers.IO) { next.loader.loadPage(nextPage) }
                try {
                    ready(firstPage)
                    ready(nextPage)
                    first.loader.select(firstPage, translate = false)
                    await { first.loader.resolvePage(firstPage) != null }
                    OnlineSelectedPageFixture.settled(first.loader)
                    val stored = checkNotNull(first.loader.resolvePage(firstPage))
                    val directory = File(checkNotNull(stored.chapterDir.uri.path))
                    val manual = async(Dispatchers.IO) {
                        first.loader.withPageOperation(firstPage) {
                            val concurrent = active.incrementAndGet()
                            maximum.updateAndGet { maxOf(it, concurrent) }
                            manualCalls.incrementAndGet()
                            entered.complete(Unit)
                            try {
                                withContext(NonCancellable) { release.await() }
                                false
                            } finally {
                                active.decrementAndGet()
                                exited.complete(Unit)
                            }
                        }
                    }
                    withTimeout(5000) { entered.await() }
                    val drained = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        first.loader.stopAndJoin(keepSelection = false)
                    }
                    // The Stop has left the command queue and now waits on the manual mutex.
                    OnlineSelectedPageFixture.settled(first.loader)
                    val continued = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        drained.await()
                        next.loader.select(nextPage, translate = true)
                        finished(next.loader, 0)
                    }
                    assertFalse(drained.isCompleted)
                    assertFalse(continued.isCompleted)
                    assertEquals(0, nextCalls.get())
                    firstPage.chapter.state = ReaderChapter.State.Wait
                    first.loader.recycle()
                    assertFalse(drained.isCompleted)
                    assertFalse(exited.isCompleted)
                    assertEquals(1, active.get())
                    assertArrayEquals(
                        OnlineSelectedPageFixture.original(0),
                        File(directory, "source.bin").readBytes(),
                    )
                    release.complete(Unit)
                    assertFalse(withTimeout(5000) { manual.await() })
                    withTimeout(5000) { drained.await() }
                    withTimeout(10000) { continued.await() }
                    OnlineSelectedPageFixture.settled(next.loader)
                    assertEquals(1, manualCalls.get())
                    assertEquals(1, nextCalls.get())
                    assertEquals(1, maximum.get())
                    assertEquals(0, active.get())
                    assertNull(first.loader.progress.value)
                    assertEquals(0, firstPage.reloadFlow.value)
                } finally {
                    release.complete(Unit)
                    firstLoad.cancelAndJoin()
                    nextLoad.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun manualOperationArrivingAfterChapterHandoffNeverRunsItsBlock() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val calls = AtomicInteger()
            val session = fixture.session(count = 1) { _, _ ->
                throw AssertionError("Capture-only selection must not start translation")
            }
            val loader = session.loader
            val page = session.pages.single()
            val load = launch(Dispatchers.IO) { loader.loadPage(page) }
            try {
                ready(page)
                loader.select(page, translate = false)
                await { loader.resolvePage(page) != null }
                OnlineSelectedPageFixture.settled(loader)
                val stored = checkNotNull(loader.resolvePage(page))
                withTimeout(5000) { loader.stopAndJoin(keepSelection = false) }
                assertFalse(
                    loader.withPageOperation(page) {
                        calls.incrementAndGet()
                        true
                    },
                )
                assertEquals(0, calls.get())
                assertNull(loader.progress.value)
                assertArrayEquals(
                    OnlineSelectedPageFixture.original(0),
                    File(checkNotNull(stored.pageFile.uri.path)).readBytes(),
                )
            } finally {
                load.cancelAndJoin()
            }
        }
    }

    private suspend fun ready(page: ReaderPage) {
        val state = withTimeout(5000) {
            page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
        }
        if (state is Page.State.Error) throw AssertionError("Scripted page image failed: $state")
    }

    private suspend fun finished(loader: OnlineTranslatedPageLoader, pageIndex: Int) {
        val state = withTimeout(10000) {
            loader.progress.first {
                it != null && it.pageIndex == pageIndex &&
                    (
                        it.stage == OnlineTranslatedPageLoader.Stage.FINISHED ||
                            it.stage == OnlineTranslatedPageLoader.Stage.FAILED
                        )
            }
        }
        assertEquals(OnlineTranslatedPageLoader.Stage.FINISHED, checkNotNull(state).stage)
        assertEquals(PageTranslator.SinglePageResult.TRANSLATED, state.result)
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) {
        while (!condition()) delay(5)
    }
}
