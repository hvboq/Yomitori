package mihon.translation

import android.content.ComponentName
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mihon.testprovider.SafAliasDocumentsProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Actual ContentResolver/DocumentsProvider archive writes with a synthetic tree and callback.
 * No picker grant, OEM-provider emulation, Activity, native inference or public queue claim.
 */
@RunWith(AndroidJUnit4::class)
class SafArchiveBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 30_000)
    fun completeRewriteKeepsExactNamedOpaqueBackupAndVerifiedOriginalBytes(): Unit = runBlocking {
        withFixture { fixture ->
            val result = rewrite(fixture.archive) { extracted -> fixture.changeFirstPage(extracted) }

            assertTrue(result)
            fixture.assertBackup()
            fixture.assertReplacement()
            fixture.assertTargetWrites(1)
        }
    }

    @Test(timeout = 30_000)
    fun cooperativePartialRewritePreservesPendingBytesWithoutReportingCompletion(): Unit = runBlocking {
        withFixture { fixture ->
            val stopped = AtomicBoolean(false)
            val result = rewrite(fixture.archive, success = false, shouldStop = stopped::get) { extracted ->
                fixture.changeFirstPage(extracted)
                stopped.set(true)
            }

            assertTrue(stopped.get())
            assertFalse("Persisted partial archive must not report full completion", result)
            fixture.assertBackup()
            fixture.assertReplacement()
            fixture.assertTargetWrites(1)
        }
    }

    @Test(timeout = 30_000)
    fun targetProviderFailureAfterTruncateRestoresOriginalFromVerifiedExactBackup(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.session.failNextWriteDocumentId.set(fixture.archiveId)
            var failure: IOException? = null
            try {
                rewrite(fixture.archive) { extracted -> fixture.changeFirstPage(extracted) }
            } catch (caught: IOException) {
                failure = caught
            }

            assertEquals(
                "Archive replacement failed; verified original backup retained",
                failure?.message,
            )
            assertEquals(
                "Owned one-shot target fault must have been reached",
                null,
                fixture.session.failNextWriteDocumentId.get(),
            )
            assertArrayEquals(fixture.initialArchive, fixture.read(fixture.archive))
            assertEquals(fixture.initialHash, sha(fixture.read(fixture.archive)))
            fixture.assertBackup()
            fixture.assertTargetWrites(2)
            fixture.assertPacked(fixture.archiveFile, "001.jpg", ORIGINAL_PAGE)
            fixture.assertPacked(fixture.archiveFile, "002.jpg", SECOND_PAGE)
            ZipFile(fixture.archiveFile).use { zip ->
                assertEquals(
                    "Failed replacement must not leave staged metadata",
                    null,
                    zip.getEntry(".yakuyomi/opaque-pending.fixture"),
                )
            }
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        assertEquals("Isolated audit installation required", "app.yomitori.yakuyomi.audit", context.packageName)
        val session = SafAliasDocumentsProvider.createSession(context)
        try {
            block(Fixture(session))
        } finally {
            session.close()
        }
    }

    private inner class Fixture(val session: SafAliasDocumentsProvider.Session) {
        val archiveFile = File(session.chapter, ARCHIVE).apply {
            ZipOutputStream(outputStream()).use { output ->
                for ((name, bytes) in mapOf("001.jpg" to ORIGINAL_PAGE, "002.jpg" to SECOND_PAGE)) {
                    output.putNextEntry(ZipEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
        val initialArchive = archiveFile.readBytes()
        val initialHash = sha(initialArchive)
        val archiveId = session.documentId(archiveFile)
        private val authority = context.packageManager.getProviderInfo(
            ComponentName(context, SafAliasDocumentsProvider::class.java),
            0,
        ).authority
        private val tree = DocumentsContract.buildTreeDocumentUri(authority, "${session.id}|root-a")
        private val chapter = requireNotNull(
            UniFile.fromUri(
                context,
                DocumentsContract.buildDocumentUriUsingTree(tree, session.documentId(session.chapter)),
            ),
        )
        val archive = requireNotNull(chapter.findFile(ARCHIVE))

        init {
            assertEquals("content", archive.uri.scheme)
            assertTrue(DocumentsContract.isTreeUri(archive.uri))
            assertTrue(DocumentsContract.isDocumentUri(context, archive.uri))
            assertEquals(archiveId, DocumentsContract.getDocumentId(archive.uri))
            assertEquals(chapter.uri, requireNotNull(archive.parentFile).uri)
            assertArrayEquals(initialArchive, read(archive))
            assertTrue(session.createRequests.isEmpty())
        }

        fun changeFirstPage(extracted: UniFile) {
            assertEquals("file", extracted.uri.scheme)
            val directory = File(requireNotNull(extracted.uri.path))
            val work = requireNotNull(directory.canonicalFile.parentFile)
            check(work.name.startsWith("yakutr_") && work.parentFile == context.cacheDir.canonicalFile)
            assertArrayEquals(ORIGINAL_PAGE, File(directory, "001.jpg").readBytes())
            assertArrayEquals(SECOND_PAGE, File(directory, "002.jpg").readBytes())
            val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
            File(materials, "001.orig.jpg").writeBytes(ORIGINAL_PAGE)
            File(materials, "opaque-pending.fixture").writeBytes(PENDING)
            File(directory, "001.jpg").writeBytes(CHANGED_PAGE)
        }

        fun assertBackup() {
            val request = session.createRequests.single()
            assertEquals(
                "Backup creation must not infer MIME from the .bak suffix",
                "application/octet-stream",
                request.first,
            )
            assertTrue(request.second.matches(Regex("Chapter\\.cbz\\.yomitori-original-[0-9a-f-]{36}\\.bak")))
            val backup = requireNotNull(chapter.findFile(request.second))
            assertEquals(request.second, backup.name)
            assertTrue(backup.isFile)
            assertNotEquals(archive.uri, backup.uri)
            assertEquals(
                session.documentId(File(session.chapter, request.second)),
                DocumentsContract.getDocumentId(backup.uri),
            )
            assertArrayEquals(initialArchive, read(backup))
            assertEquals(initialHash, sha(read(backup)))
            val diskBackups = requireNotNull(session.chapter.listFiles()).filter { it.name != ARCHIVE }
            assertEquals(
                "Only the owned archive and exact-named backup may be created",
                listOf(request.second),
                diskBackups.map { it.name },
            )
            val backupId = DocumentsContract.getDocumentId(backup.uri)
            assertTrue(session.writeModes.any { it.first == backupId && it.second == "wt" })
            assertTrue("Backup bytes must be read back through the actual resolver", backupId in session.reads)
        }

        fun assertReplacement() {
            assertFalse(initialArchive.contentEquals(read(archive)))
            assertPacked(archiveFile, "001.jpg", CHANGED_PAGE)
            assertPacked(archiveFile, "002.jpg", SECOND_PAGE)
            assertPacked(archiveFile, ".yakuyomi/001.orig.jpg", ORIGINAL_PAGE)
            assertPacked(archiveFile, ".yakuyomi/opaque-pending.fixture", PENDING)
        }

        fun assertPacked(archive: File, name: String, expected: ByteArray) {
            ZipFile(archive).use { zip ->
                val entry = requireNotNull(zip.getEntry(name))
                val actual = zip.getInputStream(entry).use { it.readBytes() }
                assertArrayEquals(expected, actual)
                assertEquals(expected.size.toLong(), entry.size)
                assertEquals(java.util.zip.CRC32().apply { update(actual) }.value, entry.crc)
            }
        }

        fun assertTargetWrites(expected: Int) {
            assertEquals(expected, session.writeModes.count { it.first == archiveId && it.second == "wt" })
            assertTrue("Target archive must be verified through resolver reads", archiveId in session.reads)
        }

        fun read(file: UniFile): ByteArray = requireNotNull(context.contentResolver.openInputStream(file.uri)).use {
            it.readBytes()
        }
    }

    /** Private wrapper control only; manager scope belongs exclusively to this fixture. */
    private suspend fun rewrite(
        archive: UniFile,
        success: Boolean = true,
        shouldStop: () -> Boolean = { false },
        process: suspend (UniFile) -> Unit,
    ): Boolean {
        val manager = TranslationManager(context)
        val resultClass = TranslationManager::class.java.declaredClasses.single { it.simpleName == "ProcessResult" }
        val constructor = resultClass.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val callback: suspend (UniFile) -> Any = { directory ->
            process(directory)
            constructor.newInstance(true, success)
        }
        val method = TranslationManager::class.java.declaredMethods.single { it.name == "processArchiveInPlace" }
            .apply { isAccessible = true }
        try {
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try {
                    method.invoke(manager, archive, { _: Int, _: Int -> }, shouldStop, callback, continuation)
                } catch (failure: InvocationTargetException) {
                    throw requireNotNull(failure.cause)
                }
            }
        } finally {
            val scope = TranslationManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(manager) as CoroutineScope
            scope.cancel()
        }
    }

    companion object {
        private const val ARCHIVE = "Chapter.cbz"
        private val ORIGINAL_PAGE = byteArrayOf(1, 2, 3, 4)
        private val SECOND_PAGE = byteArrayOf(5, 6, 7)
        private val CHANGED_PAGE = byteArrayOf(9, 8)
        private val PENDING = byteArrayOf(3, 1, 4, 1, 5)
        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
