package eu.kanade.tachiyomi.data.translation

import android.content.Context
import com.hippo.unifile.UniFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Exact-name, bounded and truncating storage for the page-lock-owned capture transaction. */
internal class CapturedSourceUniFileIo(
    private val context: Context,
    private val chapter: UniFile,
    private val materials: UniFile,
) : CapturedSourceStore.Io {
    override fun materialNames(): Set<String> = materials.listFiles()?.map { file ->
        file.name ?: throw IOException("Capture material has no name")
    }?.toSet() ?: throw IOException("Cannot list capture materials")

    override fun readMaterial(name: String, maxBytes: Int): ByteArray? = read(materials, name, maxBytes)
    override fun readPage(pageName: String, maxBytes: Int): ByteArray? = read(chapter, pageName, maxBytes)

    override fun createMaterial(name: String, bytes: ByteArray): Boolean {
        requireName(name)
        if (materials.findFile(name) != null) return false
        return materials.createFile(name)?.let { write(materials, it, name, bytes) } ?: false
    }

    override fun replaceMaterial(name: String, bytes: ByteArray): Boolean {
        requireName(name)
        val file = materials.findFile(name) ?: materials.createFile(name) ?: return false
        return write(materials, file, name, bytes)
    }

    override fun deleteMaterial(name: String): Boolean {
        requireName(name)
        val file = materials.findFile(name) ?: return true
        exactFile(materials, file, name)
        return file.delete() && materials.findFile(name) == null
    }

    override fun replacePage(pageName: String, bytes: ByteArray): Boolean {
        requireName(pageName)
        val file = chapter.findFile(pageName) ?: return false
        return write(chapter, file, pageName, bytes)
    }

    private fun read(directory: UniFile, name: String, maxBytes: Int): ByteArray? {
        requireName(name)
        val file = directory.findFile(name) ?: return null
        exactFile(directory, file, name)
        if (maxBytes < 0 || file.length() > maxBytes) throw IOException("Capture file exceeds size limit")
        val input = context.contentResolver.openInputStream(file.uri) ?: throw IOException("Cannot read capture file")
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maxBytes) throw IOException("Capture file exceeds size limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun write(directory: UniFile, file: UniFile, name: String, bytes: ByteArray): Boolean {
        exactFile(directory, file, name)
        val output = if (file.uri.scheme == "file") {
            file.openOutputStream()
        } else {
            context.contentResolver.openOutputStream(file.uri, "wt")
        } ?: return false
        output.use { it.write(bytes) }
        return file.length() == bytes.size.toLong()
    }

    private fun exactFile(directory: UniFile, file: UniFile, name: String) {
        if (!file.isFile || file.name != name) throw IOException("Capture file is not an exact named file")
        if (directory.uri.scheme == "file" && file.uri.scheme == "file") {
            val parent = File(requireNotNull(directory.uri.path)).canonicalFile
            if (File(requireNotNull(file.uri.path)).canonicalFile != File(parent, name)) {
                throw IOException("Capture file resolves outside its exact storage entry")
            }
        }
    }

    private fun requireName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && name.toByteArray().size <= 1024)
        require(name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 })
    }
}
