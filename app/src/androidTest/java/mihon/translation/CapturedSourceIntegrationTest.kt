package mihon.translation

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.io.IOException
import java.util.UUID

/** Public capture/storage entry points on private file:// fixtures; no Activity, native or HTTP. */
@RunWith(AndroidJUnit4::class)
class CapturedSourceIntegrationTest {
    @Test(timeout = 30_000)
    fun completedLegacyCaptureRetiresTargetArtifactsAndCheckpointUnionThenUsesNewSource(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.seed(SIBLING, complete = true)
            fixture.addOptionalMaterials(SIBLING)
            val siblingFiles = fixture.snapshot().filterKeys { it != TARGET }
            val old = fixture.seed(TARGET, complete = true, legacy = true)
            fixture.addOptionalMaterials(TARGET)
            fixture.writeCompletion(setOf(TARGET, SIBLING), checkpoint = true)
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, TARGET))
            val retired = fixture.targetAssets(siblingFiles.keys)
            val replacement = encoded(Color.RED)

            fixture.capture(replacement)

            fixture.assertFreshCapture(replacement)
            fixture.assertRetiredNamesInactive(retired)
            fixture.assertFilesPreserved(siblingFiles)
            fixture.assertRetiredAssetsArchived(retired)
            assertFalse(File(fixture.materials, old.original.name).exists())
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, TARGET))
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, SIBLING))
            assertEquals(setOf(SIBLING), fixture.completionUnion())
            val engine = fixture.select(ScriptedEngine(Color.RED))
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, engine.freshCalls)
            assertEquals(0, engine.resumeCalls)
            engine.assertReturnedBitmapsRecycled()
            val current = requireNotNull(fixture.store.load(TARGET))
            assertTrue(current.complete)
            assertEquals(null, current.baseRevision)
            assertEquals(1L, current.generation)
            assertEquals(sha(replacement), current.original.sha256)
            assertEquals(progress(true, "fresh source"), OcrProgressCodec.decode(current.progressPayload, SIZE, SIZE))
            assertPixels(Color.GREEN, fixture.page.readBytes())
            assertArrayEquals(replacement, File(fixture.materials, OriginalMaterial.exactName(TARGET)).readBytes())
            fixture.assertFilesPreserved(siblingFiles)
            fixture.assertRetiredAssetsArchived(retired)
            assertEquals(setOf(TARGET, SIBLING), fixture.completionUnion())
        }
    }

    @Test(timeout = 30_000)
    fun pendingCaptureDiscardsOldOcrTargetsAndStartsFreshWithoutResume(): Unit = runBlocking {
        withFixture { fixture ->
            val pending = fixture.seed(TARGET, complete = false)
            fixture.addOptionalMaterials(TARGET)
            val retired = fixture.targetAssets(emptySet())
            val replacement = encoded(Color.RED)

            fixture.capture(replacement)

            fixture.assertFreshCapture(replacement)
            fixture.assertRetiredNamesInactive(retired)
            fixture.assertRetiredAssetsArchived(retired)
            val engine = fixture.select(ScriptedEngine(Color.RED))
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, engine.freshCalls)
            assertEquals(0, engine.resumeCalls)
            engine.assertReturnedBitmapsRecycled()
            val current = requireNotNull(fixture.store.load(TARGET))
            assertTrue(current.complete)
            assertFalse(pending.revision == current.revision)
            assertEquals(null, current.baseRevision)
            assertEquals(1L, current.generation)
            val finalProgress = OcrProgressCodec.decode(current.progressPayload, SIZE, SIZE)
            assertEquals(progress(true, "fresh source"), finalProgress)
            assertTrue(finalProgress.regions.all { it.translatedText == "fresh translation" })
            assertPixels(Color.GREEN, fixture.page.readBytes())
            fixture.assertRetiredAssetsArchived(retired)
        }
    }

    @Test(timeout = 30_000)
    fun sameByteCaptureOfCompletedPageClearsCompletionAndKeepsOldSourceAndOutputInArchive(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.seed(TARGET, complete = true)
            fixture.addOptionalMaterials(TARGET)
            fixture.writeCompletion(setOf(TARGET), checkpoint = true)
            val oldDisplay = fixture.page.readBytes()
            val oldOriginal = File(fixture.materials, OriginalMaterial.exactName(TARGET)).readBytes()
            assertFalse(oldOriginal.contentEquals(oldDisplay))
            val retired = fixture.targetAssets(emptySet())

            fixture.capture(oldDisplay)

            fixture.assertFreshCapture(oldDisplay)
            fixture.assertRetiredNamesInactive(retired)
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, TARGET))
            assertEquals(emptySet<String>(), fixture.completionUnion())
            fixture.assertRetiredAssetsArchived(retired)
            val engine = fixture.select(ScriptedEngine(Color.BLUE))
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, engine.freshCalls)
            assertEquals(0, engine.resumeCalls)
            engine.assertReturnedBitmapsRecycled()
            assertArrayEquals(oldDisplay, File(fixture.materials, OriginalMaterial.exactName(TARGET)).readBytes())
            fixture.assertRetiredAssetsArchived(retired)
        }
    }

    @Test(timeout = 30_000)
    fun sameByteRecaptureRejectsAnAlreadyRunningTranslatedResult(): Unit = runBlocking {
        withFixture { fixture ->
            val sameBytes = fixture.page.readBytes()
            fixture.capture(sameBytes)
            assertStaleResultRejected(fixture, sameBytes, skipped = false, captures = 1)
        }
    }

    @Test(timeout = 30_000)
    fun sameByteRecaptureRejectsAlreadyRunningUnchangedClassification(): Unit = runBlocking {
        withFixture { fixture ->
            // No preserved source exists when inference starts: this reaches the public
            // Skipped -> completeUnchangedPageLocked branch, rather than stored-result commit.
            assertFalse(File(fixture.materials, OriginalMaterial.exactName(TARGET)).exists())
            assertStaleResultRejected(fixture, fixture.page.readBytes(), skipped = true, captures = 1)
        }
    }

    @Test(timeout = 30_000)
    fun repeatedSameByteCapturesInvalidateOldInferenceAcrossMultipleEpochs(): Unit = runBlocking {
        withFixture { fixture ->
            val sameBytes = fixture.page.readBytes()
            fixture.capture(sameBytes)
            assertStaleResultRejected(fixture, sameBytes, skipped = false, captures = 2)
        }
    }

    @Test(timeout = 30_000)
    fun invalidPngCaptureCannotLoseCompletedAssetsOrCompletion(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.seed(TARGET, complete = true, legacy = true)
            fixture.writeCompletion(setOf(TARGET), checkpoint = true)
            val before = fixture.snapshot()
            assertCaptureRejected(fixture, TARGET, byteArrayOf(0, 1, 2, 3))
            fixture.assertExactSnapshot(before)
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, TARGET))
        }
    }

    @Test(timeout = 30_000)
    fun nonPngFilenameCaptureCannotReplaceItsExistingBytes(): Unit = runBlocking {
        withFixture { fixture ->
            val name = "unsupported.jpg"
            File(fixture.directory, name).writeBytes(encoded(Color.MAGENTA))
            val before = fixture.snapshot()
            assertCaptureRejected(fixture, name, encoded(Color.RED))
            fixture.assertExactSnapshot(before)
        }
    }

    @Test(timeout = 30_000)
    fun missingPageCaptureCannotCreatePageOrRetireExistingAssets(): Unit = runBlocking {
        withFixture { fixture ->
            val before = fixture.snapshot()
            assertCaptureRejected(fixture, "missing.png", encoded(Color.RED))
            fixture.assertExactSnapshot(before)
        }
    }

    @Test(timeout = 30_000)
    fun corruptCompletionCaptureCannotDestroyPriorPageOrOriginal(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.seed(TARGET, complete = true, legacy = true)
            File(fixture.directory, CompletionManifestCodec.PRIMARY)
                .writeText("YOMITORI-MANIFEST-1\ncorrupt authority")
            val before = fixture.snapshot()
            assertCaptureRejected(fixture, TARGET, encoded(Color.RED))
            fixture.assertExactSnapshot(before)
        }
    }

    @Test(timeout = 30_000)
    fun legacyLiveResultWithoutPreInferenceCaptureTokenCannotOverwriteNewGeneration(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.capture(encoded(Color.RED))
            val before = fixture.snapshot()
            val source = image(Color.RED)
            val output = image(Color.GREEN)
            try {
                assertFalse(
                    fixture.translator.persistLivePage(
                        fixture.chapter,
                        requireNotNull(UniFile.fromFile(fixture.page)),
                        original = source,
                        translated = output,
                        analysis = null,
                        methodRaw = METHOD,
                    ),
                )
            } finally {
                source.recycle()
                output.recycle()
            }
            fixture.assertExactSnapshot(before)
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, TARGET))
        }
    }

    private suspend fun assertStaleResultRejected(
        fixture: Fixture,
        sameBytes: ByteArray,
        skipped: Boolean,
        captures: Int,
    ) = coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = fixture.select(ScriptedEngine(Color.WHITE, entered, release, skipped))
        val operation = async(Dispatchers.Default) {
            if (skipped) fixture.translator.translateChapter(fixture.chapter, METHOD) else fixture.translate()
        }
        try {
            withTimeout(5_000) { entered.await() }
            repeat(captures) { fixture.capture(sameBytes) }
            fixture.assertFreshCapture(sameBytes)
            val original = File(fixture.materials, OriginalMaterial.exactName(TARGET)).readBytes()
            val captureFiles = fixture.snapshot().filterKeys { it.startsWith(".yakuyomi/capture-") }
            assertTrue("A durable capture transaction must exist", captureFiles.isNotEmpty())
            release.complete(Unit)
            assertEquals(
                "An old inference cannot complete a recaptured source, even with identical bytes",
                if (skipped) 0 else PageTranslator.SinglePageResult.NOT_TRANSLATED,
                withTimeout(5_000) { operation.await() },
            )
            assertEquals(1, engine.freshCalls)
            assertEquals(0, engine.resumeCalls)
            engine.assertReturnedBitmapsRecycled()
            assertArrayEquals(sameBytes, fixture.page.readBytes())
            assertArrayEquals(original, File(fixture.materials, OriginalMaterial.exactName(TARGET)).readBytes())
            assertEquals(null, fixture.store.load(TARGET))
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, TARGET))
            assertEquals(emptySet<String>(), fixture.completionUnion())
            fixture.assertFilesPreserved(captureFiles)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { withTimeout(5_000) { operation.join() } }
        }
    }

    private suspend fun assertCaptureRejected(fixture: Fixture, pageName: String, bytes: ByteArray) {
        var rejected = false
        try {
            fixture.translator.replaceCapturedSource(fixture.chapter, pageName, bytes)
        } catch (_: IOException) {
            rejected = true
        } catch (_: IllegalArgumentException) {
            rejected = true
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue("Unsafe capture must fail explicitly", rejected)
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") {
            "Capture integration requires the isolated audit installation"
        }
        val directory = File(target.cacheDir, "captured-source-${UUID.randomUUID()}").apply { check(mkdir()) }
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
            // Satisfy only the public presence gate in our ContextWrapper. The warmed injected
            // engine never parses these private one-byte placeholders or loads actual models.
            val models = File(isolatedFiles, "models").apply { check(mkdir()) }
            for (base in listOf("dbnet_detect.ncnn", "ocr_48px_ctc.ncnn", "aot")) {
                File(models, "$base.param").writeBytes(byteArrayOf(0))
                File(models, "$base.bin").writeBytes(byteArrayOf(0))
            }
            assertTrue(TranslationEngineConfig.modelsResolvable(isolatedContext))
            val chapterDirectory = File(directory, "chapter").apply { check(mkdir()) }
            fixture = Fixture(isolatedContext, chapterDirectory)
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
                    assertTrue("Cannot remove owned capture fixture", directory.deleteRecursively())
                }
            }
        }
    }

    private class Fixture(val context: Context, val directory: File) {
        val page = File(directory, TARGET).apply { writeBytes(encoded(Color.WHITE)) }
        val chapter = requireNotNull(UniFile.fromFile(directory))
        val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
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
        }
        val translator = PageTranslator(context).also {
            PageTranslator::class.java.getDeclaredField("translationPreferences").apply { isAccessible = true }
                .set(it, preferences)
            PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }
                .set(it, service)
        }
        private val engines = mutableListOf<ScriptedEngine>()

        fun select(engine: ScriptedEngine): ScriptedEngine {
            check(engines.isEmpty()) { "A fixture owns exactly one scripted inference engine" }
            engines.add(engine)
            field("engine").set(service, engine)
            return engine
        }

        suspend fun capture(bytes: ByteArray) = translator.replaceCapturedSource(chapter, TARGET, bytes)
        suspend fun translate() = translator.translateSinglePage(chapter, TARGET, METHOD)

        fun seed(name: String, complete: Boolean, legacy: Boolean = false): PartialPageStore.State {
            val source = if (legacy) encoded(Color.WHITE, Bitmap.CompressFormat.WEBP_LOSSLESS) else encoded(Color.WHITE)
            val current = encoded(Color.WHITE)
            File(directory, name).writeBytes(current)
            val originalName = if (legacy) OriginalMaterial.legacyName(name) else OriginalMaterial.exactName(name)
            File(materials, originalName).writeBytes(source)
            return store.commit(
                name,
                PartialPageStore.Original(
                    originalName,
                    sha(source),
                    source.size.toLong(),
                    if (legacy) {
                        PartialPageStore.OriginalProvenance.LEGACY_REENCODED
                    } else {
                        PartialPageStore.OriginalProvenance.EXACT_BYTES
                    },
                ),
                SIZE, SIZE, OcrProgressCodec.encode(progress(complete, "old source")),
                encoded(Color.BLUE), encoded(Color.BLACK), null, complete,
                initialBaseline = if (legacy) PartialPageStore.InitialBaseline(current, sha(current)) else null,
                safeContextPayload = StoredTranslationContext.encode(SEMANTICS),
            )
        }

        fun addOptionalMaterials(name: String) {
            val base = OriginalMaterial.base(name)
            File(materials, "$base.json").writeText("{\"fixture\":\"old OCR $name\"}")
            File(materials, "$base.mask.png").writeBytes(encoded(Color.GRAY))
            File(materials, "$name.nightboxes.json").writeText("{\"fixture\":\"old night boxes $name\"}")
            File(materials, "$name.night.std.webp")
                .writeBytes(encoded(Color.DKGRAY, Bitmap.CompressFormat.WEBP_LOSSLESS))
            File(materials, "$name.night.more.webp")
                .writeBytes(encoded(Color.LTGRAY, Bitmap.CompressFormat.WEBP_LOSSLESS))
            File(materials, "$name.night.rules4").writeBytes(byteArrayOf())
        }

        fun writeCompletion(pages: Set<String>, checkpoint: Boolean) {
            val bytes = CompletionManifestCodec.encode(pages)
            File(directory, CompletionManifestCodec.PRIMARY).writeBytes(bytes)
            File(directory, CompletionManifestCodec.PREVIOUS).writeBytes(bytes)
            if (checkpoint) {
                File(directory, CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID()).writeBytes(bytes)
            }
            assertEquals(pages, completionUnion())
        }

        fun completionUnion(): Set<String> = CompletionManifestCodec.resolve(
            directory.listFiles().orEmpty().filter {
                it.isFile && (
                    it.name == CompletionManifestCodec.PRIMARY ||
                        it.name == CompletionManifestCodec.PREVIOUS || CompletionManifestCodec.isCheckpoint(it.name)
                    )
            }.associate { it.name to it.readBytes() },
        )

        fun snapshot(): Map<String, ByteArray> = directory.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(directory).invariantSeparatorsPath to it.readBytes()
        }

        fun targetAssets(siblingPaths: Set<String>): Map<String, ByteArray> = snapshot().filterKeys {
            it !in siblingPaths && (it == TARGET || it.startsWith(".yakuyomi/"))
        }

        fun assertFreshCapture(expected: ByteArray) {
            assertArrayEquals(expected, page.readBytes())
            assertArrayEquals(expected, File(materials, OriginalMaterial.exactName(TARGET)).readBytes())
            assertEquals(null, store.load(TARGET))
            assertFalse(translator.isPageTranslated(chapter, TARGET))
            for (name in listOf(
                "target.json",
                "target.mask.png",
                "$TARGET.nightboxes.json",
                "$TARGET.night.std.webp",
                "$TARGET.night.more.webp",
                "$TARGET.night.rules4",
            )) {
                assertFalse("Old target material remains active: $name", File(materials, name).exists())
            }
        }

        fun assertRetiredAssetsArchived(retired: Map<String, ByteArray>) {
            assertTrue(retired.isNotEmpty())
            val archives = materials.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("capture-") }
            assertTrue("Capture must durably retain old assets", archives.isNotEmpty())
            val archiveHashes = archives.map { sha(it.readBytes()) }.toSet()
            for ((name, bytes) in retired) {
                assertTrue("Missing archived bytes for $name", sha(bytes) in archiveHashes)
            }
            assertTrue(
                "Private capture blobs must not have reader image extensions",
                archives.none {
                    it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "avif")
                },
            )
        }

        fun assertRetiredNamesInactive(retired: Map<String, ByteArray>) {
            for (path in retired.keys) {
                if (path == TARGET || path == ".yakuyomi/${OriginalMaterial.exactName(TARGET)}") continue
                assertFalse("Retired target artifact is still active: $path", File(directory, path).exists())
            }
        }

        fun assertFilesPreserved(before: Map<String, ByteArray>) {
            for ((name, bytes) in before) {
                val actual = File(directory, name)
                assertTrue("Preserved file missing: $name", actual.isFile)
                assertArrayEquals("Preserved bytes changed: $name", bytes, actual.readBytes())
            }
        }

        fun assertExactSnapshot(before: Map<String, ByteArray>) {
            val after = snapshot()
            assertEquals("Rejected capture changed the file set", before.keys, after.keys)
            assertFilesPreserved(before)
        }

        suspend fun close() {
            try {
                service.shutdown()
                for (engine in engines) assertTrue("Owned scripted engine must close", engine.closed)
            } finally {
                engines.forEach { it.recycleRemaining() }
            }
        }
    }

    private class ScriptedEngine(
        private val expectedInput: Int,
        private val entered: CompletableDeferred<Unit>? = null,
        private val release: CompletableDeferred<Unit>? = null,
        private val skipped: Boolean = false,
    ) : TranslationEngine {
        override val translationContext = SEMANTICS
        var freshCalls = 0
        var resumeCalls = 0
        var closed = false
        private val owned = mutableListOf<Bitmap>()
        override suspend fun translatePage(page: Bitmap): PageResult {
            freshCalls++
            assertEquals("Capture must supply the new source pixels", expectedInput, page.getPixel(0, 0))
            entered?.complete(Unit)
            release?.await()
            val progress = if (skipped) {
                PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
            } else {
                progress(true, "fresh source")
            }
            val analysis = PageAnalysis(ownedImage(Color.BLACK), progress.toTextRegions(), progress)
            return if (skipped) {
                PageResult.Skipped("No text detected", STATS, analysis)
            } else {
                PageResult.Translated(ownedImage(Color.GREEN), STATS, analysis)
            }
        }
        override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
            resumeCalls++
            error("Recaptured pages must not resume OCR from the retired source")
        }
        override fun warmUp() = Unit
        override fun close() {
            closed = true
        }
        fun assertReturnedBitmapsRecycled() {
            assertEquals(if (skipped) 1 else 2, owned.size)
            assertTrue("Public entry point must release result bitmaps", owned.all { it.isRecycled })
        }
        fun recycleRemaining() = owned.filterNot { it.isRecycled }.forEach { it.recycle() }
        private fun ownedImage(color: Int) = image(color).also { owned.add(it) }
    }

    companion object {
        private const val TARGET = "target.png"
        private const val SIBLING = "sibling.png"
        private const val SIZE = 32
        private const val METHOD = "boxfill"
        private val SEMANTICS = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
        private val STATS = PageStats(1, 1, 1, 0, 0, 0, 0, 0)
        private fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        private fun sha(bytes: ByteArray) = PartialPageStore.sha256(bytes)
        private fun image(color: Int) = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        private fun encoded(color: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
            val bitmap = image(color)
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(format, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        private fun assertPixels(color: Int, bytes: ByteArray) {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(bitmap)
            requireNotNull(bitmap)
            try {
                assertEquals(SIZE, bitmap.width)
                assertEquals(SIZE, bitmap.height)
                val pixels = IntArray(SIZE * SIZE)
                bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
                assertTrue("Unexpected output pixels", pixels.all { it == color })
            } finally {
                bitmap.recycle()
            }
        }
        private fun progress(complete: Boolean, source: String) = PageProgress(
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
                            if (complete) source else "",
                        ),
                    ),
                    "h",
                    translatedText = if (complete) {
                        if (source == "fresh source") "fresh translation" else "old translation"
                    } else {
                        ""
                    },
                ),
            ),
        )
    }
}
