// This test-only package permits delegation through UniFile's package-private constructor.
package com.hippo.unifile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.DownloadedChapterContent
import eu.kanade.tachiyomi.data.download.DownloadedChapterPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Fixed-only publication safety: actual private RawFile operations and controlled rename refusal.
 * Presence probes are not full image decoding. No Activity, OCR, server, user settings, or DB writes.
 */
@RunWith(AndroidJUnit4::class)
class DownloadPublishSafetyTest {
    @Test(timeout = 30_000)
    fun unoccupiedLooseDestinationPublishesTheActualCandidateWithCorrectParent(): Unit = runBlocking {
        withFixture { f ->
            val manga = f.caseDirectory("loose")
            f.metadata(File(manga, "OtherChapter"))
            val candidate = f.imageDirectory(File(manga, "Chapter_tmp"))
            val before = f.snapshot()
            val expected = before.renamed(f.relative(candidate), "loose/Chapter")
            val rawManga = uni(manga)
            val rawCandidate = requireNotNull(rawManga.findFile(candidate.name))
            assertEquals("DL_PUBLISH_ACTUAL_PARENT", rawManga.uri, rawCandidate.parentFile?.uri)
            val faults = RenameFaults()
            val wrappedManga = GuardedUniFile(rawManga, faults)
            val wrappedCandidate = requireNotNull(wrappedManga.findFile(candidate.name))

            val published = f.publisher.publish(wrappedManga, wrappedCandidate, "Chapter")

            assertEquals("DL_PUBLISH_LOOSE_NAME", "Chapter", published.name)
            assertEquals("DL_PUBLISH_LOOSE_URI", Uri.fromFile(File(manga, "Chapter")), published.uri)
            assertEquals("DL_PUBLISH_LOOSE_PARENT", rawManga.uri, published.parentFile?.uri)
            assertTrue("DL_PUBLISH_LOOSE_DIRECTORY", published.isDirectory)
            assertTrue("DL_PUBLISH_LOOSE_BODY", f.content.hasReadablePage(published))
            assertEquals("DL_PUBLISH_LOOSE_BYTES", sha(f.png), sha(File(manga, "Chapter/001.png").readBytes()))
            assertEquals("DL_PUBLISH_LOOSE_TREE", expected, f.snapshot())
            assertEquals("DL_PUBLISH_LOOSE_ONE_RENAME", listOf("Chapter_tmp" to "Chapter"), faults.renames)
            assertEquals("DL_PUBLISH_LOOSE_DELETE_ZERO", 0, faults.deleteCalls)
        }
    }

    @Test(timeout = 30_000)
    fun failedCandidateRenamePropagatesAndPreservesCandidateAndUnrelatedMetadata(): Unit = runBlocking {
        withFixture { f ->
            val manga = f.caseDirectory("rename-failure")
            f.metadata(File(manga, "OtherChapter"))
            val candidate = f.imageDirectory(File(manga, "Chapter_tmp"))
            val before = f.snapshot()
            val rawManga = uni(manga)
            assertEquals(
                "DL_PUBLISH_RENAME_SETUP_PARENT",
                rawManga.uri,
                requireNotNull(rawManga.findFile(candidate.name)).parentFile?.uri,
            )
            val faults = RenameFaults(failFrom = candidate.name, failTo = "Chapter", omitParent = true)
            val wrappedManga = GuardedUniFile(rawManga, faults)
            val wrappedCandidate = requireNotNull(wrappedManga.findFile(candidate.name))
            assertEquals("The wrapper also exercises a provider without parentFile", null, wrappedCandidate.parentFile)

            val error = failure { f.publisher.publish(wrappedManga, wrappedCandidate, "Chapter") }

            assertTrue("DL_PUBLISH_RENAME_FAILURE_PROPAGATES", error is IOException)
            assertTrue("DL_PUBLISH_RENAME_FALSE_REACHED", faults.injected)
            assertEquals("DL_PUBLISH_RENAME_ATTEMPT", listOf(candidate.name to "Chapter"), faults.renames)
            assertEquals("DL_PUBLISH_RENAME_FALSE_DELETE_ZERO", 0, faults.deleteCalls)
            assertEquals("DL_PUBLISH_RENAME_FALSE_TREE", before, f.snapshot())
            assertFalse("DL_PUBLISH_RENAME_FALSE_NO_DESTINATION", File(manga, "Chapter").exists())
            assertTrue("DL_PUBLISH_RENAME_FALSE_CANDIDATE_EXISTS", candidate.isDirectory)
        }
    }

