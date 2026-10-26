// This test-only package permits delegation through UniFile's package-private constructor.
package com.hippo.unifile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.nightread.NightSourceRevision
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageProcessingSummaryCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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
import mihon.translation.NativeDiagnosticIdleGuard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Descriptive persistence isolation, not an OCR/server benchmark or an optimization gate.
 * Each measured chapter starts with exactly 0 or 149 genuinely completed pages, no journals.
 * Six unwrapped RawFile samples are kept separate from two counted-decorator observations.
 * All UUID-owned evidence remains on device until the coordinator has exported and checked it.
 */
@RunWith(AndroidJUnit4::class)
class PersistCompletedChapterScalingTest {
    @Test(timeout = 900_000)
    fun identicalLiveResultAcrossCompletedChapterSizes(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val explicitlyEnabled = arguments.getString("persistCompletedChapterScaling")
        check(explicitlyEnabled == "true") {
            "Explicit persistCompletedChapterScaling=true opt-in required"
        }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") {
            "Isolated audit installation required"
        }
        val idleBefore = NativeDiagnosticIdleGuard.requireIdle(context)
        val settings = SettingsGuard(context)
        val runId = UUID.randomUUID().toString()
        val root = File(context.filesDir.canonicalFile, "persist-completed-scaling-$runId")
        check(root.canonicalFile.parentFile == context.filesDir.canonicalFile)
        check(!root.exists() && root.mkdir())
        val reportFile = File(root, "report.json")
        val inventoryFile = File(root, "inventory.json")
        val rows = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
            .put("diagnostic", "identical persistLivePage at 0/149 prior complete pages")
            .put("targetPackage", context.packageName).put("sdkInt", Build.VERSION.SDK_INT)
            .put("deviceModel", Build.MODEL).put("idleBefore", idleBefore)
            .put("nativeCallsByTest", 0).put("serverCallsByTest", 0).put("sharedPreferencesWritesByTest", 0)
            .put("globalStorageOrModelConfigurationChanged", false).put("activityUsed", false)
            .put("fixturePreservedForVerifiedExport", true).put("samples", rows)
            .put("plannedBodyPagesIncludingWarmup", 4 * PRIOR + 9)
            .put("plannedNormalEvidenceFilesWithTwoManifestsPerChapter", (4 * PRIOR + 9) * 6 + 9 * 2)
            .put("inventoryFileLimit", 8192).put("inventoryByteLimit", 512L * 1024 * 1024)
            .put("timedOperation", "one identical public PageTranslator.persistLivePage only")
            .put("mainTimingStorage", "unwrapped UniFile.fromFile private RawFile")
            .put("outputHasAlphaBeforeEveryPersist", true)
            .put("outputHasAlphaAfterSuccessfulPngEncode", false)
            .put("opaquePngMetadataInitializationOutsideTimer", true)
            .put("listingObservationScope", "same private RawFile through a separate UniFile decorator")
            .put("listingCounterIsKernelSyscallCounter", false)
            .put(
                "limitations",
                JSONArray(
                    listOf(
                        "Small descriptive warm-cache sample; no native OCR, HTTP, SAF or 45-minute claim",
                        "Counted samples include child-wrapper allocation; kept separate from raw timings",
                        "Internal persistence phases have no production hook; only the whole call is timed",
                        "Method auto_whole is saved metadata; the scripted engine performs no inpainting",
                    ),
                ),
            )
        val start = now()
        val translator = PageTranslator(context)
        val preferenceField = PageTranslator::class.java.getDeclaredField("translationPreferences").apply {
            isAccessible = true
        }
        val sharedPreferences = preferenceField.get(translator)
        val privatePreferences = TranslationPreferences(InMemoryPreferenceStore()).apply {
            segThreshold.set("0.5")
        }
        preferenceField.set(translator, privatePreferences)
        val engine = ScriptedEngine()
        var original: Bitmap? = null
        var translated: PageResult.Translated? = null
        var failure: Throwable? = null
        try {
            withTimeout(840_000) {
                report.put("phase", "prepare-scripted-result")
                val prepareStart = now()
                val source = instrumentation.context.assets.open(ASSET).use { it.readBytes() }
                check(source.size == 52758 && sha(source) == SOURCE_SHA)
                val input = checkNotNull(BitmapFactory.decodeByteArray(source, 0, source.size))
                original = input
                check(input.width == WIDTH && input.height == HEIGHT)
                engine.warmUp()
                val result = engine.translatePage(input) as? PageResult.Translated
                    ?: error("Scripted engine must return a complete result")
                translated = result
                val expected = Expected(source, input, result)
                report.put("input", expected.metadata()).put("fakeEngineCalls", engine.calls)
                val warm = newCase(root, "warmup-0", 0, false, "excluded-warmup", -1)
                report.put("phase", "excluded-warmup").put("activeCase", warm.directory.name)
                rows.put(measure(context, translator, warm, expected))
                report.put("input", expected.metadata())

                val cases = listOf(
                    newCase(root, "raw-r1-small", 0, false, "raw-measured", 0),
                    newCase(root, "raw-r1-large", PRIOR, false, "raw-measured", 0),
                    newCase(root, "raw-r2-large", PRIOR, false, "raw-measured", 1),
                    newCase(root, "raw-r2-small", 0, false, "raw-measured", 1),
                    newCase(root, "raw-r3-small", 0, false, "raw-measured", 2),
                    newCase(root, "raw-r3-large", PRIOR, false, "raw-measured", 2),
                    newCase(root, "counted-small", 0, true, "counted-observation", -1),
                    newCase(root, "counted-large", PRIOR, true, "counted-observation", -1),
                )
                val large = cases.filter { it.prior == PRIOR }
                val seedStart = now()
                report.put("phase", "seed149-and-copy").put("activeCase", large.first().directory.name)
                seed(context, translator, large.first().directory, expected)
                val template = snapshot(large.first().directory)
                large.drop(1).forEach { case ->
                    copyChapter(large.first().directory, case.directory)
                    check(snapshot(case.directory) == template) {
                        "Seed copy differs from the actual persisted seed"
                    }
                }
                expected.verifyBorrowed()
                report.put("seedPreparationMs", ms(now() - seedStart))
                    .put("seedPagesWrittenViaPublicPersist", PRIOR)
                    .put("largeChapterCopiesBeforeMeasurement", large.size - 1)
                    .put("eachLargeChapterStartsWithCompletedPages", PRIOR)
                    .put(
                        "seedMaterialKindsPerPage",
                        JSONArray(
                            listOf("original", "mask", "OCR JSON", "summary", "Night revision"),
                        ),
                    )
                    .put("allPreparationMs", ms(now() - prepareStart))
                for (case in cases) {
                    report.put("phase", "measure-and-verify").put("activeCase", case.directory.name)
                    val row = measure(context, translator, case, expected)
                    rows.put(row)
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString("stream", "PERSIST_COMPLETED_SAMPLE=${row}\n")
                        },
                    )
                }
                val raw = (0 until rows.length()).map { rows.getJSONObject(it) }
                    .filter { it.getString("kind") == "raw-measured" }
                check(
                    raw.size == 6 && listOf(0, PRIOR).all { count ->
                        raw.count { it.getInt("priorCompleted") == count } == 3
                    },
                )
                report.put("rawTimingSummary", timingSummary(raw))
                expected.verifyBorrowed()
                report.put("phase", "complete").put("activeCase", JSONObject.NULL)
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                preferenceField.set(translator, sharedPreferences)
                check(preferenceField.get(translator) === sharedPreferences)
                settings.verify()
                report.put("settings", settings.metadata())
                    .put("ownedTranslatorPreferenceReferenceRestored", true)
                report.put("idleAfter", NativeDiagnosticIdleGuard.requireIdle(context))
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
            translated?.analysis?.mask?.takeUnless { it.isRecycled }?.recycle()
            translated?.page?.takeUnless { it.isRecycled }?.recycle()
            original?.takeUnless { it.isRecycled }?.recycle()
            engine.close()
            report.put("complete", failure == null).put("runWallMs", ms(now() - start))
                .put("fakeEngineClosed", engine.closed)
                .put("failureClass", failure?.javaClass?.name ?: JSONObject.NULL)
            // Root owns export/cleanup. Never remove successful or failed fixture evidence here.
            val inventory = snapshot(root, setOf("report.json", "inventory.json"))
            val inventoryBytes = JSONObject().put("schemaVersion", 1).put("runId", runId)
                .put(
                    "entries",
                    JSONArray(
                        inventory.map { (path, entry) ->
                            JSONObject().put("path", path).put("bytes", entry.bytes).put("sha256", entry.sha256)
                        },
                    ),
                ).toString(2).toByteArray(Charsets.UTF_8)
            check(inventoryBytes.size <= 2 * 1024 * 1024)
            inventoryFile.writeBytes(inventoryBytes)
            report.put("evidenceFileCount", inventory.size)
                .put("evidenceBytes", inventory.values.sumOf { it.bytes })
                .put("inventoryFile", inventoryFile.name).put("inventoryBytes", inventoryBytes.size)
                .put("inventorySha256", sha(inventoryBytes))
            val reportBytes = report.toString(2).toByteArray(Charsets.UTF_8)
            check(reportBytes.size <= 256 * 1024)
            reportFile.writeBytes(reportBytes)
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    val stream = "PERSIST_COMPLETED_EXPORT runId=$runId complete=${failure == null}\n"
                    putString("stream", stream)
                    putString("persistCompletedRunId", runId)
                    putString("persistCompletedRootPath", root.path)
                    putString("persistCompletedReportPath", reportFile.path)
                    putLong("persistCompletedReportBytes", reportBytes.size.toLong())
                    putString("persistCompletedReportSha256", sha(reportBytes))
                    putString("persistCompletedInventoryPath", inventoryFile.path)
                    putLong("persistCompletedInventoryBytes", inventoryBytes.size.toLong())
                    putString("persistCompletedInventorySha256", sha(inventoryBytes))
                },
            )
        }
        failure?.let { throw it }
    }

    private suspend fun seed(
        context: Context,
        translator: PageTranslator,
        directory: File,
        expected: Expected,
    ) {
        val chapter = checkNotNull(UniFile.fromFile(directory))
        repeat(PRIOR) { index ->
            val name = priorName(index)
            val body = File(directory, name).apply { writeBytes(expected.source) }
            expected.preparePersistCall()
            check(
                translator.persistLivePage(
                    chapter,
                    checkNotNull(UniFile.fromFile(body)),
                    expected.original,
                    expected.result.page,
                    expected.analysis,
                    METHOD,
                ),
            ) { "Public seed persistence failed at $index" }
        }
        verifyCompleted(context, translator, directory, priorNames(), expected)
    }

    private suspend fun measure(
        context: Context,
        translator: PageTranslator,
        case: Case,
        expected: Expected,
    ): JSONObject {
        val row = JSONObject().put("case", case.directory.name)
            .put("kind", case.kind).put("round", case.round)
            .put("priorCompleted", case.prior).put("counterDecorator", case.counted)
        var stage = now()
        val names = if (case.prior == 0) emptySet() else priorNames()
        val pre = verifyCompleted(context, translator, case.directory, names, expected)
        check(pre.getInt("completePages") == case.prior)
        check(!File(case.directory, TARGET).exists())
        val body = File(case.directory, TARGET).apply { writeBytes(expected.source) }
        expected.verifyBorrowed()
        val before = snapshot(case.directory)
        val counts = ListingCounts()
        val rawChapter = checkNotNull(UniFile.fromFile(case.directory))
        check(rawChapter.uri.scheme == "file")
        val chapter = if (case.counted) CountedUniFile(rawChapter, counts, null) else rawChapter
        val page = if (case.counted) {
            checkNotNull(chapter.findFile(TARGET))
        } else {
            checkNotNull(UniFile.fromFile(body))
        }
        row.put("prepareAndPreverifyMs", ms(now() - stage)).put("before", pre)
            .put("actualRawBackendClass", rawChapter.javaClass.name)
            .put("inventoryBefore", inventorySummary(before))
            .put("beforeInventorySha256", inventoryDigest(before))
        // The production PNG encoder scans opaque ARGB images, then clears hasAlpha. Restore
        // the same initial metadata before EVERY warm-up, seed, raw and counted operation.
        // This restores only test-owned metadata; pixel/config/density checks remain mandatory.
        val metadataStart = now()
        row.put("persistInputBitmap", expected.preparePersistCall())
            .put("bitmapMetadataPreparationMs", ms(now() - metadataStart))
        counts.reset()
        stage = now()
        val success = translator.persistLivePage(
            chapter,
            page,
            expected.original,
            expected.result.page,
            expected.analysis,
            METHOD,
        )
        val persistNanos = now() - stage
        row.put("persistMs", ms(persistNanos)).put("persistSucceeded", success)
        row.put("listing", if (case.counted) counts.report() else JSONObject.NULL)
        if (case.counted) {
            check(counts.totalCalls > 0) { "Counter decorator did not observe the persistence path" }
        }
        check(success) { "Measured persistence failed for ${case.directory.name}" }
        stage = now()
        expected.verifyBorrowed()
        if (case.kind == "excluded-warmup") {
            check(expected.maskBytesSha.isEmpty() && expected.materialBytesSha.isEmpty())
            // Pin the real binary encoder/JSON bytes only after independent pixel verification.
            // Production binary masks retain alpha metadata even when the raw mask is opaque.
            verifyDecoded(File(case.directory, "$MATERIALS/target.mask.png"), expected.maskArgb)
            expected.maskBytesSha = sha(File(case.directory, "$MATERIALS/target.mask.png"))
            expected.materialBytesSha = sha(File(case.directory, "$MATERIALS/target.json"))
        }
        val afterNames = names + TARGET
        val post = verifyCompleted(context, translator, case.directory, afterNames, expected)
        val after = snapshot(case.directory)
        before.filterKeys { !isManifest(it) && it != TARGET }.forEach { (path, entry) ->
            check(after[path] == entry) { "A prior page/material changed: $path" }
        }
        check(before.getValue(TARGET).sha256 == SOURCE_SHA)
        check(after.getValue(TARGET).sha256 == expected.outputBytesSha)
        verifyDecoded(File(case.directory, TARGET), expected.outputArgb)
        verifyDecoded(
            File(case.directory, "$MATERIALS/${OriginalMaterial.exactName(TARGET)}"),
            expected.sourceArgb,
        )
        verifyDecoded(File(case.directory, "$MATERIALS/target.mask.png"), expected.maskArgb)
        row.put("verifyMs", ms(now() - stage)).put("after", post)
            .put("inventoryAfter", inventorySummary(after))
            .put("afterInventorySha256", inventoryDigest(after)).put("priorEntryBytesPreserved", true)
            .put("targetOriginalBytesPreserved", true)
            .put("callerBitmapPixelsDensityConfigAndProgressPreserved", true)
            .put("encoderClearedOutputAlphaAsExpected", !expected.result.page.hasAlpha())
            .put("decodedOutputOriginalMaskPixelsVerified", true)
        return row
    }

    /** Exact material inventory also rejects journals, inflight originals and capture barriers. */
    private suspend fun verifyCompleted(
        context: Context,
        translator: PageTranslator,
        directory: File,
        names: Set<String>,
        expected: Expected,
    ): JSONObject {
        val files = checkNotNull(directory.listFiles())
        val materialDirectory = File(directory, MATERIALS)
        val manifestFiles = files.filter { isManifest(it.name) }.associate { it.name to it.readBytes() }
        check(CompletionManifestCodec.resolve(manifestFiles) == names)
        if (names.isNotEmpty()) {
            val primary = checkNotNull(manifestFiles[CompletionManifestCodec.PRIMARY])
            check(CompletionManifestCodec.decode(primary) == names)
            check(
                manifestFiles.any { (name, bytes) ->
                    CompletionManifestCodec.isCheckpoint(name) && CompletionManifestCodec.decode(bytes) == names
                },
            )
        } else {
            check(manifestFiles.isEmpty() && !materialDirectory.exists())
        }
        val materialFiles = materialDirectory.listFiles()?.toList().orEmpty()
        val allowedMaterials = names.flatMap { materialNames(it) }.toSet()
        check(materialFiles.map { it.name }.toSet() == allowedMaterials)
        check(materialFiles.size == names.size * 5)
        check(files.filter { !it.isDirectory && !isManifest(it.name) }.map { it.name }.toSet() == names)
        val expectedDirectories = if (names.isEmpty()) emptySet() else setOf(MATERIALS)
        check(files.filter { it.isDirectory }.map { it.name }.toSet() == expectedDirectories)
        val chapter = checkNotNull(UniFile.fromFile(directory))
        val store = if (names.isEmpty()) {
            null
        } else {
            PartialPageStore.forUniFile(
                context,
                chapter,
                checkNotNull(UniFile.fromFile(materialDirectory)),
            )
        }
        for (name in names) {
            check(translator.isPageTranslated(chapter, name))
            check(translator.hasDisplayableTranslation(chapter, name))
            check(translator.hasReRenderMaterials(chapter, name))
            check(store?.load(name, recover = false) == null) {
                "Complete fixture must not have any partial journal"
            }
            val base = OriginalMaterial.base(name)
            val display = File(directory, name)
            val original = File(materialDirectory, OriginalMaterial.exactName(name))
            val mask = File(materialDirectory, "$base.mask.png")
            val json = File(materialDirectory, "$base.json")
            check(sha(display) == expected.outputBytesSha && sha(original) == SOURCE_SHA)
            check(sha(mask) == expected.maskBytesSha && sha(json) == expected.materialBytesSha)
            val saved = Json.decodeFromString<PageMaterials>(json.readText())
            check(saved.method == METHOD && saved.regions.size == 4)
            check(saved.regions.none { it.preserveOriginal })
            saved.regions.zip(expected.analysis.regions).forEach { (actual, wanted) ->
                check(actual.source == wanted.sourceText && actual.target == wanted.translatedText)
                check(actual.quads == wanted.lines.map { line -> line.quad.map { listOf(it.x, it.y) } })
                check(actual.angle == wanted.angle && actual.onArt == wanted.onArt)
                check(actual.direction == wanted.direction)
                check(actual.bbox == listOf(wanted.x0, wanted.y0, wanted.x1, wanted.y1))
                check(actual.cx == wanted.cx && actual.cy == wanted.cy)
                check(actual.boxW == wanted.boxW && actual.boxH == wanted.boxH)
            }
            val revision = NightSourceRevision.decode(
                File(materialDirectory, NightSourceRevision.fileName(name)).readBytes(),
            )
            check(revision.committed && revision.capturedEpoch == null)
            check(revision.kind == NightSourceRevision.Kind.SOURCE)
            val summary = checkNotNull(
                PageProcessingSummaryCodec.decode(
                    File(materialDirectory, PageProcessingSummaryCodec.exactName(name)).readBytes(),
                ),
            )
            check(summary.binding.pageNameSha256 == sha(name.toByteArray(Charsets.UTF_8)))
            check(summary.binding.sourceSha256 == SOURCE_SHA)
            check(summary.binding.displaySha256 == expected.outputBytesSha)
            check(summary.binding.journalRevision == null && summary.binding.contextSha256 == null)
            val uncaptured = sha("processing-source-v1:uncaptured".toByteArray(Charsets.UTF_8))
            check(summary.binding.sourceTokenSha256 == uncaptured)
            val details = checkNotNull(translator.getPageProcessingDetails(chapter, name)) {
                "Actual reader metadata query rejected copied source/display/epoch/context binding"
            }
            check(details.outcome == summary.outcome)
            check(details.recordedAtEpochMs == summary.recordedAtEpochMs)
        }
        return JSONObject().put("completePages", names.size).put("bodyFiles", names.size)
            .put("materialFiles", materialFiles.size)
            .put("materialBytes", materialFiles.sumOf { it.length() })
            .put("directEntries", files.size).put("directFiles", files.count { it.isFile })
            .put("manifestFiles", manifestFiles.size).put("partialJournalFiles", 0)
            .put("primaryAndCurrentCheckpointVerified", names.isNotEmpty())
            .put("allFiveMaterialsPerPageVerified", true)
            .put("actualCompletionDisplayMaterialsAndBoundSummaryQueriesVerified", true)
            .put("capturedEpochAndJournalAndHistoricalContext", "absent; uncaptured source binding verified")
    }

    private class Expected(
        val source: ByteArray,
        val original: Bitmap,
        val result: PageResult.Translated,
    ) {
        val analysis = checkNotNull(result.analysis)
        private val progress = checkNotNull(analysis.progress)
        private val progressBytes = OcrProgressCodec.encode(progress)
        val sourceArgb = bitmapSha(original)
        val outputArgb = bitmapSha(result.page)
        val maskArgb = bitmapSha(analysis.mask)
        private val sourceDensity = original.density
        private val outputDensity = result.page.density
        private val maskDensity = analysis.mask.density
        private val sourceAlpha = original.hasAlpha()
        private val outputAlpha = result.page.hasAlpha()
        private val maskAlpha = analysis.mask.hasAlpha()
        private val sourceConfig = original.config
        private val outputConfig = result.page.config
        private val maskConfig = analysis.mask.config
        val outputBytesSha = sha(encoded(result.page))
        var maskBytesSha: String = ""

        // Production JSON is validated against actual fields and pinned after the real warm-up write.
        var materialBytesSha: String = ""
        fun preparePersistCall(): JSONObject {
            verifyBorrowed()
            check(!outputAlpha) { "Scripted baseline must have canonical opaque PNG bytes" }
            result.page.setHasAlpha(true)
            check(result.page.hasAlpha())
            check(result.page.config == outputConfig && result.page.density == outputDensity)
            check(bitmapSha(result.page) == outputArgb)
            return JSONObject().put("hasAlpha", result.page.hasAlpha()).put("config", outputConfig?.name)
                .put("density", outputDensity).put("width", result.page.width)
                .put("height", result.page.height)
                .put("argbSha256", outputArgb).put("metadataResetOutsidePersistTimer", true)
        }
        fun verifyBorrowed() {
            check(!original.isRecycled && !result.page.isRecycled && !analysis.mask.isRecycled)
            check(bitmapSha(original) == sourceArgb && bitmapSha(result.page) == outputArgb)
            check(bitmapSha(analysis.mask) == maskArgb)
            check(original.density == sourceDensity && result.page.density == outputDensity)
            check(analysis.mask.density == maskDensity)
            check(original.hasAlpha() == sourceAlpha && result.page.hasAlpha() == outputAlpha)
            check(analysis.mask.hasAlpha() == maskAlpha)
            check(original.config == sourceConfig && result.page.config == outputConfig)
            check(analysis.mask.config == maskConfig)
            check(analysis.progress == progress && !progress.hasPending && progress.regions.size == 4)
            check(OcrProgressCodec.encode(checkNotNull(analysis.progress)) == progressBytes)
        }
        fun metadata() = JSONObject().put("asset", ASSET).put("sourceBytes", source.size)
            .put("sourceSha256", SOURCE_SHA).put("width", WIDTH).put("height", HEIGHT)
            .put("sourceArgbSha256", sourceArgb).put("outputArgbSha256", outputArgb)
            .put("maskArgbSha256", maskArgb)
            .put("outputPngSha256", outputBytesSha).put("storedBinaryMaskPngSha256", maskBytesSha)
            .put("storedMaterialsSha256", materialBytesSha)
            .put("storedMaterialPinsFromVerifiedWarmup", true)
            .put("progressPayloadSha256", sha(progressBytes.toByteArray(Charsets.UTF_8)))
            .put("completeRegions", 4)
    }

    /** Four complete, neutral regions; no native library, model, renderer or client exists here. */
    private class ScriptedEngine : TranslationEngine {
        override val translationContext = TranslationContext.fromTranslator(
            TranslatorConfig(toLangName = "Korean"),
        )
        var calls = 0
        var closed = false
        override suspend fun translatePage(page: Bitmap): PageResult {
            check(!closed && calls++ == 0 && page.width == WIDTH && page.height == HEIGHT)
            val output = checkNotNull(page.copy(Bitmap.Config.ARGB_8888, true)).apply {
                setHasAlpha(false)
            }
            val mask = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLACK)
                density = page.density
                setHasAlpha(false)
            }
            val regions = (0 until 4).map { id ->
                val left = 100f + (id % 2) * 550f
                val top = 100f + (id / 2) * 700f
                val quad = listOf(
                    Pt(left, top),
                    Pt(left + 180f, top),
                    Pt(left + 180f, top + 120f),
                    Pt(left, top + 120f),
                )
                Canvas(output).drawRect(
                    left,
                    top,
                    left + 180f,
                    top + 120f,
                    Paint().apply { color = Color.GREEN },
                )
                Canvas(mask).drawRect(
                    left,
                    top,
                    left + 180f,
                    top + 120f,
                    Paint().apply { color = Color.WHITE },
                )
                RegionProgress(
                    id,
                    RegionState.TRANSLATED,
                    listOf(OcrLineProgress(quad, 1f, "h", "Neutral source region $id.")),
                    "h",
                    translatedText = "중립 시험 문장 $id.",
                )
            }
            val progress = PageProgress(WIDTH, HEIGHT, regions)
            return PageResult.Translated(
                output,
                PageStats(4, 4, 4, 0, 0, 0, 0, 0),
                PageAnalysis(mask, progress.toTextRegions(), progress),
            )
        }
        override fun warmUp() = Unit
        override fun close() {
            closed = true
        }
    }

    private data class Case(
        val directory: File,
        val prior: Int,
        val counted: Boolean,
        val kind: String,
        val round: Int,
    )
    private data class Entry(val bytes: Long, val sha256: String)

    private class ListingCounts {
        data class Bucket(var calls: Int = 0, var entries: Long = 0, var delegateNanos: Long = 0)
        private val buckets = linkedMapOf<String, Bucket>()
        val totalCalls get() = buckets.values.sumOf { it.calls }
        fun record(directoryName: String?, entries: Int, nanos: Long) {
            val key = if (directoryName == MATERIALS) "materials" else "chapter"
            buckets.getOrPut(key) { Bucket() }.apply {
                calls++
                this.entries += entries
                delegateNanos += nanos
            }
        }
        fun reset() = buckets.clear()
        fun report() = JSONObject()
            .put("scope", "UniFile.listFiles delegate calls; wrapper allocation excluded from list time")
            .put("totalListCalls", totalCalls)
            .put("includesPersistenceVerificationReads", true)
            .put("includesExternalHarnessPrePostReads", false)
            .put(
                "directories",
                JSONObject().apply {
                    buckets.forEach { (name, bucket) ->
                        put(
                            name,
                            JSONObject().put("calls", bucket.calls).put("returnedEntries", bucket.entries)
                                .put("delegateListMs", ms(bucket.delegateNanos)),
                        )
                    }
                },
            )
    }

    /** Every child-producing operation retains the decorator; streams still use the real RawFile. */
    private class CountedUniFile(
        private val delegate: UniFile,
        private val counts: ListingCounts,
        parent: UniFile?,
    ) : UniFile(parent) {
        private fun wrap(file: UniFile?): UniFile? = file?.let { CountedUniFile(it, counts, this) }
        override fun getUri(): Uri = delegate.uri
        override fun getName(): String? = delegate.name
        override fun getType(): String? = delegate.type
        override fun getFilePath(): String? = delegate.filePath
        override fun isFile(): Boolean = delegate.isFile
        override fun isDirectory(): Boolean = delegate.isDirectory
        override fun lastModified(): Long = delegate.lastModified()
        override fun length(): Long = delegate.length()
        override fun canRead(): Boolean = delegate.canRead()
        override fun canWrite(): Boolean = delegate.canWrite()
        override fun exists(): Boolean = delegate.exists()
        override fun findFile(name: String): UniFile? = wrap(delegate.findFile(name))
        override fun createFile(name: String): UniFile? = wrap(delegate.createFile(name))
        override fun createDirectory(name: String): UniFile? = wrap(delegate.createDirectory(name))
        override fun listFiles(): Array<UniFile>? = listed { delegate.listFiles() }
        override fun listFiles(filter: FilenameFilter): Array<UniFile>? = listed {
            delegate.listFiles(filter)
        }
        private fun listed(read: () -> Array<UniFile>?): Array<UniFile>? {
            val start = now()
            val files = read()
            counts.record(delegate.name, files?.size ?: 0, now() - start)
            return files?.map { checkNotNull(wrap(it)) }?.toTypedArray()
        }
        override fun delete(): Boolean = delegate.delete()
        override fun renameTo(name: String): Boolean = delegate.renameTo(name)
        override fun openInputStream(): InputStream = delegate.openInputStream()
        override fun openOutputStream(): OutputStream = delegate.openOutputStream()
        override fun openOutputStream(append: Boolean): OutputStream = delegate.openOutputStream(append)
        override fun createRandomAccessFile(mode: String): UniRandomAccessFile =
            delegate.createRandomAccessFile(mode)
    }

    /** Observe values and presence without writing any shared preference or exporting its content. */
    private class SettingsGuard(context: Context) {
        private val preferences = Injekt.get<TranslationPreferences>().let { prefs ->
            listOf<Preference<*>>(
                prefs.translationMasterEnabled,
                prefs.translationEnabled,
                prefs.liveTranslate,
                prefs.nightReadEnabled,
                prefs.keepMaterials,
                prefs.segThreshold,
            )
        }
        private val xml = File(
            File(context.applicationInfo.dataDir).canonicalFile,
            "shared_prefs/${context.packageName}_preferences.xml",
        )
        private val memoryBefore = memory()
        private val diskBefore = disk()
        private fun memory() = sha(
            preferences.sortedBy { it.key() }
                .joinToString("\n") { "${it.key()}|${it.isSet()}|${it.get()}" }.toByteArray(Charsets.UTF_8),
        )
        private fun disk() = if (xml.exists()) Entry(xml.length(), sha(xml)) else Entry(-1, "absent")
        fun verify() {
            check(memory() == memoryBefore) { "Shared selected preference values or isSet changed" }
            check(disk() == diskBefore) { "Main preference XML raw bytes changed" }
        }
        fun metadata() = JSONObject().put("selectedValuesAndIsSetUnchanged", true)
            .put("mainXmlExactBytesUnchanged", true).put("mainXmlBytes", diskBefore.bytes)
            .put("mainXmlSha256", diskBefore.sha256).put("selectedValuesDigest", memoryBefore)
            .put("credentialModelEndpointValuesReadByTest", false)
    }

    private companion object {
        const val PRIOR = 149
        const val WIDTH = 1200
        const val HEIGHT = 1600
        const val TARGET = "target.png"
        const val MATERIALS = ".yakuyomi"
        const val METHOD = "auto_whole"
        const val ASSET = "native-quality/vertical-punctuation.png"
        const val SOURCE_SHA = "281ffb1cc92aa692bddf6ea1429ecbb32a3ea6cfc974c09329154abd628010f3"
        fun now() = SystemClock.elapsedRealtimeNanos()
        fun ms(nanos: Long) = nanos / 1_000_000.0
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().hex()
        }
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
        fun priorName(index: Int) = "prior-${(index + 1).toString().padStart(3, '0')}.png"
        fun priorNames() = (0 until PRIOR).map(::priorName).toSet()
        fun materialNames(name: String) = listOf(
            OriginalMaterial.exactName(name),
            "${OriginalMaterial.base(name)}.mask.png",
            "${OriginalMaterial.base(name)}.json",
            PageProcessingSummaryCodec.exactName(name),
            NightSourceRevision.fileName(name),
        )
        fun isManifest(name: String) = name == CompletionManifestCodec.PRIMARY ||
            name == CompletionManifestCodec.PREVIOUS || CompletionManifestCodec.isCheckpoint(name)
        fun newCase(
            root: File,
            name: String,
            prior: Int,
            counted: Boolean,
            kind: String,
            round: Int,
        ): Case {
            val directory = File(root, name)
            check(directory.canonicalFile.parentFile == root && !directory.exists() && directory.mkdir())
            return Case(directory, prior, counted, kind, round)
        }
        fun copyChapter(source: File, destination: File) {
            check(checkNotNull(destination.list()).isEmpty())
            checkNotNull(source.listFiles()).forEach { file ->
                val target = File(destination, file.name)
                check(file.canonicalFile.parentFile == source)
                check(target.canonicalFile.parentFile == destination)
                if (file.isDirectory) {
                    check(file.name == MATERIALS && target.mkdir())
                    checkNotNull(file.listFiles()).forEach { material ->
                        check(material.isFile && material.canonicalFile.parentFile == file)
                        material.copyTo(File(target, material.name), overwrite = false)
                    }
                } else {
                    check(file.isFile)
                    file.copyTo(target, overwrite = false)
                }
            }
        }
        fun snapshot(directory: File, excluded: Set<String> = emptySet()): Map<String, Entry> {
            val root = directory.canonicalFile
            val result = sortedMapOf<String, Entry>()
            var bytes = 0L
            root.walkTopDown().onEnter { child ->
                check(child.canonicalFile == child.absoluteFile)
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                true
            }.filter {
                it.isFile && it.relativeTo(root).invariantSeparatorsPath !in excluded
            }.forEach { file ->
                check(file.canonicalFile == file.absoluteFile && Os.lstat(file.path).st_nlink == 1L)
                val length = file.length()
                bytes += length
                check(result.size < 8192 && bytes <= 512L * 1024 * 1024) {
                    "Owned evidence inventory exceeded bound"
                }
                result[file.relativeTo(root).invariantSeparatorsPath] = Entry(length, sha(file))
            }
            return result
        }
        fun inventoryDigest(entries: Map<String, Entry>) = sha(
            entries.entries.joinToString("\n") {
                "${it.key}|${it.value.bytes}|${it.value.sha256}"
            }.toByteArray(Charsets.UTF_8),
        )
        fun inventorySummary(entries: Map<String, Entry>) = JSONObject().put("files", entries.size)
            .put("bytes", entries.values.sumOf { it.bytes }).put("sha256", inventoryDigest(entries))
        fun timingSummary(rows: List<JSONObject>) = JSONArray(
            listOf(0, PRIOR).map { prior ->
                val values = rows.filter { it.getInt("priorCompleted") == prior }
                    .map { it.getDouble("persistMs") }.sorted()
                JSONObject().put("priorCompleted", prior).put("samples", values.size)
                    .put("persistMs", JSONArray(values))
                    .put("minimumMs", values.first()).put("medianMs", values[values.size / 2])
                    .put("maximumMs", values.last())
            },
        )
        fun encoded(bitmap: Bitmap): ByteArray = java.io.ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
        fun verifyDecoded(file: File, expectedArgb: String) {
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
            try {
                check(bitmap.width == WIDTH && bitmap.height == HEIGHT)
                check(bitmapSha(bitmap) == expectedArgb)
            } finally {
                bitmap.recycle()
            }
        }
        fun bitmapSha(bitmap: Bitmap): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val pixels = IntArray(bitmap.width * 16)
            val bytes = ByteArray(pixels.size * 4)
            var top = 0
            while (top < bitmap.height) {
                val height = minOf(16, bitmap.height - top)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, height)
                var used = 0
                for (index in 0 until bitmap.width * height) {
                    val pixel = pixels[index]
                    bytes[used++] = (pixel ushr 24).toByte()
                    bytes[used++] = (pixel ushr 16).toByte()
                    bytes[used++] = (pixel ushr 8).toByte()
                    bytes[used++] = pixel.toByte()
                }
                digest.update(bytes, 0, used)
                top += height
            }
            return digest.digest().hex()
        }
    }
}
