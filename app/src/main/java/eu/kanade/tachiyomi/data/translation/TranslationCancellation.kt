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
): T? = coroutineScope {
    if (shouldStop()) return@coroutineScope null
    var produced: T? = null
    var delivered = false
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
        val result = work.await()
        currentCoroutineContext().ensureActive()
        if (shouldStop()) null else result.also { delivered = true }
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive() // Parent cancellation must propagate.
        if (shouldStop()) null else throw cancelled
    } finally {
        monitor.cancel()
        withContext(NonCancellable) {
            work.cancelAndJoin()
            monitor.join()
        }
        if (!delivered) produced?.let(discard)
    }
}
