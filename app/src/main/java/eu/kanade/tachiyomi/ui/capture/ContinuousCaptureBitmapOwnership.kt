package eu.kanade.tachiyomi.ui.capture

import android.graphics.Bitmap

/**
 * Consumes a frame already delivered to continuous capture and releases it when consumption ends,
 * including cancellation or failure. The returned value must not retain the frame.
 */
internal suspend fun <T> consumeOwnedContinuousCaptureBitmap(
    bitmap: Bitmap,
    consume: suspend (Bitmap) -> T,
): T = try {
    consume(bitmap)
} finally {
    if (!bitmap.isRecycled) bitmap.recycle()
}
