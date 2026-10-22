package eu.kanade.tachiyomi.data.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PendingTranslationDispatchTest {
    @Test
    fun stopBeforeCompletionSuppressesEvenAutomaticTranslationUntilExplicitRestart() {
        val dispatch = PendingTranslationDispatch()
        var pending = false
        var enqueued = false
        dispatch.mark(1) { pending = true }
        dispatch.cancel(1, { pending }) { pending = false }
        assertTrue(
            dispatch.onDownloaded(1) {
                enqueued = true
                true
            },
        )
        assertFalse(enqueued)
        dispatch.mark(1) { pending = true }
        assertTrue(
            dispatch.onDownloaded(1) {
                enqueued = pending
                true
            },
        )
        assertTrue(enqueued)
    }

    @Test
    fun stopWaitsForInFlightCompletionThenCancelsItsEnqueue() {
        val dispatch = PendingTranslationDispatch()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)
        val pending = AtomicBoolean(true)
        val enqueued = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val completion = executor.submit {
                dispatch.onDownloaded(1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    enqueued.set(true)
                    pending.set(false)
                    true
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stop = executor.submit {
                cancelStarted.countDown()
                dispatch.cancel(1, pending::get) {
                    pending.set(false)
                    enqueued.set(false)
                }
            }
            assertTrue(cancelStarted.await(5, TimeUnit.SECONDS))
            assertFalse(stop.isDone)
            release.countDown()
            completion.get(5, TimeUnit.SECONDS)
            stop.get(5, TimeUnit.SECONDS)
            assertFalse(enqueued.get())
            assertFalse(pending.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun stopWithoutPendingDoesNotSuppressAnUnrelatedFutureDownload() {
        val dispatch = PendingTranslationDispatch()
        dispatch.cancel(1, { false }) {}
        var invoked = false
        assertFalse(
            dispatch.onDownloaded(1) {
                invoked = true
                false
            },
        )
        assertTrue(invoked)
    }
}
