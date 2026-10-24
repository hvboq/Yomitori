package mihon.translation

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.NcnnDiagnosticNet
import li.joye.yakuyomi.engine.NcnnFlavor
import li.joye.yakuyomi.engine.NcnnLoadDiagnosticApi
import li.joye.yakuyomi.engine.NcnnLoadReport
import li.joye.yakuyomi.engine.NcnnLoadSnapshot
import li.joye.yakuyomi.engine.NcnnLoadStage
import li.joye.yakuyomi.engine.NcnnNetOptions
import li.joye.yakuyomi.engine.NcnnReleaseReport
import mihon.core.migration.Migrator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Production-config matched direct-backend experiments, not hooks in the Kotlin factory.
 * The load matrix never forwards. Separate tiny-net and actual DBNet raw-output comparisons check
 * ordinary creation against the diagnostic route; neither is a semantic quality benchmark.
 * No Activity, engine/manager factory, translation server, credentials, GC or trim is requested.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalEngineApi::class, NcnnLoadDiagnosticApi::class)
class NativeLoadBoundaryDiagnosticTest {
    @Test(timeout = 240_000)
    fun recordsMatchedBackendLoadsAndChecksOrdinaryCreationParity() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("modelLoadBoundary") == "true") { "Explicit model load diagnostic opt-in required" }
        val id = args.getString("modelLoadBoundaryRunId").orEmpty()
        check(UUID_REGEX.matches(id) && UUID.fromString(id).toString() == id) { "Canonical fresh run UUID required" }
        Executors.newSingleThreadExecutor { task -> Thread(task, "native-load-boundary") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    Migrator.await()
                    runDiagnostic(id)
                }
            }
    }

    private fun runDiagnostic(id: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Actual ARM64 process required" }
        val cache = context.cacheDir.canonicalFile
        check(cache.isDirectory)
        val output = File(cache, "native-load-boundary-$id")
        check(!output.exists() && output.mkdir()) { "Never overwrite or reuse a diagnostic run" }
        requirePlain(output, cache, directory = true)
        val report = JSONObject().put("schemaVersion", 1).put("runId", id).put("passed", false)
            .put("finalized", false).put("targetPackage", context.packageName)
            .put("sdk", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("method", "production-config matched direct backend")
            .put("actualKotlinFactoryInvoked", false).put("productionConstructorsInstrumented", false)
            .put("productionOptionsPromoted", false).put("realModelForwardCallsByTest", 0)
            .put("realModelLoadMatrixForwardCallsByTest", 0)
            .put("genericTinyExtractCallsByTest", 0).put("serverCallsByTest", 0)
            .put("serviceOrManagerFactoriesCalledByTest", 0).put("preferencesWrittenByTest", 0)
            .put("preferencesReadOnlyForIdleGuard", true).put("modelCopiesOrDownloadsByTest", 0)
            .put("applicationMigrationAwaitedBeforePreflight", true)
            .put("reflectionAndApplicationInitializationOutsideNativeBrackets", true)
            .put("sharedSimpleOmpBootstrapPrecedesNativeBrackets", true)
            .put("indirectBootstrapReluForwardPossible", true).put("allNativeForwardsClaimedZero", false)
            .put("samplingHasNoJavaCallbackLogOrFileIo", true).put("samplerIsProcessAllocatorNotRssPss", true)
            .put("allocatorObservationsAreNonAtomicIncompleteAndNotExclusiveModelOwnership", true)
            .put("overallPeakAndColdPerformanceMeasured", false).put("sameProcessFixedOrder", true)
            .put("cpuAffinityChangedByTest", false).put("gcOrTrimRequestedByTest", false)
            .put("automaticRetries", 0).put("allThreeRealModelOutputsOrSemanticQualityProven", false)
        val realRows = JSONArray().also { report.put("realModelLoads", it) }
        val comparisons = JSONArray().also { report.put("winogradComparisons", it) }
        val parityRows = JSONArray().also { report.put("tinyCreationParity", it) }
        val failures = JSONArray().also { report.put("invalidLoadCases", it) }
        val temporary = linkedMapOf<String, String>()
        var stage = "PREFLIGHT"
        var primaryFailure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        var completedRealCreates = 0
        var completedRealCloses = 0
        var tinyExtractCalls = 0
        var rawDetectorCalls = 0
        try {
            report.put("idleBefore", requireIdleWithoutFactories(context))
            val models = File(context.filesDir.canonicalFile, "models")
            requirePlain(models, context.filesDir.canonicalFile, directory = true)
            val modelPinsBefore = modelFingerprints(models)
            report.put("modelPinsBefore", modelPinsBefore)
            val alphabet = context.assets.open("models/alphabet-all-v5.txt").use { input ->
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
            check(sha(alphabet) == DICTIONARY_SHA)
            val alphabetRows = alphabet.count { it == 10.toByte() } +
                if (alphabet.isNotEmpty() && alphabet.last() != 10.toByte()) 1 else 0
            check(alphabetRows == 19_264)
            report.put("alphabetSha256", sha(alphabet)).put("alphabetRows", 19_264)
            check(NcnnBackend.available) { "NCNN backend must be available" }
            val specifications = modelSpecifications(models)
            report.put("ocrMixedSelected", specifications.single { it.role == "ocr" }.param.name.contains("mixed"))
            for (spec in specifications) {
                var baseline: NcnnNetOptions? = null
                var baselineConfigured: NcnnNetOptions? = null
                var baselineAllocation = 0L
                for (disableWinograd in listOf(false, true)) {
                    stage = "LOAD_${spec.role.uppercase()}_${if (disableWinograd) "WINOGRAD_OFF" else "DEFAULT"}"
                    sendStage(id, stage)
                    requireIdleWithoutFactories(context)
                    val started = System.nanoTime()
                    val owner = create(spec, disableWinograd)
                    val finished = System.nanoTime()
                    var row: JSONObject? = null
                    var validated = false
                    try {
                        check(owner.isOpen && owner.loadReport.loaded) { "Matched real-model load failed" }
                        check(owner.loadReport.paramResult == 0 && owner.loadReport.modelResult == 0)
                        validateLoad(owner.loadReport, spec, disableWinograd, started, finished, loaded = true)
                        val options = owner.withHandle { handle -> NcnnBackend.readNetOptionsDiagnostic(handle) }
                        check(options == owner.loadReport.snapshots.last().options) { "Actual live options changed" }
                        val first = owner.loadReport.snapshots.first()
                        val last = owner.loadReport.snapshots.last()
                        val delta = last.allocatedBytes - first.allocatedBytes
                        completedRealCreates++
                        // Close before JSON bookkeeping and further file reads can perturb its bracket.
                        val closeStart = System.nanoTime()
                        val released = checkNotNull(owner.closeWithReport()) { "Real model was not released" }
                        val closeEnd = System.nanoTime()
                        validateRelease(released, owner.loadReport, closeStart, closeEnd)
                        completedRealCloses++
                        check(!owner.isOpen)
                        check(owner.closeWithReport() == null) { "Second close must not issue another native release" }
                        assertLoanRejected(owner)
                        row = JSONObject().put("role", spec.role).put("condition", condition(disableWinograd))
                            .put("paramName", spec.param.name)
                            .put("paramSha256", MODEL_PINS.single { it.name == spec.param.name }.sha256)
                            .put("binName", spec.bin.name)
                            .put("binSha256", MODEL_PINS.single { it.name == spec.bin.name }.sha256)
                            .put("kotlinStartedAtNs", started).put("kotlinFinishedAtNs", finished)
                            .put("nativeLoad", owner.loadReport.json()).put("actualLiveOptions", options.json())
                            .put("allocatedDeltaAfterModelVsBeforeNet", delta)
                            .put("maxSimultaneousRealModelNetsByTest", 1).put("forwarded", false)
                            .put("nativeRelease", released.json()).put("closed", true)
                            .put("kotlinCloseStartedAtNs", closeStart).put("kotlinCloseFinishedAtNs", closeEnd)
                        realRows.put(row)
                        if (!disableWinograd) {
                            baseline = options
                            baselineConfigured = owner.loadReport.snapshots[2].options
                            baselineAllocation = delta
                        } else {
                            val reference = checkNotNull(baseline)
                            val configured = checkNotNull(owner.loadReport.snapshots[2].options)
                            // This one default may follow the current CPU after a thread migration.
                            // Keep it visible rather than pinning affinity or inventing a perfect A/B.
                            check(
                                checkNotNull(baselineConfigured).copy(
                                    useWinogradConvolution = false,
                                    useA53A55OptimizedKernel = configured.useA53A55OptimizedKernel,
                                ) == configured,
                            ) { "A non-dynamic configured option changed beyond the requested switch" }
                            comparisons.put(
                                JSONObject().put("role", spec.role)
                                    .put("defaultAllocatedDelta", baselineAllocation).put("offAllocatedDelta", delta)
                                    .put("offMinusDefaultAllocatedDelta", delta - baselineAllocation)
                                    .put("defaultWinogradWasEnabled", reference.useWinogradConvolution)
                                    .put(
                                        "otherPostModelOptionsExactlyEqual",
                                        reference.copy(useWinogradConvolution = false) == options,
                                    )
                                    .put("postModelChangedOptionFields", differentOptionFields(reference, options))
                                    .put("onlyRequestedConfiguredChangeExceptDynamicCpuBit", true)
                                    .put(
                                        "dynamicA53A55OptionMatched",
                                        reference.useA53A55OptimizedKernel == options.useA53A55OptimizedKernel,
                                    )
                                    .put("singleCauseOrProductionImprovementClaimed", false),
                            )
                        }
                        requireIdleWithoutFactories(context)
                        validated = true
                    } finally {
                        owner.close()
                        if (row == null) {
                            // Preserve a valid native report even when a later fixture assertion fails.
                            realRows.put(
                                JSONObject().put("role", spec.role).put("condition", condition(disableWinograd))
                                    .put("nativeLoad", owner.loadReport.json()).put("fixtureValidationCompleted", false)
                                    .put("ownerClosedAfterFinally", !owner.isOpen),
                            )
                        } else {
                            row.put("ownerClosedAfterFinally", !owner.isOpen)
                                .put("fixtureValidationCompleted", validated)
                        }
                    }
                }
            }
            check(completedRealCreates == 6 && completedRealCloses == 6)
            stage = "TINY_PARITY"
            sendStage(id, stage)
            val tinyParam = ownedTemporary(output, "tiny.param", TINY_PARAM.toByteArray(), temporary)
            val tinyBin = ownedTemporary(output, "tiny.bin", byteArrayOf(), temporary)
            for (useEx in listOf(false, true)) {
                requireIdleWithoutFactories(context)
                val spec = ModelSpec("tiny", tinyParam, tinyBin, useEx, if (useEx) 1 else 0, !useEx, !useEx)
                var plainHandle = 0L
                val ordinaryOptions: NcnnNetOptions
                val ordinaryOutput: FloatArray
                try {
                    plainHandle = if (useEx) {
                        NcnnBackend.createNetEx(tinyParam.path, tinyBin.path, 1, false, false)
                    } else {
                        NcnnBackend.createNet(tinyParam.path, tinyBin.path)
                    }
                    check(plainHandle != 0L) { "Ordinary tiny load failed" }
                    ordinaryOptions = NcnnBackend.readNetOptionsDiagnostic(plainHandle)
                    ordinaryOutput = tinyForward(plainHandle)
                    tinyExtractCalls++
                } finally {
                    if (plainHandle != 0L) NcnnBackend.releaseNet(plainHandle)
                }
                val started = System.nanoTime()
                val owner = create(spec, disableWinograd = false)
                val finished = System.nanoTime()
                try {
                    check(owner.isOpen && owner.loadReport.loaded)
                    validateLoad(owner.loadReport, spec, false, started, finished, loaded = true)
                    val actual = owner.withHandle { NcnnBackend.readNetOptionsDiagnostic(it) }
                    check(actual == owner.loadReport.snapshots.last().options)
                    check(
                        ordinaryOptions.copy(useA53A55OptimizedKernel = actual.useA53A55OptimizedKernel) == actual,
                    ) { "Ordinary and diagnostic creation options differ" }
                    if (useEx) {
                        check(actual.numThreads == 1 && !actual.useFp16Packed && !actual.useFp16Storage)
                        check(!actual.useFp16Arithmetic && !actual.useBf16Storage)
                    }
                    val diagnosticOutput = owner.withHandle(::tinyForward)
                    tinyExtractCalls++
                    check(sameBits(ordinaryOutput, diagnosticOutput)) { "Tiny creation routes changed actual output" }
                    var nestedEntered = false
                    owner.withHandle {
                        expectStateFailure { owner.withHandle { nestedEntered = true } }
                        check(!nestedEntered)
                        expectStateFailure { owner.close() }
                        expectStateFailure { owner.closeWithReport() }
                        check(owner.isOpen)
                    }
                    val closeStart = System.nanoTime()
                    val released = checkNotNull(owner.closeWithReport())
                    val closeEnd = System.nanoTime()
                    validateRelease(released, owner.loadReport, closeStart, closeEnd)
                    check(!owner.isOpen && owner.closeWithReport() == null)
                    assertLoanRejected(owner)
                    parityRows.put(
                        JSONObject().put("mode", if (useEx) "extendedThreads1PrecisionFalse" else "ordinaryDefaults")
                            .put("ordinaryOptions", ordinaryOptions.json()).put("diagnosticOptions", actual.json())
                            .put("otherOptionsExactlyEqual", ordinaryOptions == actual)
                            .put("inputFloatCount", TINY_INPUT.size).put("inputBitsSha256", floatSha(TINY_INPUT))
                            .put("outputFloatCount", ordinaryOutput.size)
                            .put("outputBitsSha256", floatSha(ordinaryOutput))
                            .put("diagnosticOutputBitsSha256", floatSha(diagnosticOutput))
                            .put("exactOutputBitsEqual", true).put("reluExpectedBitsMatched", true)
                            .put("ordinaryCreationLoadReports", 0).put("diagnosticLoad", owner.loadReport.json())
                            .put("nativeRelease", released.json()).put("reentrantLoanAndCloseRejected", true)
                            .put("postCloseLoanRejected", true).put("secondNativeCloseNotRequested", true)
                            .put("realModelQualityParityClaimed", false),
                    )
                } finally {
                    owner.close()
                }
            }
            check(tinyExtractCalls == 4)
            stage = "DETECTOR_RAW_PARITY"
            sendStage(id, stage)
            requireIdleWithoutFactories(context)
            val detectorSpec = specifications.single { it.role == "detector" }
            val detectorInput = FloatArray(3 * DETECTOR_AREA) { index ->
                val x = index % DETECTOR_SIDE
                val y = index / DETECTOR_SIDE % DETECTOR_SIDE
                val channel = index / DETECTOR_AREA
                ((x + y + channel * 13) % 31) / 16f - 1f
            }
            val inputHash = floatSha(detectorInput)
            val ordinaryDetectorOptions: NcnnNetOptions
            val ordinaryDetectorOutput: DetectorOutput
            var plainDetector = 0L
            try {
                plainDetector = NcnnBackend.createNet(detectorSpec.param.path, detectorSpec.bin.path)
                check(plainDetector != 0L) { "Ordinary detector reference did not load" }
                ordinaryDetectorOptions = NcnnBackend.readNetOptionsDiagnostic(plainDetector)
                ordinaryDetectorOutput = detectorForward(plainDetector, detectorInput)
                rawDetectorCalls++
            } finally {
                if (plainDetector != 0L) NcnnBackend.releaseNet(plainDetector)
            }
            check(floatSha(detectorInput) == inputHash) { "Reference detector changed borrowed input" }
            val detectorStarted = System.nanoTime()
            val detectorOwner = create(detectorSpec, disableWinograd = false)
            val detectorFinished = System.nanoTime()
            try {
                check(detectorOwner.isOpen && detectorOwner.loadReport.loaded)
                validateLoad(
                    detectorOwner.loadReport,
                    detectorSpec,
                    false,
                    detectorStarted,
                    detectorFinished,
                    loaded = true,
                )
                val diagnosticOptions = detectorOwner.withHandle { NcnnBackend.readNetOptionsDiagnostic(it) }
                check(diagnosticOptions == detectorOwner.loadReport.snapshots.last().options)
                val diagnosticOutput = detectorOwner.withHandle { detectorForward(it, detectorInput) }
                rawDetectorCalls++
                check(floatSha(detectorInput) == inputHash) { "Diagnostic detector changed borrowed input" }
                val equal = ordinaryDetectorOutput.rc == diagnosticOutput.rc &&
                    sameBits(ordinaryDetectorOutput.db, diagnosticOutput.db) &&
                    sameBits(ordinaryDetectorOutput.mask, diagnosticOutput.mask)
                val result = JSONObject().put("inputWidth", DETECTOR_SIDE).put("inputHeight", DETECTOR_SIDE)
                    .put("inputChannels", 3).put("inputFloatCount", detectorInput.size)
                    .put("inputBitsSha256", inputHash)
                    .put("ordinaryOptions", ordinaryDetectorOptions.json())
                    .put("diagnosticOptions", diagnosticOptions.json())
                    .put("actualOptionFieldsChanged", differentOptionFields(ordinaryDetectorOptions, diagnosticOptions))
                    .put("ordinaryMaskShapeCode", ordinaryDetectorOutput.rc)
                    .put("diagnosticMaskShapeCode", diagnosticOutput.rc)
                    .put("dbFloatCount", ordinaryDetectorOutput.db.size)
                    .put("maskCapacity", ordinaryDetectorOutput.mask.size)
                    .put("ordinaryDbBitsSha256", floatSha(ordinaryDetectorOutput.db))
                    .put("diagnosticDbBitsSha256", floatSha(diagnosticOutput.db))
                    .put("ordinaryMaskBitsSha256", floatSha(ordinaryDetectorOutput.mask))
                    .put("diagnosticMaskBitsSha256", floatSha(diagnosticOutput.mask))
                    .put("exactRawOutputBitsEqual", equal).put("ordinaryCreationLoadReports", 0)
                    .put("diagnosticLoad", detectorOwner.loadReport.json())
                    .put("actualModel", "DBNet").put("diagnosticWinogradDisabled", false)
                    .put("semanticOcrOrTranslationQualityProven", false).put("winogradOffOutputParityProven", false)
                    .put("observerEffectWhenOptionsDifferAttributable", false)
                report.put("actualDetectorRawParity", result)
                val closeStarted = System.nanoTime()
                val release = checkNotNull(detectorOwner.closeWithReport())
                val closeFinished = System.nanoTime()
                validateRelease(release, detectorOwner.loadReport, closeStarted, closeFinished)
                result.put("nativeRelease", release.json()).put("closed", !detectorOwner.isOpen)
                assertLoanRejected(detectorOwner)
                check(equal) { "Actual detector raw output parity failed; preserve option and output evidence" }
            } finally {
                detectorOwner.close()
            }
            stage = "FAILED_LOADS"
            sendStage(id, stage)
            val corrupt = ownedTemporary(output, "invalid.param", "invalid neutral param\n".toByteArray(), temporary)
            val absentParam = File(output, "absent.param").also { check(!it.exists()) }
            val absentBin = File(output, "absent.bin").also { check(!it.exists()) }
            for ((label, param, bin) in listOf(
                Triple("missingFiles", absentParam, absentBin),
                Triple("invalidParam", corrupt, tinyBin),
            )) {
                val spec = ModelSpec("invalid", param, bin)
                val started = System.nanoTime()
                val owner = create(spec, disableWinograd = false)
                val finished = System.nanoTime()
                try {
                    check(!owner.isOpen && !owner.loadReport.loaded)
                    check(owner.loadReport.paramResult != 0 || owner.loadReport.modelResult != 0)
                    validateLoad(owner.loadReport, spec, false, started, finished, loaded = false)
                    assertLoanRejected(owner)
                    check(owner.closeWithReport() == null)
                    failures.put(
                        JSONObject().put("case", label).put("nativeLoad", owner.loadReport.json())
                            .put("returnedHandleNonzero", false).put("loanRejected", true)
                            .put("failedNetDeletedBeforeReturn", true),
                    )
                } finally {
                    owner.close()
                }
            }
            stage = "POSTFLIGHT"
            report.put("idleAfter", requireIdleWithoutFactories(context))
            val modelPinsAfter = modelFingerprints(models)
            check(modelPinsBefore.toString() == modelPinsAfter.toString()) { "Existing model fingerprints changed" }
            report.put("modelPinsAfter", modelPinsAfter).put("models7Unchanged", true)
                .put("completedRealModelCreates", completedRealCreates)
                .put("completedRealModelCloses", completedRealCloses)
                .put("genericTinyExtractCallsByTest", tinyExtractCalls)
                .put("realModelForwardCallsByTest", rawDetectorCalls)
                .put("passed", true)
        } catch (failure: Throwable) {
            primaryFailure = failure
            report.put("failedStage", stage).put("failureClass", failure.javaClass.name).put("passed", false)
        } finally {
            for ((name, hash) in temporary) {
                try {
                    val file = File(output, name)
                    requirePlain(file, output, directory = false)
                    check(digest(file) == hash) { "Owned temporary model changed" }
                    check(file.delete()) { "Cannot remove owned temporary model" }
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            if (cleanupFailures.isNotEmpty()) {
                report.put("cleanupFailureClasses", JSONArray(cleanupFailures.map { it.javaClass.name }))
                    .put("passed", false)
            }
            report.put("stage", "FINAL").put("finalized", true)
                .put("completedRealModelCreates", completedRealCreates)
                .put("completedRealModelCloses", completedRealCloses)
                .put("genericTinyExtractCallsByTest", tinyExtractCalls)
                .put("realModelForwardCallsByTest", rawDetectorCalls)
                .put("temporaryFixtureFilesRemoved", cleanupFailures.isEmpty())
            writeFinal(output, report)
            val file = File(output, "result.json")
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "NATIVE_LOAD_BOUNDARY=" + JSONObject().put("runId", id).put("bytes", file.length())
                            .put("sha256", digest(file)).put("passed", report.getBoolean("passed")),
                    )
                },
            )
        }
        primaryFailure?.let { error ->
            cleanupFailures.forEach(error::addSuppressed)
            throw error
        }
        check(cleanupFailures.isEmpty()) { "Owned temporary cleanup failed" }
        check(report.getBoolean("passed"))
    }

    private fun create(spec: ModelSpec, disableWinograd: Boolean) = NcnnBackend.createNetLoadDiagnostic(
        spec.param.path,
        spec.bin.path,
        spec.threads,
        spec.fp16Storage,
        spec.fp16Arithmetic,
        spec.extended,
        disableWinograd,
    )

    private fun validateLoad(
        report: NcnnLoadReport,
        spec: ModelSpec,
        disableWinograd: Boolean,
        start: Long,
        end: Long,
        loaded: Boolean,
    ) {
        check(report.headerVersionNumber == report.linkedVersionNumber && report.headerVersionNumber == NCNN_VERSION)
        check(report.loaded == loaded && report.usedExtendedOptions == spec.extended)
        check(report.winogradDisabled == disableWinograd)
        val stages = listOf(
            NcnnLoadStage.BEFORE_NEW_NET,
            NcnnLoadStage.AFTER_NEW_NET,
            NcnnLoadStage.AFTER_CONFIG,
            NcnnLoadStage.AFTER_LOAD_PARAM,
            NcnnLoadStage.AFTER_LOAD_MODEL,
        ) + if (loaded) emptyList() else listOf(NcnnLoadStage.AFTER_FAILED_DELETE)
        validateSnapshots(report.snapshots, stages, start, end)
        val created = checkNotNull(report.snapshots[1].options)
        val configured = checkNotNull(report.snapshots[2].options)
        val expected = created.copy(
            numThreads = if (spec.extended && spec.threads > 0) spec.threads else created.numThreads,
            useFp16Packed = if (spec.extended && !spec.fp16Storage) false else created.useFp16Packed,
            useFp16Storage = if (spec.extended && !spec.fp16Storage) false else created.useFp16Storage,
            useBf16Storage = if (spec.extended && !spec.fp16Storage) false else created.useBf16Storage,
            useFp16Arithmetic = if (spec.extended && !spec.fp16Arithmetic) false else created.useFp16Arithmetic,
            useLocalPoolAllocator = false,
            useWinogradConvolution = if (disableWinograd) false else created.useWinogradConvolution,
        )
        check(configured == expected) { "Diagnostic configuration diverged from production rules" }
    }

    private fun validateRelease(report: NcnnReleaseReport, load: NcnnLoadReport, start: Long, end: Long) {
        check(
            report.headerVersionNumber == load.headerVersionNumber &&
                report.linkedVersionNumber == load.linkedVersionNumber,
        )
        validateSnapshots(report.snapshots, listOf(NcnnLoadStage.BEFORE_DELETE, NcnnLoadStage.AFTER_DELETE), start, end)
        check(report.snapshots.first().options == load.snapshots.last().options)
        check(report.snapshots.last().options == null)
    }

    private fun validateSnapshots(
        snapshots: List<NcnnLoadSnapshot>,
        stages: List<NcnnLoadStage>,
        start: Long,
        end: Long,
    ) {
        check(snapshots.map { it.stage } == stages)
        var previous = start
        for (snapshot in snapshots) {
            check(snapshot.startedAtNs >= previous && snapshot.finishedAtNs in snapshot.startedAtNs..end)
            check(snapshot.sampleOverheadNs >= 0)
            check(
                listOf(snapshot.allocatedBytes, snapshot.arenaBytes, snapshot.mappedBytes, snapshot.freeBytes)
                    .all { it >= 0 },
            )
            previous = snapshot.finishedAtNs
        }
    }

    private fun modelSpecifications(directory: File): List<ModelSpec> {
        val models = checkNotNull(ModelSet.resolve(MODEL_PINS.map { it.name to File(directory, it.name).path }))
        val config = EngineConfig()
        val base = File(models.ocr)
        val mixed = File(directory, base.name.removeSuffix(".ncnn.param") + "_mixed.ncnn.param")
        val useMixed = config.ocr.ncnnMixed && config.ocr.ncnnFp16Storage &&
            NcnnBackend.cpuSupportsFp16 && mixed.exists()
        return listOf(
            ModelSpec("detector", File(checkNotNull(models.detectorNcnn)), File(directory, "dbnet_detect.ncnn.bin")),
            ModelSpec(
                "ocr",
                if (useMixed) mixed else base,
                File(directory, "ocr_48px_ctc.ncnn.bin"),
                true,
                if (config.ocr.concurrent) 1 else 4,
                config.ocr.ncnnFp16Storage,
                config.ocr.ncnnFp16Arith,
            ),
            ModelSpec(
                "inpainter",
                File(checkNotNull(models.aotInpainterNcnn)),
                File(directory, "mit_aot_fixed512.ncnn.bin"),
                extended = true,
                threads = 1,
            ),
        )
    }

    private fun requireIdleWithoutFactories(context: Context): JSONObject =
        NativeDiagnosticIdleGuard.requireIdle(context)

    private fun tinyForward(handle: Long): FloatArray {
        val before = TINY_INPUT.copyOf()
        val output = FloatArray(TINY_INPUT.size)
        check(
            NcnnBackend.extract(handle, TINY_INPUT, 4, 3, 2, arrayOf("out0"), arrayOf(output), NcnnFlavor.DEFAULT) == 0,
        )
        check(sameBits(before, TINY_INPUT)) { "Generic forward changed its borrowed input" }
        check(sameBits(output, FloatArray(TINY_INPUT.size) { TINY_INPUT[it].coerceAtLeast(0f) }))
        return output
    }

    private fun detectorForward(handle: Long, input: FloatArray): DetectorOutput {
        val db = FloatArray(2 * DETECTOR_AREA)
        val mask = FloatArray(DETECTOR_AREA)
        val rc = NcnnBackend.detectDbnet(handle, input, DETECTOR_SIDE, DETECTOR_SIDE, db, mask)
        check(rc > 0 && rc / 10_000 in 1..DETECTOR_SIDE && rc % 10_000 in 1..DETECTOR_SIDE)
        check(db.all { it.isFinite() } && mask.all { it.isFinite() }) { "Detector returned non-finite raw outputs" }
        return DetectorOutput(rc, db, mask)
    }

    private fun assertLoanRejected(owner: NcnnDiagnosticNet) {
        var entered = false
        expectStateFailure { owner.withHandle { entered = true } }
        check(!entered && !owner.isOpen) { "A closed handle was exposed" }
    }

    private fun expectStateFailure(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalStateException) {
            failed = true
        }
        check(failed) { "An invalid handle lifetime operation was accepted" }
    }

    private fun ownedTemporary(
        directory: File,
        name: String,
        bytes: ByteArray,
        expected: MutableMap<String, String>,
    ): File {
        check(name in TEMPORARY_NAMES && bytes.size <= 4096)
        val file = File(directory, name)
        check(!file.exists() && file.createNewFile())
        expected[name] = sha(bytes)
        file.outputStream().use { it.write(bytes) }
        requirePlain(file, directory, directory = false)
        check(digest(file) == expected[name])
        return file
    }

    private fun modelFingerprints(directory: File): JSONArray = JSONArray(
        MODEL_PINS.map { pin ->
            val file = File(directory, pin.name)
            requirePlain(file, directory, directory = false)
            check(file.length() == pin.bytes && digest(file) == pin.sha256) { "Existing model pin mismatch" }
            JSONObject().put("name", pin.name).put("bytes", pin.bytes).put("sha256", pin.sha256)
        },
    )

    private fun requirePlain(file: File, parent: File, directory: Boolean) {
        check(file.canonicalFile == file.absoluteFile && file.parentFile == parent.canonicalFile) {
            "Noncanonical owned path"
        }
        val status = Os.lstat(file.path)
        check(if (directory) OsConstants.S_ISDIR(status.st_mode) else OsConstants.S_ISREG(status.st_mode))
        if (!directory) check(status.st_nlink == 1L)
    }

    private fun writeFinal(directory: File, report: JSONObject) {
        val file = File(directory, "result.json")
        check(!file.exists() && !File(directory, "result.json.bak").exists())
        check(directory.listFiles().orEmpty().isEmpty()) { "Temporary files remain; preserve the evidence directory" }
        val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..MAX_JSON_BYTES)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
        requirePlain(file, directory, directory = false)
        check(file.length() == bytes.size.toLong() && digest(file) == sha(bytes))
        check(directory.listFiles().orEmpty().map { it.name } == listOf("result.json"))
    }

    private fun sendStage(id: String, stage: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "NATIVE_LOAD_BOUNDARY_STAGE=" + JSONObject().put("runId", id).put("stage", stage))
            },
        )
    }

    private fun NcnnLoadReport.json(): JSONObject {
        val changes = snapshots.zipWithNext().mapNotNull { (before, after) ->
            val left = before.options ?: return@mapNotNull null
            val right = after.options ?: return@mapNotNull null
            JSONObject().put("beforeStage", before.stage.name).put("afterStage", after.stage.name)
                .put("changedOptionFields", differentOptionFields(left, right))
        }
        return JSONObject().put("headerVersionNumber", headerVersionNumber)
            .put("linkedVersionNumber", linkedVersionNumber)
            .put("paramResult", paramResult).put("modelResult", modelResult)
            .put("usedExtendedOptions", usedExtendedOptions)
            .put("winogradDisabled", winogradDisabled).put("loaded", loaded)
            .put("snapshots", JSONArray(snapshots.map { it.json() })).put("optionTransitions", JSONArray(changes))
    }

    private fun NcnnReleaseReport.json() = JSONObject().put("headerVersionNumber", headerVersionNumber)
        .put("linkedVersionNumber", linkedVersionNumber).put("snapshots", JSONArray(snapshots.map { it.json() }))

    private fun NcnnLoadSnapshot.json() = JSONObject().put("stage", stage.name).put("startedAtNs", startedAtNs)
        .put("finishedAtNs", finishedAtNs).put("sampleOverheadNs", sampleOverheadNs)
        .put("allocatedBytes", allocatedBytes).put("arenaBytes", arenaBytes).put("mappedBytes", mappedBytes)
        .put("freeBytes", freeBytes).put("options", options?.json() ?: JSONObject.NULL)

    private fun NcnnNetOptions.json() = JSONObject().put("numThreads", numThreads)
        .put("openmpBlocktime", openmpBlocktime)
        .put("lightmode", lightmode).put("useWinogradConvolution", useWinogradConvolution)
        .put("useWinograd23Convolution", useWinograd23Convolution)
        .put("useWinograd43Convolution", useWinograd43Convolution)
        .put("useWinograd63Convolution", useWinograd63Convolution).put("useSgemmConvolution", useSgemmConvolution)
        .put("usePackingLayout", usePackingLayout).put("useFp16Packed", useFp16Packed)
        .put("useFp16Storage", useFp16Storage)
        .put("useFp16Arithmetic", useFp16Arithmetic).put("useBf16Storage", useBf16Storage)
        .put("useBf16Packed", useBf16Packed)
        .put("useInt8Inference", useInt8Inference).put("useInt8Packed", useInt8Packed)
        .put("useInt8Storage", useInt8Storage)
        .put("useInt8Arithmetic", useInt8Arithmetic).put("useLocalPoolAllocator", useLocalPoolAllocator)
        .put("useMappedModelLoading", useMappedModelLoading).put("useA53A55OptimizedKernel", useA53A55OptimizedKernel)
        .put("useVulkanCompute", useVulkanCompute)

    private fun differentOptionFields(left: NcnnNetOptions, right: NcnnNetOptions): JSONArray {
        val before = left.json()
        val after = right.json()
        return JSONArray(before.keys().asSequence().filter { before.get(it) != after.get(it) }.toList().sorted())
    }

    private fun digest(file: File): String = file.inputStream().use { input ->
        val expectedBytes = file.length()
        check(expectedBytes in 0..MAX_FILE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            check(bytes <= expectedBytes)
            digest.update(buffer, 0, count)
        }
        check(bytes == expectedBytes && file.length() == expectedBytes) { "Fingerprint input size changed" }
        hex(digest.digest())
    }

    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun floatSha(values: FloatArray) = sha(
        ByteBuffer.allocate(values.size * 4).apply {
            values.forEach { putInt(it.toRawBits()) }
        }.array(),
    )
    private fun sameBits(left: FloatArray, right: FloatArray) =
        left.size == right.size && left.indices.all { left[it].toRawBits() == right[it].toRawBits() }
    private fun condition(disabled: Boolean) = if (disabled) "winogradOff" else "productionDefaults"

    private data class ModelSpec(
        val role: String,
        val param: File,
        val bin: File,
        val extended: Boolean = false,
        val threads: Int = 0,
        val fp16Storage: Boolean = true,
        val fp16Arithmetic: Boolean = true,
    )

    private data class ModelPin(val name: String, val bytes: Long, val sha256: String)
    private data class DetectorOutput(val rc: Int, val db: FloatArray, val mask: FloatArray)

    private companion object {
        val UUID_REGEX = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        const val NCNN_VERSION = 20261002
        const val DETECTOR_SIDE = 256
        const val DETECTOR_AREA = DETECTOR_SIDE * DETECTOR_SIDE
        const val MAX_JSON_BYTES = 256 * 1024
        const val MAX_FILE_BYTES = 192L * 1024 * 1024
        const val MAX_DICTIONARY_BYTES = 512 * 1024
        const val DICTIONARY_SHA = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33"
        val TEMPORARY_NAMES = setOf("tiny.param", "tiny.bin", "invalid.param")
        const val TINY_PARAM = "7767517\n2 2\nInput input 0 1 in0\nReLU relu 1 1 in0 out0 0=0\n"
        val TINY_INPUT = FloatArray(24) { ((it % 9) - 4) / 4f }
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
