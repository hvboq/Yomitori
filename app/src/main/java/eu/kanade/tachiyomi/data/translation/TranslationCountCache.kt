package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owns one scan generation per manga; blocking filesystem scans may finish after cancellation. */
internal class TranslationCountCache<T>(
    private val scope: CoroutineScope,
    private val scan: suspend (T) -> Int,
    private val onError: (Exception) -> Unit,
) {
    private class Scan {
        lateinit var job: Job
    }

    private val lock = Any()
    private val counts = mutableMapOf<Long, Int>()
    private val scanning = mutableMapOf<Long, Scan>()
    private val mutableChanges = MutableStateFlow(0)
    val changes = mutableChanges.asStateFlow()

    fun get(id: Long, input: T): Int {
        val pending = synchronized(lock) {
            counts[id]?.let { return it }
            if (id in scanning) return 0
            val token = Scan()
            // Register ownership before starting, including with an immediate/unconfined dispatcher.
            token.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val count = scan(input)
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (scanning[id] === token) {
                            counts[id] = count
                            if (count > 0) mutableChanges.update { it + 1 }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onError(error)
                }
            }
            scanning[id] = token
            // Also runs when a lazy scan is cancelled before its coroutine body/finally can start.
            token.job.invokeOnCompletion {
                synchronized(lock) {
                    if (scanning[id] === token) scanning.remove(id)
                }
            }
            token
        }
        pending.job.start()
        return 0
    }

    fun invalidate(id: Long) {
        val previous = synchronized(lock) {
            counts.remove(id)
            scanning.remove(id).also { mutableChanges.update { it + 1 } }
        }
        // Cancellation can run completion handlers; do it after relinquishing the state lock.
        previous?.job?.cancel()
    }

    fun invalidateAll() {
        val previous = synchronized(lock) {
            counts.clear()
            scanning.values.toList().also {
                scanning.clear()
                mutableChanges.update { it + 1 }
            }
        }
        previous.forEach { it.job.cancel() }
    }
}
