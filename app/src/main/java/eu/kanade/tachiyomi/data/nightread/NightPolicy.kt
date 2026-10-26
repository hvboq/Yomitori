package eu.kanade.tachiyomi.data.nightread

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.NightTextBoxes
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageIdentity
import li.joye.yakuyomi.engine.NightReadRenderer
import java.io.IOException
import java.io.InputStream

/** Immutable, complete std/more policy pair. Numeric/legacy stamps cannot authorize new reads. */
internal object NightPolicy {
    private const val HEADER = "night-source-policy-v2"
    private const val MAX_PROOF_BYTES = 512
    private val HEX64 = Regex("[a-f0-9]{64}")
    private val STAMP =
        Regex(
            "night-([a-f0-9]{64})\\.rules([0-9]{1,6})\\.(std|more)\\.(rgb|same)\\.([a-z0-9-]{1,96})\\.g([a-f0-9]{32})",
        )

    data class Stamp(
        val pageKey: String,
        val version: Int,
        val level: NightLevel,
        val same: Boolean,
        val policy: String,
        val generation: String,
    )
    data class Recipe(val imageName: String, val proofs: List<String>, val generation: String)
    data class Witness(val digest: String)

    interface ReadIo {
        /** Exact, bounded revision + known metadata only. Never list, recover or hash images. */
        fun witness(): Witness
        fun proof(name: String): ByteArray
        fun image(name: String): InputStream
    }

    fun parse(name: String): Stamp? {
        val match = STAMP.matchEntire(name) ?: return null
        val level = NightLevel.entries.singleOrNull { it.fileKey == match.groupValues[3] } ?: return null
        val same = match.groupValues[4] == "same"
        if (same && level == NightLevel.STANDARD) return null
        return Stamp(
            match.groupValues[1],
            match.groupValues[2].toInt(),
            level,
            same,
            match.groupValues[5],
            match.groupValues[6],
        )
    }

    fun names(page: String, generation: String, moreDistinct: Boolean): List<String> {
        require(Regex("[a-f0-9]{32}").matches(generation))
        val prefix = "night-${NightSourceRevision.pageKey(page)}.rules${NightPages.RULES_VERSION}"
        return listOf(
            "$prefix.std.rgb.${NightReadRenderer.INPUT_RGB_POLICY_ID}.g$generation",
            "$prefix.more.${if (moreDistinct) "rgb" else "same"}.${NightReadRenderer.INPUT_RGB_POLICY_ID}.g$generation",
        ).onEach { require(it.toByteArray(Charsets.UTF_8).size <= 255 && parse(it) != null) }
    }

    fun belongsTo(name: String, page: String): Boolean = parse(name)?.pageKey == NightSourceRevision.pageKey(page)

    fun recipe(names: Set<String>, page: String, level: NightLevel): Recipe? {
        val standard = NightPages.levelName(page, NightLevel.STANDARD)
        if (standard !in names) return null
        val key = NightSourceRevision.pageKey(page)
        val stamps = names.mapNotNull { name -> parse(name)?.let { name to it } }.filter { it.second.pageKey == key }
        if (stamps.any { (_, stamp) ->
                stamp.version != NightPages.RULES_VERSION ||
                    stamp.policy != NightReadRenderer.INPUT_RGB_POLICY_ID
            }
        ) {
            return null
        }
        val std = stamps.filter { it.second.level == NightLevel.STANDARD }.singleOrNull() ?: return null
        val more = stamps.filter { it.second.level == NightLevel.MORE }.singleOrNull() ?: return null
        if (std.second.generation != more.second.generation) return null
        val moreName = NightPages.levelName(page, NightLevel.MORE)
        val image = when {
            more.second.same && moreName !in names -> standard
            !more.second.same && moreName in names -> if (level == NightLevel.STANDARD) standard else moreName
            else -> return null
        }
        return Recipe(image, listOf(std.first, more.first), std.second.generation)
    }

    fun encode(stamp: Stamp, witness: Witness): ByteArray {
        require(HEX64.matches(witness.digest))
        require(stamp.version == NightPages.RULES_VERSION && stamp.policy == NightReadRenderer.INPUT_RGB_POLICY_ID)
        return listOf(
            HEADER, stamp.pageKey, stamp.generation, stamp.policy, stamp.version.toString(),
            stamp.level.fileKey, if (stamp.same) "same" else "rgb", witness.digest, "",
        ).joinToString("\n").toByteArray(Charsets.US_ASCII).also { require(it.size <= MAX_PROOF_BYTES) }
    }

    private fun matches(name: String, bytes: ByteArray, witness: Witness): Boolean {
        val stamp = parse(name) ?: return false
        return bytes.size in 1..MAX_PROOF_BYTES && bytes.contentEquals(encode(stamp, witness))
    }

    fun reusable(names: Set<String>, page: String, io: ReadIo): Boolean {
        val recipe = recipe(names, page, NightLevel.MORE) ?: return false
        val before = io.witness()
        return recipe.proofs.all { matches(it, io.proof(it), before) } && io.witness() == before
    }

