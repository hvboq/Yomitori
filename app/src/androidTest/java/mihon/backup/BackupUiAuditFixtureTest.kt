package mihon.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Opt-in phase helpers around externally operated, actual MainActivity/SAF backup screens.
 * Never invokes backup/restore creation APIs, replaces Injekt registrations, or starts workers.
 * These seven helpers are not seven independent end-to-end feature tests.
 *
 * Build both APKs with -PyomitoriIsolatedAudit=true. Execute exactly one method at a time:
 * adb -s SERIAL shell am instrument -w -r -e backupUiAudit true -e backupUiAuditPhase seed
 *   -e class mihon.backup.BackupUiAuditFixtureTest#seedNeutralFixture
 *   app.yomitori.yakuyomi.audit.test/androidx.test.runner.AndroidJUnitRunner
 * Use phases unchanged, create, destination, restore, cleanup with their respective method names below.
 * grantLossDiagnostic observes the old transient-URI failure separately from normal URI validation.
 * Between seed/create and destination/restore, operate the real menus and SAF picker externally.
 * Private evidence: files/backup-ui-audit/marker.json and files/backup-ui-audit/PHASE.json.
 */
@Suppress("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class BackupUiAuditFixtureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.filesDir, "backup-ui-audit")
    private val markerFile get() = File(root, "marker.json")
    private val database get() = Injekt.get<Database>()
    private val mangas get() = Injekt.get<MangaRepository>()
    private val categories get() = Injekt.get<CategoryRepository>()
    private val chapters get() = Injekt.get<ChapterRepository>()
    private val history get() = Injekt.get<HistoryRepository>()
    private val store get() = Injekt.get<PreferenceStore>()
    private val workManager get() = WorkManager.getInstance(context)
    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun seedNeutralFixture() = phase("seed") {
        assertFalse("Existing audit marker must be cleaned up explicitly", markerFile.exists())
        assertTrue("Audit database must be fresh", database.mangasQueries.getAllManga().awaitAsList().isEmpty())
        assertTrue("Audit categories must be fresh", categories.getAll().all { it.isSystemCategory })
        assertTrue(
            "Audit extension stores must be fresh",
            database.extension_storeQueries.getAll().awaitAsList().isEmpty(),
        )
        assertTrue(
            "Fresh audit must have no persisted SAF grants",
            context.contentResolver.persistedUriPermissions.isEmpty(),
        )
        assertIdle()
        assertNoActiveBackupWork()
        val fixture = Fixture(UUID.randomUUID().toString())
        val marker = JSONObject()
            .put("schema", 1)
            .put("package", AUDIT_PACKAGE)
            .put("uuid", fixture.uuid)
            .put("expected", fixture.expected())
            .put("stage", "seeding")
            .put("seededAt", System.currentTimeMillis())
            .put("savedPreferences", JSONArray())
            .put("ownedDocuments", JSONArray())
            .put("beforeCreateIds", workIds(CREATE_TAG))
            .put("beforeRestoreIds", workIds(RESTORE_TAG))
            .put("phaseHelpersAreIndependentFeatureTests", false)
            .put("uiAndSafOperatedExternally", true)
            .put("workSpecInspection", "Read-only AndroidX internal input inspection; production workers are unchanged")
        saveMarker(marker)

        val base = Injekt.get<BasePreferences>()
        val library = Injekt.get<LibraryPreferences>()
        val translation = Injekt.get<TranslationPreferences>()
        val backup = Injekt.get<BackupPreferences>()
        val security = Injekt.get<SecurityPreferences>()
        val settings = listOf(
            SavedPreference(base.shownOnboardingFlow, true),
            SavedPreference(base.incognitoMode, false),
            SavedPreference(library.autoClearChapterCache, false),
            SavedPreference(library.autoUpdateInterval, 0),
            SavedPreference(library.autoRefreshMangaOnOpen, false),
            SavedPreference(translation.liveTranslate, false),
            SavedPreference(translation.translationMasterEnabled, false),
            SavedPreference(backup.backupInterval, 0),
            SavedPreference(security.hideNotificationContent, false),
            SavedPreference(library.defaultCategory, -1),
            SavedPreference(library.updateCategories, emptySet<String>()),
            SavedPreference(library.updateCategoriesExclude, emptySet<String>()),
            SavedPreference(library.categorizedDisplaySettings, false),
            SavedPreference(store.getString(fixture.preferenceKey), fixture.preferenceValue),
        )
        settings.forEach { marker.getJSONArray("savedPreferences").put(it.snapshot()) }
        saveMarker(marker) // Persist restoration data before the first preference mutation.
        settings.forEach { it.apply() }
        flushPreferences()
        // Application migrations may have scheduled this audit package's default periodic backup.
        workManager.cancelUniqueWork("BackupCreator").result.get(10, TimeUnit.SECONDS)
        categories.insert(Category(-1, fixture.alpha, 10, 11))
        categories.insert(Category(-1, fixture.beta, 20, 22))
        val alpha = categories.getAll().single { it.name == fixture.alpha }
        val beta = categories.getAll().single { it.name == fixture.beta }
        marker.put("sourceAlphaId", alpha.id).put("sourceBetaId", beta.id)
        saveMarker(marker)
        assertTrue(
            "Built-in local source must be registered",
            Injekt.get<SourceManager>().get(LocalSource.ID) is LocalSource,
        )
        val manga = mangas.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = LocalSource.ID,
                    url = fixture.mangaUrl,
                    title = fixture.title,
                    favorite = true,
                    initialized = true,
                    description = "Neutral backup metadata fixture; no page images",
                    dateAdded = HISTORY_AT,
                    viewerFlags = 3,
                    chapterFlags = 257,
                    version = 4,
                ),
            ),
        ).single()
        marker.put("mangaId", manga.id)
        saveMarker(marker)
        assertTrue(mangas.update(MangaUpdate(id = manga.id, notes = fixture.notes)))
        mangas.setMangaCategories(manga.id, listOf(alpha.id))
        val seededChapters = chapters.addAll(
            listOf(
                Chapter.create().copy(
                    mangaId = manga.id,
                    url = fixture.chapterOne,
                    name = "Neutral chapter one",
                    read = true,
                    bookmark = true,
                    lastPageRead = 7,
                    chapterNumber = 1.0,
                    sourceOrder = 1,
                    dateFetch = HISTORY_AT,
                    dateUpload = HISTORY_AT - 1_000,
                ),
                Chapter.create().copy(
                    mangaId = manga.id,
                    url = fixture.chapterTwo,
                    name = "Neutral chapter two",
                    lastPageRead = 3,
                    chapterNumber = 2.0,
                    sourceOrder = 2,
                    dateFetch = HISTORY_AT,
                    dateUpload = HISTORY_AT - 500,
                ),
            ),
        )
        history.upsertHistory(
            HistoryUpdate(
                seededChapters.single { it.url == fixture.chapterOne }.id,
                Date(HISTORY_AT),
                HISTORY_DURATION,
            ),
        )
        library.defaultCategory.set(alpha.id.toInt())
        library.updateCategories.set(setOf(alpha.id.toString()))
        library.updateCategoriesExclude.set(setOf(beta.id.toString()))
        flushPreferences()
        assertFixture(marker, remapped = false)
        marker.put("beforeCreateIds", workIds(CREATE_TAG)).put("stage", "seeded")
        saveMarker(marker)
        println("BACKUP_UI_AUDIT_UUID=${fixture.uuid}")
        println("BACKUP_UI_AUDIT_SAF_FILENAME=${fixture.filename}")
    }

    @Test
    fun verifySelectionCancelledOrRejectedWithoutMutation() = phase("unchanged") {
        val marker = loadMarker("seeded")
        assertIdle()
        assertNoActiveBackupWork()
        assertEquals(marker.getJSONArray("beforeCreateIds").strings(), workIds(CREATE_TAG).strings())
        assertEquals(marker.getJSONArray("beforeRestoreIds").strings(), workIds(RESTORE_TAG).strings())
        assertFixture(marker, remapped = false)
        marker.put("unchangedObservationCount", marker.optInt("unchangedObservationCount", 0) + 1)
            .put("unchangedObservedAt", System.currentTimeMillis())
            .put(
                "unchangedObservationBoundary",
                "Read-only state comparison after externally operated UI/SAF; no UI action is performed here",
            )
        saveMarker(marker)
    }

    @Test
    fun verifyCreatedBackup() = phase("create") {
        val marker = loadMarker("seeded", "created")
        assertOnlyOwnedData(marker)
        val work = awaitNewSuccessfulWork(CREATE_TAG, marker.getJSONArray("beforeCreateIds")) {
            observeOwnedWorkDocument(marker, it)
        }
        assertNotificationsAvailable()
        val input = workInput(work.id)
        assertFalse(input.getBoolean("is_auto_backup", true))
        assertTrue(
            "Actual UI must submit default backup options",
            input.getBooleanArray("options")!!.contentEquals(DEFAULT_CREATE_OPTIONS),
        )
        val uri = requireNotNull(input.getString("location_uri")).let(Uri::parse)
        val bytes = readOwnedBackup(uri, Fixture(marker.getString("uuid")))
        marker.put("backupWorkId", work.id.toString())
            .put("backupUri", uri.toString())
            .put("backupSha256", sha256(bytes))
            .put("backupBytes", bytes.size)
        saveMarker(marker) // Retain scoped cleanup ownership even if validation/notification assertions fail.
        val backup = BackupDecoder(context).decode(uri)
        assertBackup(marker, backup)
        val validation = BackupFileValidator(context).validate(uri)
        assertTrue(validation.missingSources.isEmpty())
        assertTrue(validation.missingTrackers.isEmpty())
        assertCompletedNotification(
            Notifications.ID_BACKUP_COMPLETE,
            Notifications.ID_BACKUP_PROGRESS,
            context.stringResource(MR.strings.backup_created),
            marker.getLong("seededAt"),
        )
        marker.put("stage", "created")
        saveMarker(marker)
    }

    @Test
    fun prepareRestoreDestination() = phase("destination") {
        val marker = loadMarker("created", "restored", "restoreDataVerifiedWithGrantLoss")
        assertOnlyOwnedData(marker)
        assertIdle()
        assertNoActiveBackupWork()
        val previouslyRestored = marker.getString("stage") != "created"
        assertFixture(marker, remapped = previouslyRestored)
        val fixture = Fixture(marker.getString("uuid"))
        // Keep a higher-ID, owned category alive while removing the backup's categories.
        val existingSentinels = categories.getAll().filter { it.name == fixture.sentinel }
        assertTrue("Fixture must not contain duplicate destination sentinels", existingSentinels.size <= 1)
        if (existingSentinels.isEmpty()) {
            assertFalse("Previously restored fixture must retain its destination sentinel", previouslyRestored)
            categories.insert(Category(-1, fixture.sentinel, 30, 33))
        }
        val sentinel = categories.getAll().single { it.name == fixture.sentinel }
        if (marker.has("sentinelId")) assertEquals(marker.getLong("sentinelId"), sentinel.id)
        assertTrue(sentinel.id > marker.getLong("sourceAlphaId"))
        assertTrue(sentinel.id > marker.getLong("sourceBetaId"))
        marker.put("sentinelId", sentinel.id).put("stage", "preparingDestination")
        saveMarker(marker)
        val manga = requireNotNull(mangas.getMangaByUrlAndSourceId(fixture.mangaUrl, LocalSource.ID))
        chapters.removeChaptersWithIds(chapters.getChapterByMangaId(manga.id, false).map { it.id })
        categories.getAll()
            .filter { it.name == fixture.alpha || it.name == fixture.beta }
            .forEach { categories.delete(it.id) }
        assertTrue(
            mangas.update(
                MangaUpdate(id = manga.id, favorite = false, notes = "Destination changed ${fixture.uuid}"),
            ),
        )
        store.getString(fixture.preferenceKey).set("Destination changed ${fixture.uuid}")
        val library = Injekt.get<LibraryPreferences>()
        library.defaultCategory.set(sentinel.id.toInt())
        library.updateCategories.set(setOf(sentinel.id.toString()))
        library.updateCategoriesExclude.set(setOf(sentinel.id.toString()))
        flushPreferences()
        assertTrue(chapters.getChapterByMangaId(manga.id, false).isEmpty())
        assertTrue(history.getHistoryByMangaId(manga.id).isEmpty())
        notificationManager.cancel(Notifications.ID_RESTORE_COMPLETE)
        marker.put("beforeRestoreIds", workIds(RESTORE_TAG))
            .put("restorePreparedAt", System.currentTimeMillis())
            .put("stage", "destinationPrepared")
        saveMarker(marker)
    }

    @Test
    fun verifyRestoredFixture() = phase("restore") {
        val marker = loadMarker("destinationPrepared", "restored")
        assertOnlyOwnedData(marker)
        val work = awaitNewSuccessfulWork(RESTORE_TAG, marker.getJSONArray("beforeRestoreIds")) {
            observeOwnedWorkDocument(marker, it)
        }
        assertNotificationsAvailable()
        val input = workInput(work.id)
        assertFalse(input.getBoolean("sync", true))
        assertTrue(
            "Actual UI must submit default restore options",
            input.getBooleanArray("options")!!.contentEquals(BooleanArray(5) { true }),
        )
        val uri = requireNotNull(input.getString("location_uri")).let(Uri::parse)
        assertTrue(
            "Actual restore input URI must retain the OpenDocument persisted read grant",
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission },
        )
        val readGrantCheckedAt = System.currentTimeMillis()
        assertEquals(marker.getString("backupSha256"), sha256(readOwnedBackup(uri, Fixture(marker.getString("uuid")))))
        assertBackup(marker, BackupDecoder(context).decode(uri))
        assertFixture(marker, remapped = true)
        assertRestoreCompletedWithoutErrors(marker)
        marker.put("restoreWorkId", work.id.toString())
            .put("restoreUri", uri.toString())
            .put(
                "restoreInputReadGrant",
                JSONObject().put("workId", work.id.toString())
                    .put("inputUri", uri.toString())
                    .put("persistedReadGrantVerified", true)
                    .put("checkedAt", readGrantCheckedAt),
            )
            .put(
                "restoredAlphaId",
                categories.getAll().single { it.name == Fixture(marker.getString("uuid")).alpha }.id,
            )
            .put("stage", "restored")
        saveMarker(marker)
    }

    @Test
    fun verifyRestoredDataAfterTransientUriGrantLoss() = phase("grantLossDiagnostic") {
        val marker = loadMarker("destinationPrepared")
        assertOnlyOwnedData(marker)
        assertIdle()
        val before = marker.getJSONArray("beforeRestoreIds").strings()
        val candidates = works(RESTORE_TAG).filter { it.id.toString() !in before }
        assertEquals("Grant-loss diagnostic requires exactly one old restore job", 1, candidates.size)
        val work = candidates.single()
        assertEquals(
            "Grant-loss diagnostic requires the old restore job to have completed successfully",
            WorkInfo.State.SUCCEEDED,
            work.state,
        )
        val diagnostic = JSONObject().put("diagnosticOnly", true)
            .put("workId", work.id.toString())
            .put("workState", work.state.name)
            .put("normalInputUriValidationPassed", false)
            .put("normalRestoreFlowPassed", false)
            .put("notificationObserved", JSONObject.NULL)
            .put("restoreCompletionZeroErrors", JSONObject.NULL)
            .put("checkpoints", JSONObject())
        fun checkpoint(step: String) {
            diagnostic.getJSONObject("checkpoints").put(step, System.currentTimeMillis())
            diagnostic.put("lastCheckpoint", step)
            marker.put("grantLossDiagnostic", diagnostic)
            saveMarker(marker)
            writeJson(
                File(root, "grantLossDiagnostic.json"),
                JSONObject().put("phase", "grantLossDiagnostic").put("package", AUDIT_PACKAGE)
                    .put("uuid", marker.getString("uuid"))
                    .put("passed", false).put("inProgress", true)
                    .put("phaseHelperOnly", true).put("actualUiOperatedExternally", true)
                    .put("helperDoesNotOperateUiOrSaf", true).put("diagnosticOnly", true)
                    .put("normalInputUriValidationPassed", false).put("grantLossDiagnostic", diagnostic),
            )
        }
        checkpoint("oldWorkSucceeded")
        assertNoActiveBackupWork()
        val input = workInput(work.id)
        assertFalse(input.getBoolean("sync", true))
        assertTrue(input.getBooleanArray("options")!!.contentEquals(BooleanArray(5) { true }))
        val transientUri = requireNotNull(input.getString("location_uri")).let(Uri::parse)
        val denied = try {
            inspectDocument(transientUri)
            false
        } catch (_: SecurityException) {
            true
        }
        assertTrue("Old GetContent URI must demonstrate a distinct SecurityException after grant loss", denied)
        diagnostic.put("transientInputUri", transientUri.toString())
            .put("inputUriQueryDenied", true)
            .put("denialClass", SecurityException::class.java.name)
        checkpoint("inputUriQueryDenied")
        val fixture = Fixture(marker.getString("uuid"))
        val persistedUri = Uri.parse(marker.getString("backupUri"))
        assertTrue(
            "Original backup must retain its persisted read grant",
            context.contentResolver.persistedUriPermissions.any { it.uri == persistedUri && it.isReadPermission },
        )
        assertEquals(marker.getString("backupSha256"), sha256(readOwnedBackup(persistedUri, fixture)))
        diagnostic.put("originalPersistedBackupShaVerified", true)
        checkpoint("originalBackupShaVerified")
        assertBackup(marker, BackupDecoder(context).decode(persistedUri))
        assertFixture(marker, remapped = true)
        diagnostic.put("actualRepositoryDataVerified", true)
        checkpoint("actualRepositoryDataVerified")
        val notificationPresent = notificationManager.activeNotifications.any {
            it.id == Notifications.ID_RESTORE_COMPLETE && it.postTime >= marker.getLong("restorePreparedAt")
        }
        diagnostic.put("notificationObserved", notificationPresent)
        checkpoint("notificationPresenceObserved")
        if (notificationPresent) {
            assertNotificationsAvailable()
            assertRestoreCompletedWithoutErrors(marker)
            diagnostic.put("restoreCompletionZeroErrors", true)
        } else {
            diagnostic.put("restoreCompletionZeroErrors", JSONObject.NULL)
                .put("notificationAbsenceBoundary", "Old completion notification unavailable after app update")
        }
        marker.put("stage", "restoreDataVerifiedWithGrantLoss")
        checkpoint("diagnosticComplete")
    }

    private suspend fun assertRestoreCompletedWithoutErrors(marker: JSONObject) {
        val notification = assertCompletedNotification(
            Notifications.ID_RESTORE_COMPLETE,
            Notifications.ID_RESTORE_PROGRESS,
            context.stringResource(MR.strings.restore_completed),
            marker.getLong("restorePreparedAt"),
        )
        val token = "__AUDIT_DURATION__"
        val parts = context.pluralStringResource(MR.plurals.restore_completed_message, 0, token, 0).split(token)
        assertEquals(2, parts.size)
        val message = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val zeroErrors = Regex("^${Regex.escape(parts[0])}.+${Regex.escape(parts[1])}$")
        assertTrue("Restore completion must report zero errors", zeroErrors.matches(message))
        assertTrue(
            notification.actions.orEmpty().none {
                it.title.toString() == context.stringResource(MR.strings.action_show_errors)
            },
        )
    }

    @Test
    fun cleanup() = phase("cleanup") {
        val marker = loadMarker()
        assertOnlyOwnedData(marker)
        // This gate must stay outside failure collection: cancelled WorkInfo is not an IO/finally join.
        enforceCleanupProcessBarrier(marker)
        val fixture = Fixture(marker.getString("uuid"))
        val failures = mutableListOf<Throwable>()
        suspend fun attempt(action: suspend () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        for (tag in listOf(CREATE_TAG, RESTORE_TAG)) {
            enforceCleanupProcessBarrier(marker)
            attempt {
                val beforeKey = if (tag == CREATE_TAG) "beforeCreateIds" else "beforeRestoreIds"
                val before = marker.getJSONArray(beforeKey).strings()
                for (work in works(tag).filter { it.id.toString() !in before }) {
                    attempt {
                        observeOwnedWorkDocument(marker, work)
                        check(work.state.isFinished) { "Work state snapshot predates the cleanup process barrier" }
                    }
                }
            }
        }
        enforceCleanupProcessBarrier(marker)
        attempt {
            assertNoActiveBackupWork()
            mangas.getMangaByUrlAndSourceId(fixture.mangaUrl, LocalSource.ID)?.let { manga ->
                if (marker.has("mangaId")) {
                    check(manga.id == marker.getLong("mangaId"))
                } else {
                    // A process may die after insertion but before the ID checkpoint is written.
                    // assertOnlyOwnedData plus the exact source/UUID URL still proves ownership.
                    marker.put("mangaId", manga.id).put("recoveredPartialSeedMangaId", true)
                    saveMarker(marker)
                }
                val driver = Injekt.get<SqlDriver>()
                val removed = driver.execute(null, "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?", 3) {
                    bindLong(0, manga.id)
                    bindLong(1, LocalSource.ID)
                    bindString(2, fixture.mangaUrl)
                }.await()
                assertEquals(1L, removed)
                driver.notifyListeners("mangas", "chapters", "history", "mangas_categories")
            }
        }
        enforceCleanupProcessBarrier(marker)
        attempt {
            assertNoActiveBackupWork()
            categories.getAll().filter { it.name in fixture.categoryNames }.forEach { categories.delete(it.id) }
        }
        enforceCleanupProcessBarrier(marker)
        attempt {
            assertNoActiveBackupWork()
            restorePreferences(marker.getJSONArray("savedPreferences"))
        }
        val documents = marker.optJSONArray("ownedDocuments") ?: JSONArray()
        for (index in 0 until documents.length()) {
            val document = documents.getJSONObject(index)
            enforceCleanupProcessBarrier(marker)
            attempt {
                assertNoActiveBackupWork()
                deleteOwnedDocument(marker, document)
            }
            enforceCleanupProcessBarrier(marker)
            attempt { releaseOwnedDocumentGrant(marker, document) }
        }
        enforceCleanupProcessBarrier(marker)
        attempt {
            listOf(
                Notifications.ID_BACKUP_PROGRESS,
                Notifications.ID_BACKUP_COMPLETE,
                Notifications.ID_RESTORE_PROGRESS,
                Notifications.ID_RESTORE_COMPLETE,
            ).forEach(notificationManager::cancel)
        }
        attempt {
            assertTrue(database.mangasQueries.getAllManga().awaitAsList().isEmpty())
            assertTrue(categories.getAll().all { it.isSystemCategory })
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Scoped audit cleanup failed; marker retained").apply {
                failures.forEach(::addSuppressed)
            }
        }
        marker.put("stage", "cleaned").put("cleanedAt", System.currentTimeMillis())
        saveMarker(marker)
    }

    private fun phase(name: String, action: suspend () -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        val optedIn = arguments.getString("backupUiAudit") == "true"
        if (!optedIn) {
            println("BACKUP_UI_AUDIT_SKIP=$name requires -e backupUiAudit true and an explicitly selected phase")
        }
        assumeTrue("Opt-in audit phase helper; use -e backupUiAudit true", optedIn)
        assertEquals("Refusing access outside the separate audit sandbox", AUDIT_PACKAGE, context.packageName)
        assertEquals("Select exactly this phase explicitly", name, arguments.getString("backupUiAuditPhase"))
        assertTrue(root.isDirectory || root.mkdirs())
        val report = JSONObject().put("phase", name).put("package", AUDIT_PACKAGE).put("passed", false)
            .put("phaseHelperOnly", true).put("actualUiOperatedExternally", true)
            .put("helperDoesNotOperateUiOrSaf", true)
        if (name == "grantLossDiagnostic") {
            report.put("diagnosticOnly", true).put("normalInputUriValidationPassed", false)
        }
        if (name == "restore") report.put("restoreInputPersistedReadGrantVerified", false)
        try {
            runBlocking { action() }
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("failureClass", failure.javaClass.name)
            throw failure
        } finally {
            if (markerFile.exists()) {
                val savedMarker = JSONObject(markerFile.readText())
                report.put("uuid", savedMarker.optString("uuid"))
                if (name == "grantLossDiagnostic" && savedMarker.has("grantLossDiagnostic")) {
                    report.put("grantLossDiagnostic", savedMarker.getJSONObject("grantLossDiagnostic"))
                }
                if (name == "restore" && report.getBoolean("passed")) {
                    val proof = savedMarker.getJSONObject("restoreInputReadGrant")
                    check(proof.getString("workId") == savedMarker.getString("restoreWorkId"))
                    report.put("restoreInputPersistedReadGrantVerified", proof.getBoolean("persistedReadGrantVerified"))
                        .put("restoreInputReadGrant", proof)
                }
            }
            writeJson(File(root, "$name.json"), report)
            println(
                "BACKUP_UI_AUDIT_PHASE=$name passed=${report.getBoolean("passed")} " +
                    "privateReport=files/backup-ui-audit/$name.json",
            )
        }
    }

    private suspend fun assertFixture(marker: JSONObject, remapped: Boolean) {
        assertOnlyOwnedData(marker)
        val fixture = Fixture(marker.getString("uuid"))
        val manga = requireNotNull(mangas.getMangaByUrlAndSourceId(fixture.mangaUrl, LocalSource.ID))
        assertEquals(marker.getLong("mangaId"), manga.id)
        assertTrue(manga.favorite)
        assertEquals(fixture.title, manga.title)
        assertEquals(fixture.notes, manga.notes)
        assertEquals(3L, manga.viewerFlags)
        assertEquals(257L, manga.chapterFlags)
        val restoredCategories = categories.getAll()
        val alpha = restoredCategories.single { it.name == fixture.alpha }
        val beta = restoredCategories.single { it.name == fixture.beta }
        assertEquals(11L, alpha.flags)
        assertEquals(22L, beta.flags)
        assertEquals(listOf(alpha.id), categories.getCategoriesByMangaId(manga.id).map { it.id })
        if (remapped) {
            assertTrue("Alpha category must be remapped", marker.getLong("sourceAlphaId") != alpha.id)
            assertTrue("Beta category must be remapped", marker.getLong("sourceBetaId") != beta.id)
            assertEquals(marker.getLong("sentinelId"), restoredCategories.single { it.name == fixture.sentinel }.id)
        }
        val restoredChapters = chapters.getChapterByMangaId(manga.id, false)
        assertEquals(2, restoredChapters.size)
        val one = restoredChapters.single { it.url == fixture.chapterOne }
        val two = restoredChapters.single { it.url == fixture.chapterTwo }
        assertTrue(one.read)
        assertTrue(one.bookmark)
        assertEquals(7L, one.lastPageRead)
        assertFalse(two.read)
        assertFalse(two.bookmark)
        assertEquals(3L, two.lastPageRead)
        val restoredHistory = history.getHistoryByMangaId(manga.id).single()
        assertEquals(one.id, restoredHistory.chapterId)
        assertEquals(HISTORY_AT, restoredHistory.readAt!!.time)
        assertEquals(HISTORY_DURATION, restoredHistory.readDuration)
        val library = Injekt.get<LibraryPreferences>()
        assertEquals(alpha.id.toInt(), library.defaultCategory.get())
        val expectedExcluded = if (remapped) {
            setOf(beta.id.toString(), marker.getLong("sentinelId").toString())
        } else {
            setOf(beta.id.toString())
        }
        val expectedIncluded = if (remapped) {
            setOf(alpha.id.toString(), marker.getLong("sentinelId").toString())
        } else {
            setOf(alpha.id.toString())
        }
        // Production merges the category-set preferences with destination selections.
        assertEquals(expectedIncluded, library.updateCategories.get())
        assertEquals(expectedExcluded, library.updateCategoriesExclude.get())
        assertEquals(fixture.preferenceValue, store.getString(fixture.preferenceKey).get())
    }

    private fun assertBackup(marker: JSONObject, backup: Backup) {
        val fixture = Fixture(marker.getString("uuid"))
        val manga = backup.backupManga.single()
        assertEquals(LocalSource.ID, manga.source)
        assertEquals(fixture.mangaUrl, manga.url)
        assertEquals(fixture.title, manga.title)
        assertEquals(fixture.notes, manga.notes)
        assertTrue(manga.favorite)
        assertEquals(setOf(fixture.alpha, fixture.beta), backup.backupCategories.map { it.name }.toSet())
        val alpha = backup.backupCategories.single { it.name == fixture.alpha }
        val beta = backup.backupCategories.single { it.name == fixture.beta }
        assertEquals(marker.getLong("sourceAlphaId"), alpha.id)
        assertEquals(marker.getLong("sourceBetaId"), beta.id)
        assertEquals(listOf(alpha.order), manga.categories)
        assertEquals(2, manga.chapters.size)
        val one = manga.chapters.single { it.url == fixture.chapterOne }
        val two = manga.chapters.single { it.url == fixture.chapterTwo }
        assertTrue(one.read && one.bookmark)
        assertEquals(7L, one.lastPageRead)
        assertFalse(two.read || two.bookmark)
        assertEquals(3L, two.lastPageRead)
        assertEquals(fixture.chapterOne, manga.history.single().url)
        assertEquals(HISTORY_AT, manga.history.single().lastRead)
        assertEquals(HISTORY_DURATION, manga.history.single().readDuration)
        assertEquals(
            StringPreferenceValue(fixture.preferenceValue),
            backup.backupPreferences.single { it.key == fixture.preferenceKey }.value,
        )
        assertTrue(backup.backupExtensionStores.isEmpty())
    }

    private suspend fun assertOnlyOwnedData(marker: JSONObject) {
        val fixture = Fixture(marker.getString("uuid"))
        assertTrue(
            "Unexpected audit manga; refusing fixture operations",
            database.mangasQueries.getAllManga().awaitAsList().all {
                it.source == LocalSource.ID && it.url == fixture.mangaUrl
            },
        )
        assertTrue(
            "Unexpected audit category; refusing fixture operations",
            categories.getAll().all { it.isSystemCategory || it.name in fixture.categoryNames },
        )
        assertTrue("Unexpected audit extension store", database.extension_storeQueries.getAll().awaitAsList().isEmpty())
    }

    private fun assertIdle() {
        assertTrue(Injekt.get<DownloadManager>().queueState.value.isEmpty())
        assertTrue(Injekt.get<TranslationManager>().queueState.value.isEmpty())
        assertEquals(0, TranslationBusy.active.value)
    }

    private fun assertNoActiveBackupWork() {
        for (tag in listOf(CREATE_TAG, RESTORE_TAG)) {
            assertTrue("Audit $tag work must be idle", works(tag).all { it.state.isFinished })
        }
    }

    private fun enforceCleanupProcessBarrier(marker: JSONObject) {
        val previous = marker.optJSONObject("cleanupCancellationBarrier")
        if (previous != null && previous.getString("status") == "requiresProcessRestart") {
            val sameProcess = previous.getInt("processPid") == PROCESS_PID &&
                previous.getLong("processStartElapsed") == PROCESS_START_ELAPSED
            check(previous.getString("processSession") != PROCESS_SESSION_UUID && !sameProcess) {
                "Cleanup cancelled active work in this process; " +
                    "force-stop only the audit app and retry in a new process"
            }
        }
        val all = listOf(CREATE_TAG, RESTORE_TAG).flatMap(::works).distinctBy { it.id }
        val active = all.filterNot { it.state.isFinished }
        if (previous != null && previous.getString("status") == "requiresProcessRestart") {
            check(active.isEmpty()) { "Backup work is active after restart; preserving the cancellation checkpoint" }
        }
        if (active.isNotEmpty()) {
            for (work in active) {
                val beforeKey = if (CREATE_TAG in work.tags) "beforeCreateIds" else "beforeRestoreIds"
                check(work.id.toString() !in marker.getJSONArray(beforeKey).strings()) {
                    "Refusing to cancel a backup task that predates this fixture"
                }
                observeOwnedWorkDocument(marker, work)
            }
            val observations = JSONArray()
            active.forEach { work ->
                observations.put(
                    JSONObject().put("id", work.id.toString())
                        .put("stateBeforeCancellation", work.state.name)
                        .put("attemptsBeforeCancellation", work.runAttemptCount)
                        .put("cancellationObserved", false),
                )
            }
            val barrier = JSONObject().put("status", "requiresProcessRestart")
                .put("processSession", PROCESS_SESSION_UUID)
                .put("processPid", PROCESS_PID)
                .put("processStartElapsed", PROCESS_START_ELAPSED)
                .put("checkpointedAt", System.currentTimeMillis())
                .put("jobs", observations)
            marker.put("cleanupCancellationBarrier", barrier)
            saveMarker(marker) // Persist the barrier before the first cancellation request.
            val failures = mutableListOf<Throwable>()
            active.forEachIndexed { index, work ->
                try {
                    workManager.cancelWorkById(work.id).result.get(10, TimeUnit.SECONDS)
                    val after = requireNotNull(workManager.getWorkInfoById(work.id).get(10, TimeUnit.SECONDS))
                    check(after.state.isFinished) { "Cancellation has not reached a terminal WorkInfo state" }
                    observations.getJSONObject(index)
                        .put("cancellationObserved", true)
                        .put("attemptsAfterCancellation", after.runAttemptCount)
                        .put("stateAfterCancellation", after.state.name)
                    saveMarker(marker)
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
            throw AssertionError(
                "Cleanup requested cancellation; force-stop only the audit app and retry in a new process " +
                    "before touching fixture databases, preferences, files, grants, or notifications",
            ).apply { failures.forEach(::addSuppressed) }
        }
        if (previous != null && previous.getString("status") == "requiresProcessRestart") {
            val jobs = previous.getJSONArray("jobs")
            for (index in 0 until jobs.length()) {
                val job = jobs.getJSONObject(index)
                val id = UUID.fromString(job.getString("id"))
                val after = requireNotNull(
                    workManager.getWorkInfoById(id).get(10, TimeUnit.SECONDS),
                )
                check(after.id == id) { "Cancellation checkpoint work ID does not match" }
                check(after.state.isFinished) { "Cancelled backup work started again in the new process" }
                val beforeKey = when {
                    CREATE_TAG in after.tags -> "beforeCreateIds"
                    RESTORE_TAG in after.tags -> "beforeRestoreIds"
                    else -> error("Cancellation checkpoint no longer identifies a backup task")
                }
                check(id.toString() !in marker.getJSONArray(beforeKey).strings()) {
                    "Cancellation checkpoint identifies work that predates this fixture"
                }
                observeOwnedWorkDocument(marker, after)
                if (!job.getBoolean("cancellationObserved")) {
                    check(!WorkInfo.State.valueOf(job.getString("stateBeforeCancellation")).isFinished) {
                        "Incomplete cancellation checkpoint did not record an active task"
                    }
                    check(after.runAttemptCount == job.getInt("attemptsBeforeCancellation")) {
                        "Incomplete cancellation checkpoint cannot prove that the task was not retried"
                    }
                    assertNoActiveBackupWork()
                    val stable = requireNotNull(workManager.getWorkInfoById(id).get(10, TimeUnit.SECONDS))
                    check(stable.id == id && stable.state.isFinished && stable.state == after.state) {
                        "Task state changed while reconciling the cancellation checkpoint"
                    }
                    check(stable.runAttemptCount == job.getInt("attemptsBeforeCancellation")) {
                        "Task attempt changed while reconciling the cancellation checkpoint"
                    }
                    job.put("cancellationObserved", true)
                        .put("attemptsAfterCancellation", stable.runAttemptCount)
                        .put("stateAfterCancellation", stable.state.name)
                        .put("reconciledAfterProcessRestart", true)
                        .put("reconciledProcessSession", PROCESS_SESSION_UUID)
                        .put("reconciledAt", System.currentTimeMillis())
                    saveMarker(marker)
                }
                check(after.runAttemptCount == job.getInt("attemptsAfterCancellation")) {
                    "Cancelled backup work was retried after the cancellation checkpoint"
                }
                check(after.state.name == job.getString("stateAfterCancellation")) {
                    "Cancelled backup work changed terminal state after the cancellation checkpoint"
                }
            }
            previous.put("status", "restartVerified")
                .put("verifiedProcessSession", PROCESS_SESSION_UUID)
                .put("verifiedProcessPid", PROCESS_PID)
                .put("verifiedProcessStartElapsed", PROCESS_START_ELAPSED)
                .put("verifiedAt", System.currentTimeMillis())
            saveMarker(marker)
        }
    }

    private fun works(tag: String): List<WorkInfo> = workManager.getWorkInfosByTag(tag).get(10, TimeUnit.SECONDS)

    private fun workIds(tag: String) = JSONArray(works(tag).map { it.id.toString() })

    private suspend fun awaitNewSuccessfulWork(
        tag: String,
        before: JSONArray,
        observed: (WorkInfo) -> Unit,
    ): WorkInfo = withTimeout(60_000) {
        val previous = before.strings()
        while (true) {
            val candidates = works(tag).filter { it.id.toString() !in previous }
            assertTrue("Expected one actual UI $tag request", candidates.size <= 1)
            val work = candidates.singleOrNull()
            if (work != null) observed(work)
            if (work != null && work.state.isFinished) {
                assertEquals("Actual UI $tag job failed", WorkInfo.State.SUCCEEDED, work.state)
                return@withTimeout work
            }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }

    private fun workInput(id: UUID): Data {
        // Public WorkInfo omits inputData. This is read-only observation of the actual job.
        val manager = workManager as WorkManagerImpl
        // Keep Room-backed types behind Any: Room is available to WorkManager at runtime,
        // but is deliberately not added to this fixture's compile dependencies.
        val workDatabase: Any = requireNotNull(
            manager.javaClass.getMethod("getWorkDatabase").invoke(manager),
        )
        val dao: Any = requireNotNull(workDatabase.javaClass.getMethod("workSpecDao").invoke(workDatabase))
        val spec: Any = requireNotNull(
            dao.javaClass.getMethod("getWorkSpec", String::class.java).invoke(dao, id.toString()),
        )
        return (spec as androidx.work.impl.model.WorkSpec).input
    }

    private fun observeOwnedWorkDocument(marker: JSONObject, work: WorkInfo): JSONObject {
        val fixture = Fixture(marker.getString("uuid"))
        val uri = requireNotNull(workInput(work.id).getString("location_uri")).let(Uri::parse)
        check(uri.scheme == "content") { "Only actual SAF content documents are owned" }
        val documents = marker.optJSONArray("ownedDocuments") ?: JSONArray().also {
            marker.put("ownedDocuments", it)
        }
        for (index in 0 until documents.length()) {
            val existing = documents.getJSONObject(index)
            if (existing.getString("uri") == uri.toString()) {
                check(existing.getString("expectedName") == fixture.filename)
                return existing // Retained ownership evidence survives document deletion and restarts.
            }
        }
        val inspected = inspectDocument(uri)
        val evidence = if (inspected.missing) {
            // A failed creator can delete its output before this helper observes the WorkInfo.
            // An opaque document ID cannot prove the UUID, so it is deliberately rejected here.
            val id = android.provider.DocumentsContract.getDocumentId(uri)
            check(id.substringAfterLast('/') == fixture.filename) {
                "Missing document has no verifiable fixture UUID in its document ID"
            }
            "actual-work-input-uuid-document-id-and-provider-absence"
        } else {
            check(inspected.name == fixture.filename) { "Actual work points to an unowned document" }
            "actual-work-input-and-provider-display-name"
        }
        val document = JSONObject()
            .put("uri", uri.toString())
            .put("expectedName", fixture.filename)
            .put("observedWorkId", work.id.toString())
            .put("ownershipEvidence", evidence)
            .put("ownershipVerifiedAt", System.currentTimeMillis())
            .put("deleted", inspected.missing)
            .put("deleteOutcome", if (inspected.missing) inspected.reason else "notAttempted")
            .put("grantReleased", false)
        documents.put(document)
        if (CREATE_TAG in work.tags) marker.put("backupUri", uri.toString())
        saveMarker(marker) // Save ownership before subsequent option/state/file/notification assertions.
        return document
    }

    private fun deleteOwnedDocument(marker: JSONObject, document: JSONObject) {
        val fixture = Fixture(marker.getString("uuid"))
        check(document.getString("expectedName") == fixture.filename)
        if (document.getBoolean("deleted")) return
        val uri = Uri.parse(document.getString("uri"))
        val inspected = inspectDocument(uri)
        if (inspected.missing) {
            document.put("deleted", true).put("deleteOutcome", inspected.reason)
            saveMarker(marker)
            return
        }
        check(inspected.name == fixture.filename) { "Refusing to delete an unowned document" }
        // The ownership checkpoint above exists before this destructive operation.
        val deleted = try {
            android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: FileNotFoundException) {
            val after = inspectDocument(uri)
            check(after.missing) { "Provider did not confirm absence after FileNotFoundException" }
            true
        }
        if (!deleted) {
            val after = inspectDocument(uri)
            check(after.missing) { "Provider refused to delete the owned document" }
        }
        document.put("deleted", true).put("deleteOutcome", "deletedOrProviderConfirmedAbsent")
            .put("deleteVerifiedAt", System.currentTimeMillis())
        saveMarker(marker) // Grant release may fail independently; do not delete/query twice on retry.
    }

    private fun releaseOwnedDocumentGrant(marker: JSONObject, document: JSONObject) {
        val fixture = Fixture(marker.getString("uuid"))
        check(document.getString("expectedName") == fixture.filename)
        if (document.getBoolean("grantReleased")) return
        val uri = Uri.parse(document.getString("uri"))
        val grants = context.contentResolver.persistedUriPermissions.filter { it.uri == uri }
        if (grants.isNotEmpty()) {
            // Retain access for a later retry if the provider has not confirmed document removal.
            check(document.getBoolean("deleted")) { "Retaining owned URI grant to retry document deletion" }
            for (permission in grants) {
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            }
        }
        check(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
        document.put("grantReleased", true).put("grantReleaseVerifiedAt", System.currentTimeMillis())
        saveMarker(marker)
    }

    private fun inspectDocument(uri: Uri): DocumentInspection {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
        return try {
            val cursor = requireNotNull(context.contentResolver.query(uri, columns, null, null, null)) {
                "Provider returned no cursor; absence is unproven"
            }
            cursor.use {
                if (it.moveToFirst()) {
                    DocumentInspection(it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                } else {
                    DocumentInspection(missing = true, reason = "providerReturnedEmptyCursor")
                }
            }
        } catch (_: FileNotFoundException) {
            DocumentInspection(missing = true, reason = "providerReportedFileNotFound")
        }
        // SecurityException and other provider errors propagate; they are not treated as absence.
    }

    private fun readOwnedBackup(uri: Uri, fixture: Fixture): ByteArray {
        assertEquals("Actual SAF must supply a content URI", "content", uri.scheme)
        assertEquals(
            "Choose the UUID filename from the seed report in the actual SAF picker",
            fixture.filename,
            documentName(uri),
        )
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        assertTrue("Neutral fixture backup must be small", bytes.size in 3..2_000_000)
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
        return bytes
    }

    private fun documentName(uri: Uri): String {
        val inspected = inspectDocument(uri)
        check(!inspected.missing) { "Owned SAF document is missing: ${inspected.reason}" }
        return requireNotNull(inspected.name)
    }

    private fun assertNotificationsAvailable() {
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
            )
        }
        assertTrue(
            "Audit notification permission must be allowed",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        val channelIds = listOf(
            Notifications.CHANNEL_BACKUP_RESTORE_COMPLETE,
            Notifications.CHANNEL_BACKUP_RESTORE_PROGRESS,
        )
        for (id in channelIds) {
            val channel = requireNotNull(notificationManager.getNotificationChannel(id))
            assertTrue(
                "Audit notification channel must be enabled",
                channel.importance != NotificationManager.IMPORTANCE_NONE,
            )
        }
    }

    private suspend fun assertCompletedNotification(
        id: Int,
        progressId: Int,
        title: String,
        after: Long,
    ): Notification = withTimeout(10_000) {
        while (true) {
            val active = notificationManager.activeNotifications
            val posted = active.singleOrNull { it.id == id }
            if (posted != null && posted.postTime >= after && active.none { it.id == progressId }) {
                assertEquals(AUDIT_PACKAGE, posted.packageName)
                assertEquals(Notifications.CHANNEL_BACKUP_RESTORE_COMPLETE, posted.notification.channelId)
                assertEquals(title, posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                return@withTimeout posted.notification
            }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }

    private fun loadMarker(vararg stages: String): JSONObject {
        val marker = JSONObject(markerFile.readText())
        assertEquals(1, marker.getInt("schema"))
        assertEquals(AUDIT_PACKAGE, marker.getString("package"))
        val fixture = Fixture(UUID.fromString(marker.getString("uuid")).toString())
        assertEquals(fixture.expected().toString(), marker.getJSONObject("expected").toString())
        if (stages.isNotEmpty()) {
            assertTrue("Unexpected audit phase: ${marker.getString("stage")}", marker.getString("stage") in stages)
        }
        return marker
    }

    private fun saveMarker(marker: JSONObject) = writeJson(markerFile, marker)

    private fun writeJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(value.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun flushPreferences() {
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
    }

    private fun restorePreferences(saved: JSONArray) {
        val allowed = allowedPreferenceKeys(Fixture(loadMarker().getString("uuid")))
        for (index in 0 until saved.length()) check(saved.getJSONObject(index).getString("key") in allowed)
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        for (index in 0 until saved.length()) {
            val entry = saved.getJSONObject(index)
            val key = entry.getString("key")
            if (!entry.getBoolean("present")) {
                editor.remove(key)
            } else {
                when (entry.getString("type")) {
                    "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
                    "int" -> editor.putInt(key, entry.getInt("value"))
                    "string" -> editor.putString(key, entry.getString("value"))
                    "set" -> editor.putStringSet(key, entry.getJSONArray("value").strings())
                    else -> error("Unsupported saved audit preference")
                }
            }
        }
        assertTrue(editor.commit())
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        for (index in 0 until saved.length()) {
            val entry = saved.getJSONObject(index)
            val key = entry.getString("key")
            assertEquals(entry.getBoolean("present"), preferences.contains(key))
            if (entry.getBoolean("present")) {
                val expected: Any = when (entry.getString("type")) {
                    "boolean" -> entry.getBoolean("value")
                    "int" -> entry.getInt("value")
                    "string" -> entry.getString("value")
                    "set" -> entry.getJSONArray("value").strings()
                    else -> error("Unsupported saved audit preference")
                }
                val actual: Any? = when (entry.getString("type")) {
                    "boolean" -> preferences.getBoolean(key, false)
                    "int" -> preferences.getInt(key, 0)
                    "string" -> preferences.getString(key, null)
                    "set" -> preferences.getStringSet(key, null)
                    else -> error("Unsupported saved audit preference")
                }
                assertEquals(expected, actual)
            }
        }
    }

    private fun allowedPreferenceKeys(fixture: Fixture): Set<String> {
        val base = Injekt.get<BasePreferences>()
        val library = Injekt.get<LibraryPreferences>()
        val translation = Injekt.get<TranslationPreferences>()
        return setOf(
            base.shownOnboardingFlow.key(), base.incognitoMode.key(), library.autoClearChapterCache.key(),
            library.autoUpdateInterval.key(), library.autoRefreshMangaOnOpen.key(), translation.liveTranslate.key(),
            translation.translationMasterEnabled.key(), Injekt.get<BackupPreferences>().backupInterval.key(),
            Injekt.get<SecurityPreferences>().hideNotificationContent.key(), library.defaultCategory.key(),
            library.updateCategories.key(),
            library.updateCategoriesExclude.key(),
            library.categorizedDisplaySettings.key(),
            fixture.preferenceKey,
        )
    }

    private class SavedPreference<T>(private val preference: Preference<T>, private val newValue: T) {
        fun snapshot(): JSONObject {
            val value = preference.get()
            val type = when (value) {
                is Boolean -> "boolean"
                is Int -> "int"
                is String -> "string"
                is Set<*> -> "set"
                else -> error("Unsupported audit preference type")
            }
            return JSONObject().put("key", preference.key()).put("present", preference.isSet()).put("type", type)
                .put("value", if (value is Set<*>) JSONArray(value.toList()) else value)
        }

        fun apply() = preference.set(newValue)
    }

    private data class Fixture(val uuid: String) {
        val alpha = "Audit alpha $uuid"
        val beta = "Audit beta $uuid"
        val sentinel = "Audit destination $uuid"
        val categoryNames = setOf(alpha, beta, sentinel)
        val mangaUrl = "backup-ui-audit-$uuid"
        val title = "Neutral backup audit $uuid"
        val notes = "Neutral notes $uuid"
        val chapterOne = "$mangaUrl/chapter-one"
        val chapterTwo = "$mangaUrl/chapter-two"
        val preferenceKey = "backup_ui_audit_fixture_$uuid"
        val preferenceValue = "Neutral preference $uuid"
        val filename = "yomitori-backup-ui-audit-$uuid.tachibk"

        fun expected(): JSONObject = JSONObject().put("mangaUrl", mangaUrl).put("title", title).put("notes", notes)
            .put("alpha", alpha).put("beta", beta).put("sentinel", sentinel).put("chapterOne", chapterOne)
            .put("chapterTwo", chapterTwo).put("preferenceKey", preferenceKey).put("preferenceValue", preferenceValue)
            .put("historyAt", HISTORY_AT)
            .put("historyDuration", HISTORY_DURATION)
            .put("suggestedBackupFilename", filename)
    }

    private data class DocumentInspection(
        val name: String? = null,
        val missing: Boolean = false,
        val reason: String = "present",
    )

    private fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val CREATE_TAG = "BackupCreator:manual"
        const val RESTORE_TAG = "BackupRestore"
        const val HISTORY_AT = 1_700_000_000_000L
        const val HISTORY_DURATION = 42_000L
        val DEFAULT_CREATE_OPTIONS = BooleanArray(10) { it != 9 }
        val PROCESS_SESSION_UUID = UUID.randomUUID().toString()
        val PROCESS_PID = android.os.Process.myPid()
        val PROCESS_START_ELAPSED = android.os.Process.getStartElapsedRealtime()
    }
}
