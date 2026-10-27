package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connect queue stop flags to suspended HTTP work; native work must finish before its input is freed. */
internal suspend fun <T : Any> runTranslationUntilStopped(
    shouldStop: () -> Boolean,
    discard: (T) -> Unit = {},
    block: suspend () -> T,
): T? {
    // Own a produced result until coroutineScope itself returns successfully. Its final
    // cancellation check can reject a result after the body and child cleanup have finished.
    var produced: T? = null
    var delivered = false
    return try {
        val result = coroutineScope {
            if (shouldStop()) return@coroutineScope null
            val work = async { block().also { produced = it } }
            val monitor = launch {
                while (work.isActive) {
                    if (shouldStop()) {
                        work.cancel()
                        break
                    }
                    delay(100)
                }
            }
            try {
                val value = work.await()
                currentCoroutineContext().ensureActive()
                if (shouldStop()) null else value
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive() // Parent cancellation must propagate.
                if (shouldStop()) null else throw cancelled
            } finally {
                monitor.cancel()
                withContext(NonCancellable) {
                    work.cancelAndJoin()
                    monitor.join()
                }
            }
        }
        result.also { delivered = it != null }
    } finally {
        if (!delivered) produced?.let(discard)
    }
}
