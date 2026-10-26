package mihon.translation

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.nightread.NightPolicy
import eu.kanade.tachiyomi.data.nightread.NightSourceRevision
import eu.kanade.tachiyomi.data.translation.CapturedSourceStore
import eu.kanade.tachiyomi.data.translation.CapturedSourceUniFileIo
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.ui.reader.loader.NightPageStreams
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.testprovider.SafAliasDocumentsProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.service.TranslationPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Neutral UUID-owned storage/reader-stream tests. No native models, translation server, global
 * preferences or user chapters. SAF methods require the isolated audit package and its real
 * DocumentsProvider. Simulated process death is an Error boundary, not an actual killed process.
 */
@RunWith(AndroidJUnit4::class)
class NightReadPolicyProofTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sourceAndMaterialSameMetadataAbaCannotReviveAnOldIndex() = raw { f ->
        val old = f.prepared()
        assertNotNull(f.open(old))
        val sourceTime = f.sourceFile.lastModified()
        val sourceBytes = f.sourceFile.readBytes()
        val oldRevision = f.revision()
        NightSourceRevision.mutate(context, f.chapter, f.materials, PAGE) {
            val changed = sourceBytes.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
            f.sourceFile.writeBytes(changed)
            assertArrayEquals(changed, f.sourceFile.readBytes())
            check(f.sourceFile.setLastModified(sourceTime))
        }
        assertNotEquals(oldRevision.id, f.revision().id)
        assertEquals(sourceTime, f.sourceFile.lastModified())
        assertEquals(sourceBytes.size.toLong(), f.sourceFile.length())
        assertNull(f.open(old))

