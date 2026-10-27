// This test-only package permits delegation through UniFile's package-private constructor.
package com.hippo.unifile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mihon.translation.DownloaderEndToEndFixture
import mihon.translation.NativeDiagnosticIdleGuard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.util.system.getDisplayMaxHeightInPx
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Actual Downloader boundaries; neutral private files, platform image decoding and libarchive ZIP writing. */
@RunWith(AndroidJUnit4::class)
class DownloaderStorageBoundaryTest {
    @Test(timeout = 60_000)
    fun thousandPageTallImageUsesItsStoredPrefixAndPreservesCollidingNeighbors(): Unit = runBlocking {
        withFixture("thousandPageTallImageUsesItsStoredPrefixAndPreservesCollidingNeighbors") { fixture, report ->
            val directory = caseDirectory(fixture, "tall")
            val height = getDisplayMaxHeightInPx * 2 + 16
            check(height in 97..32_768)
            val target = File(directory, "0001.png").apply { writeBytes(encoded(Color.WHITE, height)) }
            val neighbors = listOf("0010.png", "00010.png").associateWith { encoded(Color.BLACK, height) }
            neighbors.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
            val unrelated = File(directory, "unrelated.bin").apply { writeBytes(byteArrayOf(3, 1, 4, 1, 5)) }
            val source = field(field(fixture, "server"), "source") as HttpSource
            val pages = List(1000) { Page(it, "/neutral/$it", "${source.baseUrl}/image/1.png") }
            val download = Download(source, field(fixture, "manga") as Manga, field(fixture, "chapter") as Chapter)
                .apply { this.pages = pages }
            (field(fixture, "downloadPreferences") as DownloadPreferences).splitTallImages.set(true)
            // Provider enumeration order must not make either prefix collision the selected source.
            val faults = Faults(priority = listOf("00010.png", "0010.png"))
            val wrapped = FaultFile(uni(directory), faults)
            val owner = downloader(fixture)
            invokeSuspend(owner, "getOrDownloadImage", pages[0], download, wrapped)
            val parts = directory.listFiles().orEmpty().filter { it.name.startsWith("0001__") }.sortedBy { it.name }
            report.put("pageCount", pages.size).put("storedPrefixDigits", 4).put("splitPartCount", parts.size)
                .put("collidingNeighborCount", neighbors.size)
            assertEquals("TALL_PAGE_READY", Page.State.Ready, pages[0].status)
            assertEquals("TALL_EXACT_PART_NAMES", listOf("0001__001.jpg", "0001__002.jpg"), parts.map { it.name })
            assertFalse("TALL_SOURCE_REPLACED_BY_SPLITS", target.exists())
            assertEquals("TALL_READY_URI_IS_FIRST_SPLIT", Uri.fromFile(parts.first()), pages[0].uri)
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val readyBytes = checkNotNull(resolver.openInputStream(checkNotNull(pages[0].uri))).use { it.readBytes() }
            assertArrayEquals("TALL_READY_URI_READABLE", parts.first().readBytes(), readyBytes)
            var decodedHeight = 0
            for (part in parts) {
                val bitmap = checkNotNull(BitmapFactory.decodeFile(part.path))
                try {
                    assertEquals("TALL_SPLIT_WIDTH", 16, bitmap.width)
                    val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue("TALL_CORRECT_SOURCE_PIXELS", Color.red(center) >= 250 && Color.green(center) >= 250)
                    decodedHeight += bitmap.height
                } finally {
                    bitmap.recycle()
                }
            }
            assertEquals("TALL_COMPLETE_HEIGHT", height, decodedHeight)
            neighbors.forEach { (name, bytes) ->
                assertArrayEquals("TALL_NEIGHBOR_PRESERVED", bytes, File(directory, name).readBytes())
            }
            assertArrayEquals("TALL_UNRELATED_PRESERVED", byteArrayOf(3, 1, 4, 1, 5), unrelated.readBytes())
            val beforeRetry = snapshot(directory)
            invokeSuspend(owner, "getOrDownloadImage", pages[0], download, wrapped)
            assertEquals("TALL_REPEAT_DOES_NOT_RESPLIT", beforeRetry, snapshot(directory))
            assertEquals("TALL_REPEAT_READY", Page.State.Ready, pages[0].status)
            report.put("decodedHeightMatched", true).put("neighborBytesPreserved", true)
                .put("samePageRepeatUnchanged", true).put("realImageDecode", true)
        }
    }

