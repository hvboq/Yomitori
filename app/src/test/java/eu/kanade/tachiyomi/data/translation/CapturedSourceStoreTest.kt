package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class CapturedSourceStoreTest {
    @Test
    fun `fresh recapture archives previous display and establishes exact source without completion`() {
        val fixture = Fixture()
        val receipt = fixture.replace()

        fixture.io.page.contentEquals(NEW) shouldBe true
        fixture.io.materials.getValue(ORIGINAL).contentEquals(NEW) shouldBe true
        fixture.store.sourceEpoch(PAGE) shouldBe receipt.epoch
        fixture.store.hasPending(PAGE) shouldBe false
        fixture.store.pendingPages() shouldBe emptySet()
        fixture.complete shouldBe setOf(OTHER_PAGE)
        fixture.io.materials.getValue("unrelated.json").decodeToString() shouldBe "unrelated"
        fixture.io.materials.getValue("${receipt.archiveId}.old-page").contentEquals(OLD) shouldBe true
        fixture.io.materials.keys.none { it.endsWith(".png") && it.startsWith("capture-") } shouldBe true
        fixture.store.recover(PAGE, fixture::setComplete) shouldBe false
    }

    @Test
    fun `completed or pending OCR generations are archived and retired together without touching other pages`() {
        val fixture = Fixture(previousComplete = true)
        val previous = mapOf(
            ORIGINAL to bytes("exact old source"),
            "001.orig.webp" to bytes("legacy source"),
            "001.json" to bytes("OCR state"),
            "001.mask.png" to bytes("raw mask"),
            "partial-record.wip" to bytes("pending journal"),
            "001.png.night.webp" to bytes("night image"),
        )
        fixture.io.materials.putAll(previous)
        fixture.retired = previous.keys
        val receipt = fixture.replace()

        fixture.complete shouldBe setOf(OTHER_PAGE)
        fixture.io.materials.getValue(ORIGINAL).contentEquals(NEW) shouldBe true
        (previous.keys - ORIGINAL).forEach { (it in fixture.io.materials) shouldBe false }
        previous.toSortedMap().entries.forEachIndexed { index, entry ->
            fixture.io.materials.getValue(
                "${receipt.archiveId}.old-${index.toString().padStart(3, '0')}",
            ).contentEquals(entry.value) shouldBe true
        }
        fixture.io.materials.getValue("unrelated.json").decodeToString() shouldBe "unrelated"
    }

    @Test
    fun `same bytes produce another epoch and preserve prior successful archive`() {
        val fixture = Fixture()
        val first = fixture.replace()
        val firstNames = fixture.io.materials.keys.toSet()
        fixture.retired = setOf(ORIGINAL)
        val second = fixture.replace()

        (first.epoch == second.epoch) shouldBe false
        fixture.store.sourceEpoch(PAGE) shouldBe second.epoch
        fixture.io.materials.keys.containsAll(firstNames) shouldBe true
        fixture.io.materials.getValue("${second.archiveId}.old-page").contentEquals(NEW) shouldBe true
    }

    @Test
    fun `epoch and pending chapter lookup read metadata only after later translation`() {
        val fixture = Fixture()
        val receipt = fixture.replace()
        fixture.io.page = bytes("later translated output")
        fixture.io.forbidImageReads = true

        fixture.store.sourceEpoch(PAGE) shouldBe receipt.epoch
        fixture.store.hasPending(PAGE) shouldBe false
        fixture.store.pendingPages() shouldBe emptySet()
        fixture.io.page.decodeToString() shouldBe "later translated output"
    }

    @Test
    fun `partial exact source replacement failure restores display original artifacts and membership`() {
        val fixture = Fixture(previousComplete = true)
        fixture.addOldMaterials()
        val before = fixture.activeSnapshot()
        var failed = false
        fixture.io.after = { operation, name ->
            if (!failed && operation == "replaceMaterial" && name == ORIGINAL) {
                failed = true
                fixture.io.materials[ORIGINAL] = NEW.copyOf(2)
                throw IOException("partial original write")
            }
        }

        shouldThrow<IOException> { fixture.replace() }

        fixture.assertActiveEquals(before)
        fixture.store.sourceEpoch(PAGE) shouldBe null
        fixture.complete shouldBe setOf(PAGE, OTHER_PAGE)
        fixture.store.hasPending(PAGE) shouldBe false
    }

    @Test
    fun `partial display replacement failure restores old state and retains verified archives`() {
        val fixture = Fixture(previousComplete = true)
        fixture.addOldMaterials()
        val before = fixture.activeSnapshot()
        var failed = false
        fixture.io.after = { operation, _ ->
            if (!failed && operation == "replacePage") {
                failed = true
                fixture.io.page = NEW.copyOf(2)
                throw IOException("partial page write")
            }
        }

        shouldThrow<IOException> { fixture.replace() }

        fixture.assertActiveEquals(before)
        fixture.complete shouldBe setOf(PAGE, OTHER_PAGE)
        fixture.io.materials.keys.any { it.endsWith(".rollback") } shouldBe true
        fixture.io.materials.keys.any { it.endsWith(".old-page") } shouldBe true
    }

    @Test
    fun `same length display corruption is caught by readback and rolled back`() {
        val fixture = Fixture()
        var changed = false
        fixture.io.after = { operation, _ ->
            if (!changed && operation == "replacePage") {
                changed = true
                fixture.io.page[0] = (fixture.io.page[0].toInt() xor 1).toByte()
            }
        }

        shouldThrow<IOException> { fixture.replace() }

        fixture.io.page.contentEquals(OLD) shouldBe true
        (ORIGINAL in fixture.io.materials) shouldBe false
        fixture.store.sourceEpoch(PAGE) shouldBe null
    }

    @Test
    fun `cancellation after display replacement rolls back without consulting cancellation during recovery`() {
        val fixture = Fixture(previousComplete = true)
        fixture.addOldMaterials()
        val before = fixture.activeSnapshot()
        var cancellations = 0

        shouldThrow<CancellationException> {
            fixture.replace {
                if (fixture.io.page.contentEquals(NEW)) {
                    cancellations++
                    throw CancellationException("cancel")
                }
            }
        }

        cancellations shouldBe 1
        fixture.assertActiveEquals(before)
        fixture.complete shouldBe setOf(PAGE, OTHER_PAGE)
        fixture.store.sourceEpoch(PAGE) shouldBe null
    }

    @Test
    fun `failed rollback keeps pending authority and a restarted store can recover it`() {
        val fixture = Fixture(previousComplete = true)
        fixture.addOldMaterials()
        val before = fixture.activeSnapshot()
        fixture.io.after = { operation, _ ->
            if (operation == "replacePage") {
                fixture.io.page = bytes("torn")
                throw IOException("provider unavailable")
            }
        }

        val failure = shouldThrow<IOException> { fixture.replace() }
        failure.suppressed.size shouldBe 1
        fixture.store.hasPending(PAGE) shouldBe true
        fixture.store.pendingPages() shouldBe setOf(PAGE)
        shouldThrow<IOException> { fixture.store.sourceEpoch(PAGE) }
        fixture.io.after = null

        val restarted = CapturedSourceStore(fixture.io)
        restarted.recover(PAGE, fixture::setComplete) shouldBe true
        fixture.assertActiveEquals(before)
        fixture.complete shouldBe setOf(PAGE, OTHER_PAGE)
        restarted.sourceEpoch(PAGE) shouldBe null
    }

    @Test
    fun `process death at each precommit mutation boundary recovers exact old state`() {
        val stages = listOf("prepare", "pending", "original", "page", "delete", "manifest")
        for (stage in stages) {
            val fixture = Fixture(previousComplete = true)
            fixture.addOldMaterials()
            val before = fixture.activeSnapshot()
            fixture.io.after = { operation, name ->
                val matches = when (stage) {
                    "prepare" -> operation == "createMaterial" && name.endsWith(".prepare")
                    "pending" -> operation == "createMaterial" && name.endsWith(".pending")
                    "original" -> operation == "replaceMaterial" && name == ORIGINAL
                    "page" -> operation == "replacePage"
                    "delete" -> operation == "deleteMaterial" && name == "001.json"
                    else -> false
                }
                if (matches) throw ProcessDeath()
            }
            if (stage == "manifest") fixture.afterSetComplete = { if (!it) throw ProcessDeath() }

            shouldThrow<ProcessDeath> { fixture.replace() }
            fixture.store.hasPending(PAGE) shouldBe true
            shouldThrow<IOException> { fixture.store.sourceEpoch(PAGE) }
            fixture.io.after = null
            fixture.afterSetComplete = null

            CapturedSourceStore(fixture.io).recover(PAGE, fixture::setComplete) shouldBe true
            fixture.assertActiveEquals(before)
            fixture.complete shouldBe setOf(PAGE, OTHER_PAGE)
        }
    }

    @Test
    fun `death during archive copying authorizes no active mutation`() {
        val fixture = Fixture()
        fixture.io.after = { operation, name ->
            if (operation == "createMaterial" && name.endsWith(".new")) throw ProcessDeath()
        }

        shouldThrow<ProcessDeath> { fixture.replace() }

        fixture.io.page.contentEquals(OLD) shouldBe true
        fixture.store.sourceEpoch(PAGE) shouldBe null
        fixture.store.hasPending(PAGE) shouldBe false
        fixture.store.recover(PAGE, fixture::setComplete) shouldBe false
    }

    @Test
    fun `lost pending marker cannot hide an unfinished prepared source replacement`() {
        val fixture = Fixture(previousComplete = true)
        fixture.addOldMaterials()
        val before = fixture.activeSnapshot()
        fixture.io.after = { operation, _ -> if (operation == "replacePage") throw ProcessDeath() }
        shouldThrow<ProcessDeath> { fixture.replace() }
        fixture.io.after = null
        fixture.io.materials.remove(fixture.io.materials.keys.single { it.endsWith(".pending") })

        fixture.store.hasPending(PAGE) shouldBe true
        fixture.store.pendingPages() shouldBe setOf(PAGE)
        shouldThrow<IOException> { fixture.store.sourceEpoch(PAGE) }
        fixture.store.recover(PAGE, fixture::setComplete) shouldBe true
        fixture.assertActiveEquals(before)
        fixture.complete shouldBe setOf(PAGE, OTHER_PAGE)
    }

    @Test
    fun `torn pending marker is superseded only by verified rollback`() {
        val fixture = Fixture()
        fixture.io.after = { operation, name ->
            if (operation == "createMaterial" && name.endsWith(".pending")) {
                fixture.io.materials[name] = bytes("torn marker")
                throw IOException("pending truncated")
            }
        }

        shouldThrow<IOException> { fixture.replace() }

        fixture.io.page.contentEquals(OLD) shouldBe true
        fixture.store.sourceEpoch(PAGE) shouldBe null
        fixture.store.hasPending(PAGE) shouldBe false
    }

    @Test
    fun `truncated commit before commit point rolls back the target`() {
        val fixture = Fixture(previousComplete = true)
        fixture.io.after = { operation, name ->
            if (operation == "createMaterial" && name.endsWith(".commit")) {
                fixture.io.materials[name] = bytes("torn commit")
                throw IOException("commit truncated")
            }
        }

        shouldThrow<IOException> { fixture.replace() }

        fixture.io.page.contentEquals(OLD) shouldBe true
        fixture.complete shouldBe setOf(PAGE, OTHER_PAGE)
        fixture.store.sourceEpoch(PAGE) shouldBe null
    }

    @Test
    fun `provider exception after complete commit bytes is successful and never rolls back`() {
        val fixture = Fixture()
        fixture.io.after = { operation, name ->
            if (operation == "createMaterial" && name.endsWith(".commit")) throw IOException("late provider exception")
        }

        val receipt = fixture.replace()

        fixture.io.page.contentEquals(NEW) shouldBe true
        fixture.store.sourceEpoch(PAGE) shouldBe receipt.epoch
        fixture.io.materials.keys.none { it.endsWith(".rollback") } shouldBe true
    }

    @Test
    fun `unreadable commit outcome preserves potentially committed output until a later read resolves it`() {
        val fixture = Fixture()
        var unavailable = true
        fixture.io.before = { operation, name ->
            if (operation == "readMaterial" && name.endsWith(".commit") && unavailable) {
                unavailable = false
                throw IOException("commit read unavailable")
            }
        }

        shouldThrow<IOException> { fixture.replace() }

        fixture.io.page.contentEquals(NEW) shouldBe true
        fixture.complete shouldBe setOf(OTHER_PAGE)
        fixture.io.materials.keys.none { it.endsWith(".rollback") } shouldBe true
        (fixture.store.sourceEpoch(PAGE) != null) shouldBe true
        fixture.store.recover(PAGE, fixture::setComplete) shouldBe false
    }

    @Test
    fun `process death after full commit resolves as committed after restart`() {
        val fixture = Fixture()
        fixture.io.after = { operation, name ->
            if (operation == "createMaterial" && name.endsWith(".commit")) throw ProcessDeath()
        }

        shouldThrow<ProcessDeath> { fixture.replace() }
        fixture.io.after = null

        val restarted = CapturedSourceStore(fixture.io)
        (restarted.sourceEpoch(PAGE) != null) shouldBe true
        restarted.recover(PAGE, fixture::setComplete) shouldBe false
        fixture.io.page.contentEquals(NEW) shouldBe true
    }

    @Test
    fun `corrupt prepared authority and orphan marker fail closed without changing active bytes`() {
        val fixture = Fixture()
        val receipt = fixture.replace()
        val active = fixture.activeSnapshot()
        fixture.io.materials["${receipt.archiveId}.prepare"]!![0] = 0

        shouldThrow<IOException> { fixture.store.sourceEpoch(PAGE) }
        shouldThrow<IOException> { fixture.store.pendingPages() }
        shouldThrow<IOException> { fixture.store.recover(PAGE, fixture::setComplete) }
        fixture.assertActiveEquals(active)

        fixture.io.materials.remove("${receipt.archiveId}.prepare")
        shouldThrow<IOException> { fixture.store.sourceEpoch(PAGE) }
        shouldThrow<IOException> { fixture.store.pendingPages() }
    }

    @Test
    fun `recovery validates every archived byte before changing any active material`() {
        val fixture = Fixture(previousComplete = true)
        fixture.addOldMaterials()
        fixture.io.after = { operation, _ -> if (operation == "replacePage") throw ProcessDeath() }
        shouldThrow<ProcessDeath> { fixture.replace() }
        fixture.io.after = null
        val active = fixture.activeSnapshot()
        val archive = fixture.io.materials.keys.single { it.endsWith(".old-000") }
        fixture.io.materials[archive]!![0] = 0

        shouldThrow<IOException> { fixture.store.recover(PAGE, fixture::setComplete) }

        fixture.assertActiveEquals(active)
        fixture.store.hasPending(PAGE) shouldBe true
    }

    @Test
    fun `rollback manifest callback preserves newly completed other page`() {
        val fixture = Fixture(previousComplete = true)
        var failed = false
        fixture.afterSetComplete = { complete ->
            if (!complete && !failed) {
                failed = true
                fixture.complete = fixture.complete + "concurrently-completed.png"
                throw IOException("manifest verification failed")
            }
        }

        shouldThrow<IOException> { fixture.replace() }

        fixture.complete shouldBe setOf(PAGE, OTHER_PAGE, "concurrently-completed.png")
        fixture.io.page.contentEquals(OLD) shouldBe true
    }

    @Test
    fun `failed next recapture restores previous successful epoch and its exact source`() {
        val fixture = Fixture()
        val first = fixture.replace()
        fixture.retired = setOf(ORIGINAL)
        var failed = false
        fixture.io.after = { operation, _ ->
            if (!failed && operation == "replacePage") {
                failed = true
                throw IOException("second capture failed")
            }
        }

        shouldThrow<IOException> {
            fixture.store.replace(PAGE, bytes("second capture"), fixture.retired, false, fixture::setComplete)
        }

        fixture.io.page.contentEquals(NEW) shouldBe true
        fixture.io.materials.getValue(ORIGINAL).contentEquals(NEW) shouldBe true
        fixture.store.sourceEpoch(PAGE) shouldBe first.epoch
    }

    @Test
    fun `unsafe missing ambiguous and excessive retired inventories fail before any archive creation`() {
        val inventories = listOf(
            setOf("../outside"),
            setOf("missing.json"),
            setOf("capture-forbidden.prepare"),
            setOf("Case.json", "case.json"),
            (0..128).map { "material-$it" }.toSet(),
        )
        for (inventory in inventories) {
            val fixture = Fixture()
            inventory.filterNot { it == "missing.json" }.forEach { fixture.io.materials[it] = byteArrayOf(1) }
            val before = fixture.io.materials.keys.toSet()

            shouldThrow<IOException> {
                fixture.store.replace(PAGE, NEW, inventory, false, fixture::setComplete)
            }

            fixture.io.materials.keys shouldBe before
            fixture.io.page.contentEquals(OLD) shouldBe true
        }
    }

    @Test
    fun `an existing original omitted from retirement and empty source are rejected before writes`() {
        val fixture = Fixture()
        fixture.io.materials[ORIGINAL] = OLD.copyOf()
        val before = fixture.io.materials.keys.toSet()

        shouldThrow<IOException> { fixture.replace() }
        shouldThrow<IOException> {
            fixture.store.replace(PAGE, byteArrayOf(), setOf(ORIGINAL), false, fixture::setComplete)
        }
        shouldThrow<IOException> {
            fixture.store.replace("../$PAGE", NEW, setOf(ORIGINAL), false, fixture::setComplete)
        }

        fixture.io.materials.keys shouldBe before
        fixture.io.page.contentEquals(OLD) shouldBe true
    }

    @Test
    fun `bounded orphan archive history stops before the next transaction creates files`() {
        val fixture = Fixture()
        val pageHash = MessageDigest.getInstance("SHA-256").digest(PAGE.toByteArray())
            .joinToString("") { "%02x".format(it) }
        repeat(256) { index ->
            val epoch = index.toString(16).padStart(32, '0')
            fixture.io.materials["capture-$pageHash-$epoch.new"] = byteArrayOf(1)
        }
        val before = fixture.io.materials.keys.toSet()

        shouldThrow<IOException> { fixture.replace() }

        fixture.io.materials.keys shouldBe before
        fixture.io.page.contentEquals(OLD) shouldBe true
        fixture.store.sourceEpoch(PAGE) shouldBe null
    }

    @Test
    fun `oversized metadata and case ambiguous exact source fail closed before active writes`() {
        val fixture = Fixture()
        val receipt = fixture.replace()
        fixture.io.materials["${receipt.archiveId}.prepare"] = ByteArray(128 * 1024 + 1)
        val active = fixture.activeSnapshot()

        shouldThrow<IOException> { fixture.store.sourceEpoch(PAGE) }
        fixture.assertActiveEquals(active)

        val ambiguous = Fixture()
        ambiguous.io.materials["001.orig.PNG"] = OLD.copyOf()
        ambiguous.retired = setOf("001.orig.PNG")
        val before = ambiguous.io.materials.keys.toSet()
        shouldThrow<IOException> { ambiguous.replace() }
        ambiguous.io.materials.keys shouldBe before
        ambiguous.io.page.contentEquals(OLD) shouldBe true
    }

    private class ProcessDeath : Error()

    private class Fixture(previousComplete: Boolean = false) {
        val io = MemoryIo()
        val store = CapturedSourceStore(io)
        var complete: Set<String> = if (previousComplete) setOf(PAGE, OTHER_PAGE) else setOf(OTHER_PAGE)
        var retired: Set<String> = emptySet()
        var afterSetComplete: ((Boolean) -> Unit)? = null

        init {
            io.materials["unrelated.json"] = bytes("unrelated")
        }

        fun setComplete(value: Boolean) {
            complete = if (value) complete + PAGE else complete - PAGE
            afterSetComplete?.invoke(value)
        }

        fun replace(checkCancellation: () -> Unit = {}): CapturedSourceStore.Receipt = store.replace(
            PAGE,
            NEW,
            retired,
            PAGE in complete,
            ::setComplete,
            checkCancellation,
        )

        fun addOldMaterials() {
            io.materials[ORIGINAL] = bytes("old OCR source")
            io.materials["001.json"] = bytes("old OCR journal")
            io.materials["001.mask.png"] = bytes("old raw mask")
            retired = setOf(ORIGINAL, "001.json", "001.mask.png")
        }

        fun activeSnapshot(): Pair<ByteArray, Map<String, ByteArray>> = io.page.copyOf() to
            io.materials.filterKeys { !it.startsWith("capture-") }.mapValues { it.value.copyOf() }

        fun assertActiveEquals(snapshot: Pair<ByteArray, Map<String, ByteArray>>) {
            io.page.contentEquals(snapshot.first) shouldBe true
            val current = io.materials.filterKeys { !it.startsWith("capture-") }
            current.keys shouldBe snapshot.second.keys
            snapshot.second.forEach { (name, bytes) -> current.getValue(name).contentEquals(bytes) shouldBe true }
        }
    }

    private class MemoryIo : CapturedSourceStore.Io {
        val materials = mutableMapOf<String, ByteArray>()
        var page = OLD.copyOf()
        var before: ((String, String) -> Unit)? = null
        var after: ((String, String) -> Unit)? = null
        var forbidImageReads = false

        override fun materialNames(): Set<String> = materials.keys.toSet()

        override fun readMaterial(name: String, maxBytes: Int): ByteArray? {
            before?.invoke("readMaterial", name)
            if (forbidImageReads && !listOf(".prepare", ".pending", ".commit", ".rollback").any(name::endsWith)) {
                throw AssertionError("Metadata lookup read image")
            }
            return materials[name]?.also { if (it.size > maxBytes) throw IOException("bounded read") }?.copyOf()
        }

        override fun createMaterial(name: String, bytes: ByteArray): Boolean {
            before?.invoke("createMaterial", name)
            if (name in materials) return false
            materials[name] = bytes.copyOf()
            after?.invoke("createMaterial", name)
            return true
        }

        override fun replaceMaterial(name: String, bytes: ByteArray): Boolean {
            before?.invoke("replaceMaterial", name)
            if (name !in materials) return false
            materials[name] = bytes.copyOf()
            after?.invoke("replaceMaterial", name)
            return true
        }

        override fun deleteMaterial(name: String): Boolean {
            before?.invoke("deleteMaterial", name)
            materials.remove(name)
            after?.invoke("deleteMaterial", name)
            return true
        }

        override fun readPage(pageName: String, maxBytes: Int): ByteArray {
            before?.invoke("readPage", pageName)
            if (forbidImageReads) throw AssertionError("Metadata lookup read page")
            if (page.size > maxBytes) throw IOException("bounded page read")
            return page.copyOf()
        }

        override fun replacePage(pageName: String, bytes: ByteArray): Boolean {
            before?.invoke("replacePage", pageName)
            page = bytes.copyOf()
            after?.invoke("replacePage", pageName)
            return true
        }
    }

    companion object {
        private const val PAGE = "001.png"
        private const val OTHER_PAGE = "002.png"
        private const val ORIGINAL = "001.orig.png"
        private val OLD = bytes("old displayed page")
        private val NEW = bytes("new captured source")
        private fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
    }
}