    @Test(timeout = 30_000)
    fun existingMetadataOriginalsUnknownDirectoriesAndCorruptArchivesAreNeverMoved(): Unit = runBlocking {
        withFixture { f ->
            for (kind in listOf("metadata", "originals", "unknown-directory", "corrupt-archive")) {
                val manga = f.caseDirectory(kind)
                val archive = kind == "corrupt-archive"
                val finalName = if (archive) "Chapter.cbz" else "Chapter"
                val target = File(manga, finalName)
                if (archive) {
                    target.writeBytes("neutral malformed archive".toByteArray())
                } else {
                    f.metadata(target)
                    when (kind) {
                        "originals" -> {
                            val originals = File(target, ".yakuyomi").apply { check(mkdir()) }
                            File(originals, "001.orig.png").writeBytes(f.png)
                        }
                        "unknown-directory" -> {
                            val nested = File(target, "unknown").apply { check(mkdir()) }
                            File(nested, "note.txt").writeText("neutral preserved material")
                        }
                    }
                }
                val candidate = if (archive) {
                    File(manga, "${finalName}_tmp").also { f.archive(it, f.png) }
                } else {
                    f.imageDirectory(File(manga, "${finalName}_tmp"))
                }
                val before = f.snapshot()
                val faults = RenameFaults()
                val wrappedManga = GuardedUniFile(uni(manga), faults)
                val wrappedCandidate = requireNotNull(wrappedManga.findFile(candidate.name))

                val error = failure { f.publisher.publish(wrappedManga, wrappedCandidate, finalName) }

                assertTrue("DL_PUBLISH_EXISTING_REFUSED_$kind", error is IOException)
                assertEquals(
                    "DL_PUBLISH_EXISTING_RENAME_ZERO_$kind",
                    emptyList<Pair<String?, String>>(),
                    faults.renames,
                )
                assertEquals("DL_PUBLISH_EXISTING_DELETE_ZERO_$kind", 0, faults.deleteCalls)
                assertEquals("DL_PUBLISH_EXISTING_TREE_$kind", before, f.snapshot())
            }

            // A same-named staging folder outside the supplied manga directory has no authority there.
            val manga = f.caseDirectory("foreign-parent")
            f.metadata(File(manga, "OtherChapter"))
            val foreign = f.imageDirectory(File(f.caseDirectory("foreign-candidate"), "Chapter_tmp"))
            val before = f.snapshot()
            val faults = RenameFaults()
            val error = failure {
                f.publisher.publish(GuardedUniFile(uni(manga), faults), GuardedUniFile(uni(foreign), faults), "Chapter")
            }
            assertTrue("DL_PUBLISH_FOREIGN_PARENT_REFUSED", error is IOException)
            assertEquals("DL_PUBLISH_FOREIGN_PARENT_RENAME_ZERO", emptyList<Pair<String?, String>>(), faults.renames)
            assertEquals("DL_PUBLISH_FOREIGN_PARENT_DELETE_ZERO", 0, faults.deleteCalls)
            assertEquals("DL_PUBLISH_FOREIGN_PARENT_TREE", before, f.snapshot())
        }
    }

    @Test(timeout = 30_000)
    fun newCbzCanBePublishedBesideAnUnchangedCanonicalMetadataDirectory(): Unit = runBlocking {
        withFixture { f ->
            val manga = f.caseDirectory("archive")
            f.metadata(File(manga, "Chapter"))
            val candidate = File(manga, "Chapter.cbz_tmp").also { f.archive(it, f.png) }
            val before = f.snapshot()
            val expected = before.renamed(f.relative(candidate), "archive/Chapter.cbz")
            val faults = RenameFaults()
            val wrappedManga = GuardedUniFile(uni(manga), faults)

            val published = f.publisher.publish(
                wrappedManga,
                requireNotNull(wrappedManga.findFile(candidate.name)),
                "Chapter.cbz",
            )

            assertTrue("DL_PUBLISH_ARCHIVE_FILE", published.isFile)
            assertEquals("DL_PUBLISH_ARCHIVE_NAME", "Chapter.cbz", published.name)
            assertEquals("DL_PUBLISH_ARCHIVE_URI", Uri.fromFile(File(manga, "Chapter.cbz")), published.uri)
            assertTrue("DL_PUBLISH_ARCHIVE_BODY", f.content.hasReadablePage(published))
            ZipFile(File(manga, "Chapter.cbz")).use { zip ->
                val body = zip.getInputStream(requireNotNull(zip.getEntry("001.png"))).use { it.readBytes() }
                assertEquals("DL_PUBLISH_ARCHIVE_PAGE_BYTES", sha(f.png), sha(body))
            }
            assertEquals("DL_PUBLISH_ARCHIVE_EXACT_TREE", expected, f.snapshot())
            assertEquals("DL_PUBLISH_ARCHIVE_ONE_RENAME", listOf("Chapter.cbz_tmp" to "Chapter.cbz"), faults.renames)
            assertEquals("DL_PUBLISH_ARCHIVE_DELETE_ZERO", 0, faults.deleteCalls)
        }
    }