    @Test(timeout = 60_000)
    fun repeatedZipWriteFailuresRemoveOnlyTheirNewStagingFiles(): Unit = runBlocking {
        withFixture("repeatedZipWriteFailuresRemoveOnlyTheirNewStagingFiles") { fixture, report ->
            val directory = archiveCase(fixture, "write-failure")
            val source = File(directory, "Chapter_tmp")
            val before = snapshot(directory)
            val faults = Faults(missingSource = File(source, "missing-owned-input.png"))
            val rows = JSONArray()
            repeat(2) { attempt ->
                val error = failure {
                    invokeArchive(
                        downloader(fixture),
                        FaultFile(uni(directory), faults),
                        FaultFile(uni(source), faults),
                    )
                }
                val ownStagesGone = faults.created.all { !File(directory, it).exists() }
                val unchanged = before == snapshot(directory)
                rows.put(
                    JSONObject().put("attempt", attempt + 1).put("writeFailed", error is IOException)
                        .put("ownedStagesGone", ownStagesGone).put("protectedTreeUnchanged", unchanged),
                )
                assertTrue("ZIP_WRITE_FAILURE_PROPAGATED", error is IOException)
                assertEquals("ZIP_SOURCE_DESCRIPTOR_FAULT_REACHED", attempt + 1, faults.sourceDescriptorFaults)
                assertEquals("ZIP_NEW_STAGE_CREATED", attempt + 1, faults.created.size)
                assertTrue("ZIP_FAILED_NEW_STAGE_REMOVED", ownStagesGone)
                assertTrue("ZIP_SOURCE_AND_UNRELATED_PRESERVED", unchanged)
            }
            assertEquals("ZIP_DISTINCT_OWNED_STAGES", 2, faults.created.distinct().size)
            assertEquals("ZIP_ONLY_OWNED_STAGES_DELETED", faults.created.toSet(), faults.deleted.toSet())
            assertEquals("ZIP_NO_DUPLICATE_DELETE", 2, faults.deleted.size)
            report.put("attempts", rows).put("newStagesCreated", 2).put("newStagesRemaining", 0)
                .put("sourceDescriptorFaults", faults.sourceDescriptorFaults).put("realZipWriter", true)
        }
    }

    @Test(timeout = 60_000)
    fun ambiguousArchivePublishFailurePreservesCompletedOutputAndSource(): Unit = runBlocking {
        withFixture("ambiguousArchivePublishFailurePreservesCompletedOutputAndSource") { fixture, report ->
            val directory = archiveCase(fixture, "publish-failure")
            val source = File(directory, "Chapter_tmp")
            val before = snapshot(directory)
            val faults = Faults(renameThenRefuse = true)
            val error = failure {
                invokeArchive(downloader(fixture), FaultFile(uni(directory), faults), FaultFile(uni(source), faults))
            }
            assertTrue("ZIP_PUBLISH_FAILURE_PROPAGATED", error is IOException)
            assertEquals("ZIP_AMBIGUOUS_RENAME_REACHED", 1, faults.ambiguousRenames)
            assertEquals("ZIP_AMBIGUOUS_OUTPUT_NOT_DELETED", emptyList<String>(), faults.deleted)
            val published = File(directory, "Chapter.cbz")
            assertTrue("ZIP_AMBIGUOUS_COMPLETE_OUTPUT_PRESERVED", published.isFile)
            ZipFile(published).use { zip ->
                val entry = checkNotNull(zip.getEntry("001.png"))
                assertArrayEquals(
                    "ZIP_AMBIGUOUS_OUTPUT_EXACT",
                    File(source, "001.png").readBytes(),
                    zip.getInputStream(entry).use { it.readBytes() },
                )
            }
            val after = snapshot(directory)
            assertEquals("ZIP_AMBIGUOUS_ONLY_NEW_OUTPUT", before.keys + "Chapter.cbz", after.keys)
            before.forEach { (name, hash) -> assertEquals("ZIP_AMBIGUOUS_SOURCE_PRESERVED", hash, after[name]) }
            report.put("providerRenamedBeforeReturningFalse", true).put("completedArchivePreserved", true)
                .put("protectedTreeUnchanged", true).put("realZipWriter", true)
        }
    }

