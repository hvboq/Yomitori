// This test-only package permits delegation through UniFile's package-private constructor.
package com.hippo.unifile

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.UUID

/** Real private manifest reads with counted provider metadata, not native inference or timing. */
@RunWith(AndroidJUnit4::class)
class ManifestSnapshotIoTest {
    @Test
    fun unrelatedFileTypeQueriesStayZeroAsTheChapterGrows() {
        val cases = listOf(0, 32, 128, 256).map { count ->
            withFixture { fixture ->
                val bytes = CompletionManifestCodec.encode(PAGES)
                fixture.file(PRIMARY, bytes)
                repeat(count) { index -> fixture.file("page-$index.avif", byteArrayOf(7, 8, 9)) }
                val snapshot = readSnapshot(fixture.translator, fixture.chapter)
                assertEquals(setOf(PRIMARY), snapshot.keys)
                assertArrayEquals(bytes, snapshot.getValue(PRIMARY))
                assertEquals(PAGES, CompletionManifestCodec.resolve(snapshot))
                assertEquals(1, fixture.counts.listCalls)
                assertEquals(2, fixture.counts.types(PRIMARY))
                assertEquals(1, fixture.counts.uris(PRIMARY))
                JSONObject().put("unrelatedEntries", count)
                    .put(
                        "unrelatedTypeQueries",
                        fixture.counts.typeQueries.values.sum() - fixture.counts.types(PRIMARY),
                    )
                    .put("unrelatedUriQueries", fixture.counts.uriQueries.values.sum() - fixture.counts.uris(PRIMARY))
                    .put("candidateTypeQueries", fixture.counts.types(PRIMARY))
                    .put("candidateUriQueries", fixture.counts.uris(PRIMARY))
                    .put("listCalls", fixture.counts.listCalls)
            }
        }
        // Collect every chapter size before the RED assertion so the original baseline is useful.
        report("chapter-growth", cases)
        cases.forEach {
            assertEquals("Unrelated entries must not require type metadata: $it", 0, it.getInt("unrelatedTypeQueries"))
            assertEquals(0, it.getInt("unrelatedUriQueries"))
        }
    }

