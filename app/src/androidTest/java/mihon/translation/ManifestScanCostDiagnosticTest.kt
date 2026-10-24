package mihon.translation

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Warmed private RawFile metadata-read diagnostic, not persistLivePage or end-to-end performance.
 * The .avif-named unrelated fixtures contain neutral metadata bytes and are never decoded.
 * Preparation, validation, report writes and fixture cleanup are outside each observed interval.
 */
@RunWith(AndroidJUnit4::class)
class ManifestScanCostDiagnosticTest {
    @Test
    fun measuresOwnedRawFileManifestScanWithoutInference() {
        runBlocking { collect() }
    }

    private suspend fun collect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("manifestScanCost") == "true") { "Explicit manifest scan diagnostic opt-in required" }
        val runId = checkNotNull(arguments.getString("manifestScanCostRunId"))
        check(runId.matches(UUID_PATTERN) && UUID.fromString(runId).toString() == runId)
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit target required" }
        val parent = context.filesDir.canonicalFile
        check(parent.isDirectory && parent.parentFile == File(context.applicationInfo.dataDir).canonicalFile)
        val output = File(parent, "manifest-scan-cost-$runId")
        check(!output.exists() && output.mkdir()) { "Never overwrite or reuse a diagnostic UUID" }
        requirePlain(output, parent)
        val reportFile = File(output, "result.json")
        val samples = JSONArray()
        val warmups = JSONArray()
        val fixtures = ArrayList<Fixture>()
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
            .put("passed", false).put("completed", false).put("diagnosticOnly", true)
            .put("targetPackage", context.packageName).put("processId", Process.myPid())
            .put("timedMethod", "PageTranslator.readManifestFiles")
            .put("timingIncludesReflectionDispatch", true)
            .put(
                "timingExcludes",
                JSONArray(
                    listOf("preparation", "mapValidation", "hashValidation", "idleGuards", "reportWrites", "cleanup"),
                ),
            )
            .put("storageBackend", "UniFile.fromFile over app-private directory; not SAF/OEM provider")
            .put("unrelatedPageCounts", JSONArray(PAGE_COUNTS.toList()))
            .put("warmupCallsPerCondition", WARMUPS).put("measuredCallsPerCondition", ROUNDS)
            .put("expectedMeasuredCalls", PAGE_COUNTS.size * ROUNDS)
            .put("unmeasuredValidationReadCalls", PAGE_COUNTS.size * 2)
            .put("measuredOrderPositionsBalanced", true)
            .put("nativeModelCallsByTest", 0).put("serverCallsByTest", 0).put("imageDecodeCallsByTest", 0)
            .put("rawFixtureBytesAreValidImages", false).put("settingsWrittenByTest", 0)
            .put("credentialsReadOrWritten", 0).put("timingThresholdDeterminesPass", false)
            .put("persistLivePageOrWholeChapterPerformanceProven", false)
            .put("warmFileCacheAndFixedOrderMayInfluenceTimes", true)
            .put("warmups", warmups).put("samples", samples)
        var failure: Throwable? = null
        var settings: SettingsGuard? = null
        var shared: TranslationEngineService? = null

        fun fail(stage: String, error: Throwable) {
            if (failure == null) {
                failure = error
                report.put("firstFailureStage", stage).put("firstFailureClass", error.javaClass.name)
            } else {
                failure?.addSuppressed(error)
            }
        }

        fun checkpoint(phase: String) {
            report.put("phase", phase)
            atomicReport(reportFile, output, report)
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "MANIFEST_SCAN_COST_STATUS=" + JSONObject().put("runId", runId).put("phase", phase)
                            .put("directory", output.path).put("report", reportFile.path),
                    )
                },
            )
        }

        try {
            checkpoint("PREFLIGHT")
            shared = Injekt.get<TranslationEngineService>()
            withTimeout(10_000L) { Injekt.get<TranslationManager>().ensureRestored() }
            requireIdle(context, checkNotNull(shared))
            settings = SettingsGuard(context)
            report.put("settingsBefore", checkNotNull(settings).description())
            check(output.usableSpace >= 8L * 1024 * 1024)
            PAGE_COUNTS.forEach { count ->
                fixtures += prepareFixture(output, count)
            }
            report.put("fixtures", JSONArray(fixtures.map { it.description() }))
            val translator = PageTranslator(context)
            val method = PageTranslator::class.java.getDeclaredMethod("readManifestFiles", UniFile::class.java)
                .apply { isAccessible = true }
            fixtures.forEach { fixture ->
                verifyFixture(fixture, output)
                fixture.validate(readOnce(method, translator, fixture).second)
            }
            checkpoint("PREPARED")
            repeat(WARMUPS) { round ->
                order(round).forEachIndexed { position, index ->
                    val fixture = fixtures[index]
                    val (elapsed, value) = readOnce(method, translator, fixture)
                    fixture.validate(value)
                    warmups.put(sample(round, position, fixture.pageCount, elapsed))
                }
            }
            checkpoint("WARMED")
            repeat(ROUNDS) { round ->
                requireIdle(context, checkNotNull(shared))
                order(round + WARMUPS).forEachIndexed { position, index ->
                    val fixture = fixtures[index]
                    val (elapsed, value) = readOnce(method, translator, fixture)
                    fixture.validate(value)
                    samples.put(sample(round, position, fixture.pageCount, elapsed))
                }
                // No report serialization or fsync is included in a method's elapsed interval.
                checkpoint("MEASURED_ROUND_${round + 1}")
            }
            check(samples.length() == PAGE_COUNTS.size * ROUNDS)
            check(warmups.length() == PAGE_COUNTS.size * WARMUPS)
            PAGE_COUNTS.forEach { count ->
                check(
                    (0 until samples.length()).count {
                        samples.getJSONObject(it).getInt("unrelatedPages") == count
                    } ==
                        ROUNDS,
                )
                PAGE_COUNTS.indices.forEach { position ->
                    check(
                        (0 until samples.length()).count {
                            samples.getJSONObject(it).getInt("unrelatedPages") == count &&
                                samples.getJSONObject(it).getInt("orderPosition") == position
                        } == ROUNDS / PAGE_COUNTS.size,
                    )
                }
            }
            fixtures.forEach { fixture ->
                verifyFixture(fixture, output)
                fixture.validate(readOnce(method, translator, fixture).second)
            }
            report.put("allReadMapsExact", true).put("allFixtureBytesAndManifestHashesUnchanged", true)
                .put("relatedNamedDirectoriesExcluded", true)
        } catch (error: Throwable) {
            fail("COLLECTION", error)
        } finally {
            try {
                checkNotNull(settings).verifyAll()
                report.put("settingsAfter", checkNotNull(settings).description())
                    .put("selectedSettingsMemoryValuePresenceAndDiskUnchanged", true)
                requireIdle(context, checkNotNull(shared))
            } catch (error: Throwable) {
                fail("FINAL_GUARDS", error)
            }
            try {
                // Verify every prepared fixture and every name before the first removal.
                fixtures.forEach { verifyFixture(it, output) }
                val expectedChildren = fixtures.map { it.directory.name }.toSet() + reportFile.name
                check(checkNotNull(output.list()).toSet() == expectedChildren)
                fixtures.forEach(::removeFixture)
                report.put("privateInputFixtureCleanupPassed", true)
                    .put("inputFilesRemoved", fixtures.sumOf { it.files.size })
                    .put("emptyInputDirectoriesRemoved", fixtures.sumOf { it.directories.size })
                    .put("cleanupUsesRecursiveDelete", false)
            } catch (error: Throwable) {
                fail("FIXTURE_CLEANUP", error)
            }
            report.put("completed", true).put("passed", failure == null)
            try {
                checkpoint(if (failure == null) "COMPLETED" else "FAILED")
            } catch (error: Throwable) {
                fail("FINAL_REPORT", error)
            }
        }
        failure?.let { throw it }
        check(reportFile.isFile && checkNotNull(output.list()).toSet() == setOf("result.json"))
        val bytes = reportFile.readBytes()
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "MANIFEST_SCAN_COST=" + JSONObject().put("runId", runId).put("directory", output.path)
                        .put("report", reportFile.path).put("bytes", bytes.size).put("sha256", digest(bytes)),
                )
            },
        )
    }

    private fun order(round: Int): List<Int> = when (round % PAGE_COUNTS.size) {
        0 -> listOf(2, 0, 3, 1)
        1 -> listOf(1, 3, 0, 2)
        2 -> listOf(0, 1, 2, 3)
        else -> listOf(3, 2, 1, 0)
    }

    private fun sample(round: Int, position: Int, count: Int, nanos: Long): JSONObject = JSONObject()
        .put("round", round).put("orderPosition", position).put("unrelatedPages", count).put("elapsedNanos", nanos)
        .put("elapsedMs", nanos / 1_000_000.0)

    /** Only reflection dispatch plus the actual method execute inside these two clocks. */
    private fun readOnce(
        method: Method,
        translator: PageTranslator,
        fixture: Fixture,
    ): Pair<Long, Map<String, ByteArray>> {
        val started = SystemClock.elapsedRealtimeNanos()
        val raw = try {
            method.invoke(translator, fixture.chapter)
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        }
        val elapsed = SystemClock.elapsedRealtimeNanos() - started
        check(elapsed >= 0)
        @Suppress("UNCHECKED_CAST")
        val value = raw as Map<String, ByteArray>
        return elapsed to value
    }

    private data class FilePin(val relative: String, val bytes: Long, val sha256: String)

    private class Fixture(
        val pageCount: Int,
        val directory: File,
        val files: List<FilePin>,
        val directories: List<String>,
        val expected: Map<String, ByteArray>,
    ) {
        val chapter: UniFile = checkNotNull(UniFile.fromFile(directory))

        fun validate(actual: Map<String, ByteArray>) {
            check(actual.keys == expected.keys) { "Only regular primary/checkpoint snapshots may be opened" }
            expected.forEach { (name, bytes) -> check(actual.getValue(name).contentEquals(bytes)) }
            check(CompletionManifestCodec.resolve(actual) == setOf(ANCHOR))
        }

        fun description(): JSONObject = JSONObject().put("unrelatedPages", pageCount)
            .put("directoryName", directory.name).put("inputRegularFiles", files.size)
            .put("relatedNamedDirectories", JSONArray(directories.filter { it.isNotEmpty() }))
            .put("expectedReadNames", JSONArray(expected.keys.sorted()))
            .put(
                "files",
                JSONArray(
                    files.map {
                        JSONObject().put("name", it.relative).put("bytes", it.bytes).put("sha256", it.sha256)
                    },
                ),
            )
    }

    private fun prepareFixture(output: File, count: Int): Fixture {
        val directory = File(output, "chapter-$count")
        check(!directory.exists() && directory.mkdir())
        requirePlain(directory, output)
        val manifest = CompletionManifestCodec.encode(setOf(ANCHOR))
        val expected = linkedMapOf(CompletionManifestCodec.PRIMARY to manifest, CHECKPOINT to manifest)
        val source = linkedMapOf(
            ANCHOR to "neutral metadata anchor; never decoded".toByteArray(),
            INVALID_CHECKPOINT to "YOMITORI-truncated but unrelated filename".toByteArray(),
        )
        repeat(count) { index ->
            source["page-${index.toString().padStart(4, '0')}.avif"] = "neutral unrelated metadata $index".toByteArray()
        }
        val pins = (source + expected).map { (name, bytes) ->
            val file = File(directory, name)
            check(!file.exists())
            file.writeBytes(bytes)
            requirePlain(file, directory)
            FilePin(name, bytes.size.toLong(), digest(bytes))
        }
        val directories = listOf("", CompletionManifestCodec.PREVIOUS, CHECKPOINT_DIRECTORY)
        directories.filter { it.isNotEmpty() }.forEach { name ->
            File(directory, name).also {
                check(!it.exists() && it.mkdir())
                requirePlain(it, directory)
            }
        }
        return Fixture(count, directory, pins, directories, expected)
    }

    private fun verifyFixture(fixture: Fixture, output: File) {
        requirePlain(fixture.directory, output)
        val expectedNames = fixture.files.map { it.relative }.toSet() + fixture.directories.filter { it.isNotEmpty() }
        check(checkNotNull(fixture.directory.list()).toSet() == expectedNames)
        fixture.files.forEach { pin ->
            val file = File(fixture.directory, pin.relative)
            requirePlain(file, fixture.directory)
            check(file.isFile && file.length() == pin.bytes && digest(file.readBytes()) == pin.sha256)
        }
        fixture.directories.filter { it.isNotEmpty() }.forEach { name ->
            File(fixture.directory, name).also {
                requirePlain(it, fixture.directory)
                check(it.isDirectory && checkNotNull(it.list()).isEmpty())
            }
        }
    }

    private fun removeFixture(fixture: Fixture) {
        fixture.files.forEach { pin -> check(File(fixture.directory, pin.relative).delete()) }
        fixture.directories.filter { it.isNotEmpty() }.forEach { name -> check(File(fixture.directory, name).delete()) }
        check(fixture.directory.delete() && !fixture.directory.exists())
    }

    private class SettingsGuard(context: Context) {
        private val prefs = Injekt.get<TranslationPreferences>()
        private val preferences = listOf<Preference<*>>(
            prefs.translationMasterEnabled,
            prefs.translationEnabled,
            prefs.liveTranslate,
            prefs.nightReadEnabled,
        )
        private val file =
            File(
                File(context.applicationInfo.dataDir).canonicalFile,
                "shared_prefs/${context.packageName}_preferences.xml",
            )
        private val memory = memoryDigest()
        private val disk = diskDigest()

        private fun memoryDigest(): String = digest(
            preferences.sortedBy {
                it.key()
            }.joinToString("\n") { "${it.key()}|${it.isSet()}|${it.get()}" }.toByteArray(),
        )

        private fun diskDigest(): String {
            val keys = preferences.map { it.key() }.toSet()
            if (!file.exists()) {
                check(preferences.none { it.isSet() })
                return digest("selected XML absent".toByteArray())
            }
            val selected = mutableMapOf<String, String>()
            file.inputStream().use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        val key = parser.getAttributeValue(null, "name")
                        if (key in keys) {
                            check(parser.name == "boolean")
                            check(selected.put(checkNotNull(key), parser.getAttributeValue(null, "value")) == null)
                        }
                    }
                    parser.next()
                }
            }
            check(preferences.all { it.isSet() == (it.key() in selected) })
            return digest(keys.sorted().joinToString("\n") { "$it|${selected[it] ?: "ABSENT"}" }.toByteArray())
        }

        fun verifyAll() {
            check(memory == memoryDigest()) { "Selected setting values or presence changed" }
            check(disk == diskDigest()) { "Selected setting disk values or presence changed" }
        }

        fun description(): JSONObject = JSONObject().put("selectedPreferenceCount", preferences.size)
            .put("memoryValueAndPresenceSha256", memory).put("selectedXmlSha256", disk)
            .put("writesByTest", 0).put("rawValuesExported", false).put("credentialSlotsReadOrWritten", 0)
    }

    private suspend fun requireIdle(context: Context, shared: TranslationEngineService) {
        check(!shared.loading.value && !shared.warm.value)
        check(TranslationBusy.active.value == 0 && Injekt.get<TranslationManager>().queueState.value.isEmpty())
        val downloads = Injekt.get<DownloadManager>()
        check(downloads.queueState.value.isEmpty() && !downloads.isRunning)
        val engine = TranslationEngineService::class.java.getDeclaredField("engine").apply {
            isAccessible = true
        }.get(shared)
        val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight").apply { isAccessible = true }
            .get(shared) as AtomicInteger
        check(engine == null && inFlight.get() == 0)
        check(TranslationStore(context).restore().isEmpty())
        val pending = TranslationStore(context).restorePendingMarks(Long.MIN_VALUE)
        check(pending.translate.isEmpty() && pending.night.isEmpty())
        for (tag in listOf("Translator", "TranslatorHeartbeat", "Downloader")) {
            val work = WorkManager.getInstance(context).getWorkInfosByTag(tag).get(10, TimeUnit.SECONDS)
            check(work.none { !it.state.isFinished })
        }
    }

    private fun atomicReport(file: File, parent: File, report: JSONObject) {
        requirePlain(file, parent)
        val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..MAX_REPORT_BYTES)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        requirePlain(file, parent)
        check(file.readBytes().contentEquals(bytes))
    }

    private fun requirePlain(file: File, parent: File) {
        check(file.canonicalFile == file.absoluteFile && file.parentFile == parent)
        if (file.exists()) {
            val state = Os.lstat(file.path)
            check(!OsConstants.S_ISLNK(state.st_mode) && state.st_uid == Process.myUid())
            if (OsConstants.S_ISREG(
                    state.st_mode,
                )
            ) {
                check(state.st_nlink == 1L)
            } else {
                check(OsConstants.S_ISDIR(state.st_mode))
            }
        }
    }

    private companion object {
        val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val PAGE_COUNTS = intArrayOf(0, 32, 128, 256)
        const val WARMUPS = 2
        const val ROUNDS = 12
        const val MAX_REPORT_BYTES = 1024 * 1024
        const val ANCHOR = "anchor.avif"
        const val CHECKPOINT = ".yakuyomi_translated.checkpoint-00000000-0000-0000-0000-000000000001"
        const val CHECKPOINT_DIRECTORY = ".yakuyomi_translated.checkpoint-00000000-0000-0000-0000-000000000002"
        const val INVALID_CHECKPOINT = ".yakuyomi_translated.checkpoint-not-a-uuid"

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
