package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.StoredTranslationContext
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File

/** Real journal/service integration with scripted engines, not native OCR or external translation. */
@RunWith(AndroidJUnit4::class)
class StoredPageContextIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun languageRestartFailurePreservesPriorOutputThenReplacesAllTargetsAndResumes() = runBlocking {
        withPending { fixture, pending ->
            val previousOutput = fixture.page.readBytes()
            val previousMaterials = fixture.materials.listFiles()!!.associate { it.name to it.readBytes() }
            var fullCalls = 0
            var resumeCalls = 0
            val english = object : TranslationEngine {
                override val translationContext = semantic("English")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    assertEquals("Inference must use the preserved source", Color.WHITE, page.getPixel(0, 0))
                    fullCalls++
                    return if (fullCalls ==
                        1
                    ) {
                        PageResult.Failed("scripted restart failure")
                    } else {
                        result("English", false)
                    }
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                    resumeCalls++
                    assertEquals("English 1", analysis.progress!!.regions[0].translatedText)
                    assertTrue(analysis.progress!!.regions.none { it.translatedText.contains("한국어") })
                    return result("English", true)
                }
                override fun warmUp() = Unit
                override fun close() = Unit
            }
            fixture.select(english)
            assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, fixture.translate())
            assertEquals(1, fullCalls)
            assertEquals(0, resumeCalls)
            assertArrayEquals(previousOutput, fixture.page.readBytes())
            assertEquals(pending, fixture.store.load(PAGE))
            assertEquals(previousMaterials.keys, fixture.materials.listFiles()!!.map { it.name }.toSet())
            previousMaterials.forEach { (name, bytes) ->
                assertArrayEquals(name, bytes, File(fixture.materials, name).readBytes())
            }

            assertEquals(PageTranslator.SinglePageResult.PARTIAL, fixture.translate())
            val replacement = requireNotNull(fixture.store.load(PAGE))
            assertEquals(pending.revision, replacement.baseRevision)
            assertEquals(pending.original, replacement.original)
            assertArrayEquals(previousOutput, fixture.store.readOutput(pending))
            val progress = OcrProgressCodec.decode(replacement.progressPayload, SIZE, SIZE)
            assertEquals("English 1", progress.regions[0].translatedText)
            assertTrue(progress.regions.none { it.translatedText.contains("한국어") })
            assertEquals(english.translationContext, StoredTranslationContext.decode(replacement.safeContextPayload!!))
            assertEquals(2, fullCalls)
            assertEquals(0, resumeCalls)
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))

            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(2, fullCalls)
            assertEquals(1, resumeCalls)
            val completed = requireNotNull(fixture.store.load(PAGE))
            assertTrue(completed.complete)
            assertEquals(replacement.safeContextPayload, completed.safeContextPayload)
            val finalProgress = OcrProgressCodec.decode(completed.progressPayload, SIZE, SIZE)
            assertEquals(listOf("English 1", "English 2"), finalProgress.regions.map { it.translatedText })
            assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
            fixture.assertOriginalPreserved()
        }
    }

    @Test
    fun changedContextNoTextResultClosesPreviousJournalWithoutRetainingOldTargets() = runBlocking {
        withPending { fixture, pending ->
            var calls = 0
            val english = object : TranslationEngine {
                override val translationContext = semantic("English")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls++
                    assertEquals(Color.WHITE, page.getPixel(0, 0))
                    val progress = PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
                    return PageResult.Skipped(
                        "No text detected",
                        stats(0),
                        PageAnalysis(image(Color.BLACK), emptyList(), progress),
                    )
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("Old-language progress must not be passed to resume")
                override fun warmUp() = Unit
                override fun close() = Unit
            }
            fixture.select(english)
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, calls)
            val completed = requireNotNull(fixture.store.load(PAGE))
            assertEquals(pending.revision, completed.baseRevision)
            assertTrue(completed.complete)
            val progress = OcrProgressCodec.decode(completed.progressPayload, SIZE, SIZE)
            assertTrue(progress.noTextDetected)
            assertTrue(progress.regions.isEmpty())
            assertFalse(progress.hasPending)
            assertEquals(english.translationContext, StoredTranslationContext.decode(completed.safeContextPayload!!))
            assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
            fixture.assertOriginalPreserved()
        }
    }

    @Test
    fun completedJournalChangedContextNoTextStoresFreshFinalStateAndPreservesPriorOutput() = runBlocking {
        withCompleted { fixture, completed ->
            assertFreshSkippedCompletes(fixture, completed, "English", noText = true)
        }
    }

    @Test
    fun completedJournalSameContextAllKeptStoresFreshFinalStateWithoutResuming() = runBlocking {
        withCompleted { fixture, completed ->
            assertFreshSkippedCompletes(fixture, completed, "Korean", noText = false)
        }
    }

    @Test
    fun legacyCompletedPageNoTextPreservesPreviousOutputAsFirstJournalBaseline() = runBlocking {
        withFixture { fixture ->
            check(fixture.materials.mkdir())
            File(fixture.materials, "001.orig.png").writeBytes(fixture.initialBytes)
            fixture.page.writeBytes(encoded(Color.BLACK))
            File(fixture.directory, CompletionManifestCodec.PRIMARY)
                .writeBytes(CompletionManifestCodec.encode(setOf(PAGE)))
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertEquals(null, fixture.store.load(PAGE))
            assertFreshSkippedCompletes(fixture, null, "English", noText = true)
        }
    }

    private suspend fun assertFreshSkippedCompletes(
        fixture: Fixture,
        previous: PartialPageStore.State?,
        language: String,
        noText: Boolean,
    ) {
        val previousOutput = fixture.page.readBytes()
        val previousRecord = previous?.let {
            File(fixture.materials, it.outputSnapshotName.removeSuffix(".output") + ".record")
        }
        val previousRecordBytes = previousRecord?.readBytes()
        var fullCalls = 0
        var resumeCalls = 0
        val freshProgress = if (noText) {
            PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
        } else {
            PageProgress(
                SIZE,
                SIZE,
                listOf(
                    RegionProgress(
                        0,
                        RegionState.INTENTIONALLY_KEPT,
                        listOf(
                            OcrLineProgress(
                                listOf(Pt(0f, 0f), Pt(16f, 0f), Pt(16f, 16f), Pt(0f, 16f)),
                                1f,
                                "h",
                                "fresh deliberately kept source",
                            ),
                        ),
                        "h",
                    ),
                ),
            )
        }
        val freshEngine = object : TranslationEngine {
            override val translationContext = semantic(language)
            override suspend fun translatePage(page: Bitmap): PageResult {
                fullCalls++
                assertEquals("Fresh inference must use the preserved source", Color.WHITE, page.getPixel(0, 0))
                return PageResult.Skipped(
                    if (noText) "No text detected" else "All regions intentionally kept",
                    stats(0),
                    PageAnalysis(image(Color.BLACK), freshProgress.toTextRegions(), freshProgress),
                )
            }
            override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                resumeCalls++
                error("A completed page requires fresh inference, never pending resume")
            }
            override fun warmUp() = Unit
            override fun close() = Unit
        }
        fixture.select(freshEngine)
        // The previous branch returned NOT_TRANSLATED here and retained the old target/context.
        assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
        assertEquals(1, fullCalls)
        assertEquals(0, resumeCalls)
        val replacement = requireNotNull(fixture.store.load(PAGE))
        assertTrue(replacement.complete)
        assertEquals(previous?.revision, replacement.baseRevision)
        assertEquals((previous?.generation ?: 0L) + 1L, replacement.generation)
        assertEquals(freshProgress, OcrProgressCodec.decode(replacement.progressPayload, SIZE, SIZE))
        assertEquals(freshEngine.translationContext, StoredTranslationContext.decode(replacement.safeContextPayload!!))
        assertFalse(fixture.page.readBytes().contentEquals(previousOutput))
        assertArrayEquals(fixture.page.readBytes(), fixture.store.readOutput(replacement))
        val visible = requireNotNull(BitmapFactory.decodeFile(fixture.page.path))
        try {
            val pixels = IntArray(SIZE * SIZE)
            visible.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            assertTrue("Fresh kept/no-text result must display source pixels", pixels.all { it == Color.WHITE })
        } finally {
            visible.recycle()
        }
        if (previous != null) {
            assertEquals(previous.original, replacement.original)
            assertEquals(previous.initialBaseline, replacement.initialBaseline)
            assertArrayEquals(previousOutput, fixture.store.readOutput(previous))
            assertArrayEquals(previousRecordBytes, requireNotNull(previousRecord).readBytes())
            assertEquals(
                semantic("Korean"),
                StoredTranslationContext.decode(previous.safeContextPayload!!),
            )
            assertTrue(
                OcrProgressCodec.decode(previous.progressPayload, SIZE, SIZE).regions.all {
                    it.translatedText.startsWith("한국어")
                },
            )
        } else {
            assertEquals(PartialPageStore.OriginalProvenance.EXACT_BYTES, replacement.original.provenance)
            assertArrayEquals(previousOutput, fixture.store.readInitialBaseline(replacement))
        }
        assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
        assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
        fixture.assertOriginalPreserved()
    }

    private suspend fun withCompleted(block: suspend (Fixture, PartialPageStore.State) -> Unit) {
        withPending { fixture, pending ->
            var resumeCalls = 0
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult = error("Expected same-context resume")
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                    resumeCalls++
                    return result("한국어", true)
                }
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, resumeCalls)
            val completed = requireNotNull(fixture.store.load(PAGE))
            assertTrue(completed.complete)
            assertEquals(pending.revision, completed.baseRevision)
            block(fixture, completed)
        }
    }

    private suspend fun withPending(block: suspend (Fixture, PartialPageStore.State) -> Unit) {
        withFixture { fixture ->
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult = result("한국어", false)
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(PageTranslator.SinglePageResult.PARTIAL, fixture.translate())
            val pending = requireNotNull(fixture.store.load(PAGE))
            assertEquals(semantic("Korean"), StoredTranslationContext.decode(pending.safeContextPayload!!))
            block(fixture, pending)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        assertTrue("PageTranslator requires the prepared model set", TranslationEngineConfig.modelsResolvable(context))
        val directory = File(context.cacheDir, "stored-context-${System.nanoTime()}").apply { check(mkdir()) }
        val source = image(Color.WHITE)
        val page = File(directory, PAGE)
        try {
            page.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            source.recycle()
        }
        val initialBytes = page.readBytes()
        val service = TranslationEngineService(context)
        val signature = TranslationEngineService::class.java.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        ).apply { isAccessible = true }.invoke(service, "boxfill", Injekt.get<TranslationPreferences>().snapshot())
        field("builtSignature").set(service, signature)
        field("warmedUp").setBoolean(service, true)
        val translator = PageTranslator(context).also {
            PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }.set(it, service)
        }
        val fixture = Fixture(directory, page, initialBytes, service, translator)
        try {
            block(fixture)
        } finally {
            service.shutdown()
            directory.deleteRecursively()
        }
    }

    private class Fixture(
        val directory: File,
        val page: File,
        val initialBytes: ByteArray,
        val service: TranslationEngineService,
        val translator: PageTranslator,
    ) {
        val chapter = requireNotNull(UniFile.fromFile(directory))
        val materials get() = File(directory, ".yakuyomi")
        val store get() = PartialPageStore.forUniFile(
            InstrumentationRegistry.getInstrumentation().targetContext,
            chapter,
            requireNotNull(UniFile.fromFile(materials)),
        )
        fun select(engine: TranslationEngine) {
            field("engine").set(service, engine)
        }
        suspend fun translate() = translator.translateSinglePage(chapter, PAGE, "boxfill")
        fun assertOriginalPreserved() = assertArrayEquals(initialBytes, File(materials, "001.orig.png").readBytes())
    }

    companion object {
        private const val PAGE = "001.png"
        private const val SIZE = 32
        private fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        private fun semantic(
            language: String,
        ) = TranslationContext.fromTranslator(TranslatorConfig(toLangName = language))
        private fun image(
            color: Int,
        ) = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        private fun encoded(color: Int): ByteArray {
            val bitmap = image(color)
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        private fun stats(kept: Int) = PageStats(2, 2, kept, 0, 0, 0, 0, 0)
        private fun result(language: String, complete: Boolean): PageResult {
            val progress = PageProgress(
                SIZE,
                SIZE,
                (0..1).map { id ->
                    val resolved = id == 0 || complete
                    RegionProgress(
                        id,
                        if (resolved) RegionState.TRANSLATED else RegionState.OCR_PENDING,
                        listOf(
                            OcrLineProgress(
                                listOf(Pt(0f, 0f), Pt(16f, 0f), Pt(16f, 16f), Pt(0f, 16f)),
                                1f,
                                "h",
                                if (resolved) "source ${id + 1}" else "",
                            ),
                        ),
                        "h",
                        translatedText = if (resolved) "$language ${id + 1}" else "",
                    )
                },
            )
            val analysis = PageAnalysis(image(Color.WHITE), progress.toTextRegions(), progress)
            val output = image(if (complete) Color.BLACK else Color.BLUE)
            return if (complete) {
                PageResult.Translated(
                    output,
                    stats(2),
                    analysis,
                )
            } else {
                PageResult.Partial(output, stats(1), analysis)
            }
        }
    }
}
