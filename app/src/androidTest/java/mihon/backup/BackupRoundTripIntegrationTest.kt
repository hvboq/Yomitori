package mihon.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.util.Xml
import androidx.preference.PreferenceScreen
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsList
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreCallbacks
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.repository.ExtensionStoreRepositoryImpl
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.interactor.GetExtensionStores
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Actual file/database/preferences round trips, isolated from the app's database and preferences.
 * This does not exercise SAF selection, WorkManager, notifications, or image/model backups.
 */
@OptIn(ExperimentalSerializationApi::class)
@RunWith(AndroidJUnit4::class)
class BackupRoundTripIntegrationTest {
    @Test
    fun actualGzipBackupRestoresAllSectionsAndIsDurableAndIdempotent() = runBlocking {
        Fixture().use { fixture ->
            fixture.seedSource()
            fixture.seedDestination()
            val file = fixture.createBackup()
            val decoded = fixture.verifyFile(file)
            fixture.assertDecoded(decoded)

            val first = fixture.destination.restore(file)
            assertSuccessful(first)
            fixture.assertDestination()
            fixture.destination.reopenDatabase()
            fixture.assertDestination()

            val second = fixture.destination.restore(file)
            assertSuccessful(second)
            fixture.assertDestination()
            fixture.destination.reopenDatabase()
            fixture.assertDestination()
            assertEquals(2, fixture.destination.invalidations.get())
            assertEquals(2, fixture.destination.reschedules.get())
        }
    }

    @Test
    fun truncatedGzipAndInvalidProtobufDoNotMutateDestinationOrRunCompletionSideEffects() = runBlocking {
        Fixture().use { fixture ->
            fixture.seedSource()
            fixture.seedDestination()
            val good = fixture.createBackup()
            fixture.seedCorruptionSentinel()
            val bytes = good.readBytes()
            val truncated = File(fixture.root, "truncated.tachibk").apply {
                writeBytes(bytes.copyOf(bytes.size / 2))
            }
            val invalidProto = File(fixture.root, "invalid-protobuf.tachibk").apply {
                GZIPOutputStream(outputStream()).use { it.write(byteArrayOf(0x0a, 0x7f, 0x01)) }
            }
            val before = fixture.destination.snapshot()
            for (file in listOf(truncated, invalidProto)) {
                assertTrue(
                    "actual validator must reject corrupt input",
                    runCatching {
                        fixture.validator.validate(Uri.fromFile(file))
                    }.isFailure,
                )
                val callbacks = RecordingCallbacks()
                assertTrue(
                    "actual restore must reject before applying any section",
                    runCatching {
                        fixture.destination.restorer(callbacks).restore(Uri.fromFile(file), RestoreOptions())
                    }.isFailure,
                )
                assertEquals(before, fixture.destination.snapshot())
                assertTrue(callbacks.progress.isEmpty())
                assertTrue(callbacks.completions.isEmpty())
                assertEquals(0, fixture.destination.invalidations.get())
                assertEquals(0, fixture.destination.reschedules.get())
            }
        }
    }

    @Test
    fun categoryCreationCompletesBeforeDependentMangaAndPreferenceRemapping() = runBlocking {
        Fixture().use { fixture ->
            fixture.seedSource()
            fixture.seedDestination()
            val file = fixture.createBackup()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dependentEntered = CompletableDeferred<Unit>()
            val held = AtomicBoolean(true)
            val dependentReadsWhileHeld = AtomicInteger()
            val destination = fixture.destination
            val gateRepository = object : CategoryRepository by destination.categoryRepository {
                override suspend fun getAll(): List<Category> {
                    entered.complete(Unit)
                    release.await()
                    return destination.categoryRepository.getAll()
                }
            }
            val dependentRepository = object : CategoryRepository by destination.categoryRepository {
                override suspend fun getAll(): List<Category> {
                    if (held.get()) {
                        dependentReadsWhileHeld.incrementAndGet()
                        dependentEntered.complete(Unit)
                    }
                    return destination.categoryRepository.getAll()
                }
            }
            val callbacks = RecordingCallbacks()
            coroutineScope {
                val operation = async(Dispatchers.Default) {
                    destination.restorer(
                        callbacks,
                        categoryCreation = GetCategories(gateRepository),
                        dependentCategories = GetCategories(dependentRepository),
                    ).restore(Uri.fromFile(file), RestoreOptions())
                }
                try {
                    withTimeout(10_000) { entered.await() }
                    // Correctly ordered restoration waits here. The previous parallel launch lets
                    // dependent getAll calls run against the incomplete destination category table.
                    withTimeoutOrNull(200) { dependentEntered.await() }
                } finally {
                    held.set(false)
                    release.complete(Unit)
                    withTimeout(20_000) { operation.await() }
                }
            }
            assertEquals(
                "no dependent category lookup while category creation is blocked",
                0,
                dependentReadsWhileHeld.get(),
            )
            assertSuccessful(callbacks)
            fixture.assertDestination()
        }
    }

