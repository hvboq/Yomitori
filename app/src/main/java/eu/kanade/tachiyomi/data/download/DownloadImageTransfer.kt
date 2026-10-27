package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** A protocol-invalid response cannot be repaired by appending another response to the same prefix. */
internal class InvalidDownloadResponse(message: String) : IOException(message)

internal data class DownloadImageTransfer(val complete: Boolean, val needsFullResponse: Boolean)

/** The caller retains response ownership. Cancellation also closes it while a body read is blocked. */
internal suspend fun saveDownloadImageResponse(
    response: Response,
    file: UniFile,
    requestedOffset: Long,
): DownloadImageTransfer = coroutineScope {
    if (requestedOffset < 0) throw InvalidDownloadResponse("Cannot determine downloaded image length")
    val partial = response.code == 206
    var complete = true
    var unknownTotal = false
    val expectedBytes = if (partial) {
        val range = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
            ?: throw InvalidDownloadResponse("Partial image response has no valid Content-Range")
        val first = range.groupValues[1].toLongOrNull()
        val last = range.groupValues[2].toLongOrNull()
        val totalText = range.groupValues[3]
        val total = totalText.takeUnless { it == "*" }?.toLongOrNull()
        if (first == null || last == null || first != requestedOffset || last < first ||
            last == Long.MAX_VALUE || (totalText != "*" && (total == null || total <= last))
        ) {
            throw InvalidDownloadResponse("Partial image response does not match the stored prefix")
        }
        unknownTotal = total == null
        complete = total != null && last == total - 1
        (last - first + 1).also { length ->
            val declared = response.body.contentLength()
            if (declared >= 0 && declared != length) {
                throw InvalidDownloadResponse("Partial image response body length disagrees with its range")
            }
        }
    } else {
        response.body.contentLength().takeIf { it >= 0 }
    }
    val context = currentCoroutineContext()
    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            response.close()
        }
    }
    try {
        var received = 0L
        val counted = object : ForwardingSource(response.body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                context.ensureActive()
                val remaining = expectedBytes?.minus(received)
                val limit = if (remaining != null && remaining < Long.MAX_VALUE) {
                    minOf(byteCount, remaining + 1)
                } else {
                    byteCount
                }
                val count = super.read(sink, limit)
                if (count > 0) {
                    if (remaining != null && count > remaining) {
                        throw InvalidDownloadResponse("Image response exceeded its declared body length")
                    }
                    received = Math.addExact(received, count)
                }
                return count
            }
        }.buffer()
        counted.saveTo(file.openOutputStream(partial && requestedOffset > 0))
        context.ensureActive()
        if (received == 0L || (expectedBytes != null && received != expectedBytes)) {
            throw IOException("Image response ended before the declared body was received")
        }
        val expectedFileSize = Math.addExact(if (partial) requestedOffset else 0L, received)
        if (file.length() != expectedFileSize) throw IOException("Saved image length does not match the response")
        // '*' is legal but does not establish the representation's end. Retry without Range;
        // only a complete response (or a later range with a known final total) may be promoted.
        DownloadImageTransfer(complete, unknownTotal)
    } finally {
        withContext(NonCancellable) { closer.cancelAndJoin() }
    }
}

private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)", RegexOption.IGNORE_CASE)
