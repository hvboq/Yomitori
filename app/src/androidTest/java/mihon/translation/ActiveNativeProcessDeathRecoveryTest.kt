package mihon.translation

import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Xml
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStore
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.RegionState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt-in isolated audit; the host owns process-death and automatic-recovery observation.
 * Real singleton/public queue/native OCR. Host holds 002 before upstream dispatch, kills A,
 * then observes normal-app automatic startup completion BEFORE B instrumentation is started.
 * B verifies/cleans only; no manual resume/enqueue/start. C is idempotent owned cleanup.
 * No fake/native substitution, in-process server, credential decryption/extraction or model copying.
 * The custom credential backing is tested only for absence/literal empty; its slot is never written.
 */
@OptIn(InternalEngineApi::class)
@RunWith(AndroidJUnit4::class)
class ActiveNativeProcessDeathRecoveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test(timeout = 20_000)
    fun inspectNativeModelContext() {
        gate()
        val target = context
        val models = File(target.filesDir, "models")
        val rows = JSONArray(
            MODEL_NAMES.map { name ->
                val file = File(models, name)
                JSONObject().put("name", name).put("absolutePath", file.absolutePath)
                    .put("canonicalPath", file.canonicalPath).put("isFile", file.isFile)
                    .put("bytes", file.length()).put("absoluteEqualsCanonical", file.absoluteFile == file.canonicalFile)
                    .put("canonicalParentMatchesModels", file.canonicalFile.parentFile == models.canonicalFile)
            },
        )
        status(
            JSONObject().put("phase", "MODEL_CONTEXT_PROBE").put("uuid", uuid())
                .put("targetPackage", target.packageName).put("targetDataDir", target.applicationInfo.dataDir)
                .put(
                    "targetFilesDir",
                    target.filesDir.absolutePath,
                ).put("targetFilesDirCanonical", target.filesDir.canonicalPath)
                .put("applicationFilesDir", target.applicationContext.filesDir.absolutePath)
                .put("models", rows).put("modelContentsOrCredentialsRead", false)
                .put("nativeInferenceOrQueueStarted", false),
        )
    }

    @Test(timeout = 600_000)
    fun prepareActiveNativeAndWaitForHostKill(): Unit = runBlocking {
        gate()
        val harness = Harness(uuid())
        harness.requireInitiallyIdle()
        val inputs = harness.readInputs()
        check(!harness.output.exists() && !harness.book.exists())
        check(harness.output.mkdir())
        val ledger = harness.initialLedger(inputs)
        harness.writeLedger(ledger) // Durable rollback before the first preference/DB write.
        var armed = false
        try {
            harness.closeMasterGates()
            harness.prepareOwnedStorage(ledger)
            harness.configureRealEngine(ledger)
            check(harness.book.mkdir() && harness.chapterDirectory.mkdir())
            harness.copyInputs(inputs)
            ledger.put("sourceFiles", fileHashes(harness.chapterDirectory))
            harness.writeLedger(ledger)
            val mangas = Injekt.get<MangaRepository>()
            val chapters = Injekt.get<ChapterRepository>()
            val manga = withContext(NonCancellable) {
                mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = LocalSource.ID, url = harness.title, title = harness.title)),
                ).single().also {
                    ledger.put("mangaId", it.id)
                    harness.writeLedger(ledger)
                }
            }
            val chapter = withContext(NonCancellable) {
                chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "${harness.title}/Chapter",
                            name = "Chapter",
                        ),
                    ),
                ).single().also {
                    ledger.put("chapterId", it.id)
                    harness.writeLedger(ledger)
                }
            }
            harness.assertLocalDirectory(chapter)
            harness.drainMutex.withLock {
                harness.preferences.translationMasterEnabled.set(true)
                harness.requireConfiguredPreferences(master = true)
                // Real public enqueue; no engine, request, translator or worker injection.
                harness.manager.translate(manga, listOf(chapter), method = METHOD)
                harness.recordPreparedWorkOwnership(ledger)
            }
            val relay = withTimeout(180_000) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val receipt = harness.activeRelayOrNull()
                    if (receipt != null && harness.activeCheckpointReady(chapter.id) &&
                        harness.hasFirstPageCompletion()
                    ) {
                        harness.requireActive(chapter.id)
                        harness.requireSecondPageUntouched(ledger)
                        break
                    }
                    delay(100)
                }
                checkNotNull(harness.activeRelayOrNull())
            }
            harness.requireActive(chapter.id)
            harness.requireSecondPageUntouched(ledger)
            harness.verifyCompletedPage("001.png", ledger)
            val firstSources = harness.pageSources("001.png")
            check(sourceSetSha(firstSources) == relay.getString("firstPageSourceSetSha256"))
            check(firstSources.size == relay.getInt("firstPageRegionCount"))
            check(relay.getString("secondPageSourceSetSha256") != sourceSetSha(firstSources))
            harness.persistedActiveBarrier(chapter.id)
            harness.requirePersistedOwnedStorage(ledger)
            ledger.put("firstPageFiles", JSONObject(firstPageFiles(harness.chapterDirectory)))
                .put("firstPageSourcesSha256", sourceSetSha(firstSources))
                .put("firstPageTargetsSha256", sourceSetSha(harness.pageTargets("001.png")))
                .put("activeRelaySha256", shaFile(File(harness.output, "relay-active.json")))
                .put("activeRelay", relay)
                .put("activeNativeEngine", harness.actualEngineMetadata())
                .put("activeWork", harness.ownWorkSnapshot(ledger))
                .put("checkpointFiles", fileHashes(harness.chapterDirectory))
                .put("stage", "ARMED_ACTIVE_NATIVE_SECOND_POST_HELD")
                .put("armedAtElapsedMs", SystemClock.elapsedRealtime())
            harness.writeLedger(ledger)
            val ledgerSha = shaFile(harness.ledgerFile)
            val ready = JSONObject().put("schema", 1).put("uuid", harness.id)
                .put("phase", "READY_FOR_HOST_SIGKILL").put("ledgerSha256", ledgerSha)
                .put("pid", Process.myPid()).put("processStartTicks", processStartTicks())
                .put("completedPage001", true).put("page002OriginalAndUncommitted", true)
                .put("secondPostHeldBeforeUpstream", true).put("masterDiskOnAndQueueUnpaused", true)
                .put("ownWorkRunning", true).put("normalJUnitPassExpected", false)
                .put("hostMustKillWithinMs", 45_000)
            writeDurable(File(harness.output, "ready.json"), ready.toString(2).toByteArray())
            armed = true
            status(ready)
            // Existing production readTimeout remains unchanged. Root kills promptly; this is
            // not the earlier ten-minute quiescent/paused fixture's wait window.
            withTimeout(45_000) {
                var last = SystemClock.elapsedRealtime()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    delay(200)
                    if (SystemClock.elapsedRealtime() - last >= 2_000) {
                        check(shaFile(harness.ledgerFile) == ledgerSha)
                        harness.requireActive(chapter.id)
                        harness.requireSecondPageUntouched(ledger)
                        status(ready)
                        last = SystemClock.elapsedRealtime()
                    }
                }
            }
            error("Armed preparation cannot pass normally")
        } catch (failure: Throwable) {
            // SIGKILL never executes this path. After ARMED preserve the ledger for explicit C;
            // do not reinterpret a timeout, cleanup or runner return as successful process death.
            if (!armed) {
                withContext(NonCancellable) {
                    runCatching { harness.cleanup(ledger) }.exceptionOrNull()?.let(failure::addSuppressed)
                }
            }
            throw failure
        }
    }

    @Test(timeout = 120_000)
    fun verifyAutomaticallyCompletedNativeRecovery(): Unit = runBlocking {
        gate()
        val harness = Harness(uuid())
        val ledger = harness.readLedger(requireSameApks = true)
        val report = JSONObject().put("schema", 1).put("uuid", harness.id).put("passed", false)
            .put("verificationPid", Process.myPid()).put("verificationProcessStartTicks", processStartTicks())
            .put("ledgerSha256", shaFile(harness.ledgerFile))
            .put("manualResumeCalls", 0).put("manualEnqueueCalls", 0).put("workerStartCallsFromTest", 0)
            .put("automaticStartupProofIsSeparateHostReceipt", true)
            .put("savedPartialGroupResumeTested", false).put("tornWriteWindowTested", false)
            .put("actualReaderOrSignedRelease", false)
        var failure: Throwable? = null
        try {
            val host = harness.readHostRecoveryReceipt(ledger)
            check(
                jsonStringMap(fileHashes(harness.chapterDirectory)) ==
                    jsonStringMap(host.getJSONObject("preVerificationChapterFiles")),
            )
            report.put("hostRecoveryReceiptSha256", shaFile(File(harness.output, "host-recovery.json")))
                .put("automaticProcessPid", host.getInt("automaticProcessPid"))
                .put("automaticProcessStartTicks", host.getString("automaticProcessStartTicks"))
                .put("startupMode", host.getString("startupMode"))
                .put("normalAppLaunch", host.getBoolean("normalAppLaunch"))
                .put("verificationProcessIsAutomaticProcess", Process.myPid() == host.getInt("automaticProcessPid"))
            // The host observes normal-app completion BEFORE starting instrumentation B.
            // No ensureRestored/resume/translate/warmUp/worker-start calls in this verification body.
            harness.requireAutomaticallyCompletedIdle(ledger)
            harness.requirePersistedOwnedStorage(ledger)
            harness.requireOwnedDatabaseAndLocalFormat(ledger)
            harness.requireConfiguredPreferences(master = true)
            harness.verifyCompletedPage("001.png", ledger)
            harness.verifyCompletedPage("002.png", ledger)
            check(firstPageFiles(harness.chapterDirectory) == jsonStringMap(ledger.getJSONObject("firstPageFiles")))
            check(harness.completedPages() == setOf("001.png", "002.png"))
            val secondSources = harness.pageSources("002.png")
            val activeRelay = ledger.getJSONObject("activeRelay")
            check(sourceSetSha(secondSources) == activeRelay.getString("secondPageSourceSetSha256"))
            check(secondSources.size == activeRelay.getInt("secondPageRegionCount"))
            check(sourceSetSha(harness.pageSources("001.png")) == ledger.getString("firstPageSourcesSha256"))
            check(sourceSetSha(harness.pageTargets("001.png")) == ledger.getString("firstPageTargetsSha256"))
            harness.requireModelsUnchanged(ledger)
            report.put("firstPageAllCompletedFilesByteExact", true)
                .put("firstPageSourceAndTargetExact", true).put("bothOriginalsExact", true)
                .put("bothPagesHaveNonblankKoreanTargets", true)
                .put("completedPages", JSONArray(listOf("001.png", "002.png")))
                .put("activeSecondPageRetriedFromUntouchedSource", true)
                .put("logicalAttempts", JSONObject().put("001", 1).put("002", 2))
                .put("forwardedUpstreamPosts", JSONObject().put("001", 1).put("002", 1))
                .put("oneWholePagePostPerAttempt", true)
                .put("ownWorkAfterCompletion", harness.ownWorkSnapshot(ledger))
                .put("workInfoQueryScope", "Verification-process query after host-observed automatic disk completion")
                .put("preVerificationWorkManagerSucceededIndependentlyMeasured", false)
                .put("testUsesExistingPublicNativeFactory", true).put("fakeEngineOrTranslatorInjected", false)
                .put("nativeCallsDuringAutoProcessInstrumented", false).put("passed", true)
        } catch (caught: Throwable) {
            failure = caught
            report.put("failureClass", caught.javaClass.name)
        } finally {
            withContext(NonCancellable) {
                runCatching { harness.cleanup(ledger) }.fold(
                    onSuccess = { report.put("cleanup", it) },
                    onFailure = { cleanupFailure ->
                        report.put("cleanupFailureClass", cleanupFailure.javaClass.name)
                        if (failure == null) failure = cleanupFailure else failure!!.addSuppressed(cleanupFailure)
                    },
                )
                if (failure != null) report.put("passed", false)
                writeDurable(File(harness.output, "result.json"), report.toString(2).toByteArray())
                status(
                    JSONObject().put("phase", if (failure == null) "VERIFY_PASSED" else "VERIFY_FAILED")
                        .put("uuid", harness.id).put("report", File(harness.output, "result.json").absolutePath),
                )
            }
        }
        failure?.let { throw it }
    }

    @Test(timeout = 120_000)
    fun cleanupOwnedNativeRecovery(): Unit = runBlocking {
        gate()
        val harness = Harness(uuid())
        val ledger = harness.readLedger(requireSameApks = false)
        val receipt = withContext(NonCancellable) { harness.cleanup(ledger, standalone = true) }
        check(receipt.getBoolean("passed"))
        status(JSONObject().put("phase", "CLEANUP_PASSED").put("uuid", harness.id))
    }

    private inner class Harness(val id: String) {
        val preferences = Injekt.get<TranslationPreferences>()
        val manager = Injekt.get<TranslationManager>()
        val service = Injekt.get<TranslationEngineService>()
        val title = "Active native recovery fixture $id"
        private val externalParent = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val output = File(externalParent, "active-native-recovery-$id")
        private val inputRoot = File(externalParent, "active-native-input-$id")
        val ledgerFile = File(output, "ledger.json")
        private val storageManager = Injekt.get<StorageManager>()
        private val storagePreference = Injekt.get<StoragePreferences>().baseStorageDirectory
        private val storageRoot = File(output, "storage")
        private val storageUri = Uri.fromFile(storageRoot)
        private val localParent = File(storageRoot, "local")
        private val defaultStorageUri = Uri.parse(storagePreference.defaultValue()).also { check(it.scheme == "file") }
        private val defaultDirectory = File(checkNotNull(defaultStorageUri.path)).canonicalFile
        val book = File(localParent, title)
        val chapterDirectory = File(book, "Chapter")
        val translator = managerField("pageTranslator").get(manager) as PageTranslator
        val drainMutex = managerField("drainMutex").get(manager) as Mutex
        private val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        private val customCredentialKey = preferences.apiKeyFor("custom").key()
        private var expectedCustomCredentialPresence: Boolean? = null
        private val queuePrefs = context.getSharedPreferences("active_translations", Context.MODE_PRIVATE)
        private val workManager = WorkManager.getInstance(context)
        private val statistics = context.getSharedPreferences("translation_stats", Context.MODE_PRIVATE)
        private val booleanPrefs get() = listOf(
            preferences.translationMasterEnabled,
            preferences.nightReadEnabled,
            preferences.liveTranslate,
            preferences.translationEnabled,
            preferences.keepMaterials,
        )
        private val stringPrefs get() = listOf(
            preferences.provider,
            preferences.apiBase,
            preferences.model,
            preferences.sourceLangName,
            preferences.targetLangName,
            preferences.orientation,
            preferences.inpaintMethod,
        )
        private val configKeys get() = (booleanPrefs.map { it.key() } + stringPrefs.map { it.key() }).toSet()
        private val relayBase: String get() = checkNotNull(arguments.getString("activeNativeApiBase")).also {
            val uri = URI(it)
            check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535)
            check(uri.path.trimEnd('/') == "/v1")
            check(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
        }
        private val model: String get() = checkNotNull(arguments.getString("activeNativeModel")).also {
            check(it.matches(Regex("[A-Za-z0-9_.:/-]{1,160}")))
        }

        suspend fun requireInitiallyIdle() {
            manager.ensureRestored()
            check(manager.queueState.value.isEmpty())
            requireNoDownloads()
            requireColdService()
            requireNoTranslationWorkers()
            check(TranslationStore(context).restore().isEmpty())
            val marks = TranslationStore(context).restorePendingMarks(Long.MIN_VALUE)
            check(marks.translate.isEmpty() && marks.night.isEmpty())
            check(queuePrefs.all.keys.all { it in QUEUE_BOOLEANS || it in QUEUE_SETS })
            check(QUEUE_SETS.all { queuePrefs.getStringSet(it, emptySet()).orEmpty().isEmpty() })
            requireOriginalStorageBoundary()
            // No decryption, credential extraction or slot writes; a literally empty backing is
            // a valid unauthenticated configuration and is distinct from an absent preference.
            requireCustomCredentialEmptyAndUnchanged()
            requireModelsPresent()
            check(TranslationEngineConfig.modelsResolvable(context))
            check(book.parentFile == localParent && book.canonicalFile == book.absoluteFile)
            check(output.canonicalFile == output.absoluteFile && output.parentFile == externalParent)
            relayBase
            model
            withTimeout(10_000) { Injekt.get<SourceManager>().isInitialized.first { it } }
        }

        fun readInputs(): JSONObject {
            check(inputRoot.canonicalFile == inputRoot.absoluteFile && inputRoot.parentFile == externalParent)
            check(inputRoot.isDirectory)
            check(inputRoot.list()!!.toSet() == setOf("manifest.json", "001.png", "002.png"))
            val manifestFile = File(inputRoot, "manifest.json")
            val manifest = readBoundedJson(manifestFile, inputRoot, MAX_RECEIPT_BYTES)
            check(manifest.getInt("schema") == 1 && manifest.getString("uuid") == id)
            val rows = jsonObjects(manifest.getJSONArray("files"))
            check(rows.size == 2 && rows.map { it.getString("name") }.toSet() == setOf("001.png", "002.png"))
            for (row in rows) {
                val name = row.getString("name")
                val file = File(inputRoot, name)
                check(file.canonicalFile == file.absoluteFile && file.parentFile == inputRoot && file.isFile)
                check(file.length() in 1..MAX_FILE_BYTES && file.length() == row.getLong("bytes"))
                check(row.getString("sha256").matches(SHA_PATTERN) && shaFile(file) == row.getString("sha256"))
                check(row.getString("sha256") == INPUT_SHA.getValue(name))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == "image/png")
                check(bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS)
            }
            return JSONObject(manifest.toString()).put("manifestSha256", shaFile(manifestFile))
        }

        fun copyInputs(inputs: JSONObject) {
            check(shaFile(File(inputRoot, "manifest.json")) == inputs.getString("manifestSha256"))
            jsonObjects(inputs.getJSONArray("files")).forEach {
                val source = File(inputRoot, it.getString("name"))
                check(shaFile(source) == it.getString("sha256"))
                val destination = File(chapterDirectory, it.getString("name"))
                check(!destination.exists())
                writeDurable(destination, source.readBytes())
                check(shaFile(destination) == it.getString("sha256"))
            }
        }

        fun initialLedger(inputs: JSONObject): JSONObject {
            val today = LocalDate.now()
            val dates = listOf(today, today.plusDays(1)).map { it.toString() }
            val statsFile = File(context.applicationInfo.dataDir, "shared_prefs/translation_stats.xml")
            return JSONObject().put("schema", 1).put("uuid", id).put("package", context.packageName)
                .put("pid", Process.myPid()).put("processStartTicks", processStartTicks()).put("uid", Process.myUid())
                .put("stage", "PREPARING").put("title", title)
                .put("targetApkSha256", shaFile(File(context.applicationInfo.sourceDir)))
                .put("runnerApkSha256", shaFile(File(instrumentation.context.applicationInfo.sourceDir)))
                .put("localParentPathSha256", sha(localParent.path.toByteArray()))
                .put("storage", originalStorageSnapshot()).put("inputs", inputs)
                .put("preferences", preferenceSnapshot()).put("configuredRelayBase", relayBase)
                .put("configuredModel", model)
                .put("customCredentialSlotWasSet", requireCustomCredentialEmptyAndUnchanged())
                .put("modelFiles", modelHashes())
                .put("statisticsFileExisted", statsFile.exists())
                .put(
                    "statisticsDates",
                    JSONArray(
                        dates.map { date ->
                            JSONObject().put("key", date).put("contains", statistics.contains(date))
                                .put("value", statistics.getString(date, null) ?: JSONObject.NULL)
                        },
                    ),
                )
                .put("statisticsOtherHashes", statisticsOtherHashes(dates.toSet()))
                .put(
                    "queueBooleans",
                    JSONArray(
                        QUEUE_BOOLEANS.map {
                            JSONObject().put("key", it).put("contains", queuePrefs.contains(it))
                                .put("value", queuePrefs.getBoolean(it, false))
                        },
                    ),
                )
                .put("queueEmptySetPresence", JSONArray(QUEUE_SETS.filter(queuePrefs::contains)))
                .put("poolPaused", manager.isTranslatePaused.value).put("nightPoolPaused", manager.isNightPaused.value)
                .put(
                    "beforeWork",
                    JSONObject(TAGS.associateWith { tag -> JSONArray(work(tag).map { it.id.toString() }) }),
                )
                .put("preparedOwnWorkIds", JSONArray())
                .put("mangaId", JSONObject.NULL).put("chapterId", JSONObject.NULL)
        }

        private fun preferenceSnapshot() = JSONArray().apply {
            booleanPrefs.forEach {
                put(
                    JSONObject().put("key", it.key()).put("type", "boolean")
                        .put("contains", it.isSet()).put("value", it.get()),
                )
            }
            stringPrefs.forEach {
                // Only this non-credential allowlist enters the owned UUID rollback ledger.
                if (it === preferences.apiBase && it.get().isNotBlank()) {
                    val uri = URI(it.get())
                    check(uri.scheme in setOf("http", "https") && uri.rawUserInfo == null)
                    check(uri.rawQuery == null && uri.rawFragment == null)
                }
                put(
                    JSONObject().put("key", it.key()).put("type", "string")
                        .put("contains", it.isSet()).put("value", it.get()),
                )
            }
        }

        fun writeLedger(ledger: JSONObject) = writeDurable(ledgerFile, ledger.toString(2).toByteArray())

        fun readLedger(requireSameApks: Boolean): JSONObject {
            val expected = checkNotNull(arguments.getString("activeNativeLedgerSha256"))
            check(expected.matches(SHA_PATTERN))
            val ledger = readBoundedJson(ledgerFile, output, MAX_LEDGER_BYTES)
            check(shaFile(ledgerFile) == expected)
            check(ledger.getInt("schema") == 1 && ledger.getString("uuid") == id)
            check(ledger.getString("package") == context.packageName && ledger.getInt("uid") == Process.myUid())
            check(ledger.getString("title") == title)
            if (requireSameApks) {
                check(ledger.getString("targetApkSha256") == shaFile(File(context.applicationInfo.sourceDir)))
                val runner = File(instrumentation.context.applicationInfo.sourceDir)
                check(ledger.getString("runnerApkSha256") == shaFile(runner))
            }
            check(ledger.getString("localParentPathSha256") == sha(localParent.path.toByteArray()))
            validateStorageSnapshot(ledger.getJSONObject("storage"))
            check(ledger.getString("configuredRelayBase") == relayBase && ledger.getString("configuredModel") == model)
            val saved = jsonObjects(ledger.getJSONArray("preferences"))
            check(saved.size == configKeys.size && saved.map { it.getString("key") }.toSet() == configKeys)
            saved.forEach {
                val boolean = it.getString("key") in booleanPrefs.map { p -> p.key() }
                check(it.getString("type") == if (boolean) "boolean" else "string")
            }
            val originalPresence = ledger.getBoolean("customCredentialSlotWasSet")
            val expectedPresence = expectedCustomCredentialPresence
            check(expectedPresence == null || expectedPresence == originalPresence)
            expectedCustomCredentialPresence = originalPresence
            requireCustomCredentialEmptyAndUnchanged()
            return ledger
        }

        /** Return presence only. A nonempty backing is rejected without decrypting/extracting it. */
        private fun requireCustomCredentialEmptyAndUnchanged(): Boolean {
            val present = defaultPrefs.contains(customCredentialKey)
            check(!present || defaultPrefs.getString(customCredentialKey, null) == "") {
                "Custom credential backing must be absent or literally empty"
            }
            val expected = expectedCustomCredentialPresence
            if (expected == null) {
                expectedCustomCredentialPresence = present
            } else {
                check(present == expected) { "Original custom credential slot presence must be preserved" }
            }
            return present
        }

        fun closeMasterGates() {
            preferences.translationMasterEnabled.set(false)
            preferences.nightReadEnabled.set(false)
            manager.haltForMasterOff()
            check(defaultPrefs.edit().commit())
            val expected = mapOf(
                preferences.translationMasterEnabled.key() to false,
                preferences.nightReadEnabled.key() to false,
            )
            check(readSelectedPreferences(defaultPrefsName(), expected.keys) == expected)
        }

        fun configureRealEngine(ledger: JSONObject) {
            requireCustomCredentialEmptyAndUnchanged()
            preferences.provider.set("custom")
            preferences.apiBase.set(relayBase)
            preferences.model.set(model)
            preferences.sourceLangName.set("Japanese")
            preferences.targetLangName.set("Korean")
            preferences.orientation.set("horizontal")
            preferences.inpaintMethod.set(METHOD)
            preferences.liveTranslate.set(false)
            preferences.translationEnabled.set(false)
            preferences.keepMaterials.set(true)
            preferences.nightReadEnabled.set(false)
            requireConfiguredPreferences(master = false)
            ledger.put("stage", "REAL_NATIVE_CONFIG_PERSISTED")
            writeLedger(ledger)
        }

        fun requireConfiguredPreferences(master: Boolean) {
            requireCustomCredentialEmptyAndUnchanged()
            val expected = configuredValues(master)
            check(defaultPrefs.edit().commit())
            check(expected.keys == configKeys)
            check(readSelectedPreferences(defaultPrefsName(), configKeys) == expected)
            booleanPrefs.forEach { check(it.isSet() && it.get() == expected.getValue(it.key())) }
            stringPrefs.forEach { check(it.isSet() && it.get() == expected.getValue(it.key())) }
            // The public config mapping reads non-credential fields only; do not read the opaque signature.
            val cfg = TranslationEngineConfig.buildEngineConfig(preferences, METHOD)
            check(cfg.translator.provider == "custom" && cfg.translator.model == model)
            check(cfg.translator.fromLangName == "Japanese" && cfg.translator.toLangName == "Korean")
            check(cfg.inpainter.method == "boxfill")
        }

        private fun configuredValues(master: Boolean): Map<String, Any> = mapOf(
            preferences.translationMasterEnabled.key() to master, preferences.nightReadEnabled.key() to false,
            preferences.liveTranslate.key() to false, preferences.translationEnabled.key() to false,
            preferences.keepMaterials.key() to true, preferences.provider.key() to "custom",
            preferences.apiBase.key() to relayBase, preferences.model.key() to model,
            preferences.sourceLangName.key() to "Japanese", preferences.targetLangName.key() to "Korean",
            preferences.orientation.key() to "horizontal", preferences.inpaintMethod.key() to METHOD,
        )

        private fun effectiveStorageUri(): Uri? = (
            StorageManager::class.java.getDeclaredField("baseDir").apply { isAccessible = true }
                .get(storageManager) as UniFile?
            )?.uri

        private fun requireOriginalStorageBoundary() {
            check(effectiveStorageUri() == null) { "Original effective storage must be absent" }
            check(!storagePreference.isSet()) { "Original audit storage preference must be unset" }
            check(storagePreference.get() == storagePreference.defaultValue())
            check(!defaultDirectory.exists() && !File(defaultDirectory, "local").exists()) {
                "The default/user storage boundary must remain absent"
            }
            check(defaultDirectory != storageRoot)
            check(!storageRoot.path.startsWith(defaultDirectory.path + File.separator))
        }

        private fun originalStorageSnapshot(): JSONObject {
            requireOriginalStorageBoundary()
            return JSONObject().put("key", storagePreference.key())
                .put("contains", storagePreference.isSet()).put("value", storagePreference.get())
                .put("effectiveUri", JSONObject.NULL).put("defaultUri", defaultStorageUri.toString())
                .put("defaultDirectoryExists", false).put("defaultLocalExists", false)
                .put("ownedUri", storageUri.toString()).put("ownedDirectory", "storage")
        }

        private fun validateStorageSnapshot(saved: JSONObject) {
            check(saved.getString("key") == storagePreference.key())
            check(!saved.getBoolean("contains") && saved.isNull("effectiveUri"))
            check(saved.getString("value") == storagePreference.defaultValue())
            check(saved.getString("defaultUri") == defaultStorageUri.toString())
            check(!saved.getBoolean("defaultDirectoryExists") && !saved.getBoolean("defaultLocalExists"))
            check(saved.getString("ownedUri") == storageUri.toString())
            check(saved.getString("ownedDirectory") == "storage")
        }

        suspend fun prepareOwnedStorage(ledger: JSONObject) {
            validateStorageSnapshot(ledger.getJSONObject("storage"))
            requireOriginalStorageBoundary()
            check(storageRoot.canonicalFile == storageRoot.absoluteFile && storageRoot.parentFile == output)
            check(!storageRoot.exists() && storageRoot.mkdir())
            // The rollback ledger already exists and is fsynced before this real preference write.
            storagePreference.set(storageUri.toString())
            requirePersistedOwnedStorage(ledger)
            check(TranslationEngineConfig.modelsResolvable(context)) {
                "The existing private model fallback must remain available after the storage switch"
            }
            ledger.put("stage", "OWNED_STORAGE_PERSISTED")
            writeLedger(ledger)
        }

        suspend fun requirePersistedOwnedStorage(ledger: JSONObject) {
            validateStorageSnapshot(ledger.getJSONObject("storage"))
            check(defaultPrefs.edit().commit())
            check(storagePreference.isSet() && storagePreference.get() == storageUri.toString())
            val expected = mapOf(storagePreference.key() to storageUri.toString())
            check(readSelectedPreferences(defaultPrefsName(), expected.keys) == expected)
            awaitCondition {
                effectiveStorageUri() == storageUri &&
                    STORAGE_DIRECTORIES.all { File(storageRoot, it).isDirectory } &&
                    File(storageRoot, "downloads/.nomedia").isFile
            }
            check(storageRoot.canonicalFile == storageRoot.absoluteFile && storageRoot.parentFile == output)
            check(storageManager.getLocalSourceDirectory()?.uri == Uri.fromFile(localParent))
            check(!defaultDirectory.exists() && !File(defaultDirectory, "local").exists())
        }

        private fun requireStorageRestorePrecondition(ledger: JSONObject) {
            val saved = ledger.getJSONObject("storage")
            validateStorageSnapshot(saved)
            val owned = storagePreference.isSet() && storagePreference.get() == storageUri.toString()
            val original = storagePreference.isSet() == saved.getBoolean("contains") &&
                storagePreference.get() == saved.getString("value")
            check(owned || original) { "Storage changed outside this owned harness; retain the recovery ledger" }
            check(!defaultDirectory.exists() && !File(defaultDirectory, "local").exists())
        }

        private suspend fun restoreOwnedStorage(ledger: JSONObject, receipt: JSONObject) {
            requireStorageRestorePrecondition(ledger)
            val saved = ledger.getJSONObject("storage")
            if (storagePreference.isSet() && storagePreference.get() == storageUri.toString()) {
                // Consume the earlier switch before deleting its preference, even if preparation
                // failed while the StorageManager collector was still catching up. A fresh C
                // invocation also initializes the real manager from this persisted owned URI.
                awaitCondition { effectiveStorageUri() == storageUri }
            }
            if (saved.getBoolean("contains")) {
                storagePreference.set(saved.getString("value"))
            } else {
                storagePreference.delete()
            }
            check(defaultPrefs.edit().commit())
            check(storagePreference.isSet() == saved.getBoolean("contains"))
            check(storagePreference.get() == saved.getString("value"))
            val expected = if (saved.getBoolean("contains")) {
                mapOf(storagePreference.key() to saved.getString("value"))
            } else {
                emptyMap()
            }
            check(readSelectedPreferences(defaultPrefsName(), setOf(storagePreference.key())) == expected)
            awaitCondition { effectiveStorageUri() == null }
            requireOriginalStorageBoundary()
            // Never delete a directory still selected by the real StorageManager. Its automatic
            // child directories are audited separately from the UUID book/page tree above.
            val removed = removeOwnedStorageSkeleton()
            receipt.put("storagePreferenceValuePresenceAndDiskRestored", true)
                .put("effectiveStorageRestoredToOriginalNull", true)
                .put("defaultDirectoryAndLocalStillAbsent", true)
                .put("ownedStorageCleanup", removed).put("ownedStorageDirectoryAbsent", !storageRoot.exists())
        }

        private fun removeOwnedStorageSkeleton(): JSONObject {
            val receipt = JSONObject().put("removedFiles", 0).put("removedDirectories", 0)
                .put("onlyAutomaticStorageChildrenAllowed", true)
            if (!storageRoot.exists()) return receipt.put("alreadyAbsent", true)
            check(storageRoot.canonicalFile == storageRoot.absoluteFile && storageRoot.parentFile == output)
            check(storageRoot.isDirectory)
            val children = checkNotNull(storageRoot.listFiles()).sortedBy { it.name }
            check(children.all { it.name in STORAGE_DIRECTORIES })
            val files = mutableListOf<File>()
            for (directory in children) {
                check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile)
                check(directory.canonicalFile.parentFile == storageRoot)
                val contents = checkNotNull(directory.listFiles())
                if (directory.name == "downloads") {
                    check(contents.all { it.name == ".nomedia" && it.isFile && it.length() == 0L })
                    contents.forEach {
                        check(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == directory)
                        files += it
                    }
                } else {
                    check(contents.isEmpty()) { "An owned storage child has unexpected contents; retain it" }
                }
            }
            // Finish validating every allowed directory/file before deleting even the empty marker.
            val hashes = files.associateWith { sha(it.readBytes()) }
            receipt.put(
                "fileHashesBeforeDelete",
                JSONObject(
                    hashes.mapKeys {
                        it.key.relativeTo(storageRoot).invariantSeparatorsPath
                    },
                ),
            )
            files.forEach {
                check(sha(it.readBytes()) == hashes.getValue(it))
                check(it.delete() && !it.exists())
            }
            children.forEach { check(it.list()!!.isEmpty() && it.delete() && !it.exists()) }
            check(storageRoot.list()!!.isEmpty() && storageRoot.delete() && !storageRoot.exists())
            return receipt.put("removedFiles", files.size).put("removedDirectories", children.size + 1)
        }

        fun activeRelayOrNull(): JSONObject? {
            val file = File(output, "relay-active.json")
            if (!file.exists()) return null
            val receipt = readBoundedJson(file, output, MAX_RECEIPT_BYTES)
            check(receipt.getInt("schema") == 1 && receipt.getString("uuid") == id)
            check(receipt.getString("phase") == "SECOND_REQUEST_HELD_BEFORE_UPSTREAM")
            check(receipt.getInt("firstPageForwardedPosts") == 1 && receipt.getInt("secondPageReceivedPosts") == 1)
            check(receipt.getInt("secondPageForwardedPosts") == 0)
            check(receipt.getBoolean("requestHasOnlyText") && receipt.getBoolean("allRegionIdsUnique"))
            check(receipt.getBoolean("firstRequestWholePageBatch") && receipt.getBoolean("secondRequestWholePageBatch"))
            listOf("firstPageSourceSetSha256", "secondPageSourceSetSha256").forEach {
                check(receipt.getString(it).matches(SHA_PATTERN))
            }
            check(receipt.getInt("firstPageRegionCount") == 1 && receipt.getInt("secondPageRegionCount") == 2)
            check(receipt.getString("firstPageSourceSetSha256") == sourceSetSha(EXPECTED_SOURCE.getValue("001.png")))
            check(receipt.getString("secondPageSourceSetSha256") == sourceSetSha(EXPECTED_SOURCE.getValue("002.png")))
            return receipt
        }

        fun hasFirstPageCompletion(): Boolean = File(chapterDirectory, CompletionManifestCodec.PRIMARY).isFile &&
            "001.png" in CompletionManifestCodec.resolve(
                chapterDirectory.listFiles().orEmpty().filter { isManifest(it.name) }
                    .associate { it.name to it.readBytes() },
            )

        fun requireActive(chapterId: Long) {
            val item = manager.queueState.value.single()
            check(item.chapter.id == chapterId && item.manga.source == LocalSource.ID && item.manga.url == title)
            check(item.kind == TranslationItem.Kind.TRANSLATE && item.status == TranslationItem.Status.TRANSLATING)
            check(item.method == METHOD && !item.paused && !manager.isTranslatePaused.value)
            check(item.done == 1 && item.total == 2)
            check(preferences.translationMasterEnabled.get() && !preferences.nightReadEnabled.get())
            check(service.warm.value && !service.loading.value)
            check((serviceField("inFlight").get(service) as AtomicInteger).get() >= 1)
            check(work("Translator").any { it.state == WorkInfo.State.RUNNING })
        }

        fun activeCheckpointReady(chapterId: Long): Boolean {
            val item = manager.queueState.value.singleOrNull() ?: return false
            return item.chapter.id == chapterId && item.status == TranslationItem.Status.TRANSLATING &&
                item.done == 1 && item.total == 2 && work("Translator").any { it.state == WorkInfo.State.RUNNING }
        }

        fun requireSecondPageUntouched(ledger: JSONObject) {
            check(completedPages() == setOf("001.png"))
            val second = File(chapterDirectory, "002.png")
            check(shaFile(second) == ledger.getJSONObject("sourceFiles").getString("002.png"))
            val names = File(chapterDirectory, ".yakuyomi").listFiles().orEmpty().map { it.name }.toSet()
            check(names.none { it.startsWith("002.") })
            check(PartialPageStore.artifactNames("002.png", names).isEmpty())
        }

        suspend fun persistedActiveBarrier(chapterId: Long) {
            requireConfiguredPreferences(master = true)
            check(queuePrefs.edit().commit())
            val disk = readSelectedPreferences("active_translations", setOf("0", "paused", "paused_night") + QUEUE_SETS)
            check(disk["paused"] == false)
            check((disk["pending_translate"] as Set<*>).isEmpty() && (disk["pending_night"] as Set<*>).isEmpty())
            val item = JSONObject(disk.getValue("0") as String)
            check(item.getLong("chapterId") == chapterId && item.getString("method") == METHOD)
            check(!item.has("paused") || !item.getBoolean("paused"))
            check(!item.has("mangaPaused") || !item.getBoolean("mangaPaused"))
            check(!item.has("errored") || !item.getBoolean("errored"))
            check(!item.has("nightRender") || !item.getBoolean("nightRender"))
            val restored = TranslationStore(context).restore().single()
            check(restored.chapter.id == chapterId && !restored.paused && !restored.mangaPaused && !restored.errored)
        }

        fun actualEngineMetadata(): JSONObject {
            val engine = serviceField("engine").get(service)
            check(engine is Pipeline) { "Actual production Pipeline required" }
            val ocr = Pipeline::class.java.getDeclaredField("ocr").apply { isAccessible = true }.get(engine)
            check(ocr is Ocr) { "Actual production native Ocr required" }
            return JSONObject().put("engineClass", engine.javaClass.name).put("ocrClass", ocr.javaClass.name)
                .put("ocrBackend", ocr.backend).put("privateComponentInjected", false)
        }

        fun ownWorkSnapshot(ledger: JSONObject) = JSONArray().apply {
            val prior = ledger.getJSONObject("beforeWork")
            TAGS.forEach { tag ->
                val baseline = prior.getJSONArray(tag).let { array ->
                    (0 until array.length()).map { array.getString(it) }.toSet()
                }
                work(tag).filter { it.id.toString() !in baseline }.forEach {
                    put(JSONObject().put("id", it.id.toString()).put("tag", tag).put("state", it.state.name))
                }
            }
        }

        fun readHostRecoveryReceipt(ledger: JSONObject): JSONObject {
            check(ledger.getString("stage") == "ARMED_ACTIVE_NATIVE_SECOND_POST_HELD")
            val file = File(output, "host-recovery.json")
            val expected = checkNotNull(arguments.getString("activeNativeHostReceiptSha256"))
            check(expected.matches(SHA_PATTERN))
            val receipt = readBoundedJson(file, output, MAX_RECEIPT_BYTES)
            check(shaFile(file) == expected)
            check(receipt.getInt("schema") == 1 && receipt.getString("uuid") == id)
            check(receipt.getString("ledgerSha256") == shaFile(ledgerFile))
            check(receipt.getString("targetApkSha256") == ledger.getString("targetApkSha256"))
            check(receipt.getInt("preparedPid") == ledger.getInt("pid"))
            check(receipt.getString("preparedProcessStartTicks") == ledger.getString("processStartTicks"))
            check(receipt.getInt("killSignal") == 9 && receipt.getBoolean("oldProcessAbsent"))
            check(receipt.getBoolean("oldClientAborted"))
            check(receipt.getBoolean("secondOldRequestUpstreamNeverDispatched"))
            val startupMode = receipt.getString("startupMode")
            check(startupMode in setOf("NORMAL_APP_START", "BACKGROUND_SYSTEM_RESTART"))
            check(receipt.getBoolean("normalAppLaunch") == (startupMode == "NORMAL_APP_START"))
            check(receipt.getBoolean("automaticCompletionObserved"))
            check(receipt.getBoolean("completedBeforeVerificationInstrumentationStarted"))
            check(receipt.getInt("manualResumeCalls") == 0 && receipt.getInt("manualEnqueueCalls") == 0)
            check(receipt.getInt("workerStartCallsFromHarness") == 0)
            check(receipt.getInt("automaticProcessPid") > 0)
            check(receipt.getInt("automaticProcessPid") != ledger.getInt("pid"))
            check(receipt.getString("automaticProcessStartTicks").toLong() > 0)
            check(receipt.getString("automaticProcessStartTicks") != ledger.getString("processStartTicks"))
            check(receipt.getInt("firstPageForwardedPosts") == 1 && receipt.getInt("secondPageReceivedPosts") == 2)
            check(receipt.getInt("secondPageForwardedPosts") == 1)
            check(receipt.getBoolean("requestHasOnlyText") && receipt.getBoolean("allRegionIdsUnique"))
            check(receipt.getBoolean("firstRequestWholePageBatch") && receipt.getBoolean("secondRequestWholePageBatch"))
            check(receipt.getString("firstPageSourceSetSha256") == ledger.getString("firstPageSourcesSha256"))
            check(
                receipt.getString("secondPageSourceSetSha256") ==
                    ledger.getJSONObject("activeRelay").getString("secondPageSourceSetSha256"),
            )
            // RUNNING came from A's sealed ready evidence. The host does not query WorkManager
            // in the normal app; B's later WorkInfo query is explicitly a different observation.
            check(receipt.getBoolean("ownWorkObservedRunning"))
            check(receipt.getBoolean("normalServicesIdleObserved"))
            check(!receipt.getBoolean("preVerificationWorkManagerSucceededIndependentlyMeasured"))
            // Existing unique KEEP may reuse A's Work UUID; do not require a new B Work ID.
            return receipt
        }

        fun requireAutomaticallyCompletedIdle(ledger: JSONObject) {
            check(manager.queueState.value.isEmpty() && TranslationBusy.active.value == 0)
            requireNoDownloads()
            check((serviceField("inFlight").get(service) as AtomicInteger).get() == 0 && !service.loading.value)
            val pending = TranslationStore(context).restorePendingMarks(Long.MIN_VALUE)
            check(pending.translate.isEmpty() && pending.night.isEmpty())
            check(queuePrefs.all.keys.all { it in QUEUE_BOOLEANS || it in QUEUE_SETS })
            check(TAGS.all { work(it).all { info -> info.state.isFinished } })
            check(
                jsonObjects(ownWorkSnapshot(ledger)).any {
                    it.getString("tag") == "Translator" && it.getString("state") == "SUCCEEDED"
                },
            )
            check(!hasProgressNotification())
        }

        private fun materials(name: String): PageMaterials {
            val file = File(chapterDirectory, ".yakuyomi/${name.removeSuffix(".png")}.json")
            check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() in 1..MAX_LEDGER_BYTES)
            return Json { ignoreUnknownKeys = true }.decodeFromString(file.readText())
        }
        fun pageSources(name: String) = materials(name).regions.filterNot { it.preserveOriginal }.map { it.source }
        fun pageTargets(name: String) = materials(name).regions.filterNot { it.preserveOriginal }.map { it.target }

        fun verifyCompletedPage(name: String, ledger: JSONObject) {
            check(name in setOf("001.png", "002.png") && name in completedPages())
            val sourceSha = ledger.getJSONObject("sourceFiles").getString(name)
            val original = File(chapterDirectory, ".yakuyomi/${name.removeSuffix(".png")}.orig.png")
            check(shaFile(original) == sourceSha && shaFile(File(chapterDirectory, name)) != sourceSha)
            val dimensions = imageBounds(original)
            check(imageBounds(File(chapterDirectory, name)) == dimensions)
            val mask = File(chapterDirectory, ".yakuyomi/${name.removeSuffix(".png")}.mask.png")
            check(imageBounds(mask) == dimensions)
            val material = materials(name)
            check(material.method == METHOD && material.regions.isNotEmpty())
            check(material.regions.none { it.preserveOriginal })
            check(
                material.regions.all {
                    it.source.isNotBlank() && it.target.isNotBlank() && HANGUL.containsMatchIn(it.target)
                },
            )
            check(material.regions.map { it.source }.sorted() == EXPECTED_SOURCE.getValue(name).sorted())
            material.regions.forEach { region ->
                check(region.angle.isFinite() && region.cx.isFinite() && region.cy.isFinite())
                check(region.boxW.isFinite() && region.boxH.isFinite() && region.boxW > 0 && region.boxH > 0)
                check(region.direction in setOf("h", "v"))
                check(region.bbox.size == 4 && region.bbox.all { it.isFinite() })
                check(region.bbox[0] < region.bbox[2] && region.bbox[1] < region.bbox[3])
                check(region.quads.isNotEmpty() && region.quads.all { it.size == 4 })
                region.quads.flatten().forEach { point ->
                    check(point.size == 2 && point.all { it.isFinite() })
                    check(point[0] in -1f..(dimensions.first + 1f))
                    check(point[1] in -1f..(dimensions.second + 1f))
                }
            }
            // Fresh full success legitimately has no partial journal. Validate one only if it
            // actually exists; original/body/materials plus manifest are the fresh success oracle.
            store().load(name, recover = false)?.let { saved ->
                check(saved.complete && saved.original.sha256 == sourceSha && saved.safeContextPayload != null)
                check(saved.width == dimensions.first && saved.height == dimensions.second)
                val progress = OcrProgressCodec.decode(saved.progressPayload, saved.width, saved.height)
                check(progress.regions.size == material.regions.size && !progress.hasPending)
                check(progress.regions.all { it.state == RegionState.TRANSLATED })
            }
        }

        suspend fun requireOwnedDatabaseAndLocalFormat(ledger: JSONObject) {
            val mangaId = ledger.getLong("mangaId")
            val chapterId = ledger.getLong("chapterId")
            val manga = checkNotNull(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId(title, LocalSource.ID))
            check(manga.id == mangaId && manga.url == title && manga.source == LocalSource.ID)
            val chapter = checkNotNull(Injekt.get<ChapterRepository>().getChapterById(chapterId))
            check(chapter.mangaId == mangaId && chapter.url == "$title/Chapter")
            withTimeout(10_000) { Injekt.get<SourceManager>().isInitialized.first { it } }
            assertLocalDirectory(chapter)
        }

        private fun requireModelsPresent(): List<File> {
            // Android may expose filesDir through /data/user/0 while libcore resolves it to /data/data.
            val files = context.filesDir.canonicalFile
            check(files.isDirectory && files.name == "files")
            check(files.parentFile == File(context.applicationInfo.dataDir).canonicalFile)
            val models = File(files, "models")
            check(models.isDirectory && models.canonicalFile == models.absoluteFile)
            return MODEL_NAMES.map {
                val file = File(models, it)
                check(file.isFile && file.length() > 0 && file.canonicalFile == file.absoluteFile)
                file
            }
        }
        private fun modelHashes(): JSONObject {
            return JSONObject(requireModelsPresent().associate { it.name to shaFile(it) })
        }
        fun requireModelsUnchanged(ledger: JSONObject) {
            check(jsonStringMap(modelHashes()) == jsonStringMap(ledger.getJSONObject("modelFiles")))
        }

        private fun statisticsOtherHashes(excluded: Set<String>) = JSONObject(
            statistics.all.filterKeys { it !in excluded }.mapValues { (_, value) ->
                check(value is String)
                sha(value.toByteArray())
            },
        )

        private fun restoreStatistics(ledger: JSONObject) {
            val rows = jsonObjects(ledger.getJSONArray("statisticsDates"))
            check(rows.size == 2 && rows.map { it.getString("key") }.toSet().size == 2)
            val dates = rows.map { it.getString("key") }.toSet()
            rows.forEach { check(LocalDate.parse(it.getString("key")).toString() == it.getString("key")) }
            // Only the two originally protected date keys may change; other statistics, including
            // any later-date writes outside this bounded run, must match their original hashes.
            check(
                jsonStringMap(statisticsOtherHashes(dates)) ==
                    jsonStringMap(ledger.getJSONObject("statisticsOtherHashes")),
            )
            val editor = statistics.edit()
            rows.forEach {
                if (it.getBoolean("contains")) {
                    editor.putString(it.getString("key"), it.getString("value"))
                } else {
                    editor.remove(it.getString("key"))
                }
            }
            check(editor.commit())
            rows.forEach {
                check(statistics.contains(it.getString("key")) == it.getBoolean("contains"))
                val previous = if (it.isNull("value")) null else it.getString("value")
                check(statistics.getString(it.getString("key"), null) == previous)
            }
            val expected = rows.filter { it.getBoolean("contains") }
                .associate { it.getString("key") to it.getString("value") }
            check(readSelectedPreferences("translation_stats", dates) == expected)
            if (!ledger.getBoolean("statisticsFileExisted")) {
                check(statistics.all.isEmpty() && context.deleteSharedPreferences("translation_stats"))
                check(!File(context.applicationInfo.dataDir, "shared_prefs/translation_stats.xml").exists())
            }
        }

        private fun requirePreferenceRestorePrecondition(ledger: JSONObject) {
            val temporary = configuredValues(master = true)
            jsonObjects(ledger.getJSONArray("preferences")).forEach { row ->
                val key = row.getString("key")
                if (row.getString("type") == "boolean") {
                    val pref = booleanPrefs.single { it.key() == key }
                    val original = pref.isSet() == row.getBoolean("contains") && pref.get() == row.getBoolean("value")
                    val configured = pref.isSet() && pref.get() == temporary.getValue(key)
                    val closed = key == preferences.translationMasterEnabled.key() && pref.isSet() && !pref.get()
                    check(original || configured || closed)
                } else {
                    val pref = stringPrefs.single { it.key() == key }
                    val original = pref.isSet() == row.getBoolean("contains") && pref.get() == row.getString("value")
                    val configured = pref.isSet() && pref.get() == temporary.getValue(key)
                    check(original || configured)
                }
            }
            requireCustomCredentialEmptyAndUnchanged()
        }

        private fun restorePreferences(ledger: JSONObject) {
            val saved = jsonObjects(ledger.getJSONArray("preferences"))
            saved.filter { it.getString("type") == "string" }.forEach { row ->
                val pref = stringPrefs.single { it.key() == row.getString("key") }
                if (row.getBoolean("contains")) pref.set(row.getString("value")) else pref.delete()
            }
            val gates = setOf(preferences.translationMasterEnabled.key(), preferences.nightReadEnabled.key())
            // Restore non-gate booleans first; gates last, after all owned work and storage settle.
            val booleans = saved.filter { it.getString("type") == "boolean" }
                .sortedBy { it.getString("key") in gates }
            booleans.forEach { row ->
                val pref = booleanPrefs.single { it.key() == row.getString("key") }
                if (row.getBoolean("contains")) pref.set(row.getBoolean("value")) else pref.delete()
            }
            check(defaultPrefs.edit().commit())
            val expected = saved.filter { it.getBoolean("contains") }.associate { row ->
                val value: Any = if (row.getString("type") == "boolean") {
                    row.getBoolean("value")
                } else {
                    row.getString("value")
                }
                row.getString("key") to value
            }
            check(readSelectedPreferences(defaultPrefsName(), configKeys) == expected)
            saved.forEach { row ->
                if (row.getString("type") == "boolean") {
                    val pref = booleanPrefs.single { it.key() == row.getString("key") }
                    check(pref.isSet() == row.getBoolean("contains") && pref.get() == row.getBoolean("value"))
                } else {
                    val pref = stringPrefs.single { it.key() == row.getString("key") }
                    check(pref.isSet() == row.getBoolean("contains") && pref.get() == row.getString("value"))
                }
            }
            requireCustomCredentialEmptyAndUnchanged()
        }

        fun requireNoDownloads() {
            val downloads = Injekt.get<DownloadManager>()
            check(downloads.queueState.value.isEmpty() && !downloads.isRunning)
        }

        fun requireNoTranslationWorkers() {
            check(TAGS.all { work(it).all { info -> info.state.isFinished } })
            check(!hasProgressNotification())
        }

        fun requireColdService() {
            check(!service.warm.value && !service.loading.value && TranslationBusy.active.value == 0)
            check(serviceField("engine").get(service) == null)
            check((serviceField("inFlight").get(service) as AtomicInteger).get() == 0)
        }

        fun assertLocalDirectory(chapter: Chapter) {
            val source = Injekt.get<SourceManager>().get(LocalSource.ID) as LocalSource
            val format = source.getFormat(chapter.toSChapter())
            check(format is Format.Directory && format.file.uri == directory().uri)
        }

        fun directory() = checkNotNull(UniFile.fromFile(chapterDirectory))
        fun store() = PartialPageStore.forUniFile(
            context,
            directory(),
            checkNotNull(directory().findFile(".yakuyomi")),
        )
        fun completedPages(): Set<String> = CompletionManifestCodec.resolve(
            chapterDirectory.listFiles().orEmpty().filter {
                it.name == CompletionManifestCodec.PRIMARY || it.name == CompletionManifestCodec.PREVIOUS ||
                    CompletionManifestCodec.isCheckpoint(it.name)
            }.associate { it.name to it.readBytes() },
        ).also { pages ->
            val primary = File(chapterDirectory, CompletionManifestCodec.PRIMARY)
            check(CompletionManifestCodec.decode(primary.readBytes()) == pages)
            check(
                chapterDirectory.listFiles().orEmpty().filter { CompletionManifestCodec.isCheckpoint(it.name) }
                    .any { CompletionManifestCodec.decode(it.readBytes()) == pages },
            )
        }

        suspend fun joinDrains() {
            withTimeout(20_000) {
                drainMutex.withLock { Unit }
                (managerField("nightDrainMutex").get(manager) as Mutex).withLock { Unit }
            }
            check(TranslationBusy.active.value == 0)
        }

        fun work(tag: String): List<WorkInfo> = workManager.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS)

        suspend fun recordPreparedWorkOwnership(ledger: JSONObject) {
            // Held under the real drain mutex immediately after this UUID's public enqueue.
            // Initial gates proved no nonterminal translation work or foreign queue/downloads.
            awaitCondition {
                jsonObjects(ownWorkSnapshot(ledger)).any { it.getString("tag") == "Translator" }
            }
            val ids = jsonObjects(ownWorkSnapshot(ledger)).map { it.getString("id") }.distinct().sorted()
            ledger.put("preparedOwnWorkIds", JSONArray(ids))
            writeLedger(ledger)
        }

        private fun knownOwnedWorkIds(ledger: JSONObject): Set<String> = buildSet {
            val prepared = ledger.getJSONArray("preparedOwnWorkIds")
            for (index in 0 until prepared.length()) add(prepared.getString(index))
            if (ledger.has("activeWork")) {
                jsonObjects(ledger.getJSONArray("activeWork")).forEach {
                    add(it.getString("id"))
                }
            }
        }.also { ids -> ids.forEach { check(UUID.fromString(it).toString() == it) } }

        private fun requireOnlyKnownNonterminalWork(ledger: JSONObject) {
            val known = knownOwnedWorkIds(ledger)
            check(TAGS.all { tag -> work(tag).all { it.state.isFinished || it.id.toString() in known } }) {
                "Unattributed nonterminal translation work; preserve it and the owned recovery ledger"
            }
        }

        private suspend fun requireOwnedOrEmptyPersistedQueue(ledger: JSONObject) {
            requireNoDownloads()
            val marks = TranslationStore(context).restorePendingMarks(Long.MIN_VALUE)
            check(marks.translate.isEmpty() && marks.night.isEmpty())
            check(QUEUE_SETS.all { queuePrefs.getStringSet(it, emptySet()).orEmpty().isEmpty() })
            val chapterId = if (ledger.isNull("chapterId")) null else ledger.getLong("chapterId")
            val restored = TranslationStore(context).restore()
            check(
                restored.all {
                    it.chapter.id == chapterId && it.manga.url == title && it.manga.source == LocalSource.ID
                },
            )
            check(queuePrefs.all.keys.all { it in QUEUE_BOOLEANS || it in QUEUE_SETS || it == "0" })
            if (queuePrefs.contains("0")) {
                check(chapterId != null && !ledger.isNull("mangaId"))
                val row = JSONObject(checkNotNull(queuePrefs.getString("0", null)))
                check(row.getLong("chapterId") == chapterId && row.getLong("mangaId") == ledger.getLong("mangaId"))
                check(row.getString("method") == METHOD)
                check(!row.has("nightRender") || !row.getBoolean("nightRender"))
            }
            requireOnlyKnownNonterminalWork(ledger)
        }

        suspend fun cancelOnlyNewWork(ledger: JSONObject) {
            requireOnlyKnownNonterminalWork(ledger)
            val known = knownOwnedWorkIds(ledger)
            val prior = ledger.getJSONObject("beforeWork")
            for (tag in TAGS) {
                val previous = prior.getJSONArray(tag)
                val ids = (0 until previous.length()).map { previous.getString(it) }.toSet()
                work(tag).filter {
                    it.id.toString() in known && it.id.toString() !in ids && !it.state.isFinished
                }.forEach {
                    workManager.cancelWorkById(it.id).result.get(5, TimeUnit.SECONDS)
                }
            }
            awaitCondition {
                requireOnlyKnownNonterminalWork(ledger)
                TAGS.all { work(it).all { info -> info.state.isFinished } } && !hasProgressNotification()
            }
        }

        suspend fun cleanup(ledger: JSONObject, standalone: Boolean = false): JSONObject {
            val receiptFile = File(output, if (standalone) "cleanup-reentry.json" else "cleanup.json")
            val receipt = JSONObject().put("schema", 1).put("uuid", id).put("passed", false)
                .put("pid", Process.myPid()).put("reportsRetainedForHostExport", true)
                .put("ledgerSha256", sha(ledgerFile.readBytes())).put("standaloneCleanup", standalone)
                .put("originalTargetApkSha256", ledger.getString("targetApkSha256"))
                .put("originalRunnerApkSha256", ledger.getString("runnerApkSha256"))
                .put("cleanupTargetApkSha256", shaFile(File(context.applicationInfo.sourceDir)))
                .put("cleanupRunnerApkSha256", shaFile(File(instrumentation.context.applicationInfo.sourceDir)))
                .put("cleanupRequiresSameApk", false)
            // Reject unrelated work before any cancellation, settings restoration or DB mutation.
            requireStorageRestorePrecondition(ledger)
            requirePreferenceRestorePrecondition(ledger)
            requireModelsUnchanged(ledger)
            requireOwnedOrEmptyPersistedQueue(ledger)
            requireNoDownloads()
            val chapterId = if (ledger.isNull("chapterId")) null else ledger.getLong("chapterId")
            withTimeout(20_000) { manager.ensureRestored() }
            check(manager.queueState.value.all { it.chapter.id == chapterId && it.manga.url == title })
            preferences.translationMasterEnabled.set(false)
            preferences.nightReadEnabled.set(false)
            manager.haltForMasterOff()
            chapterId?.let { manager.cancelPendingTranslation(it) }
            joinDrains()
            service.shutdown()
            requireColdService()
            cancelOnlyNewWork(ledger)
            check(manager.queueState.value.isEmpty())
            val chapters = Injekt.get<ChapterRepository>()
            val mangas = Injekt.get<MangaRepository>()
            chapterId?.let { id ->
                chapters.getChapterById(id)?.let {
                    check(it.mangaId == ledger.getLong("mangaId") && it.url == "$title/Chapter")
                    chapters.removeChaptersWithIds(listOf(id))
                }
                check(chapters.getChapterById(id) == null)
                manager.forgetChapterOutputs(listOf(id))
            }
            if (!ledger.isNull("mangaId")) {
                val id = ledger.getLong("mangaId")
                // Querying by exact owned URL makes cleanup repeatable after a successful phase B.
                val own = mangas.getMangaByUrlAndSourceId(title, LocalSource.ID)
                if (own != null) {
                    check(own.id == id && own.source == LocalSource.ID && own.url == title)
                    val driver = Injekt.get<SqlDriver>()
                    val removedRow = driver.execute(
                        null,
                        "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?",
                        3,
                    ) {
                        bindLong(0, id)
                        bindLong(1, LocalSource.ID)
                        bindString(2, title)
                    }.await()
                    check(removedRow == 1L)
                    driver.notifyListeners("mangas", "chapters", "history")
                    Injekt.get<TranslationCache>().invalidate(id)
                }
                check(mangas.getMangaByUrlAndSourceId(title, LocalSource.ID) == null)
            }
            // File deletion is explicit, canonical-bound, symlink-free and limited to this UUID.
            if (book.exists()) receipt.put("ownFilesBeforeDelete", fileHashes(book))
            val removed = if (book.exists()) deleteOwnedTree(book, localParent) else 0
            check(!book.exists())
            restoreStatistics(ledger)
            restoreOwnedStorage(ledger, receipt)
            // Restore pool values while both master gates are closed and the actual queue is empty.
            setPoolPausedField("_isTranslatePaused", ledger.getBoolean("poolPaused"))
            setPoolPausedField("_isNightPaused", ledger.getBoolean("nightPoolPaused"))
            restoreEmptyQueue(ledger)
            restorePreferences(ledger)
            requireModelsUnchanged(ledger)
            service.shutdown()
            requireColdService()
            receipt.put("ownDbRowsAbsent", true).put("ownQueueAbsent", true).put("ownWorkTerminal", true)
                .put("ownLocalFilesRemoved", removed).put("ownLocalDirectoryAbsent", true)
                .put("boundedDailyStatisticsRestored", true)
                .put("allTemporaryPreferenceValuesPresenceAndDiskRestored", true)
                .put("originalCustomSlotPresenceAndEmptyBackingPreserved", true)
                .put("hostInputNotDeletedByTest", true)
                .put("emptyQueueStructureAndPresenceRestored", true).put("serviceColdIdle", true)
                .put("modelsOrUserHistoryEdited", false).put("passed", true)
            writeDurable(receiptFile, receipt.toString(2).toByteArray())
            return receipt
        }

        private fun setPoolPausedField(name: String, value: Boolean) {
            @Suppress("UNCHECKED_CAST")
            (managerField(name).get(manager) as MutableStateFlow<Boolean>).value = value
        }

        private fun restoreEmptyQueue(ledger: JSONObject) {
            // Recheck immediately before writing: never overwrite another pending download mark,
            // even when the live translation queue happens to be empty during cleanup reentry.
            requireNoDownloads()
            val marks = TranslationStore(context).restorePendingMarks(Long.MIN_VALUE)
            check(marks.translate.isEmpty() && marks.night.isEmpty())
            check(QUEUE_SETS.all { queuePrefs.getStringSet(it, emptySet()).orEmpty().isEmpty() })
            check(manager.queueState.value.isEmpty())
            check(queuePrefs.all.keys.all { it in QUEUE_BOOLEANS || it in QUEUE_SETS })
            val edit = queuePrefs.edit()
            jsonObjects(ledger.getJSONArray("queueBooleans")).forEach {
                val key = it.getString("key")
                check(key in QUEUE_BOOLEANS)
                if (it.getBoolean("contains")) edit.putBoolean(key, it.getBoolean("value")) else edit.remove(key)
            }
            val present = ledger.getJSONArray("queueEmptySetPresence")
            val sets = (0 until present.length()).map { present.getString(it) }.toSet()
            check(sets.all { it in QUEUE_SETS })
            QUEUE_SETS.forEach { if (it in sets) edit.putStringSet(it, emptySet()) else edit.remove(it) }
            check(edit.commit())
            val expected = buildMap<String, Any> {
                jsonObjects(ledger.getJSONArray("queueBooleans")).filter { it.getBoolean("contains") }.forEach {
                    put(it.getString("key"), it.getBoolean("value"))
                }
                sets.forEach { put(it, emptySet<String>()) }
            }
            check(readSelectedPreferences("active_translations", QUEUE_BOOLEANS.toSet() + QUEUE_SETS) == expected)
        }

        private fun hasProgressNotification() = context.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == Notifications.ID_TRANSLATION_PROGRESS }
    }

    private fun gate() {
        check(context.packageName == AUDIT_PACKAGE && Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
        check(arguments.getString("activeNativeRecovery") == "true") {
            "Explicit activeNativeRecovery=true required; missing opt-in is not a pass/skip"
        }
    }

    private fun uuid(): String = checkNotNull(arguments.getString("activeNativeUuid")).also {
        check(UUID.fromString(it).toString() == it && it.matches(Regex(UUID_PATTERN)))
    }

    private fun status(value: JSONObject) = instrumentation.sendStatus(
        2,
        Bundle().apply { putString("stream", "ACTIVE_NATIVE_RECOVERY=$value\n") },
    )

    private fun defaultPrefsName() = "${context.packageName}_preferences"

    /** Read ONLY the requested keys. No full preference dump or credentials are returned/stored. */
    private fun readSelectedPreferences(name: String, keys: Set<String>): Map<String, Any> {
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
        if (!file.exists()) return emptyMap()
        val selected = mutableMapOf<String, Any>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    val key = parser.getAttributeValue(null, "name")
                    if (key in keys) {
                        selected[checkNotNull(key)] = when (parser.name) {
                            "boolean" -> parser.getAttributeValue(null, "value").toBooleanStrict()
                            "string" -> parser.nextText()
                            "set" -> buildSet<String> {
                                val depth = parser.depth
                                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                                    if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
                                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string") {
                                        add(parser.nextText())
                                    }
                                }
                            }
                            else -> error("Unexpected whitelisted preference type")
                        }
                    }
                }
                parser.next()
            }
        }
        return selected
    }

    companion object {
        private const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        private const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        private const val METHOD = "boxfill"
        private const val MAX_LEDGER_BYTES = 128 * 1024L
        private const val MAX_RECEIPT_BYTES = 16 * 1024L
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024L
        private const val MAX_IMAGE_PIXELS = 16 * 1024 * 1024L
        private val SHA_PATTERN = Regex("[a-f0-9]{64}")
        private val HANGUL = Regex("[가-힣]")
        private val TAGS = listOf("Translator", "TranslatorHeartbeat")
        private val QUEUE_BOOLEANS = listOf("paused", "paused_night")
        private val QUEUE_SETS = listOf("pending_translate", "pending_night")
        private val STORAGE_DIRECTORIES = setOf("autobackup", "downloads", "local")
        private val MODEL_NAMES = listOf(
            "dbnet_detect.ncnn.param",
            "dbnet_detect.ncnn.bin",
            "ocr_48px_ctc.ncnn.param",
            "ocr_48px_ctc_mixed.ncnn.param",
            "ocr_48px_ctc.ncnn.bin",
            "mit_aot_fixed512.ncnn.param",
            "mit_aot_fixed512.ncnn.bin",
        )
        private val INPUT_SHA = mapOf(
            "001.png" to "00a1c4e367d9ca33e50944bbefaa039dd1ab39ffd3b9460c944d262cbed05e2d",
            "002.png" to "281ffb1cc92aa692bddf6ea1429ecbb32a3ea6cfc974c09329154abd628010f3",
        )
        private val EXPECTED_SOURCE = mapOf(
            "001.png" to listOf("私はここにいます"),
            "002.png" to listOf("少し待って、ください。", "「明日もよろしくお願いします。」"),
        )

        /** Cross-language stable digest: sorted lowercase SHA256(text UTF8), joined with LF, no final LF. */
        private fun sourceSetSha(texts: List<String>): String = sha(
            texts.map { sha(it.toByteArray(Charsets.UTF_8)) }.sorted().joinToString("\n")
                .toByteArray(Charsets.US_ASCII),
        )
        private fun jsonStringMap(value: JSONObject): Map<String, String> = value.keys().asSequence()
            .associateWith { value.getString(it) }
        private fun isManifest(name: String) = name == CompletionManifestCodec.PRIMARY ||
            name == CompletionManifestCodec.PREVIOUS || CompletionManifestCodec.isCheckpoint(name)
        private fun readBoundedJson(file: File, parent: File, limit: Long): JSONObject {
            check(parent.canonicalFile == parent.absoluteFile && parent.isDirectory)
            check(file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == parent)
            check(file.isFile && file.length() in 1..limit)
            return JSONObject(file.readText(Charsets.UTF_8))
        }
        private fun imageBounds(file: File): Pair<Int, Int> {
            check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() in 1..MAX_FILE_BYTES)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            check(options.outMimeType == "image/png" && options.outWidth > 0 && options.outHeight > 0)
            check(options.outWidth.toLong() * options.outHeight <= MAX_IMAGE_PIXELS)
            return options.outWidth to options.outHeight
        }
        private fun serviceField(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        private fun managerField(name: String) = TranslationManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        private suspend fun awaitCondition(condition: () -> Boolean) {
            withTimeout(30_000) {
                while (!condition()) {
                    currentCoroutineContext().ensureActive()
                    delay(50)
                }
            }
        }
        private fun processStartTicks(): String {
            // comm may contain spaces/parentheses; field 22 is offset 19 after the final ')'.
            val stat = File("/proc/self/stat").readText()
            return stat.substring(stat.lastIndexOf(')') + 2).split(' ')[19].also { check(it.toLong() > 0) }
        }
        private fun jsonObjects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun shaFile(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun writeDurable(file: File, bytes: ByteArray) {
            val parent = checkNotNull(file.parentFile).canonicalFile
            check(file.absoluteFile == file.canonicalFile && file.canonicalFile.parentFile == parent)
            val temporary = File(parent, "${file.name}.writing")
            check(!temporary.exists())
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            check(temporary.renameTo(file))
            // Opening a directory read-only needs no O_DIRECTORY constant (not exposed by Android).
            val descriptor = Os.open(parent.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
            check(file.readBytes().contentEquals(bytes))
        }
        private fun fileHashes(root: File): JSONObject {
            val hashes = linkedMapOf<String, String>()
            fun scan(directory: File) {
                check(directory.canonicalFile == directory.absoluteFile)
                directory.listFiles()!!.sortedBy { it.name }.forEach {
                    check(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == directory)
                    if (it.isDirectory) {
                        scan(it)
                    } else {
                        check(it.isFile && it.length() <= MAX_FILE_BYTES)
                        hashes[it.relativeTo(root).invariantSeparatorsPath] = shaFile(it)
                    }
                }
            }
            scan(root)
            return JSONObject(hashes)
        }
        private fun firstPageFiles(root: File): Map<String, String> = fileHashes(root).let { all ->
            val materials = File(root, ".yakuyomi").listFiles().orEmpty().map { it.name }.toSet()
            val journalNames = PartialPageStore.artifactNames("001.png", materials)
            all.keys().asSequence().filter {
                val basename = it.substringAfterLast('/')
                it == "001.png" || basename.startsWith("001.") || basename in journalNames
            }
                .associateWith { all.getString(it) }
        }
        private fun deleteOwnedTree(root: File, parent: File): Int {
            check(root.parentFile == parent && root.canonicalFile == root.absoluteFile)
            val files = mutableListOf<File>()
            val directories = mutableListOf<File>()
            fun visit(directory: File) {
                check(directory.canonicalFile == directory.absoluteFile)
                directory.listFiles()!!.forEach {
                    check(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == directory)
                    if (it.isDirectory) {
                        visit(it)
                    } else {
                        check(it.isFile)
                        files += it
                    }
                }
                directories += directory
            }
            visit(root) // Validate the entire owned boundary before the first explicit delete.
            val hashes = files.associateWith { shaFile(it) }
            files.forEach {
                check(shaFile(it) == hashes.getValue(it))
                check(it.delete() && !it.exists())
            }
            directories.forEach { check(it.list()!!.isEmpty() && it.delete() && !it.exists()) }
            return files.size
        }
    }
}
