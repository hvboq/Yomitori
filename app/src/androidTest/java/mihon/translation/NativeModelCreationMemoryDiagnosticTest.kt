package mihon.translation

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStore
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.Ocr
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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sequential API-boundary observations, not allocator ownership attribution.
 * Mirrors Yakuyomi.create's three model constructors, retaining all returned objects until close.
 * No detector/OCR/AOT forward, warmUp, translator, image, server, credential or preference writes.
 * Existing constructors may bootstrap SimpleOMP by an internal small ReLU forward; that is not
 * intercepted, pre-run, or misreported as proof of zero native forwards.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalEngineApi::class)
class NativeModelCreationMemoryDiagnosticTest {
    @Test
    fun recordsSequentialFactoryConstructorsWithoutModelInference() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("modelCreationMemory") == "true") {
            "Explicit constructor diagnostic opt-in required"
        }
        val id = checkNotNull(arguments.getString("modelCreationMemoryRunId"))
        check(id.matches(UUID_REGEX) && UUID.fromString(id).toString() == id)
        Executors.newSingleThreadExecutor { task -> Thread(task, "model-creation-memory") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) { runDiagnostic(id) }
            }
    }

    private suspend fun runDiagnostic(id: String) {
        val context = auditContext()
        val parent = privateFiles(context)
        val output = File(parent, "model-creation-memory-$id")
        check(!output.exists() && output.mkdir()) { "Never restart, overwrite, or reuse this UUID" }
        requirePlain(output, parent)
        val identityBefore = identity()
        val report = JSONObject().put("schemaVersion", 1).put("runId", id)
            .put("passed", false).put("completed", false).put("phase", "PREFLIGHT")
            .put("targetPackage", context.packageName).put("processIdentity", identityBefore)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)
            .put("constructorOrder", JSONArray(listOf("detector", "ocr", "inpainter")))
            .put(
                "attributionScope",
                "Sequential constructor API-boundary process differences; not per-model complete allocator ownership",
            )
            .put("firstConstructorMayIncludeLibraryAndSimpleOmpBootstrap", true)
            .put("directModelForwardCallsByTest", 0).put("directWarmUpCallsByTest", 0)
            .put("indirectSimpleOmpBootstrapReluForwardPossible", true)
            .put("allNativeForwardsClaimedZero", false).put("serverRequestsByTest", 0)
            .put("translatorObjectsCreated", 0).put("credentialSlotsReadOrWritten", 0)
            .put("preferencesWrittenByTest", 0).put("testRequestsGcOrTrim", false)
            .put("automaticConstructorOrRunRetries", 0).put("actualFactoryInvoked", false)
            .put(
                "factoryMirroringScope",
                "Same ModelSet resolver, configs and DBNet/OCR/AOT constructor order; no translator/Pipeline wrapper",
            )
            .put("nativeMemoryCompletelyReleasedClaimed", false)
            .put("observationsAreNonAtomicAndAllocate", true)
            .put("expectedDetailedObservationCount", EXPECTED_OBSERVATIONS)
            .put("closeBoundaryBeforeReportBookkeeping", true)
            .put("maxOwnedFileBytes", MAX_OWNED_BYTES).put("maxJsonBytes", MAX_JSON_BYTES)
        val creations = JSONArray().also { report.put("creations", it) }
        val closes = JSONArray().also { report.put("closes", it) }
        val observations = JSONArray().also { report.put("observations", it) }
        val failures = JSONArray().also { report.put("failures", it) }
        var firstFailure: Throwable? = null
        var settings: SettingsGuard? = null
        var modelsBefore: JSONArray? = null
        var modelsDirectory: File? = null
        var shared: TranslationEngineService? = null
        var detector: Detector? = null
        var ocr: Ocr? = null
        var inpainter: Inpainter? = null
        var allConstructed = false
        var currentStage = "PREFLIGHT"
        var finalWritten = false
        var lastCloseBoundaryNanos = 0L

        fun failed(stage: String, error: Throwable) {
            check(failures.length() < MAX_FAILURES)
            val item = JSONObject().put("stage", stage).put("failureClass", error.javaClass.name)
            if (firstFailure == null) {
                firstFailure = error
                report.put("firstFailure", item)
            }
            failures.put(item)
            report.put("passed", false)
        }

        fun components(): List<Pair<String, AutoCloseable>> = listOfNotNull(
            detector?.let { "detector" to it },
            ocr?.let { "ocr" to it },
            inpainter?.let { "inpainter" to it },
        )

        fun observe(point: String): JSONObject {
            check(observations.length() < MAX_OBSERVATIONS)
            return JSONObject().put("point", point).put("memory", memory(detailed = true)).also { observations.put(it) }
        }

        fun checkpoint(phase: String) {
            report.put("phase", phase)
            atomicJsonBounded(File(output, "progress.json"), report, output)
            status(output, id, phase)
        }

        fun create(name: String, constructAndRetain: () -> Unit) {
            currentStage = "CREATE_$name"
            val row = JSONObject().put("component", name).put("completed", false)
                .put("before", observe("beforeCreate:$name"))
            creations.put(row)
            checkpoint(currentStage)
            val started = now()
            try {
                // The lambda retains the returned object BEFORE observation/checkpoint can fail.
                constructAndRetain()
                row.put("constructorMs", ms(now() - started)).put("constructorReturned", true)
                    .put("after", observe("afterCreate:$name"))
                    .put("handleStatesAfterReturn", handleStates(components()))
                check(row.getDouble("constructorMs") <= MAX_CONSTRUCTOR_MS)
                row.put("completed", true)
                checkpoint("CREATED_$name")
            } catch (error: Throwable) {
                row.put("constructorOrObservationFailureClass", error.javaClass.name)
                    .put("boundaryMsAtFailure", ms(now() - started))
                throw error
            }
        }

        try {
            checkpoint("PREFLIGHT")
            check(output.usableSpace >= 32L * 1024 * 1024)
            shared = Injekt.get<TranslationEngineService>()
            withTimeout(10_000L) { Injekt.get<TranslationManager>().ensureRestored() }
            requireIdle(context, checkNotNull(shared))
            settings = SettingsGuard(context)
            report.put("settingsBefore", checkNotNull(settings).description())
            modelsDirectory = modelDirectory(context)
            modelsBefore = modelFingerprints(checkNotNull(modelsDirectory))
            report.put("modelsBefore", modelsBefore)
            val dictionaryBytes = context.assets.open("models/alphabet-all-v5.txt").use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= MAX_DICTIONARY_BYTES)
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
            check(bytesSha(dictionaryBytes) == DICTIONARY_SHA)
            val dictionary = dictionaryBytes.inputStream().bufferedReader().use { it.readLines() }
            check(dictionary.size == 19_264)
            report.put("dictionarySha256", bytesSha(dictionaryBytes)).put("dictionaryRows", dictionary.size)
            val directory = checkNotNull(modelsDirectory)
            val models = checkNotNull(
                ModelSet.resolve(MODEL_PINS.map { (name, _, _) -> name to File(directory, name).path }),
            )
            check(models.ocr == File(directory, "ocr_48px_ctc.ncnn.param").path)
            check(models.detectorNcnn == File(directory, "dbnet_detect.ncnn.param").path)
            check(models.aotInpainterNcnn == File(directory, "mit_aot_fixed512.ncnn.param").path)
            val config = EngineConfig()
            report.put(
                "constructorConfig",
                JSONObject().put("detector", config.detector.toString())
                    .put("ocr", config.ocr.toString()).put("inpainter", config.inpainter.toString()),
            )
            check(
                config.ocr.concurrent && config.ocr.ncnnFp16Storage && config.ocr.ncnnFp16Arith && config.ocr.ncnnMixed,
            )
            check(config.inpainter.method == "aot" && config.inpainter.tileSize == 768)
            requireIdentity(identityBefore)
            create("detector") { detector = Detector(checkNotNull(models.detectorNcnn), config.detector) }
            checkNotNull(settings).verifyMemory()
            requireIdle(context, checkNotNull(shared))
            create("ocr") { ocr = Ocr(models.ocr, dictionary, config.ocr) }
            report.put("actualOcrBackend", checkNotNull(ocr).backend)
                .put(
                    "actualOcrNativeThreads",
                    Ocr::class.java.getDeclaredField("threads")
                        .apply { isAccessible = true }.getInt(ocr),
                )
            checkNotNull(settings).verifyMemory()
            requireIdle(context, checkNotNull(shared))
            create("inpainter") {
                inpainter = Inpainter(checkNotNull(models.aotInpainterNcnn), config.inpainter)
                allConstructed = true
            }
            report.put("allThreeReturnedObjectsSimultaneouslyHeld", true)
                .put("afterAllCreateBeforeAnyWarmUp", observe("afterAllCreateBeforeAnyWarmUp"))
            check(handleStates(components()).getBoolean("allNonZero"))
            checkpoint("ALL_CREATED_NO_MODEL_FORWARD")
        } catch (error: Throwable) {
            failed(currentStage, error)
        } finally {
            // Success mirrors Pipeline.close order; partial construction mirrors ConstructionResources.
            val owned = components()
            val closeOrder = if (allConstructed) owned else owned.asReversed()
            report.put("allThreeConstructorsCompleted", allConstructed)
                .put("closeOrderBasis", if (allConstructed) "Pipeline.close" else "ConstructionResources.close")
                .put("actualCloseOrder", JSONArray(closeOrder.map { it.first }))
            try {
                report.put("nativeHandlesBeforeClose", handleStates(owned))
            } catch (error: Throwable) {
                failed("HANDLE_OBSERVATION_BEFORE_CLOSE", error)
            }
            for ((name, component) in closeOrder) {
                val row = JSONObject().put("component", name).put("attempts", 1).put("returned", false)
                closes.put(row)
                try {
                    row.put("before", observe("beforeClose:$name"))
                } catch (error: Throwable) {
                    failed("OBSERVE_BEFORE_CLOSE_$name", error)
                }
                val started = now()
                var closeFailure: Throwable? = null
                try {
                    component.close()
                } catch (error: Throwable) {
                    closeFailure = error
                } finally {
                    // Anchor offsets at the actual close return/throw, before JSON or failure accounting.
                    lastCloseBoundaryNanos = now()
                }
                row.put("returned", closeFailure == null)
                    .put("closeMs", ms(lastCloseBoundaryNanos - started))
                    .put("closeBoundaryElapsedRealtimeNanos", lastCloseBoundaryNanos)
                closeFailure?.let { error ->
                    row.put("failureClass", error.javaClass.name)
                    failed("CLOSE_$name", error)
                }
                try {
                    row.put("after", observe("afterClose:$name"))
                    check(!componentHandleNonZero(component))
                } catch (error: Throwable) {
                    failed("OBSERVE_AFTER_CLOSE_$name", error)
                }
            }
            try {
                report.put("nativeHandlesAfterClose", handleStates(owned))
                if (owned.isNotEmpty()) check(handleStates(owned).getBoolean("allZero"))
                if (owned.isNotEmpty()) {
                    val closedAt = lastCloseBoundaryNanos
                    check(closedAt > 0L)
                    val post = JSONArray().also { report.put("postClose", it) }
                    for (offset in listOf(0, 30, 120)) {
                        awaitOffset(closedAt, offset * 1000L)
                        requireIdentity(identityBefore)
                        post.put(
                            JSONObject().put("requestedOffsetSeconds", offset)
                                .put("actualOffsetMs", ms(now() - closedAt))
                                .put("observation", observe("postClose:$offset")),
                        )
                        checkpoint("POST_CLOSE_$offset")
                    }
                } else {
                    report.put("postCloseNotCollectedReason", "No constructor returned an owned component")
                }
            } catch (error: Throwable) {
                failed("POST_CLOSE_ACCOUNTING", error)
            }
            try {
                val before = checkNotNull(modelsBefore)
                val after = modelFingerprints(checkNotNull(modelsDirectory))
                report.put("modelsAfter", after)
                check(before.toString() == after.toString())
                report.put("sevenModelFilesUnchanged", true)
                checkNotNull(settings).verifyAll()
                report.put("settingsAfter", checkNotNull(settings).description())
                    .put("selectedSettingsMemoryDiskValueAndPresenceUnchanged", true)
                requireIdle(context, checkNotNull(shared))
                requireIdentity(identityBefore)
                check(allConstructed && creations.length() == 3 && closes.length() == 3)
                check(observations.length() == EXPECTED_OBSERVATIONS)
                check(
                    (0 until observations.length()).all {
                        observations.getJSONObject(it).getJSONObject("memory")
                            .getJSONObject("selfSmaps").getBoolean("complete")
                    },
                ) { "Detailed observation incomplete; retain partial evidence without claiming pass" }
                report.put("allDetailedMemoryObservationsComplete", true)
            } catch (error: Throwable) {
                failed("FINAL_VALIDATION", error)
            }
            report.put("completed", true).put("passed", firstFailure == null)
                .put("phase", if (firstFailure == null) "COMPLETED" else "FAILED")
                .put("constructorCount", creations.length()).put("closeAttempts", closes.length())
            try {
                val final = File(output, "result.json")
                check(!final.exists()) { "Do not overwrite a prior final result" }
                atomicJsonBounded(final, report, output)
                finalWritten = true
            } catch (error: Throwable) {
                failed("WRITE_FINAL_RESULT", error)
                report.put("passed", false).put("phase", "FINAL_WRITE_FAILED")
                try {
                    atomicJsonBounded(File(output, "progress.json"), report, output)
                } catch (secondary: Throwable) {
                    firstFailure?.addSuppressed(secondary)
                }
            }
        }
        firstFailure?.let { throw it }
        check(finalWritten && report.getBoolean("passed") && report.getBoolean("completed"))
    }

    private fun componentHandleNonZero(component: AutoCloseable): Boolean = component.javaClass
        .getDeclaredField("ncnnHandle").apply { isAccessible = true }.getLong(component) != 0L

    private fun handleStates(components: List<Pair<String, AutoCloseable>>): JSONObject {
        val states = components.associate { (name, component) -> name to componentHandleNonZero(component) }
        return JSONObject().put("returnedComponentCount", states.size).put("componentsNonZero", JSONObject(states))
            .put("allNonZero", states.isNotEmpty() && states.values.all { it })
            .put("allZero", states.isNotEmpty() && states.values.none { it })
            .put(
                "scope",
                "Returned Kotlin objects' handle field states only; no pointer or native ownership attribution",
            )
    }

    private fun status(output: File, id: String, phase: String) = InstrumentationRegistry.getInstrumentation()
        .sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "MODEL_CREATION_MEMORY_STATUS=" + JSONObject().put("runId", id)
                        .put("phase", phase).put("directory", output.path)
                        .put("report", File(output, "result.json").path),
                )
            },
        )

    private fun atomicJsonBounded(file: File, value: JSONObject, directory: File) {
        requirePlain(file, directory)
        val bytes = value.toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..MAX_JSON_BYTES)
        checkOwned(directory)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        requirePlain(file, directory)
        checkOwned(directory)
    }

    private fun checkOwned(directory: File) {
        val files = checkNotNull(directory.listFiles())
        check(files.size <= 6)
        var total = 0L
        for (file in files) {
            check(file.name in OWNED_NAMES)
            requirePlain(file, directory)
            check(file.isFile)
            total = Math.addExact(total, file.length())
        }
        check(total <= MAX_OWNED_BYTES)
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
        if (file.exists()) {
            val state = Os.lstat(file.path)
            check(!OsConstants.S_ISLNK(state.st_mode))
            check(state.st_uid == Process.myUid())
            if (OsConstants.S_ISREG(state.st_mode)) {
                check(state.st_nlink == 1L)
            } else {
                check(OsConstants.S_ISDIR(state.st_mode))
            }
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtimeNanos()
    private fun ms(nanos: Long): Double = nanos / 1_000_000.0
    private fun identity(): JSONObject {
        val stat = File("/proc/self/stat").readText()
        check(stat.substringBefore(" (").toInt() == Process.myPid())
        val afterName = stat.substring(stat.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
        return JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("processStartTicks", afterName[19]).put("startElapsedRealtimeMs", Process.getStartElapsedRealtime())
    }

    private fun requireIdentity(expected: JSONObject) = check(identity().toString() == expected.toString())

    private fun memory(detailed: Boolean = false): JSONObject {
        val started = now()
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val result = JSONObject().put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("pssKiB", info.totalPss).put("javaPssKiB", info.dalvikPss).put("nativePssKiB", info.nativePss)
            .put("privateDirtyKiB", info.totalPrivateDirty)
            .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaHeapCommittedBytes", runtime.totalMemory()).put("javaHeapMaxBytes", runtime.maxMemory())
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("nativeHeapSizeBytes", Debug.getNativeHeapSize())
            .put("nativeHeapFreeBytes", Debug.getNativeHeapFreeSize())
            .put("fdCount", File("/proc/self/fd").list()?.size ?: -1)
            .put("threadCount", File("/proc/self/task").list()?.size ?: -1)
        if (detailed) result.put("selfSmaps", selfSmaps())
        return observationTiming(result, started)
    }

    private fun observationTiming(result: JSONObject, started: Long): JSONObject {
        val finished = now()
        return result.put("observationStartElapsedRealtimeNanos", started)
            .put("observationEndElapsedRealtimeNanos", finished).put("observationMs", ms(finished - started))
    }

    private class SmapsObservationException(val reason: String) : Exception()

    /** Bounded self-only stream. Mapping names select buckets but never leave this method. */
    private fun selfSmaps(): JSONObject {
        val started = now()
        val result = JSONObject().put("available", false).put("complete", false)
            .put("buckets", JSONObject.NULL).put("totalsKiB", JSONObject.NULL)
            .put("categoriesDescribeMappingNamesNotOwnership", true)
            .put("byteLimit", 8 * 1024 * 1024).put("lineByteLimit", 8192).put("mapLimit", 32_768)
            .put("timeLimitMsCheckedBetweenLines", 2000)
        var bytesRead = 0
        var maps = 0
        var opened = false
        val totals = SMAPS_BUCKETS.associateWith { LongArray(SMAPS_METRICS.size) }
        val counts = SMAPS_BUCKETS.associateWith { 0 }.toMutableMap()
        var bucket: String? = null
        var current = LongArray(SMAPS_METRICS.size) { -1L }
        fun finishMap() {
            val name = bucket ?: return
            if (current.any { it < 0 }) throw SmapsObservationException("missing-required-metric")
            current.forEachIndexed { index, value ->
                totals.getValue(name)[index] = Math.addExact(totals.getValue(name)[index], value)
            }
            counts[name] = counts.getValue(name) + 1
        }
        fun line(value: String) {
            if (now() - started > 2 * NANOS_PER_SECOND) throw SmapsObservationException("time-limit")
            val header = SMAPS_HEADER.matchEntire(value)
            if (header != null) {
                finishMap()
                if (++maps > 32_768) throw SmapsObservationException("map-limit")
                bucket = smapsBucket(header.groupValues[1])
                current = LongArray(SMAPS_METRICS.size) { -1L }
            } else {
                val index = SMAPS_METRICS.indexOf(value.substringBefore(':'))
                if (index >= 0) {
                    if (bucket == null || current[index] >= 0L) throw SmapsObservationException("malformed-map")
                    val fields = value.substringAfter(':').trim().split(Regex("\\s+"))
                    val number = fields.firstOrNull()?.toLongOrNull()
                    if (fields.size != 2 || fields[1] != "kB" || number == null || number < 0L) {
                        throw SmapsObservationException("malformed-metric")
                    }
                    current[index] = number
                } else if (bucket == null && value.isNotBlank()) {
                    throw SmapsObservationException("missing-header")
                }
            }
        }
        try {
            File("/proc/self/smaps").inputStream().buffered().use { input ->
                opened = true
                val buffer = ByteArray(8192)
                var length = 0
                while (true) {
                    val byte = input.read()
                    if (byte < 0) break
                    if (++bytesRead > 8 * 1024 * 1024) throw SmapsObservationException("byte-limit")
                    if (byte == 10) {
                        line(String(buffer, 0, length, Charsets.UTF_8))
                        length = 0
                    } else {
                        if (length == buffer.size) throw SmapsObservationException("line-limit")
                        buffer[length++] = byte.toByte()
                    }
                }
                if (length != 0 || maps == 0) throw SmapsObservationException("incomplete-stream")
                finishMap()
            }
            val buckets = JSONObject()
            val all = LongArray(SMAPS_METRICS.size)
            for (name in SMAPS_BUCKETS) {
                val values = JSONObject().put("maps", counts.getValue(name))
                SMAPS_METRICS.forEachIndexed { index, metric ->
                    values.put(metric, totals.getValue(name)[index])
                    all[index] = Math.addExact(all[index], totals.getValue(name)[index])
                }
                buckets.put(name, values)
            }
            val combined = JSONObject()
            SMAPS_METRICS.forEachIndexed { index, metric -> combined.put(metric, all[index]) }
            result.put("complete", true).put("buckets", buckets).put("totalsKiB", combined)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            result.put("unavailableClass", error.javaClass.simpleName)
            if (error is SmapsObservationException) result.put("incompleteReason", error.reason)
        }
        result.put("available", opened).put("bytesRead", bytesRead).put("mapsRead", maps)
        return observationTiming(result, started)
    }

    private fun smapsBucket(raw: String): String {
        val name = raw.removeSuffix(" (deleted)")
        return when {
            name.isEmpty() -> "unnamedAnonymous"
            name == "[heap]" || name.startsWith("[anon:libc_malloc") || name.startsWith("[anon:scudo") ->
                "allocatorNamed"
            name.startsWith("[anon:dalvik") -> "artNamed"
            name.startsWith("[stack") || name.startsWith("[anon:stack_and_tls") -> "stackNamed"
            name.startsWith("[anon:") -> "otherNamedAnonymous"
            name.startsWith('/') && MODEL_PINS.any { it.first == name.substringAfterLast('/') } -> "modelNamedFile"
            name.startsWith('/') -> "otherFileNamed"
            else -> "unknown"
        }
    }

    private suspend fun awaitOffset(origin: Long, milliseconds: Long) {
        while (true) {
            val remaining = milliseconds * 1_000_000L - (now() - origin)
            if (remaining <= 0L) return
            delay(minOf(250L, (remaining / 1_000_000L).coerceAtLeast(1L)))
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

    private companion object {
        const val MAX_JSON_BYTES = 512 * 1024
        const val MAX_OWNED_BYTES = 2L * 1024 * 1024
        const val MAX_DICTIONARY_BYTES = 1024 * 1024
        const val MAX_OBSERVATIONS = 24
        const val EXPECTED_OBSERVATIONS = 16
        const val MAX_FAILURES = 24
        const val MAX_CONSTRUCTOR_MS = 240_000.0
        val OWNED_NAMES = setOf(
            "progress.json",
            "progress.json.new",
            "progress.json.bak",
            "result.json",
            "result.json.new",
            "result.json.bak",
        )
        val UUID_REGEX = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val SMAPS_METRICS = listOf("Size", "Rss", "Pss", "Private_Clean", "Private_Dirty", "Anonymous", "SwapPss")
        val SMAPS_BUCKETS = listOf(
            "allocatorNamed",
            "artNamed",
            "stackNamed",
            "unnamedAnonymous",
            "otherNamedAnonymous",
            "modelNamedFile",
            "otherFileNamed",
            "unknown",
        )
        val SMAPS_HEADER = Regex(
            "[0-9a-f]+-[0-9a-f]+\\s+[rwxps-]{4}\\s+[0-9a-f]+\\s+[0-9a-f]+:[0-9a-f]+\\s+\\d+(?:\\s+(.*))?",
        )
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        const val NANOS_PER_SECOND = 1_000_000_000L
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
