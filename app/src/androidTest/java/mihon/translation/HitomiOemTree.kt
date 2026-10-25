package mihon.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** External-storage-provider-only ownership contract. Never use UniFile for absence/deletion proof. */
internal class HitomiOemTree(
    private val context: Context,
    val uuid: UUID,
    private val marker: File,
) {
    private val resolver = context.contentResolver
    private val baseline = grants()
    private val rootName = "hitomi-downloader-$uuid"
    private val rootId = "primary:Download/$rootName"
    private val expectedTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootId)
    private val contract = linkedMapOf("" to true)
    private var accepted = false
    private var acquired = false
    private var mayHaveWrites = false
    var released = false
        private set
    var strictRootEmptyBeforeExactGrantRelease = false
        private set
    var priorPersistedGrantsEqualAfterRelease = false
        private set
    lateinit var tree: Uri
        private set
    lateinit var root: UniFile
        private set

    fun accept(uri: Uri?, flags: Int) {
        check(!accepted && uri != null) { "OEM_PICKER_CANCELLED_OR_DUPLICATE" }
        check(uri == expectedTree && uri.scheme == "content" && uri.encodedAuthority == AUTHORITY) {
            "OEM_EXACT_TREE_REQUIRED"
        }
        check(uri.query == null && uri.fragment == null && uri.pathSegments == listOf("tree", rootId)) {
            "OEM_TREE_SHAPE_MISMATCH"
        }
        check(baseline.none { it.uri == uri.toString() }) { "OEM_GRANT_ALREADY_EXISTED" }
        check(flags and RW == RW && flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            "OEM_RETURNED_PERSISTABLE_RW_FLAGS_REQUIRED"
        }
        tree = uri
        val observed = row(rootId)
        check(observed.name == rootName && observed.directory) { "OEM_ROOT_IDENTITY_MISMATCH" }
        check(observed.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
            "OEM_ROOT_CREATE_UNSUPPORTED"
        }
        check(children(rootId).isEmpty()) { "OEM_NEW_ROOT_MUST_BE_EMPTY" }
        root = checkNotNull(UniFile.fromUri(context, tree)) { "OEM_UNIFILE_ROOT_UNAVAILABLE" }
        check(root.uri.authority == AUTHORITY && DocumentsContract.isTreeUri(root.uri)) {
            "OEM_UNIFILE_TREE_MISMATCH"
        }
        check(DocumentsContract.getTreeDocumentId(root.uri) == rootId) { "OEM_UNIFILE_TREE_ID_MISMATCH" }
        check(DocumentsContract.getDocumentId(root.uri) == rootId) { "OEM_UNIFILE_DOCUMENT_ID_MISMATCH" }
        accepted = true
        persist("acceptedEmptyRoot", emptyList())
        resolver.takePersistableUriPermission(tree, RW)
        acquired = true
        val after = grants()
        check(after.filterNot { it.uri == tree.toString() }.toSet() == baseline.toSet()) {
            "OEM_PREEXISTING_GRANTS_CHANGED"
        }
        check(after.single { it.uri == tree.toString() }.let { it.read && it.write }) {
            "OEM_EXACT_PERSISTED_RW_GRANT_REQUIRED"
        }
        persist("persistedGrant", emptyList())
    }

    fun reserveProducts(source: String, manga: String, chapter: String) {
        check(accepted && acquired && !mayHaveWrites)
        for (name in listOf(source, manga, chapter)) {
            check(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", ".."))
        }
        val mangaPath = "downloads/$source/$manga"
        contract["downloads"] = true
        contract["downloads/.nomedia"] = false
        contract["downloads/$source"] = true
        contract[mangaPath] = true
        for (directory in listOf("$mangaPath/$chapter", "$mangaPath/${chapter}_tmp")) {
            contract[directory] = true
            for (name in listOf(".nomedia", "ComicInfo.xml")) contract["$directory/$name"] = false
            for (index in 1..2) {
                for (extension in listOf("tmp", "avif", "webp", "jpg", "jpeg", "png", "gif")) {
                    contract["$directory/00$index.$extension"] = false
                }
            }
        }
        persist("productNamesReservedBeforeWrite", emptyList())
        mayHaveWrites = true
    }

    fun checkpoint(): List<Entry> = snapshot().also { persist("observedOwnedDocuments", it) }

    /** First validate the entire bounded tree. Unknown documents cause zero deletion. */
    fun cleanup() {
        if (!accepted) {
            check(grants().toSet() == baseline.toSet()) { "OEM_GRANTS_CHANGED_BEFORE_ACCEPT" }
            priorPersistedGrantsEqualAfterRelease = true
            released = true
            return
        }
        val ledger = snapshot()
        persist("cleanupLedgerSealed", ledger)
        // The contract and exact IDs authorize these documents; hashes guard changes since this snapshot.
        for (entry in ledger.filter { it.path.isNotEmpty() }.sortedByDescending { it.path.count { c -> c == '/' } }) {
            val current = row(entry.id)
            check(current == entry.row) { "OEM_DOCUMENT_CHANGED_BEFORE_DELETE" }
            if (entry.row.directory) {
                check(children(entry.id).isEmpty()) { "OEM_DIRECTORY_NOT_EMPTY_BEFORE_DELETE" }
            } else {
                check(digest(entry.id) == entry.sha256) { "OEM_FILE_CHANGED_BEFORE_DELETE" }
            }
            check(DocumentsContract.deleteDocument(resolver, document(entry.id))) { "OEM_DOCUMENT_DELETE_REFUSED" }
            val parentId = entry.id.substringBeforeLast('/')
            check(children(parentId).none { it.id == entry.id }) { "OEM_DOCUMENT_DELETE_NOT_OBSERVED" }
        }
        check(children(rootId).isEmpty()) { "OEM_ROOT_NOT_EMPTY_AFTER_CLEANUP" }
        val ownGrant = grants().singleOrNull { it.uri == tree.toString() }
        if (acquired) {
            check(ownGrant?.let { it.read && it.write } == true) { "OWN_RW_GRANT_LOST_BEFORE_EMPTY_ATTESTATION" }
            // This strict raw query is performed by the granted audit UID, not later by shell UID.
            check(children(rootId).isEmpty()) { "OEM_ROOT_NOT_EMPTY_BEFORE_EXACT_GRANT_RELEASE" }
            strictRootEmptyBeforeExactGrantRelease = true
            persist("strictRootEmptyObservedWhileExactGrantHeld", emptyList())
        }
        // Preserve the empty UUID folder. Never ask the OEM provider to recursively delete a tree root.
        if (acquired || grants().any { it.uri == tree.toString() }) {
            resolver.releasePersistableUriPermission(tree, RW)
        }
        check(grants().toSet() == baseline.toSet()) { "OEM_GRANT_BASELINE_NOT_RESTORED" }
        priorPersistedGrantsEqualAfterRelease = true
        persist("ownedDocumentsAbsentGrantReleasedEmptyRootPreserved", emptyList())
        released = true
    }

    private fun snapshot(): List<Entry> {
        val output = mutableListOf<Entry>()
        fun visit(id: String, relative: String) {
            check(output.size < 24) { "OEM_DOCUMENT_COUNT_BUDGET" }
            val observed = row(id)
            check(contract[relative] == observed.directory) { "OEM_UNRECORDED_DOCUMENT_PRESERVED" }
            check(observed.name == relative.substringAfterLast('/').ifEmpty { rootName }) {
                "OEM_DOCUMENT_NAME_MISMATCH"
            }
            val sha = if (observed.directory) null else digest(id)
            output += Entry(relative, id, observed, sha)
            if (observed.directory) {
                for (child in children(id)) {
                    check(child.name.isNotEmpty() && '/' !in child.name && child.name !in setOf(".", ".."))
                    check(child.id == "$id/${child.name}") { "OEM_CHILD_ID_BOUNDARY_MISMATCH" }
                    val path = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                    visit(child.id, path)
                }
            }
        }
        visit(rootId, "")
        return output
    }

    private fun row(id: String): Row = query(document(id)).single().also {
        check(it.id == id) { "OEM_QUERY_DOCUMENT_ID_MISMATCH" }
    }

    private fun children(id: String): List<Row> = query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
    ).also { rows ->
        check(rows.size <= 24 && rows.map { it.id }.distinct().size == rows.size) { "OEM_CHILD_LIST_GUARD" }
    }

    private fun query(uri: Uri): List<Row> {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return checkNotNull(resolver.query(uri, columns, null, null, null)) { "OEM_NULL_QUERY_CURSOR" }.use { c ->
            buildList {
                while (c.moveToNext()) {
                    check(size < 25) { "OEM_QUERY_COUNT_BUDGET" }
                    val id = checkNotNull(c.getString(0))
                    val name = checkNotNull(c.getString(1))
                    val mime = checkNotNull(c.getString(2))
                    val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val length = if (directory) {
                        0L
                    } else {
                        check(!c.isNull(3)) { "OEM_FILE_SIZE_UNAVAILABLE" }
                        c.getLong(3)
                    }
                    check(length in 0..HitomiOemRequestBudget.MAX_IMAGE_BYTES) { "OEM_FILE_SIZE_GUARD" }
                    add(Row(id, name, directory, length, c.getInt(4)))
                }
            }
        }
    }

    private fun digest(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        checkNotNull(resolver.openInputStream(document(id))).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                count += size
                check(count <= HitomiOemRequestBudget.MAX_IMAGE_BYTES) { "OEM_HASH_BYTE_BUDGET" }
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun document(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun persist(stage: String, entries: List<Entry>) {
        val data = JSONObject().put("uuid", uuid.toString()).put("treeUri", tree.toString())
            .put("rootDocumentId", rootId).put("stage", stage).put("mayHaveWrites", mayHaveWrites)
            .put("contract", JSONObject(contract as Map<*, *>)).put("entryCount", entries.size)
            .put(
                "entries",
                JSONArray(
                    entries.map { entry ->
                        JSONObject().put("path", entry.path).put("id", entry.id).put("directory", entry.row.directory)
                            .put("length", entry.row.length).put("sha256", entry.sha256)
                    },
                ),
            )
        marker.writeText(data.toString())
    }

    private fun grants() = resolver.persistedUriPermissions.map {
        Grant(it.uri.toString(), it.isReadPermission, it.isWritePermission, it.persistedTime)
    }

    data class Entry(val path: String, val id: String, val row: Row, val sha256: String?)
    data class Row(val id: String, val name: String, val directory: Boolean, val length: Long, val flags: Int)
    private data class Grant(val uri: String, val read: Boolean, val write: Boolean, val persistedTime: Long)

    companion object {
        private const val AUTHORITY = "com.android.externalstorage.documents"
        private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