    @Test
    fun excludingCategoriesPreservesExistingCategorySelectionsAndRestoresOtherSections() = runBlocking {
        Fixture().use { fixture ->
            fixture.seedSource()
            fixture.seedDestination()
            val destination = fixture.destination
            val existingBeta = destination.categoryRepository.getAll().single { it.name == BETA }
            val sourceBeta = fixture.source.categoryRepository.getAll().single { it.name == BETA }
            assertEquals(1L, existingBeta.id)
            assertEquals(2L, sourceBeta.id)
            // Different numeric defaults make accidental direct backup-ID copying observable.
            assertTrue(
                fixture.source.appPreferences.edit()
                    .putInt(LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY, sourceBeta.id.toInt())
                    .commit(),
            )
            val originalSelection = setOf(Category.UNCATEGORIZED_ID.toString(), existingBeta.id.toString())
            assertTrue(
                destination.appPreferences.edit()
                    .putInt(LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY, existingBeta.id.toInt())
                    .putStringSet("library_update_categories", originalSelection)
                    .putStringSet("library_update_categories_exclude", originalSelection)
                    .commit(),
            )
            val file = fixture.createBackup()
            val decoded = fixture.verifyFile(file)
            assertEquals(setOf(ALPHA, BETA), decoded.backupCategories.map { it.name }.toSet())
            assertEquals(
                IntPreferenceValue(sourceBeta.id.toInt()),
                decoded.backupPreferences.single { it.key == LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY }.value,
            )
            val callbacks = RecordingCallbacks()
            withContext(Dispatchers.IO) {
                destination.restorer(callbacks).restore(Uri.fromFile(file), RestoreOptions(categories = false))
            }
            assertSuccessful(callbacks)
            assertEquals(
                listOf(existingBeta),
                destination.categoryRepository.getAll().filterNot {
                    it.isSystemCategory
                },
            )
            assertEquals(
                existingBeta.id.toInt(),
                destination.appPreferences.getInt(LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY, -1),
            )
            assertEquals(originalSelection, destination.appPreferences.getStringSet("library_update_categories", null))
            assertEquals(
                originalSelection,
                destination.appPreferences.getStringSet("library_update_categories_exclude", null),
            )

            val favorite = destination.mangaRepository.getMangaByUrlAndSourceId(FAVORITE, SOURCE_ID)!!
            assertTrue(favorite.favorite)
            assertEquals("Fixture favorite", favorite.title)
            assertEquals("Fixture notes", favorite.notes)
            assertEquals(MANGA_MEMO, favorite.memo)
            assertTrue(
                "excluded backup categories must not be linked by stale numeric IDs",
                destination.categoryRepository.getCategoriesByMangaId(favorite.id).isEmpty(),
            )
            assertEquals(2, destination.database.mangasQueries.getAllManga().awaitAsList().size)
            assertEquals(1, destination.mangaRepository.getFavorites().size)
            assertEquals(1, destination.mangaRepository.getReadMangaNotInLibrary().size)
            assertFalse(destination.mangaRepository.getMangaByUrlAndSourceId(READ_OUTSIDE, SOURCE_ID)!!.favorite)
            assertNull(destination.mangaRepository.getMangaByUrlAndSourceId(UNREAD_OUTSIDE, SOURCE_ID))
            val chapters = destination.chapterRepository.getChapterByMangaId(favorite.id, false)
            assertEquals(2, chapters.size)
            val one = chapters.single { it.url == CHAPTER_ONE }
            val two = chapters.single { it.url == CHAPTER_TWO }
            assertTrue(one.read)
            assertTrue(one.bookmark)
            assertEquals(7L, one.lastPageRead)
            assertEquals(CHAPTER_MEMO, one.memo)
            assertFalse(two.read)
            assertEquals(3L, two.lastPageRead)
            val history = destination.historyRepository.getHistoryByMangaId(favorite.id).single()
            assertEquals(one.id, history.chapterId)
            assertEquals(HISTORY_AT, history.readAt?.time)
            assertEquals(HISTORY_DURATION, history.readDuration)
            assertEquals(HISTORY_DURATION, destination.historyRepository.getTotalReadDuration())
            val track = destination.trackRepository.getTracksByMangaId(favorite.id).single()
            assertEquals(expectedTrack(favorite.id).copy(id = track.id), track)
            assertEquals(
                listOf(EXCLUDED),
                destination.database.excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(
                    favorite.id,
                ).awaitAsList(),
            )
            assertEquals(EXTENSION_INDEX, destination.extensionRepository.getAll().single().indexUrl)
            assertSixTypes(destination.appPreferences, "app")
            assertSixTypes(destination.sourcePreferences, "source")
            assertEquals(ENDPOINT, destination.appPreferences.getString("translation_api_base", null))
            assertEquals("fixture-model", destination.appPreferences.getString("translation_model", null))
            assertEquals("unchanged", destination.appPreferences.getString("destination_sentinel", null))
            assertFalse(destination.appPreferences.contains("translation_api_key_enc__custom"))
            assertFalse(destination.appPreferences.contains("translation_api_key_enc__sakura"))
            destination.assertPreferencesOnDisk()
            assertEquals(1, destination.invalidations.get())
            assertEquals(1, destination.reschedules.get())
        }
    }

