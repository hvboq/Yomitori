package mihon.translation

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.StoredTranslationContext
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Public stored-page correctness and descriptive wall time; no Activity, native model or HTTP. */
@RunWith(AndroidJUnit4::class)
class StoredPageScalingTest {
    @Test(timeout = 240_000)
    fun publicStoredResumePreservesEveryPageAcrossCompletedJournalCounts(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") {
            "Stored-page scaling requires the isolated audit installation"
        }
        val runId = UUID.randomUUID().toString()
        val directory = File(target.cacheDir, "stored-page-scaling-$runId").apply { check(mkdir()) }
        check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
        val reports = File(target.filesDir, "stored-page-scaling-audit").apply { check(isDirectory || mkdir()) }
        val reportFile = File(reports, "$runId.json")
        val isolatedFiles = File(directory, "files").apply { check(mkdir()) }
        val isolatedContext = object : ContextWrapper(target) {
            override fun getFilesDir(): File = isolatedFiles
        }
        val source = encoded(Color.WHITE)
        val pendingOutput = encoded(Color.BLUE)
        val finalOutput = encoded(Color.GREEN)
        val mask = encoded(Color.BLACK)
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
        val samples = JSONArray()
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("runId", runId)
            .put("inputWidth", SIZE)
            .put("inputHeight", SIZE)
            .put("sourcePngBytes", source.size)
            .put("sourcePngSha256", sha(source))
            .put("pendingOutputPngSha256", sha(pendingOutput))
            .put("expectedFinalPatternPngSha256", sha(finalOutput))
            .put("rawMaskPngSha256", sha(mask))
            .put("semanticContextFingerprint", SEMANTICS.semanticFingerprint)
            .put("nativeInferenceUsed", false)
            .put("externalTranslationServerUsed", false)
            .put("activityUsed", false)
            .put("defaultPreferencesOrDatabaseChanged", false)
            .put("realModelWeightsRead", false)
            .put("samples", samples)
        try {
            storageField.set(TranslationEngineConfig, memoryStorage)
            assertSame(memoryStorage, storageField.get(TranslationEngineConfig))
            // Only the public entry point's presence gate is satisfied. These files are never
            // parsed: a warmed scripted engine is installed before any operation is registered.
            val models = File(isolatedFiles, "models").apply { check(mkdir()) }
            for (base in listOf("dbnet_detect.ncnn", "ocr_48px_ctc.ncnn", "aot")) {
                File(models, "$base.param").writeBytes(byteArrayOf(0))
                File(models, "$base.bin").writeBytes(byteArrayOf(0))
            }
            assertTrue(TranslationEngineConfig.modelsResolvable(isolatedContext))
            withTimeout(180_000) {
                report.put(
                    "excludedPublicPathWarmup",
                    runCase(isolatedContext, directory, -1, 0, source, pendingOutput, finalOutput, mask),
                )
                val order = listOf(listOf(0, 16, 48), listOf(48, 0, 16), listOf(16, 48, 0))
                for ((round, counts) in order.withIndex()) {
                    for (count in counts) {
                        samples.put(
                            runCase(
                                isolatedContext,
                                directory,
                                round,
                                count,
                                source,
                                pendingOutput,
                                finalOutput,
                                mask,
                            ),
                        )
                    }
                }
            }
            assertEquals(9, samples.length())
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("passed", false).put("failureClass", failure.javaClass.name)
            throw failure
        } finally {
            try {
                storageField.set(TranslationEngineConfig, originalStorage)
                assertSame(originalStorage, storageField.get(TranslationEngineConfig))
                report.put("sharedStorageObjectRestored", true)
            } finally {
                check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
                report.put("ownFixtureDeleted", directory.deleteRecursively())
                reportFile.writeText(report.toString(2))
                println("STORED_PAGE_SCALING_REPORT=${reportFile.absolutePath}")
                assertTrue("Cannot remove private scaling fixture", report.getBoolean("ownFixtureDeleted"))
            }
        }
    }

    private suspend fun runCase(
        context: Context,
        parent: File,
        round: Int,
        completedCount: Int,
        source: ByteArray,
        pendingOutput: ByteArray,
        finalOutput: ByteArray,
        mask: ByteArray,
    ): JSONObject {
        val started = SystemClock.elapsedRealtimeNanos()
        val directory = File(parent, "case-${UUID.randomUUID()}").apply { check(mkdir()) }
        val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
        val chapter = requireNotNull(UniFile.fromFile(directory))
        val store = PartialPageStore.forUniFile(context, chapter, requireNotNull(UniFile.fromFile(materials)))
        val names = (0 until completedCount).map { "seed-$it.png" }.toSet()
        var service: TranslationEngineService? = null
        val engine = ScriptedEngine()
        try {
            for (name in names) {
                seed(store, directory, materials, name, source, finalOutput, mask, true)
            }
            val initial = seed(store, directory, materials, TARGET, source, pendingOutput, mask, false)
            File(directory, CompletionManifestCodec.PRIMARY).writeBytes(CompletionManifestCodec.encode(names))
            assertEquals(
                names,
                CompletionManifestCodec.decode(File(directory, CompletionManifestCodec.PRIMARY).readBytes()),
            )
            assertFalse(requireNotNull(store.load(TARGET)).complete)
            verifySeeds(store, materials, directory, names, source, finalOutput)
            val preferences = TranslationPreferences(InMemoryPreferenceStore())
            val activeService = TranslationEngineService(context)
            service = activeService
            field("translationPreferences").set(activeService, preferences)
            val signature = TranslationEngineService::class.java.getDeclaredMethod(
                "configSignature",
                String::class.java,
                TranslationPreferences::class.java,
            ).apply { isAccessible = true }.invoke(activeService, METHOD, preferences.snapshot())
            field("builtSignature").set(activeService, signature)
            engine.warmUp()
            field("warmedUp").setBoolean(activeService, true)
            field("engine").set(activeService, engine)
            val translator = PageTranslator(context).also {
                PageTranslator::class.java.getDeclaredField("translationPreferences").apply { isAccessible = true }
                    .set(it, preferences)
                PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }
                    .set(it, activeService)
            }
            val preparedAt = SystemClock.elapsedRealtimeNanos()
            // This is the complete public operation: bitmap decode/encode, hashing, JSON, journal
            // scans, manifest/readback and local file I/O. It is not a storage-only/native timing.
            val outcome = translator.translateSinglePage(chapter, TARGET, METHOD)
            val finishedAt = SystemClock.elapsedRealtimeNanos()
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, outcome)
            assertEquals(1, engine.resumeCalls)
            assertEquals(0, engine.freshCalls)
            engine.assertReturnedBitmapsRecycled()
            val current = requireNotNull(store.load(TARGET))
            assertTrue(current.complete)
            assertEquals(initial.revision, current.baseRevision)
            assertEquals(initial.generation + 1, current.generation)
            assertEquals(SEMANTICS, StoredTranslationContext.decode(requireNotNull(current.safeContextPayload)))
            assertEquals(progress(true), OcrProgressCodec.decode(current.progressPayload, SIZE, SIZE))
            assertArrayEquals(source, File(materials, OriginalMaterial.exactName(TARGET)).readBytes())
            val actualOutput = File(directory, TARGET).readBytes()
            assertFalse(actualOutput.contentEquals(pendingOutput))
            assertFinalOutputPattern(actualOutput)
            assertArrayEquals(actualOutput, store.readOutput(current))
            assertTrue(translator.isPageTranslated(chapter, TARGET))
            assertTrue(translator.isChapterTranslated(chapter))
            verifySeeds(store, materials, directory, names, source, finalOutput)
            val manifest = requireNotNull(
                CompletionManifestCodec.decode(File(directory, CompletionManifestCodec.PRIMARY).readBytes()),
            )
            assertEquals(names + TARGET, manifest)
            val verifiedAt = SystemClock.elapsedRealtimeNanos()
            return JSONObject()
                .put("round", round)
                .put("preexistingCompletedJournalPages", completedCount)
                .put("preexistingPendingTargetJournalPages", 1)
                .put("preparationWallNs", preparedAt - started)
                .put("publicOperationWallNs", finishedAt - preparedAt)
                .put("verificationWallNs", verifiedAt - finishedAt)
                .put("fakeEngineResumeCalls", engine.resumeCalls)
                .put("fakeEngineFreshCalls", engine.freshCalls)
                .put("returnedBitmapCount", 2)
                .put("returnedBitmapsRecycled", true)
                .put("originalSha256", sha(source))
                .put("displaySha256", sha(actualOutput))
                .put("displayWidth", SIZE)
                .put("displayHeight", SIZE)
                .put("displayAllPixelsArgb", Color.GREEN)
                .put("completedSeedCountAfter", names.size)
                .put("manifestCompletedCountAfter", manifest.size)
                .put("targetGenerationAfter", current.generation)
                .put("targetSemanticContextFingerprint", SEMANTICS.semanticFingerprint)
                .put("correctnessPassed", true)
        } finally {
            try {
                withContext(NonCancellable) {
                    service?.shutdown()
                    assertEquals("The owned fake engine must close", service != null, engine.closed)
                }
            } finally {
                engine.recycleRemaining()
                check(directory.canonicalFile.parentFile == parent.canonicalFile)
                check(directory.deleteRecursively()) { "Cannot remove private scaling case" }
            }
        }
    }

    private fun seed(
        store: PartialPageStore,
        directory: File,
        materials: File,
        name: String,
        source: ByteArray,
        output: ByteArray,
        mask: ByteArray,
        complete: Boolean,
    ): PartialPageStore.State {
        File(directory, name).writeBytes(source)
        val originalName = OriginalMaterial.exactName(name)
        File(materials, originalName).writeBytes(source)
        return store.commit(
            name,
            PartialPageStore.Original(originalName, sha(source), source.size.toLong()),
            SIZE, SIZE, OcrProgressCodec.encode(progress(complete)), output, mask, null, complete,
            safeContextPayload = StoredTranslationContext.encode(SEMANTICS),
        )
    }

    private fun verifySeeds(
        store: PartialPageStore,
        materials: File,
        directory: File,
        names: Set<String>,
        source: ByteArray,
        output: ByteArray,
    ) {
        for (name in names) {
            val state = requireNotNull(store.load(name))
            assertTrue(state.complete)
            assertEquals(1L, state.generation)
            assertEquals(SEMANTICS, StoredTranslationContext.decode(requireNotNull(state.safeContextPayload)))
            assertArrayEquals(source, File(materials, OriginalMaterial.exactName(name)).readBytes())
            assertArrayEquals(output, File(directory, name).readBytes())
            assertArrayEquals(output, store.readOutput(state))
        }
    }

    private fun assertFinalOutputPattern(bytes: ByteArray) {
        // Product PNG encoding deliberately strips an entirely opaque alpha channel. Validate
        // every displayed pixel, while the caller separately checks journal/display byte identity.
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertEquals(SIZE, bitmap.width)
            assertEquals(SIZE, bitmap.height)
            val pixels = IntArray(SIZE * SIZE)
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            assertTrue("Every output pixel must match the final pattern", pixels.all { it == Color.GREEN })
        } finally {
            bitmap.recycle()
        }
    }

    private class ScriptedEngine : TranslationEngine {
        override val translationContext = SEMANTICS
        var freshCalls = 0
        var resumeCalls = 0
        var closed = false
        private val returnedBitmaps = mutableListOf<Bitmap>()
        override suspend fun translatePage(page: Bitmap): PageResult {
            freshCalls++
            error("Expected same-context pending resume")
        }
        override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
            resumeCalls++
            assertEquals(Color.WHITE, original.getPixel(0, 0))
            assertEquals(Color.BLACK, analysis.mask.getPixel(0, 0))
            assertEquals(progress(false), analysis.progress)
            val finalProgress = progress(true)
            return PageResult.Translated(
                ownedImage(Color.GREEN),
                PageStats(1, 1, 1, 0, 0, 0, 0, 0),
                PageAnalysis(ownedImage(Color.BLACK), finalProgress.toTextRegions(), finalProgress),
            )
        }
        fun assertReturnedBitmapsRecycled() {
            assertEquals(2, returnedBitmaps.size)
            assertTrue("Public operation must release its output and mask", returnedBitmaps.all { it.isRecycled })
        }
        fun recycleRemaining() {
            returnedBitmaps.filterNot { it.isRecycled }.forEach { it.recycle() }
        }
        private fun ownedImage(color: Int) = image(color).also { returnedBitmaps.add(it) }
        override fun warmUp() = Unit
        override fun close() {
            closed = true
        }
    }

    companion object {
        private const val SIZE = 32
        private const val TARGET = "target.png"
        private const val METHOD = "boxfill"
        private val SEMANTICS = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
        private fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        private fun sha(bytes: ByteArray) = PartialPageStore.sha256(bytes)
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
        private fun progress(complete: Boolean) = PageProgress(
            SIZE,
            SIZE,
            listOf(
                RegionProgress(
                    0,
                    if (complete) RegionState.TRANSLATED else RegionState.OCR_PENDING,
                    listOf(
                        OcrLineProgress(
                            listOf(Pt(0f, 0f), Pt(16f, 0f), Pt(16f, 16f), Pt(0f, 16f)),
                            1f,
                            "h",
                            if (complete) "neutral source" else "",
                        ),
                    ),
                    "h",
                    translatedText = if (complete) "중립" else "",
                ),
            ),
        )
    }
}