    @Test(timeout = 30_000)
    fun zeroByteImageFilesAndArchiveEntriesNeverPublishOrMoveExistingMetadata(): Unit = runBlocking {
        withFixture { f ->
            for (archive in listOf(false, true)) {
                val kind = if (archive) "zero-archive" else "zero-loose"
                val manga = f.caseDirectory(kind)
                f.metadata(File(manga, "OtherChapter"))
                val finalName = if (archive) "Chapter.cbz" else "Chapter"
                val candidate = if (archive) {
                    File(manga, "${finalName}_tmp").also { f.archive(it, byteArrayOf()) }
                } else {
                    File(manga, "${finalName}_tmp").apply {
                        check(mkdir())
                        File(this, "001.png").writeBytes(byteArrayOf())
                    }
                }
                val readerProbe = if (archive) {
                    // Public reader-presence classification uses the final archive extension.
                    File(manga, "empty-probe.cbz").apply { writeBytes(candidate.readBytes()) }.also { probe ->
                        ZipFile(probe).use { zip ->
                            assertEquals("DL_PUBLISH_ZERO_ENTRY_SETUP", 0L, zip.getEntry("001.png").size)
                        }
                    }
                } else {
                    candidate
                }
                assertFalse("DL_PUBLISH_ZERO_BODY_$kind", f.content.hasReadablePage(uni(readerProbe)))
                val before = f.snapshot()
                val faults = RenameFaults()
                val wrappedManga = GuardedUniFile(uni(manga), faults)

                val error = failure {
                    f.publisher.publish(wrappedManga, requireNotNull(wrappedManga.findFile(candidate.name)), finalName)
                }

                assertTrue("DL_PUBLISH_ZERO_REFUSED_$kind", error is IOException)
                assertEquals("DL_PUBLISH_ZERO_RENAME_ZERO_$kind", emptyList<Pair<String?, String>>(), faults.renames)
                assertEquals("DL_PUBLISH_ZERO_DELETE_ZERO_$kind", 0, faults.deleteCalls)
                assertEquals("DL_PUBLISH_ZERO_EXACT_TREE_$kind", before, f.snapshot())
            }
        }
    }