    private suspend fun withFixture(
        method: String,
        block: suspend (DownloaderEndToEndFixture, JSONObject) -> Unit,
    ) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        check(InstrumentationRegistry.getArguments().getString("downloaderStorageBoundaryRegression") == "true") {
            "Explicit downloaderStorageBoundaryRegression opt-in required"
        }
        NativeDiagnosticIdleGuard.requireIdle(target)
        val preferencesBefore = preferenceSnapshot(target)
        val report = JSONObject().put("testMethod", method).put("terminal", false).put("passed", false)
            .put("actualDownloaderBoundary", true).put("actualPublicQueue", false).put("actualActivity", false)
            .put("modelInferenceCalls", 0).put("liveSiteOrTranslationServerCalls", 0)
        val fixture = DownloaderEndToEndFixture(target, archive = false)
        var failure: Throwable? = null
        try {
            fixture.prepare()
            block(fixture, report)
            val requests = field(field(fixture, "server"), "requests") as Collection<*>
            assertEquals("STORAGE_BOUNDARY_HTTP_REQUESTS", 0, requests.size)
            report.put("httpImageRequests", 0)
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                try {
                    fixture.close()
                    assertEquals("STORAGE_BOUNDARY_RAW_PREFERENCES", preferencesBefore, preferenceSnapshot(target))
                    report.put("fixtureClosed", true).put("rawPreferencesUnchanged", true)
                } catch (cleanup: Throwable) {
                    if (failure == null || cleanup is VirtualMachineError || cleanup is ThreadDeath ||
                        cleanup is LinkageError
                    ) {
                        failure = cleanup
                    } else {
                        failure?.addSuppressed(cleanup)
                    }
                } finally {
                    report.put("terminal", true).put("passed", failure == null)
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        2,
                        Bundle().apply { putString("downloaderStorageBoundaryEvidence", report.toString()) },
                    )
                }
            }
        }
        failure?.let { throw it }
    }

    private class Faults(
        val priority: List<String> = emptyList(),
        val missingSource: File? = null,
        val renameThenRefuse: Boolean = false,
    ) {
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var sourceDescriptorFaults = 0
        var ambiguousRenames = 0
    }

    private class FaultFile(private val delegate: UniFile, private val faults: Faults) : UniFile(delegate.parentFile) {
        private fun wrap(file: UniFile?): UniFile? = file?.let { FaultFile(it, faults) }
        override fun createFile(name: String): UniFile? = wrap(delegate.createFile(name)).also {
            if (it != null && name.startsWith(".yomitori-download-")) faults.created += name
        }
        override fun createDirectory(name: String): UniFile? = wrap(delegate.createDirectory(name))
        override fun getUri(): Uri {
            if (delegate.name == "001.png" && faults.missingSource != null) {
                faults.sourceDescriptorFaults++
                return Uri.fromFile(faults.missingSource)
            }
            return delegate.uri
        }
        override fun getName(): String? = delegate.name
        override fun getType(): String? = delegate.type
        override fun getFilePath(): String? = delegate.filePath
        override fun isDirectory(): Boolean = delegate.isDirectory
        override fun isFile(): Boolean = delegate.isFile
        override fun lastModified(): Long = delegate.lastModified()
        override fun length(): Long = delegate.length()
        override fun canRead(): Boolean = delegate.canRead()
        override fun canWrite(): Boolean = delegate.canWrite()
        override fun delete(): Boolean {
            faults.deleted += checkNotNull(delegate.name)
            return delegate.delete()
        }
        override fun exists(): Boolean = delegate.exists()
        override fun listFiles(): Array<UniFile>? = delegate.listFiles()?.sortedBy {
            faults.priority.indexOf(it.name).takeIf { rank -> rank >= 0 } ?: faults.priority.size
        }?.map { checkNotNull(wrap(it)) }?.toTypedArray()
        override fun listFiles(filter: FilenameFilter): Array<UniFile>? =
            delegate.listFiles(filter)?.map { checkNotNull(wrap(it)) }?.toTypedArray()
        override fun findFile(name: String): UniFile? = wrap(delegate.findFile(name))
        override fun renameTo(name: String): Boolean {
            if (faults.renameThenRefuse && delegate.name.orEmpty().startsWith(".yomitori-download-")) {
                check(delegate.renameTo(name))
                faults.ambiguousRenames++
                return false
            }
            return delegate.renameTo(name)
        }
        override fun openOutputStream(): OutputStream = delegate.openOutputStream()
        override fun openOutputStream(append: Boolean): OutputStream = delegate.openOutputStream(append)
        override fun openInputStream(): InputStream = delegate.openInputStream()
        override fun createRandomAccessFile(mode: String): UniRandomAccessFile = delegate.createRandomAccessFile(mode)
    }

    private companion object {
        fun field(owner: Any, name: String): Any = checkNotNull(
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner),
        )
        fun downloader(fixture: DownloaderEndToEndFixture) =
            field(field(fixture, "manager"), "downloader") as Downloader
        fun uni(file: File): UniFile = checkNotNull(UniFile.fromFile(file))
        fun caseDirectory(fixture: DownloaderEndToEndFixture, name: String) =
            File(field(fixture, "root") as File, name).apply { check(mkdir()) }
        fun archiveCase(fixture: DownloaderEndToEndFixture, name: String): File = caseDirectory(fixture, name).apply {
            File(this, "Chapter_tmp").apply { check(mkdir()) }.let { source ->
                File(source, "001.png").writeBytes(encoded(Color.WHITE, 40))
            }
            File(this, "unrelated.bin").writeBytes(byteArrayOf(2, 7, 1, 8))
            File(this, ".yomitori-download-existing_tmp").writeBytes(byteArrayOf(9, 4, 2))
        }
        fun encoded(color: Int, height: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(16, height, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        fun snapshot(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
                .digest(it.readBytes()).joinToString("") { value -> "%02x".format(value.toInt() and 255) }
        }
        fun preferenceSnapshot(context: Context) = snapshot(File(context.applicationInfo.dataDir, "shared_prefs"))
        fun failure(block: () -> Unit): Exception? = try {
            block()
            null
        } catch (error: Exception) {
            error
        }
        fun invokeArchive(owner: Downloader, manga: UniFile, source: UniFile) {
            val method = owner.javaClass.getDeclaredMethod(
                "archiveChapter",
                UniFile::class.java,
                String::class.java,
                UniFile::class.java,
            ).apply { isAccessible = true }
            try {
                method.invoke(owner, manga, "Chapter.cbz", source)
            } catch (error: InvocationTargetException) {
                throw checkNotNull(error.cause)
            }
        }
        suspend fun invokeSuspend(owner: Any, name: String, vararg arguments: Any): Any? =
            suspendCoroutineUninterceptedOrReturn { continuation ->
                val method = owner.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }
                try {
                    method.invoke(owner, *arguments, continuation)
                } catch (error: InvocationTargetException) {
                    throw checkNotNull(error.cause)
                }
            }
    }
}