        f.publish()
        val materialIndex = f.prepared()
        assertNotNull(f.open(materialIndex))
        val materialTime = f.materialFile.lastModified()
        val materialBytes = f.materialFile.readBytes()
        NightSourceRevision.mutate(context, f.chapter, f.materials, PAGE) {
            val changed = materialBytes.copyOf().apply { this[0] = (first().toInt() xor 1).toByte() }
            f.materialFile.writeBytes(changed)
            assertArrayEquals(changed, f.materialFile.readBytes())
            check(f.materialFile.setLastModified(materialTime))
        }
        assertNull(f.open(materialIndex))
        f.assertProtected()
    }

    @Test
    fun sourcePendingSurvivesRestartAndCannotBeLiftedByNightRetry() = raw { f ->
        val old = f.prepared()
        NightSourceRevision.begin(context, f.chapter, f.materials, PAGE)
        repeat(3) {
            assertNull(f.open(old))
            expectIo { NightSourceRevision.ensureCommitted(context, f.chapter, f.materials, PAGE) }
            assertFalse(f.revision().committed)
            assertEquals(NightSourceRevision.Kind.SOURCE, f.revision().kind)
        }
        // Only a real verified successor/rollback, not a read, can settle source pending.
        NightSourceRevision.mutate(context, f.chapter, f.materials, PAGE) {
            f.sourceFile.writeBytes(f.originalBytes)
            assertArrayEquals(f.originalBytes, f.sourceFile.readBytes())
        }
        assertTrue(f.revision().committed)
        assertNull(f.open(old))
        f.assertProtected()
    }

    @Test
    fun publicationPendingCanBeRepairedOnlyWithUnchangedProtectedSource() = raw { f ->
        val oldRevision = f.revision()
        val witness = NightPolicy.snapshot(f.chapter, f.materials, PAGE)
        NightSourceRevision.beginPublication(context, f.chapter, f.materials, PAGE, witness)
        assertFalse(f.revision().committed)
        assertEquals(NightSourceRevision.Kind.PUBLICATION, f.revision().kind)
        val repaired = NightSourceRevision.ensureCommitted(context, f.chapter, f.materials, PAGE)
        assertTrue(repaired.committed)
        assertNotEquals(oldRevision.id, repaired.id)
        assertNull(f.open(f.prepared())) // old payload cannot acquire the new revision

        f.publish()
        val next = NightPolicy.snapshot(f.chapter, f.materials, PAGE)
        NightSourceRevision.beginPublication(context, f.chapter, f.materials, PAGE, next)
        f.materialFile.appendText("changed")
        expectIo { NightSourceRevision.ensureCommitted(context, f.chapter, f.materials, PAGE) }
        assertFalse(f.revision().committed)
        f.assertProtected()
    }

    @Test
    fun partialJournalRecoveryChangesRevisionButReadOnlyLoadDoesNot() = raw { f ->
        val old = f.prepared()
        val store = PartialPageStore.forUniFile(context, f.chapter, f.materials)
        val original = PartialPageStore.Original(
            OriginalMaterial.exactName(PAGE),
            PartialPageStore.sha256(f.originalBytes),
            f.originalBytes.size.toLong(),
        )
        val output = encoded(Color.GREEN)
        var stopped = false
        try {
            store.commit(
                PAGE, original, 4, 4, "{}", output, encoded(Color.BLACK), null, complete = false,
                checkCancellation = {
                    if (f.sourceFile.readBytes().contentEquals(output)) throw SimulatedProcessDeath()
                },
            )
        } catch (_: SimulatedProcessDeath) {
            stopped = true
        }
        assertTrue(stopped)
        val interrupted = f.revision()
        assertNull(f.open(old))
        assertNull(PartialPageStore.forUniFile(context, f.chapter, f.materials).load(PAGE))
        val recovered = f.revision()
        assertTrue(recovered.committed)
        assertNotEquals(interrupted.id, recovered.id)
        assertArrayEquals(f.originalBytes, f.sourceFile.readBytes())
        assertNull(f.open(old))
        assertNull(store.load(PAGE))
        assertEquals(recovered, f.revision())
        f.assertProtected()
    }

    @Test
    fun realSafValidRepeatedDecodeListsAndReadsCaptureHistoryZeroTimes() = saf { f, session ->
        val streams = f.streams()
        assertTrue("initial refresh must actually validate real capture history", session.captureHistoryOpens.get() > 0)
        session.childQueries.set(0)
        session.captureHistoryOpens.set(0)
        session.sourceImageOpens.set(0)
        repeat(8) {
            assertArrayEquals(f.nightBytes, streams.open(0) { f.source.openInputStream() }.use { it.readBytes() })
        }
        assertEquals(0, session.childQueries.get())
        assertEquals(0, session.captureHistoryOpens.get())
        assertEquals("valid Night decode must not hash/open original pixels", 0, session.sourceImageOpens.get())
        f.assertProtected()
    }

    @Test
    fun realSafMissingCorruptPendingAndLegacyRepeatedFallbackNeverRefreshes() = saf { f, session ->
        val cases = listOf<() -> Unit>(
            { f.proofFiles().forEach { check(it.delete()) } },
            {
                f.publish()
                f.proofFiles().first().writeBytes(ByteArray(513))
            },
            {
                f.publish()
                NightSourceRevision.begin(context, f.chapter, f.materials, PAGE)
            },
            {
                // A verified source successor is required to leave prior source pending.
                NightSourceRevision.mutate(context, f.chapter, f.materials, PAGE) {
                    f.sourceFile.writeBytes(f.originalBytes)
                    assertArrayEquals(f.originalBytes, f.sourceFile.readBytes())
                }
                f.proofFiles().forEach { check(it.delete()) }
                File(f.materialsFile, NightPages.legacyName(PAGE)).writeBytes(f.nightBytes)
            },
        )
        cases.forEach { prepare ->
            prepare()
            val streams = f.streams()
            session.childQueries.set(0)
            session.captureHistoryOpens.set(0)
            session.sourceImageOpens.set(0)
            repeat(6) {
                assertArrayEquals(
                    f.sourceFile.readBytes(),
                    streams.open(0) {
                        f.source.openInputStream()
                    }.use { it.readBytes() },
                )
            }
            assertEquals(0, session.childQueries.get())
            assertEquals(0, session.captureHistoryOpens.get())
            assertEquals(
                "only the six actual fallback streams may open source pixels",
                6,
                session.sourceImageOpens.get(),
            )
        }
        f.assertProtected()
    }

    @Test
    fun realSafRecaptureRollbackRevokesOldProofEvenWhenPixelsReturnExactly() = saf { f, session ->
        val old = f.prepared()
        val before = f.revision()
        session.failNextPageWrite.set(true)
        var failed = false
        try {
            runBlocking { PageTranslator(context).replaceCapturedSource(f.chapter, PAGE, encoded(Color.RED)) }
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        val after = f.revision()
        assertTrue(after.committed)
        assertNotEquals(before.id, after.id)
        assertEquals(before.capturedEpoch, after.capturedEpoch)
        assertArrayEquals(f.originalBytes, f.sourceFile.readBytes())
        assertNull(f.open(old))
        f.assertProtected()
    }

    @Test
    fun sameFilenameNewGenerationAndPerPageEventsReloadAlreadyDecodedPage() = raw { f ->
        val streams = f.streams()
        streams.open(0) { f.source.openInputStream() }.close()
        val oldName = streams.servedNightName(0)
        val page = ReaderPage(0)
        f.publish()
        assertEquals(listOf(page), streams.pagesToReload(listOf(page)))
        assertEquals(oldName, NightPages.levelName(PAGE, NightLevel.MORE))
        streams.open(0) { f.source.openInputStream() }.close()
        assertTrue(streams.pagesToReload(listOf(page)).isEmpty())
        val event = NightSourceRevision.Change(
            NightSourceRevision.chapterKey(f.chapter),
            PAGE,
            NightSourceRevision.sequence,
        )
        assertEquals(listOf(page), streams.changedPages(event, listOf(page)))
        f.assertProtected()
    }

    @Test
    fun actualNightRoundRevocationFailureAbortsBeforeAnyFixedImageOverwrite() = raw { f ->
        val round = f.round()
        val oldImages = NightPages.currentNames(PAGE).associateWith { File(f.materialsFile, it).readBytes() }
        val blockingProof = f.proofFiles().first()
        check(blockingProof.delete() && blockingProof.mkdir())
        assertFalse(round.commit())
        oldImages.forEach { (name, bytes) -> assertArrayEquals(bytes, File(f.materialsFile, name).readBytes()) }
        round.discard()
        f.assertProtected()
    }

    @Test
    fun actualNightRoundPublishesNewPairAndPartialPublicationCannotAuthorizeEitherTier() = raw { f ->
        val other = f.addOtherPage()
        val otherBytes = other.exactPageBytes()
        val oldProofNames = f.proofFiles().map { it.name }.also { assertEquals(2, it.size) }
        val old = f.prepared()
        val round = f.round()
        assertTrue(round.commit())
        oldProofNames.forEach { name -> assertFalse(File(f.materialsFile, name).exists()) }
        assertNull(f.open(old))
        assertNull(f.open(f.prepared()))
        val proofNames = round.proofs()
        val witness = round.witness()
        File(
            f.materialsFile,
            proofNames.first(),
        ).writeBytes(NightPolicy.encode(checkNotNull(NightPolicy.parse(proofNames.first())), witness))
        assertNull(f.open(f.prepared()))
        File(
            f.materialsFile,
            proofNames.last(),
        ).writeBytes(NightPolicy.encode(checkNotNull(NightPolicy.parse(proofNames.last())), witness))
        assertNotNull(f.open(f.prepared()))
        other.assertExactPageBytes(otherBytes)
        f.assertProtected()
    }

    @Test
    fun availabilityRefreshRetainsCompletePreparedReaderSnapshot() = raw { f ->
        val streams = f.streams()
        repeat(3) {
            val names = streams.refresh().keys // same API used by ReaderViewModel availability
            assertEquals(
                eu.kanade.tachiyomi.data.nightread.NightAvailability.READY,
                NightPages.availability(true, names),
            )
            assertArrayEquals(f.nightBytes, streams.open(0) { f.source.openInputStream() }.use { it.readBytes() })
        }
        f.assertProtected()
    }

    @Test
    fun concurrentRefreshPublishesOnlyCompleteSnapshotAndFailureRetainsPreviousSnapshot() = raw { f ->
        val index = NightPages.Index()
        index.refreshPrepared(f.chapter, listOf(PAGE to f.source))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        val gatedSources = object : AbstractList<Pair<String, UniFile>>() {
            override val size: Int get() = 1
            override fun get(index: Int): Pair<String, UniFile> {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return PAGE to f.source
            }
        }
        val worker = thread(name = "owned-night-refresh") {
            try {
                index.refreshPrepared(f.chapter, gatedSources)
            } catch (
                failure: Throwable,
            ) {
                workerFailure.set(failure)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(5) { assertNotNull(f.open(index.cachedPage(f.chapter, PAGE))) }
        } finally {
            release.countDown()
            worker.join(10_000)
        }
        assertFalse(worker.isAlive)
        workerFailure.get()?.let { throw AssertionError("owned refresh failed", it) }
        assertNotNull(f.open(index.cachedPage(f.chapter, PAGE)))
        val failedSources = object : AbstractList<Pair<String, UniFile>>() {
            override val size: Int get() = 1
            override fun get(index: Int): Pair<String, UniFile> = throw IOException("neutral-owned-refresh-failure")
        }
        expectIo { index.refreshPrepared(f.chapter, failedSources) }
        assertNotNull(f.open(index.cachedPage(f.chapter, PAGE)))
        f.assertProtected()
    }

    @Test
    fun realSafOptionalMaterialFailureRollsBackVerifiedBytesAndAllowsExplicitNightRetry() = saf { f, session ->
        val old = f.prepared()
        val before = f.materialFile.readBytes()
        val previousRevision = f.revision()
        session.failNextWriteDocumentId.set(session.documentId(f.materialFile))
        PageTranslator::class.java.getDeclaredMethod(
            "saveMaterialsMethod",
            UniFile::class.java,
            UniFile::class.java,
            String::class.java,
            PageMaterials::class.java,
            String::class.java,
        ).apply { isAccessible = true }.invoke(
            PageTranslator(context),
            f.chapter,
            f.materials,
            PAGE,
            PageMaterials(method = "old", regions = emptyList()),
            "new",
        )
        assertArrayEquals(before, f.materialFile.readBytes())
        assertTrue(f.revision().committed)
        assertNotEquals(previousRevision.id, f.revision().id)
        assertNull(f.open(old))
        f.publish()
        assertNotNull(f.open(f.prepared()))
        f.assertProtected()
    }

    @Test
    fun skippedColorRetirementReloadsDecodedPageWithoutSuccessfulChapterDone() = raw { f ->
        val other = f.addOtherPage()
        val otherBytes = other.exactPageBytes()
        val oldProofNames = f.proofFiles().map { it.name }.also { assertEquals(2, it.size) }
        val streams = f.streams()
        streams.open(0) { f.source.openInputStream() }.close()
        val page = ReaderPage(0)
        val before = f.revision()
        val witness = NightPolicy.snapshot(f.chapter, f.materials, PAGE)
        val retired = PageTranslator::class.java.getDeclaredMethod(
            "retireNightForColor",
            UniFile::class.java,
            UniFile::class.java,
            String::class.java,
            NightPolicy.Witness::class.java,
        ).apply {
            isAccessible = true
        }.invoke(PageTranslator(context), f.chapter, f.materials, PAGE, witness) as Boolean
        assertTrue(retired)
        assertNotEquals(before.id, f.revision().id)
        val change = NightSourceRevision.Change(
            NightSourceRevision.chapterKey(f.chapter),
            PAGE,
            NightSourceRevision.sequence,
        )
        streams.changedPages(change, listOf(page)).forEach { it.reload() }
        assertEquals(1, page.reloadFlow.value)
        assertArrayEquals(f.originalBytes, streams.open(0) { f.source.openInputStream() }.use { it.readBytes() })
        assertFalse(NightPages.hasAnyArtifact(f.materialsFile.listFiles().orEmpty().map { it.name }.toSet(), PAGE))
        oldProofNames.forEach { name -> assertFalse(File(f.materialsFile, name).exists()) }
        other.assertExactPageBytes(otherBytes)
        f.assertProtected()
    }

    @Test
    fun optionalBaselinePreparationFailureDoesNotPublishPendingOrTouchSource() = raw { f ->
        val before = f.revision()
        val revisionFile = File(f.materialsFile, NightSourceRevision.fileName(PAGE))
        val revisionBytes = revisionFile.readBytes()
        RandomAccessFile(f.materialFile, "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
        val source = f.sourceFile.readBytes()
        PageTranslator::class.java.getDeclaredMethod(
            "saveMaterialsMethod",
            UniFile::class.java,
            UniFile::class.java,
            String::class.java,
            PageMaterials::class.java,
            String::class.java,
        ).apply { isAccessible = true }.invoke(
            PageTranslator(context),
            f.chapter,
            f.materials,
            PAGE,
            PageMaterials(method = "old", regions = emptyList()),
            "new",
        )
        assertEquals(before, f.revision())
        assertArrayEquals(revisionBytes, revisionFile.readBytes())
        assertArrayEquals(source, f.sourceFile.readBytes())
        assertEquals(16L * 1024 * 1024 + 1, f.materialFile.length())
        f.assertProtected()
    }

    @Test
    fun realSafTreeAliasesReceiveSameSourceMutationEventAndReloadDecodedPage() = saf { f, session ->
        val authority = context.packageManager.getProviderInfo(
            ComponentName(context, SafAliasDocumentsProvider::class.java),
            0,
        ).authority
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "${session.id}|root-b")
        val alias =
            checkNotNull(
                UniFile.fromUri(
                    context,
                    DocumentsContract.buildDocumentUriUsingTree(tree, session.documentId(session.chapter)),
                ),
            )
        assertNotEquals(f.chapter.uri, alias.uri)
        assertEquals(NightSourceRevision.chapterKey(f.chapter), NightSourceRevision.chapterKey(alias))
        val streams = f.streams(alias)
        val aliasSource = checkNotNull(alias.findFile(PAGE))
        streams.open(0) { aliasSource.openInputStream() }.close()
        val page = ReaderPage(0)
        val change = runBlocking {
            val event = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) {
                    NightSourceRevision.changes.first {
                        it.chapterUri == NightSourceRevision.chapterKey(f.chapter) &&
                            it.page == PAGE
                    }
                }
            }
            NightSourceRevision.mutate(context, f.chapter, f.materials, PAGE) {
                f.sourceFile.writeBytes(f.originalBytes)
                assertArrayEquals(f.originalBytes, f.sourceFile.readBytes())
            }
            event.await()
        }
        streams.changedPages(change, listOf(page)).forEach { it.reload() }
        assertEquals(1, page.reloadFlow.value)
        assertArrayEquals(f.originalBytes, streams.open(0) { aliasSource.openInputStream() }.use { it.readBytes() })
        f.assertProtected()
    }

    @Test
    fun realSafRollbackPublishesFreshPendingBeforeRestoreWhenPriorCommitOutcomeWasUnknown() = saf { f, session ->
        val materialBefore = f.materialFile.readBytes()
        val translator = PageTranslator(context)
        val write = PageTranslator::class.java.getDeclaredMethod(
            "beginNightMaterialWrite",
            UniFile::class.java,
            UniFile::class.java,
            String::class.java,
        ).apply { isAccessible = true }.invoke(translator, f.chapter, f.materials, PAGE)
        val initialPending = f.revision()
        // Model a COMMITTED record which landed before a failed readback. The provider's
        // existing write gate then observes the REAL rollback boundary before target truncate.
        val landed = NightSourceRevision.Revision(NightSourceRevision.newId(), true, initialPending.capturedEpoch)
        File(f.materialsFile, NightSourceRevision.fileName(PAGE)).writeBytes(NightSourceRevision.encode(landed))
        f.materialFile.writeText("neutral-partial-material")
        val gate = session.gateNextWrite(f.materialFile)
        val workerFailure = AtomicReference<Throwable?>(null)
        val worker = thread(name = "owned-night-material-rollback") {
            try {
                write.javaClass.getDeclaredMethod("rollback").apply { isAccessible = true }.invoke(write)
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        try {
            assertTrue(gate.awaitEntered(5_000))
            val guarded = f.revision()
            assertFalse(guarded.committed)
            assertEquals(NightSourceRevision.Kind.SOURCE, guarded.kind)
            assertNotEquals(initialPending.id, guarded.id)
            assertNotEquals(landed.id, guarded.id)
        } finally {
            gate.release()
            worker.join(10_000)
        }
        assertFalse(worker.isAlive)
        workerFailure.get()?.let { throw AssertionError("owned rollback failed", it) }
        assertArrayEquals(materialBefore, f.materialFile.readBytes())
        assertTrue(f.revision().committed)
        assertNotEquals(landed.id, f.revision().id)
        f.assertProtected()
    }

    private inner class Fixture(
        val chapter: UniFile,
        val sourceFile: File,
        val materialsFile: File,
        private val pageName: String = PAGE,
    ) {
        val source = checkNotNull(chapter.findFile(pageName))
        val materials = checkNotNull(chapter.findFile(NightPages.DIR))
        val originalBytes = sourceFile.readBytes()
        private val originalFile = File(materialsFile, OriginalMaterial.exactName(pageName)).apply {
            writeBytes(originalBytes)
        }
        val materialFile = File(materialsFile, "${OriginalMaterial.base(pageName)}.json").apply { writeText("{}") }
        private val unrelated = File(materialsFile, "unrelated.keep").apply { writeText("neutral-owned-data") }
        val nightBytes = encoded(Color.BLUE)
        private val index = NightPages.Index()

        fun addCaptureHistory() {
            val mutation = NightSourceRevision.begin(context, chapter, materials, pageName)
            CapturedSourceStore(CapturedSourceUniFileIo(context, chapter, materials)).replace(
                pageName,
                originalBytes,
                setOf(originalFile.name),
                false,
                setComplete = {},
            )
            mutation.committed()
        }

        fun revision(): NightSourceRevision.Revision = NightSourceRevision.read(
            checkNotNull(materials.findFile(NightSourceRevision.fileName(pageName))),
        )

        fun proofFiles(): List<File> = materialsFile.listFiles().orEmpty().filter {
            NightPolicy.belongsTo(it.name, pageName)
        }

        fun publish() {
            NightSourceRevision.ensureCommitted(context, chapter, materials, pageName)
            val protected = NightPolicy.snapshot(chapter, materials, pageName)
            proofFiles().forEach { check(it.delete()) }
            val mutation = NightSourceRevision.beginPublication(context, chapter, materials, pageName, protected)
            NightPages.currentNames(pageName).forEach { File(materialsFile, it).writeBytes(nightBytes) }
            mutation.committed()
            val witness = NightPolicy.snapshot(chapter, materials, pageName)
            NightPolicy.names(pageName, NightSourceRevision.newId(), moreDistinct = true).forEach { name ->
                File(materialsFile, name).writeBytes(NightPolicy.encode(checkNotNull(NightPolicy.parse(name)), witness))
            }
            NightSourceRevision.changed(chapter, pageName)
        }

        fun prepared(): NightPages.Index.PageFiles? {
            index.refresh(chapter)
            return index.page(chapter, source, pageName)
        }

        fun open(entry: NightPages.Index.PageFiles?): ByteArray? = try {
            entry?.let {
                NightPolicy.openCurrent(it.files.keys, pageName, NightLevel.MORE, it.io)
            }?.second?.use { it.readBytes() }
        } catch (_: IOException) {
            null
        }

        fun streams(readerChapter: UniFile = chapter): NightPageStreams {
            val reader = ReaderPreferences(InMemoryPreferenceStore()).apply { nightReadMode.set(true) }
            val translation = TranslationPreferences(InMemoryPreferenceStore()).apply {
                nightReadEnabled.set(true)
                nightReadFillLevel.set(NightLevel.MORE.prefValue)
            }
            return NightPageStreams(reader, translation).also {
                it.chapterDir = readerChapter
                it.registerSource(0) {
                    if (readerChapter.uri == chapter.uri) source else readerChapter.findFile(pageName)
                }
                it.refresh()
            }
        }

        fun round(): Round {
            val translator = PageTranslator(context)
            val type = Class.forName("eu.kanade.tachiyomi.data.translation.PageTranslator\$NightRound")
            val instance = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(
                translator,
                chapter,
                materials,
                source,
                pageName,
                Bitmap.CompressFormat.PNG,
            )
            val token = PageTranslator::class.java.getDeclaredMethod(
                "sourceToken",
                UniFile::class.java,
                UniFile::class.java,
                String::class.java,
            )
                .apply { isAccessible = true }.invoke(translator, chapter, source, pageName)
            type.getDeclaredMethod("bindSource", token.javaClass, NightPolicy.Witness::class.java).apply {
                isAccessible =
                    true
            }
                .invoke(instance, token, NightPolicy.snapshot(chapter, materials, pageName))
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            try {
                NightLevel.entries.forEach { level ->
                    type.getDeclaredMethod("record", NightLevel::class.java, Boolean::class.javaPrimitiveType).apply {
                        isAccessible =
                            true
                    }
                        .invoke(instance, level, true)
                    type.getDeclaredMethod("write", NightLevel::class.java, Bitmap::class.java).apply {
                        isAccessible =
                            true
                    }
                        .invoke(instance, level, bitmap)
                }
            } finally {
                bitmap.recycle()
            }
            return Round(instance, type)
        }

        /** A second real, independently proven page in this same private RawFile chapter. */
        fun addOtherPage(): Fixture {
            check(chapter.uri.scheme == "file")
            val parent = checkNotNull(sourceFile.canonicalFile.parentFile)
            val name = "other.png"
            val otherSource = File(parent, name)
            check(otherSource.canonicalFile.parentFile == parent && !otherSource.exists())
            otherSource.writeBytes(encoded(Color.GREEN))
            return Fixture(chapter, otherSource, materialsFile, name).also { other ->
                other.publish()
                assertArrayEquals(other.nightBytes, other.open(other.prepared()))
            }
        }

        fun exactPageBytes(): Map<File, ByteArray> {
            val proofs = proofFiles()
            assertEquals(2, proofs.size)
            val names = NightPages.currentNames(pageName) + NightSourceRevision.fileName(pageName)
            val files = listOf(sourceFile, originalFile, materialFile) + proofs + names.map { File(materialsFile, it) }
            check(files.toSet().size == 8 && files.all { it.isFile })
            return files.associateWith { it.readBytes() }
        }

        fun assertExactPageBytes(expected: Map<File, ByteArray>) {
            val actual = exactPageBytes()
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (file, bytes) -> assertArrayEquals(bytes, actual.getValue(file)) }
            assertArrayEquals(nightBytes, open(prepared()))
            assertProtected()
        }

        fun assertProtected() {
            assertArrayEquals(originalBytes, originalFile.readBytes())
            assertEquals("neutral-owned-data", unrelated.readText())
        }
    }

    private class Round(private val instance: Any, private val type: Class<*>) {
        private fun call(
            name: String,
        ): Any? = type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance)
        fun commit(): Boolean = call("commit") as Boolean
        fun discard() {
            call("discard")
        }

        @Suppress("UNCHECKED_CAST")
        fun proofs(): List<String> = call("policyStamps") as List<String>
        fun witness(): NightPolicy.Witness = call("publishedWitness") as NightPolicy.Witness
    }

    private fun raw(block: (Fixture) -> Unit) {
        val cache = context.cacheDir.canonicalFile
        val root = File(cache, "night-policy-${UUID.randomUUID()}")
        check(root.parentFile == cache && root.mkdir())
        var success = false
        try {
            val chapter = File(root, "chapter").apply { check(mkdir()) }
            val source = File(chapter, PAGE).apply { writeBytes(encoded(Color.WHITE)) }
            val materials = File(chapter, NightPages.DIR).apply { check(mkdir()) }
            val fixture = Fixture(checkNotNull(UniFile.fromFile(chapter)), source, materials)
            fixture.publish()
            block(fixture)
            success = true
        } finally {
            check(root.canonicalFile.parentFile == cache && root.name.startsWith("night-policy-"))
            if (success) check(root.deleteRecursively()) // retain only this UUID-owned failed fixture
        }
    }

    private fun saf(block: (Fixture, SafAliasDocumentsProvider.Session) -> Unit) {
        assertEquals("app.yomitori.yakuyomi.audit", context.packageName)
        val session = SafAliasDocumentsProvider.createSession(context)
        var success = false
        try {
            session.page.writeBytes(encoded(Color.WHITE))
            val material = File(session.chapter, NightPages.DIR).apply { check(mkdir()) }
            val authority = context.packageManager.getProviderInfo(
                ComponentName(context, SafAliasDocumentsProvider::class.java),
                0,
            ).authority
            val tree = DocumentsContract.buildTreeDocumentUri(authority, "${session.id}|root-a")
            val chapterUri = DocumentsContract.buildDocumentUriUsingTree(tree, session.documentId(session.chapter))
            val fixture = Fixture(checkNotNull(UniFile.fromUri(context, chapterUri)), session.page, material)
            fixture.addCaptureHistory()
            fixture.publish()
            block(fixture, session)
            success = true
        } finally {
            session.close(retainOwnedFiles = !success)
        }
    }

    private fun expectIo(block: () -> Unit) {
        var failure: IOException? = null
        try {
            block()
        } catch (caught: IOException) {
            failure = caught
        }
        assertNotNull("Expected bounded/fail-closed source policy rejection", failure)
    }

    private class SimulatedProcessDeath : Error()

    companion object {
        private const val PAGE = "target.png"
        private fun encoded(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return try {
                ByteArrayOutputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
