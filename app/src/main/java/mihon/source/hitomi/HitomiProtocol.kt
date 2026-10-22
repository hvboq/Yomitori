// Adapted from qwopqwop200/Aidoku native Hitomi adapter (821a612f).
// Copyright 2025 Aidoku community source contributors. SPDX-License-Identifier: MIT
package mihon.source.hitomi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure parsers: remote JavaScript is treated as data and is never evaluated. */
internal object HitomiProtocol {
    const val MAX_BYTES = 100_000_000
    const val PAGE_SIZE = 25

    data class Routing(val path: String, val cases: Set<Int>, val matched: Int, val fallback: Int) {
        fun imageUrl(hash: String, extension: String): String {
            require(extension in setOf("avif", "webp", "jpg", "jpeg", "png", "gif"))
            val number = imageId(hash)
            val server = (if (number in cases) matched else fallback) + 1
            val prefix = if (extension == "webp") "w" else "a"
            return "https://$prefix$server.gold-usergeneratedcontent.net/$path/$number/$hash.$extension"
        }

        fun thumbnailUrl(hash: String, extension: String = "avif", size: String = "big"): String {
            require(extension in setOf("avif", "webp") && size in setOf("big", "small"))
            val number = imageId(hash)
            val server = if (number in cases) matched else fallback
            require(server in 0..1) { "Invalid thumbnail server" }
            val host = if (server == 0) "atn" else "btn"
            val suffix = hash.takeLast(3)
            return "https://$host.gold-usergeneratedcontent.net/${extension}${size}tn/" +
                "${suffix[2]}/${suffix.take(2)}/$hash.$extension"
        }
    }

    fun imageId(hash: String): Int {
        require(hash.matches(Regex("[a-fA-F0-9]{3,128}"))) { "Invalid image hash" }
        val suffix = hash.takeLast(3)
        return "${suffix[2]}${suffix[0]}${suffix[1]}".toInt(16)
    }

    fun routing(script: String): Routing {
        require(script.length <= 1_000_000) { "Routing response too large" }
        val path = requireNotNull(Regex("b:\\s*'([^']+)'").find(script)?.groupValues?.get(1))
        require(path.length <= 256 && path.matches(Regex("[A-Za-z0-9_/-]+"))) { "Invalid CDN path" }
        require(path.trim('/').isNotEmpty())
        val fallback = Regex("var o = ([0-9]+)").find(script)?.groupValues?.get(1)?.toInt() ?: 0
        val matched = Regex("(?<!var )o = ([0-9]+)\\s*; break").find(script)?.groupValues?.get(1)?.toInt() ?: 1
        require(fallback in 0..1 && matched in 0..1) { "Invalid CDN server" }
        val cases = Regex("case\\s+([0-9]+)\\s*:").findAll(script).map { it.groupValues[1].toInt() }.toSet()
        require(cases.all { it in 0..4095 }) { "Invalid CDN hash bucket" }
        return Routing(path.trim('/'), cases, matched, fallback)
    }

    fun galleryJson(script: String): String {
        require(script.length <= 20_000_000)
        val marker = Regex("\\bgalleryinfo\\s*=\\s*").find(script) ?: error("Invalid gallery response")
        val payload = script.substring(marker.range.last + 1).trim().trimEnd(';').trim()
        require(payload.startsWith('{') && payload.endsWith('}')) { "Invalid gallery JSON" }
        return payload
    }

    fun ids(bytes: ByteArray): List<Long> {
        require(bytes.size <= MAX_BYTES && bytes.size % 4 == 0) { "Invalid gallery index" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return List(bytes.size / 4) { buffer.int.toLong() and 0xffffffffL }
    }

    fun searchIds(bytes: ByteArray): List<Long> {
        require(bytes.size >= 4)
        val count = ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
        require(count * 4 + 4 == bytes.size.toLong()) { "Invalid search result length" }
        return ids(bytes.copyOfRange(4, bytes.size))
    }

    data class Value(val offset: Long, val length: Int)
    data class Node(val keys: List<ByteArray>, val values: List<Value>, val children: List<Long>)

    fun node(bytes: ByteArray): Node {
        require(bytes.size <= 464)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.int.also { require(it in 0..16) }
        val keys = List(count) {
            val length = buffer.int.also { require(it in 1..32) }
            ByteArray(length).also(buffer::get)
        }
        require(buffer.int == count)
        val values = List(count) {
            Value(buffer.long.also { require(it >= 0) }, buffer.int.also { require(it in 1..MAX_BYTES) })
        }
        val children = List(17) { buffer.long.also { require(it >= 0) } }
        return Node(keys, values, children)
    }

    fun compareKey(left: ByteArray, right: ByteArray): Int {
        for (i in 0 until minOf(left.size, right.size)) {
            val difference = (left[i].toInt() and 255) - (right[i].toInt() and 255)
            if (difference != 0) return difference
        }
        return 0 // The published index uses prefix comparison.
    }

    /** Validate 206 offsets; mirrors returning 200 must be sliced at the requested offset. */
    fun range(bytes: ByteArray, code: Int, header: String?, first: Long, last: Long, allowEnd: Boolean): ByteArray {
        require(first >= 0 && last >= first && last - first < MAX_BYTES && bytes.size <= MAX_BYTES)
        if (code == 416 && allowEnd) {
            val total = header?.removePrefix("bytes */")?.toLongOrNull()
            require(header?.startsWith("bytes */") == true && total != null && first >= total)
            return byteArrayOf()
        }
        if (code == 200) {
            if (first >= bytes.size) {
                require(allowEnd)
                return byteArrayOf()
            }
            val result = bytes.copyOfRange(first.toInt(), minOf(last + 1, bytes.size.toLong()).toInt())
            require(allowEnd || result.size.toLong() == last - first + 1)
            return result
        }
        require(code == 206) { "Index request failed (HTTP $code)" }
        val match = requireNotNull(Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(header.orEmpty()))
        val returnedFirst = match.groupValues[1].toLong()
        val returnedLast = match.groupValues[2].toLong()
        val total = match.groupValues[3].toLongOrNull()
        require(
            returnedFirst == first && returnedLast in first..last && returnedLast - first + 1 == bytes.size.toLong(),
        )
        require(total == null || returnedLast < total)
        require(returnedLast == last || (allowEnd && total != null && returnedLast == total - 1))
        return bytes
    }
}
