package mihon.translation

import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import mihon.testprovider.SafAliasDocumentsProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Observe the real cached-copy boundary; never closes or changes a borrowed numeric descriptor. */
internal class CachedDownloadDescriptorProbe(
    private val session: SafAliasDocumentsProvider.Session,
    private val cacheRoot: File,
    private val cacheFile: File,
    private val renameSource: File,
) : Closeable {
    private data class Identity(val device: Long, val inode: Long)
    private data class Observation(
        val matches: Int,
        val deletedLinks: Int,
        val gcBefore: Long?,
        val gcAfter: Long?,
    )

    private val identity = Os.stat(cacheFile.path).let { Identity(it.st_dev, it.st_ino) }
    private lateinit var ownKernelLink: String
    private val callbacks = AtomicInteger()
    private val observation = AtomicReference<Observation?>(null)
    private val observationFailure = AtomicReference<Throwable?>(null)
    private var registration: SafAliasDocumentsProvider.RenameObservation? = null
    private var closed = false
    private var verified = false

    fun prepare() {
        check(cacheRoot.canonicalFile == cacheRoot.absoluteFile)
        check(cacheFile.canonicalFile == cacheFile.absoluteFile && cacheFile.parentFile == cacheRoot)
        check(cacheFile.isFile && cacheFile.length() > 0)
        // Capture the actual kernel spelling of this own inode, including Android bind aliases.
        // Both the deliberate input stream and its duplicate close before the initial-zero check.
        ownKernelLink = cacheFile.inputStream().use { input ->
            ParcelFileDescriptor.dup(input.fd).use {
                val stat = Os.fstat(it.fileDescriptor)
                check(stat.st_dev == identity.device && stat.st_ino == identity.inode)
                Os.readlink("/proc/self/fd/${it.fd}").also { link ->
                    check(link.isNotEmpty() && !link.endsWith(" (deleted)"))
                }
            }
        }
        // Check proc access and the inode matcher using only our own deliberately held stream.
        assertEquals("CACHED_DOWNLOAD_FD_INITIAL_ZERO", 0, matchingDescriptors().first)
        cacheFile.inputStream().use {
            assertEquals("CACHED_DOWNLOAD_FD_HELD_SENTINEL_ONE", 1, matchingDescriptors().first)
        }
        assertEquals("CACHED_DOWNLOAD_FD_SENTINEL_CLOSED", 0, matchingDescriptors().first)
        registration = session.observeNextRename(renameSource, "001.png") {
            try {
                check(callbacks.incrementAndGet() == 1)
                // This synchronous provider callback runs after findImageType returns, before the
                // actual .tmp rename and before Downloader deletes the cached file. Never wait here.
                val current = Os.stat(cacheFile.path)
                check(current.st_dev == identity.device && current.st_ino == identity.inode)
                val gcBefore = gcCount()
                val (matches, deleted) = matchingDescriptors()
                check(observation.compareAndSet(null, Observation(matches, deleted, gcBefore, gcCount())))
            } catch (failure: Throwable) {
                // Preserve the actual publication path. Assert this failure on the test thread later.
                observationFailure.compareAndSet(null, failure)
            }
        }
    }

    fun verifyClosedBeforeRename() {
        val seen = observation.get()
        val failure = observationFailure.get()
        val diagnostic = JSONObject()
            .put("stage", "synchronousBeforeSafRename")
            .put("selectedRenameCallbacks", callbacks.get())
            .put("observationCaptured", seen != null)
            .put("observationFailureClass", failure?.javaClass?.simpleName ?: JSONObject.NULL)
            .put("matchingOwnInodeDescriptors", seen?.matches ?: JSONObject.NULL)
            .put("deletedOwnInodeLinksAtObservation", seen?.deletedLinks ?: JSONObject.NULL)
            .put("gcCountBeforeSnapshot", seen?.gcBefore ?: JSONObject.NULL)
            .put("gcCountAfterSnapshot", seen?.gcAfter ?: JSONObject.NULL)
            .put("gcCountersAreNotCollectorBarriers", true)
            .put("baselineZeroDoesNotDisproveMissingClose", true)
            .put("seededCacheFileDeletedByDownloader", !cacheFile.exists())
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("cachedDownloadDescriptorObservation", diagnostic.toString()) },
        )
        check(failure == null) { "CACHED_DOWNLOAD_FD_OBSERVATION_FAILED" }
        assertEquals("CACHED_DOWNLOAD_FD_SELECTED_RENAME_ONCE", 1, callbacks.get())
        checkNotNull(seen) { "CACHED_DOWNLOAD_FD_OBSERVATION_MISSING" }
        assertFalse("CACHED_DOWNLOAD_FD_REAL_CACHE_CONSUMED", cacheFile.exists())
        assertEquals("CACHED_DOWNLOAD_FD_HEADER_STREAM_CLOSED_BEFORE_RENAME", 0, seen.matches)
        verified = true
    }

    fun evidence(): JSONObject {
        check(verified && closed)
        return JSONObject()
            .put("cacheSeededThroughActualChapterCachePutImage", true)
            .put("httpPageLoaderOrPublicReaderFillInvoked", false)
            .put("selectedRenameCallbacks", callbacks.get())
            .put("matchingOwnInodeDescriptorsBeforeRename", checkNotNull(observation.get()).matches)
            .put("cacheInodeMatchedRatherThanWholeProcessFdCount", true)
            .put("procMatcherHeldStreamAndCloseControlVerified", true)
            .put("cachedFileConsumedByActualDownloader", true)
            .put("systemGcOrRawDescriptorCloseUsed", false)
            .put("strictAlwaysRedBaselineGuaranteed", false)
    }

    private fun matchingDescriptors(): Pair<Int, Int> {
        // Snapshot names once. Never count temporary descriptors created by this observation.
        val names = checkNotNull(File("/proc/self/fd").list())
        check(names.size <= 4096 && names.all { it.toIntOrNull() != null })
        var matches = 0
        var deletedLinks = 0
        for (name in names) {
            val link = try {
                Os.readlink("/proc/self/fd/$name")
            } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.ENOENT || failure.errno == OsConstants.EBADF) continue
                throw failure
            }
            // Never duplicate or fstat unrelated process descriptors with OEM-restricted metadata.
            if (link != ownKernelLink) continue
            val duplicate = try {
                ParcelFileDescriptor.fromFd(name.toInt())
            } catch (failure: IOException) {
                // A listed original can close before dup. All other errors, including EACCES, fail.
                val errno = (failure.cause as? ErrnoException)?.errno
                if (errno == OsConstants.ENOENT || errno == OsConstants.EBADF) continue
                throw failure
            }
            duplicate.use {
                // The original can close and its number be reused between readlink and dup.
                // Recheck the pinned target before fstat so that race never broadens our scope.
                val pinnedLink = Os.readlink("/proc/self/fd/${it.fd}")
                if (pinnedLink != ownKernelLink) return@use
                val stat = Os.fstat(it.fileDescriptor)
                if (stat.st_dev == identity.device && stat.st_ino == identity.inode) {
                    matches++
                    if (pinnedLink.endsWith(" (deleted)")) deletedLinks++
                }
            }
        }
        return matches to deletedLinks
    }

    override fun close() {
        check(!closed)
        registration?.let { session.clearRenameObservation(it) }
        closed = true
    }

    private fun gcCount(): Long? = try {
        Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
    } catch (_: RuntimeException) {
        null
    }
}
