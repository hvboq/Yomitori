package mihon.translation

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
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
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStatsStore
import eu.kanade.tachiyomi.data.translation.TranslationStore
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import kotlinx.coroutines.CompletableDeferred
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two instrumentation invocations separated by a HOST SIGKILL, plus an idempotent cleanup entry.
 * A remains alive after READY and is expected to lose its runner connection, never to pass JUnit.
 * B checks a new OS process and the exact frozen ledger, then uses the real singleton/public queue.
 * Scripted inference, real LocalSource/PageTranslator/journals/WorkManager. No model/HTTP/Activity.
 * This covers a quiescent paused checkpoint, not in-flight torn writes or automatic worker revival.
 */
@RunWith(AndroidJUnit4::class)
class TranslationProcessDeathRecoveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test(timeout = 720_000)
    fun preparePausedCheckpointAndWaitForHostKill(): Unit = runBlocking {
        gate()
        val harness = Harness(uuid())
        harness.requireInitiallyIdle()
        check(!harness.output.exists()) { "The explicit UUID must be new" }
        check(!harness.book.exists()) { "The neutral LocalSource UUID must be new" }
        check(harness.output.mkdir())
        // Persist the rollback information before the first setting, file, DB or queue change.
        val ledger = harness.initialLedger()
        harness.writeLedger(ledger)
        try {
            harness.closeMasterGates()
            harness.prepareOwnedStorage(ledger)
            check(harness.book.mkdir())
            check(harness.chapterDirectory.mkdir())
            writeImage(File(harness.chapterDirectory, "001.png"), Color.WHITE)
            writeImage(File(harness.chapterDirectory, "002.png"), Color.BLUE)
            writeDurable(File(harness.chapterDirectory, "note.txt"), NOTE)
            ledger.put("sourceFiles", fileHashes(harness.chapterDirectory))
            harness.writeLedger(ledger)
            val mangas = Injekt.get<MangaRepository>()
            val chapters = Injekt.get<ChapterRepository>()
            val manga = withContext(NonCancellable) {
                mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(source = LocalSource.ID, url = harness.title, title = harness.title),
                    ),
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
            val fake = CheckpointEngine(resuming = false)
            harness.withPrivateStats {
                harness.drainMutex.withLock {
                    harness.installFake(fake)
                    val completed = harness.translator.translateChapter(
                        harness.directory(),
                        METHOD,
                        nightMaterials = false,
                    )
                    check(completed == 1)
                }
            }
            check(fake.firstFresh.get() == 1 && fake.secondFresh.get() == 1 && fake.resume.get() == 0)
            harness.service.shutdown()
            harness.requireColdService()
            val pending = harness.pendingState()
            val progress = OcrProgressCodec.decode(pending.progressPayload, SIZE, SIZE)
            check(progress == pendingProgress() && progress.pendingRegionIds == listOf(1))
            check(!pending.complete && harness.completedPages() == setOf("001.png"))
            check(harness.translator.hasPartialProgress(harness.directory()))
            check(!harness.translator.isChapterTranslated(harness.directory()))
            check(pending.safeContextPayload != null)
            // The actual public enqueue is paused while the real drain mutex is held. Opening the
            // master gate never permits inference between enqueue and the chapter/pool pauses.
            harness.drainMutex.withLock {
                harness.preferences.translationMasterEnabled.set(true)
                harness.manager.translate(manga, listOf(chapter), method = METHOD)
                harness.manager.pauseChapter(chapter.id, TranslationItem.Kind.TRANSLATE)
                harness.manager.pause(false)
                harness.preferences.translationMasterEnabled.set(false)
                harness.manager.haltForMasterOff()
            }
            harness.joinDrains()
            harness.cancelOnlyNewWork(ledger)
            harness.requirePaused(chapter.id)
            harness.requireColdService()
            harness.persistedGateAndQueueBarrier(chapter.id)
            harness.requirePersistedOwnedStorage(ledger)
            // Sources, completed output/materials and pending journal/mask are independently read.
            harness.verifyOriginals(ledger)
            ledger.put("checkpointFiles", fileHashes(harness.chapterDirectory))
                .put(
                    "successfulRegion",
                    OcrProgressCodec.encode(
                        progress.copy(regions = listOf(progress.regions[0])),
                    ),
                )
                .put("pendingProgress", pending.progressPayload)
                .put("pendingOriginalSha256", pending.original.sha256)
                .put("pendingRawMaskSha256", sha(harness.store().readRawMask(pending)))
                .put("seedFreshCalls", 2).put("seedResumeCalls", 0)
                .put("stage", "ARMED_PAUSED_CHECKPOINT")
                .put("armedAtElapsedMs", SystemClock.elapsedRealtime())
            harness.writeLedger(ledger)
            val ledgerSha = sha(harness.ledgerFile.readBytes())
            val ready = JSONObject().put("schema", 1).put("uuid", harness.id)
                .put("phase", "READY_FOR_HOST_SIGKILL").put("ledgerSha256", ledgerSha)
                .put("pid", Process.myPid()).put("processStartTicks", processStartTicks())
                .put("pausedCheckpointVerified", true).put("masterAndNightDiskOff", true)
                .put("ownedStoragePersistedAndEffective", true)
                .put("normalJUnitPassExpected", false)
            writeDurable(File(harness.output, "ready.json"), ready.toString(2).toByteArray())
            status(ready)
            // Do not return, clean up, force-stop, or impersonate process death in this method.
            // Root observes PID/start identity, sends SIGKILL and records the runner interruption.
            withTimeout(600_000) {
                var lastStatus = SystemClock.elapsedRealtime()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    delay(200)
                    if (SystemClock.elapsedRealtime() - lastStatus >= 5_000) {
                        check(sha(harness.ledgerFile.readBytes()) == ledgerSha)
                        harness.requirePaused(chapter.id)
                        harness.requireColdService()
                        status(ready)
                        lastStatus = SystemClock.elapsedRealtime()
                    }
                }
            }
            error("Armed preparation cannot complete normally")
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { harness.cleanup(ledger) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    @Test(timeout = 180_000)
    fun resumePausedCheckpointInNewProcess(): Unit = runBlocking {
        gate()
        val harness = Harness(uuid())
        val ledger = harness.readLedger(requireSameApks = true)
        val report = JSONObject().put("schema", 1).put("uuid", harness.id).put("passed", false)
            .put("pid", Process.myPid()).put("processStartTicks", processStartTicks())
            .put("preparedPid", ledger.getInt("pid"))
            .put("preparedProcessStartTicks", ledger.getString("processStartTicks"))
            .put("ledgerSha256", sha(harness.ledgerFile.readBytes()))
            .put("hostSigkillProofIsSeparateReceipt", true)
            .put("automaticWorkManagerRestartTested", false).put("inFlightTornWriteTested", false)
            .put("pendingMeaning", "Paused queue entry plus page 002 OCR journal")
            .put("downloaderPendingMarksTested", false)
            .put("scriptedEngineNativeCalls", 0).put("startupNativeCallCountInstrumented", false)
            .put("llmCalls", 0).put("actualReaderActivity", false)
        val fake = CheckpointEngine(resuming = true)
        var failure: Throwable? = null
        try {
            check(ledger.getString("stage") == "ARMED_PAUSED_CHECKPOINT")
            check(ledger.getInt("pid") != Process.myPid()) { "Phase B requires a different actual OS PID" }
            check(ledger.getString("processStartTicks") != processStartTicks())
            harness.requireMasterGatesOffOnDisk()
            harness.requirePersistedOwnedStorage(ledger)
            harness.manager.ensureRestored() // Real startup singleton; never construct a substitute manager.
            val chapterId = ledger.getLong("chapterId")
            harness.requirePaused(chapterId)
            harness.requireColdService()
            harness.requireNoDownloads()
            harness.requireNoTranslationWorkers()
            withTimeout(10_000) { Injekt.get<SourceManager>().isInitialized.first { it } }
            harness.assertLocalDirectory(
                checkNotNull(Injekt.get<ChapterRepository>().getChapterById(chapterId)),
            )
            val checkpoint = fileHashes(harness.chapterDirectory)
            check(checkpoint.toString() == ledger.getJSONObject("checkpointFiles").toString())
            harness.verifyOriginals(ledger)
            val old = harness.pendingState()
            check(!old.complete && old.progressPayload == ledger.getString("pendingProgress"))
            val savedSuccessfulRegion = OcrProgressCodec.decode(
                ledger.getString("successfulRegion"),
                SIZE,
                SIZE,
            ).regions.single()
            check(savedSuccessfulRegion == OcrProgressCodec.decode(old.progressPayload, SIZE, SIZE).regions[0])
            check(sha(harness.store().readRawMask(old)) == ledger.getString("pendingRawMaskSha256"))
            check(harness.completedPages() == setOf("001.png"))
            val beforeFirst = firstPageFiles(harness.chapterDirectory)
            val secondPage = File(harness.chapterDirectory, "002.png")
            val beforeSuccessfulPixels = savedSuccessfulRegionPixelHash(secondPage)
            report.put("preInjectionRestoredPausedQueue", true).put("preInjectionEngineAbsentColdAndIdle", true)
                .put("preInjectionFilesUnchanged", true).put("preInjectionMasterAndNightDiskOff", true)
                .put("preInjectionTranslationWorkersTerminal", true)
                .put("preInjectionColdStartStoragePreferenceDiskAndEffectiveBaseMatch", true)
                .put("preInjectionScope", "Persisted gates, cold service, unchanged files; host kill is external")
            val beforeResumeWork = harness.work("Translator").map { it.id }.toSet()
            harness.withPrivateStats(release = { fake.releaseResume.complete(Unit) }) {
                harness.drainMutex.withLock {
                    harness.installFake(fake)
                    harness.preferences.translationMasterEnabled.set(true)
                    harness.manager.resumeChapter(chapterId, TranslationItem.Kind.TRANSLATE)
                }
                withTimeout(20_000) { fake.resumeEntered.await() }
                awaitCondition {
                    harness.work("Translator").any {
                        it.id !in beforeResumeWork && it.state == WorkInfo.State.RUNNING
                    }
                }
                val newWork = harness.work("Translator").filter { it.id !in beforeResumeWork }
                check(newWork.isNotEmpty())
                report.put(
                    "newPublicResumeWork",
                    JSONArray(
                        newWork.map {
                            JSONObject().put("id", it.id.toString()).put("observedStateBeforeRelease", it.state.name)
                        },
                    ),
                )
                fake.releaseResume.complete(Unit)
                awaitCondition {
                    harness.manager.queueState.value.none { it.chapter.id == chapterId } &&
                        TranslationBusy.active.value == 0 && fake.active.get() == 0 &&
                        harness.translator.isChapterTranslated(harness.directory())
                }
                harness.joinDrains()
                awaitCondition {
                    harness.work("Translator").filter { it.id !in beforeResumeWork }.all { it.state.isFinished }
                }
                val finalWork = harness.work("Translator").filter { it.id !in beforeResumeWork }
                check(finalWork.any { it.state == WorkInfo.State.SUCCEEDED })
                check(finalWork.none { it.state == WorkInfo.State.FAILED })
                report.put(
                    "newPublicResumeWorkFinal",
                    JSONArray(
                        finalWork.map {
                            JSONObject().put("id", it.id.toString()).put("state", it.state.name)
                        },
                    ),
                )
            }
            check(fake.firstFresh.get() == 0 && fake.secondFresh.get() == 0 && fake.resume.get() == 1)
            check(fake.resumedRegionIds == listOf(1))
            check(beforeSuccessfulPixels == savedSuccessfulRegionPixelHash(secondPage))
            check(beforeFirst == firstPageFiles(harness.chapterDirectory))
            check(harness.completedPages() == setOf("001.png", "002.png"))
            val after = harness.pendingState()
            val afterProgress = OcrProgressCodec.decode(after.progressPayload, SIZE, SIZE)
            check(after.complete && !afterProgress.hasPending && afterProgress == finalProgress())
            check(afterProgress.regions[0] == savedSuccessfulRegion)
            check(after.original == old.original && after.safeContextPayload == old.safeContextPayload)
            check(sha(harness.store().readRawMask(after)) == ledger.getString("pendingRawMaskSha256"))
            harness.verifyOriginals(ledger)
            check(File(harness.chapterDirectory, "note.txt").readBytes().contentEquals(NOTE))
            check(sha(harness.ledgerFile.readBytes()) == report.getString("ledgerSha256"))
            report.put("differentPidAndStartIdentity", true).put("freshCalls", 0).put("resumeCalls", 1)
                .put("scriptedPendingRegionIdsRetried", JSONArray(fake.resumedRegionIds))
                .put("successfulRegionExact", true).put("successfulRegionRenderedPixelsExact", true)
                .put("completedPageFilesExact", true)
                .put("originalsAndPendingRawMaskExact", true).put("unrelatedNoteExact", true)
                .put("completedPages", JSONArray(harness.completedPages().sorted()))
                .put("chapterCompleted", true).put("manualPublicResumeVerified", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failureClass", error.javaClass.name)
        } finally {
            fake.releaseResume.complete(Unit)
            withContext(NonCancellable) {
                try {
                    report.put("cleanup", harness.cleanup(ledger))
                } catch (error: Throwable) {
                    report.put("cleanupFailureClass", error.javaClass.name)
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
                report.put("passed", failure == null)
                writeDurable(File(harness.output, "result.json"), report.toString(2).toByteArray())
                status(
                    JSONObject().put("phase", if (failure == null) "RESUME_PASSED" else "RESUME_FAILED")
                        .put("uuid", harness.id).put("report", File(harness.output, "result.json").absolutePath),
                )
            }
        }
        failure?.let { throw it }
    }

    @Test(timeout = 120_000)
    fun cleanupOwnedCheckpoint(): Unit = runBlocking {
        gate()
        val harness = Harness(uuid())
        // Recovery must remain available after fixing/rebuilding a failed harness. The explicit
        // ledger SHA, package/UID, UUID boundary and own DB identity still gate every cleanup.
        val ledger = harness.readLedger(requireSameApks = false)
        val receipt = withContext(NonCancellable) { harness.cleanup(ledger, standalone = true) }
        check(receipt.getBoolean("passed"))
        status(
            JSONObject().put("phase", "CLEANUP_PASSED").put("uuid", harness.id)
                .put("report", File(harness.output, "cleanup-reentry.json").absolutePath),
        )
    }

    private inner class Harness(val id: String) {
        val preferences = Injekt.get<TranslationPreferences>()
        val manager = Injekt.get<TranslationManager>()
        val service = Injekt.get<TranslationEngineService>()
        val title = "Process death fixture $id"
        private val externalParent = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val output = File(externalParent, "translation-process-death-$id")
        val ledgerFile = File(output, "ledger.json")
        private val storageManager = Injekt.get<StorageManager>()
        private val storagePreference = Injekt.get<StoragePreferences>().baseStorageDirectory
        private val storageRoot = File(output, "storage")
        private val storageUri = Uri.fromFile(storageRoot)
        private val localParent = File(storageRoot, "local")
        private val defaultStorageUri = Uri.parse(storagePreference.defaultValue()).also {
            check(it.scheme == "file") { "This audit requires a file-backed default storage boundary" }
        }
        private val defaultDirectory = File(checkNotNull(defaultStorageUri.path)).canonicalFile
        val book = File(localParent, title)
        val chapterDirectory = File(book, "Chapter")
        val translator = managerField("pageTranslator").get(manager) as PageTranslator
        val drainMutex = managerField("drainMutex").get(manager) as Mutex
        private val statsName = "process-death-stats-$id"
        private val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        private val queuePrefs = context.getSharedPreferences("active_translations", Context.MODE_PRIVATE)
        private val workManager = WorkManager.getInstance(context)
        private val switches get() = listOf(preferences.translationMasterEnabled, preferences.nightReadEnabled)
        private val statsField = PageTranslator::class.java.getDeclaredField("statsStore").apply {
            isAccessible = true
        }
        private var previousStats: Any? = null
        private var statsInjected = false

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
            check(!File(context.applicationInfo.dataDir, "shared_prefs/$statsName.xml").exists())
            // This harness deliberately handles the unconfigured audit state, without creating
            // the shared/default Yomitori directory or switching away from a user's valid tree.
            requireOriginalStorageBoundary()
            check(TranslationEngineConfig.modelsResolvable(context)) {
                "Existing public model-presence gate is required"
            }
            check(book.parentFile == localParent && book.canonicalFile == book.absoluteFile)
            check(output.canonicalFile == output.absoluteFile && output.parentFile == externalParent)
            withTimeout(10_000) { Injekt.get<SourceManager>().isInitialized.first { it } }
        }

        fun initialLedger() = JSONObject().put("schema", 1).put("uuid", id).put("package", context.packageName)
            .put("pid", Process.myPid()).put("processStartTicks", processStartTicks())
            .put("uid", Process.myUid()).put("stage", "PREPARING").put("title", title)
            .put("targetApkSha256", shaFile(File(context.applicationInfo.sourceDir)))
            .put("runnerApkSha256", shaFile(File(instrumentation.context.applicationInfo.sourceDir)))
            .put("localParentPathSha256", sha(localParent.path.toByteArray()))
            .put("storage", originalStorageSnapshot())
            .put(
                "switches",
                JSONArray(
                    switches.map {
                        JSONObject().put("key", it.key()).put("contains", it.isSet()).put("value", it.get())
                    },
                ),
            )
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
            .put("poolPaused", manager.isTranslatePaused.value)
            .put("nightPoolPaused", manager.isNightPaused.value)
            .put(
                "beforeWork",
                JSONObject(
                    TAGS.associateWith { tag ->
                        JSONArray(work(tag).map { it.id.toString() })
                    },
                ),
            )
            .put("mangaId", JSONObject.NULL).put("chapterId", JSONObject.NULL)

        fun writeLedger(ledger: JSONObject) = writeDurable(ledgerFile, ledger.toString(2).toByteArray())

        fun readLedger(requireSameApks: Boolean): JSONObject {
            check(output.isDirectory && output.canonicalFile == output.absoluteFile)
            check(ledgerFile.isFile && ledgerFile.length() in 1..MAX_LEDGER_BYTES)
            check(ledgerFile.canonicalFile.parentFile == output)
            check(ledgerFile.canonicalFile == ledgerFile.absoluteFile)
            val bytes = ledgerFile.readBytes()
            val expected = checkNotNull(arguments.getString("translationProcessDeathLedgerSha256"))
            check(expected.matches(Regex("[a-f0-9]{64}")) && sha(bytes) == expected)
            return JSONObject(bytes.toString(Charsets.UTF_8)).also {
                check(it.getInt("schema") == 1 && it.getString("uuid") == id)
                check(it.getString("package") == context.packageName)
                check(it.getInt("uid") == Process.myUid() && it.getString("title") == title)
                if (requireSameApks) {
                    check(it.getString("targetApkSha256") == shaFile(File(context.applicationInfo.sourceDir)))
                    val runnerApk = File(instrumentation.context.applicationInfo.sourceDir)
                    check(it.getString("runnerApkSha256") == shaFile(runnerApk))
                }
                check(it.getString("localParentPathSha256") == sha(localParent.path.toByteArray()))
                validateStorageSnapshot(it.getJSONObject("storage"))
                check(it.getJSONArray("switches").length() == 2)
                val switchKeys = jsonObjects(it.getJSONArray("switches")).map { row -> row.getString("key") }
                check(switchKeys == switches.map { preference -> preference.key() })
            }
        }

        fun closeMasterGates() {
            preferences.translationMasterEnabled.set(false)
            preferences.nightReadEnabled.set(false)
            manager.haltForMasterOff()
            requireMasterGatesOffOnDisk()
        }

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

        fun requireMasterGatesOffOnDisk() {
            check(defaultPrefs.edit().commit())
            check(!preferences.translationMasterEnabled.get() && !preferences.nightReadEnabled.get())
            val expected = switches.associate { it.key() to false }
            check(readSelectedPreferences(defaultPrefsName(), expected.keys) == expected)
        }

        suspend fun persistedGateAndQueueBarrier(chapterId: Long) {
            requireMasterGatesOffOnDisk()
            check(context.getSharedPreferences(statsName, Context.MODE_PRIVATE).edit().commit())
            check(queuePrefs.edit().commit())
            val disk = readSelectedPreferences(
                "active_translations",
                setOf("0", "paused", "paused_night") + QUEUE_SETS,
            )
            check(disk["paused"] == true)
            check((disk["pending_translate"] as Set<*>).isEmpty() && (disk["pending_night"] as Set<*>).isEmpty())
            val item = JSONObject(disk.getValue("0") as String)
            check(item.getLong("chapterId") == chapterId)
            check(item.getString("method") == METHOD && item.getBoolean("paused"))
            val restored = TranslationStore(context).restore().single()
            check(restored.chapter.id == chapterId && restored.paused && !restored.nightRender)
        }

        fun requirePaused(chapterId: Long) {
            val item = manager.queueState.value.single()
            check(item.chapter.id == chapterId && item.manga.url == title && item.manga.source == LocalSource.ID)
            check(item.status == TranslationItem.Status.QUEUE && item.kind == TranslationItem.Kind.TRANSLATE)
            check(item.paused && item.method == METHOD && manager.isTranslatePaused.value)
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
        fun pendingState() = checkNotNull(store().load("002.png"))
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

        fun verifyOriginals(ledger: JSONObject) {
            val sources = ledger.getJSONObject("sourceFiles")
            for (name in listOf("001", "002")) {
                val original = File(chapterDirectory, ".yakuyomi/$name.orig.png")
                check(sha(original.readBytes()) == sources.getString("$name.png"))
            }
        }

        suspend fun joinDrains() {
            withTimeout(20_000) {
                drainMutex.withLock { Unit }
                (managerField("nightDrainMutex").get(manager) as Mutex).withLock { Unit }
            }
            check(TranslationBusy.active.value == 0)
        }

        fun installFake(engine: TranslationEngine) {
            requireColdService()
            // Existing service contract: compute its opaque signature internally; never export or
            // inspect it (it includes credentials). No endpoint, API key or server setting is edited.
            val signature = TranslationEngineService::class.java.getDeclaredMethod(
                "configSignature",
                String::class.java,
                TranslationPreferences::class.java,
            ).apply { isAccessible = true }.invoke(service, METHOD, preferences.snapshot())
            serviceField("engine").set(service, engine)
            serviceField("builtSignature").set(service, signature)
            serviceField("warmedUp").setBoolean(service, true)
            @Suppress("UNCHECKED_CAST")
            (serviceField("_warm").get(service) as MutableStateFlow<Boolean>).value = true
        }

        suspend fun withPrivateStats(release: () -> Unit = {}, operation: suspend () -> Unit) {
            check(!statsInjected)
            previousStats = statsField.get(translator)
            val wrapped = object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    context.getSharedPreferences(statsName, mode)
            }
            statsField.set(translator, TranslationStatsStore(wrapped))
            statsInjected = true
            try {
                operation()
            } finally {
                withContext(NonCancellable) {
                    release()
                    // Never restore the real stats dependency while an owned page could still use it.
                    joinDrains()
                    restoreStatsInjection()
                }
            }
        }

        private fun restoreStatsInjection() {
            if (statsInjected) {
                statsField.set(translator, previousStats)
                check(statsField.get(translator) === previousStats)
                statsInjected = false
                previousStats = null
            }
        }

        fun work(tag: String): List<WorkInfo> = workManager.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS)

        suspend fun cancelOnlyNewWork(ledger: JSONObject) {
            val prior = ledger.getJSONObject("beforeWork")
            for (tag in TAGS) {
                val previous = prior.getJSONArray(tag)
                val ids = (0 until previous.length()).map { previous.getString(it) }.toSet()
                work(tag).filter { it.id.toString() !in ids && !it.state.isFinished }.forEach {
                    workManager.cancelWorkById(it.id).result.get(5, TimeUnit.SECONDS)
                }
            }
            awaitCondition {
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
            val chapterId = if (ledger.isNull("chapterId")) null else ledger.getLong("chapterId")
            withTimeout(20_000) { manager.ensureRestored() }
            check(manager.queueState.value.all { it.chapter.id == chapterId && it.manga.url == title })
            preferences.translationMasterEnabled.set(false)
            preferences.nightReadEnabled.set(false)
            manager.haltForMasterOff()
            chapterId?.let { manager.cancelPendingTranslation(it) }
            joinDrains()
            restoreStatsInjection()
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
            val stats = File(context.applicationInfo.dataDir, "shared_prefs/$statsName.xml")
            check(context.getSharedPreferences(statsName, Context.MODE_PRIVATE).edit().commit())
            if (stats.exists()) check(context.deleteSharedPreferences(statsName))
            check(!stats.exists())
            restoreOwnedStorage(ledger, receipt)
            // Restore pool values while both master gates are closed and the actual queue is empty.
            setPoolPausedField("_isTranslatePaused", ledger.getBoolean("poolPaused"))
            setPoolPausedField("_isNightPaused", ledger.getBoolean("nightPoolPaused"))
            restoreEmptyQueue(ledger)
            val originalSwitches = jsonObjects(ledger.getJSONArray("switches"))
            originalSwitches.forEach { saved ->
                val preference = switches.single { it.key() == saved.getString("key") }
                if (saved.getBoolean("contains")) {
                    preference.set(saved.getBoolean("value"))
                } else {
                    preference.delete()
                }
            }
            check(defaultPrefs.edit().commit())
            val expectedDisk = originalSwitches.filter { it.getBoolean("contains") }
                .associate { it.getString("key") to it.getBoolean("value") }
            check(readSelectedPreferences(defaultPrefsName(), switches.map { it.key() }.toSet()) == expectedDisk)
            originalSwitches.forEach { saved ->
                val preference = switches.single { it.key() == saved.getString("key") }
                check(preference.isSet() == saved.getBoolean("contains"))
                check(preference.get() == saved.getBoolean("value"))
            }
            receipt.put("ownDbRowsAbsent", true).put("ownQueueAbsent", true).put("ownWorkTerminal", true)
                .put("ownLocalFilesRemoved", removed).put("ownLocalDirectoryAbsent", true)
                .put("privateStatsAbsent", true).put("twoSwitchValuesPresenceAndDiskRestored", true)
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

    private class CheckpointEngine(private val resuming: Boolean) : TranslationEngine {
        override val translationContext = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
        val firstFresh = AtomicInteger()
        val secondFresh = AtomicInteger()
        val resume = AtomicInteger()
        val active = AtomicInteger()
        val resumeEntered = CompletableDeferred<Unit>()
        val releaseResume = CompletableDeferred<Unit>()
        var resumedRegionIds = emptyList<Int>()
        private var closed = false

        override suspend fun translatePage(page: Bitmap): PageResult {
            check(!closed && !resuming) { "Phase B must never perform fresh inference" }
            active.incrementAndGet()
            return try {
                val first = page.getPixel(0, 0) == Color.WHITE
                check(first || page.getPixel(0, 0) == Color.BLUE)
                if (first) firstFresh.incrementAndGet() else secondFresh.incrementAndGet()
                if (first) {
                    val progress = finalProgress().copy(regions = listOf(finalProgress().regions[0]))
                    PageResult.Translated(image(Color.GREEN), STATS, pageAnalysis(progress))
                } else {
                    PageResult.Partial(secondOutput(complete = false), STATS, pageAnalysis(pendingProgress()))
                }
            } finally {
                active.decrementAndGet()
            }
        }

        override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
            check(!closed && resuming)
            active.incrementAndGet()
            return try {
                check(original.getPixel(0, 0) == Color.BLUE && original.width == SIZE && original.height == SIZE)
                check(analysis.progress == pendingProgress())
                check(
                    PageProgress.capture(
                        SIZE,
                        SIZE,
                        analysis.regions,
                        pendingProgress().regions.map { it.state },
                    ) == pendingProgress(),
                )
                check(pixels(analysis.mask).all { it == Color.BLACK })
                resumedRegionIds = checkNotNull(analysis.progress).pendingRegionIds
                resume.incrementAndGet()
                resumeEntered.complete(Unit)
                withTimeout(60_000) { releaseResume.await() }
                val output = secondOutput(complete = true)
                PageResult.Translated(output, STATS, pageAnalysis(finalProgress()))
            } finally {
                active.decrementAndGet()
            }
        }

        override fun warmUp() = error("Scripted fixture must never initialize native inference")
        override fun close() {
            check(active.get() == 0)
            closed = true
        }
    }

    private fun gate() {
        check(context.packageName == AUDIT_PACKAGE) { "Isolated audit installation required" }
        check(arguments.getString("translationProcessDeath") == "true") {
            "Explicit translationProcessDeath=true is required; missing opt-in is not a pass or skip"
        }
    }

    private fun uuid(): String = checkNotNull(arguments.getString("translationProcessDeathUuid")).also {
        check(UUID.fromString(it).toString() == it && it.matches(Regex(UUID_PATTERN)))
    }

    private fun status(value: JSONObject) = instrumentation.sendStatus(
        2,
        Bundle().apply { putString("stream", "TRANSLATION_PROCESS_DEATH=$value\n") },
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
        private const val SIZE = 32
        private const val METHOD = "boxfill"
        private const val MAX_LEDGER_BYTES = 128 * 1024L
        private val TAGS = listOf("Translator", "TranslatorHeartbeat")
        private val QUEUE_BOOLEANS = listOf("paused", "paused_night")
        private val QUEUE_SETS = listOf("pending_translate", "pending_night")
        private val STORAGE_DIRECTORIES = setOf("autobackup", "downloads", "local")
        private val NOTE = "Neutral process-death fixture note\n".toByteArray()
        private val STATS = PageStats(2, 2, 0, 0, 0, 0, 0, 0)

        private fun pendingProgress() = PageProgress(
            SIZE,
            SIZE,
            listOf(
                region(0, RegionState.TRANSLATED, "こんにちは", "안녕하세요", 2f),
                region(1, RegionState.OCR_PENDING, "", "", 18f),
            ),
        )
        private fun finalProgress() = pendingProgress().copy(
            regions = listOf(
                pendingProgress().regions[0],
                region(1, RegionState.TRANSLATED, "ありがとう", "고마워요", 18f),
            ),
        )
        private fun region(id: Int, state: RegionState, source: String, target: String, y: Float) = RegionProgress(
            id, state,
            listOf(OcrLineProgress(listOf(Pt(2f, y), Pt(28f, y), Pt(28f, y + 8), Pt(2f, y + 8)), 0.9f, "h", source)),
            "h", cx = 15f, cy = y + 4, boxW = 26f, boxH = 8f, translatedText = target,
        )
        private fun pageAnalysis(progress: PageProgress) = PageAnalysis(
            image(Color.BLACK),
            progress.toTextRegions(),
            progress,
        )
        private fun secondOutput(complete: Boolean) = image(if (complete) Color.YELLOW else Color.BLUE).apply {
            for (y in 0 until 16) for (x in 0 until SIZE) setPixel(x, y, Color.GREEN)
        }
        private fun savedSuccessfulRegionPixelHash(file: File): String {
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
            try {
                check(bitmap.width == SIZE && bitmap.height == SIZE)
                val region = IntArray(SIZE * 16)
                bitmap.getPixels(region, 0, SIZE, 0, 0, SIZE, 16)
                check(region.all { it == Color.GREEN })
                return sha(region.joinToString(",").toByteArray())
            } finally {
                bitmap.recycle()
            }
        }
        private fun image(color: Int) = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        private fun writeImage(file: File, color: Int) {
            val bitmap = image(color)
            try {
                FileOutputStream(file).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
        }
        private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
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
                        check(it.isFile && it.length() <= MAX_LEDGER_BYTES)
                        hashes[it.relativeTo(root).invariantSeparatorsPath] = sha(it.readBytes())
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
            val hashes = files.associateWith { sha(it.readBytes()) }
            files.forEach {
                check(sha(it.readBytes()) == hashes.getValue(it))
                check(it.delete() && !it.exists())
            }
            directories.forEach { check(it.list()!!.isEmpty() && it.delete() && !it.exists()) }
            return files.size
        }
    }
}