    @Test
    fun unknownAndUnrelatedNamesDoNotRequirePotentiallyFailingTypeMetadata() {
        val names = listOf(null, "001.png", CompletionManifestCodec.CHECKPOINT_PREFIX + "not-a-checkpoint")
        val cases = names.mapIndexed { index, exposedName ->
            withFixture { fixture ->
                fixture.file(PRIMARY, CompletionManifestCodec.encode(PAGES))
                val actualName = "opaque-$index.bin"
                fixture.file(actualName, byteArrayOf(5, 6))
                fixture.overrides[actualName] = NameOverride(exposedName, throwOnType = true)
                val outcome = runCatching { readSnapshot(fixture.translator, fixture.chapter) }
                outcome.getOrNull()?.let { assertEquals(PAGES, CompletionManifestCodec.resolve(it)) }
                JSONObject().put("nameCase", index).put("nameIsNull", exposedName == null)
                    .put("failureClass", outcome.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                    .put("unrelatedTypeQueries", fixture.counts.types(actualName))
                    .put("unrelatedUriQueries", fixture.counts.uris(actualName))
                    .put("candidateUriQueries", fixture.counts.uris(PRIMARY))
            }
        }
        report("unavailable-unrelated-metadata", cases)
        cases.forEach {
            assertTrue(
                "An unrelated provider entry must not break the authoritative snapshot: $it",
                it.isNull("failureClass"),
            )
            assertEquals(0, it.getInt("unrelatedTypeQueries"))
            assertEquals(0, it.getInt("unrelatedUriQueries"))
            assertEquals(1, it.getInt("candidateUriQueries"))
        }
    }

    @Test
    fun manifestNamedDirectoriesAreCheckedButNeverOpened() = withFixture { fixture ->
        val checkpoint = CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID()
        fixture.file(PRIMARY, CompletionManifestCodec.encode(PAGES))
        fixture.directory(CompletionManifestCodec.PREVIOUS)
        fixture.directory(checkpoint)
        val snapshot = readSnapshot(fixture.translator, fixture.chapter)
        val row = JSONObject().put(
            "candidateDirectoryTypeQueries",
            fixture.counts.types(CompletionManifestCodec.PREVIOUS) + fixture.counts.types(checkpoint),
        )
            .put(
                "candidateDirectoryUriQueries",
                fixture.counts.uris(CompletionManifestCodec.PREVIOUS) + fixture.counts.uris(checkpoint),
            )
            .put("fileTypeQueries", fixture.counts.types(PRIMARY)).put("fileUriQueries", fixture.counts.uris(PRIMARY))
        report("manifest-named-directory-protection", listOf(row))
        assertEquals(PAGES, CompletionManifestCodec.resolve(snapshot))
        assertEquals(setOf(PRIMARY), snapshot.keys)
        assertEquals(2, row.getInt("candidateDirectoryTypeQueries"))
        assertEquals(0, row.getInt("candidateDirectoryUriQueries"))
        assertEquals(2, row.getInt("fileTypeQueries"))
        assertEquals(1, row.getInt("fileUriQueries"))
    }

    @Test
    fun corruptCandidatesStillFailAndAValidCheckpointRecovers() = withFixture { fixture ->
        val checkpoint = CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID()
        val corrupt = "YOMITORI-MANIFEST-1\n${"0".repeat(64)}\n[\"fabricated.png\"]".toByteArray()
        fixture.file(PRIMARY, corrupt)
        fixture.file(checkpoint, corrupt)
        val rejected = runCatching { readSnapshot(fixture.translator, fixture.chapter) }
        val rejectedTypes = fixture.counts.typeQueries.values.sum()
        val rejectedUris = fixture.counts.uriQueries.values.sum()
        assertTrue("All present corrupt snapshots must remain an error", rejected.exceptionOrNull() is IOException)
        assertEquals(4, rejectedTypes)
        assertEquals(2, rejectedUris)

        val verified = CompletionManifestCodec.encode(PAGES)
        fixture.file(checkpoint, verified)
        fixture.counts.reset()
        val recovered = readSnapshot(fixture.translator, fixture.chapter)
        val row = JSONObject().put("allCorruptFailureClass", rejected.exceptionOrNull()?.javaClass?.simpleName)
            .put("allCorruptTypeQueries", rejectedTypes).put("allCorruptUriQueries", rejectedUris)
            .put("recoveredCompletedPages", CompletionManifestCodec.resolve(recovered).size)
            .put("recoveryTypeQueries", fixture.counts.typeQueries.values.sum())
            .put("recoveryUriQueries", fixture.counts.uriQueries.values.sum())
        report("corrupt-refusal-and-checkpoint-recovery", listOf(row))
        assertEquals(setOf(PRIMARY, checkpoint), recovered.keys)
        assertArrayEquals(corrupt, recovered.getValue(PRIMARY))
        assertArrayEquals(verified, recovered.getValue(checkpoint))
        assertEquals(PAGES, CompletionManifestCodec.resolve(recovered))
        assertEquals(4, row.getInt("recoveryTypeQueries"))
        assertEquals(2, row.getInt("recoveryUriQueries"))
    }

    private fun <T> withFixture(block: (Fixture) -> T): T {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit target required" }
        val settings = SettingsGuard(context)
        val parent = context.cacheDir.canonicalFile
        val directory = File(parent, "manifest-snapshot-io-${UUID.randomUUID()}")
        check(!directory.exists() && directory.mkdir() && directory.canonicalFile.parentFile == parent)
        val fixture = Fixture(directory, PageTranslator(context))
        try {
            return block(fixture)
        } finally {
            try {
                fixture.verifyAndDelete(parent)
            } finally {
                settings.verify()
            }
        }
    }

    private class Fixture(val root: File, val translator: PageTranslator) {
        val counts = Counts()
        val overrides = mutableMapOf<String, NameOverride>()
        private val expectedFiles = mutableMapOf<String, ByteArray>()
        private val expectedDirectories = mutableSetOf<String>()
        val chapter: UniFile = CountedUniFile(requireNotNull(UniFile.fromFile(root)), counts, overrides)
        fun file(name: String, bytes: ByteArray) {
            require('/' !in name && name !in expectedDirectories)
            File(root, name).writeBytes(bytes)
            expectedFiles[name] = bytes.copyOf()
        }
        fun directory(name: String) {
            require('/' !in name && name !in expectedFiles)
            check(File(root, name).mkdir())
            expectedDirectories += name
        }
        fun verifyAndDelete(parent: File) {
            check(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
            val children = checkNotNull(root.listFiles())
            check(children.map { it.name }.toSet() == expectedFiles.keys + expectedDirectories)
            children.forEach { file ->
                check(file.canonicalFile == file.absoluteFile && file.parentFile == root)
                val state = Os.lstat(file.path)
                check(state.st_uid == Process.myUid() && !OsConstants.S_ISLNK(state.st_mode))
                if (file.name in expectedFiles) {
                    check(OsConstants.S_ISREG(state.st_mode) && state.st_nlink == 1L)
                    assertArrayEquals(
                        "The read must never mutate fixture bytes",
                        expectedFiles.getValue(file.name),
                        file.readBytes(),
                    )
                } else {
                    check(OsConstants.S_ISDIR(state.st_mode) && file.list()?.isEmpty() == true)
                }
            }
            // Verify all fixture members before the first removal; only these exact UUID children.
            children.forEach { check(it.delete()) }
            check(root.delete() && !root.exists())
        }
    }

    private data class NameOverride(val name: String?, val throwOnType: Boolean)
    private class Counts {
        val typeQueries = mutableMapOf<String, Int>()
        val uriQueries = mutableMapOf<String, Int>()
        var listCalls = 0
        fun types(name: String) = typeQueries[name] ?: 0
        fun uris(name: String) = uriQueries[name] ?: 0
        fun reset() {
            typeQueries.clear()
            uriQueries.clear()
            listCalls = 0
        }
    }

    /** Metadata faults are injected only at unrelated entries; successful streams use real RawFile URI. */
    private class CountedUniFile(
        private val delegate: UniFile,
        private val counts: Counts,
        private val overrides: Map<String, NameOverride>,
    ) : UniFile(null) {
        private val actualName get() = checkNotNull(delegate.name)
        private fun wrap(file: UniFile?): UniFile? = file?.let { CountedUniFile(it, counts, overrides) }
        override fun getUri(): Uri {
            counts.uriQueries[actualName] = counts.uris(actualName) + 1
            return delegate.uri
        }
        override fun getName(): String? =
            overrides[actualName]?.name ?: if (actualName in overrides) null else delegate.name
        override fun isFile(): Boolean {
            counts.typeQueries[actualName] = counts.types(actualName) + 1
            if (overrides[actualName]?.throwOnType ==
                true
            ) {
                throw IOException("Unrelated synthetic provider type failure")
            }
            return delegate.isFile
        }
        override fun listFiles(): Array<UniFile>? {
            counts.listCalls++
            return delegate.listFiles()?.sortedBy { it.name?.let(overrides::get)?.throwOnType != true }
                ?.map { requireNotNull(wrap(it)) }?.toTypedArray()
        }
        override fun listFiles(filter: FilenameFilter): Array<UniFile>? = delegate.listFiles(filter)
            ?.map { requireNotNull(wrap(it)) }?.toTypedArray()
        override fun getType(): String? = delegate.type
        override fun getFilePath(): String? = delegate.filePath
        override fun isDirectory(): Boolean = delegate.isDirectory
        override fun lastModified(): Long = delegate.lastModified()
        override fun length(): Long = delegate.length()
        override fun canRead(): Boolean = delegate.canRead()
        override fun canWrite(): Boolean = delegate.canWrite()
        override fun exists(): Boolean = delegate.exists()
        override fun findFile(name: String): UniFile? = wrap(delegate.findFile(name))
        override fun openInputStream(): InputStream = delegate.openInputStream()
        override fun createFile(name: String): UniFile? = error("Read-only fixture")
        override fun createDirectory(name: String): UniFile? = error("Read-only fixture")
        override fun delete(): Boolean = error("Read-only fixture")
        override fun renameTo(name: String): Boolean = error("Read-only fixture")
        override fun openOutputStream(): OutputStream = error("Read-only fixture")
        override fun openOutputStream(append: Boolean): OutputStream = error("Read-only fixture")
        override fun createRandomAccessFile(mode: String): UniRandomAccessFile = error("Read-only fixture")
    }

    /** Read only whitelisted settings; never inspect credentials or write settings in this fixture. */
    private class SettingsGuard(context: Context) {
        private val preferences = Injekt.get<TranslationPreferences>().let { prefs ->
            listOf<Preference<*>>(
                prefs.translationMasterEnabled, prefs.translationEnabled, prefs.liveTranslate, prefs.nightReadEnabled,
                prefs.provider, prefs.model, prefs.apiBase, prefs.sourceLangName, prefs.targetLangName,
                prefs.inpaintMethod, prefs.liveInpaintMethod, prefs.ocrConcurrency, prefs.stripPad, prefs.useBicubic,
                prefs.ocrUnsharp, prefs.bboxPad, prefs.tileSize, prefs.maskDilate, prefs.segThreshold, prefs.dbnetSize,
                prefs.detectUnsharp, prefs.minProb, prefs.orientation,
            )
        }
        private val xmlFile =
            File(
                File(context.applicationInfo.dataDir).canonicalFile,
                "shared_prefs/${context.packageName}_preferences.xml",
            )
        private val beforeMemory = memoryDigest()
        private val beforeDisk = diskDigest()
        private fun memoryDigest() = digest(
            preferences.sortedBy {
                it.key()
            }.joinToString("\n") { "${it.key()}|${it.isSet()}|${it.get()}" }.toByteArray(),
        )
        private fun diskDigest(): String {
            val keys = preferences.map { it.key() }.toSet()
            if (!xmlFile.exists()) {
                check(preferences.none { it.isSet() })
                return digest("selected-xml-absent".toByteArray())
            }
            val selected = mutableMapOf<String, String>()
            xmlFile.inputStream().use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        val key = parser.getAttributeValue(null, "name")
                        if (key in keys) {
                            val type = parser.name
                            val value = when (type) {
                                "string" -> parser.nextText()
                                "boolean", "int", "long", "float" -> parser.getAttributeValue(null, "value")
                                else -> error("Unsupported selected setting type")
                            }
                            check(selected.put(checkNotNull(key), "$type|$value") == null)
                        }
                    }
                    parser.next()
                }
            }
            check(preferences.all { it.isSet() == (it.key() in selected) })
            return digest(keys.sorted().joinToString("\n") { "$it|${selected[it] ?: "ABSENT"}" }.toByteArray())
        }
        fun verify() {
            check(beforeMemory == memoryDigest()) { "Selected setting values or presence changed" }
            check(beforeDisk == diskDigest()) { "Selected setting disk values or presence changed" }
        }
    }

    private companion object {
        const val PRIMARY = CompletionManifestCodec.PRIMARY
        val PAGES = setOf("001.avif", "002.png")
        fun digest(
            bytes: ByteArray,
        ) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun report(case: String, rows: List<JSONObject>) {
            val value = JSONObject().put("case", case).put("observations", JSONArray(rows))
                .put("nativeOrServerCallsByTest", 0).put("preferencesWrittenByTest", 0)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply { putString("stream", "MANIFEST_SNAPSHOT_IO=$value\n") },
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun readSnapshot(translator: PageTranslator, chapter: UniFile): Map<String, ByteArray> = try {
            val method = PageTranslator::class.java.getDeclaredMethod("readManifestFiles", UniFile::class.java).apply {
                isAccessible =
                    true
            }
            method.invoke(translator, chapter) as Map<String, ByteArray>
        } catch (error: InvocationTargetException) {
            throw checkNotNull(error.cause)
        }
    }
}
