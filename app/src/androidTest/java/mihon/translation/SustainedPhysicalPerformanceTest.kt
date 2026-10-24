package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStore
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.Yakuyomi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicit, isolated, sequential native/HTTPS/storage diagnostic. It never retries a failed run. */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalEngineApi::class)
class SustainedPhysicalPerformanceTest {
    @Test
    fun preparePinnedSyntheticInputs() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("sustainedPhysicalPrepare") == "true") { "Explicit preparation opt-in required" }
        val context = auditContext()
        val id = uuid(checkNotNull(args.getString("sustainedPhysicalInputId")))
        val parent = privateFiles(context)
        val directory = File(parent, "sustained-physical-input-$id")
        check(!directory.exists() && directory.mkdir()) { "Never replace an existing prepared input" }
        requirePlain(directory, parent)
        val files = JSONArray()
        for ((index, name) in FIXTURE_NAMES.withIndex()) {
            val bitmap = fixture(index)
            try {
                val file = File(directory, "$name.png")
                check(!file.exists())
                FileOutputStream(file).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.fd.sync()
                }
                requirePlain(file, directory)
                files.put(
                    JSONObject().put("fixture", name).put("name", file.name).put("bytes", file.length())
                        .put("sha256", digest(file)).put("argbSha256", bitmapSha(bitmap))
                        .put("width", bitmap.width).put("height", bitmap.height),
                )
            } finally {
                bitmap.recycle()
            }
        }
        val manifest = JSONObject().put("schemaVersion", 1).put("inputId", id)
            .put("inputBasename", directory.name).put("neutralSyntheticOnly", true)
            .put("fixtureDefinition", "Copy of PhysicalDevicePerformanceTest Canvas fixtures")
            .put("argbHashLayout", "Row-major bytes A,R,G,B").put("files", files)
            .put("nativeCalls", 0).put("nativeCallsScope", "Model inference only; Canvas/PNG internals not counted")
            .put("networkCalls", 0).put("preferencesChanged", false)
        val manifestFile = File(directory, "manifest.json")
        atomicJson(manifestFile, manifest)
        check(directory.list()?.toSet() == FIXTURE_NAMES.map { "$it.png" }.toSet() + "manifest.json")
        val prepared = JSONObject().put("phase", "PREPARED").put("inputId", id)
            .put("inputBasename", directory.name).put("directory", directory.path)
            .put("manifestBytes", manifestFile.length()).put("manifestSha256", digest(manifestFile))
            .put("files", files).put("nativeCalls", 0).put("nativeCallsScope", "Model inference only")
            .put("networkCalls", 0)
        send("SUSTAINED_PHYSICAL_INPUT=", prepared)
    }

    @Test
    fun singleEngineProcessesFreshPagesForRequestedActiveDuration() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "sustained-physical-collector") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) { runSustained() }
            }
    }

    private suspend fun runSustained() {
        val args = RunArguments.read()
        val context = auditContext()
        val parent = privateFiles(context)
        val inputs = Inputs(parent, args.input, args.manifestSha)
        val identity = identity()
        val shared = Injekt.get<TranslationEngineService>()
        withTimeout(10_000) { Injekt.get<TranslationManager>().ensureRestored() }
        requireIdle(context, shared)
        val settings = SettingsGuard(context)
        val modelsDirectory = modelDirectory(context)
        val modelsBefore = modelFingerprints(modelsDirectory)
        val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { it.readBytes() }
        check(bytesSha(dictionaryBytes) == DICTIONARY_SHA)
        val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
        check(dictionary.size == 19_264)
        val config = configuration(args)
        val models = ModelSet(
            ocr = File(modelsDirectory, "ocr_48px_ctc.ncnn.param").path,
            detectorNcnn = File(modelsDirectory, "dbnet_detect.ncnn.param").path,
            aotInpainterNcnn = File(modelsDirectory, "mit_aot_fixed512.ncnn.param").path,
        )
        val output = File(parent, "sustained-physical-${args.runId}")
        check(!output.exists() && output.mkdir()) { "Never restart or overwrite this run UUID" }
        requirePlain(output, parent)
        val chapterFile = File(output, "chapter").also { check(it.mkdir()) }
        val chapter = checkNotNull(UniFile.fromFile(chapterFile))
        val pageTranslator = PageTranslator(context)
        val pagesFile = File(output, "pages.jsonl")
        check(pagesFile.createNewFile())
        val recorder = Recorder(output, pagesFile)
        val totalStart = now()
        val report = JSONObject().put("passed", false).put("completed", false).put("runId", args.runId)
            .put("mode", if (args.smoke) "SMOKE" else "LONG").put("longPassEligible", !args.smoke)
            .put("longEligible", !args.smoke).put("chapterInventorySealed", false)
            .put("durationSecondsRequested", args.durationSeconds).put("pageCap", args.pageCap)
            .put("wallLimitSeconds", args.wallLimitSeconds).put("pageTimeoutSeconds", 240)
            .put("processIdentity", identity).put("targetPackage", context.packageName)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)
            .put("inputBasename", inputs.directory.name).put("inputManifestSha256", args.manifestSha)
            .put("inputManifest", inputs.manifest).put("modelsBefore", modelsBefore)
            .put("dictionarySha256", bytesSha(dictionaryBytes)).put("dictionaryRows", dictionary.size)
            .put("immutableConfig", safeConfig(config)).put("settingsBefore", settings.description())
            .put("apiKeyArgumentEmpty", true).put("credentialPreferenceAccessed", false)
            .put("preferencesChanged", false).put("engineCreateCalls", 0).put("explicitWarmUpCalls", 0)
            .put("engineCloseAttempts", 0).put("actualHttpAttemptsAndStatusMeasured", false)
            .put("llmTimingScope", "Client aggregate latency including network/server/parsing/bounded negotiation")
            .put("stageClock", "PageStats uses currentTimeMillis; all outer/accounting clocks are monotonic")
            .put("initializationScope", "Factory/warmup after hash preflight; file cache may be warm; not app launch")
            .put("memoryScope", "Process PSS/ART/native heap/FD/thread observations; not per-engine leak proof")
            .put("nativeHandlesCompletelyReleasedClaimed", false).put("testRequestsGc", false)
            .put("testSleepsDuringActiveLoop", false).put("automaticPageOrRunRetries", 0)
            .put("activeDefinition", "Verified successful translatePage + persistLivePage monotonic durations only")
            .put("excludedFromActive", "Initialization, input copy/decode/hash, verification, memory/report I/O, close")
            .put("storageScope", "One growing chapter; every source is a fresh uniquely named pinned PNG copy")
            .put("readerScope", "Actual ReaderPage.displayStream bytes/pixels; no Activity or touch claim")
            .put("reportGrowth", "Append-only BEGIN/END JSONL; small summary, no growing in-memory page list")
            .put("memoryBefore", memory())
        var engine: TranslationEngine? = null
        var fatal: Throwable? = null
        var attempted = 0
        var successful = 0
        var activeNanos = 0L
        var runStart = 0L
        var ownedDataBytes = 0L
        val buckets = FIXTURE_NAMES.associateWith { OnlineSummary() }
        recorder.checkpoint(report)
        status(report, output, "INITIALIZING")
        try {
            var start = now()
            report.put("engineCreateCalls", 1)
            val activeEngine = withTimeout(240_000L) {
                Yakuyomi.create(models, dictionary, "", config).also { engine = it }
            }
            report.put("engineCreateMs", ms(now() - start)).put("memoryAfterCreate", memory())
            check(activeEngine is Pipeline)
            val actualOcr = Pipeline::class.java.getDeclaredField("ocr").apply { isAccessible = true }
                .get(activeEngine) as Ocr
            val nativeThreads = Ocr::class.java.getDeclaredField("threads").apply { isAccessible = true }
                .getInt(actualOcr)
            report.put("actualEngineClass", activeEngine.javaClass.name).put("actualOcrBackend", actualOcr.backend)
                .put("actualOcrNativeThreads", nativeThreads).put("cpuSupportsFp16", Yakuyomi.ncnnCpuSupportsFp16())
            start = now()
            report.put("explicitWarmUpCalls", 1)
            withTimeout(240_000L) { activeEngine.warmUp() }
            report.put("nativeWarmUpMs", ms(now() - start)).put("memoryAfterWarmUp", memory())
                .put("initializationMs", ms(now() - totalStart))
            recorder.checkpoint(report)
            runStart = now()
            while (activeNanos < args.durationSeconds * NANOS_PER_SECOND || successful < 3) {
                currentCoroutineContext().ensureActive()
                check(attempted < args.pageCap) { "Page cap reached before sustained success" }
                val remaining = args.wallLimitSeconds * NANOS_PER_SECOND - (now() - runStart)
                check(remaining > 0L) { "Wall deadline reached before active target" }
                requireIdentity(identity)
                requireIdle(context, shared)
                settings.verifyMemory()
                check(output.usableSpace >= MIN_FREE_BYTES) { "Insufficient free space; do not continue" }
                check(ownedDataBytes < MAX_RUN_DATA_BYTES) { "Own run data limit reached" }
                attempted++
                val entry = inputs.entries[(attempted - 1) % inputs.entries.size]
                val name = "%05d.png".format(java.util.Locale.ROOT, attempted)
                val row = JSONObject().put("index", attempted).put("page", name).put("fixture", entry.fixture)
                    .put("inputFile", entry.name).put("sourcePinsVerified", false)
                    .put("sourcePngSha256", entry.sha256).put("sourceArgbSha256", entry.argbSha256)
                    .put("phase", if (attempted == 1) "first" else "warm").put("memoryBefore", memory())
                recorder.append(JSONObject().put("record", "BEGIN").put("page", row))
                val page = try {
                    withTimeout(minOf(240_000L, (remaining / 1_000_000L).coerceAtLeast(1L))) {
                        processPage(
                            activeEngine, pageTranslator, inputs, entry, chapterFile, chapter, name, attempted - 1, row,
                        ) { stage ->
                            row.put("activeStage", stage)
                            report.put("currentPage", row)
                            recorder.checkpoint(report)
                        }
                    }
                } catch (error: Throwable) {
                    row.put("status", "failed").put("failureClass", error.javaClass.name)
                        .put("memoryAfterFailure", memory())
                    recorder.append(JSONObject().put("record", "END").put("page", row))
                    report.put("firstFailedPage", row)
                    throw error
                }
                activeNanos += page.activeNanos
                ownedDataBytes += page.dataBytes
                successful++
                row.put("status", "passed").put("cumulativeActiveMs", ms(activeNanos))
                    .put("runWallMs", ms(now() - runStart)).put("memoryAfterRelease", memory())
                buckets.getValue(entry.fixture).add(row)
                recorder.append(JSONObject().put("record", "END").put("page", row))
                report.put("attemptedPages", attempted).put("successfulPages", successful).put("failedPages", 0)
                    .put("activeMs", ms(activeNanos)).put("runWallMs", ms(now() - runStart))
                    .put("estimatedOwnedPageDataBytes", ownedDataBytes).put("lastPage", name)
                    .put("byFixture", JSONObject(buckets.mapValues { it.value.json() }))
                    .put("reportIoMs", recorder.ioMs())
                recorder.checkpoint(report)
                status(report, output, "PROCESSING")
                check(now() - runStart <= args.wallLimitSeconds * NANOS_PER_SECOND) { "Wall deadline exceeded" }
            }
            check(successful == attempted && successful >= 3 && successful < args.pageCap)
            check(activeNanos >= args.durationSeconds * NANOS_PER_SECOND)
            check(now() - runStart <= args.wallLimitSeconds * NANOS_PER_SECOND)
            check(FIXTURE_NAMES.all { buckets.getValue(it).count > 0 })
            report.put("activeTargetReached", true).put("pageCapReached", false).put("allThreeFixturesUsed", true)
        } catch (error: Throwable) {
            fatal = error
            report.put("failureClass", error.javaClass.name).put("failedPages", attempted - successful)
                .put("activeTargetReached", false)
            recorder.append(
                JSONObject().put("record", "RUN_FAILED").put("attemptedPages", attempted)
                    .put("successfulPages", successful).put("failureClass", error.javaClass.name),
            )
        } finally {
            val mainWallNanos = if (runStart == 0L) 0L else now() - runStart
            report.put("runWallMs", ms(mainWallNanos)).put("pageCapReached", attempted >= args.pageCap)
                .put("mainWallLimitExceeded", mainWallNanos > args.wallLimitSeconds * NANOS_PER_SECOND)
            report.put("memoryBeforeEngineClose", memory()).put("engineCloseAttempts", if (engine == null) 0 else 1)
            val closeStart = now()
            try {
                engine?.close()
            } catch (error: Throwable) {
                fatal = fatal ?: error
                report.put("closeFailureClass", error.javaClass.name)
            }
            report.put("engineCloseMs", ms(now() - closeStart))
            try {
                withTimeout(150_000L) {
                    val closedAt = now()
                    val samples = JSONArray().put(postCloseSample(closedAt, 0))
                    report.put("postClose", samples)
                    recorder.checkpoint(report)
                    status(report, output, "POST_CLOSE_0")
                    awaitOffset(closedAt, 30_000L)
                    samples.put(postCloseSample(closedAt, 30))
                    recorder.checkpoint(report)
                    status(report, output, "POST_CLOSE_30")
                    awaitOffset(closedAt, 120_000L)
                    samples.put(postCloseSample(closedAt, 120))
                    recorder.checkpoint(report)
                    status(report, output, "POST_CLOSE_120")
                    inputs.verifyUnchanged()
                    val modelsAfter = modelFingerprints(modelDirectory(context))
                    check(modelsBefore.toString() == modelsAfter.toString())
                    report.put("modelsAfter", modelsAfter).put("modelsAndInputsUnchanged", true)
                    settings.verifyAll()
                    report.put("settingsAfter", settings.description())
                        .put("settingsValuePresenceAndDiskUnchanged", true)
                    requireIdle(context, shared)
                    requireIdentity(identity)
                    recorder.sealInventory(chapterFile)
                    report.put("chapterInventory", recorder.inventoryDescription()).put("chapterInventorySealed", true)
                    report.put("postCloseAccountingMs", ms(now() - closedAt))
                }
            } catch (error: Throwable) {
                fatal = fatal ?: error
                report.put("accountingFailureClass", error.javaClass.name)
            }
            try {
                recorder.close()
            } catch (error: Throwable) {
                fatal = fatal ?: error
                report.put("logCloseFailureClass", error.javaClass.name)
            }
            report.put("attemptedPages", attempted).put("successfulPages", successful)
                .put("activeMs", ms(activeNanos)).put("totalWallMs", ms(now() - totalStart))
                .put("runWallMs", if (runStart == 0L) 0.0 else report.optDouble("runWallMs"))
                .put("reportIoMs", recorder.ioMs()).put("completed", fatal == null).put("passed", fatal == null)
                .put("longDurationPassed", fatal == null && !args.smoke)
            recorder.checkpoint(report)
            status(report, output, if (fatal == null) "COMPLETED" else "FAILED", recorder.finalManifest())
        }
        if (fatal != null) throw AssertionError("Sustained run failed; inspect its owned checkpoint report")
    }
    private data class PageMeasurement(val activeNanos: Long, val dataBytes: Long)

    private suspend fun processPage(
        engine: TranslationEngine,
        translator: PageTranslator,
        inputs: Inputs,
        entry: InputEntry,
        directory: File,
        chapter: UniFile,
        name: String,
        readerIndex: Int,
        row: JSONObject,
        progress: (String) -> Unit,
    ): PageMeasurement {
        val start = now()
        val pageFile = File(directory, name)
        var original: Bitmap? = null
        var result: PageResult? = null
        try {
            progress("PREPARE")
            var stage = now()
            check(!pageFile.exists() && !translator.isPageTranslated(chapter, name))
            check(!File(directory, ".yakuyomi/${name.removeSuffix(".png")}.orig.png").exists())
            inputs.copyFresh(entry, pageFile)
            val source = checkNotNull(BitmapFactory.decodeFile(pageFile.path))
            original = source
            check(source.width == entry.width && source.height == entry.height && bitmapSha(source) == entry.argbSha256)
            row.put("prepareMs", ms(now() - stage)).put("width", source.width).put("height", source.height)
                .put("sourcePinsVerified", true).put("memoryBeforeEngine", memory())
            progress("ENGINE")
            stage = now()
            result = engine.translatePage(source)
            val engineNanos = now() - stage
            row.put("engineCallMs", ms(engineNanos)).put("memoryAfterEngine", memory())
            val translated = result as? PageResult.Translated ?: error("Every performance page must fully translate")
            val analysis = checkNotNull(translated.analysis)
            check(analysis.progress?.hasPending == false)
            check(analysis.regions.isNotEmpty() && analysis.regions.none { it.preserveOriginal })
            check(analysis.regions.all { it.sourceText.isNotBlank() && it.translatedText.isNotBlank() })
            check(analysis.regions.all { HANGUL.containsMatchIn(it.translatedText) })
            check(bitmapSha(source) == entry.argbSha256)
            val outputArgb = bitmapSha(translated.page)
            val rawMaskArgb = bitmapSha(analysis.mask)
            check(outputArgb != entry.argbSha256)
            row.put("stats", stats(translated.stats)).put("regions", analysis.regions.size)
                .put("koreanTargetRegions", analysis.regions.count { HANGUL.containsMatchIn(it.translatedText) })
                .put("sourceTextSetSha256", stringDigest(analysis.regions.map { it.sourceText }))
                .put("targetTextSetSha256", stringDigest(analysis.regions.map { it.translatedText }))
                .put("outputArgbSha256", outputArgb).put("rawMaskArgbSha256", rawMaskArgb)
            progress("PERSIST")
            stage = now()
            check(
                translator.persistLivePage(
                    chapter,
                    checkNotNull(UniFile.fromFile(pageFile)),
                    source,
                    translated.page,
                    analysis,
                    METHOD,
                ),
            ) { "Persistence did not succeed" }
            val persistNanos = now() - stage
            row.put("persistMs", ms(persistNanos)).put("memoryAfterPersist", memory())
            progress("VERIFY")
            stage = now()
            val originalFile = File(directory, ".yakuyomi/${name.removeSuffix(".png")}.orig.png")
            val mask = File(directory, ".yakuyomi/${name.removeSuffix(".png")}.mask.png")
            val materials = File(directory, ".yakuyomi/${name.removeSuffix(".png")}.json")
            listOf(pageFile, originalFile, mask, materials).forEach { check(it.isFile && it.length() > 0L) }
            check(digest(originalFile) == entry.sha256 && digest(pageFile) != entry.sha256)
            val saved = decodeMaterials(materials)
            check(saved.method == METHOD && saved.regions.size == analysis.regions.size && saved.regions.isNotEmpty())
            check(saved.regions.none { it.preserveOriginal })
            saved.regions.zip(analysis.regions).forEach { (actual, expected) ->
                check(actual.source == expected.sourceText && actual.target == expected.translatedText)
                check(actual.source.isNotBlank() && actual.target.isNotBlank() && HANGUL.containsMatchIn(actual.target))
                check(actual.quads == expected.lines.map { line -> line.quad.map { listOf(it.x, it.y) } })
            }
            check(imageDimensions(originalFile) == (entry.width to entry.height))
            check(imageDimensions(mask) == (entry.width to entry.height))
            row.put("maskReadback", verifyMask(mask, analysis.mask))
            check(bitmapSha(analysis.mask) == rawMaskArgb)
            val manifest = verifyCompletion(directory, readerIndex + 1, name)
            check(translator.isPageTranslated(chapter, name))
            val reader = ReaderPage(readerIndex, stream = { pageFile.inputStream() })
            verifyReader(reader, entry.width, entry.height, outputArgb)
            reader.showOriginalPreview { originalFile.inputStream() }
            verifyReader(reader, entry.width, entry.height, entry.argbSha256)
            reader.clearOriginalPreview()
            verifyReader(reader, entry.width, entry.height, outputArgb)
            check(digest(originalFile) == entry.sha256 && bitmapSha(source) == entry.argbSha256)
            check(bitmapSha(translated.page) == outputArgb)
            val dataBytes = listOf(pageFile, originalFile, mask, materials).sumOf { it.length() }
            row.put("verifyMs", ms(now() - stage)).put("originalPreserved", true)
                .put("materialsReadbackVerified", true).put("readerTranslatedOriginalAndReturnPixelsVerified", true)
                .put("completion", manifest).put("memoryAfterVerify", memory())
                .put("bodyBytes", pageFile.length()).put("originalBytes", originalFile.length())
                .put("maskBytes", mask.length()).put("materialBytes", materials.length())
                .put("bodySha256", digest(pageFile)).put("maskSha256", digest(mask))
                .put("materialsSha256", digest(materials)).put("newPageDataBytes", dataBytes)
            return PageMeasurement(engineNanos + persistNanos, dataBytes)
        } finally {
            val releaseStart = now()
            val pageAndMask = when (val outcome = result) {
                is PageResult.Translated -> outcome.page to outcome.analysis?.mask
                is PageResult.Partial -> outcome.page to outcome.analysis.mask
                is PageResult.Skipped -> null to outcome.analysis?.mask
                else -> null to null
            }
            pageAndMask.first?.let { if (it !== original && !it.isRecycled) it.recycle() }
            pageAndMask.second?.let {
                if (it !== original && it !== pageAndMask.first && !it.isRecycled) it.recycle()
            }
            original?.let { if (!it.isRecycled) it.recycle() }
            row.put("releaseMs", ms(now() - releaseStart)).put("pageWallMs", ms(now() - start))
        }
    }

    private fun verifyCompletion(directory: File, count: Int, page: String): JSONObject {
        // One top-level listing is shared by count/checkpoint verification; verification time is separate.
        val files = checkNotNull(directory.listFiles())
        val primary = File(directory, CompletionManifestCodec.PRIMARY)
        val complete = checkNotNull(CompletionManifestCodec.decode(readBytes(primary, MAX_METADATA_BYTES)))
        check(complete.size == count && page in complete)
        check(complete.all { it.matches(PAGE_NAME) && it.removeSuffix(".png").toInt() in 1..count })
        val checkpoints = files.filter { CompletionManifestCodec.isCheckpoint(it.name) }
        check(checkpoints.isNotEmpty())
        check(checkpoints.any { CompletionManifestCodec.decode(readBytes(it, MAX_METADATA_BYTES)) == complete })
        val materialCount = if (count <= 3 || count % 30 == 0) File(directory, ".yakuyomi").list()?.size else null
        return JSONObject().put("count", complete.size).put("primaryBytes", primary.length())
            .put("primarySha256", digest(primary)).put("checkpointFiles", checkpoints.size)
            .put("currentCheckpointVerified", true).put("checkpointBytes", checkpoints.sumOf { it.length() })
            .put("topLevelFiles", files.size).put("materialFilesObserved", materialCount ?: JSONObject.NULL)
            .put("materialCountSampling", "First3/every30; no extra full material scan on other pages")
    }

    private fun verifyReader(reader: ReaderPage, width: Int, height: Int, argbSha: String) {
        val bitmap = checkNotNull(checkNotNull(reader.displayStream).invoke().use { BitmapFactory.decodeStream(it) })
        try {
            check(bitmap.width == width && bitmap.height == height && bitmapSha(bitmap) == argbSha)
        } finally {
            bitmap.recycle()
        }
    }

    private fun verifyMask(file: File, source: Bitmap): JSONObject {
        val saved = checkNotNull(BitmapFactory.decodeFile(file.path))
        try {
            check(saved.width == source.width && saved.height == source.height)
            val width = source.width
            val rows = minOf(16, source.height)
            val expected = IntArray(width * rows)
            val actual = IntArray(expected.size)
            var white = 0L
            var top = 0
            while (top < source.height) {
                val height = minOf(rows, source.height - top)
                source.getPixels(expected, 0, width, 0, top, width, height)
                saved.getPixels(actual, 0, width, 0, top, width, height)
                for (index in 0 until width * height) {
                    val binary = if ((expected[index] and 255) > 127) Color.WHITE else Color.BLACK
                    check(actual[index] == binary)
                    if (binary == Color.WHITE) white++
                }
                top += height
            }
            check(white > 0 && white < width.toLong() * source.height)
            return JSONObject().put("binaryMatchesActualAnalysisMask", true).put("whitePixels", white)
                .put("width", saved.width).put("height", saved.height).put("argbSha256", bitmapSha(saved))
        } finally {
            saved.recycle()
        }
    }

    private fun decodeMaterials(file: File): PageMaterials =
        Json.decodeFromString(readBytes(file, MAX_METADATA_BYTES).toString(Charsets.UTF_8))

    private data class RunArguments(
        val runId: String,
        val smoke: Boolean,
        val durationSeconds: Long,
        val pageCap: Int,
        val input: String,
        val manifestSha: String,
        val base: String,
        val model: String,
    ) {
        val wallLimitSeconds: Long get() = if (smoke) 360 else 3600

        companion object {
            fun read(): RunArguments {
                val args = InstrumentationRegistry.getArguments()
                check(args.getString("sustainedPhysicalPerformance") == "true") { "Explicit sustained opt-in required" }
                val smokeText = args.getString("sustainedPhysicalSmoke") ?: "false"
                check(smokeText in setOf("true", "false"))
                val smoke = smokeText == "true"
                val duration = args.getString("sustainedPhysicalDurationSeconds")?.let {
                    checkNotNull(it.toLongOrNull())
                }
                    ?: if (smoke) 60L else 2700L
                check(duration in if (smoke) 30L..180L else 2700L..3600L)
                val cap = args.getString("sustainedPhysicalPageCap")?.let { checkNotNull(it.toIntOrNull()) }
                    ?: if (smoke) 120 else 1200
                check(cap in if (smoke) 3..120 else 3..3600)
                val id = checkNotNull(args.getString("sustainedPhysicalRunId"))
                check(id.matches(UUID_REGEX) && UUID.fromString(id).toString() == id)
                val input = checkNotNull(args.getString("sustainedPhysicalInput"))
                check(input.matches(Regex("sustained-physical-input-$UUID_PATTERN")))
                check(input != "sustained-physical-input-$id") { "Run and prepared input UUIDs must differ" }
                val manifestSha = checkNotNull(args.getString("sustainedPhysicalManifestSha256"))
                check(manifestSha.matches(SHA_REGEX))
                val base = checkNotNull(args.getString("liveTranslationBase"))
                val url = checkNotNull(base.toHttpUrlOrNull())
                check(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty())
                check(url.query == null && url.fragment == null)
                val model = checkNotNull(args.getString("liveTranslationModel"))
                check(model.isNotBlank() && model.length <= 160 && model.none { it.isISOControl() })
                return RunArguments(id, smoke, duration, cap, input, manifestSha, base, model)
            }
        }
    }

    private data class InputEntry(
        val fixture: String,
        val name: String,
        val bytes: Long,
        val sha256: String,
        val argbSha256: String,
        val width: Int,
        val height: Int,
    )

    private inner class Inputs(parent: File, name: String, private val manifestSha: String) {
        val directory = File(parent, name)
        val manifest: JSONObject
        val entries: List<InputEntry>

        init {
            requirePlain(directory, parent)
            check(directory.isDirectory)
            val file = File(directory, "manifest.json")
            requirePlain(file, directory)
            val bytes = readBytes(file, 65_536)
            check(bytesSha(bytes) == manifestSha)
            manifest = JSONObject(bytes.toString(Charsets.UTF_8))
            check(manifest.getInt("schemaVersion") == 1 && manifest.getBoolean("neutralSyntheticOnly"))
            check(manifest.getString("inputBasename") == name)
            check(manifest.getString("inputId") == name.removePrefix("sustained-physical-input-"))
            val files = manifest.getJSONArray("files")
            check(files.length() == 3)
            entries = (0 until files.length()).map { index ->
                val entry = files.getJSONObject(index)
                check(entry.getString("fixture") == FIXTURE_NAMES[index])
                check(entry.getString("name") == "${FIXTURE_NAMES[index]}.png")
                InputEntry(
                    entry.getString("fixture"),
                    entry.getString("name"),
                    entry.getLong("bytes"),
                    entry.getString("sha256"),
                    entry.getString("argbSha256"),
                    entry.getInt("width"),
                    entry.getInt("height"),
                ).also {
                    check(it.bytes in 1..MAX_PAGE_BYTES && it.width == 1200 && it.height == 1600)
                    check(it.sha256.matches(SHA_REGEX) && it.argbSha256.matches(SHA_REGEX))
                }
            }
            verifyUnchanged()
            for (entry in entries) {
                val bitmap = checkNotNull(BitmapFactory.decodeFile(File(directory, entry.name).path))
                try {
                    check(bitmap.width == entry.width && bitmap.height == entry.height)
                    check(bitmapSha(bitmap) == entry.argbSha256)
                } finally {
                    bitmap.recycle()
                }
            }
        }

        fun verifyUnchanged() {
            check(directory.list()?.toSet() == FIXTURE_NAMES.map { "$it.png" }.toSet() + "manifest.json")
            check(digest(File(directory, "manifest.json")) == manifestSha)
            for (entry in entries) {
                val file = File(directory, entry.name)
                requirePlain(file, directory)
                check(file.isFile && file.length() == entry.bytes && digest(file) == entry.sha256)
                check(imageDimensions(file) == (entry.width to entry.height))
            }
        }

        fun copyFresh(entry: InputEntry, destination: File) {
            check(!destination.exists())
            val source = File(directory, entry.name)
            requirePlain(source, directory)
            check(source.length() == entry.bytes)
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        check(copied <= entry.bytes)
                        output.write(buffer, 0, count)
                    }
                    check(copied == entry.bytes)
                    output.fd.sync()
                }
            }
            check(destination.length() == entry.bytes && digest(destination) == entry.sha256)
        }
    }

    private inner class Recorder(private val root: File, private val pages: File) : AutoCloseable {
        private val stream = FileOutputStream(pages, true)
        private val report = File(root, "result.json")
        private val inventory = File(root, "inventory.jsonl")
        private var ioNanos = 0L
        private var inventoryFiles = 0
        private var inventoryBytes = 0L

        fun append(value: JSONObject) {
            val start = now()
            val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
            check(bytes.size <= 65_536 && pages.length() + bytes.size <= MAX_JSONL_BYTES)
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            ioNanos += now() - start
        }

        fun checkpoint(value: JSONObject) {
            val start = now()
            check(value.toString().toByteArray(Charsets.UTF_8).size <= 131_072)
            atomicJson(report, value)
            ioNanos += now() - start
        }

        fun ioMs(): Double = ms(ioNanos)

        fun sealInventory(chapter: File) {
            val start = now()
            check(!inventory.exists())
            FileOutputStream(inventory).use { output ->
                for (file in chapter.walkTopDown()) {
                    check(file.canonicalFile == file.absoluteFile)
                    check(file.toPath().startsWith(chapter.toPath()))
                    if (!file.isFile) {
                        check(file.isDirectory)
                        continue
                    }
                    check(++inventoryFiles <= 30_000)
                    inventoryBytes += file.length()
                    check(inventoryBytes <= MAX_RUN_DATA_BYTES)
                    val line = JSONObject().put("path", file.relativeTo(root).invariantSeparatorsPath)
                        .put("bytes", file.length()).put("sha256", digest(file))
                    output.write((line.toString() + "\n").toByteArray(Charsets.UTF_8))
                }
                output.fd.sync()
            }
            ioNanos += now() - start
        }

        fun inventoryDescription(): JSONObject = JSONObject().put("files", inventoryFiles)
            .put("totalChapterBytes", inventoryBytes).put("inventoryBytes", inventory.length())
            .put("sha256", digest(inventory)).put("scope", "Chapter data only; terminal manifest seals reports")

        fun finalManifest(): JSONArray = JSONArray(
            listOf(report, pages, inventory).filter { it.isFile }.map { file ->
                requirePlain(file, root)
                JSONObject().put("name", file.name).put("bytes", file.length()).put("sha256", digest(file))
            },
        )

        override fun close() = stream.close()
    }

    private class OnlineSummary {
        var count = 0
            private set
        private val values = linkedMapOf<String, Mean>()

        fun add(row: JSONObject) {
            count++
            for (key in listOf("engineCallMs", "persistMs", "verifyMs", "prepareMs", "pageWallMs")) {
                values.getOrPut(key) { Mean() }.add(row.getDouble(key))
            }
            for (key in listOf("detectMs", "ocrMs", "llmRequestMs", "inpaintMs", "renderMs")) {
                values.getOrPut(key) { Mean() }.add(row.getJSONObject("stats").getDouble(key))
            }
            val pss = row.getJSONObject("memoryAfterRelease").getDouble("pssKiB")
            values.getOrPut("pssAfterReleaseKiB") { Mean() }.add(pss)
        }

        fun json(): JSONObject = JSONObject().put("count", count)
            .put("population", "All completed pages, first included")
            .put("values", JSONObject(values.mapValues { it.value.json() }))

        private class Mean {
            private var count = 0
            private var sum = 0.0
            private var minimum = Double.POSITIVE_INFINITY
            private var maximum = Double.NEGATIVE_INFINITY
            fun add(value: Double) {
                count++
                sum += value
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
            }
            fun json() = JSONObject().put("count", count).put("mean", sum / count)
                .put("min", minimum).put("max", maximum)
        }
    }
    private fun configuration(args: RunArguments): EngineConfig {
        val base = EngineConfig()
        return base.copy(
            translator = base.translator.copy(
                provider = "custom",
                apiBase = LlmProviders.chatUrlOf(LlmProviders.byId("custom"), args.base),
                model = args.model,
                fromLangName = "Japanese",
                toLangName = "Korean",
                sampleSource = "",
                sampleTarget = "",
                temperature = 0.0,
                thinking = false,
            ),
            render = base.render.copy(orientation = TextOrientation.HORIZONTAL),
        )
    }

    private fun safeConfig(config: EngineConfig): JSONObject {
        val url = checkNotNull(config.translator.apiBase.toHttpUrlOrNull())
        return JSONObject().put("detector", config.detector.toString()).put("ocr", config.ocr.toString())
            .put("inpainter", config.inpainter.toString()).put("render", config.render.toString())
            .put("provider", "custom").put("model", config.translator.model)
            .put("from", "Japanese").put("to", "Korean").put("temperature", 0.0).put("thinking", false)
            .put("fewShotEmpty", true).put("endpointScheme", url.scheme).put("endpointHost", url.host)
            .put("endpointPort", url.port).put("endpointPath", url.encodedPath)
    }

    private inner class SettingsGuard(context: Context) {
        private val preferences = Injekt.get<TranslationPreferences>().let { prefs ->
            listOf<Preference<*>>(
                prefs.translationMasterEnabled, prefs.translationEnabled, prefs.liveTranslate, prefs.nightReadEnabled,
                prefs.provider, prefs.model, prefs.apiBase, prefs.sourceLangName, prefs.targetLangName,
                prefs.inpaintMethod, prefs.liveInpaintMethod, prefs.ocrConcurrency, prefs.stripPad, prefs.useBicubic,
                prefs.ocrUnsharp, prefs.bboxPad, prefs.tileSize, prefs.maskDilate, prefs.segThreshold, prefs.dbnetSize,
                prefs.detectUnsharp, prefs.minProb, prefs.orientation,
            )
        }
        private val xmlFile = File(
            File(context.applicationInfo.dataDir).canonicalFile,
            "shared_prefs/${context.packageName}_preferences.xml",
        )
        private val initialMemory = memoryDigest()
        private val initialDisk = diskDigest()

        private fun memoryDigest(): String = stringDigest(
            preferences.sortedBy { it.key() }.map {
                "${it.key()}|${it.isSet()}|${it.get()}"
            },
        )

        private fun diskDigest(): String {
            val keys = preferences.map { it.key() }.toSet()
            if (!xmlFile.exists()) {
                check(preferences.none { it.isSet() })
                return stringDigest(listOf("selected-xml-absent"))
            }
            val selected = mutableMapOf<String, String>()
            xmlFile.inputStream().use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        val key = parser.getAttributeValue(null, "name")
                        if (key in keys) {
                            val name = parser.name
                            val value = when (name) {
                                "string" -> parser.nextText()
                                "boolean", "int", "long", "float" -> parser.getAttributeValue(null, "value")
                                else -> error("Unsupported selected setting type")
                            }
                            check(selected.put(checkNotNull(key), "$name|$value") == null)
                        }
                    }
                    parser.next()
                }
            }
            check(preferences.all { it.isSet() == (it.key() in selected) })
            return stringDigest(keys.sorted().map { "$it|${selected[it] ?: "ABSENT"}" })
        }

        fun verifyMemory() = check(initialMemory == memoryDigest()) { "Selected settings changed during run" }
        fun verifyAll() {
            verifyMemory()
            check(initialDisk == diskDigest()) { "Selected settings changed on disk" }
        }
        fun description(): JSONObject = JSONObject().put("whitelistedPreferenceCount", preferences.size)
            .put("memoryValueAndPresenceSha256", initialMemory).put("selectedXmlSha256", initialDisk)
            .put("writesByTest", 0).put("credentialSlotsReadOrWritten", 0).put("rawValuesExported", false)
    }

    private suspend fun requireIdle(context: Context, shared: TranslationEngineService) {
        check(!shared.loading.value && !shared.warm.value)
        check(TranslationBusy.active.value == 0 && Injekt.get<TranslationManager>().queueState.value.isEmpty())
        val downloads = Injekt.get<DownloadManager>()
        check(downloads.queueState.value.isEmpty() && !downloads.isRunning)
        val engine = TranslationEngineService::class.java.getDeclaredField("engine").apply { isAccessible = true }
            .get(shared)
        val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight").apply { isAccessible = true }
            .get(shared) as AtomicInteger
        check(engine == null && inFlight.get() == 0) { "Shared engine must remain absent" }
        check(TranslationStore(context).restore().isEmpty())
        val marks = TranslationStore(context).restorePendingMarks(Long.MIN_VALUE)
        check(marks.translate.isEmpty() && marks.night.isEmpty())
        for (tag in listOf("Translator", "TranslatorHeartbeat", "Downloader")) {
            val work = WorkManager.getInstance(context).getWorkInfosByTag(tag).get(10, TimeUnit.SECONDS)
            check(work.none { !it.state.isFinished }) { "Background work must remain idle" }
        }
    }

    private fun modelDirectory(context: Context): File {
        val files = privateFiles(context)
        return File(files, "models").also {
            requirePlain(it, files)
            check(it.isDirectory)
        }
    }

    private fun modelFingerprints(directory: File): JSONArray = JSONArray(
        MODEL_PINS.map { (name, bytes, sha) ->
            val file = File(directory, name)
            requirePlain(file, directory)
            check(file.isFile && file.length() == bytes && digest(file) == sha)
            JSONObject().put("name", name).put("bytes", bytes).put("sha256", sha)
        },
    )

    private fun auditContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit target is required" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
    }

    private fun privateFiles(context: Context): File = context.filesDir.canonicalFile.also {
        check(it.isDirectory && it.name == "files")
        check(it.parentFile == File(context.applicationInfo.dataDir).canonicalFile)
    }

    private fun requirePlain(file: File, parent: File) {
        check(file.canonicalFile == file.absoluteFile && file.parentFile == parent)
    }

    private fun identity(): JSONObject {
        val stat = File("/proc/self/stat").readText()
        check(stat.substringBefore(" (").toInt() == Process.myPid())
        val afterName = stat.substring(stat.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
        return JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("processStartTicks", afterName[19]).put("startElapsedRealtimeMs", Process.getStartElapsedRealtime())
    }

    private fun requireIdentity(expected: JSONObject) = check(identity().toString() == expected.toString())

    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        return JSONObject().put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("pssKiB", info.totalPss).put("javaPssKiB", info.dalvikPss).put("nativePssKiB", info.nativePss)
            .put("privateDirtyKiB", info.totalPrivateDirty)
            .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaHeapCommittedBytes", runtime.totalMemory()).put("javaHeapMaxBytes", runtime.maxMemory())
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("fdCount", File("/proc/self/fd").list()?.size ?: -1)
            .put("threadCount", File("/proc/self/task").list()?.size ?: -1)
    }

    private fun postCloseSample(origin: Long, requestedSeconds: Int): JSONObject = JSONObject()
        .put("requestedOffsetSeconds", requestedSeconds).put("actualOffsetMs", ms(now() - origin))
        .put("memory", memory())

    private suspend fun awaitOffset(origin: Long, milliseconds: Long) {
        while (true) {
            val remaining = milliseconds * 1_000_000L - (now() - origin)
            if (remaining <= 0L) return
            delay(minOf(250L, (remaining / 1_000_000L).coerceAtLeast(1L)))
        }
    }

    private fun status(report: JSONObject, output: File, phase: String, files: JSONArray? = null) {
        val status = JSONObject().put("phase", phase).put("runId", report.getString("runId"))
            .put("mode", report.getString("mode")).put("directory", output.path)
            .put("report", File(output, "result.json").path).put("attemptedPages", report.optInt("attemptedPages"))
            .put("successfulPages", report.optInt("successfulPages")).put("activeMs", report.optDouble("activeMs", 0.0))
        if (files != null) status.put("sealedReportFiles", files).put("passed", report.getBoolean("passed"))
        send("SUSTAINED_PHYSICAL_STATUS=", status)
    }

    private fun send(prefix: String, value: JSONObject) = InstrumentationRegistry.getInstrumentation().sendStatus(
        2,
        Bundle().apply { putString("stream", "$prefix$value") },
    )

    private fun stats(value: PageStats): JSONObject = JSONObject().put("detectMs", value.detectMs)
        .put("ocrMs", value.ocrMs).put("llmRequestMs", value.translateMs).put("inpaintMs", value.inpaintMs)
        .put("renderMs", value.renderMs).put("pipelineWallMs", value.wallMs)
        .put("lines", value.lines).put("regions", value.regions).put("kept", value.kept)
        .put("promptTokens", value.promptTokens).put("completionTokens", value.completionTokens)

    private fun readBytes(file: File, maximum: Int): ByteArray {
        check(file.isFile && file.length() in 1..maximum.toLong())
        val result = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(result.size() + count <= maximum)
                result.write(buffer, 0, count)
            }
        }
        return result.toByteArray()
    }

    private fun imageDimensions(file: File): Pair<Int, Int> {
        check(file.isFile && file.length() in 1..MAX_PAGE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outMimeType == "image/png" && bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
        return bounds.outWidth to bounds.outHeight
    }

    private fun atomicJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(value.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Fixed 16-row buffers avoid retaining a page-sized ARGB copy in the measurement harness. */
    private fun bitmapSha(bitmap: Bitmap): String {
        check(bitmap.width in 1..4096 && bitmap.height in 1..4096)
        val hash = MessageDigest.getInstance("SHA-256")
        val rows = minOf(16, bitmap.height)
        val pixels = IntArray(bitmap.width * rows)
        val bytes = ByteArray(pixels.size * 4)
        var top = 0
        while (top < bitmap.height) {
            val height = minOf(rows, bitmap.height - top)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, height)
            var used = 0
            for (index in 0 until bitmap.width * height) {
                val pixel = pixels[index]
                bytes[used++] = (pixel ushr 24).toByte()
                bytes[used++] = (pixel ushr 16).toByte()
                bytes[used++] = (pixel ushr 8).toByte()
                bytes[used++] = pixel.toByte()
            }
            hash.update(bytes, 0, used)
            top += height
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun bytesSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun stringDigest(values: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun uuid(value: String): String = value.also {
        check(it.matches(UUID_REGEX) && UUID.fromString(it).toString() == it)
    }

    private fun now(): Long = SystemClock.elapsedRealtimeNanos()
    private fun ms(nanos: Long): Double = nanos / 1_000_000.0
    private fun fixture(type: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.LTGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun bubble(left: Float, top: Float, right: Float, bottom: Float) {
            paint.color = Color.DKGRAY
            canvas.drawRect(left - 8, top - 8, right + 8, bottom + 8, paint)
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(left, top, right, bottom), 70f, 70f, paint)
            paint.color = Color.BLACK
        }
        when (type) {
            0 -> {
                paint.textSize = 60f
                bubble(70f, 120f, 1130f, 650f)
                canvas.drawText("こんにちは。", 200f, 340f, paint)
                canvas.drawText("今日もよろしくお願いします。", 150f, 470f, paint)
                bubble(70f, 850f, 1130f, 1450f)
                canvas.drawText("明日の会議は午後三時です。", 180f, 1080f, paint)
                canvas.drawText("少し待ってください。", 240f, 1210f, paint)
            }
            1 -> {
                paint.textSize = 64f
                bubble(80f, 100f, 550f, 1470f)
                bubble(650f, 100f, 1120f, 1470f)
                for ((x, text) in listOf(980f to "明日もよろしく", 860f to "お願いします", 410f to "少し待って", 290f to "ください")) {
                    text.forEachIndexed { index, c -> canvas.drawText(c.toString(), x, 350f + index * 80f, paint) }
                }
            }
            else -> {
                paint.textSize = 36f
                val lines = listOf(
                    "今日はいい天気ですね。",
                    "一緒に出かけませんか。",
                    "まだ準備ができません。",
                    "少し待ってください。",
                    "もうすぐ終わります。",
                    "ありがとうございます。",
                )
                repeat(6) { index ->
                    val left = 50f + (index % 2) * 600f
                    val top = 70f + (index / 2) * 500f
                    bubble(left, top, left + 500f, top + 430f)
                    canvas.drawText(lines[index], left + 30f, top + 170f, paint)
                    canvas.drawText("分かりました。", left + 100f, top + 260f, paint)
                }
            }
        }
        return bitmap
    }

    private companion object {
        const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        val UUID_REGEX = Regex(UUID_PATTERN)
        val SHA_REGEX = Regex("[a-f0-9]{64}")
        val PAGE_NAME = Regex("[0-9]{5}\\.png")
        val FIXTURE_NAMES = listOf("horizontal", "vertical", "dense")
        val HANGUL = Regex("[가-힣]")
        const val METHOD = "auto_whole"
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MAX_PAGE_BYTES = 24L * 1024 * 1024
        const val MAX_METADATA_BYTES = 8 * 1024 * 1024
        const val MAX_JSONL_BYTES = 64L * 1024 * 1024
        const val MAX_RUN_DATA_BYTES = 1536L * 1024 * 1024
        const val MIN_FREE_BYTES = 256L * 1024 * 1024
        val MODEL_PINS = listOf(
            Triple(
                "dbnet_detect.ncnn.param",
                13392L,
                "9e6db2f8c6b0662ab00eb2100b3373d3c984a235eaac0e61c0b2a484ee1ff7b5",
            ),
            Triple(
                "dbnet_detect.ncnn.bin",
                153010556L,
                "f57bdbede7764a534c56e88be0269602259a7fcd47e54e8b7d954fd0fcc55c3d",
            ),
            Triple(
                "ocr_48px_ctc.ncnn.param",
                18133L,
                "e701cfc5df9d3c55c9fd0a36725499d01d45c18e32946271fc26e580cd9901bd",
            ),
            Triple(
                "ocr_48px_ctc_mixed.ncnn.param",
                18438L,
                "32e298deca3acb8ba95897ca8cccbd028582ae42a697c3c05b4eaefe198309e3",
            ),
            Triple(
                "ocr_48px_ctc.ncnn.bin",
                83037664L,
                "3e0a809441f5284871d18a3d757a7ec098c4b9ee64bdfd7ac776a84aa057c7a9",
            ),
            Triple(
                "mit_aot_fixed512.ncnn.param",
                33810L,
                "f21ef860d21a6cdf60dfb1742c08d1c0d98837bceeb2ee3fe9c2dbbeee7d32b5",
            ),
            Triple(
                "mit_aot_fixed512.ncnn.bin",
                11366088L,
                "a52db45eafc1dd2aa4ce9a339c711917fa98fefb31ce4506d4c95e8b5e3560b6",
            ),
        )
    }
}
