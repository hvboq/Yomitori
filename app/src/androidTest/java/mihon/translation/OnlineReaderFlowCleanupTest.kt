package mihon.translation

import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.tachiyomi.crash.TraceLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.EngineTrace
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit recovery of one abandoned synthetic fixture; no Activity, model or server is started. */
@RunWith(AndroidJUnit4::class)
class OnlineReaderFlowCleanupTest {
    @Test(timeout = 120_000)
    fun removeOwnedAbandonedSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        check(context.packageName == "app.yomitori.yakuyomi.audit")
        check(args.getString("onlineReaderFlowCleanup") == "true")
        val supplied = checkNotNull(args.getString("onlineReaderFlowCleanupUuid"))
        val uuid = UUID.fromString(supplied)
        check(uuid.toString() == supplied)
        val prefix = "online-reader-flow-$uuid"
        val parent = context.cacheDir.canonicalFile
        val root = File(parent, prefix)
        val ledgerFile = File(root, "owned.json")
        var ledgerBytes: ByteArray? = null
        var prewarm: OnlineReaderFlowPrewarmLease? = null
        var stage = "idle_guard"
        var failure: Throwable? = null
        val report = JSONObject().put("sessionUuid", uuid.toString()).put("passed", false)
            .put("originalPreferenceWrites", false).put("modelFilesWritten", false)
            .put("protectedBytesRequireIndependentHostCheck", true)
        try {
            NativeDiagnosticIdleGuard.requireIdle(context)
            prewarm = OnlineReaderFlowPrewarmLease()
            check(!TraceLog.isEnabled && EngineTrace.sink == null)
            val original = PreferenceManager.getDefaultSharedPreferences(context).all.toMap()
            check(original["translation_diagnostic_log"] != true)
            val work = WorkManager.getInstance(context)
            val names = listOf("Downloader", "Translator", "TranslatorHeartbeat")
            val beforeWork = names.associateWith { name ->
                work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).also { rows ->
                    check(rows.all { it.state.isFinished })
                }.associate { it.id to it.state }
            }
            report.put("workRowsAllFinishedBefore", true)
            stage = "owned_tree_and_ledger_validate"
            val entries = ownedTree(root, parent, prefix)
            check(
                root.listFiles().orEmpty().all {
                    it.name in setOf("cache", "files", "storage", "evidence", "owned.json")
                },
            )
            check(ledgerFile in entries && ledgerFile.length() in 1L..1024L)
            val ownedLedger = ledgerFile.readBytes()
            ledgerBytes = ownedLedger
            val ledger = JSONObject(ownedLedger.toString(Charsets.UTF_8))
            check(ledger.keys().asSequence().toSet() == setOf("uuid", "mangaId", "chapterId"))
            check(ledger.get("uuid") is String && ledger.getString("uuid") == supplied)
            check(ledger.get("mangaId") is Int || ledger.get("mangaId") is Long)
            check(ledger.get("chapterId") is Int || ledger.get("chapterId") is Long)
            val mangaId = ledger.getLong("mangaId")
            val chapterId = ledger.getLong("chapterId")
            check(mangaId > 0 && chapterId > 0)
            val preferenceDirectory = File(context.applicationInfo.dataDir, "shared_prefs").canonicalFile
            val preferenceNames = listOf("settings", "active_downloads", "active_translations", "chapters_to_delete")
                .map { "$prefix-$it" }
            for (name in preferenceNames) {
                val file = File(preferenceDirectory, "$name.xml")
                val stat = Os.lstat(file.absolutePath)
                check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_nlink == 1L)
                check(file.canonicalFile == file.absoluteFile && file.length() <= 256 * 1024)
            }
            stage = "owned_database_rows_remove"
            val database = Injekt.get<Database>()
            database.transaction {
                val all = database.mangasQueries.getAllManga().awaitAsList()
                check(all.size <= 10_000)
                val manga = all.single { it._id == mangaId }
                check(
                    manga.url == "/fixture/$uuid" && manga.title == "Online reader synthetic $uuid" && !manga.favorite,
                )
                check(all.filter { it.source == manga.source } == listOf(manga))
                val chapters = database.chaptersQueries.getChaptersByMangaId(mangaId, 0L).awaitAsList()
                check(chapters.size == 1)
                val chapter = chapters.single()
                check(chapter._id == chapterId && chapter.manga_id == mangaId)
                check(chapter.url == "/chapter" && chapter.name == "Fixture" && !chapter.bookmark)
                check(database.chaptersQueries.getChapterById(chapterId).awaitAsOneOrNull() == chapter)
                check(database.mangasQueries.deleteNonLibraryManga(listOf(manga.source), 0L) == 1L)
                check(database.mangasQueries.getAllManga().awaitAsList() == all.filter { it._id != mangaId })
                check(database.chaptersQueries.getChapterById(chapterId).awaitAsOneOrNull() == null)
                check(database.chaptersQueries.getChaptersByMangaId(mangaId, 0L).awaitAsList().isEmpty())
            }
            report.put("ownedMangaRowsRemoved", 1).put("ownedChapterRowsRemoved", 1)
                .put("otherMangaRowsUnchanged", true)
            stage = "owned_preferences_remove"
            NativeDiagnosticIdleGuard.requireIdle(context)
            for (name in preferenceNames) {
                check(name.startsWith("$prefix-"))
                check(context.getSharedPreferences(name, 0).edit().clear().commit())
                check(context.deleteSharedPreferences(name))
                check(
                    !File(preferenceDirectory, "$name.xml").exists() &&
                        !File(preferenceDirectory, "$name.xml.bak").exists(),
                )
            }
            report.put("ownedPreferenceStoresRemoved", preferenceNames.size)
            check(PreferenceManager.getDefaultSharedPreferences(context).all == original)
            for ((name, rows) in beforeWork) {
                check(
                    work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).associate { it.id to it.state } ==
                        rows,
                )
            }
            report.put("originalPreferenceValuesUnchanged", true).put("workRowsUnchanged", true)
            stage = "owned_tree_remove"
            val current = ownedTree(root, parent, prefix)
            check(current.toSet() == entries.toSet())
            // Retain the ledger until all other files/directories have been removed.
            for (file in current.asReversed().filter { it != root && it != ledgerFile }) check(file.delete())
            check(root.listFiles().orEmpty().toList() == listOf(ledgerFile))
            check(ledgerFile.delete())
            check(root.delete() && !root.exists())
            report.put("ownedRootRemoved", true).put("ownedTreeEntryCount", entries.size)
            NativeDiagnosticIdleGuard.requireIdle(context)
            check(!TraceLog.isEnabled && EngineTrace.sink == null)
            report.put("sharedNativeAndQueuesIdle", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failureStage", stage).put("failure", onlineReaderFlowFailure(error))
            // A failed delete must retain the ownership proof for another explicit recovery attempt.
            ledgerBytes?.let { bytes ->
                runCatching {
                    if (root.exists() && root.canonicalFile == root.absoluteFile &&
                        OsConstants.S_ISDIR(Os.lstat(root.absolutePath).st_mode) && !ledgerFile.exists()
                    ) {
                        ledgerFile.writeBytes(bytes)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                prewarm?.let {
                    try {
                        it.awaitBackground()
                        it.restore()
                        report.put("prewarmRestored", true)
                    } catch (error: Throwable) {
                        failure = failure ?: error
                        report.put("prewarmFailure", onlineReaderFlowFailure(error))
                    }
                }
            }
        }
        report.put("passed", failure == null)
        emitOnlineReaderFlow("onlineReaderFlowRecovery", report)
        if (failure != null) throw AssertionError("OWNED_ONLINE_READER_RECOVERY_FAILED")
    }

    /** Validate before any delete: no symlinks, foreign hardlinks, large/unbounded trees or path escape. */
    private fun ownedTree(root: File, parent: File, prefix: String): List<File> {
        check(root.name == prefix && root.parentFile == parent && root.canonicalFile == root.absoluteFile)
        val pending = ArrayDeque<File>()
        pending.add(root)
        val result = mutableListOf<File>()
        var total = 0L
        while (pending.isNotEmpty()) {
            val file = pending.removeFirst()
            check(result.size < 1_000)
            check(file == root || file.absolutePath.startsWith(root.absolutePath + File.separator))
            check(file.canonicalFile == file.absoluteFile)
            check(file.relativeTo(root).path.split(File.separatorChar).size <= 16)
            val stat = Os.lstat(file.absolutePath)
            check(stat.st_uid == Process.myUid())
            when {
                OsConstants.S_ISDIR(stat.st_mode) -> {
                    val children = checkNotNull(file.listFiles())
                    check(children.size + pending.size + result.size < 1_000)
                    pending.addAll(children.sortedBy { it.name })
                }
                OsConstants.S_ISREG(stat.st_mode) -> {
                    check(stat.st_nlink == 1L && stat.st_size in 0..16L * 1024 * 1024)
                    total += stat.st_size
                    check(total <= 64L * 1024 * 1024)
                }
                else -> error("OWNED_TREE_REGULAR_FILES_AND_DIRECTORIES_ONLY")
            }
            result += file
        }
        check(OsConstants.S_ISDIR(Os.lstat(root.absolutePath).st_mode))
        return result
    }
}
