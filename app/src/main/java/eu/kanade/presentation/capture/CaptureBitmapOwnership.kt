package eu.kanade.presentation.capture

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Takes ownership of a newly captured bitmap until the consumer's job, including its children,
 * completes. A completion handler also runs if cancellation prevents the body from ever starting.
 * Consumers must not retain the bitmap beyond this job; early release remains harmless.
 */
internal fun CoroutineScope.launchOwnedCaptureBitmap(
    bitmap: Bitmap,
    consume: suspend CoroutineScope.() -> Unit,
): Job {
    val job = try {
        launch(block = consume)
    } catch (failure: Throwable) {
        bitmap.recycleCapturedIfNeeded()
        throw failure
    }
    job.invokeOnCompletion { bitmap.recycleCapturedIfNeeded() }
    return job
}

/** A delivered frame belongs to its caller; cancellation releases only an undelivered frame. */
internal suspend fun awaitOwnedCaptureBitmap(
    requestFrame: (onResult: (Bitmap?) -> Unit) -> Unit,
): Bitmap? = suspendCancellableCoroutine { continuation ->
    requestFrame { bitmap ->
        if (continuation.isActive) {
            continuation.resume(bitmap) { _, undelivered, _ ->
                undelivered?.recycleCapturedIfNeeded()
            }
        } else {
            bitmap?.recycleCapturedIfNeeded()
        }
    }
}

private fun Bitmap.recycleCapturedIfNeeded() {
    if (!isRecycled) recycle()
}