    @Test(timeout = 30_000)
    fun providerFindsPublishedLooseAliasAfterAnUnchangedCanonicalMetadataDirectory(): Unit = runBlocking {
        withFixture { f ->
            val storageRoot = f.caseDirectory("alias")
            val folder = object : FolderProvider {
                override fun directory(): File = storageRoot
                override fun path(): String = Uri.fromFile(storageRoot).toString()
            }
            val storage = StorageManager(f.context, StoragePreferences(folder, InMemoryPreferenceStore()))
            f.ownScope(storage)
            val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
            libraryPreferences.disallowNonAsciiFilenames.set(false)
            val provider = DownloadProvider(f.context, storage, libraryPreferences)
            val source = StubSource(9_000_002L, "all", "Neutral publication fixture")
            val mangaTitle = "Neutral publication fixture"
            val chapterName = "Chapter 一"
            val chapterUrl = "/neutral/chapter/1"
            val canonicalName = provider.getChapterDirName(chapterName, null, chapterUrl)
            val manga = File(
                storageRoot,
                "downloads/${provider.getSourceDirName(source)}/${provider.getMangaDirName(mangaTitle)}",
            ).apply { check(mkdirs()) }
            val canonical = File(manga, canonicalName)
            f.metadata(canonical)
            val candidate = f.imageDirectory(File(manga, "${canonicalName}_tmp"))
            val aliasName = "${canonicalName}_dl"
            val alias = File(manga, aliasName)
            val before = f.snapshot()
            val expected = before.renamed(f.relative(candidate), f.relative(alias))
            val faults = RenameFaults()
            val wrappedManga = GuardedUniFile(uni(manga), faults)

            val published = f.publisher.publish(
                wrappedManga,
                requireNotNull(wrappedManga.findFile(candidate.name)),
                aliasName,
            )
            val found = provider.findReadableDownloadedChapterDir(chapterName, null, chapterUrl, mangaTitle, source)

            assertNotNull("DL_PUBLISH_ALIAS_FOUND", found)
            val resolved = requireNotNull(found)
            assertEquals("DL_PUBLISH_ALIAS_URI", published.uri, resolved.uri)
            assertEquals("DL_PUBLISH_ALIAS_NAME", aliasName, resolved.name)
            assertEquals(
                "DL_PUBLISH_ALIAS_GENERIC_LOOKUP",
                published.uri,
                provider.findChapterDir(chapterName, null, chapterUrl, mangaTitle, source)?.uri,
            )
            assertTrue("DL_PUBLISH_ALIAS_BODY", f.content.hasReadablePage(resolved))
            assertEquals(
                "DL_PUBLISH_ALIAS_BODY_BYTES",
                sha(f.png),
                sha(requireNotNull(resolved.findFile("001.png")).openInputStream().use { it.readBytes() }),
            )

            libraryPreferences.disallowNonAsciiFilenames.set(true)
            assertFalse(
                "DL_PUBLISH_ALIAS_FILENAME_MODE_CHANGED",
                canonicalName == provider.getChapterDirName(chapterName, null, chapterUrl),
            )
            val toggled = provider.findReadableDownloadedChapterDir(chapterName, null, chapterUrl, mangaTitle, source)
            assertNotNull("DL_PUBLISH_ALIAS_FOUND_AFTER_ASCII_TOGGLE", toggled)
            val toggledResult = requireNotNull(toggled)
            assertEquals("DL_PUBLISH_ALIAS_URI_AFTER_ASCII_TOGGLE", published.uri, toggledResult.uri)
            assertEquals(
                "DL_PUBLISH_ALIAS_GENERIC_AFTER_ASCII_TOGGLE",
                published.uri,
                provider.findChapterDir(chapterName, null, chapterUrl, mangaTitle, source)?.uri,
            )
            assertTrue("DL_PUBLISH_ALIAS_BODY_AFTER_ASCII_TOGGLE", f.content.hasReadablePage(toggledResult))
            assertEquals(
                "DL_PUBLISH_ALIAS_BYTES_AFTER_ASCII_TOGGLE",
                sha(f.png),
                sha(requireNotNull(toggledResult.findFile("001.png")).openInputStream().use { it.readBytes() }),
            )
            assertEquals("DL_PUBLISH_ALIAS_EXACT_TREE", expected, f.snapshot())
            assertEquals("DL_PUBLISH_ALIAS_CANONICAL_RETAINED", true, canonical.isDirectory)
            assertEquals("DL_PUBLISH_ALIAS_ONE_RENAME", listOf(candidate.name to aliasName), faults.renames)
            assertEquals("DL_PUBLISH_ALIAS_DELETE_ZERO", 0, faults.deleteCalls)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") {
            "Publication safety tests require the isolated audit installation"
        }
        val fixture = Fixture(context)
        var failure: Throwable? = null
        try {
            block(fixture)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    fixture.close()
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }
    }

    private class Fixture(val context: Context) {
        private val prefix = "download-publish-safety-${UUID.randomUUID()}"
        private val root = File(context.cacheDir, prefix).apply { check(mkdir()) }
        val publisher = DownloadedChapterPublisher(context)
        val content = DownloadedChapterContent(context)
        private val scopeOwners = mutableListOf<Any>()
        val png = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(Color.WHITE)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        fun caseDirectory(name: String): File = File(root, name).apply { check(mkdir()) }

        fun metadata(directory: File) {
            check(directory.mkdir())
            File(directory, ".nomedia").writeBytes(byteArrayOf())
            File(directory, "ComicInfo.xml").writeBytes(METADATA)
            File(directory, "ComicInfo").writeBytes(METADATA)
        }

        fun imageDirectory(directory: File): File = directory.apply {
            check(mkdir())
            File(this, "001.png").writeBytes(png)
        }

