package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
        runTranslationUntilStopped({ stop }, { discarded = it }) {
            stop = true
            "bitmap"
        } shouldBe null
        discarded shouldBe "bitmap"
    }

    @Test
    fun `completed work is delivered once and already stopped work never starts`() = runTest {
        runTranslationUntilStopped({ false }, { error("Unexpected discard") }) { "ok" } shouldBe "ok"
        runTranslationUntilStopped({ true }) { error("Unexpected work") } shouldBe null
    }
}
