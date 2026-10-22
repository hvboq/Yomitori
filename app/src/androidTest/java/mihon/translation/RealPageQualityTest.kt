package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in local research only. Copyright-unclear source images are provisioned externally, never bundled. */
@RunWith(AndroidJUnit4::class)
class RealPageQualityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun productionChapterTranslatesRealPagesAndPreservesRecovery() = runBlocking(Dispatchers.IO) {
        assumeTrue(args.getString("realPageQuality") == "true")
        val pageCount = (args.getString("realPageCount") ?: "2").toInt()
        require(pageCount in 2..20)
        val root = requireNotNull(context.getExternalFilesDir(null))
        val output = File(root, "real-pages-${System.currentTimeMillis()}")
        check(output.mkdir())
        val report = JSONObject().put("pid", Process.myPid()).put("preferencesChanged", false)
            .put("execution", "Production PageTranslator.translateChapter, auto_whole default depth 2")
            .put("qualityLimit", "Persistence and structural assertions do not prove semantic translation accuracy")
            .put("copyright", "Local research fixtures; original source and image redistribution licence unverified")
            .put("memoryBefore", memory())
            .put("requestedPages", pageCount)
            .put("uniqueFixtures", 2)
        val rows = JSONArray()
        report.put("pages", rows)
        val start = SystemClock.elapsedRealtimeNanos()
        val completionTimes = linkedMapOf<String, Double>()
        val completionMemory = linkedMapOf<String, JSONObject>()
        var succeeded = false
        fun checkpoint() = writeJson(File(output, "result.json"), report)
        checkpoint()
        println("REAL_PAGE_REPORT=${File(output, "result.json").absolutePath}")
        try {
            assertSettings()
            val translator = PageTranslator(context)
            val chapter = requireNotNull(UniFile.fromFile(output))
            val fixtures = listOf(
                Triple("ch34_011.jpg", "001.png", "0db76891835ba25c4a897ae05e16dd3cfe19521376125bb35cf8e634b00354ae"),
                Triple("demo01.jpg", "002.png", "f9072089137c0d0fa9e2bad330644a55c75f95744d19400ef53b7904c149fafe"),
            )
            repeat(pageCount) { pageIndex ->
                val (sourceName, _, expectedHash) = fixtures[pageIndex % fixtures.size]
                val pageName = "%03d.png".format(java.util.Locale.ROOT, pageIndex + 1)
                val source = File(root, "real-page-input/$sourceName")
                check(source.isFile && digest(source) == expectedHash) {
                    "Missing or unexpected approved fixture: $sourceName"
                }
                val decoded = requireNotNull(BitmapFactory.decodeFile(source.path)) { "Fixture decode failed" }
                try {
                    val page = File(output, pageName)
                    page.outputStream().use { check(decoded.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    rows.put(
                        JSONObject().put("page", pageName).put("fixture", sourceName)
                            .put("fixtureSha256", expectedHash).put("originalSha256", digest(page))
                            .put("width", decoded.width).put("height", decoded.height),
                    )
                } finally {
                    decoded.recycle()
                }
            }
            checkpoint()
            val service = Injekt.get<TranslationEngineService>()
            // Observe the existing counter without changing production scheduling or logging settings.
            val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight")
                .apply { isAccessible = true }.get(service) as AtomicInteger
            check(inFlight.get() == 0) { "Run only while the app translation service is idle" }
            val maxInFlight = AtomicInteger(0)
            val observer = launch {
                while (isActive) {
                    maxInFlight.updateAndGet { maxOf(it, inFlight.get()) }
                    delay(25)
                }
            }
            val translationStart = SystemClock.elapsedRealtimeNanos()
            val count = try {
                withTimeout(900_000) {
                    translator.translateChapter(
                        chapter,
                        "auto_whole",
                        nightMaterials = false,
                        onPageDone = { name ->
                            synchronized(completionTimes) {
                                completionTimes[name] = elapsed(translationStart)
                                completionMemory[name] = memory().put("inFlight", inFlight.get())
                                report.put("pageCompletionMsFromChapterStart", JSONObject(completionTimes.toMap()))
                                report.put("memoryAfterEachCompletion", JSONObject(completionMemory.toMap()))
                                checkpoint()
                            }
                        },
                    )
                }
            } finally {
                observer.cancelAndJoin()
                report.put("translationWallMs", elapsed(translationStart)).put("maxObservedInFlight", maxInFlight.get())
                synchronized(completionTimes) {
                    report.put("pageCompletionMsFromChapterStart", JSONObject(completionTimes.toMap()))
                }
                report.put("memoryAfterChapter", memory())
                checkpoint()
            }
            report.put("translatedPages", count)
            check(count == pageCount) { "Expected $pageCount translated pages; inspect chapter diagnostics" }
            check(maxInFlight.get() >= 2) { "Two-page production overlap was not observed" }
            for (i in 0 until rows.length()) {
                verifyPage(output, rows.getJSONObject(i), translator)
                checkpoint()
            }
            check(translator.isChapterTranslated(chapter)) { "Chapter completion marker missing" }
            // Exercise actual original restoration on a separate test-owned copy. Keep the translated
            // chapter intact for a new-process resume check and subsequent local visual inspection.
            val restored = File(output, "restore-check")
            check(restored.mkdir())
            for (i in 0 until rows.length()) {
                File(output, rows.getJSONObject(i).getString("page"))
                    .copyTo(File(restored, rows.getJSONObject(i).getString("page")))
            }
            check(File(output, ".yakuyomi").copyRecursively(File(restored, ".yakuyomi")))
            check(
                translator.reRenderChapter(
                    requireNotNull(UniFile.fromFile(restored)),
                    PageTranslator.ORIGINAL_METHOD,
                ) ==
                    pageCount,
            ) {
                "Production original restoration failed"
            }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                check(digest(File(restored, row.getString("page"))) == row.getString("originalSha256")) {
                    "Restored bytes differ"
                }
                row.put("restoredOriginalExact", true)
            }
            val ledger = JSONObject().put("pid", Process.myPid()).put("directory", output.name)
                .put("pages", rows).put("complete", true)
            writeJson(File(output, "ledger.json"), ledger)
            writeJson(File(root, "real-page-latest.json"), JSONObject().put("directory", output.name))
            succeeded = true
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.simpleName).put("failure", safeText(failure.message.orEmpty()))
            throw failure
        } finally {
            report.put("completed", succeeded).put("wallMs", elapsed(start)).put("memoryAfter", memory())
            val errors = File(output, ".yakuyomi_errors.txt")
            if (errors.isFile) {
                report.put(
                    "chapterErrors",
                    safeText(
                        errors.inputStream().use {
                            it.readNBytes(16384)
                        }.toString(Charsets.UTF_8),
                    ),
                )
            }
            checkpoint()
            // No global shutdown: this test does not own the app's shared engine or its other clients.
        }
    }

    @Test
    fun newProcessResumesPersistedChapterWithoutRewritingPages() = runBlocking(Dispatchers.IO) {
        assumeTrue(args.getString("realPageResume") == "true")
        val root = requireNotNull(context.getExternalFilesDir(null))
        val name = args.getString("realPageDirectory")
            ?: JSONObject(File(root, "real-page-latest.json").readText()).getString("directory")
        check(Regex("real-pages-[0-9]+").matches(name)) { "Pass realPageDirectory from the preceding run" }
        val output = File(root, name)
        val report = JSONObject().put("pid", Process.myPid()).put("preferencesChanged", false)
            .put("networkObservation", "Network calls are not instrumented; no zero-network assertion")
        var succeeded = false
        try {
            assertSettings()
            val ledger = JSONObject(File(output, "ledger.json").readText())
            check(ledger.getBoolean("complete") && ledger.getString("directory") == name)
            check(ledger.getInt("pid") != Process.myPid()) { "Restart check requires a new app process" }
            val rows = ledger.getJSONArray("pages")
            val translator = PageTranslator(context)
            for (i in 0 until rows.length()) {
                verifyPage(
                    output,
                    rows.getJSONObject(i),
                    translator,
                    verifyExistingHashes = true,
                )
            }
            val snapshot = fileHashes(output)
            val count = withTimeout(60_000) {
                translator.translateChapter(
                    requireNotNull(UniFile.fromFile(output)),
                    "auto_whole",
                    nightMaterials = false,
                )
            }
            check(count == 0) { "Completed chapter was translated again" }
            check(snapshot == fileHashes(output)) { "Resume rewrote chapter or material files" }
            report.put("translatedPages", count).put("allFileHashesUnchanged", true).put("pages", rows)
            succeeded = true
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.simpleName).put("failure", safeText(failure.message.orEmpty()))
            throw failure
        } finally {
            report.put("completed", succeeded).put("memoryAfter", memory())
            writeJson(File(output, "restart-result.json"), report)
            println("REAL_PAGE_RESTART_REPORT=${File(output, "restart-result.json").absolutePath}")
        }
    }

    private fun assertSettings() {
        val base = args.getString("liveTranslationBase").orEmpty()
        val model = args.getString("liveTranslationModel").orEmpty()
        check(base.isNotBlank() && model.isNotBlank()) { "Explicit approved server and model arguments required" }
        val prefs = Injekt.get<TranslationPreferences>()
        check(prefs.provider.get() == "custom" && prefs.apiBase.get().trimEnd('/') == base.trimEnd('/')) {
            "Configured server mismatch"
        }
        check(prefs.model.get() == model) { "Configured model mismatch" }
        check(prefs.apiKeyFor("custom").get().isEmpty()) { "This opt-in test requires the configured no-key server" }
        check(prefs.sourceLangName.get() == "Japanese" && prefs.targetLangName.get() == "Korean")
        check(prefs.orientation.get() == "horizontal" && prefs.inpaintMethod.get() == "auto_whole")
        check(prefs.liveTranslate.get() && prefs.translationMasterEnabled.get()) {
            "Live translation and master settings required"
        }
        check(TranslationEngineConfig.modelsResolvable(context)) { "Required local models are missing" }
    }

    private fun verifyPage(
        output: File,
        row: JSONObject,
        translator: PageTranslator,
        verifyExistingHashes: Boolean = false,
    ) {
        val page = File(output, row.getString("page"))
        val chapter = requireNotNull(UniFile.fromFile(output))
        check(translator.isPageTranslated(chapter, page.name)) { "Page completion marker missing: ${page.name}" }
        val backup = File(output, ".yakuyomi/${page.nameWithoutExtension}.orig.png")
        check(digest(backup) == row.getString("originalSha256")) { "Original backup differs: ${page.name}" }
        check(translator.originalForPreview(chapter, page.name) != null) { "Original preview unavailable" }
        val materialFile = File(output, ".yakuyomi/${page.nameWithoutExtension}.json")
        val materials = json.decodeFromString<PageMaterials>(materialFile.readText())
        val translated = materials.regions.filterNot { it.preserveOriginal }
        check(translated.isNotEmpty() && translated.all { it.source.isNotBlank() && it.target.isNotBlank() }) {
            "Missing OCR/translation regions"
        }
        check(translated.any { Regex("[가-힣]").containsMatchIn(it.target) }) { "No Korean translation found" }
        val validCoordinates = materials.regions.all { region ->
            region.quads.isNotEmpty() && region.quads.all { quad ->
                quad.size == 4 && quad.all { point -> point.size == 2 && point.all(Float::isFinite) }
            }
        }
        check(validCoordinates) {
            "Invalid persisted region coordinates"
        }
        val translatedHash = digest(page)
        check(translatedHash != row.getString("originalSha256")) { "Saved translation is unchanged" }
        if (verifyExistingHashes) {
            check(translatedHash == row.getString("translatedSha256")) { "Translated file changed across restart" }
            check(digest(materialFile) == row.getString("materialsSha256")) { "Materials changed across restart" }
        }
        val reader = ReaderPage(0, stream = { page.inputStream() })
        val bitmap =
            requireNotNull(
                requireNotNull(reader.displayStream)().use {
                    BitmapFactory.decodeStream(it)
                },
            ) { "Reader stream decode failed" }
        try {
            check(bitmap.width == row.getInt("width") && bitmap.height == row.getInt("height")) {
                "Output size differs"
            }
        } finally {
            bitmap.recycle()
        }
        row.put("translatedSha256", translatedHash).put("materialsSha256", digest(materialFile))
            .put("materials", JSONObject(materialFile.readText())).put("completionMarked", true)
            .put("originalPreserved", true).put("readerStreamDecoded", true)
    }

    private fun fileHashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to digest(it) }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        return JSONObject().put(
            "pssKiB",
            info.totalPss,
        ).put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
    }

    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    private fun safeText(text: String) = text.replace(Regex("https?://[^\\s]+"), "[endpoint]").take(16384)
    private fun writeJson(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value.toString(2))
        check(temporary.renameTo(file)) { "Cannot checkpoint test report" }
    }
}
