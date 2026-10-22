package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class IdleModelReleaseControllerTest {
    private class Rig(scope: TestScope) {
        val requests = mutableListOf<IdleModelReleaseController.Request>()
        val controller = IdleModelReleaseController(scope.backgroundScope, release = { requests += it })
        var closes = 0

        fun consume(request: IdleModelReleaseController.Request = requests.last()) =
            controller.consume(request) { ++closes }
    }

    @Test
    fun `UI hidden then process stop shares one background deadline`() = runTest {
        val r = Rig(this)
        r.controller.onForeground()
        r.controller.onBackground() // UI_HIDDEN
        runCurrent()
        advanceTimeBy(30.seconds)
        r.controller.onBackground() // process ON_STOP must not restart the grace
        advanceTimeBy(29.seconds)
        runCurrent()
        r.requests.shouldBeEmpty()
        advanceTimeBy(1.seconds)
        runCurrent()
        r.requests.single().reason shouldBe IdleModelReleaseController.Reason.BACKGROUND
        r.consume() shouldBe 1
        advanceTimeBy(10.minutes)
        runCurrent()
        r.requests.size shouldBe 1
    }

    @Test
    fun `foreground before background deadline cancels it and starts longer foreground idle TTL`() = runTest {
        val r = Rig(this)
        r.controller.onBackground()
        runCurrent()
        advanceTimeBy(59.seconds)
        r.controller.onForeground()
        runCurrent()
        advanceTimeBy(299.seconds)
        runCurrent()
        r.requests.shouldBeEmpty()
        advanceTimeBy(1.seconds)
        runCurrent()
        r.requests.single().reason shouldBe IdleModelReleaseController.Reason.FOREGROUND_IDLE
    }

    @Test
    fun `foreground invalidates a release already dispatched but waiting for service mutex`() = runTest {
        val r = Rig(this)
        r.controller.onBackground()
        runCurrent()
        advanceTimeBy(60.seconds)
        runCurrent()
        val stale = r.requests.single()
        r.controller.onForeground()
        r.consume(stale) shouldBe null
        r.closes shouldBe 0
    }

    @Test
    fun `new page cancels dispatched idle request and restarts inactivity after completion`() = runTest {
        val r = Rig(this)
        r.controller.onForeground()
        runCurrent()
        advanceTimeBy(5.minutes)
        runCurrent()
        val stale = r.requests.single()
        r.controller.onWorkStarted()
        r.consume(stale) shouldBe null
        advanceTimeBy(10.minutes)
        runCurrent()
        r.requests.size shouldBe 1 // No periodic timer during active inference.
        r.controller.onWorkFinished()
        runCurrent()
        advanceTimeBy(299.seconds)
        runCurrent()
        r.requests.size shouldBe 1
        advanceTimeBy(1.seconds)
        runCurrent()
        r.requests.size shouldBe 2
        r.consume() shouldBe 1
    }

    @Test
    fun `grace expiry during two active pages waits for both completions`() = runTest {
        val r = Rig(this)
        r.controller.onForeground()
        r.controller.onWorkStarted()
        r.controller.onWorkStarted()
        r.controller.onBackground()
        runCurrent()
        advanceTimeBy(120.seconds)
        runCurrent()
        r.requests.shouldBeEmpty()
        r.controller.onWorkFinished()
        r.requests.shouldBeEmpty()
        r.controller.onWorkFinished()
        r.requests.single().reason shouldBe IdleModelReleaseController.Reason.BACKGROUND
        r.consume() shouldBe 1
    }

    @Test
    fun `memory pressure during warmup is retained and dispatched on final completion`() = runTest {
        val r = Rig(this)
        r.controller.onForeground()
        r.controller.onWorkStarted()
        val request = r.controller.request(IdleModelReleaseController.Reason.MEMORY_PRESSURE)
        r.consume(request) shouldBe null
        r.requests.shouldBeEmpty()
        r.controller.onWorkFinished()
        r.requests.single() shouldBe request
        r.consume() shouldBe 1
        r.consume(request) shouldBe null // Duplicate IO dispatch cannot close a later generation.
    }

    @Test
    fun `new foreground use invalidates old pressure request`() = runTest {
        val r = Rig(this)
        r.controller.onWorkStarted()
        val old = r.controller.request(IdleModelReleaseController.Reason.MEMORY_PRESSURE)
        r.controller.onForeground()
        r.controller.onWorkFinished()
        r.requests.shouldBeEmpty()
        r.consume(old) shouldBe null
    }

    @Test
    fun `explicit shutdown survives foreground new work and later trim requests`() = runTest {
        val r = Rig(this)
        r.controller.onWorkStarted()
        val explicit = r.controller.request(IdleModelReleaseController.Reason.EXPLICIT)
        r.controller.onForeground()
        r.controller.onWorkStarted()
        r.controller.request(IdleModelReleaseController.Reason.MEMORY_PRESSURE) shouldBe explicit
        r.controller.onWorkFinished()
        r.requests.shouldBeEmpty()
        r.controller.onWorkFinished()
        r.requests.single() shouldBe explicit
        r.consume() shouldBe 1
    }

    @Test
    fun `foreground transition during active work starts full inactivity TTL after completion`() = runTest {
        val r = Rig(this)
        r.controller.onWorkStarted()
        r.controller.onForeground()
        runCurrent()
        advanceTimeBy(4.minutes)
        r.controller.onWorkFinished()
        runCurrent()
        advanceTimeBy(299.seconds)
        runCurrent()
        r.requests.shouldBeEmpty()
        advanceTimeBy(1.seconds)
        runCurrent()
        r.requests.single().reason shouldBe IdleModelReleaseController.Reason.FOREGROUND_IDLE
    }

    @Test
    fun `foreground warmup longer than idle TTL still receives full inactivity after it ends`() = runTest {
        val r = Rig(this)
        r.controller.onWorkStarted()
        r.controller.onForeground()
        runCurrent()
        advanceTimeBy(6.minutes)
        runCurrent()
        r.requests.shouldBeEmpty()
        r.controller.onWorkFinished()
        runCurrent()
        advanceTimeBy(299.seconds)
        runCurrent()
        r.requests.shouldBeEmpty()
        advanceTimeBy(1.seconds)
        runCurrent()
        r.consume() shouldBe 1
    }

    @Test
    fun `background page after stale pressure uses fresh background grace`() = runTest {
        val r = Rig(this)
        val old = r.controller.request(IdleModelReleaseController.Reason.MEMORY_PRESSURE)
        r.controller.onWorkStarted()
        r.consume(old) shouldBe null
        r.controller.onWorkFinished()
        runCurrent()
        advanceTimeBy(60.seconds)
        runCurrent()
        r.requests.last().reason shouldBe IdleModelReleaseController.Reason.BACKGROUND
        r.consume() shouldBe 1
    }
}
