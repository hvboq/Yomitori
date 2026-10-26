package eu.kanade.tachiyomi.ui.reader.loader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OnlineSelectedPageCacheTest {
    @Test
    fun defaultCacheCapturesActualSixMegapixelPngLargerThanSixteenMiBWithoutChangingOriginal() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val width = 2500
            val height = 2400
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
            val source = try {
                val random = Random(0x53454C454354L)
                val row = IntArray(width)
                for (y in 0 until height) {
                    for (x in row.indices) row[x] = Color.BLACK or random.nextInt(1 shl 24)
                    bitmap.setPixels(row, 0, width, 0, y, width, 1)
                }
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
            assertTrue(
                "The actual encoded input must exceed the former 16 MiB restriction",
                source.size > 16 * 1024 * 1024,
            )
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            assertEquals("image/png", bounds.outMimeType)
            assertEquals(width, bounds.outWidth)
            assertEquals(height, bounds.outHeight)
            assertEquals(6_000_000L, bounds.outWidth.toLong() * bounds.outHeight)
            val cache = OnlinePageCache(fixture.context)
            cache.capture(OnlinePageCache.sha("neutral-six-megapixel-noise")) { ByteArrayInputStream(source) }.use {
                assertEquals(source.size.toLong(), it.entry.sourceBytes)
                assertEquals(sha(source), it.entry.sourceSha256)
                assertArrayEquals(source, File(it.entry.directory, "source.bin").readBytes())
                assertArrayEquals(source, it.entry.page.readBytes())
            }
        }
    }

    @Test
    fun exactOriginalAndSourceIdentitySurviveReuseAndChangedContentGetsAnotherAuthority() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val cache = OnlinePageCache(fixture.context, OnlineSelectedPageFixture.smallLimits())
            val original = OnlineSelectedPageFixture.original(0)
            val identity = OnlinePageCache.sha("neutral-source/chapter/page0")
            val first = cache.capture(identity) { ByteArrayInputStream(original) }
            val directory = first.entry.directory
            assertArrayEquals(original, first.entry.page.readBytes())
            assertArrayEquals(original, File(directory, "source.bin").readBytes())
            first.close()
            cache.capture(identity) { ByteArrayInputStream(original) }.use { reused ->
                assertEquals(directory, reused.entry.directory)
                assertArrayEquals(original, reused.entry.page.readBytes())
            }
            val changed = OnlineSelectedPageFixture.original(1)
            cache.capture(identity) { ByteArrayInputStream(changed) }.use { other ->
                assertNotEquals(directory, other.entry.directory)
                assertArrayEquals(changed, File(other.entry.directory, "source.bin").readBytes())
                assertArrayEquals(original, File(directory, "source.bin").readBytes())
            }
            val otherIdentity = OnlinePageCache.sha("another-neutral-source/same-body")
            cache.capture(otherIdentity) { ByteArrayInputStream(original) }.use { other ->
                assertNotEquals(directory, other.entry.directory)
            }
            // A changed immutable source must never silently turn the old display into an original.
            File(directory, "source.bin").writeBytes(changed)
            val before = snapshot(directory)
            val denied = runCatching { cache.capture(identity) { ByteArrayInputStream(original) } }
            assertTrue(denied.isFailure)
            assertEquals(before, snapshot(directory))
            assertArrayEquals(original, File(directory, "page.png").readBytes())
        }
    }

    @Test
    fun activeAndOpenStreamLeasesPreventEvictionAndReleasedBudgetCanRecover() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val cache = OnlinePageCache(fixture.context, OnlineSelectedPageFixture.smallLimits(entries = 1))
            val original = OnlineSelectedPageFixture.original(0)
            val first = cache.capture(OnlinePageCache.sha("lease-a")) { ByteArrayInputStream(original) }
            val entry = first.entry
            val before = snapshot(entry.directory)
            reject { cache.capture(OnlinePageCache.sha("lease-b")) { ByteArrayInputStream(original) }.close() }
            assertEquals(before, snapshot(entry.directory))
            first.close()
            cache.open(entry).use { stream ->
                reject { cache.capture(OnlinePageCache.sha("lease-b")) { ByteArrayInputStream(original) }.close() }
                assertArrayEquals(original, stream.readBytes())
                assertEquals(before, snapshot(entry.directory))
            }
            cache.capture(OnlinePageCache.sha("lease-b")) { ByteArrayInputStream(original) }.use { replacement ->
                assertFalse(entry.directory.exists())
                assertArrayEquals(original, replacement.entry.page.readBytes())
            }
        }
    }

    @Test
    fun pendingJournalRetainsSuccessfulTargetsButVerifiedCompletedJournalCanBeEvicted() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val cache = OnlinePageCache(fixture.context, OnlineSelectedPageFixture.smallLimits(entries = 1))
            val source = OnlineSelectedPageFixture.original(0)
            val held = cache.capture(OnlinePageCache.sha("journal-a")) { ByteArrayInputStream(source) }
            val entry = held.entry
            val pageName = entry.page.name
            val materials = File(entry.directory, ".yakuyomi").apply { check(mkdir()) }
            val original = File(materials, OriginalMaterial.exactName(pageName)).apply { writeBytes(source) }
            val descriptor = PartialPageStore.Original(original.name, sha(source), source.size.toLong())
            val store = PartialPageStore.forUniFile(
                fixture.context,
                entry.chapterDir,
                checkNotNull(com.hippo.unifile.UniFile.fromFile(materials)),
            )
            val rawMask = OnlineSelectedPageFixture.encoded(Color.WHITE)
            val pendingPayload = OcrProgressCodec.encode(OnlineSelectedPageFixture.progress(pending = true))
            val pending = store.commit(
                pageName,
                descriptor,
                OnlineSelectedPageFixture.WIDTH,
                OnlineSelectedPageFixture.HEIGHT,
                pendingPayload,
                OnlineSelectedPageFixture.translated(0),
                rawMask,
                baseRevision = null,
                complete = false,
            )
            held.close()
            val before = snapshot(entry.directory)
            reject { cache.capture(OnlinePageCache.sha("journal-b")) { ByteArrayInputStream(source) }.close() }
            assertEquals(before, snapshot(entry.directory))
            val restored = checkNotNull(store.load(pageName, recover = false))
            assertEquals(pending.revision, restored.revision)
            assertEquals(pendingPayload, restored.progressPayload)
            val progress = OcrProgressCodec.decode(restored.progressPayload, restored.width, restored.height)
            assertTrue(progress.hasPending)
            assertEquals("중립 0", progress.regions[0].translatedText)
            assertArrayEquals(source, original.readBytes())
            // A bare journal flag is insufficient: the storage API must also commit its
            // completion ledger and source/mask provenance before eviction is permitted.
            fixture.complete(
                OnlineTranslatedPageLoader.StoredPage(entry.chapterDir, entry.pageFile),
                1,
            )
            assertTrue(checkNotNull(store.load(pageName, recover = false)).complete)
            cache.capture(OnlinePageCache.sha("journal-b")) { ByteArrayInputStream(source) }.use {
                assertFalse(entry.directory.exists())
                assertArrayEquals(source, it.entry.page.readBytes())
            }
        }
    }

    @Test
    fun rejectedSourceAndPixelBoundsCloseStreamsAndHealthyCaptureStillWorks() = runBlocking {
        OnlineSelectedPageFixture.run { fixture ->
            val limits = OnlineSelectedPageFixture.smallLimits(entries = 2).copy(
                sourceBytes = 512,
                processingReserve = 1024,
                pagePixels = 1024,
            )
            val cache = OnlinePageCache(fixture.context, limits)
            val closed = AtomicInteger()
            fun stream(bytes: ByteArray) = object : ByteArrayInputStream(bytes) {
                override fun close() {
                    closed.incrementAndGet()
                    super.close()
                }
            }
            reject { cache.capture(OnlinePageCache.sha("too-many-bytes")) { stream(ByteArray(1024)) }.close() }
            reject {
                cache.capture(OnlinePageCache.sha("too-many-pixels")) {
                    stream(OnlineSelectedPageFixture.encoded(Color.WHITE, 64, 64))
                }.close()
            }
            assertEquals(2, closed.get())
            val directory = File(fixture.context.cacheDir, "online-reader-pages-v1")
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            val source = OnlineSelectedPageFixture.original(0)
            cache.capture(OnlinePageCache.sha("healthy-after-rejection")) { stream(source) }.use {
                assertArrayEquals(source, it.entry.page.readBytes())
                assertArrayEquals(source, File(it.entry.directory, "source.bin").readBytes())
            }
            assertEquals(3, closed.get())
        }
    }

    private suspend fun reject(block: suspend () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue("The bounded cache must refuse this operation", rejected)
    }

    private fun snapshot(directory: File): Map<String, String> = directory.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to sha(it.readBytes()) }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
