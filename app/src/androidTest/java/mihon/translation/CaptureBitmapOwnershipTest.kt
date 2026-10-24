package mihon.translation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.presentation.capture.awaitOwnedCaptureBitmap
import eu.kanade.presentation.capture.launchOwnedCaptureBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

/** Real Android Bitmap ownership at capture callback boundaries; no Activity, PixelCopy or storage. */
@RunWith(AndroidJUnit4::class)
class CaptureBitmapOwnershipTest {
    @Test(timeout = 10_000)
    fun normalConsumerKeepsBitmapUntilItsSuccessfulRelease() = withFixture { fixture ->
        var saves = 0
        var successUi = 0
        val job = fixture.scope.launchOwnedCaptureBitmap(fixture.bitmap) {
            assertFalse("The consumer must receive a live captured bitmap", fixture.bitmap.isRecycled)
            assertEquals(Color.BLUE, fixture.bitmap.getPixel(0, 0))
            saves++
            successUi++
            fixture.releaseAtExistingSuccessSite()
        }
        assertFalse("Scheduling must not release a pending frame", fixture.bitmap.isRecycled)

        fixture.dispatcher.runUntilIdle()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(1, saves)
        assertEquals(1, successUi)
        assertEquals(1, fixture.successSiteReleases)
        assertTrue(fixture.bitmap.isRecycled)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test(timeout = 10_000)
    fun frameArrivingInAnAlreadyCancelledScopeIsReleasedWithoutSaving() = withFixture { fixture ->
        fixture.scope.cancel()
        var saves = 0
        var successUi = 0
        val job = fixture.scope.launchOwnedCaptureBitmap(fixture.bitmap) {
            saves++
            successUi++
            fixture.releaseAtExistingSuccessSite()
        }

        fixture.dispatcher.runUntilIdle()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertEquals(0, saves)
        assertEquals(0, successUi)
        assertEquals(0, fixture.successSiteReleases)
        assertTrue(fixture.failures.isEmpty())
        assertTrue(
            "A late captured frame must be released even when the coroutine never starts",
            fixture.bitmap.isRecycled,
        )
    }

    @Test(timeout = 10_000)
    fun cancellationWhileConsumerIsSuspendedReleasesBitmapWithoutSuccessUi() = withFixture { fixture ->
        val saveGate = CompletableDeferred<Unit>()
        var started = 0
        var successUi = 0
        val job = fixture.scope.launchOwnedCaptureBitmap(fixture.bitmap) {
            started++
            saveGate.await()
            successUi++
            fixture.releaseAtExistingSuccessSite()
        }
        fixture.dispatcher.runUntilIdle()
        assertEquals(1, started)
        assertFalse("A suspended consumer still owns its frame", fixture.bitmap.isRecycled)

        job.cancel()
        fixture.dispatcher.runUntilIdle()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertEquals(0, successUi)
        assertEquals(0, fixture.successSiteReleases)
        assertTrue("Cancellation must keep its coroutine cancellation semantics", fixture.failures.isEmpty())
        assertTrue("Cancelled capture persistence must release its owned frame", fixture.bitmap.isRecycled)
    }

    @Test(timeout = 10_000)
    fun consumerFailureRetainsItsExceptionAndReleasesBitmapWithoutSuccessUi() = withFixture { fixture ->
        val saveGate = CompletableDeferred<Unit>()
        val failure = IllegalStateException("Neutral controlled capture failure")
        var started = 0
        var successUi = 0
        val job = fixture.scope.launchOwnedCaptureBitmap(fixture.bitmap) {
            started++
            saveGate.await()
            if (started == 1) throw failure
            successUi++
            fixture.releaseAtExistingSuccessSite()
        }
        fixture.dispatcher.runUntilIdle()
        assertEquals(1, started)
        assertFalse(fixture.bitmap.isRecycled)

        saveGate.complete(Unit)
        fixture.dispatcher.runUntilIdle()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertEquals(0, successUi)
        assertEquals(0, fixture.successSiteReleases)
        assertEquals(1, fixture.failures.size)
        assertSame("Release must not replace the original consumer exception", failure, fixture.failures.single())
        assertTrue("Failed capture persistence must release its owned frame", fixture.bitmap.isRecycled)
    }

    @Test(timeout = 10_000)
    fun resumedFrameCancelledBeforeDeliveryIsReleasedWithoutBeingConsumed() = withFixture { fixture ->
        var onFrame: ((Bitmap?) -> Unit)? = null
        var consumed = 0
        val job = fixture.scope.launch {
            val frame = awaitOwnedCaptureBitmap { onFrame = it }
            consumed++
            assertSame(fixture.bitmap, frame)
        }
        fixture.dispatcher.runUntilIdle()
        assertNotNull(onFrame)

        checkNotNull(onFrame)(fixture.bitmap)
        assertEquals("Resume is queued; the consumer must not have run yet", 0, consumed)
        assertFalse(fixture.bitmap.isRecycled)
        job.cancel()
        fixture.dispatcher.runUntilIdle()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertEquals(0, consumed)
        assertTrue(fixture.failures.isEmpty())
        assertTrue("Prompt cancellation must release a frame that the caller never received", fixture.bitmap.isRecycled)
    }

    @Test(timeout = 10_000)
    fun successfullyDeliveredFrameRemainsOwnedByItsCaller() = withFixture { fixture ->
        var onFrame: ((Bitmap?) -> Unit)? = null
        var delivered: Bitmap? = null
        val job = fixture.scope.launch {
            delivered = awaitOwnedCaptureBitmap { onFrame = it }
            assertFalse(
                "A delivered frame must remain live while its caller uses it",
                checkNotNull(delivered).isRecycled,
            )
        }
        fixture.dispatcher.runUntilIdle()
        assertNotNull(onFrame)

        checkNotNull(onFrame)(fixture.bitmap)
        fixture.dispatcher.runUntilIdle()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertSame(fixture.bitmap, delivered)
        assertFalse("Successful hand-off must not recycle the caller-owned bitmap", fixture.bitmap.isRecycled)
        assertEquals(Color.BLUE, checkNotNull(delivered).getPixel(0, 0))
        assertTrue(fixture.failures.isEmpty())
    }

    @Test(timeout = 10_000)
    fun frameArrivingAfterAwaitCancellationIsReleasedWithoutDelivery() = withFixture { fixture ->
        var onFrame: ((Bitmap?) -> Unit)? = null
        var consumed = 0
        val job = fixture.scope.launch {
            awaitOwnedCaptureBitmap { onFrame = it }
            consumed++
        }
        fixture.dispatcher.runUntilIdle()
        assertNotNull(onFrame)
        job.cancel()
        fixture.dispatcher.runUntilIdle()
        assertTrue(job.isCancelled)

        checkNotNull(onFrame)(fixture.bitmap)

        assertEquals(0, consumed)
        assertTrue(fixture.bitmap.isRecycled)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test(timeout = 10_000)
    fun uiConsumerWithoutManualReleaseKeepsBitmapUntilJobCompletion() = withFixture { fixture ->
        val saveGate = CompletableDeferred<Unit>()
        var successUi = 0
        val job = fixture.scope.launchOwnedCaptureBitmap(fixture.bitmap) {
            assertFalse("The UI consumer must receive a live captured bitmap", fixture.bitmap.isRecycled)
            saveGate.await()
            successUi++
            assertFalse("The frame must remain live through the consumer's final statement", fixture.bitmap.isRecycled)
        }
        fixture.dispatcher.runUntilIdle()
        assertFalse(job.isCompleted)
        assertFalse("A suspended consumer still owns its frame", fixture.bitmap.isRecycled)

        saveGate.complete(Unit)
        fixture.dispatcher.runUntilIdle()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(1, successUi)
        assertEquals("The UI consumer no longer performs manual release", 0, fixture.successSiteReleases)
        assertTrue("Job completion must release a normally consumed frame", fixture.bitmap.isRecycled)
        assertTrue(fixture.failures.isEmpty())
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.scope.cancel()
            fixture.dispatcher.runUntilIdle()
            // Baseline failures still clean up their own tiny bitmap; the assertion ran first.
            if (!fixture.bitmap.isRecycled) fixture.bitmap.recycle()
        }
    }

    private class Fixture {
        val bitmap: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val dispatcher = QueuedDispatcher()
        val failures = mutableListOf<Throwable>()
        val scope =
            CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> failures += error })
        var successSiteReleases = 0
            private set

        fun releaseAtExistingSuccessSite() {
            if (!bitmap.isRecycled) {
                successSiteReleases++
                bitmap.recycle()
            }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runUntilIdle() {
            repeat(64) {
                if (queue.isEmpty()) return
                queue.removeFirst().run()
            }
            fail("The controlled capture fixture did not settle within its bounded dispatcher queue")
        }
    }
}
