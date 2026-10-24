package mihon.translation

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.capture.CAPTURE_META_FILE
import eu.kanade.tachiyomi.ui.capture.CaptureSaveError
import eu.kanade.tachiyomi.ui.capture.CaptureSaveResult
import eu.kanade.tachiyomi.ui.capture.CaptureSiteSetting
import eu.kanade.tachiyomi.ui.capture.CaptureViewModel
import eu.kanade.tachiyomi.ui.capture.MANGA_META_FILE
import eu.kanade.tachiyomi.ui.capture.readMangaMeta
import eu.kanade.tachiyomi.ui.capture.readMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.testprovider.SafAliasDocumentsProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.ByteArrayOutputStream
import java.io.File

/** Actual fresh capture writes through a private SAF StorageManager; no Activity, native or HTTP. */
@RunWith(AndroidJUnit4::class)
class CaptureFreshStorageFailureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 30_000)
    fun providerWriteFailureRemovesFreshIncompletePageAndPreservesPreviousFile(): Unit = runBlocking {
        withFixture { fixture ->
            // The provider accepts an owned future document ID before createDocument creates it.
            val freshId = fixture.session.documentId(fixture.freshFile)
            fixture.session.failNextWriteDocumentId.set(freshId)
            val capture = image(Color.BLUE)
            try {
                val result = fixture.model.saveCapture(capture, CAPTURE_URL)
                assertTrue("A real provider write failure must be reported", result is CaptureSaveResult.Failed)
                assertEquals(CaptureSaveError.WRITE, (result as CaptureSaveResult.Failed).reason)
                assertNull(
                    "The exact-document provider fault must be consumed",
                    fixture.session.failNextWriteDocumentId.get(),
                )
                assertTrue(
                    "The fresh page must have reached provider creation",
                    ("image/png" to "001") in fixture.session.createRequests,
                )
                assertTrue(
                    "The fresh page must reach ContentResolver's truncating write path",
                    fixture.session.writeModes.any { it.first == freshId && it.second == "wt" },
                )
                fixture.assertPreviousUnchanged()
                assertFalse("The caller retains ownership of its input bitmap", capture.isRecycled)
                // This is deliberately the required behavior, not an assertion that preserves the bug.
                assertFalse(
                    "A rejected fresh write must remove the provider-created incomplete PNG",
                    fixture.freshFile.exists(),
                )
                assertNull("The failed page must not remain visible through SAF", fixture.chapter.findFile(PAGE))
                assertFalse(
                    "A failed new page must not be recorded in chapter metadata",
                    "001" in readMeta(fixture.chapter),
                )
                val retry = fixture.model.saveCapture(capture, CAPTURE_URL)
                assertTrue("The consumed one-shot fault must allow a normal retry", retry is CaptureSaveResult.Saved)
                retry as CaptureSaveResult.Saved
                assertEquals("A rejected fresh write must not consume a page number", 1, retry.page)
                val retryUri = Uri.parse(retry.path)
                assertEquals("The retry must still use real SAF storage", "content", retryUri.scheme)
                assertEquals(freshId, DocumentsContract.getDocumentId(retryUri))
                val retryBytes = fixture.read(retryUri)
                assertArrayEquals(fixture.freshFile.readBytes(), retryBytes)
                assertArrayEquals(
                    "The retry must persist a PNG",
                    byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10),
                    retryBytes.copyOfRange(0, 8),
                )
                val storedRetry = checkNotNull(BitmapFactory.decodeByteArray(retryBytes, 0, retryBytes.size)) {
                    "The retried PNG must decode"
                }
                try {
                    assertEquals(SIZE, storedRetry.width)
                    assertEquals(SIZE, storedRetry.height)
                    assertEquals(Color.BLUE, storedRetry.getPixel(SIZE / 2, SIZE / 2))
                } finally {
                    storedRetry.recycle()
                }
                assertEquals(CAPTURE_URL, readMeta(fixture.chapter)["001"])
                fixture.assertPreviousUnchanged()
            } finally {
                if (!capture.isRecycled) capture.recycle()
            }
        }
    }

    @Test(timeout = 30_000)
    fun successfulFreshCapturePersistsRealPngAndMetadataAndPreservesPreviousFile(): Unit = runBlocking {
        withFixture { fixture ->
            val capture = image(Color.BLUE)
            try {
                val result = fixture.model.saveCapture(capture, CAPTURE_URL)
                assertTrue("The normal SAF control must save successfully", result is CaptureSaveResult.Saved)
                result as CaptureSaveResult.Saved
                assertEquals(1, result.page)
                val savedUri = Uri.parse(result.path)
                assertEquals("The control must use actual SAF storage", "content", savedUri.scheme)
                assertEquals(fixture.session.documentId(fixture.freshFile), DocumentsContract.getDocumentId(savedUri))
                val providerBytes = fixture.read(savedUri)
                assertArrayEquals(
                    "SAF reads and owned backing bytes must agree",
                    fixture.freshFile.readBytes(),
                    providerBytes,
                )
                assertArrayEquals(
                    "The persisted page must have a PNG signature",
                    byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10),
                    providerBytes.copyOfRange(0, 8),
                )
                val stored = checkNotNull(BitmapFactory.decodeByteArray(providerBytes, 0, providerBytes.size)) {
                    "The persisted fresh page must be a decodable PNG"
                }
                try {
                    assertEquals(SIZE, stored.width)
                    assertEquals(SIZE, stored.height)
                    val actual = IntArray(SIZE * SIZE)
                    stored.getPixels(actual, 0, SIZE, 0, 0, SIZE, SIZE)
                    assertArrayEquals(IntArray(SIZE * SIZE) { Color.BLUE }, actual)
                } finally {
                    stored.recycle()
                }
                assertEquals(CAPTURE_URL, readMeta(fixture.chapter)["001"])
                assertEquals(CAPTURE_URL, readMangaMeta(fixture.manga))
                assertTrue(
                    "Chapter metadata must really exist on the provider",
                    File(fixture.chapterFile, CAPTURE_META_FILE).isFile,
                )
                assertTrue(
                    "Manga metadata must really exist on the provider",
                    File(fixture.mangaFile, MANGA_META_FILE).isFile,
                )
                assertTrue(
                    "The normal control must use the same truncating SAF write path",
                    fixture.session.writeModes.any {
                        it.first == fixture.session.documentId(fixture.freshFile) && it.second == "wt"
                    },
                )
                fixture.assertPreviousUnchanged()
                assertFalse("The caller retains ownership of its input bitmap", capture.isRecycled)
            } finally {
                if (!capture.isRecycled) capture.recycle()
            }
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        assertEquals("Isolated audit installation required", "app.yomitori.yakuyomi.audit", context.packageName)
        val session = SafAliasDocumentsProvider.createSession(context)
        val models = ViewModelStore()
        var storage: StorageManager? = null
        var storageStopped = false
        var failure: Throwable? = null
        try {
            val authority = context.packageManager.getProviderInfo(
                ComponentName(context, SafAliasDocumentsProvider::class.java),
                0,
            ).authority
            val tree = DocumentsContract.buildTreeDocumentUri(authority, "${session.id}|root-a")
            val storagePreferences = StoragePreferences(
                object : FolderProvider {
                    override fun directory(): File = session.directory
                    override fun path(): String = tree.toString()
                },
                InMemoryPreferenceStore(),
            )
            val privateStorage = StorageManager(context, storagePreferences).also { storage = it }
            val local = requireNotNull(privateStorage.getLocalSourceDirectory())
            assertEquals("The real StorageManager must resolve a content tree", "content", local.uri.scheme)
            val manga = requireNotNull(local.createDirectory(BOOK))
            val chapter = requireNotNull(manga.createDirectory(CHAPTER))
            val previous = requireNotNull(chapter.createFile(PREVIOUS))
            val previousBytes = encoded(Color.WHITE)
            context.contentResolver.openOutputStream(previous.uri, "wt").use { output ->
                requireNotNull(output).write(previousBytes)
            }
            val model = CaptureViewModel(
                context.applicationContext as Application,
                privateStorage,
                UiPreferences(InMemoryPreferenceStore()),
            )
            onMain {
                models.put("private-fresh-capture", model)
                model.bookName = BOOK
                model.chapterName = CHAPTER
            }
            model.saveSiteSetting(CAPTURE_URL, CaptureSiteSetting(autoTrim = false))
            session.createRequests.clear()
            session.writeModes.clear()
            block(Fixture(session, manga, chapter, model, previousBytes))
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            fun cleanup(block: () -> Unit) {
                try {
                    block()
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                }
            }
            cleanup { onMain { models.clear() } }
            cleanup {
                storage?.let { privateStorage ->
                    val scope = StorageManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                        .get(privateStorage) as CoroutineScope
                    scope.cancel()
                    runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]?.join() } }
                }
                storageStopped = true
            }
            cleanup {
                // Session.close only deletes its UUID-owned cache root after checking its identity.
                // Retain an unexpectedly live worker's files instead of racing their removal.
                check(storageStopped) { "Private storage did not stop; retaining its owned fixture" }
                check(
                    session.directory.canonicalFile ==
                        File(context.cacheDir.canonicalFile, "saf-alias-${session.id}"),
                )
                session.close()
            }
        }
        failure?.let { throw it }
    }

    private inner class Fixture(
        val session: SafAliasDocumentsProvider.Session,
        val manga: UniFile,
        val chapter: UniFile,
        val model: CaptureViewModel,
        private val previousBytes: ByteArray,
    ) {
        val mangaFile = session.checked(File(session.directory, "local/$BOOK"))
        val chapterFile = session.checked(File(mangaFile, CHAPTER))
        val freshFile = session.checked(File(chapterFile, PAGE))

        fun read(uri: Uri): ByteArray =
            requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }

        fun assertPreviousUnchanged() {
            val previous = requireNotNull(chapter.findFile(PREVIOUS))
            assertArrayEquals("Existing bytes must survive a new-page save attempt", previousBytes, read(previous.uri))
            assertArrayEquals(previousBytes, File(chapterFile, PREVIOUS).readBytes())
        }
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (caught: Throwable) {
                failure = caught
            }
        }
        failure?.let { throw it }
    }

    private fun image(color: Int): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }

    private fun encoded(color: Int): ByteArray = image(color).let { bitmap ->
        try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val BOOK = "NeutralFreshCapture"
        const val CHAPTER = "01"
        const val PAGE = "001.png"
        const val PREVIOUS = "existing.png"
        const val SIZE = 64
        const val CAPTURE_URL = "https://capture.example.invalid/neutral-page"
    }
}
