package eu.kanade.tachiyomi.ui.reader.loader

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Public calls with a paused send boundary; real cache/storage, no Activity, native or socket work. */
@RunWith(AndroidJUnit4::class)
class OnlineSelectedPageCommandOrderTest {
    @Test
    fun delayedOlderSelectionDoesNotCancelTheLatestSelection() = commandOrder(stop = false)

    @Test
    fun delayedOlderStopDoesNotCancelTheLatestSelection() = commandOrder(stop = true)

    private fun commandOrder(stop: Boolean) = runBlocking {
        withTimeout(30_000) {
            OnlineSelectedPageFixture.run { fixture ->
                val firstEntered = CompletableDeferred<Unit>()
                val latestEntered = CompletableDeferred<Job>()
                val releaseLatest = CompletableDeferred<Unit>()
                val latestAttempts = AtomicInteger()
                val active = AtomicInteger()
                val maximum = AtomicInteger()
                val session = fixture.session(count = 2) { index, stored ->
                    val concurrent = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, concurrent) }
                    try {
                        if (index == 0) {
                            firstEntered.complete(Unit)
                            awaitCancellation()
                        }
                        latestAttempts.incrementAndGet()
                        latestEntered.complete(checkNotNull(currentCoroutineContext()[Job]))
                        withContext(NonCancellable) { releaseLatest.await() }
                        fixture.complete(stored, index)
                        PageTranslator.SinglePageResult.TRANSLATED
                    } finally {
                        active.decrementAndGet()
                    }
                }
                val loader = session.loader
                val pages = session.pages
                val load = launch(Dispatchers.IO) { loader.loadPage(pages[0]) }
                val commandsField = loader.javaClass.getDeclaredField("commands").apply { isAccessible = true }

                @Suppress("UNCHECKED_CAST")
                val originalCommands = commandsField.get(loader) as Channel<Any>
                val gate = BlockingSendGate(originalCommands)
                var older: Caller? = null
                var latest: Caller? = null
                try {
                    pages.forEach { page ->
                        val status = withTimeout(5000) {
                            page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                        }
                        assertEquals(Page.State.Ready, status)
                    }
                    if (stop) {
                        loader.select(pages[0], translate = true, retryVersion = 11)
                        withTimeout(5000) { firstEntered.await() }
                    }
                    commandsField.set(loader, gate)
                    val oldCaller = Caller {
                        if (stop) loader.stop() else loader.select(pages[0], translate = true, retryVersion = 11)
                    }
                    older = oldCaller
                    await { gate.entered.count == 0L }
                    val newCaller = Caller { loader.select(pages[1], translate = true, retryVersion = 22) }
                    latest = newCaller

                    // With separate state/send critical sections the newer call returns and starts
                    // first. An atomic state/send implementation instead blocks this caller at
                    // select's monitor. Both branches retain the real public calls and Channel FIFO.
                    await { newCaller.done.count == 0L || newCaller.blockedInSelect() }
                    val serialized = newCaller.done.count != 0L
                    if (!serialized) withTimeout(5000) { latestEntered.await() }
                    gate.release.countDown()
                    oldCaller.finish()
                    newCaller.finish()
                    val latestJob = withTimeout(5000) { latestEntered.await() }
                    // In the unsynchronized branch, observe the defect before permitting the
                    // processor to finish, so a fast successful write cannot hide cancellation.
                    if (!serialized) await { latestJob.isCancelled }
                    releaseLatest.complete(Unit)
                    withTimeout(5000) { latestJob.join() }
                    OnlineSelectedPageFixture.settled(loader)

                    // A stranded latest request is also deduplicated on a repeated reader callback.
                    loader.select(pages[1], translate = true, retryVersion = 22)
                    OnlineSelectedPageFixture.settled(loader)
                    val terminal = loader.progress.value
                    val completed = terminal != null && terminal.page === pages[1] && terminal.retryVersion == 22L &&
                        terminal.stage == OnlineTranslatedPageLoader.Stage.FINISHED &&
                        terminal.result == PageTranslator.SinglePageResult.TRANSLATED
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        2,
                        Bundle().apply {
                            putString("selectedPageCommandOrder", if (stop) "stop" else "select")
                            putBoolean("atomicSendBlockedCompetingCaller", serialized)
                            putBoolean("latestStartedBeforeDelayedSend", !serialized)
                            putBoolean("latestCancelled", latestJob.isCancelled)
                            putInt("latestAttempts", latestAttempts.get())
                            putBoolean("terminalMatchesLatestRequest", completed)
                        },
                    )
                    assertFalse("A delayed older command cancelled the latest selection", latestJob.isCancelled)
                    assertTrue("Repeated selection remained stranded without its terminal result", completed)
                    assertEquals(1, latestAttempts.get())
                    assertEquals(1, maximum.get())
                    assertEquals(0, active.get())
                } finally {
                    gate.release.countDown()
                    releaseLatest.complete(Unit)
                    withContext(NonCancellable) {
                        older?.finish()
                        latest?.finish()
                        commandsField.set(loader, originalCommands)
                        load.cancelAndJoin()
                    }
                }
            }
        }
    }

    /** Pauses before the real trySend, without fabricating commands or a successful send result. */
    private class BlockingSendGate(private val delegate: Channel<Any>) : Channel<Any> by delegate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val armed = AtomicBoolean(true)

        override fun trySend(element: Any): ChannelResult<Unit> {
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "Command send gate did not release" }
            }
            return delegate.trySend(element)
        }
    }

    private class Caller(block: () -> Unit) {
        val done = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val thread = Thread {
            try {
                block()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }.apply { start() }

        fun blockedInSelect(): Boolean = thread.state == Thread.State.BLOCKED && thread.stackTrace.any {
            it.className == OnlineTranslatedPageLoader::class.java.name && it.methodName == "select"
        }

        suspend fun finish() = withContext(Dispatchers.IO) {
            thread.join(5000)
            check(!thread.isAlive) { "Command caller did not finish" }
            failure.get()?.let { throw it }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) {
        while (!condition()) delay(5)
    }
}
