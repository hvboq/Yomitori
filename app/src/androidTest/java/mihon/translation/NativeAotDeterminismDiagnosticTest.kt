package mihon.translation

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.EngineTrace
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.NcnnLoadDiagnosticApi
import li.joye.yakuyomi.engine.NcnnNetOptions
import mihon.core.migration.Migrator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Repeats the unchanged raw AOT entry with fixed arrays, separately from bitmap preprocessing.
 * A collection pass does not assert determinism or approve any production option change.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalEngineApi::class, NcnnLoadDiagnosticApi::class)
class NativeAotDeterminismDiagnosticTest {
    @Test(timeout = 1_200_000)
    fun recordsRawAotRepeatedCallsWithoutPromotion() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("winogradQuality") == "true") { "Explicit raw AOT diagnostic opt-in required" }
        val id = arguments.getString("winogradQualityRunId").orEmpty()
        check(UUID_REGEX.matches(id) && UUID.fromString(id).toString() == id) { "Canonical fresh UUID required" }
        check(arguments.getString("winogradQualityRole") == ROLE) { "Select the raw AOT determinism role" }
        Executors.newSingleThreadExecutor { task -> Thread(task, "aot-raw-determinism") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    Migrator.await()
                    collect(id)
                }
            }
    }

    private suspend fun collect(id: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == PACKAGE)
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
        val cache = context.cacheDir.canonicalFile
        val output = File(cache, "native-winograd-quality-$id")
        check(cache.isDirectory && !output.exists() && output.mkdir()) { "Never overwrite an earlier diagnostic" }
        requirePlain(output, cache, directory = true)
        val sessions = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("runId", id).put("role", ROLE)
            .put("targetPackage", context.packageName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("passed", false).put("finalized", false)
            .put("collectionValidationPassed", false).put("candidateGatePassed", false)
            .put("candidateQualityDeterminesCollectionPass", false).put("productionOptionsPromoted", false)
            .put("passedScope", "Collection, trace, fixed input and lifetime guards; not deterministic output")
            .put("candidateGate", "Every repeat within each mode/input has identical finite raw output bits")
            .put("inputScope", "Two generated raw CHW/mask pairs; not a claim of public bitmap-input equivalence")
            .put("publicModelWrapperInvoked", false).put("existingRawAotEntryInvoked", true)
            .put("diagnosticLoadOrDetachInvoked", false).put("nativePointerValuesExported", false)
            .put("networkCallsByTest", 0).put("settingsWrittenByTest", 0).put("credentialApisAccessedByTest", false)
            .put("serviceOrManagerFactoriesCalledByTest", 0).put("modelCopiesOrDownloadsByTest", 0)
            .put("nativeModelsHeldAtOnceByTest", 1).put("cpuAffinityChangedByTest", false)
            .put("gcOrTrimRequestedByTest", false).put("explicitWarmUpCalls", 0).put("automaticRetries", 0)
            .put("plainCreationAndReleaseOnly", true).put("actualKotlinFactoryInvoked", false)
            .put("indirectSimpleOmpBootstrapReluPossible", true).put("allNativeForwardsClaimedExactly24", false)
            .put("inputSide", SIDE).put("inputFloatCount", IMAGE_FLOATS).put("maskFloatCount", AREA)
            .put("inputArraysReusedAcrossModes", true).put("inputResizingOrBitmapConversionByTest", false)
            .put("sameNetSideForEveryCall", true).put("repetitionsPerInput", REPEATS)
            .put("modeCountExpected", MODES.size).put("sourceCountExpected", 2)
            .put("rawAotCallsExpected", MODES.size * 2 * REPEATS)
            .put("timingClock", "System.nanoTime / CLOCK_MONOTONIC")
            .put("latencyScope", "Raw AOT wrapper including trace observer; arrays, hashing and report IO excluded")
            .put("coldIoLongTermPerformanceOrPeakMemoryMeasured", false)
            .put("imageFiles", JSONArray()).put("sessions", sessions)
        var stage = "PREFLIGHT"
        var primaryFailure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        var pinsBefore: JSONArray? = null
        var attempts = 0
        var completed = 0
        var creates = 0
        var releases = 0
        var allDeterministic = true
        var allFinite = true
        var defaultThreads: Int? = null
        val file = File(output, "result.json")
        fun checkpoint() {
            report.put("stage", stage).put("rawAotCallsAttempted", attempts).put("rawAotCallsCompleted", completed)
                .put("successfulModelCreates", creates).put("successfulModelReleaseCalls", releases)
            writeReport(output, report)
        }
        fun phase(value: String) {
            stage = value
            checkpoint()
            sendStatus("WINOGRAD_QUALITY_STAGE", JSONObject().put("runId", id).put("role", ROLE).put("stage", stage))
        }
        try {
            phase("PREFLIGHT")
            report.put("idleBefore", NativeDiagnosticIdleGuard.requireIdle(context))
            val models = File(context.filesDir.canonicalFile, "models")
            requirePlain(models, context.filesDir.canonicalFile, directory = true)
            pinsBefore = modelPins(models)
            report.put("modelsBefore", pinsBefore)
            check(NcnnBackend.available)
            report.put("cpuSupportsFp16", NcnnBackend.cpuSupportsFp16)
            val inputs = listOf(generatedInput("texture"), generatedInput("gradient"))
            report.put("inputs", JSONArray(inputs.map { it.metadata() }))
            val param = File(models, "mit_aot_fixed512.ncnn.param")
            val bin = File(models, "mit_aot_fixed512.ncnn.bin")
            for ((modeIndex, mode) in MODES.withIndex()) {
                currentCoroutineContext().ensureActive()
                phase("MODE_${modeIndex}_${mode.name}")
                NativeDiagnosticIdleGuard.requireIdle(context)
                val calls = JSONArray()
                val row = JSONObject().put("index", modeIndex).put("mode", mode.name).put("calls", calls)
                    .put("usedDiagnosticLoad", false).put("usedDetach", false)
                    .put("rawHandleExported", false).put("rawNetVariableClearedAfterRelease", false)
                    .put("releaseAttempted", false).put("sameNetForBothInputs", true)
                sessions.put(row)
                var handle = 0L
                var modeFailure: Throwable? = null
                try {
                    val loadStart = System.nanoTime()
                    handle = when (modeIndex) {
                        0 -> NcnnBackend.createNet(param.path, bin.path)
                        1 -> NcnnBackend.createNetEx(param.path, bin.path, 1, true, true)
                        2 -> NcnnBackend.createNetEx(param.path, bin.path, 1, false, false)
                        3 -> NcnnBackend.createNetEx(
                            param.path,
                            bin.path,
                            checkNotNull(defaultThreads),
                            true,
                            false,
                        )
                        else -> error("Unexpected predeclared mode")
                    }
                    val loadElapsed = System.nanoTime() - loadStart
                    check(handle != 0L) { "AOT model creation failed" }
                    creates++
                    val options = NcnnBackend.readNetOptionsDiagnostic(handle)
                    check(!options.useLocalPoolAllocator && options.numThreads > 0)
                    if (modeIndex == 0) {
                        defaultThreads = options.numThreads
                        report.put("ordinaryObservedThreadCount", options.numThreads)
                    } else {
                        check(options.numThreads == if (modeIndex == 3) defaultThreads else 1)
                    }
                    if (modeIndex == 2) {
                        check(!options.useFp16Packed && !options.useFp16Storage && !options.useFp16Arithmetic)
                        check(!options.useBf16Storage)
                    }
                    if (modeIndex == 3) check(!options.useFp16Arithmetic)
                    row.put("actualOptions", options.json()).put("loadNs", loadElapsed)
                        .put("actualNetParamName", param.name).put("actualNetBinName", bin.name)
                    var modeDeterministic = true
                    var modeFinite = true
                    for (input in inputs) {
                        phase("MODE_${modeIndex}_${input.name}")
                        input.verify()
                        val observations = JSONArray()
                        val inputRow = JSONObject().put("source", input.name).put("calls", observations)
                            .put("sameArrayObjectsForAllRepeats", true).put("fixedInputs", input.metadata())
                        calls.put(inputRow)
                        var first: FloatArray? = null
                        var firstSha: String? = null
                        var sourceDeterministic = true
                        var sourceFinite = true
                        for (repetition in 0 until REPEATS) {
                            currentCoroutineContext().ensureActive()
                            input.verify()
                            val result = FloatArray(IMAGE_FLOATS)
                            val call = JSONObject().put("repetition", repetition).put("outputFloatCount", result.size)
                                .put("freshOutputArray", true).put("inputAndMaskBitsUnchangedBefore", true)
                            observations.put(call)
                            attempts++
                            val rc = rawCall(handle, input.image, input.mask, result, call)
                            check(rc == 0 && result.size == IMAGE_FLOATS)
                            completed++
                            call.put("nativeShapeContractSatisfied", true)
                            input.verify()
                            call.put("inputAndMaskBitsUnchangedAfter", true)
                            val stats = statistics(result)
                            val digest = floatsSha(result)
                            if (first == null) {
                                first = result
                                firstSha = digest
                            }
                            val baseline = checkNotNull(first)
                            val difference = difference(baseline, result)
                            val equal = sameRawBits(baseline, result)
                            check(equal == (digest == firstSha))
                            val finite = stats.getInt("nonFiniteCount") == 0
                            sourceDeterministic = sourceDeterministic && equal
                            sourceFinite = sourceFinite && finite
                            call.put("outputRawFloatBitsSha256", digest).put("outputStatistics", stats)
                                .put("firstOutputRawFloatBitsSha256", firstSha)
                                .put("exactRawBitsEqualToFirst", equal).put("differenceFromFirst", difference)
                            checkpoint()
                        }
                        input.verify()
                        inputRow.put("allInputAndMaskBitsUnchanged", true)
                            .put("allRepeatedRawBitsEqual", sourceDeterministic)
                            .put("allOutputsFinite", sourceFinite)
                        modeDeterministic = modeDeterministic && sourceDeterministic
                        modeFinite = modeFinite && sourceFinite
                    }
                    row.put("allRepeatedRawBitsEqual", modeDeterministic).put("allOutputsFinite", modeFinite)
                        .put("rawDeterminismGatePassed", modeDeterministic && modeFinite)
                    allDeterministic = allDeterministic && modeDeterministic
                    allFinite = allFinite && modeFinite
                } catch (error: Throwable) {
                    modeFailure = error
                    throw error
                } finally {
                    var releaseAttempted = false
                    var releaseFailure: Throwable? = null
                    if (handle != 0L) {
                        releaseAttempted = true
                        try {
                            NcnnBackend.releaseNet(handle)
                            handle = 0L
                            releases++
                        } catch (closing: Throwable) {
                            releaseFailure = closing
                        }
                    }
                    // Native teardown precedes every reporting allocation in this finally block.
                    try {
                        row.put("releaseAttempted", releaseAttempted)
                        if (releaseAttempted && releaseFailure == null) {
                            row.put("rawNetVariableClearedAfterRelease", handle == 0L)
                                .put(
                                    "releaseScope",
                                    "Ordinary release returned and the local owning variable became zero",
                                )
                        }
                        releaseFailure?.let { row.put("releaseFailureClass", it.javaClass.name) }
                    } catch (reporting: Throwable) {
                        val previous = releaseFailure ?: modeFailure
                        if (previous == null) throw reporting else previous.addSuppressed(reporting)
                    }
                    releaseFailure?.let { closing ->
                        val previous = modeFailure
                        if (previous == null) throw closing else previous.addSuppressed(closing)
                    }
                }
                check(handle == 0L)
                checkpoint()
            }
            inputs.forEach { it.verify() }
            check(attempts == MODES.size * inputs.size * REPEATS && completed == attempts)
            check(creates == MODES.size && releases == creates && sessions.length() == MODES.size)
            check(
                (0 until sessions.length()).all {
                    sessions.getJSONObject(it).getBoolean("rawNetVariableClearedAfterRelease")
                },
            )
            report.put("idleAfter", NativeDiagnosticIdleGuard.requireIdle(context))
            val after = modelPins(models)
            check(after.toString() == pinsBefore.toString())
            report.put("modelsAfter", after).put("models7Unchanged", true)
                .put("allRepeatedRawBitsEqual", allDeterministic).put("allRawOutputsFinite", allFinite)
                .put("candidateGatePassed", allDeterministic && allFinite)
                .put("collectionValidationPassed", true)
        } catch (error: Throwable) {
            primaryFailure = error
            report.put("failedStage", stage).put("failureClass", error.javaClass.name)
        } finally {
            if (pinsBefore != null) {
                try {
                    val after = modelPins(File(context.filesDir.canonicalFile, "models"))
                    check(after.toString() == pinsBefore.toString())
                    report.put("models7UnchangedOnFinalCheck", true)
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
            val inventory = output.list()?.toSet().orEmpty()
            val inventoryOk = inventory == setOf("result.json")
            val passed = primaryFailure == null && cleanupFailures.isEmpty() && inventoryOk &&
                report.optBoolean("collectionValidationPassed", false)
            report.put("rawAotCallsAttempted", attempts).put("rawAotCallsCompleted", completed)
                .put("successfulModelCreates", creates).put("successfulModelReleaseCalls", releases)
                .put("ownedFinalInventoryMatchesImagesAndReport", inventoryOk)
                .put("cleanupFailureClasses", JSONArray(cleanupFailures.map { it.javaClass.name }))
                .put("passed", passed).put("finalized", true).put("stage", "FINAL")
            try {
                writeReport(output, report)
                sendStatus(
                    "WINOGRAD_QUALITY",
                    JSONObject().put("runId", id).put("bytes", file.length()).put("sha256", digest(file))
                        .put("passed", passed),
                )
            } catch (error: Throwable) {
                val previous = primaryFailure
                if (previous == null) primaryFailure = error else previous.addSuppressed(error)
            }
        }
        primaryFailure?.let { error ->
            cleanupFailures.forEach(error::addSuppressed)
            throw error
        }
        check(cleanupFailures.isEmpty() && report.getBoolean("passed")) { "Raw AOT collection or cleanup failed" }
    }

    private fun rawCall(
        handle: Long,
        image: FloatArray,
        mask: FloatArray,
        output: FloatArray,
        row: JSONObject,
    ): Int {
        check(image.size == IMAGE_FLOATS && mask.size == AREA && output.size == IMAGE_FLOATS)
        val previous = EngineTrace.sink
        var enters = 0
        var calls = 0
        var exits = 0
        var observedRc: Int? = null
        var failure: Throwable? = null
        val observer: (String) -> Unit = { message ->
            if (message.startsWith("ncnn.inpaint.enter ")) {
                check(message == "ncnn.inpaint.enter s=$SIDE")
                enters++
            }
            if (message.startsWith("ncnn.inpaint.call ")) {
                check(message == "ncnn.inpaint.call s=$SIDE")
                calls++
            }
            if (message.startsWith("ncnn.inpaint.exit rc=")) {
                observedRc = message.substringAfter("rc=").toInt()
                exits++
            }
            previous?.invoke(message)
        }
        EngineTrace.sink = observer
        return try {
            val start = System.nanoTime()
            val rc = NcnnBackend.inpaintAot(handle, image, mask, SIDE, output)
            val elapsed = System.nanoTime() - start
            row.put("elapsedNs", elapsed).put("rc", rc)
            check(elapsed >= 0 && rc == 0)
            check(enters == 1 && calls == 1 && exits == 1 && observedRc == rc)
            rc
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val owned = EngineTrace.sink === observer
            if (owned) EngineTrace.sink = previous
            val restored = EngineTrace.sink === previous
            try {
                row.put(
                    "nativeTrace",
                    JSONObject().put("kind", "inpaint").put("dimension", "s=$SIDE")
                        .put("enter", enters).put("call", calls).put("exit", exits)
                        .put("rc", observedRc ?: JSONObject.NULL)
                        .put("observerIncludedInTimedRawCall", true).put("previousSinkRestored", restored),
                )
            } catch (reporting: Throwable) {
                val previousFailure = failure
                if (previousFailure == null) throw reporting else previousFailure.addSuppressed(reporting)
            }
            if (!owned || !restored) {
                val error = IllegalStateException("Native trace ownership changed during raw AOT call")
                val previousFailure = failure
                if (previousFailure == null) throw error else previousFailure.addSuppressed(error)
            }
        }
    }

    private fun generatedInput(name: String): FixedInput {
        check(name == "texture" || name == "gradient")
        val image = FloatArray(IMAGE_FLOATS)
        val mask = FloatArray(AREA)
        var masked = 0
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                val offset = y * SIDE + x
                val hole = x in 252 until 516 && y in 234 until 534
                if (hole) {
                    mask[offset] = 1f
                    masked++
                    continue
                }
                val r: Int
                val g: Int
                val b: Int
                if (name == "texture") {
                    val alternate = (x / 11 + y / 9) % 2 == 0
                    r = if (alternate) 143 else 128
                    g = if (alternate) 120 else 128
                    b = 128
                } else {
                    r = 65 + 120 * x / (SIDE - 1)
                    g = 80 + 105 * y / (SIDE - 1)
                    b = 100 + 70 * (x + y) / (2 * (SIDE - 1))
                }
                image[offset] = r / 127.5f - 1f
                image[AREA + offset] = g / 127.5f - 1f
                image[2 * AREA + offset] = b / 127.5f - 1f
            }
        }
        check(masked in 1 until AREA && mask.all { it == 0f || it == 1f } && image.all(Float::isFinite))
        val imageCopy = image.copyOf()
        val maskCopy = mask.copyOf()
        return FixedInput(name, image, mask, imageCopy, maskCopy, floatsSha(image), floatsSha(mask), masked)
    }

    private inner class FixedInput(
        val name: String,
        val image: FloatArray,
        val mask: FloatArray,
        private val imageCopy: FloatArray,
        private val maskCopy: FloatArray,
        private val imageSha: String,
        private val maskSha: String,
        private val maskedPixels: Int,
    ) {
        fun metadata() = JSONObject().put("id", name).put("width", SIDE).put("height", SIDE).put("channels", 3)
            .put("imageFloatCount", image.size).put("maskFloatCount", mask.size).put("maskedPixels", maskedPixels)
            .put("imageRawFloatBitsSha256", imageSha).put("maskRawFloatBitsSha256", maskSha)
            .put("maskBinary", true).put("maskedImageChannelsExactlyZero", true)
            .put("generation", "Fixed integer RGB pattern normalized to [-1,1]; no randomness")

        fun verify() {
            check(image.size == IMAGE_FLOATS && mask.size == AREA)
            check(sameRawBits(imageCopy, image) && sameRawBits(maskCopy, mask))
            check(floatsSha(image) == imageSha && floatsSha(mask) == maskSha)
        }
    }

    private fun statistics(values: FloatArray): JSONObject {
        var finite = 0
        var nan = 0
        var positiveInfinity = 0
        var negativeInfinity = 0
        var minimum = Double.POSITIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (value in values) {
            when {
                value.isNaN() -> nan++
                value == Float.POSITIVE_INFINITY -> positiveInfinity++
                value == Float.NEGATIVE_INFINITY -> negativeInfinity++
                else -> {
                    check(value.isFinite())
                    finite++
                    val number = value.toDouble()
                    minimum = min(minimum, number)
                    maximum = max(maximum, number)
                    sum += number
                }
            }
        }
        check(finite + nan + positiveInfinity + negativeInfinity == values.size)
        return JSONObject().put("count", values.size).put("finiteCount", finite).put("nanCount", nan)
            .put("positiveInfinityCount", positiveInfinity).put("negativeInfinityCount", negativeInfinity)
            .put("nonFiniteCount", nan + positiveInfinity + negativeInfinity)
            .put("finiteMinimum", if (finite > 0) minimum else JSONObject.NULL)
            .put("finiteMaximum", if (finite > 0) maximum else JSONObject.NULL)
            .put("finiteMean", if (finite > 0) sum / finite else JSONObject.NULL)
    }

    private fun difference(first: FloatArray, next: FloatArray): JSONObject {
        check(first.size == next.size)
        var changed = 0
        var finitePairs = 0
        var nonFinitePairs = 0
        var nonFiniteBitDifferences = 0
        var maximum = 0.0
        var sum = 0.0
        for (index in first.indices) {
            val left = first[index]
            val right = next[index]
            val different = left.toRawBits() != right.toRawBits()
            if (different) changed++
            if (left.isFinite() && right.isFinite()) {
                finitePairs++
                val delta = abs(left.toDouble() - right.toDouble())
                maximum = max(maximum, delta)
                sum += delta
            } else {
                nonFinitePairs++
                if (different) nonFiniteBitDifferences++
            }
        }
        return JSONObject().put("comparedFloatCount", first.size).put("changedRawFloatBitsCount", changed)
            .put("finitePairCount", finitePairs).put("nonFinitePairCount", nonFinitePairs)
            .put("nonFiniteRawBitDifferences", nonFiniteBitDifferences)
            .put("finitePairMaximumAbsoluteDifference", if (finitePairs > 0) maximum else JSONObject.NULL)
            .put("finitePairMeanAbsoluteDifference", if (finitePairs > 0) sum / finitePairs else JSONObject.NULL)
    }

    private fun sameRawBits(left: FloatArray, right: FloatArray): Boolean {
        if (left.size != right.size) return false
        for (index in left.indices) if (left[index].toRawBits() != right[index].toRawBits()) return false
        return true
    }

    private fun floatsSha(values: FloatArray): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var used = 0
        for (value in values) {
            val bits = value.toRawBits()
            buffer[used++] = bits.toByte()
            buffer[used++] = (bits ushr 8).toByte()
            buffer[used++] = (bits ushr 16).toByte()
            buffer[used++] = (bits ushr 24).toByte()
            if (used == buffer.size) {
                hash.update(buffer)
                used = 0
            }
        }
        hash.update(buffer, 0, used)
        return hex(hash.digest())
    }

    private fun modelPins(directory: File) = JSONArray(
        MODEL_PINS.map { pin ->
            val file = File(directory, pin.name)
            requirePlain(file, directory, directory = false)
            check(file.length() == pin.bytes && digest(file) == pin.sha256)
            JSONObject().put("name", pin.name).put("bytes", pin.bytes).put("sha256", pin.sha256)
        },
    )

    private fun requirePlain(file: File, parent: File, directory: Boolean) {
        check(file.canonicalFile == file.absoluteFile && file.parentFile == parent.canonicalFile)
        val status = Os.lstat(file.path)
        check(if (directory) OsConstants.S_ISDIR(status.st_mode) else OsConstants.S_ISREG(status.st_mode))
        if (!directory) check(status.st_nlink == 1L)
    }

    private fun writeReport(directory: File, report: JSONObject) {
        val file = File(directory, "result.json")
        if (file.exists()) requirePlain(file, directory, directory = false)
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
        requirePlain(file, directory, directory = false)
        check(file.length() == bytes.size.toLong() && digest(file) == sha(bytes))
    }

    private fun sendStatus(label: String, value: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "$label=$value") },
        )
    }

    private fun NcnnNetOptions.json() = JSONObject().put("numThreads", numThreads)
        .put("openmpBlocktime", openmpBlocktime).put("lightmode", lightmode)
        .put("useWinogradConvolution", useWinogradConvolution)
        .put("useWinograd23Convolution", useWinograd23Convolution)
        .put("useWinograd43Convolution", useWinograd43Convolution)
        .put("useWinograd63Convolution", useWinograd63Convolution)
        .put("useSgemmConvolution", useSgemmConvolution).put("usePackingLayout", usePackingLayout)
        .put("useFp16Packed", useFp16Packed).put("useFp16Storage", useFp16Storage)
        .put("useFp16Arithmetic", useFp16Arithmetic).put("useBf16Storage", useBf16Storage)
        .put("useBf16Packed", useBf16Packed).put("useInt8Inference", useInt8Inference)
        .put("useInt8Packed", useInt8Packed).put("useInt8Storage", useInt8Storage)
        .put("useInt8Arithmetic", useInt8Arithmetic).put("useLocalPoolAllocator", useLocalPoolAllocator)
        .put("useMappedModelLoading", useMappedModelLoading)
        .put("useA53A55OptimizedKernel", useA53A55OptimizedKernel).put("useVulkanCompute", useVulkanCompute)

    private fun digest(file: File): String = file.inputStream().use { input ->
        val length = file.length()
        check(length in 0..MAX_FILE_BYTES)
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var consumed = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            consumed += count
            check(consumed <= length)
            hash.update(buffer, 0, count)
        }
        check(consumed == length && file.length() == length)
        hex(hash.digest())
    }

    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private data class Mode(val name: String)
    private data class ModelPin(val name: String, val bytes: Long, val sha256: String)

    private companion object {
        const val PACKAGE = "app.yomitori.yakuyomi.audit"
        const val ROLE = "aot-determinism"
        const val SIDE = 768
        const val AREA = SIDE * SIDE
        const val IMAGE_FLOATS = 3 * AREA
        const val REPEATS = 3
        const val MAX_REPORT_BYTES = 256 * 1024
        const val MAX_FILE_BYTES = 192L * 1024 * 1024
        val UUID_REGEX = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val MODES = listOf(
            Mode("ordinaryDefault"),
            Mode("singleThreadDefaultPrecision"),
            Mode("singleThreadFp16StorageAndArithmeticDisabled"),
            Mode("defaultThreadCountFp16StorageTrueArithmeticFalse"),
        )
        val MODEL_PINS = listOf(
            ModelPin(
                "dbnet_detect.ncnn.param",
                13392,
                "9e6db2f8c6b0662ab00eb2100b3373d3c984a235eaac0e61c0b2a484ee1ff7b5",
            ),
            ModelPin(
                "dbnet_detect.ncnn.bin",
                153010556,
                "f57bdbede7764a534c56e88be0269602259a7fcd47e54e8b7d954fd0fcc55c3d",
            ),
            ModelPin(
                "ocr_48px_ctc.ncnn.param",
                18133,
                "e701cfc5df9d3c55c9fd0a36725499d01d45c18e32946271fc26e580cd9901bd",
            ),
            ModelPin(
                "ocr_48px_ctc_mixed.ncnn.param",
                18438,
                "32e298deca3acb8ba95897ca8cccbd028582ae42a697c3c05b4eaefe198309e3",
            ),
            ModelPin(
                "ocr_48px_ctc.ncnn.bin",
                83037664,
                "3e0a809441f5284871d18a3d757a7ec098c4b9ee64bdfd7ac776a84aa057c7a9",
            ),
            ModelPin(
                "mit_aot_fixed512.ncnn.param",
                33810,
                "f21ef860d21a6cdf60dfb1742c08d1c0d98837bceeb2ee3fe9c2dbbeee7d32b5",
            ),
            ModelPin(
                "mit_aot_fixed512.ncnn.bin",
                11366088,
                "a52db45eafc1dd2aa4ce9a339c711917fa98fefb31ce4506d4c95e8b5e3560b6",
            ),
        )
    }
}
