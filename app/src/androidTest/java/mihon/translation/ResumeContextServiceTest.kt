package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.StoredTranslationContext
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.translation.service.TranslationPreferences
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Isolated service-policy races with tiny bitmaps; no native models, network or user-pref writes. */
@RunWith(AndroidJUnit4::class)
class ResumeContextServiceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val isolatedPreferenceNames = mutableListOf<String>()

    @After
    fun removeOnlyIsolatedPreferenceFiles() {
        isolatedPreferenceNames.forEach { name ->
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertTrue("Cannot flush isolated preference cleanup", preferences.edit().clear().commit())
            assertTrue("Cannot delete isolated preference XML", context.deleteSharedPreferences(name))
        }
        isolatedPreferenceNames.clear()
    }

    @Test
    fun sameLanguageFailoverResumesUsingActualEngineInsteadOfDifferentPreferences() = runBlocking {
        withInput { source, analysis ->
            val saved = semantic(TranslatorConfig(toLangName = "Korean", model = "old-route-model"))
            val actual = semantic(
                TranslatorConfig(
                    toLangName = "Korean",
                    provider = "custom",
                    model = "failover-model",
                    apiBase = "http://127.0.0.1/v1/chat/completions",
                ),
            )
            assertEquals(saved, actual)
            val fake = CountingEngine(actual)
            // Deliberately unrelated preferences cannot be substituted for the injected engine.
            val preferences = preferences("English")
            val service = isolatedService(fake, preferences)
            try {
                val result = service.translateStoredPage(
                    source,
                    analysis,
                    StoredTranslationContext.encode(saved),
                    "boxfill",
                )
                assertTrue(result.result is PageResult.Skipped)
                assertFalse(result.restarted)
                assertEquals(actual, StoredTranslationContext.decode(requireNotNull(result.contextPayload)))
                assertEquals(0, fake.full.get())
                assertEquals(1, fake.resumed.get())
                assertSame(analysis, fake.lastAnalysis)
            } finally {
                service.shutdown()
            }
        }
    }

    @Test
    fun targetLanguageChangeStartsFromOriginalWithoutPassingPreviousTargetsToResume() = runBlocking {
        withInput { source, analysis ->
            val saved = semantic(TranslatorConfig(toLangName = "Korean"))
            val current = semantic(TranslatorConfig(toLangName = "English"))
            val fake = CountingEngine(current)
            val service = isolatedService(fake, preferences("English"))
            val previous = analysis.progress
            try {
                val result = service.translateStoredPage(
                    source,
                    analysis,
                    StoredTranslationContext.encode(saved),
                    "boxfill",
                )
                assertTrue(result.restarted)
                assertEquals(current, StoredTranslationContext.decode(requireNotNull(result.contextPayload)))
                assertEquals(1, fake.full.get())
                assertEquals(0, fake.resumed.get())
                assertSame(source, fake.lastOriginal)
                assertSame(previous, analysis.progress)
                assertEquals(Color.WHITE, source.getPixel(0, 0))
                assertFalse(analysis.mask.isRecycled)
            } finally {
                service.shutdown()
            }
        }
    }

    @Test
    fun absentLegacyContextRestartsButFreshPageIsNotReportedAsRestarted() = runBlocking {
        withInput { source, analysis ->
            val fake = CountingEngine(semantic(TranslatorConfig(toLangName = "Korean")))
            val service = isolatedService(fake, preferences("Korean"))
            try {
                val legacy = service.translateStoredPage(source, analysis, null, "boxfill")
                assertTrue(legacy.restarted)
                assertTrue(legacy.result is PageResult.Skipped)
                val fresh = service.translateStoredPage(source, null, null, "boxfill")
                assertFalse(fresh.restarted)
                assertEquals(2, fake.full.get())
                assertEquals(0, fake.resumed.get())
            } finally {
                service.shutdown()
            }
        }
    }

    @Test
    fun malformedAndUnknownPayloadsFailBeforeEngineCreationOrNativeWarmup() = runBlocking {
        withInput { source, analysis ->
            val service = TranslationEngineService(context)
            try {
                val valid = StoredTranslationContext.encode(semantic(TranslatorConfig(toLangName = "Korean")))
                val payloads = listOf(
                    "{}",
                    "null",
                    valid.dropLast(1),
                    valid.replace("\"schemaVersion\":1", "\"schemaVersion\":99"),
                )
                payloads.forEach { payload ->
                    val result = service.translateStoredPage(source, analysis, payload, "boxfill")
                    assertTrue(result.result is PageResult.Failed)
                    assertNull(result.contextPayload)
                    assertFalse(result.restarted)
                    assertNull(field("engine").get(service))
                    assertEquals(0, (field("inFlight").get(service) as AtomicInteger).get())
                    assertFalse(service.loading.value)
                    assertFalse(analysis.mask.isRecycled)
                }
            } finally {
                service.shutdown()
            }
        }
    }

    @Test
    fun unidentifiedInjectedEngineFailsWithoutInferringOrGuessingFromPreferences() = runBlocking {
        withInput { source, analysis ->
            val fake = CountingEngine(null)
            val service = isolatedService(fake, preferences("Korean"))
            try {
                val result = service.translateStoredPage(source, analysis, null, "boxfill")
                assertTrue(result.result is PageResult.Failed)
                assertNull(result.contextPayload)
                assertEquals(0, fake.full.get())
                assertEquals(0, fake.resumed.get())
            } finally {
                service.shutdown()
            }
        }
    }

    @Test
    fun preferencesChangedDuringInferenceCannotRelabelResultAndReleaseWaitsForRegistration() = runBlocking {
        withInput { source, analysis ->
            val actual = semantic(TranslatorConfig(toLangName = "Korean"))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val fake = object : CountingEngine(actual) {
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                    started.complete(Unit)
                    finish.await()
                    return super.resumePage(original, analysis)
                }
                override fun close() {
                    super.close()
                    closed.complete(Unit)
                }
            }
            val preferences = preferences("Korean")
            val service = isolatedService(fake, preferences)
            try {
                val pending = async(Dispatchers.Default) {
                    service.translateStoredPage(source, analysis, StoredTranslationContext.encode(actual), "boxfill")
                }
                withTimeout(10_000) { started.await() }
                assertEquals(1, (field("inFlight").get(service) as AtomicInteger).get())
                preferences.targetLangName.set("French")
                service.shutdownBlocking()
                delay(100)
                assertEquals(0, fake.closed.get())
                finish.complete(Unit)
                val result = withTimeout(10_000) { pending.await() }
                assertFalse(result.restarted)
                assertEquals(actual, StoredTranslationContext.decode(requireNotNull(result.contextPayload)))
                val changed = StoredTranslationContext.encode(semantic(TranslatorConfig(toLangName = "French")))
                assertFalse(result.contextPayload == changed)
                withTimeout(10_000) { closed.await() }
                assertEquals(1, fake.closed.get())
            } finally {
                finish.complete(Unit)
                service.shutdown()
            }
        }
    }

    @Test
    fun changedSettingsDrainOldRegistrationAndUseTheEngineActuallySelectedAfterDrain() = runBlocking {
        withInput { source, analysis ->
            val actual = semantic(TranslatorConfig(toLangName = "Korean"))
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val sequence = AtomicInteger()
            val fake = object : CountingEngine(actual) {
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                    if (sequence.getAndIncrement() == 0) {
                        entered.complete(Unit)
                        finish.await()
                    }
                    return super.resumePage(original, analysis)
                }
            }
            val preferences = preferences("Korean")
            val service = isolatedService(fake, preferences)
            val initialSignature = field("builtSignature").get(service)
            suspend fun <T> stage(name: String, block: suspend () -> T): T {
                println("RESUME_CONTEXT_DRAIN_STAGE=$name")
                return try {
                    withTimeout(10_000) { block() }
                } catch (timeout: TimeoutCancellationException) {
                    val snapshotTarget = preferences.snapshot().targetLangName.get()
                    val changed = signature(service, preferences.snapshot()) != initialSignature
                    val locked = (field("mutex").get(service) as Mutex).isLocked
                    val active = (field("inFlight").get(service) as AtomicInteger).get()
                    throw AssertionError(
                        "Drain fixture timed out at $name: snapshotTarget=$snapshotTarget signatureChanged=$changed " +
                            "mutexLocked=$locked inFlight=$active entered=${sequence.get()} closed=${fake.closed.get()}",
                        timeout,
                    )
                }
            }
            try {
                val first = async(Dispatchers.Default) {
                    service.translateStoredPage(source, analysis, StoredTranslationContext.encode(actual), "boxfill")
                }
                stage("first-inference-entered") { entered.await() }
                preferences.targetLangName.set("French")
                assertEquals("French", preferences.targetLangName.get())
                assertEquals(
                    "The mutable fixture must persist changes into snapshots",
                    "French",
                    preferences.snapshot().targetLangName.get(),
                )
                assertFalse(
                    "Changed snapshot must change the service signature",
                    signature(service, preferences.snapshot()) == initialSignature,
                )
                println("RESUME_CONTEXT_DRAIN_STAGE=french-snapshot-and-signature-verified")
                val second = async(Dispatchers.Default) {
                    service.translateStoredPage(source, analysis, StoredTranslationContext.encode(actual), "boxfill")
                }
                val mutex = field("mutex").get(service) as Mutex
                stage("registration-mutex-held-during-drain") { while (!mutex.isLocked) delay(10) }
                delay(100)
                assertFalse(second.isCompleted)
                assertEquals("Changed settings must not reuse an engine before its old page drains", 1, sequence.get())
                assertEquals(1, (field("inFlight").get(service) as AtomicInteger).get())
                assertSame(fake, field("engine").get(service))
                assertEquals(0, fake.closed.get())
                // The drain path must re-snapshot settings. Restore the old signature before the
                // old page finishes so the selected engine remains this same injected instance.
                preferences.targetLangName.set("Korean")
                assertEquals("Korean", preferences.snapshot().targetLangName.get())
                assertEquals(
                    "Restored snapshot must restore the original signature",
                    initialSignature,
                    signature(service, preferences.snapshot()),
                )
                println("RESUME_CONTEXT_DRAIN_STAGE=korean-snapshot-and-signature-restored")
                finish.complete(Unit)
                val results =
                    stage("both-inferences-finished-after-restored-snapshot") { listOf(first.await(), second.await()) }
                results.forEach {
                    assertFalse(it.restarted)
                    assertEquals(actual, StoredTranslationContext.decode(requireNotNull(it.contextPayload)))
                }
                assertEquals(2, sequence.get())
                assertEquals(2, fake.resumed.get())
                assertEquals(0, fake.closed.get())
            } finally {
                finish.complete(Unit)
                service.shutdown()
            }
        }
    }

    @Test
    fun coldFirstStoredInferenceStillSerializesBeforeSubsequentPageRegistration() = runBlocking {
        withInput { source, analysis ->
            val actual = semantic(TranslatorConfig(toLangName = "Korean"))
            val entered = List(2) { CompletableDeferred<Unit>() }
            val finish = List(2) { CompletableDeferred<Unit>() }
            val sequence = AtomicInteger()
            val fake = object : CountingEngine(actual) {
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                    val index = sequence.getAndIncrement()
                    entered[index].complete(Unit)
                    finish[index].await()
                    return super.resumePage(original, analysis)
                }
            }
            val service = isolatedService(fake, preferences("Korean"), warmed = false)
            try {
                val first = async(Dispatchers.Default) {
                    service.translateStoredPage(source, analysis, StoredTranslationContext.encode(actual), "boxfill")
                }
                withTimeout(10_000) { entered[0].await() }
                val second = async(Dispatchers.Default) {
                    service.translateStoredPage(source, analysis, StoredTranslationContext.encode(actual), "boxfill")
                }
                delay(100)
                assertFalse(
                    "Cold engine must finish its first inference before admitting another",
                    entered[1].isCompleted,
                )
                assertEquals(1, (field("inFlight").get(service) as AtomicInteger).get())
                finish[0].complete(Unit)
                withTimeout(10_000) {
                    first.await()
                    entered[1].await()
                }
                finish[1].complete(Unit)
                withTimeout(10_000) { second.await() }
                assertEquals(2, fake.resumed.get())
            } finally {
                finish.forEach { it.complete(Unit) }
                service.shutdown()
            }
        }
    }

    private fun semantic(config: TranslatorConfig): TranslationContext = requireNotNull(
        TranslationContext.forPipeline(
            TranslationContext.fromTranslator(config),
            TranslationContext.forOcrPolicy(0),
            null,
        ),
    )

    private fun preferences(target: String): TranslationPreferences {
        // snapshot() expects getAll() to return stored primitives. InMemoryPreferenceStore's
        // separate Preference copies do not reflect later writes there, so it cannot drive a
        // configuration-change race. Use a new private UUID file, never the user's default store.
        val name = "resume-context-${UUID.randomUUID()}"
        isolatedPreferenceNames += name
        val backing = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return TranslationPreferences(AndroidPreferenceStore(context, backing)).apply {
            targetLangName.set(target)
            assertEquals("Fixture direct target", target, targetLangName.get())
            assertEquals("Fixture snapshot target", target, snapshot().targetLangName.get())
        }
    }

    private suspend fun withInput(block: suspend (Bitmap, PageAnalysis) -> Unit) {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val mask = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val progress = PageProgress(
            8,
            8,
            listOf(
                RegionProgress(
                    id = 0,
                    state = RegionState.OCR_PENDING,
                    lines = listOf(
                        OcrLineProgress(listOf(Pt(1f, 1f), Pt(6f, 1f), Pt(6f, 6f), Pt(1f, 6f)), 0.9f, "h", ""),
                    ),
                    direction = "h",
                ),
            ),
        )
        val analysis = PageAnalysis(mask, emptyList(), progress)
        try {
            block(source, analysis)
        } finally {
            source.recycle()
            mask.recycle()
        }
    }

    private open class CountingEngine(override val translationContext: TranslationContext?) : TranslationEngine {
        val full = AtomicInteger()
        val resumed = AtomicInteger()
        val closed = AtomicInteger()
        var lastOriginal: Bitmap? = null
        var lastAnalysis: PageAnalysis? = null
        override suspend fun translatePage(page: Bitmap): PageResult {
            full.incrementAndGet()
            lastOriginal = page
            return skipped()
        }
        override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
            resumed.incrementAndGet()
            lastOriginal = original
            lastAnalysis = analysis
            return skipped()
        }
        override fun warmUp() = Unit
        override fun close() {
            closed.incrementAndGet()
        }
        private fun skipped() = PageResult.Skipped("context fixture", PageStats(0, 0, 0, 0, 0, 0, 0, 0))
    }

    private fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun isolatedService(
        engine: TranslationEngine,
        preferences: TranslationPreferences,
        warmed: Boolean = true,
    ): TranslationEngineService {
        val service = TranslationEngineService(context)
        field("translationPreferences").set(service, preferences)
        val signature = signature(service, preferences.snapshot())
        field("engine").set(service, engine)
        field("builtSignature").set(service, signature)
        field("warmedUp").setBoolean(service, warmed)
        @Suppress("UNCHECKED_CAST")
        (field("_warm").get(service) as MutableStateFlow<Boolean>).value = true
        service.onForeground()
        return service
    }

    private fun signature(service: TranslationEngineService, preferences: TranslationPreferences): String =
        TranslationEngineService::class.java.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        ).apply { isAccessible = true }.invoke(service, "boxfill", preferences) as String
}