        fun archive(file: File, page: ByteArray) {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("001.png"))
                zip.write(page)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                zip.write(METADATA)
                zip.closeEntry()
            }
        }

        fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

        fun snapshot(): Tree = Tree(
            root.walkTopDown().filter { it.isDirectory && it != root }.map(::relative).toSet(),
            root.walkTopDown().filter { it.isFile }.associate { relative(it) to sha(it.readBytes()) },
        )

        fun ownScope(owner: Any) {
            scopeOwners += owner
        }

        suspend fun close() {
            for (owner in scopeOwners.asReversed()) {
                val scope = owner.javaClass.getDeclaredField("scope").apply { isAccessible = true }
                    .get(owner) as CoroutineScope
                withTimeout(10_000) { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
            }
            val owned = root.canonicalFile
            check(owned.parentFile == context.cacheDir.canonicalFile && owned.name == prefix)
            check(owned.name.startsWith("download-publish-safety-"))
            check(owned.deleteRecursively()) { "Cannot remove the owned publication safety fixture" }
            check(!owned.exists())
        }
    }

    private data class Tree(val directories: Set<String>, val files: Map<String, String>) {
        fun renamed(from: String, to: String): Tree {
            fun replace(path: String): String = when {
                path == from -> to
                path.startsWith("$from/") -> to + path.removePrefix(from)
                else -> path
            }
            return Tree(directories.map(::replace).toSet(), files.mapKeys { replace(it.key) })
        }
    }

    private class RenameFaults(
        private val failFrom: String? = null,
        private val failTo: String? = null,
        val omitParent: Boolean = false,
    ) {
        val renames = mutableListOf<Pair<String?, String>>()
        var injected = false
        var deleteCalls = 0

        fun rename(file: UniFile, name: String): Boolean {
            val previous = file.name
            renames += previous to name
            if (previous == failFrom && name == failTo && !injected) {
                injected = true
                return false
            }
            return file.renameTo(name)
        }

        fun delete(): Nothing {
            deleteCalls++
            error("Publication must not delete existing or staging files")
        }
    }

    /** Names, URIs, data and actual successful renames still come from the real private RawFile. */
    private class GuardedUniFile(private val delegate: UniFile, private val faults: RenameFaults) :
        UniFile(if (faults.omitParent) null else delegate.parentFile) {
        private fun wrap(file: UniFile?): UniFile? = file?.let { GuardedUniFile(it, faults) }
        override fun createFile(name: String): UniFile? = wrap(delegate.createFile(name))
        override fun createDirectory(name: String): UniFile? = wrap(delegate.createDirectory(name))
        override fun getUri(): Uri = delegate.uri
        override fun getName(): String? = delegate.name
        override fun getType(): String? = delegate.type
        override fun getFilePath(): String? = delegate.filePath
        override fun isDirectory(): Boolean = delegate.isDirectory
        override fun isFile(): Boolean = delegate.isFile
        override fun lastModified(): Long = delegate.lastModified()
        override fun length(): Long = delegate.length()
        override fun canRead(): Boolean = delegate.canRead()
        override fun canWrite(): Boolean = delegate.canWrite()
        override fun delete(): Boolean = faults.delete()
        override fun exists(): Boolean = delegate.exists()
        override fun listFiles(): Array<UniFile>? = delegate.listFiles()?.map {
            requireNotNull(wrap(it))
        }?.toTypedArray()
        override fun listFiles(filter: FilenameFilter): Array<UniFile>? =
            delegate.listFiles(filter)?.map { requireNotNull(wrap(it)) }?.toTypedArray()
        override fun findFile(name: String): UniFile? = wrap(delegate.findFile(name))
        override fun renameTo(name: String): Boolean = faults.rename(delegate, name)
        override fun openOutputStream(): OutputStream = delegate.openOutputStream()
        override fun openOutputStream(append: Boolean): OutputStream = delegate.openOutputStream(append)
        override fun openInputStream(): InputStream = delegate.openInputStream()
        override fun createRandomAccessFile(mode: String): UniRandomAccessFile = delegate.createRandomAccessFile(mode)
    }

    private companion object {
        val METADATA =
            "<?xml version=\"1.0\"?><ComicInfo><Title>Neutral fixture</Title></ComicInfo>".toByteArray()

        fun uni(file: File): UniFile = requireNotNull(UniFile.fromFile(file))

        fun failure(block: () -> Unit): Throwable? = try {
            block()
            null
        } catch (expected: Exception) {
            expected
        }

        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
