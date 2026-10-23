// This test-only package permits delegation through UniFile's package-private constructor.
package com.hippo.unifile

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.service.TranslationPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real single-page storage with synthetic bitmaps and manifest-read faults; no Activity or server. */
@RunWith(AndroidJUnit4::class)
class ManifestReadFailureSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun unreadableLegacyCompletionNeverReplacesPreservedOriginalWithTranslatedDisplay() = runBlocking {
        withFixture { fixture ->
            val originalBefore = fixture.original.readBytes()
            val displayBefore = fixture.page.readBytes()
            fixture.faults.arm(2)
            val outcome = fixture.translator.translateSinglePage(fixture.chapter, PAGE, METHOD)
            val faultsReached = fixture.faults.hits.get()
            fixture.faults.disarm()
            val originalAfter = fixture.original.readBytes()
            val displayAfter = fixture.page.readBytes()
            val completed = fixture.translator.isPageTranslated(fixture.chapter, PAGE)
            val report = JSONObject()
                .put("case", "legacy-original-protection")
                .put("faultBudget", 2)
                .put("manifestReadFaultsReached", faultsReached)
                .put("engineCalls", fixture.engine.calls.get())
                .put("engineInputPixel", fixture.engine.inputPixel)
                .put("outcome", outcome.name)
                .put("originalShaBefore", sha(originalBefore))
                .put("originalShaAfter", sha(originalAfter))
                .put("displayShaBefore", sha(displayBefore))
                .put("displayShaAfter", sha(displayAfter))
                .put("completedAfter", completed)
            record("legacy-original-protection", report)
            assertTrue("The actual manifest read must encounter the fixture fault: $report", faultsReached > 0)
            if (fixture.engine.calls.get() > 0) {
                assertEquals("An unsafe inference must also reach the persistence recheck: $report", 2, faultsReached)
            }
            assertEquals(
                "A read failure must not promote the translated display to original: $report",
                sha(originalBefore),
                sha(originalAfter),
            )
            assertArrayEquals(
                "A failed classification must preserve the displayed page: $report",
                displayBefore,
                displayAfter,
            )
            assertEquals("Unreadable completion must fail before inference: $report", 0, fixture.engine.calls.get())
            assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, outcome)
            assertTrue("The pre-existing verified completion remains valid after reads recover", completed)
        }
    }

    @Test
    fun failedCompletionSnapshotBeforeManifestCommitNeverDropsAnotherCompletedPage() = runBlocking {
        withFixture { fixture ->
            val oldOriginal = fixture.original.readBytes()
            val oldDisplay = fixture.page.readBytes()
            File(fixture.directory, NEXT_PAGE).writeBytes(encoded(Color.WHITE))
            // Initial classification is healthy. Only the post-inference persistence/commit reads fail;
            // CompletionManifestStore's subsequent readFiles calls can see the old manifest again.
            fixture.engine.onInference = { fixture.faults.arm(2) }
            val outcome = fixture.translator.translateSinglePage(fixture.chapter, NEXT_PAGE, METHOD)
            val faultsReached = fixture.faults.hits.get()
            fixture.faults.disarm()
            val oldCompleted = fixture.translator.isPageTranslated(fixture.chapter, PAGE)
            val newCompleted = fixture.translator.isPageTranslated(fixture.chapter, NEXT_PAGE)
            val report = JSONObject()
                .put("case", "completion-snapshot-recovery")
                .put("faultBudget", 2)
                .put("manifestReadFaultsReached", faultsReached)
                .put("engineCalls", fixture.engine.calls.get())
                .put("engineInputPixel", fixture.engine.inputPixel)
                .put("outcome", outcome.name)
                .put("oldOriginalShaBefore", sha(oldOriginal))
                .put("oldOriginalShaAfter", sha(fixture.original.readBytes()))
                .put("oldDisplayShaBefore", sha(oldDisplay))
                .put("oldDisplayShaAfter", sha(fixture.page.readBytes()))
                .put("oldPageCompletedAfter", oldCompleted)
                .put("newPageCompletedAfter", newCompleted)
            record("completion-snapshot-recovery", report)
            assertEquals(
                "The real single-page caller must reach inference before this fault: $report",
                1,
                fixture.engine.calls.get(),
            )
            assertTrue("The real post-inference read must fail: $report", faultsReached > 0)
            if (outcome == PageTranslator.SinglePageResult.TRANSLATED) {
                assertEquals("Successful old-code commit must encounter both injected reads: $report", 2, faultsReached)
            }
            assertTrue("A recovered store read must not lose another page's completion: $report", oldCompleted)
            assertArrayEquals(oldOriginal, fixture.original.readBytes())
            assertArrayEquals(oldDisplay, fixture.page.readBytes())
            assertEquals(outcome == PageTranslator.SinglePageResult.TRANSLATED, newCompleted)
        }
    }

    @Test
    fun invalidPrimaryAndCheckpointNeverPromoteTranslatedDisplayToOriginal() = runBlocking {
        withFixture { fixture ->
            val originalBefore = fixture.original.readBytes()
            val displayBefore = fixture.page.readBytes()
            val invalid = CompletionManifestCodec.encode(setOf(PAGE)).copyOf()
            val checksumStart = invalid.indexOf('\n'.code.toByte()) + 1
            check(checksumStart > 0)
            invalid[checksumStart] = if (invalid[checksumStart] == '0'.code.toByte()) {
                '1'.code.toByte()
            } else {
                '0'.code.toByte()
            }
            assertEquals(
                "Only the checksum is corrupted; it cannot verify",
                null,
                CompletionManifestCodec.decode(invalid),
            )
            val primary = File(fixture.directory, CompletionManifestCodec.PRIMARY).apply { writeBytes(invalid) }
            val checkpoint = File(
                fixture.directory,
                CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID(),
            ).apply { writeBytes(invalid) }
            val outcome = fixture.translator.translateSinglePage(fixture.chapter, PAGE, METHOD)
            val originalAfter = fixture.original.readBytes()
            val displayAfter = fixture.page.readBytes()
            val completed = fixture.translator.isPageTranslated(fixture.chapter, PAGE)
            val report = JSONObject()
                .put("case", "all-invalid-checksum-protection")
                .put("encoding", "verified-format-with-invalid-sha256")
                .put("manifestBytesBefore", invalid.size)
                .put("primaryBytesAfter", primary.length())
                .put("checkpointExistsAfter", checkpoint.isFile)
                .put("engineCalls", fixture.engine.calls.get())
                .put("engineInputPixel", fixture.engine.inputPixel)
                .put("manifestReadFaultsReached", fixture.faults.hits.get())
                .put("outcome", outcome.name)
                .put("originalShaBefore", sha(originalBefore))
                .put("originalShaAfter", sha(originalAfter))
                .put("displayShaBefore", sha(displayBefore))
                .put("displayShaAfter", sha(displayAfter))
                .put("completedAfter", completed)
            record("all-invalid-checksum-protection", report)
            assertEquals(
                "Invalid completion bytes must not promote translated pixels to original: $report",
                sha(originalBefore),
                sha(originalAfter),
            )
            assertArrayEquals("The previous display must remain unchanged: $report", displayBefore, displayAfter)
            assertEquals("Unverifiable completion must fail before inference: $report", 0, fixture.engine.calls.get())
            assertEquals("No synthetic provider fault is used", 0, fixture.faults.hits.get())
            assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, outcome)
            assertEquals("Invalid completion is not a displayed completed state", false, completed)
        }
    }

    @Test
    fun oversizedPrimaryAndCheckpointNeverPromoteTranslatedDisplayToOriginal() = runBlocking {
        withFixture { fixture ->
            val originalBefore = fixture.original.readBytes()
            val displayBefore = fixture.page.readBytes()
            val oversized = ByteArray(CompletionManifestCodec.MAX_BYTES + 1)
            val primary = File(fixture.directory, CompletionManifestCodec.PRIMARY).apply { writeBytes(oversized) }
            val checkpoint = File(
                fixture.directory,
                CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID(),
            ).apply { writeBytes(oversized) }
            assertEquals("The primary really exceeds the read budget", oversized.size.toLong(), primary.length())
            assertEquals("The checkpoint really exceeds the read budget", oversized.size.toLong(), checkpoint.length())
            val outcome = fixture.translator.translateSinglePage(fixture.chapter, PAGE, METHOD)
            val originalAfter = fixture.original.readBytes()
            val displayAfter = fixture.page.readBytes()
            val completed = fixture.translator.isPageTranslated(fixture.chapter, PAGE)
            val report = JSONObject()
                .put("case", "all-oversized-completion-protection")
                .put("encoding", "max-bytes-plus-one")
                .put("maximumManifestBytes", CompletionManifestCodec.MAX_BYTES)
                .put("manifestBytesBefore", oversized.size)
                .put("primaryBytesAfter", primary.length())
                .put("checkpointExistsAfter", checkpoint.isFile)
                .put("engineCalls", fixture.engine.calls.get())
                .put("engineInputPixel", fixture.engine.inputPixel)
                .put("manifestReadFaultsReached", fixture.faults.hits.get())
                .put("outcome", outcome.name)
                .put("originalShaBefore", sha(originalBefore))
                .put("originalShaAfter", sha(originalAfter))
                .put("displayShaBefore", sha(displayBefore))
                .put("displayShaAfter", sha(displayAfter))
                .put("completedAfter", completed)
            record("all-oversized-completion-protection", report)
            assertEquals(
                "Oversized completion bytes must not promote translated pixels to original: $report",
                sha(originalBefore),
                sha(originalAfter),
            )
            assertArrayEquals("The previous display must remain unchanged: $report", displayBefore, displayAfter)
            assertEquals("Unverifiable completion must fail before inference: $report", 0, fixture.engine.calls.get())
            assertEquals("No synthetic provider fault is used", 0, fixture.faults.hits.get())
            assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, outcome)
            assertEquals("Oversized completion is not a displayed completed state", false, completed)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        check(context.packageName == "app.yomitori.yakuyomi.audit") {
            "Manifest read-failure regressions require the isolated audit installation"
        }
        assertTrue("PageTranslator requires the prepared model set", TranslationEngineConfig.modelsResolvable(context))
        val directory = File(context.cacheDir, "manifest-read-failure-${UUID.randomUUID()}").apply { check(mkdir()) }
        check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        var service: TranslationEngineService? = null
        val engine = ScriptedEngine()
        try {
            val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
            val original = File(materials, "001.orig.png").apply { writeBytes(encoded(Color.WHITE)) }
            val page = File(directory, PAGE).apply { writeBytes(encoded(Color.BLACK)) }
            File(directory, CompletionManifestCodec.PRIMARY)
                .writeBytes(CompletionManifestCodec.encode(setOf(PAGE)))
            val preferences = TranslationPreferences(InMemoryPreferenceStore())
            val activeService = TranslationEngineService(context)
            service = activeService
            serviceField("translationPreferences").set(activeService, preferences)
            val signature = TranslationEngineService::class.java.getDeclaredMethod(
                "configSignature",
                String::class.java,
                TranslationPreferences::class.java,
            ).apply { isAccessible = true }.invoke(activeService, METHOD, preferences.snapshot())
            serviceField("builtSignature").set(activeService, signature)
            serviceField("warmedUp").setBoolean(activeService, true)
            serviceField("engine").set(activeService, engine)
            val translator = PageTranslator(context).also {
                PageTranslator::class.java.getDeclaredField("translationPreferences").apply { isAccessible = true }
                    .set(it, preferences)
                PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }
                    .set(it, activeService)
            }
            val faults = ReadFaults()
            val chapter = FaultingUniFile(requireNotNull(UniFile.fromFile(directory)), faults)
            block(Fixture(directory, page, original, chapter, faults, engine, translator))
        } finally {
            try {
                service?.shutdown()
            } finally {
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(directory.deleteRecursively()) { "Cannot remove the private manifest fixture" }
            }
        }
    }

    private fun record(case: String, report: JSONObject) {
        val reports = File(context.filesDir, "manifest-read-failure-audit").apply { check(isDirectory || mkdir()) }
        val output = File(reports, "$case.json")
        output.writeText(report.toString(2))
        println("MANIFEST_READ_FAILURE_REPORT=${output.absolutePath}")
        println(report.toString(2))
    }

    private class Fixture(
        val directory: File,
        val page: File,
        val original: File,
        val chapter: UniFile,
        val faults: ReadFaults,
        val engine: ScriptedEngine,
        val translator: PageTranslator,
    )

    private class ScriptedEngine : TranslationEngine {
        override val translationContext = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
        val calls = AtomicInteger()
        var inputPixel: Int? = null
        var onInference: () -> Unit = {}
        override suspend fun translatePage(page: Bitmap): PageResult {
            calls.incrementAndGet()
            inputPixel = page.getPixel(0, 0)
            onInference()
            return PageResult.Translated(image(Color.GREEN), PageStats(0, 0, 0, 0, 0, 0, 0, 0))
        }
        override fun warmUp() = Unit
        override fun close() = Unit
    }

    private class ReadFaults {
        private val remaining = AtomicInteger()
        val hits = AtomicInteger()
        fun arm(count: Int) {
            check(count > 0)
            hits.set(0)
            remaining.set(count)
        }
        fun disarm() {
            remaining.set(0)
        }
        fun beforeUri(name: String?) {
            if (name != CompletionManifestCodec.PRIMARY && name != CompletionManifestCodec.PREVIOUS &&
                name?.let(CompletionManifestCodec::isCheckpoint) != true
            ) {
                return
            }
            while (true) {
                val count = remaining.get()
                if (count <= 0) return
                if (remaining.compareAndSet(count, count - 1)) {
                    hits.incrementAndGet()
                    throw IOException("Synthetic manifest input URI read failure")
                }
            }
        }
    }

    /** Every operation delegates to private RawFile storage except the explicitly armed URI reads. */
    private class FaultingUniFile(private val delegate: UniFile, private val faults: ReadFaults) : UniFile(null) {
        private fun wrap(file: UniFile?): UniFile? = file?.let { FaultingUniFile(it, faults) }
        override fun createFile(name: String): UniFile? = wrap(delegate.createFile(name))
        override fun createDirectory(name: String): UniFile? = wrap(delegate.createDirectory(name))
        override fun getUri(): Uri {
            faults.beforeUri(delegate.name)
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
        override fun delete(): Boolean = delegate.delete()
        override fun exists(): Boolean = delegate.exists()
        override fun listFiles(): Array<UniFile>? = delegate.listFiles()?.map {
            requireNotNull(wrap(it))
        }?.toTypedArray()
        override fun listFiles(filter: FilenameFilter): Array<UniFile>? =
            delegate.listFiles(filter)?.map { requireNotNull(wrap(it)) }?.toTypedArray()
        override fun findFile(name: String): UniFile? = wrap(delegate.findFile(name))
        override fun renameTo(name: String): Boolean = delegate.renameTo(name)
        override fun openOutputStream(): OutputStream = delegate.openOutputStream()
        override fun openOutputStream(append: Boolean): OutputStream = delegate.openOutputStream(append)
        override fun openInputStream(): InputStream = delegate.openInputStream()
        override fun createRandomAccessFile(mode: String): UniRandomAccessFile = delegate.createRandomAccessFile(mode)
    }

    companion object {
        private const val SIZE = 32
        private const val PAGE = "001.png"
        private const val NEXT_PAGE = "002.png"
        private const val METHOD = "boxfill"
        private fun serviceField(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        private fun sha(bytes: ByteArray) = PartialPageStore.sha256(bytes)
        private fun image(
            color: Int,
        ) = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        private fun encoded(color: Int): ByteArray {
            val bitmap = image(color)
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
