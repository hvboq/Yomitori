package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.Clock

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,

    private val parser: ProtoBuf = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),

    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator = ExtensionStoresBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
    private val backupFileValidator: BackupFileValidator = BackupFileValidator(context),
    private val autoBackupFilename: () -> String = ::getFilename,
) {

    suspend fun backup(uri: Uri, options: BackupOptions): String = if (isAutoBackup) {
        automaticBackupMutex.withLock { createBackup(uri, options) }
    } else {
        createBackup(uri, options)
    }

    private suspend fun createBackup(uri: Uri, options: BackupOptions): String {
        var file: UniFile? = null
        var automaticDirectory: UniFile? = null
        var committed = false
        try {
            file = if (isAutoBackup) {
                automaticDirectory = UniFile.fromUri(context, uri)?.takeIf { it.isDirectory }
                automaticDirectory?.let(::createAutomaticFile)
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.isFile) {
                throw IllegalStateException(context.stringResource(MR.strings.create_backup_file_error))
            }

            val nonFavoriteManga = if (options.readEntries) mangaRepository.getReadMangaNotInLibrary() else emptyList()
            val backupManga = backupMangas(getFavorites.await() + nonFavoriteManga, options)

            val backup = Backup(
                backupManga = backupManga,
                backupCategories = backupCategories(options),
                backupSources = backupSources(backupManga),
                backupPreferences = backupAppPreferences(options),
                backupExtensionStores = backupExtensionStores(options),
                backupSourcePreferences = backupSourcePreferences(options),
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            file.openOutputStream()
                .also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }
                .sink().gzip().buffer().use {
                    it.write(byteArray)
                }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            backupFileValidator.validate(fileUri)

            if (isAutoBackup) {
                currentCoroutineContext().ensureActive()
                backupPreferences.lastAutoBackupTimestamp.set(Clock.System.now().toEpochMilliseconds())
                committed = true
                pruneAutomaticBackups(checkNotNull(automaticDirectory), fileUri)
            }

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            if (!isAutoBackup || !committed) file?.delete()
            throw e
        }
    }

    private fun createAutomaticFile(directory: UniFile): UniFile? {
        val previousFiles = checkNotNull(directory.listFiles()) { "Could not enumerate automatic backup directory" }
        val previousUris = previousFiles.map { it.uri }.toSet()
        val basename = autoBackupFilename().removeSuffix(".tachibk")
        repeat(MAX_ALLOCATION_ATTEMPTS) {
            val filename = "${basename}_${UUID.randomUUID()}.tachibk"
            if (directory.findFile(filename) != null) return@repeat
            val created = directory.createFile(filename) ?: return null
            // Do not take ownership if a provider returns an already existing document.
            check(created.uri !in previousUris) { "Automatic backup allocation reused an existing document" }
            return created
        }
        return null
    }

    private suspend fun pruneAutomaticBackups(directory: UniFile, committedUri: Uri) {
        try {
            val previousFiles = checkNotNull(directory.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }) {
                "Could not enumerate older automatic backups"
            }
            previousFiles
                .filter { it.isFile && it.uri != committedUri }
                .sortedWith(compareByDescending<UniFile> { it.lastModified() }.thenByDescending { it.name })
                .drop(MAX_AUTO_BACKUPS - 1)
                .forEach {
                    currentCoroutineContext().ensureActive()
                    it.delete()
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Retention is best effort after a valid backup commits; preserve that backup.
            logcat(LogPriority.ERROR, error) { "Failed to prune older automatic backups" }
        }
    }

    private suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    private suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    private fun backupSources(mangas: List<BackupManga>): List<BackupSource> {
        return sourcesBackupCreator(mangas)
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    private suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    private fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private const val MAX_ALLOCATION_ATTEMPTS: Int = 3
        private val automaticBackupMutex = Mutex()
        private val FILENAME_REGEX = (
            """${Regex.escape(BuildConfig.APPLICATION_ID)}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}""" +
                """(?:_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?\.tachibk"""
            ).toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}