    @Test
    fun cancellingWhileCategoryCreationIsBlockedCancelsDependentStagesAndCompletionSideEffects() = runBlocking {
        Fixture().use { fixture ->
            fixture.seedSource()
            fixture.seedDestination()
            val file = fixture.createBackup()
            val destination = fixture.destination
            val beforeAppPreferences = destination.appPreferences.all.toMap()
            val beforeCategories = destination.categoryRepository.getAll()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dependentReads = AtomicInteger()
            val gateRepository = object : CategoryRepository by destination.categoryRepository {
                override suspend fun getAll(): List<Category> {
                    entered.complete(Unit)
                    release.await()
                    return destination.categoryRepository.getAll()
                }
            }
            val dependentRepository = object : CategoryRepository by destination.categoryRepository {
                override suspend fun getAll(): List<Category> {
                    dependentReads.incrementAndGet()
                    return destination.categoryRepository.getAll()
                }
            }
            val callbacks = RecordingCallbacks()
            coroutineScope {
                val operation = async(Dispatchers.Default) {
                    destination.restorer(
                        callbacks,
                        categoryCreation = GetCategories(gateRepository),
                        dependentCategories = GetCategories(dependentRepository),
                    ).restore(Uri.fromFile(file), RestoreOptions())
                }
                try {
                    withTimeout(10_000) { entered.await() }
                    // The category gate remains closed until cancellation has fully completed.
                    withTimeout(10_000) { operation.cancelAndJoin() }
                    assertTrue(operation.isCancelled)
                    assertTrue(operation.isCompleted)
                    assertFalse(release.isCompleted)
                } finally {
                    // Always release our gate and join our job, including timeout/assertion paths.
                    withContext(NonCancellable) {
                        release.complete(Unit)
                        withTimeout(20_000) { operation.cancelAndJoin() }
                    }
                }
            }
            assertEquals(0, dependentReads.get())
            assertEquals(beforeCategories, destination.categoryRepository.getAll())
            assertEquals(
                listOf(BETA),
                destination.categoryRepository.getAll().filterNot {
                    it.isSystemCategory
                }.map { it.name },
            )
            assertEquals(beforeAppPreferences, destination.appPreferences.all.toMap())
            assertTrue(destination.database.mangasQueries.getAllManga().awaitAsList().isEmpty())
            assertTrue(callbacks.completions.isEmpty())
            assertEquals(0, destination.invalidations.get())
            assertEquals(0, destination.reschedules.get())
            // Source preferences/extension stores are independent stages and may finish before
            // cancellation. Their persistence is deliberately not treated as atomic rollback.
        }
    }

