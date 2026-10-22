package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationCountCacheTest {
    @Test
    fun `pending scan is deduplicated and zero is cached without extra notification`() = runTest {
        val answer = CompletableDeferred<Int>()
        var calls = 0
        val cache = TranslationCountCache<Unit>(backgroundScope, {
            calls++
            answer.await()
        }, { throw it })
        repeat(3) { cache.get(1L, Unit) shouldBe 0 }
        runCurrent()
        calls shouldBe 1
        answer.complete(0)
        runCurrent()
        repeat(3) { cache.get(1L, Unit) shouldBe 0 }
        runCurrent()
        calls shouldBe 1
        cache.changes.value shouldBe 0
    }

    @Test
    fun `scan failure clears its ownership and next get retries`() = runTest {
        var calls = 0
        val errors = mutableListOf<Exception>()
        val cache = TranslationCountCache<Unit>(backgroundScope, {
            if (++calls == 1) throw IOException("Provider temporarily unavailable")
            4
        }, { errors += it })
        cache.get(1L, Unit) shouldBe 0
        runCurrent()
        calls shouldBe 1
        errors.size shouldBe 1
        cache.changes.value shouldBe 0
        cache.get(1L, Unit) shouldBe 0
        runCurrent()
        cache.get(1L, Unit) shouldBe 4
        calls shouldBe 2
        cache.changes.value shouldBe 1
    }

    @Test
    fun `cancelled scan also allows next get without reporting a filesystem error`() = runTest {
        var calls = 0
        val errors = mutableListOf<Exception>()
        val cache = TranslationCountCache<Unit>(backgroundScope, {
            if (++calls == 1) throw CancellationException("Scan cancelled")
            2
        }, { errors += it })
        cache.get(1L, Unit)
        runCurrent()
        cache.get(1L, Unit)
        runCurrent()
        cache.get(1L, Unit) shouldBe 2
        calls shouldBe 2
        errors.size shouldBe 0
    }

    @Test
    fun `late old completion neither publishes nor removes the new pending scan`() = runTest {
        val old = CompletableDeferred<Int>()
        val fresh = CompletableDeferred<Int>()
        val calls = mutableListOf<String>()
        val cache = TranslationCountCache<String>(backgroundScope, { generation ->
            calls += generation
            // Filesystem providers may keep running even after cancellation is requested.
            withContext(NonCancellable) { if (generation == "old") old.await() else fresh.await() }
        }, { throw it })
        cache.get(1L, "old")
        runCurrent()
        cache.invalidate(1L)
        cache.get(1L, "fresh")
        runCurrent()
        old.complete(99)
        runCurrent()
        cache.get(1L, "unexpected third scan") shouldBe 0
        runCurrent()
        calls shouldBe listOf("old", "fresh")
        cache.changes.value shouldBe 1 // Only invalidation; old result never advertised.
        fresh.complete(3)
        runCurrent()
        cache.get(1L, "unused") shouldBe 3
        cache.changes.value shouldBe 2
    }

    @Test
    fun `old result arriving after fresh publication cannot overwrite it`() = runTest {
        val old = CompletableDeferred<Int>()
        val cache = TranslationCountCache<Boolean>(backgroundScope, { isOld ->
            if (isOld) withContext(NonCancellable) { old.await() } else 7
        }, { throw it })
        cache.get(1L, true)
        runCurrent()
        cache.invalidate(1L)
        cache.get(1L, false)
        runCurrent()
        cache.get(1L, false) shouldBe 7
        old.complete(100)
        runCurrent()
        cache.get(1L, false) shouldBe 7
        cache.changes.value shouldBe 2
    }

    @Test
    fun `invalidate all discards every old generation and permits a zero replacement`() = runTest {
        val old = CompletableDeferred<Int>()
        var calls = 0
        val cache = TranslationCountCache<Boolean>(backgroundScope, { isOld ->
            calls++
            if (isOld) withContext(NonCancellable) { old.await() } else 0
        }, { throw it })
        cache.get(1L, true)
        cache.get(2L, true)
        runCurrent()
        cache.invalidateAll()
        cache.get(1L, false)
        runCurrent()
        old.complete(8)
        runCurrent()
        cache.get(1L, false) shouldBe 0
        cache.get(2L, false) shouldBe 0
        runCurrent()
        calls shouldBe 4
        cache.changes.value shouldBe 1
    }

    @Test
    fun `immediate dispatcher cannot complete before scan ownership is installed`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            var calls = 0
            val cache = TranslationCountCache<Unit>(scope, {
                calls++
                5
            }, { throw it })
            cache.get(1L, Unit) shouldBe 0
            cache.get(1L, Unit) shouldBe 5
            calls shouldBe 1
            cache.changes.value shouldBe 1
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `invalidation before scheduled scan starts does not leave ownership stuck`() = runTest {
        val calls = mutableListOf<String>()
        val cache = TranslationCountCache<String>(backgroundScope, {
            calls += it
            6
        }, { throw it })
        cache.get(1L, "old")
        cache.invalidate(1L)
        cache.get(1L, "fresh")
        runCurrent()
        calls shouldBe listOf("fresh")
        cache.get(1L, "unused") shouldBe 6
        cache.changes.value shouldBe 2
    }
}
