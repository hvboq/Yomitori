package mihon.translation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.capture.CaptureSaveError
import eu.kanade.tachiyomi.ui.capture.CaptureSaveResult
import eu.kanade.tachiyomi.ui.capture.consumeOwnedContinuousCaptureBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

/** Delivered-frame ownership with controlled work; no ViewModel loop, PixelCopy, native OCR or storage. */
@RunWith(AndroidJUnit4::class)
class ContinuousCaptureBitmapOwnershipTest {
    @Test(timeout = 10_000)
    fun normalComparisonReleasesBeforeItsIndependentResultIsConsumed() = withFixture { fixture ->
        var thumbnailReads = 0
        var continued = 0
        var returned: IntArray? = null
        val job = fixture.scope.launch {
            returned = consumeOwnedContinuousCaptureBitmap(fixture.bitmap) { frame ->
                withContext(fixture.workerDispatcher) {
                    assertFalse("Comparison must borrow a live delivered frame", frame.isRecycled)
                    thumbnailReads++
                    intArrayOf(frame.getPixel(0, 0))
                }
            }
            assertTrue("Comparison releases before the caller advances its state", fixture.bitmap.isRecycled)
            continued++
        }

        fixture.drain()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(1, thumbnailReads)
        assertEquals(1, continued)
        assertArrayEquals(intArrayOf(Color.BLUE), checkNotNull(returned))
        assertTrue(fixture.bitmap.isRecycled)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test(timeout = 10_000)
    fun cancelledComparisonReleasesDeliveredFrameWithoutAdvancingState() = withFixture { fixture ->
        val workGate = CompletableDeferred<Unit>()
        val cancellation = CancellationException("Neutral controlled comparison cancellation")
        var entered = 0
        var workerExited = 0
        var continued = 0
        var completionCause: Throwable? = null
        val job = fixture.scope.launch {
            consumeOwnedContinuousCaptureBitmap(fixture.bitmap) { frame ->
                withContext(fixture.workerDispatcher) {
                    entered++
                    try {
                        workGate.await()
                        intArrayOf(frame.getPixel(0, 0))
                    } finally {
                        assertFalse("Comparison must finish borrowing before release", frame.isRecycled)
                        workerExited++
                    }
                }
            }
            continued++
        }
        job.invokeOnCompletion { completionCause = it }
        fixture.drain()
        assertEquals(1, entered)
        assertEquals(0, workerExited)
        assertFalse(fixture.bitmap.isRecycled)

        job.cancel(cancellation)
        fixture.drain()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertSame(cancellation, completionCause)
        assertEquals(1, workerExited)
        assertEquals(0, continued)
        assertTrue(fixture.failures.isEmpty())
        assertTrue("Cancelled comparison must release its delivered frame", fixture.bitmap.isRecycled)
    }

    @Test(timeout = 10_000)
    fun failedComparisonPreservesItsCauseAndReleasesDeliveredFrame() = withFixture { fixture ->
        val workGate = CompletableDeferred<Unit>()
        val failure = IllegalStateException("Neutral controlled comparison failure")
        var entered = 0
        var continued = 0
        val job = fixture.scope.launch {
            consumeOwnedContinuousCaptureBitmap(fixture.bitmap) { frame ->
                withContext(fixture.workerDispatcher) {
                    entered++
                    workGate.await()
                    assertFalse(frame.isRecycled)
                    if (entered == 1) throw failure
                    intArrayOf(frame.getPixel(0, 0))
                }
            }
            continued++
        }
        fixture.drain()
        assertEquals(1, entered)
        assertFalse(fixture.bitmap.isRecycled)

        workGate.complete(Unit)
        fixture.drain()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertEquals(0, continued)
        assertEquals(1, fixture.failures.size)
        assertSame(failure, fixture.failures.single())
        assertTrue("Failed comparison must release its delivered frame", fixture.bitmap.isRecycled)
    }

    @Test(timeout = 10_000)
    fun discardedCleanConsumerReturnsNullAndReleasesBeforeCallerContinues() = withFixture { fixture ->
        val discardBeforeSave = { true }
        var thumbnailReads = 0
        var urlReads = 0
        var saves = 0
        var continued = 0
        var returned: CaptureSaveResult? = CaptureSaveResult.MissingName
        val job = fixture.scope.launch {
            returned = consumeOwnedContinuousCaptureBitmap(fixture.bitmap) { frame ->
                withContext(fixture.workerDispatcher) {
                    assertFalse(frame.isRecycled)
                    thumbnailReads++
                    frame.getPixel(0, 0)
                }
                // This controls the discard path, not the production blank-detection algorithm.
                if (discardBeforeSave()) {
                    null
                } else {
                    urlReads++
                    saves++
                    CaptureSaveResult.Saved(1, "neutral-capture-result")
                }
            }
            assertTrue("Discard releases before loop-state continuation", fixture.bitmap.isRecycled)
            continued++
        }

        fixture.drain()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertNull(returned)
        assertEquals(1, thumbnailReads)
        assertEquals(0, urlReads)
        assertEquals(0, saves)
        assertEquals(1, continued)
        assertTrue(fixture.bitmap.isRecycled)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test(timeout = 10_000)
    fun cleanResultsKeepTheirIdentityAndReleaseBeforeCallerUpdates() {
        val results = listOf(
            CaptureSaveResult.Saved(1, "neutral-capture-result"),
            CaptureSaveResult.Failed(CaptureSaveError.WRITE, "Neutral controlled save result"),
            CaptureSaveResult.MissingName,
        )
        for (original in results) {
            withFixture { fixture ->
                var thumbnailReads = 0
                var urlReads = 0
                var saves = 0
                var successCount = 0
                var returned: CaptureSaveResult? = null
                val job = fixture.scope.launch {
                    returned = consumeOwnedContinuousCaptureBitmap(fixture.bitmap) { frame ->
                        withContext(fixture.workerDispatcher) {
                            assertFalse(frame.isRecycled)
                            thumbnailReads++
                            frame.getPixel(0, 0)
                        }
                        urlReads++
                        withContext(fixture.workerDispatcher) {
                            assertFalse("Persistence only borrows the delivered frame", frame.isRecycled)
                            saves++
                            original
                        }
                    }
                    assertTrue("Release precedes save-result state changes", fixture.bitmap.isRecycled)
                    if (returned is CaptureSaveResult.Saved) successCount++
                }

                fixture.drain()

                assertTrue(job.isCompleted)
                assertFalse(job.isCancelled)
                assertSame("Capture save results must not be replaced", original, returned)
                assertEquals(1, thumbnailReads)
                assertEquals(1, urlReads)
                assertEquals(1, saves)
                assertEquals(if (original is CaptureSaveResult.Saved) 1 else 0, successCount)
                assertTrue(fixture.bitmap.isRecycled)
                assertTrue(fixture.failures.isEmpty())
            }
        }
    }

    @Test(timeout = 10_000)
    fun cancelledCleanPersistenceReleasesFrameWithoutCommitOrSuccessCount() = withFixture { fixture ->
        val saveGate = CompletableDeferred<Unit>()
        val cancellation = CancellationException("Neutral controlled clean-frame cancellation")
        var thumbnailReads = 0
        var urlReads = 0
        var saveEntered = 0
        var saveExited = 0
        var committed = 0
        var successCount = 0
        var completionCause: Throwable? = null
        val job = fixture.scope.launch {
            val result: CaptureSaveResult = consumeOwnedContinuousCaptureBitmap(fixture.bitmap) { frame ->
                withContext(fixture.workerDispatcher) {
                    assertFalse(frame.isRecycled)
                    thumbnailReads++
                    frame.getPixel(0, 0)
                }
                urlReads++
                withContext(fixture.workerDispatcher) {
                    saveEntered++
                    try {
                        saveGate.await()
                        committed++
                        CaptureSaveResult.Saved(1, "neutral-capture-result")
                    } finally {
                        assertFalse("Persistence must finish borrowing before release", frame.isRecycled)
                        saveExited++
                    }
                }
            }
            if (result is CaptureSaveResult.Saved) successCount++
        }
        job.invokeOnCompletion { completionCause = it }
        fixture.drain()
        assertEquals(1, thumbnailReads)
        assertEquals(1, urlReads)
        assertEquals(1, saveEntered)
        assertEquals(0, saveExited)
        assertFalse(fixture.bitmap.isRecycled)

        job.cancel(cancellation)
        fixture.drain()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertSame(cancellation, completionCause)
        assertEquals(1, saveExited)
        assertEquals(0, committed)
        assertEquals(0, successCount)
        assertTrue(fixture.failures.isEmpty())
        assertTrue("Cancelled clean persistence must release its delivered frame", fixture.bitmap.isRecycled)
    }

    @Test(timeout = 10_000)
    fun failedUrlProviderReleasesCleanFrameAndPreservesItsCause() = withFixture { fixture ->
        val failure = IllegalStateException("Neutral controlled URL-provider failure")
        var thumbnailReads = 0
        var urlReads = 0
        var saves = 0
        var successCount = 0
        val urlProvider: () -> String? = {
            urlReads++
            throw failure
        }
        val job = fixture.scope.launch {
            val result: CaptureSaveResult = consumeOwnedContinuousCaptureBitmap(fixture.bitmap) { frame ->
                withContext(fixture.workerDispatcher) {
                    assertFalse(frame.isRecycled)
                    thumbnailReads++
                    frame.getPixel(0, 0)
                }
                val url = urlProvider()
                saves++
                CaptureSaveResult.Saved(1, url ?: "neutral-capture-result")
            }
            if (result is CaptureSaveResult.Saved) successCount++
        }

        fixture.drain()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertEquals(1, thumbnailReads)
        assertEquals(1, urlReads)
        assertEquals(0, saves)
        assertEquals(0, successCount)
        assertEquals(1, fixture.failures.size)
        assertSame(failure, fixture.failures.single())
        assertTrue("Failed URL acquisition must release its delivered clean frame", fixture.bitmap.isRecycled)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.scope.cancel()
            fixture.drain()
            // Baseline failures still release the fixture's tiny bitmap after the assertion.
            if (!fixture.bitmap.isRecycled) fixture.bitmap.recycle()
        }
    }

    private class Fixture {
        val bitmap: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val ownerDispatcher = QueuedDispatcher()
        val workerDispatcher = QueuedDispatcher()
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + ownerDispatcher + CoroutineExceptionHandler { _, error -> failures += error },
        )

        fun drain() {
            repeat(64) {
                if (ownerDispatcher.runNextIfPresent()) return@repeat
                if (workerDispatcher.runNextIfPresent()) return@repeat
                return
            }
            fail("The controlled continuous-capture queues did not settle within their fixed bound")
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runNextIfPresent(): Boolean {
            if (queue.isEmpty()) return false
            queue.removeFirst().run()
            return true
        }
    }
}