    /** Already-open descriptor semantics: prove immediately before AND after opening the image. */
    fun openCurrent(names: Set<String>, page: String, level: NightLevel, io: ReadIo): Pair<String, InputStream>? {
        val recipe = recipe(names, page, level) ?: return null
        val before = io.witness()
        if (!recipe.proofs.all { matches(it, io.proof(it), before) }) return null
        val input = io.image(recipe.imageName)
        var transferred = false
        try {
            if (io.witness() != before || !recipe.proofs.all { matches(it, io.proof(it), before) }) return null
            transferred = true
            return "${recipe.generation}:${recipe.imageName}" to input
        } finally {
            if (!transferred) input.close()
        }
    }

    /** A finite set of exact URIs; construction is the ONLY capture-history validation. */
    class Entry internal constructor(
        private val page: String,
        private val source: UniFile,
        private val materials: List<Pair<String, UniFile?>>,
        private val revisionFile: UniFile,
        private val revision: NightSourceRevision.Revision,
        private val files: Map<String, UniFile>,
    ) : ReadIo {
        override fun witness(): Witness {
            if (NightSourceRevision.read(revisionFile) != revision ||
                !revision.committed
            ) {
                throw IOException("Night source changed")
            }
            val result = metadataWitness(page, source, materials, revision)
            if (NightSourceRevision.read(revisionFile) !=
                revision
            ) {
                throw IOException("Night source changed during metadata read")
            }
            return result
        }

        override fun proof(name: String): ByteArray {
            if (!belongsTo(name, page)) throw IOException("Night proof page mismatch")
            return NightSourceRevision.readExact(
                files[name] ?: throw IOException("Missing Night proof"),
                MAX_PROOF_BYTES,
            )
        }

        override fun image(name: String): InputStream {
            if (name !in NightPages.currentNames(page)) throw IOException("Night image mismatch")
            return files[name]?.openInputStream() ?: throw IOException("Cannot open Night image")
        }
    }

    /** Publication repair reads only current metadata, never infers original/journal authority. */
    fun protectedWitness(chapter: UniFile, materials: UniFile, page: String, baseId: String, epoch: String?): Witness {
        val source = chapter.findFile(page) ?: throw IOException("Missing Night source")
        val files = NightSourceRevision.inventory(materials)
        return metadataWitness(
            page,
            source,
            selectedMaterials(page, files),
            NightSourceRevision.Revision(baseId, true, epoch),
        )
    }

    private fun selectedMaterials(page: String, files: Map<String, UniFile>): List<Pair<String, UniFile?>> = listOf(
        NightTextBoxes.materialsJsonName(page),
        OriginalMaterial.base(page) + ".mask.png",
        NightTextBoxes.fileName(page),
    ).map { it to files[it] }

    private fun metadataWitness(
        page: String,
        source: UniFile,
        materials: List<Pair<String, UniFile?>>,
        revision: NightSourceRevision.Revision,
    ): Witness {
        val values = ArrayList<String>(18)
        values += revision.id
        values += revision.capturedEpoch ?: "no-explicit-capture"
        fun metadata(name: String, file: UniFile?) {
            values += name
            if (file == null) {
                values += "absent"
            } else {
                val length = file.length()
                val modified = file.lastModified()
                if (!file.isFile || file.name != name || length <= 0 ||
                    modified <= 0
                ) {
                    throw IOException("Unprovable Night metadata")
                }
                values += PageIdentity.fromUri(file.uri).toString()
                values += length.toString()
                values += modified.toString()
            }
        }
        metadata(page, source)
        materials.forEach { (name, file) -> metadata(name, file) }
        // Length-prefix fields, including absent selected materials, prevent ambiguous joins.
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            values.forEach { value ->
                val field = value.toByteArray(Charsets.UTF_8)
                out.writeInt(field.size)
                out.write(field)
            }
        }
        return Witness(NightSourceRevision.sha256(bytes.toByteArray()))
    }

    /** Initial/explicit refresh only; [files] is an already acquired single material inventory. */
    fun entry(source: UniFile, page: String, files: Map<String, UniFile>): Entry {
        val revisionFile =
            files[NightSourceRevision.fileName(page)] ?: throw IOException("Missing Night source revision")
        val before = NightSourceRevision.read(revisionFile)
        if (!before.committed) throw IOException("Pending Night source revision")
        val epoch = NightSourceRevision.capturedEpoch(files, page)
        if (before.capturedEpoch != epoch) throw IOException("Night capture authority mismatch")
        val selected = selectedMaterials(page, files)
        val result = Entry(page, source, selected, revisionFile, before, files)
        result.witness()
        if (NightSourceRevision.read(revisionFile) != before) throw IOException("Night source changed during refresh")
        return result
    }

    /** Producer convenience; never used by decode. Caller owns the shared page lock. */
    fun snapshot(chapter: UniFile, materials: UniFile, page: String): Witness {
        val source = chapter.findFile(page) ?: throw IOException("Missing Night source")
        return entry(source, page, NightSourceRevision.inventory(materials)).witness()
    }

    fun readBounded(input: InputStream, limit: Int): ByteArray = NightSourceRevision.readBounded(input, limit)
}
