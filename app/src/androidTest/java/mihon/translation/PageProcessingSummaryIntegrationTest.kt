package mihon.translation

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageProcessingCounts
import eu.kanade.tachiyomi.data.translation.PageProcessingDetails
import eu.kanade.tachiyomi.data.translation.PageProcessingReason
import eu.kanade.tachiyomi.data.translation.PageProcessingSummaryCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.StoredTranslationContext
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.model.TranslationFailureCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.FailureKind
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.ProcessingFailure
import li.joye.yakuyomi.engine.ProcessingStage
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Public storage integration using owned file:// fixtures and scripted inference; no native, HTTP or Activity. */
@RunWith(AndroidJUnit4::class)
class PageProcessingSummaryIntegrationTest {
    @Test(timeout = 30_000)
    fun manualTypedTimeoutAndUntypedFailurePreserveSourceWithoutDuplicateInference(): Unit = runBlocking {
        val cases = listOf(
            ProcessingFailure(ProcessingStage.TRANSLATE, FailureKind.TIMEOUT) to TranslationFailureCode.SERVER_TIMEOUT,
            null to TranslationFailureCode.UNKNOWN,
        )
        val observedCodes = mutableListOf<String>()
        val observedCalls = mutableListOf<Int>()
        for ((failure, expectedCode) in cases) {
            withFixture(ScriptedEngine(Kind.FAILED, null, failure = failure)) { fixture ->
                val originalBefore = fixture.original.takeIf { it.isFile }?.readBytes()
                val outcome = fixture.translateDetailed()
                assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, outcome.result)
                assertEquals(expectedCode, outcome.failureCode)
                assertFalse(outcome.displayChanged)
                assertArrayEquals(fixture.initialBytes, fixture.page.readBytes())
                if (originalBefore == null) {
                    assertFalse("A failure must not create a new original authority", fixture.original.exists())
                } else {
                    assertArrayEquals(originalBefore, fixture.original.readBytes())
                }
                assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
                assertFalse(fixture.translator.isChapterTranslated(fixture.chapter))
                assertTrue(fixture.completionUnion().isEmpty())
                assertNull(fixture.store.load(PAGE))
                assertNull(fixture.readWithoutMutation())
                assertEquals("Detailed must invoke inference exactly once", 1, fixture.engine.freshCalls)
                assertEquals(0, fixture.engine.resumeCalls)
                assertEquals(0, TranslationBusy.active.value)
                fixture.engine.assertReturnedBitmapsRecycled()
                observedCodes += checkNotNull(outcome.failureCode).wireValue
                observedCalls += fixture.engine.freshCalls
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString(
                    "typedSinglePageFailureEvidence",
                    JSONObject().put("passed", true).put("attempts", 2)
                        .put("inferenceCallsPerAttempt", JSONArray(observedCalls))
                        .put("failureCodes", JSONArray(observedCodes))
                        .put("sourcePreserved", true).put("completionAdded", false).toString(),
                )
            },
        )
    }

    @Test(timeout = 30_000)
    fun manualSkippedResultDistinguishesNoTextAndKeptSourceWithoutInventingCompletion(): Unit = runBlocking {
        val cases = listOf(
            PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true) to PageProcessingReason.NO_TEXT,
            progress(RegionState.INTENTIONALLY_KEPT, policyIds = setOf(0)) to PageProcessingReason.OCR_POLICY_KEPT,
            progress(RegionState.INTENTIONALLY_KEPT) to PageProcessingReason.RESPONSE_FILTERED,
        )
        for ((finalProgress, reason) in cases) {
            withFixture(ScriptedEngine(Kind.SKIPPED, finalProgress)) { fixture ->
                // The outcome is from this attempt, independently of its optional saved summary.
                // Its result must remain distinct from translated completion and actual failure.
                val outcome = fixture.translateDetailed()
                assertEquals("SKIPPED", outcome.result.name)
                assertNull(outcome.failureCode)
                assertFalse(
                    "Fresh unchanged classification must not invalidate the display or Night image",
                    outcome.displayChanged,
                )
                assertArrayEquals(fixture.initialBytes, fixture.page.readBytes())
                if (fixture.original.exists()) assertArrayEquals(fixture.initialBytes, fixture.original.readBytes())
                assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
                assertFalse(fixture.translator.isChapterTranslated(fixture.chapter))
                assertTrue(fixture.completionUnion().isEmpty())
                assertNull(fixture.store.load(PAGE))
                val details = requireNotNull(fixture.readWithoutMutation())
                assertEquals(reason, details.outcome.reason)
                val counts = requireNotNull(details.outcome.counts)
                assertEquals(0, counts.translatedRegions)
                assertEquals(0, counts.pendingRegions)
                assertEquals(1, fixture.engine.freshCalls)
                assertEquals(0, fixture.engine.resumeCalls)
                fixture.engine.assertReturnedBitmapsRecycled()
            }
        }
    }

    @Test(timeout = 30_000)
    fun manualSkipDoesNotRequireAnOptionalSummaryAndLaterFailureCannotReuseItsOutcome(): Unit = runBlocking {
        val noText = PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
        withFixture(ScriptedEngine(Kind.SKIPPED, noText)) { fixture ->
            check(fixture.summary.mkdir())
            val sentinel = File(fixture.summary, "owned-sentinel").apply { writeText("neutral summary obstruction") }
            val sentinelBytes = sentinel.readBytes()
            assertEquals("SKIPPED", fixture.translate().name)
            assertNull(fixture.readWithoutMutation())
            assertArrayEquals(sentinelBytes, sentinel.readBytes())
            assertTrue(sentinel.delete() && fixture.summary.delete())

            assertEquals("SKIPPED", fixture.translate().name)
            assertEquals(PageProcessingReason.NO_TEXT, requireNotNull(fixture.readWithoutMutation()).outcome.reason)
            fixture.engine.selectFresh(Kind.FAILED, null, "Korean")
            val failedOutcome = fixture.translateDetailed()
            assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, failedOutcome.result)
            assertFalse("A failed attempt must not claim a display write", failedOutcome.displayChanged)
            assertArrayEquals(fixture.initialBytes, fixture.page.readBytes())
            if (fixture.original.exists()) assertArrayEquals(fixture.initialBytes, fixture.original.readBytes())
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertTrue(fixture.completionUnion().isEmpty())
            assertNull(fixture.store.load(PAGE))
            assertEquals(3, fixture.engine.freshCalls)
            assertEquals(0, fixture.engine.resumeCalls)
            fixture.engine.assertReturnedBitmapsRecycled()
        }
    }

    @Test(timeout = 30_000)
    fun freshSkippedReasonsKeepExactSourceAndReadsNeverStartInferenceOrWriteFiles(): Unit = runBlocking {
        val cases = listOf(
            PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true) to
                PageProcessingReason.NO_TEXT,
            progress(RegionState.INTENTIONALLY_KEPT, policyIds = setOf(0)) to
                PageProcessingReason.OCR_POLICY_KEPT,
            progress(RegionState.INTENTIONALLY_KEPT) to
                PageProcessingReason.RESPONSE_FILTERED,
            progress(RegionState.INTENTIONALLY_KEPT, RegionState.INTENTIONALLY_KEPT, policyIds = setOf(0)) to
                PageProcessingReason.MIXED_KEPT,
        )
        for ((finalProgress, reason) in cases) {
            withFixture(ScriptedEngine(Kind.SKIPPED, finalProgress)) { fixture ->
                val start = System.currentTimeMillis()
                assertEquals(0, fixture.translator.translateChapter(fixture.chapter, METHOD, nightMaterials = false))
                val finish = System.currentTimeMillis()
                assertArrayEquals(fixture.initialBytes, fixture.page.readBytes())
                assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
                assertEquals(setOf(PAGE), fixture.completionUnion())
                val details = requireNotNull(fixture.readWithoutMutation())
                assertEquals(reason, details.outcome.reason)
                assertTrue(details.recordedAtEpochMs in start..finish)
                val counts = requireNotNull(details.outcome.counts)
                assertEquals(finalProgress.regions.size, counts.regions)
                assertEquals(finalProgress.regions.sumOf { it.lines.size }, counts.detectedLines)
                assertEquals(0, counts.translatedRegions)
                assertEquals(0, counts.pendingRegions)
                assertEquals(1, fixture.engine.freshCalls)
                assertEquals(0, fixture.engine.resumeCalls)
                fixture.engine.assertReturnedBitmapsRecycled()
            }
        }
    }

    @Test(timeout = 30_000)
    fun customSkippedResultWithoutAnalysisRecordsUnknownRatherThanParsingItsReason(): Unit = runBlocking {
        withFixture(ScriptedEngine(Kind.SKIPPED, null)) { fixture ->
            assertEquals(0, fixture.translator.translateChapter(fixture.chapter, METHOD, nightMaterials = false))
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertArrayEquals(fixture.initialBytes, fixture.page.readBytes())
            val details = requireNotNull(fixture.readWithoutMutation())
            assertEquals(PageProcessingReason.UNKNOWN, details.outcome.reason)
            assertNull(details.outcome.counts)
            assertEquals(1, fixture.engine.freshCalls)
        }
    }

    @Test(timeout = 30_000)
    fun translatedRecordContainsFinalCountsAndLeavesTheExactOriginalProtected(): Unit = runBlocking {
        withFixture(ScriptedEngine(Kind.TRANSLATED, progress(RegionState.TRANSLATED))) { fixture ->
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertFalse(fixture.initialBytes.contentEquals(fixture.page.readBytes()))
            assertArrayEquals(fixture.initialBytes, fixture.original.readBytes())
            val details = requireNotNull(fixture.readWithoutMutation())
            assertEquals(PageProcessingReason.TRANSLATED, details.outcome.reason)
            assertEquals(PageProcessingCounts(1, 1, 0, 0, 0, 1, 0), details.outcome.counts)
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertEquals(1, fixture.engine.freshCalls)
            fixture.engine.assertReturnedBitmapsRecycled()
        }
    }

    @Test(timeout = 30_000)
    fun preservedSourceSkippedReanalysisRestoresPixelsAndRecordsItsFinalJournalAndActualContext(): Unit = runBlocking {
        val cases = listOf(
            PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true) to PageProcessingReason.NO_TEXT,
            progress(RegionState.INTENTIONALLY_KEPT, policyIds = setOf(0)) to PageProcessingReason.OCR_POLICY_KEPT,
        )
        for ((finalProgress, reason) in cases) {
            withFixture(ScriptedEngine(Kind.TRANSLATED, progress(RegionState.TRANSLATED))) { fixture ->
                assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
                assertNotNull(fixture.readWithoutMutation())
                assertPixels(Color.GREEN, fixture.page.readBytes())
                val preserved = fixture.original.readBytes()
                val priorRecord = requireNotNull(PageProcessingSummaryCodec.decode(fixture.summary.readBytes()))
                fixture.engine.selectFresh(Kind.SKIPPED, finalProgress, "English")

                val restoredOutcome = fixture.translateDetailed()
                assertEquals("SKIPPED", restoredOutcome.result.name)
                assertNull(restoredOutcome.failureCode)
                assertTrue(
                    "Restoring the preserved source must invalidate its former display and Night image",
                    restoredOutcome.displayChanged,
                )

                assertPixels(Color.WHITE, fixture.page.readBytes())
                assertArrayEquals(fixture.initialBytes, preserved)
                assertArrayEquals(preserved, fixture.original.readBytes())
                val current = requireNotNull(fixture.store.load(PAGE))
                assertTrue(current.complete)
                assertEquals(finalProgress, OcrProgressCodec.decode(current.progressPayload, SIZE, SIZE))
                assertEquals(
                    fixture.engine.translationContext,
                    StoredTranslationContext.decode(requireNotNull(current.safeContextPayload)),
                )
                assertFalse(priorRecord.binding.contextSha256 == fixture.engine.translationContext.semanticFingerprint)
                val record = requireNotNull(PageProcessingSummaryCodec.decode(fixture.summary.readBytes()))
                assertEquals(current.revision, record.binding.journalRevision)
                assertEquals(fixture.engine.translationContext.semanticFingerprint, record.binding.contextSha256)
                assertEquals(reason, requireNotNull(fixture.readWithoutMutation()).outcome.reason)
                assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
                assertEquals(2, fixture.engine.freshCalls)
                assertEquals(0, fixture.engine.resumeCalls)
                assertEquals(3, fixture.engine.returnedBitmapCount)
                fixture.engine.assertReturnedBitmapsRecycled()
            }
        }
    }

    @Test(timeout = 30_000)
    fun skippedWithContradictoryTranslatedProgressRecordsUnknownForFreshAndPreservedSources(): Unit = runBlocking {
        for (preserveOriginal in listOf(false, true)) {
            val initialKind = if (preserveOriginal) Kind.TRANSLATED else Kind.SKIPPED
            withFixture(ScriptedEngine(initialKind, progress(RegionState.TRANSLATED))) { fixture ->
                if (preserveOriginal) {
                    assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
                    fixture.engine.selectFresh(Kind.SKIPPED, progress(RegionState.TRANSLATED), "English")
                    assertEquals("SKIPPED", fixture.translate().name)
                    assertPixels(Color.WHITE, fixture.page.readBytes())
                    assertArrayEquals(fixture.initialBytes, fixture.original.readBytes())
                } else {
                    assertEquals(
                        0,
                        fixture.translator.translateChapter(fixture.chapter, METHOD, nightMaterials = false),
                    )
                    assertArrayEquals(fixture.initialBytes, fixture.page.readBytes())
                }
                val details = requireNotNull(fixture.readWithoutMutation())
                assertEquals(PageProcessingReason.UNKNOWN, details.outcome.reason)
                assertNull(details.outcome.counts)
                assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
                assertEquals(if (preserveOriginal) 2 else 1, fixture.engine.freshCalls)
                assertEquals(0, fixture.engine.resumeCalls)
                fixture.engine.assertReturnedBitmapsRecycled()
            }
        }
    }

    @Test(timeout = 30_000)
    fun resumedRecordUsesFinalPageCountsAndPreservesAnEarlierSuccessfulTarget(): Unit = runBlocking {
        val first = progress(RegionState.TRANSLATED, RegionState.OCR_PENDING)
        val final = progress(RegionState.TRANSLATED, RegionState.TRANSLATED)
        withFixture(ScriptedEngine(Kind.PARTIAL, first, final)) { fixture ->
            val partialOutcome = fixture.translateDetailed()
            assertEquals(PageTranslator.SinglePageResult.PARTIAL, partialOutcome.result)
            assertNull(partialOutcome.failureCode)
            assertTrue(partialOutcome.displayChanged)
            val pending = requireNotNull(fixture.store.load(PAGE))
            assertFalse(pending.complete)
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            val partial = requireNotNull(fixture.readWithoutMutation())
            assertEquals(PageProcessingReason.PARTIAL, partial.outcome.reason)
            assertEquals(PageProcessingCounts(2, 2, 0, 0, 0, 1, 1), partial.outcome.counts)
            val partialSummary = fixture.summary.readBytes()
            val completedOutcome = fixture.translateDetailed()
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, completedOutcome.result)
            assertNull(completedOutcome.failureCode)
            assertTrue(completedOutcome.displayChanged)
            val completed = requireNotNull(fixture.store.load(PAGE))
            assertTrue(completed.complete)
            assertEquals(pending.revision, completed.baseRevision)
            val completedProgress = OcrProgressCodec.decode(completed.progressPayload, SIZE, SIZE)
            assertEquals(first.regions[0], completedProgress.regions[0])
            assertArrayEquals(fixture.initialBytes, fixture.original.readBytes())
            val details = requireNotNull(fixture.readWithoutMutation())
            assertEquals(PageProcessingReason.TRANSLATED, details.outcome.reason)
            assertEquals(PageProcessingCounts(2, 2, 0, 0, 0, 2, 0), details.outcome.counts)
            assertFalse(partialSummary.contentEquals(fixture.summary.readBytes()))
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertEquals(1, fixture.engine.freshCalls)
            assertEquals(1, fixture.engine.resumeCalls)
            fixture.engine.assertReturnedBitmapsRecycled()
        }
    }

    @Test(timeout = 30_000)
    fun legacyCompletionWithoutSummaryRemainsCompleteAndDoesNotInventAnExplanation(): Unit = runBlocking {
        withFixture(ScriptedEngine(Kind.TRANSLATED, progress(RegionState.TRANSLATED))) { fixture ->
            File(fixture.directory, CompletionManifestCodec.PRIMARY)
                .writeBytes(CompletionManifestCodec.encode(setOf(PAGE)))
            assertNull(fixture.readWithoutMutation())
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            val before = fixture.hashes()
            assertEquals(0, fixture.translator.translateChapter(fixture.chapter, METHOD, nightMaterials = false))
            assertEquals(PageTranslator.SinglePageResult.NO_ORIGINAL, fixture.translate())
            assertEquals(before, fixture.hashes())
            assertEquals(0, fixture.engine.freshCalls)
            assertEquals(0, fixture.engine.resumeCalls)
            assertFalse(fixture.original.exists())
            assertFalse(fixture.summary.exists())
        }
    }

    @Test(timeout = 30_000)
    fun displayOriginalAndSameByteJournalRevisionChangesEachRejectAStaleRecord(): Unit = runBlocking {
        val final = progress(RegionState.TRANSLATED, RegionState.TRANSLATED)
        val engine = ScriptedEngine(Kind.PARTIAL, progress(RegionState.TRANSLATED, RegionState.OCR_PENDING), final)
        withFixture(engine) { fixture ->
            assertEquals(PageTranslator.SinglePageResult.PARTIAL, fixture.translate())
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertNotNull(fixture.readWithoutMutation())
            val display = fixture.page.readBytes()
            fixture.page.writeBytes(encoded(Color.RED))
            assertNull(fixture.readWithoutMutation())
            fixture.page.writeBytes(display)
            assertNotNull(fixture.readWithoutMutation())
            val source = fixture.original.readBytes()
            fixture.original.writeBytes(encoded(Color.YELLOW))
            assertNull(fixture.readWithoutMutation())
            fixture.original.writeBytes(source)
            assertNotNull(fixture.readWithoutMutation())

            val previous = requireNotNull(fixture.store.load(PAGE))
            val next = fixture.store.commit(
                PAGE, previous.original, SIZE, SIZE, previous.progressPayload,
                fixture.store.readOutput(previous), fixture.store.readRawMask(previous), previous.revision, true,
                safeContextPayload = previous.safeContextPayload,
            )
            assertFalse(previous.revision == next.revision)
            assertEquals(previous.outputSha256, next.outputSha256)
            assertArrayEquals(display, fixture.page.readBytes())
            assertNull(fixture.readWithoutMutation())
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertEquals(1, fixture.engine.freshCalls)
            assertEquals(1, fixture.engine.resumeCalls)
        }
    }

    @Test(timeout = 30_000)
    fun originalRestoreInvalidatesTranslatedRecordWithoutStartingInference(): Unit = runBlocking {
        withFixture(ScriptedEngine(Kind.TRANSLATED, progress(RegionState.TRANSLATED))) { fixture ->
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertNotNull(fixture.readWithoutMutation())
            val source = fixture.original.readBytes()
            assertTrue(fixture.translator.reRenderPage(fixture.chapter, PAGE, PageTranslator.ORIGINAL_METHOD))
            assertArrayEquals(source, fixture.page.readBytes())
            assertArrayEquals(source, fixture.original.readBytes())
            assertNull(fixture.readWithoutMutation())
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertEquals(1, fixture.engine.freshCalls)
            assertEquals(0, fixture.engine.resumeCalls)
        }
    }

    @Test(timeout = 30_000)
    fun sameByteRecaptureRetiresAndArchivesTheSavedSummary(): Unit = runBlocking {
        val noText = PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
        withFixture(ScriptedEngine(Kind.SKIPPED, noText)) { fixture ->
            assertEquals(0, fixture.translator.translateChapter(fixture.chapter, METHOD, nightMaterials = false))
            assertEquals(PageProcessingReason.NO_TEXT, requireNotNull(fixture.readWithoutMutation()).outcome.reason)
            val oldSummary = fixture.summary.readBytes()
            val source = fixture.page.readBytes()
            fixture.translator.replaceCapturedSource(fixture.chapter, PAGE, source)
            assertArrayEquals(source, fixture.page.readBytes())
            assertArrayEquals(source, fixture.original.readBytes())
            assertFalse(fixture.summary.exists())
            assertNull(fixture.readWithoutMutation())
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            val archives = fixture.materials.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("capture-") }
            assertTrue(
                "The retired summary must remain in a verified capture archive",
                archives.any { PartialPageStore.sha256(it.readBytes()) == PartialPageStore.sha256(oldSummary) },
            )
            assertEquals(1, fixture.engine.freshCalls)
            assertEquals(0, fixture.engine.resumeCalls)
        }
    }

    @Test(timeout = 30_000)
    fun corruptSummaryCannotChangeCompletionOrTriggerAnotherInference(): Unit = runBlocking {
        val noText = PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
        withFixture(ScriptedEngine(Kind.SKIPPED, noText)) { fixture ->
            assertEquals(0, fixture.translator.translateChapter(fixture.chapter, METHOD, nightMaterials = false))
            assertNotNull(fixture.readWithoutMutation())
            fixture.summary.writeText("{\"not\":\"a processing record\"}")
            val before = fixture.hashes()
            assertNull(fixture.readWithoutMutation())
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertEquals(0, fixture.translator.translateChapter(fixture.chapter, METHOD, nightMaterials = false))
            assertEquals(before, fixture.hashes())
            assertEquals(1, fixture.engine.freshCalls)
            assertEquals(0, fixture.engine.resumeCalls)
        }
    }

    @Test(timeout = 30_000)
    fun blockedOptionalSummaryWriteLeavesTheSourceAndCompletionValid(): Unit = runBlocking {
        val noText = PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
        withFixture(ScriptedEngine(Kind.SKIPPED, noText)) { fixture ->
            check(fixture.summary.mkdir())
            val sentinel = File(fixture.summary, "owned-sentinel").apply { writeText("keep this neutral fixture") }
            val sentinelBytes = sentinel.readBytes()
            assertEquals(0, fixture.translator.translateChapter(fixture.chapter, METHOD, nightMaterials = false))
            assertArrayEquals(fixture.initialBytes, fixture.page.readBytes())
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertEquals(setOf(PAGE), fixture.completionUnion())
            assertNull(fixture.readWithoutMutation())
            assertTrue(fixture.summary.isDirectory)
            assertArrayEquals(sentinelBytes, sentinel.readBytes())
            assertFalse(File(fixture.directory, ".yakuyomi_errors.txt").exists())
            assertEquals(1, fixture.engine.freshCalls)
            assertEquals(0, fixture.engine.resumeCalls)
            fixture.engine.assertReturnedBitmapsRecycled()
        }
    }

    private suspend fun withFixture(engine: ScriptedEngine, block: suspend (Fixture) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") {
            "Processing summary integration requires the isolated audit installation"
        }
        val directory = File(target.cacheDir, "processing-summary-${UUID.randomUUID()}").apply { check(mkdir()) }
        check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
        val isolatedFiles = File(directory, "private-files").apply { check(mkdir()) }
        val isolatedContext = object : ContextWrapper(target) {
            override fun getFilesDir(): File = isolatedFiles
        }
        val storageField = TranslationEngineConfig::class.java.getDeclaredField("storagePreferences").apply {
            isAccessible = true
        }
        val originalStorage = storageField.get(TranslationEngineConfig)
        val memoryStorage = StoragePreferences(
            object : FolderProvider {
                override fun directory(): File = directory
                override fun path(): String = ""
            },
            InMemoryPreferenceStore(),
        )
        var fixture: Fixture? = null
        try {
            storageField.set(TranslationEngineConfig, memoryStorage)
            assertSame(memoryStorage, storageField.get(TranslationEngineConfig))
            // Satisfy only the presence gate. A warmed scripted engine never parses these bytes.
            val models = File(isolatedFiles, "models").apply { check(mkdir()) }
            for (base in listOf("dbnet_detect.ncnn", "ocr_48px_ctc.ncnn", "aot")) {
                File(models, "$base.param").writeBytes(byteArrayOf(0))
                File(models, "$base.bin").writeBytes(byteArrayOf(0))
            }
            assertTrue(TranslationEngineConfig.modelsResolvable(isolatedContext))
            fixture = Fixture(isolatedContext, File(directory, "chapter").apply { check(mkdir()) }, engine)
            block(fixture)
        } finally {
            try {
                withContext(NonCancellable) { fixture?.close() }
            } finally {
                try {
                    storageField.set(TranslationEngineConfig, originalStorage)
                    assertSame(originalStorage, storageField.get(TranslationEngineConfig))
                } finally {
                    check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
                    assertTrue("Cannot remove owned summary fixture", directory.deleteRecursively())
                }
            }
        }
    }

    private class Fixture(val context: Context, val directory: File, val engine: ScriptedEngine) {
        val page = File(directory, PAGE).apply { writeBytes(encoded(Color.WHITE)) }
        val initialBytes = page.readBytes()
        val chapter = requireNotNull(UniFile.fromFile(directory))
        val pageFile = requireNotNull(UniFile.fromFile(page))
        val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
        val original = File(materials, OriginalMaterial.exactName(PAGE))
        val summary = File(materials, PageProcessingSummaryCodec.exactName(PAGE))
        val store get() = PartialPageStore.forUniFile(context, chapter, requireNotNull(UniFile.fromFile(materials)))
        private val preferences = TranslationPreferences(InMemoryPreferenceStore())
        private val service = TranslationEngineService(context).also {
            field("translationPreferences").set(it, preferences)
            val signature = TranslationEngineService::class.java.getDeclaredMethod(
                "configSignature",
                String::class.java,
                TranslationPreferences::class.java,
            ).apply { isAccessible = true }.invoke(it, METHOD, preferences.snapshot())
            field("builtSignature").set(it, signature)
            field("warmedUp").setBoolean(it, true)
            field("engine").set(it, engine)
        }
        val translator = PageTranslator(context).also {
            PageTranslator::class.java.getDeclaredField("translationPreferences").apply { isAccessible = true }
                .set(it, preferences)
            PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }
                .set(it, service)
        }

        suspend fun translate() = translator.translateSinglePage(chapter, PAGE, METHOD)

        suspend fun translateDetailed() = translator.translateSinglePageDetailed(chapter, PAGE, METHOD)

        suspend fun readWithoutMutation(): PageProcessingDetails? {
            val before = hashes()
            val calls = engine.freshCalls to engine.resumeCalls
            val result = translator.getPageProcessingDetails(pageFile)
            assertEquals("A saved-record query must not modify any owned file", before, hashes())
            assertEquals(
                "A saved-record query must not infer or resume",
                calls,
                engine.freshCalls to engine.resumeCalls,
            )
            val fromChapter = translator.getPageProcessingDetails(chapter, PAGE)
            assertEquals("Both explicit file and loader-owned chapter lookups must agree", result, fromChapter)
            assertEquals("The chapter lookup must not modify any owned file", before, hashes())
            assertEquals(
                "The chapter lookup must not infer or resume",
                calls,
                engine.freshCalls to engine.resumeCalls,
            )
            return result
        }

        fun hashes(): Map<String, String> = directory.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(directory).invariantSeparatorsPath to PartialPageStore.sha256(it.readBytes())
        }

        fun completionUnion(): Set<String> = CompletionManifestCodec.resolve(
            directory.listFiles().orEmpty().filter {
                it.isFile && (
                    it.name == CompletionManifestCodec.PRIMARY || it.name == CompletionManifestCodec.PREVIOUS ||
                        CompletionManifestCodec.isCheckpoint(it.name)
                    )
            }.associate { it.name to it.readBytes() },
        )

        suspend fun close() {
            try {
                service.shutdown()
                assertTrue("The fixture must close its scripted engine", engine.closed)
            } finally {
                engine.recycleRemaining()
            }
        }
    }

    private enum class Kind { SKIPPED, TRANSLATED, PARTIAL, FAILED }

    private class ScriptedEngine(
        private var firstKind: Kind,
        private var firstProgress: PageProgress?,
        private val finalProgress: PageProgress? = null,
        private val failure: ProcessingFailure? = null,
    ) : TranslationEngine {
        override var translationContext = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
            private set

        var freshCalls = 0
        var resumeCalls = 0
        var closed = false
        private val owned = mutableListOf<Bitmap>()
        val returnedBitmapCount get() = owned.size

        fun selectFresh(kind: Kind, progress: PageProgress?, language: String) {
            firstKind = kind
            firstProgress = progress
            translationContext = TranslationContext.fromTranslator(TranslatorConfig(toLangName = language))
        }

        override suspend fun translatePage(page: Bitmap): PageResult {
            freshCalls++
            assertEquals("Inference must use the neutral preserved source", Color.WHITE, page.getPixel(0, 0))
            return result(firstKind, firstProgress)
        }

        override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
            resumeCalls++
            assertEquals(Color.WHITE, original.getPixel(0, 0))
            val previous = requireNotNull(analysis.progress)
            assertEquals(firstProgress, previous)
            assertEquals("neutral target 0", previous.regions[0].translatedText)
            val final = requireNotNull(finalProgress)
            assertEquals(previous.regions[0], final.regions[0])
            return result(Kind.TRANSLATED, final)
        }

        private fun result(kind: Kind, progress: PageProgress?): PageResult {
            val stats = PageStats(
                progress?.regions?.sumOf { it.lines.size } ?: 0,
                progress?.regions?.size ?: 0,
                progress?.regions?.count { it.state == RegionState.TRANSLATED } ?: 0,
                0,
                0,
                0,
                0,
                0,
            )
            val analysis = progress?.let { PageAnalysis(ownedImage(Color.BLACK), it.toTextRegions(), it) }
            return when (kind) {
                // Deliberately identical reason text: classification must use explicit progress.
                Kind.SKIPPED -> PageResult.Skipped("No text detected", stats, analysis)
                Kind.TRANSLATED -> PageResult.Translated(ownedImage(Color.GREEN), stats, analysis)
                Kind.PARTIAL -> PageResult.Partial(ownedImage(Color.BLUE), stats, requireNotNull(analysis))
                Kind.FAILED -> PageResult.Failed("Neutral scripted current-attempt failure", failure)
            }
        }

        override fun warmUp() = Unit
        override fun close() {
            closed = true
        }
        fun assertReturnedBitmapsRecycled() {
            assertTrue("Public translation entry must release every returned bitmap", owned.all { it.isRecycled })
        }
        fun recycleRemaining() = owned.filterNot { it.isRecycled }.forEach { it.recycle() }
        private fun ownedImage(color: Int) = image(color).also { owned.add(it) }
    }

    companion object {
        private const val PAGE = "neutral.png"
        private const val SIZE = 32
        private const val METHOD = "boxfill"

        private fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }

        private fun image(color: Int) = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

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

        private fun assertPixels(color: Int, bytes: ByteArray) {
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals(SIZE, bitmap.width)
                assertEquals(SIZE, bitmap.height)
                val pixels = IntArray(SIZE * SIZE)
                bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
                assertTrue("Unexpected restored source pixels", pixels.all { it == color })
            } finally {
                bitmap.recycle()
            }
        }

        private fun progress(vararg states: RegionState, policyIds: Set<Int> = emptySet()) = PageProgress(
            SIZE,
            SIZE,
            states.mapIndexed { id, state ->
                RegionProgress(
                    id,
                    state,
                    listOf(
                        OcrLineProgress(
                            listOf(Pt(0f, 0f), Pt(16f, 0f), Pt(16f, 16f), Pt(0f, 16f)),
                            1f,
                            "h",
                            if (state == RegionState.OCR_PENDING) "" else "neutral source $id",
                            intentionallyKept = id in policyIds,
                        ),
                    ),
                    "h",
                    translatedText = if (state == RegionState.TRANSLATED) "neutral target $id" else "",
                )
            },
        ).also { it.validateFor(SIZE, SIZE) }
    }
}
