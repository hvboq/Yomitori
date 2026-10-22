package mihon.translation

import android.content.Intent
import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.TranslationEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Native process-lifecycle integration and isolated races; no API requests or preference changes. */
@RunWith(AndroidJUnit4::class)
class EngineLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun appProcessStopReleasesNativeModelsAndResumeReacquires() = runBlocking<Unit> {
        val service = Injekt.get<TranslationEngineService>()
        assertTrue("Configured models and provider are required", service.isReady())
        val method = Injekt.get<TranslationPreferences>().liveInpaintMethod.get()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val report = JSONObject().put("startedAtEpochMs", System.currentTimeMillis())
            .put("nativeWarmupOnly", true).put("translationApiUsed", false)
            .put("memoryIsObservationalNoPassThreshold", true)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { it.ready = true }
                processStarted(true)
                service.warmUp(method)
                assertTrue(service.warm.value)
                val first = checkNotNull(engine(service))
                report.put("afterWarm", memory(service))
                // Real Activity -> ProcessLifecycleOwner -> App.onStop -> prewarm adapter -> service.
                // No direct service.onBackground()/App.onStop() invocation in this test.
                report.put("activityStopRequestedElapsedRealtimeMs", SystemClock.elapsedRealtime())
                scenario.moveToState(Lifecycle.State.CREATED)
                val stopped = processStarted(false)
                val stopObservedAt = SystemClock.elapsedRealtime()
                report.put("processStoppedState", stopped.name)
                    .put("processStopObservedElapsedRealtimeMs", stopObservedAt)
                delay(30_000)
                assertTrue("Normal short background transition keeps warm models", service.warm.value)
                report.put("beforeClose", memory(service))
                withTimeout(45_000) {
                    while (service.warm.value || engine(service) != null) delay(100)
                    // warm=false means detached; acquiring the lifecycle mutex also waits until
                    // actual native close finishes, so the next memory sample is after close.
                    (field("mutex").get(service) as Mutex).withLock { Unit }
                }
                report.put("backgroundCloseElapsedMs", SystemClock.elapsedRealtime() - stopObservedAt)
                    .put("afterClose", memory(service))
                scenario.moveToState(Lifecycle.State.RESUMED)
                report.put("processResumedState", processStarted(true).name)
                service.warmUp(method)
                assertTrue(service.warm.value)
                assertNotSame("Released native engine must be recreated", first, engine(service))
                report.put("nativeEngineIdentityChanged", first !== engine(service))
                    .put("afterReacquire", memory(service))
            }
        } finally {
            service.shutdown()
        }
        assertFalse(service.warm.value)
        report.put("afterFinalShutdown", memory(service)).put("passed", true)
        val output = File(context.getExternalFilesDir(null), "engine-lifecycle/result.json")
        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        output.writeText(report.toString(2))
        println("ENGINE_LIFECYCLE_REPORT=${output.absolutePath}")
        println(report.toString(2))
    }

    @Test
    fun suspendingShutdownWaitsForBothPagesAndNativeClose() = runBlocking<Unit> {
        val started = List(2) { CompletableDeferred<Unit>() }
        val finish = List(2) { CompletableDeferred<Unit>() }
        val sequence = AtomicInteger()
        val closeStarted = CompletableDeferred<Unit>()
        val allowClose = CountDownLatch(1)
        val closed = AtomicInteger()
        val fake = object : TranslationEngine {
            override suspend fun translatePage(page: Bitmap): PageResult {
                val index = sequence.getAndIncrement()
                started[index].complete(Unit)
                finish[index].await()
                return skipped("shutdown lifecycle fixture")
            }
            override fun warmUp() = Unit
            override fun close() {
                closeStarted.complete(Unit)
                check(allowClose.await(10, TimeUnit.SECONDS)) { "Test did not release native close" }
                closed.incrementAndGet()
            }
        }
        val service = isolatedService(fake)
        val page = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val calls = List(2) { async(Dispatchers.Default) { service.translatePage(page, "boxfill") } }
            withTimeout(10_000) { started.forEach { it.await() } }
            val shutdown = async(Dispatchers.Default) { service.shutdown() }
            delay(100)
            assertFalse("shutdown must wait for both pages", shutdown.isCompleted)
            finish[0].complete(Unit)
            withTimeout(10_000) { while (calls.count { it.isCompleted } != 1) delay(10) }
            assertFalse("shutdown must still wait for the second page", shutdown.isCompleted)
            finish[1].complete(Unit)
            withTimeout(10_000) {
                calls.forEach { it.await() }
                closeStarted.await()
            }
            assertFalse("Detach alone must not complete shutdown", shutdown.isCompleted)
            assertEquals(0, closed.get())
            allowClose.countDown()
            withTimeout(10_000) { shutdown.await() }
            assertEquals(1, closed.get())
            assertFalse(service.warm.value)
        } finally {
            finish.forEach { it.complete(Unit) }
            allowClose.countDown()
            service.shutdown()
            page.recycle()
        }
    }

    @Test
    fun cancellingShutdownCallerCannotLoseDeferredNativeRelease() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val closeCount = AtomicInteger()
        val fake = object : TranslationEngine {
            override suspend fun translatePage(page: Bitmap): PageResult {
                started.complete(Unit)
                finish.await()
                return skipped("cancelled shutdown caller fixture")
            }
            override fun warmUp() = Unit
            override fun close() {
                closeCount.incrementAndGet()
                closed.complete(Unit)
            }
        }
        val service = isolatedService(fake)
        val page = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val call = async(Dispatchers.Default) { service.translatePage(page, "boxfill") }
            withTimeout(10_000) { started.await() }
            // UNDISPATCHED registers the explicit request before its first IO suspension.
            val shutdown = async(start = CoroutineStart.UNDISPATCHED) { service.shutdown() }
            shutdown.cancelAndJoin()
            assertEquals(0, closeCount.get())
            finish.complete(Unit)
            assertTrue(call.await() is PageResult.Skipped)
            withTimeout(10_000) { closed.await() }
            assertEquals(1, closeCount.get())
            assertFalse(service.warm.value)
        } finally {
            finish.complete(Unit)
            service.shutdown()
            page.recycle()
        }
    }

    @Test
    fun trimDuringConcurrentPagesReturnsImmediatelyAndClosesOnlyAfterBothFinish() = runBlocking<Unit> {
        val active = AtomicInteger()
        val closed = AtomicInteger()
        val started = List(2) { CompletableDeferred<Unit>() }
        val finish = List(2) { CompletableDeferred<Unit>() }
        val sequence = AtomicInteger()
        val fake = object : TranslationEngine {
            override suspend fun translatePage(page: Bitmap): PageResult {
                val index = sequence.getAndIncrement()
                active.incrementAndGet()
                started[index].complete(Unit)
                return try {
                    finish[index].await()
                    skipped("lifecycle fixture")
                } finally {
                    active.decrementAndGet()
                }
            }
            override fun warmUp() = Unit
            override fun close() {
                check(active.get() == 0) { "Closed an engine still used by a page" }
                closed.incrementAndGet()
            }
        }
        val service = isolatedService(fake)
        val page = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val calls = List(2) { async(Dispatchers.Default) { service.translatePage(page, "boxfill") } }
            withTimeout(10_000) { started.forEach { it.await() } }
            // Run the real callback entry on main: close must happen on IO after this returns.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { service.shutdownBlocking() }
            delay(100)
            assertEquals(0, closed.get())
            assertTrue(service.warm.value)
            finish[0].complete(Unit)
            withTimeout(10_000) { while (active.get() != 1) delay(10) }
            assertEquals(0, closed.get())
            finish[1].complete(Unit)
            calls.forEach { assertTrue(it.await() is PageResult.Skipped) }
            withTimeout(10_000) { while (closed.get() == 0) delay(10) }
            assertEquals(1, closed.get())
            assertFalse(service.warm.value)
        } finally {
            finish.forEach { it.complete(Unit) }
            service.shutdown()
            page.recycle()
        }
    }

    private fun engine(service: TranslationEngineService): TranslationEngine? = field("engine").get(service)
        as TranslationEngine?

    private fun skipped(reason: String) = PageResult.Skipped(reason, PageStats(0, 0, 0, 0, 0, 0, 0, 0))

    private suspend fun processStarted(expected: Boolean) = withTimeout(10_000) {
        withContext(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first {
                it.isAtLeast(Lifecycle.State.STARTED) == expected
            }
        }
    }

    private fun memory(service: TranslationEngineService): JSONObject {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return JSONObject().put("epochMs", System.currentTimeMillis())
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("pssKiB", info.totalPss).put("warm", service.warm.value)
    }

    private fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun isolatedService(engine: TranslationEngine): TranslationEngineService {
        val service = TranslationEngineService(context)
        val signature = TranslationEngineService::class.java.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        ).apply { isAccessible = true }.invoke(service, "boxfill", Injekt.get<TranslationPreferences>().snapshot())
        field("engine").set(service, engine)
        field("builtSignature").set(service, signature)
        field("warmedUp").setBoolean(service, true)
        @Suppress("UNCHECKED_CAST")
        (field("_warm").get(service) as MutableStateFlow<Boolean>).value = true
        service.onForeground()
        return service
    }
}