    private fun assertSuccessful(callbacks: RecordingCallbacks) {
        assertEquals(1, callbacks.completions.size)
        assertEquals(0, callbacks.completions.single())
        assertTrue(callbacks.progress.isNotEmpty())
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val namespace = "backup-roundtrip-${UUID.randomUUID()}"
        val root = File(context.cacheDir, namespace).apply { check(mkdirs()) }
        val source = IsolatedState(context, root, "$namespace-a")
        val destination = IsolatedState(context, root, "$namespace-b")
        private val registry = FixtureSourceManager(source.sourcePreferences)
        val trackerLookups = CopyOnWriteArrayList<Long>()
        private val parser = ProtoBuf
        private val decoder = BackupDecoder(source.context, parser)
        val validator = BackupFileValidator(
            context = source.context,
            sourceManager = registry,
            trackerLookup = {
                trackerLookups += it
                null
            },
            decoder = decoder,
        )

        suspend fun seedSource() {
            source.categoryRepository.insert(Category(-1, ALPHA, 10, 11))
            source.categoryRepository.insert(Category(-1, BETA, 20, 22))
            val alpha = source.categoryRepository.getAll().single { it.name == ALPHA }
            val beta = source.categoryRepository.getAll().single { it.name == BETA }
            assertEquals(1L, alpha.id)
            assertEquals(2L, beta.id)
            val mangas = source.mangaRepository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = SOURCE_ID,
                        url = FAVORITE,
                        title = "Fixture favorite",
                        favorite = true,
                        initialized = true,
                        author = "Fixture author",
                        artist = "Fixture artist",
                        description = "Fixture description",
                        genre = listOf("Fixture genre"),
                        status = SManga.ONGOING.toLong(),
                        dateAdded = 1_700_000_000_000,
                        viewerFlags = 3,
                        chapterFlags = 257,
                        version = 4,
                        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                        memo = MANGA_MEMO,
                    ),
                    Manga.create().copy(source = SOURCE_ID, url = READ_OUTSIDE, title = "Fixture read outside library"),
                    Manga.create().copy(
                        source = SOURCE_ID,
                        url = UNREAD_OUTSIDE,
                        title = "Fixture unread outside library",
                    ),
                ),
            )
            assertEquals(3, mangas.size)
            val favorite = mangas.single { it.url == FAVORITE }
            val read = mangas.single { it.url == READ_OUTSIDE }
            val unread = mangas.single { it.url == UNREAD_OUTSIDE }
            assertTrue(source.mangaRepository.update(MangaUpdate(id = favorite.id, notes = "Fixture notes")))
            source.mangaRepository.setMangaCategories(favorite.id, listOf(alpha.id))
            source.mangaRepository.setMangaCategories(read.id, listOf(beta.id))
            val chapters = source.chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = favorite.id,
                        url = CHAPTER_ONE,
                        name = "Fixture one",
                        read = true,
                        bookmark = true,
                        lastPageRead = 7,
                        chapterNumber = 1.0,
                        sourceOrder = 1,
                        scanlator = "Fixture visible",
                        dateFetch = 1_700_000_000_000,
                        dateUpload = 1_699_000_000_000,
                        version = 3,
                        memo = CHAPTER_MEMO,
                    ),
                    Chapter.create().copy(
                        mangaId = favorite.id,
                        url = CHAPTER_TWO,
                        name = "Fixture partial",
                        read = false,
                        lastPageRead = 3,
                        chapterNumber = 2.0,
                        scanlator = EXCLUDED,
                    ),
                    Chapter.create().copy(
                        mangaId = read.id,
                        url = "$READ_OUTSIDE/chapter-1",
                        name = "Fixture outside",
                        read = true,
                        lastPageRead = 5,
                    ),
                    Chapter.create().copy(
                        mangaId = unread.id,
                        url = "$UNREAD_OUTSIDE/chapter-1",
                        name = "Fixture unread",
                    ),
                ),
            )
            assertEquals(4, chapters.size)
            source.historyRepository.upsertHistory(
                HistoryUpdate(chapters.single { it.url == CHAPTER_ONE }.id, Date(HISTORY_AT), HISTORY_DURATION),
            )
            source.trackRepository.insert(expectedTrack(favorite.id))
            source.database.excluded_scanlatorsQueries.insert(favorite.id, EXCLUDED)
            source.database.extension_storeQueries.upsert(
                indexUrl = EXTENSION_INDEX,
                name = "Backup fixture store",
                badgeLabel = "Fixture",
                signingKey = "fixture-not-a-real-signing-key",
                contactWebsite = "https://backup-fixture.invalid",
                contactDiscord = "https://backup-fixture.invalid/community",
                isLegacy = false,
                extensionListUrl = "https://backup-fixture.invalid/extensions.json",
            )
            putSixTypes(source.appPreferences, "app")
            putSixTypes(source.sourcePreferences, "source")
            assertTrue(
                source.appPreferences.edit()
                    .putString("translation_api_base", ENDPOINT)
                    .putString("translation_model", "fixture-model")
                    .putString("translation_api_key", "fixture-only-legacy-credential")
                    .putString("translation_api_key_enc", "fixture-only-encrypted-credential")
                    .putString("translation_api_key_enc__custom", "fixture-only-custom-credential")
                    .putString("translation_api_key_enc__sakura", "fixture-only-sakura-credential")
                    .putInt(LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY, alpha.id.toInt())
                    .putStringSet("library_update_categories", setOf(alpha.id.toString()))
                    .putStringSet("library_update_categories_exclude", setOf(beta.id.toString()))
                    .putString(Preference.privateKey("fixture_private"), "fixture-private-preference")
                    .putString(Preference.appStateKey("fixture_state"), "fixture-state-excluded")
                    .commit(),
            )
        }

        suspend fun seedDestination() {
            destination.categoryRepository.insert(Category(-1, BETA, 0, 22))
            assertEquals(1L, destination.categoryRepository.getAll().single { it.name == BETA }.id)
            assertTrue(
                destination.appPreferences.edit()
                    .putString("translation_api_base", "https://destination-fixture.invalid/v1/")
                    .putString("translation_api_key_enc__custom", "fixture-only-destination-custom")
                    .putString("translation_api_key_enc__sakura", "fixture-only-destination-sakura")
                    .putString("destination_sentinel", "unchanged")
                    .commit(),
            )
            assertTrue(
                destination.sourcePreferences.edit().putString("destination_source_sentinel", "unchanged").commit(),
            )
        }

        suspend fun seedCorruptionSentinel() {
            val manga = destination.mangaRepository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = SOURCE_ID,
                        url = "/backup-fixture/sentinel",
                        title = "Destination sentinel",
                    ),
                ),
            ).single()
            val beta = destination.categoryRepository.getAll().single { it.name == BETA }
            destination.mangaRepository.setMangaCategories(manga.id, listOf(beta.id))
            val chapter = destination.chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/backup-fixture/sentinel/chapter",
                        name = "Sentinel",
                        read = true,
                        lastPageRead = 2,
                    ),
                ),
            ).single()
            destination.historyRepository.upsertHistory(HistoryUpdate(chapter.id, Date(HISTORY_AT - 1), 123))
            destination.trackRepository.insert(expectedTrack(manga.id).copy(remoteId = 303))
            destination.database.excluded_scanlatorsQueries.insert(manga.id, "Sentinel excluded")
            destination.database.extension_storeQueries.upsert(
                indexUrl = "https://destination-fixture.invalid/index.json",
                name = "Destination sentinel",
                badgeLabel = "Sentinel",
                signingKey = "fixture-destination-signing-marker",
                contactWebsite = "https://destination-fixture.invalid",
                contactDiscord = null,
                isLegacy = true,
                extensionListUrl = null,
            )
        }

        suspend fun createBackup(): File {
            val file = File(root, "actual-roundtrip.tachibk").apply { check(createNewFile()) }
            val creator = BackupCreator(
                context = source.context,
                isAutoBackup = false,
                parser = parser,
                getFavorites = GetFavorites(source.mangaRepository),
                backupPreferences = BackupPreferences(source.store),
                mangaRepository = source.mangaRepository,
                categoriesBackupCreator = CategoriesBackupCreator(source.getCategories),
                mangaBackupCreator = MangaBackupCreator(
                    source.database,
                    source.getCategories,
                    GetHistory(source.historyRepository),
                ),
                preferenceBackupCreator = PreferenceBackupCreator(registry, source.store) { key ->
                    check(key == SOURCE_KEY)
                    source.sourcePreferences
                },
                extensionStoresBackupCreator = ExtensionStoresBackupCreator(
                    GetExtensionStores(source.extensionRepository),
                ),
                sourcesBackupCreator = SourcesBackupCreator(registry),
                backupFileValidator = validator,
            )
            assertEquals(
                Uri.fromFile(file).toString(),
                creator.backup(Uri.fromFile(file), BackupOptions(privateSettings = true)),
            )
            assertEquals(listOf(TRACKER_ID), trackerLookups.toList())
            return file
        }

        fun verifyFile(file: File): Backup {
            val raw = file.readBytes()
            assertTrue(raw.size > 20)
            assertEquals(0x1f, raw[0].toInt() and 0xff)
            assertEquals(0x8b, raw[1].toInt() and 0xff)
            val protobuf = GZIPInputStream(file.inputStream()).use { it.readBytes() }
            val decoded = parser.decodeFromByteArray(Backup.serializer(), protobuf)
            assertArrayEquals(
                parser.encodeToByteArray(Backup.serializer(), decoded),
                parser.encodeToByteArray(Backup.serializer(), decoder.decode(Uri.fromFile(file))),
            )
            val result = validator.validate(Uri.fromFile(file))
            assertTrue(result.missingSources.isEmpty())
            assertTrue(result.missingTrackers.isEmpty())
            assertEquals(listOf(TRACKER_ID, TRACKER_ID), trackerLookups.toList())
            assertEquals(2, registry.validationLookups.get())
            val text = protobuf.toString(Charsets.ISO_8859_1)
            listOf(
                "fixture-only-legacy-credential",
                "fixture-only-encrypted-credential",
                "fixture-only-custom-credential",
                "fixture-only-sakura-credential",
            )
                .forEach { assertFalse("dummy credential must not be serialized", text.contains(it)) }
            return decoded
        }

        fun assertDecoded(backup: Backup) {
            assertEquals(setOf(FAVORITE, READ_OUTSIDE), backup.backupManga.map { it.url }.toSet())
            assertEquals(2, backup.backupManga.size)
            assertEquals(setOf(ALPHA, BETA), backup.backupCategories.map { it.name }.toSet())
            assertEquals(listOf(SOURCE_ID), backup.backupSources.map { it.sourceId })
            assertEquals(1, backup.backupExtensionStores.size)
            val favorite = backup.backupManga.single { it.url == FAVORITE }
            assertEquals(listOf(10L), favorite.categories)
            assertEquals(listOf(20L), backup.backupManga.single { it.url == READ_OUTSIDE }.categories)
            assertEquals(2, favorite.chapters.size)
            assertEquals(1, favorite.history.size)
            assertEquals(HISTORY_DURATION, favorite.history.single().readDuration)
            assertEquals(1, favorite.tracking.size)
            assertEquals(listOf(EXCLUDED), favorite.excludedScanlators)
            val app = backup.backupPreferences.associate { it.key to it.value }
            assertEquals(IntPreferenceValue(42), app["app_int"])
            assertEquals(LongPreferenceValue(4_200_000_000L), app["app_long"])
            assertEquals(FloatPreferenceValue(1.25f), app["app_float"])
            assertEquals(BooleanPreferenceValue(true), app["app_boolean"])
            assertEquals(StringPreferenceValue("fixture-app"), app["app_string"])
            assertEquals(StringSetPreferenceValue(setOf("alpha", "beta")), app["app_set"])
            assertEquals(StringPreferenceValue(ENDPOINT), app["translation_api_base"])
            assertEquals(StringPreferenceValue("fixture-model"), app["translation_model"])
            assertEquals(
                StringPreferenceValue("fixture-private-preference"),
                app[Preference.privateKey("fixture_private")],
            )
            assertFalse(app.keys.any { it.startsWith("translation_api_key") })
            assertFalse(app.containsKey(Preference.appStateKey("fixture_state")))
            val prefs = backup.backupSourcePreferences.single()
            assertEquals(SOURCE_KEY, prefs.sourceKey)
            val source = prefs.prefs.associate { it.key to it.value }
            assertEquals(IntPreferenceValue(42), source["source_int"])
            assertEquals(LongPreferenceValue(4_200_000_000L), source["source_long"])
            assertEquals(FloatPreferenceValue(1.25f), source["source_float"])
            assertEquals(BooleanPreferenceValue(true), source["source_boolean"])
            assertEquals(StringPreferenceValue("fixture-source"), source["source_string"])
            assertEquals(StringSetPreferenceValue(setOf("alpha", "beta")), source["source_set"])
        }

        suspend fun assertDestination() {
            val favorite = destination.mangaRepository.getMangaByUrlAndSourceId(FAVORITE, SOURCE_ID)
            assertNotNull(favorite)
            favorite!!
            assertTrue(favorite.favorite)
            assertEquals("Fixture favorite", favorite.title)
            assertEquals("Fixture author", favorite.author)
            assertEquals("Fixture artist", favorite.artist)
            assertEquals("Fixture description", favorite.description)
            assertEquals(listOf("Fixture genre"), favorite.genre)
            assertEquals("Fixture notes", favorite.notes)
            assertEquals(MANGA_MEMO, favorite.memo)
            assertEquals(3L, favorite.viewerFlags)
            assertEquals(257L, favorite.chapterFlags)
            assertEquals(UpdateStrategy.ALWAYS_UPDATE, favorite.updateStrategy)
            assertEquals(2, destination.database.mangasQueries.getAllManga().awaitAsList().size)
            assertEquals(1, destination.mangaRepository.getFavorites().size)
            assertEquals(1, destination.mangaRepository.getReadMangaNotInLibrary().size)
            assertFalse(destination.mangaRepository.getMangaByUrlAndSourceId(READ_OUTSIDE, SOURCE_ID)!!.favorite)
            assertNull(destination.mangaRepository.getMangaByUrlAndSourceId(UNREAD_OUTSIDE, SOURCE_ID))
            val categories = destination.categoryRepository.getAll().filterNot { it.isSystemCategory }
            assertEquals(2, categories.size)
            val alpha = categories.single { it.name == ALPHA }
            val beta = categories.single { it.name == BETA }
            assertEquals(2L, alpha.id)
            assertEquals(1L, beta.id)
            assertEquals(11L, alpha.flags)
            assertEquals(22L, beta.flags)
            val links = destination.categoryRepository.getCategoriesByMangaId(favorite.id)
            assertEquals(listOf(alpha.id), links.map { it.id })
            val readOutside = destination.mangaRepository.getMangaByUrlAndSourceId(READ_OUTSIDE, SOURCE_ID)!!
            assertEquals(
                listOf(beta.id),
                destination.categoryRepository.getCategoriesByMangaId(readOutside.id).map { it.id },
            )
            val chapters = destination.chapterRepository.getChapterByMangaId(favorite.id, false)
            assertEquals(2, chapters.size)
            val one = chapters.single { it.url == CHAPTER_ONE }
            val two = chapters.single { it.url == CHAPTER_TWO }
            assertTrue(one.read)
            assertTrue(one.bookmark)
            assertEquals(7L, one.lastPageRead)
            assertEquals(CHAPTER_MEMO, one.memo)
            assertFalse(two.read)
            assertEquals(3L, two.lastPageRead)
            val history = destination.historyRepository.getHistoryByMangaId(favorite.id).single()
            assertEquals(one.id, history.chapterId)
            assertEquals(HISTORY_AT, history.readAt?.time)
            assertEquals(HISTORY_DURATION, history.readDuration)
            assertEquals(HISTORY_DURATION, destination.historyRepository.getTotalReadDuration())
            val track = destination.trackRepository.getTracksByMangaId(favorite.id).single()
            assertEquals(expectedTrack(favorite.id).copy(id = track.id), track)
            assertEquals(
                listOf(EXCLUDED),
                destination.database.excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(
                    favorite.id,
                ).awaitAsList(),
            )
            val extension = destination.extensionRepository.getAll().single()
            assertEquals(EXTENSION_INDEX, extension.indexUrl)
            assertEquals("Backup fixture store", extension.name)
            assertEquals("Fixture", extension.badgeLabel)
            assertEquals("fixture-not-a-real-signing-key", extension.signingKey)
            assertEquals("https://backup-fixture.invalid", extension.contact.website)
            assertEquals("https://backup-fixture.invalid/community", extension.contact.discord)
            assertFalse(extension.isLegacy)
            assertEquals("https://backup-fixture.invalid/extensions.json", extension.extensionListUrl)
            assertSixTypes(destination.appPreferences, "app")
            assertSixTypes(destination.sourcePreferences, "source")
            assertEquals(ENDPOINT, destination.appPreferences.getString("translation_api_base", null))
            assertEquals("fixture-model", destination.appPreferences.getString("translation_model", null))
            assertEquals(
                alpha.id.toInt(),
                destination.appPreferences.getInt(LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY, -1),
            )
            assertEquals(
                setOf(alpha.id.toString()),
                destination.appPreferences.getStringSet("library_update_categories", null),
            )
            assertEquals(
                setOf(beta.id.toString()),
                destination.appPreferences.getStringSet("library_update_categories_exclude", null),
            )
            assertFalse(destination.appPreferences.contains("translation_api_key_enc__custom"))
            assertFalse(destination.appPreferences.contains("translation_api_key_enc__sakura"))
            assertFalse(destination.appPreferences.contains("translation_api_key"))
            assertFalse(destination.appPreferences.contains("translation_api_key_enc"))
            assertEquals("unchanged", destination.appPreferences.getString("destination_sentinel", null))
            assertEquals("unchanged", destination.sourcePreferences.getString("destination_source_sentinel", null))
            destination.assertPreferencesOnDisk()
        }

        override fun close() {
            cleanupAll(
                { destination.close() },
                { source.close() },
                { check(!root.exists() || root.deleteRecursively()) },
            )
        }
    }

    private class IsolatedState(base: Context, root: File, private val namespace: String) : AutoCloseable {
        private val cache = File(root, namespace).apply { check(mkdirs()) }
        val context: Context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = cache
            override fun getExternalCacheDir(): File = cache
        }
        private val appName = "$namespace-app"
        private val sourceName = "$namespace-source"
        private val databaseName = "$namespace.db"
        val appPreferences = base.getSharedPreferences(appName, Context.MODE_PRIVATE)
        val sourcePreferences = base.getSharedPreferences(sourceName, Context.MODE_PRIVATE)
        val store = AndroidPreferenceStore(context, appPreferences)
        val invalidations = AtomicInteger()
        val reschedules = AtomicInteger()
        private var driver = openDriver()
        var database = openDatabase(driver)
            private set
        val categoryRepository get() = CategoryRepositoryImpl(database)
        val mangaRepository get() = MangaRepositoryImpl(database)
        val chapterRepository get() = ChapterRepositoryImpl(database)
        val historyRepository get() = HistoryRepositoryImpl(database)
        val trackRepository get() = TrackRepositoryImpl(database)
        val getCategories get() = GetCategories(categoryRepository)
        private val network = NetworkHelper(context, NetworkPreferences(store))
        val extensionRepository get() = ExtensionStoreRepositoryImpl(
            ExtensionStoreService(network, Json { ignoreUnknownKeys = true }, ProtoBuf),
            database,
        )

        private fun openDriver(): AndroidxSqliteDriver {
            context.getDatabasePath(databaseName).parentFile!!.mkdirs()
            return AndroidxSqliteDriver(
                driver = BundledSQLiteDriver(),
                databaseType = AndroidxSqliteDatabaseType.FileProvider(context, databaseName),
                schema = Database.Schema,
                configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
            )
        }

        fun reopenDatabase() {
            driver.close()
            driver = openDriver()
            database = openDatabase(driver)
        }

        private fun openDatabase(driver: AndroidxSqliteDriver) = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )

        fun restorer(
            callbacks: RecordingCallbacks,
            categoryCreation: GetCategories = getCategories,
            dependentCategories: GetCategories = getCategories,
        ): BackupRestorer {
            val chapters = GetChaptersByMangaId(chapterRepository)
            val fetch = FetchInterval(chapters)
            return BackupRestorer(
                context = context,
                callbacks = callbacks,
                isSync = false,
                database = database,
                categoriesRestorer = CategoriesRestorer(database, categoryCreation, LibraryPreferences(store)),
                preferenceRestorer = PreferenceRestorer(
                    context = context,
                    getCategories = dependentCategories,
                    preferenceStore = store,
                    sourcePreferenceStoreResolver = { key ->
                        check(key == SOURCE_KEY)
                        AndroidPreferenceStore(context, sourcePreferences)
                    },
                    onAppPreferencesRestored = { reschedules.incrementAndGet() },
                ),
                extensionStoreRestorer = ExtensionStoreRestorer(database),
                mangaRestorer = MangaRestorer(
                    database,
                    dependentCategories,
                    GetMangaByUrlAndSourceId(mangaRepository),
                    chapters,
                    UpdateManga(mangaRepository, fetch),
                    GetTracks(trackRepository),
                    InsertTrack(trackRepository),
                    fetch,
                ),
                invalidateDownloadCache = { invalidations.incrementAndGet() },
                decoder = BackupDecoder(context, ProtoBuf),
            )
        }

        suspend fun restore(file: File): RecordingCallbacks = RecordingCallbacks().also {
            withContext(Dispatchers.IO) { restorer(it).restore(Uri.fromFile(file), RestoreOptions()) }
        }

        suspend fun snapshot(): List<Any> {
            val mangas = database.mangasQueries.getAllManga().awaitAsList()
            return listOf(
                mangas,
                mangas.map { manga ->
                    listOf(
                        chapterRepository.getChapterByMangaId(manga._id, false),
                        categoryRepository.getCategoriesByMangaId(manga._id),
                        historyRepository.getHistoryByMangaId(manga._id),
                        trackRepository.getTracksByMangaId(manga._id),
                        database.excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(manga._id).awaitAsList(),
                    )
                },
                categoryRepository.getAll(),
                database.extension_storeQueries.getAll().awaitAsList(),
                appPreferences.all.toMap(),
                sourcePreferences.all.toMap(),
            )
        }

        fun assertPreferencesOnDisk() {
            for ((name, preferences) in listOf(appName to appPreferences, sourceName to sourcePreferences)) {
                // A real changed commit flushes previous apply writes. Reading the dedicated XML
                // avoids claiming persistence from Android's cached SharedPreferences instance.
                assertTrue(preferences.edit().putLong(DISK_BARRIER, System.nanoTime()).commit())
                val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
                assertTrue(file.isFile)
                assertEquals(
                    preferences.all.filterKeys { it != DISK_BARRIER },
                    readPreferenceXml(file).filterKeys {
                        it !=
                            DISK_BARRIER
                    },
                )
            }
        }

        override fun close() {
            cleanupAll(
                { network.client.dispatcher.cancelAll() },
                { network.client.connectionPool.evictAll() },
                { network.client.cache?.close() },
                { driver.close() },
                { check(context.deleteDatabase(databaseName)) },
                { check(context.deleteSharedPreferences(appName)) },
                { check(context.deleteSharedPreferences(sourceName)) },
                { check(!cache.exists() || cache.deleteRecursively()) },
            )
        }
    }

    private class RecordingCallbacks : BackupRestoreCallbacks {
        val progress = CopyOnWriteArrayList<Int>()
        val completions = CopyOnWriteArrayList<Int>()
        override fun showRestoreProgress(content: String, progress: Int, maxAmount: Int, sync: Boolean) {
            check(!sync)
            check(progress in 1..maxAmount)
            this.progress += progress
        }

        override fun showRestoreComplete(time: Long, errorCount: Int, path: String?, file: String?, sync: Boolean) {
            check(!sync)
            check(time >= 0)
            completions += errorCount
        }
    }

    private class FixtureSourceManager(preferences: SharedPreferences) : SourceManager {
        private val source = object : ConfigurableSource {
            override val id = SOURCE_ID
            override val name = "Backup fixture source"
            override val lang = "en"
            override val supportsLatest = false
            override fun getSourcePreferences() = preferences
            override fun setupPreferenceScreen(screen: PreferenceScreen) = error("backup must not open source settings")
            override suspend fun getPopularManga(page: Int): MangasPage = error("backup must not fetch remote data")
            override suspend fun getLatestUpdates(page: Int): MangasPage = error("backup must not fetch remote data")
            override suspend fun getSearchManga(
                page: Int,
                query: String,
                filters: FilterList,
            ): MangasPage = error("backup must not fetch remote data")
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ): SMangaUpdate =
                error("backup must not fetch remote data")
            override suspend fun getPageList(chapter: SChapter): List<Page> = error("backup must not fetch remote data")
        }
        val validationLookups = AtomicInteger()
        override val isInitialized = MutableStateFlow(true)
        override val sources = MutableStateFlow<List<Source>>(listOf(source))
        override fun get(sourceKey: Long): Source? {
            validationLookups.incrementAndGet()
            check(sourceKey == SOURCE_ID)
            return source
        }

        override fun getOrStub(sourceKey: Long): Source {
            check(sourceKey == SOURCE_ID)
            return source
        }

        override fun getAll(): List<Source> = listOf(source)
        override fun getOnlineSources(): List<HttpSource> = emptyList()
        override fun getStubSources(): List<StubSource> = emptyList()
    }

    companion object {
        private const val SOURCE_ID = 991_023L
        private const val SOURCE_KEY = "source_991023"
        private const val TRACKER_ID = 7L
        private const val ALPHA = "Fixture Alpha"
        private const val BETA = "Fixture Beta"
        private const val FAVORITE = "/backup-fixture/favorite"
        private const val READ_OUTSIDE = "/backup-fixture/read"
        private const val UNREAD_OUTSIDE = "/backup-fixture/unread"
        private const val CHAPTER_ONE = "$FAVORITE/chapter-1"
        private const val CHAPTER_TWO = "$FAVORITE/chapter-2"
        private const val EXCLUDED = "Fixture excluded"
        private const val HISTORY_AT = 1_700_000_123_456L
        private const val HISTORY_DURATION = 2_700L
        private const val ENDPOINT = "https://backup-fixture.invalid/v1/"
        private const val EXTENSION_INDEX = "https://backup-fixture.invalid/index.json"
        private const val DISK_BARRIER = "__APP_STATE_fixture_disk_barrier"
        private val MANGA_MEMO = buildJsonObject { put("fixture", "manga-memo") }
        private val CHAPTER_MEMO = buildJsonObject { put("fixture", "chapter-memo") }

        private fun cleanupAll(vararg actions: () -> Unit) {
            var failure: Throwable? = null
            actions.forEach { action ->
                try {
                    action()
                } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else first.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }

        private fun putSixTypes(preferences: SharedPreferences, prefix: String) {
            assertTrue(
                preferences.edit()
                    .putInt("${prefix}_int", 42)
                    .putLong("${prefix}_long", 4_200_000_000L)
                    .putFloat("${prefix}_float", 1.25f)
                    .putBoolean("${prefix}_boolean", true)
                    .putString("${prefix}_string", "fixture-$prefix")
                    .putStringSet("${prefix}_set", setOf("alpha", "beta"))
                    .commit(),
            )
        }

        private fun assertSixTypes(preferences: SharedPreferences, prefix: String) {
            assertEquals(42, preferences.getInt("${prefix}_int", -1))
            assertEquals(4_200_000_000L, preferences.getLong("${prefix}_long", -1))
            assertEquals(1.25f, preferences.getFloat("${prefix}_float", -1f), 0f)
            assertTrue(preferences.getBoolean("${prefix}_boolean", false))
            assertEquals("fixture-$prefix", preferences.getString("${prefix}_string", null))
            assertEquals(setOf("alpha", "beta"), preferences.getStringSet("${prefix}_set", null))
        }

        private fun expectedTrack(mangaId: Long) = Track(
            id = 0,
            mangaId = mangaId,
            trackerId = TRACKER_ID,
            remoteId = 101,
            libraryId = 202,
            title = "Fixture track",
            lastChapterRead = 1.5,
            totalChapters = 20,
            status = 2,
            score = 8.5,
            remoteUrl = "https://backup-fixture.invalid/track/101",
            startDate = 1_690_000_000_000,
            finishDate = 1_700_000_000_000,
            private = true,
        )

        private fun readPreferenceXml(file: File): Map<String, Any> = file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            val result = linkedMapOf<String, Any>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name == "map") continue
                val name = checkNotNull(parser.getAttributeValue(null, "name"))
                result[name] = when (parser.name) {
                    "string" -> parser.nextText()
                    "int" -> parser.getAttributeValue(null, "value").toInt()
                    "long" -> parser.getAttributeValue(null, "value").toLong()
                    "float" -> parser.getAttributeValue(null, "value").toFloat()
                    "boolean" -> parser.getAttributeValue(null, "value").toBooleanStrict()
                    "set" -> buildSet {
                        val depth = parser.depth
                        while (parser.next() != XmlPullParser.END_TAG || parser.depth != depth) {
                            if (parser.eventType == XmlPullParser.START_TAG) {
                                check(parser.name == "string")
                                add(parser.nextText())
                            }
                        }
                    }
                    else -> error("unexpected own preference type ${parser.name}")
                }
            }
            result
        }
    }
}
