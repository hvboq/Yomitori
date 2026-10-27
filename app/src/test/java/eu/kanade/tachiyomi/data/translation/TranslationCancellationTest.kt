package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TranslationCancellationTest {
    @Test
    fun `queue stop cancels suspended translation without returning a result`() = runTest {
        var stop = false
        var cancelled = false
        val job = async {
            runTranslationUntilStopped<String>({ stop }) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        runCurrent()
        stop = true
        advanceTimeBy(101)
        job.await() shouldBe null
        cancelled shouldBe true
    }

    @Test
    fun `parent cancellation propagates`() = runTest {
        var propagated = false
        val job = async {
            try {
                runTranslationUntilStopped<String>({ false }) { awaitCancellation() }
            } catch (cancelled: CancellationException) {
                propagated = true
                throw cancelled
            }
        }
        runCurrent()
        job.cancelAndJoin()
        propagated shouldBe true
    }

    @Test
    fun `result produced while stopping is discarded`() = runTest {
        var stop = false
        var discarded = ""
        var discardCalls = 0
        runTranslationUntilStopped({ stop }, {
            discarded = it
            discardCalls++
        }) {
            stop = true
            "bitmap"
        } shouldBe null
        discarded shouldBe "bitmap"
        discardCalls shouldBe 1
    }

    @Test
    fun `completed work is delivered once and already stopped work never starts`() = runTest {
        runTranslationUntilStopped({ false }, { error("Unexpected discard") }) { "ok" } shouldBe "ok"
        runTranslationUntilStopped({ true }) { error("Unexpected work") } shouldBe null
    }

    @Test
    fun `parent cancellation after result selection discards undelivered result once`() = runTest {
        val value = Any()
        var produced = false
        var finalStopChecks = 0
        var discarded = 0
        var delivered = false
        var cancellationPropagated = false
        val job = async {
            val owner = checkNotNull(currentCoroutineContext()[Job])
            try {
                runTranslationUntilStopped(
                    shouldStop = {
                        if (produced) {
                            finalStopChecks++
                            owner.cancel()
                        }
                        false
                    },
                    discard = {
                        check(it === value)
                        discarded++
                    },
                ) {
                    produced = true
                    value
                }
                delivered = true
            } catch (cancelled: CancellationException) {
                cancellationPropagated = true
                throw cancelled
            }
        }
        job.join()
        produced shouldBe true
        finalStopChecks shouldBe 1
        job.isCancelled shouldBe true
        cancellationPropagated shouldBe true
        delivered shouldBe false
        discarded shouldBe 1
    }

    @Test
    fun `selection failure discards produced result once and preserves the failure`() = runTest {
        val value = Any()
        val failure = IllegalStateException("Selection failed")
        var produced = false
        var discarded = 0
        val outcome = runCatching {
            runTranslationUntilStopped(
                shouldStop = {
                    if (produced) throw failure
                    false
                },
                discard = {
                    check(it === value)
                    discarded++
                },
            ) {
                produced = true
                value
            }
        }
        outcome.exceptionOrNull() shouldBe failure
        discarded shouldBe 1
    }

    @Test
    fun `work failure without a result never calls discard`() = runTest {
        val failure = IllegalStateException("Work failed")
        var discarded = 0
        val outcome = runCatching {
            runTranslationUntilStopped<String>({ false }, { discarded++ }) { throw failure }
        }
        outcome.exceptionOrNull() shouldBe failure
        discarded shouldBe 0
    }
}
