package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import kotlinx.coroutines.CancellationException
import li.joye.yakuyomi.engine.TextLine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Test-only exact candidate comparison. Raw text, salt and expected text hashes are never exported.
 * Successful original ROI PNGs deliberately remain for host inspection and exact-file cleanup; only a
 * failed partial write is removed here. A new UUID directory is mandatory for every helper instance.
 */
internal class OcrCandidateGroundTruth private constructor(
    private val cache: File,
    private val directory: File,
    private val salt: ByteArray,
    private val bindings: Map<Int, Binding>,
) {
    private val directoryIdentity = Os.lstat(directory.path)
    private val validated = mutableSetOf<Int>()
    private val recorded = mutableSetOf<Int>()
    private val ownedFiles = mutableSetOf<File>()
    private var partialCleanupFailed = false

    val retainedImageCount: Int
        get() = guarded(6) { ownedFiles.count { Files.exists(it.toPath(), NOFOLLOW_LINKS) } }

    fun validatePage(pageNumber: Int, encodedSha256: String, width: Int, height: Int) = guarded(2) {
        val binding = checkNotNull(bindings[pageNumber])
        check(encodedSha256 == binding.encodedSha256 && width == binding.width && height == binding.height)
        validated += pageNumber
        Unit
    }

    fun record(
        pageNumber: Int,
        page: Bitmap,
        baseline: List<TextLine>,
        candidate: List<TextLine>,
        selectedIndices: List<Int>,
        row: JSONObject,
    ) = guarded(3) {
        val binding = checkNotNull(bindings[pageNumber])
        check(pageNumber in validated && pageNumber !in recorded && !row.has("groundTruth"))
        check(!page.isRecycled && page.width == binding.width && page.height == binding.height)
        check(selectedIndices.isNotEmpty() && selectedIndices.distinct().size == selectedIndices.size)
        check(selectedIndices.all { it in baseline.indices } && candidate.size == selectedIndices.size)
        val position = selectedIndices.indexOf(binding.lineIndex)
        check(position >= 0)
        check(quadBits(baseline[binding.lineIndex]) == binding.quadBits)
        selectedIndices.forEachIndexed { index, original ->
            check(quadBits(candidate[index]) == quadBits(baseline[original]))
        }
        val text = candidate[position].text
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val actual = try {
            MessageDigest.getInstance("SHA-256").apply { update(salt) }.digest(textBytes)
        } finally {
            textBytes.fill(0)
        }
        val matches = try {
            JSONArray(binding.expectedHashes.map { MessageDigest.isEqual(actual, it) })
        } finally {
            actual.fill(0)
        }
        val groundTruth = JSONObject().put("expectedMatches", matches).put("candidateLength", text.length)
            .put("provided", true).put("encodedImageAndDimensionsMatched", true)
            .put("orderedQuadFloatBitsMatched", true).put("comparison", "salted-raw-utf8-exact")
        row.put("groundTruth", groundTruth)
        groundTruth.put("roiArtifact", saveRoi(pageNumber, page, baseline[binding.lineIndex]))
        recorded += pageNumber
    }

    fun addSummary(report: JSONObject) = guarded(4) {
        report.put(
            "groundTruthSummary",
            JSONObject().put("retainedImageCount", retainedImageCount)
                .put("hostCleanupRequired", Files.exists(directory.toPath(), NOFOLLOW_LINKS))
                .put("actualTextExported", false).put("partialCleanupFailed", partialCleanupFailed)
                .put("validatedPageCount", validated.size).put("recordedPageCount", recorded.size)
                .put(
                    "allPagesValidated",
                    validated == bindings.keys,
                ).put("allPagesRecorded", recorded == bindings.keys),
        )
        Unit
    }

    private fun saveRoi(pageNumber: Int, page: Bitmap, line: TextLine): JSONObject = guarded(5) {
        verifyDirectory()
        val left = floor(line.quad.minOf { it.x }).toInt()
        val top = floor(line.quad.minOf { it.y }).toInt()
        val right = ceil(line.quad.maxOf { it.x }).toInt()
        val bottom = ceil(line.quad.maxOf { it.y }).toInt()
        val width = right - left
        val height = bottom - top
        check(left >= 0 && top >= 0 && right <= page.width && bottom <= page.height)
        check(width in 1..512 && height in 1..128 && width.toLong() * height <= 65_536)
        val file = File(directory, "page$pageNumber.png")
        check(file.parentFile == directory && file.canonicalFile == file.absoluteFile)
        check(!Files.exists(file.toPath(), NOFOLLOW_LINKS))
        val encoded = BoundedPng()
        val crop = Bitmap.createBitmap(page, left, top, width, height)
        val bytes = try {
            check(crop.compress(Bitmap.CompressFormat.PNG, 100, encoded))
            encoded.toByteArray().also { check(it.isNotEmpty() && it.size <= MAX_PNG_BYTES) }
        } finally {
            encoded.clear()
            if (crop !== page) crop.recycle()
        }
        try {
            val hash = sha256(bytes)
            verifyDirectory()
            val descriptor = Os.open(
                file.path,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
                0x180,
            )
            ownedFiles += file
            var identity: StructStat? = null
            var complete = false
            try {
                val createdIdentity = Os.fstat(descriptor)
                identity = createdIdentity
                // Android's FileOutputStream(FileDescriptor) borrows the descriptor; this helper owns it.
                val output = FileOutputStream(descriptor)
                output.use {
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                verifyDirectory()
                val saved = Os.lstat(file.path)
                check(saved.st_dev == createdIdentity.st_dev && saved.st_ino == createdIdentity.st_ino)
                check(OsConstants.S_ISREG(saved.st_mode) && file.length() == bytes.size.toLong())
                val receipt = JSONObject().put("relativePath", "cache/${directory.name}/${file.name}")
                    .put("bytes", bytes.size).put("sha256", hash)
                    .put("bounds", JSONArray(listOf(left, top, right, bottom)))
                complete = true
                return@guarded receipt
            } finally {
                try {
                    Os.close(descriptor)
                } finally {
                    if (!complete) {
                        try {
                            verifyDirectory()
                            val created = checkNotNull(identity)
                            val partial = Os.lstat(file.path)
                            check(partial.st_dev == created.st_dev && partial.st_ino == created.st_ino)
                            check(OsConstants.S_ISREG(partial.st_mode))
                            Files.delete(file.toPath())
                            ownedFiles -= file
                        } catch (error: Throwable) {
                            partialCleanupFailed = true
                            if (error is VirtualMachineError || error is ThreadDeath) throw error
                            if (error is CancellationException) {
                                throw CancellationException("Ground-truth diagnostic cancelled")
                            }
                        }
                    }
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun verifyDirectory() {
        check(cache.isDirectory && cache.canonicalFile == cache.absoluteFile)
        check(directory.parentFile == cache && directory.canonicalFile == directory.absoluteFile)
        check(!Files.isSymbolicLink(directory.toPath()) && Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        val current = Os.lstat(directory.path)
        check(current.st_dev == directoryIdentity.st_dev && current.st_ino == directoryIdentity.st_ino)
    }

    private class Binding(
        val encodedSha256: String,
        val width: Int,
        val height: Int,
        val lineIndex: Int,
        val quadBits: List<Int>,
        val expectedHashes: List<ByteArray>,
    )

    private class BoundedPng : ByteArrayOutputStream() {
        override fun write(value: Int) {
            check(count < MAX_PNG_BYTES)
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length >= 0 && count.toLong() + length <= MAX_PNG_BYTES)
            super.write(bytes, offset, length)
        }

        fun clear() {
            buf.fill(0)
            reset()
        }
    }

    companion object {
        private const val MAX_PNG_BYTES = 128 * 1024
        private val HEX = Regex("[0-9a-f]{64}")

        fun parse(context: Context, arguments: Bundle): OcrCandidateGroundTruth? {
            if (arguments.getString("liveHitomiOcrGroundTruthDiagnostic") != "true") return null
            return guarded(1) {
                check(context.packageName == "app.yomitori.yakuyomi.audit")
                val runId = checkNotNull(arguments.getString("diagnosticRunId"))
                check(UUID.fromString(runId).toString() == runId)
                val raw = checkNotNull(arguments.getString("groundTruthSpec"))
                check(raw.length <= 16 * 1024 && raw.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
                val spec = JSONObject(raw)
                check(spec.keys().asSequence().toSet() == setOf("salt", "pages"))
                val salt = decodeHash(spec.getString("salt"))
                val pages = spec.getJSONArray("pages")
                check(pages.length() == 2)
                val bindings = (0 until pages.length()).associate { index ->
                    val page = pages.getJSONObject(index)
                    check(
                        page.keys().asSequence().toSet() == setOf(
                            "page",
                            "encodedSha256",
                            "width",
                            "height",
                            "lineIndex",
                            "quad",
                            "expectedTextSha256",
                        ),
                    )
                    val number = integer(page, "page")
                    check(number == 2 || number == 3)
                    val encodedHash = page.getString("encodedSha256").also { check(it.matches(HEX)) }
                    val width = integer(page, "width").also { check(it in 1..100_000) }
                    val height = integer(page, "height").also { check(it in 1..100_000) }
                    val lineIndex = integer(page, "lineIndex").also { check(it in 0..1023) }
                    val quad = page.getJSONArray("quad")
                    check(quad.length() == 4)
                    val bits = (0 until 4).flatMap { point ->
                        val pair = quad.getJSONArray(point)
                        check(pair.length() == 2)
                        (0 until 2).map { axis ->
                            val value = pair.get(axis)
                            check(value is Number)
                            val coordinate = value.toDouble()
                            val single = coordinate.toFloat()
                            check(coordinate.isFinite() && single.toDouble() == coordinate)
                            check(single >= 0f && single <= if (axis == 0) width.toFloat() else height.toFloat())
                            single.toRawBits()
                        }
                    }
                    val hashes = page.getJSONArray("expectedTextSha256")
                    check(hashes.length() in 1..4)
                    val expected = (0 until hashes.length()).map { hashes.getString(it) }
                    check(expected.distinct().size == expected.size)
                    number to Binding(encodedHash, width, height, lineIndex, bits, expected.map(::decodeHash))
                }
                check(bindings.keys == setOf(2, 3))
                val cache = context.cacheDir.canonicalFile
                check(!Files.isSymbolicLink(context.cacheDir.toPath()) && cache.isDirectory)
                val directory = File(cache, "ocr-groundtruth-$runId")
                check(directory.canonicalFile == directory.absoluteFile && directory.parentFile == cache)
                check(!Files.exists(directory.toPath(), NOFOLLOW_LINKS))
                Files.createDirectory(directory.toPath())
                OcrCandidateGroundTruth(cache, directory, salt, bindings).also { it.verifyDirectory() }
            }
        }

        private fun integer(json: JSONObject, key: String): Int {
            val value = json.get(key)
            check(value is Number)
            val number = value.toDouble()
            check(number.isFinite() && number == number.toInt().toDouble())
            return number.toInt()
        }

        private fun quadBits(line: TextLine): List<Int> {
            check(line.quad.size == 4 && line.quad.all { it.x.isFinite() && it.y.isFinite() })
            return line.quad.flatMap { listOf(it.x.toRawBits(), it.y.toRawBits()) }
        }

        private fun decodeHash(value: String): ByteArray {
            check(value.matches(HEX))
            return ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

        private inline fun <T> guarded(stage: Int, block: () -> T): T = try {
            block()
        } catch (error: Throwable) {
            when (error) {
                is VirtualMachineError, is ThreadDeath -> throw error
                is CancellationException -> throw CancellationException("Ground-truth diagnostic cancelled")
                else -> throw IllegalStateException("Ground-truth diagnostic stage $stage failed")
            }
        }
    }
}
