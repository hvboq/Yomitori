package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Thread-safe policy only: no Android calls, model loading, native closing, or blocking waits.
 * A background transition starts one grace period, including when UI_HIDDEN precedes ON_STOP.
 * New work cancels an old idle decision. Foreground inactivity starts after all work finishes;
 * a background deadline already reached while busy is applied when the current work drains.
 * Pressure arriving during work is retained until the final operation completes. An explicit
 * shutdown survives foreground/new-work events; an idle or pressure decision does not.
 */
internal class IdleModelReleaseController(
    private val scope: CoroutineScope,
    private val release: (Request) -> Unit,
    private val grace: Duration = BACKGROUND_GRACE,
    private val foregroundIdle: Duration = FOREGROUND_IDLE,
) {
    internal enum class Reason { BACKGROUND, FOREGROUND_IDLE, MEMORY_PRESSURE, EXPLICIT }
    internal data class Request(
        val generation: Long,
        val reason: Reason,
        val completed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val lock = Any()
    private var foreground = false
    private var operations = 0
    private var generation = 0L
    private var timer: Job? = null
    private var pending: Request? = null

    fun onForeground() = synchronized(lock) {
        foreground = true
        invalidateIdleLocked()
        scheduleIdleLocked()
    }

    fun onBackground() = synchronized(lock) {
        if (foreground) {
            foreground = false
            invalidateIdleLocked()
        }
        scheduleIdleLocked()
    }

    fun onWorkStarted() = synchronized(lock) {
        invalidateIdleLocked()
        operations++
    }

    fun onWorkFinished() {
        val ready = synchronized(lock) {
            check(operations > 0) { "Unbalanced engine operation" }
            operations--
            if (operations != 0) return@synchronized null
            pending ?: run {
                scheduleIdleLocked()
                null
            }
        }
        ready?.let(release)
    }

    fun request(reason: Reason): Request {
        val (request, ready) = synchronized(lock) {
            cancelTimerLocked()
            // A trim callback must not weaken a user/queue's explicit shutdown request.
            val requested = pending?.takeIf { it.reason == Reason.EXPLICIT }
                ?: Request(++generation, reason).also { pending = it }
            requested to (operations == 0)
        }
        if (ready) release(request)
        return request
    }

    /**
     * Called under the service's lifecycle mutex. [detach] only clears references/state; it MUST
     * NOT close native resources or suspend. Foreground/new-work invalidation and detachment
     * therefore have a definite order without making a main-thread callback wait for native close.
     */
    fun <T> consume(request: Request, detach: () -> T): T? = synchronized(lock) {
        if (pending != request || operations != 0) return@synchronized null
        pending = null
        generation++
        cancelTimerLocked()
        detach()
    }

    private fun invalidateIdleLocked() {
        cancelTimerLocked()
        if (pending?.reason != Reason.EXPLICIT) {
            generation++
            pending = null
        }
    }

    private fun cancelTimerLocked() {
        timer?.cancel()
        timer = null
    }

    private fun scheduleIdleLocked() {
        if (timer != null || pending != null) return
        if (foreground && operations > 0) return
        val expected = ++generation
        val reason = if (foreground) Reason.FOREGROUND_IDLE else Reason.BACKGROUND
        val timeout = if (foreground) foregroundIdle else grace
        timer = scope.launch {
            delay(timeout)
            val ready = synchronized(lock) {
                if (generation != expected) return@synchronized null
                timer = null
                val request = Request(expected, reason)
                pending = request
                request.takeIf { operations == 0 }
            }
            ready?.let(release)
        }
    }

    companion object {
        val BACKGROUND_GRACE = 60.seconds
        val FOREGROUND_IDLE = 5.minutes
    }
}
