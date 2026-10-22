package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class PartialPageStoreTest {
    @Test
    fun `pending output survives restart without replacing its original or becoming completed`() {
        val fixture = Fixture()
        val state = fixture.commit("first partial")
        val restarted = PartialPageStore(fixture.io)

        restarted.load(PAGE) shouldBe state
        restarted.readOutput(state).decodeToString() shouldBe "first partial"
        restarted.readRawMask(state).contentEquals(mask()) shouldBe true
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
        restarted.applyDoneOverlay(setOf(PAGE, "other.png")) shouldBe setOf("other.png")
    }

    @Test
    fun `completion tombstone supersedes older pending and stale completion checkpoints`() {
        val fixture = Fixture()
        val partial = fixture.commit("first partial")
        val complete = fixture.commit("all groups translated", base = partial.revision, complete = true)
        val store = PartialPageStore(fixture.io)

        store.load(PAGE) shouldBe complete
        store.applyDoneOverlay(emptySet()) shouldBe setOf(PAGE)
        store.applyDoneOverlay(setOf(PAGE, "other.png")) shouldBe setOf(PAGE, "other.png")
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `reopened partial after a complete generation overrides every old done checkpoint`() {
        val fixture = Fixture()
        val complete = fixture.commit("complete v1", complete = true)
        val pending = fixture.commit("partial v2", base = complete.revision)

        fixture.store.load(PAGE) shouldBe pending
        fixture.store.applyDoneOverlay(setOf(PAGE)) shouldBe emptySet()
    }

    @Test
    fun `stale worker cannot overwrite a newer partial generation`() {
        val fixture = Fixture()
        val first = fixture.commit("partial v1")
        val second = fixture.commit("partial v2", base = first.revision)
        val before = fixture.io.materials.mapValues { it.value.copyOf() }

        shouldThrow<IOException> { fixture.commit("stale result", base = first.revision, complete = true) }

        fixture.store.load(PAGE) shouldBe second
        fixture.io.page.decodeToString() shouldBe "partial v2"
        fixture.io.materials.keys shouldBe before.keys
        before.forEach { (name, bytes) -> fixture.io.materials.getValue(name).contentEquals(bytes) shouldBe true }
    }

    @Test
    fun `cancellation after replacing the first partial restores the exact original`() {
        val fixture = Fixture()
        shouldThrow<CancellationException> {
            fixture.commit("partial", cancel = {
                if (fixture.io.page.decodeToString() == "partial") throw CancellationException("stop")
            })
        }

        fixture.io.page.decodeToString() shouldBe "exact original bytes"
        fixture.store.load(PAGE) shouldBe null
        fixture.io.materials.keys.none { it.endsWith(".wip") || it.endsWith(".commit") } shouldBe true
    }

    @Test
    fun `cancellation on retry restores the previous partial including its successful translations`() {
        val fixture = Fixture()
        val partial = fixture.commit("successful groups remain")
        shouldThrow<CancellationException> {
            fixture.commit("new result", base = partial.revision, cancel = {
                if (fixture.io.page.decodeToString() == "new result") throw CancellationException("stop")
            })
        }

        fixture.io.page.decodeToString() shouldBe "successful groups remain"
        fixture.store.load(PAGE) shouldBe partial
    }

    @Test
    fun `process death with a truncated visible first page rolls back using verified recovery authority`() {
        val fixture = Fixture()
        fixture.io.replace = { bytes ->
            fixture.io.page = bytes.copyOf(2)
            throw ProcessDeath()
        }
        shouldThrow<ProcessDeath> { fixture.commit("partial") }
        fixture.io.replace = null

        PartialPageStore(fixture.io).load(PAGE) shouldBe null
        fixture.io.page.decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `process death on retry restores prior snapshot rather than treating translated output as original`() {
        val fixture = Fixture()
        val partial = fixture.commit("first successes")
        fixture.io.replace = { bytes ->
            fixture.io.page = bytes.copyOf(3)
            throw ProcessDeath()
        }
        shouldThrow<ProcessDeath> { fixture.commit("second attempt", base = partial.revision) }
        fixture.io.replace = null

        PartialPageStore(fixture.io).load(PAGE) shouldBe partial
        fixture.io.page.decodeToString() shouldBe "first successes"
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `process death after commit is durable even if cleanup never ran`() {
        val fixture = Fixture()
        fixture.io.afterCreate = { name -> if (name.endsWith(".commit")) throw ProcessDeath() }
        shouldThrow<ProcessDeath> { fixture.commit("fully committed", complete = true) }
        fixture.io.afterCreate = null

        val state = PartialPageStore(fixture.io).load(PAGE)!!
        state.complete shouldBe true
        fixture.io.page.decodeToString() shouldBe "fully committed"
        fixture.io.materials.keys.none { it.endsWith(".wip") } shouldBe true
    }

    @Test
    fun `torn final marker is rolled back and cannot fabricate a completed page`() {
        val fixture = Fixture()
        val partial = fixture.commit("first successes")
        fixture.io.afterCreate = { name ->
            if (name.endsWith(".commit")) {
                fixture.io.materials[name] = fixture.io.materials.getValue(name).copyOf(23)
                throw ProcessDeath()
            }
        }
        shouldThrow<ProcessDeath> { fixture.commit("new result", base = partial.revision, complete = true) }
        fixture.io.afterCreate = null

        fixture.store.applyDoneOverlay(setOf(PAGE)) shouldBe emptySet()
        PartialPageStore(fixture.io).load(PAGE) shouldBe partial
        fixture.io.page.decodeToString() shouldBe "first successes"
    }

    @Test
    fun `snapshot mask record and wip failures cannot touch the current page`() {
        for (suffix in listOf(".output", ".mask", ".record", ".wip")) {
            val fixture = Fixture()
            fixture.io.truncateSuffix = suffix

            shouldThrow<IOException> { fixture.commit("partial") }

            fixture.io.page.decodeToString() shouldBe "exact original bytes"
            fixture.io.replacementCount shouldBe 0
            fixture.io.materials.keys.none { it.endsWith(".commit") } shouldBe true
        }
    }

    @Test
    fun `orphan preparation files do not revoke a legacy completed page`() {
        val fixture = Fixture()
        fixture.io.truncateSuffix = ".record"
        shouldThrow<IOException> { fixture.commit("partial") }

        fixture.store.applyDoneOverlay(setOf(PAGE)) shouldBe setOf(PAGE)
    }

    @Test
    fun `failed wip write can be retried when the original page was never touched`() {
        val fixture = Fixture()
        fixture.io.truncateSuffix = ".wip"
        shouldThrow<IOException> { fixture.commit("first attempt") }
        fixture.store.applyDoneOverlay(setOf(PAGE)) shouldBe emptySet()
        fixture.io.truncateSuffix = null

        val retried = fixture.commit("second attempt")

        fixture.store.load(PAGE) shouldBe retried
        fixture.io.page.decodeToString() shouldBe "second attempt"
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `damaged wip cannot invent recovery authority for a changed visible page`() {
        val fixture = Fixture()
        fixture.io.replace = { bytes ->
            fixture.io.page = bytes.copyOf(2)
            throw ProcessDeath()
        }
        shouldThrow<ProcessDeath> { fixture.commit("interrupted") }
        fixture.io.replace = null
        val wip = fixture.io.materials.keys.single { it.endsWith(".wip") }
        fixture.io.materials[wip] = byteArrayOf(1)
        val replacements = fixture.io.replacementCount

        shouldThrow<IOException> { fixture.store.load(PAGE) }

        fixture.io.replacementCount shouldBe replacements
        fixture.io.page.size shouldBe 2
        fixture.store.applyDoneOverlay(setOf(PAGE)) shouldBe emptySet()
    }

    @Test
    fun `forked committed generations are rejected rather than picking a filename or newest timestamp`() {
        val first = Fixture()
        val second = Fixture()
        first.commit("one worker")
        second.commit("different worker")
        second.io.materials.filterKeys { it != ORIGINAL }.forEach { (name, bytes) ->
            first.io.materials[name] =
                bytes.copyOf()
        }
        val replacements = first.io.replacementCount

        shouldThrow<IOException> { first.store.load(PAGE) }

        first.io.replacementCount shouldBe replacements
        first.io.page.decodeToString() shouldBe "one worker"
        first.store.applyDoneOverlay(setOf(PAGE)) shouldBe emptySet()
    }

    @Test
    fun `false success and short writes are verified rather than accepted`() {
        val fixture = Fixture()
        val partial = fixture.commit("known successes")
        fixture.io.replace = { bytes ->
            fixture.io.page = bytes.copyOf(bytes.size - 1)
            fixture.io.replace = null
            true
        }

        shouldThrow<IOException> { fixture.commit("another attempt", base = partial.revision) }

        fixture.io.page.decodeToString() shouldBe "known successes"
        fixture.store.load(PAGE) shouldBe partial
    }

    @Test
    fun `failed immediate rollback keeps recovery authority for restart`() {
        val fixture = Fixture()
        val partial = fixture.commit("known successes")
        fixture.io.replace = { bytes ->
            fixture.io.page = bytes.copyOf(1)
            false
        }
        shouldThrow<IOException> { fixture.commit("another attempt", base = partial.revision) }
        fixture.io.materials.keys.any { it.endsWith(".wip") } shouldBe true
        fixture.io.replace = null

        PartialPageStore(fixture.io).load(PAGE) shouldBe partial
        fixture.io.page.decodeToString() shouldBe "known successes"
    }

    @Test
    fun `changed page is never overwritten even if committed wip cleanup previously failed`() {
        val fixture = Fixture()
        fixture.io.failDelete = true
        fixture.commit("complete result", complete = true)
        fixture.io.page = "user replaced image".toByteArray()
        val replacements = fixture.io.replacementCount

        shouldThrow<IOException> { PartialPageStore(fixture.io).load(PAGE) }

        fixture.io.page.decodeToString() shouldBe "user replaced image"
        fixture.io.replacementCount shouldBe replacements
    }

    @Test
    fun `original or snapshot corruption blocks retry without modifying any visible page`() {
        for (material in listOf("original", "output", "mask", "record")) {
            val fixture = Fixture()
            val partial = fixture.commit("known successes")
            val name = when (material) {
                "original" -> ORIGINAL
                "output" -> partial.outputSnapshotName
                "mask" -> partial.rawMaskSnapshotName
                else -> fixture.io.materials.keys.single { it.endsWith(".record") }
            }
            fixture.io.materials[name] = fixture.io.materials.getValue(name).copyOf(1)
            val replacements = fixture.io.replacementCount

            shouldThrow<Exception> { fixture.commit("retry", base = partial.revision) }

            fixture.io.page.decodeToString() shouldBe "known successes"
            fixture.io.replacementCount shouldBe replacements
            // Corrupt metadata must also suppress older completion checkpoints.
            if (material == "record") fixture.store.applyDoneOverlay(setOf(PAGE)) shouldBe emptySet()
        }
    }

    @Test
    fun `unreadable final commit never triggers rollback against an indeterminate durable commit`() {
        val fixture = Fixture()
        fixture.io.unreadableCommitAfterWrite = true
        shouldThrow<IOException> { fixture.commit("possibly committed", complete = true) }
        fixture.io.page.decodeToString() shouldBe "possibly committed"
        fixture.io.materials.keys.any { it.endsWith(".wip") } shouldBe true
        fixture.io.unreadableCommitAfterWrite = false

        PartialPageStore(fixture.io).load(PAGE)!!.complete shouldBe true
        fixture.io.page.decodeToString() shouldBe "possibly committed"
    }

    @Test
    fun `writer throwing after durable final bytes still reports a committed generation`() {
        val fixture = Fixture()
        fixture.io.afterCreate = { name -> if (name.endsWith(".commit")) throw IOException("provider late failure") }

        val state = fixture.commit("committed result", complete = true)

        state.complete shouldBe true
        fixture.store.load(PAGE) shouldBe state
        fixture.io.page.decodeToString() shouldBe "committed result"
    }

    @Test
    fun `read-only load does not repair an interrupted page`() {
        val fixture = Fixture()
        fixture.io.replace = { bytes ->
            fixture.io.page = bytes.copyOf(1)
            throw ProcessDeath()
        }
        shouldThrow<ProcessDeath> { fixture.commit("partial") }
        fixture.io.replace = null
        val replacements = fixture.io.replacementCount

        shouldThrow<IOException> { fixture.store.load(PAGE, recover = false) }

        fixture.io.replacementCount shouldBe replacements
        fixture.io.page.size shouldBe 1
    }

    @Test
    fun `read-only load never cleans up even a completed recovery marker`() {
        val fixture = Fixture()
        fixture.io.failDelete = true
        val committed = fixture.commit("completed", complete = true)
        val wip = fixture.io.materials.keys.single { it.endsWith(".wip") }
        fixture.io.failDelete = false

        fixture.store.load(PAGE, recover = false) shouldBe committed

        (wip in fixture.io.materials) shouldBe true
        fixture.io.page.decodeToString() shouldBe "completed"
    }

    @Test
    fun `lost commit and wip cannot revive a legacy completed marker for partial output`() {
        val fixture = Fixture()
        fixture.commit("durable partial")
        fixture.io.materials.keys.filter {
            it.endsWith(".wip") || it.endsWith(".commit")
        }.toList().forEach(fixture.io.materials::remove)
        val replacements = fixture.io.replacementCount

        shouldThrow<IOException> { fixture.store.load(PAGE) }

        fixture.io.replacementCount shouldBe replacements
        fixture.io.page.decodeToString() shouldBe "durable partial"
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `path injection invalid original dimensions payload and mask are rejected before writes`() {
        val fixture = Fixture()
        for (page in listOf("../001.png", "dir/001.png", "dir\\001.png", "\u0000001.png", "..", " ")) {
            shouldThrow<IOException> { fixture.commit("output", page = page) }
        }
        shouldThrow<IOException> { fixture.commit("output", original = fixture.original.copy(name = "../secret")) }
        shouldThrow<IOException> { fixture.commit("output", original = fixture.original.copy(sha256 = "bad")) }
        shouldThrow<IOException> { fixture.commit("output", width = 0) }
        shouldThrow<IOException> { fixture.commit("output", payload = "[]") }
        shouldThrow<IOException> { fixture.commit("output", payload = "{truncated") }
        shouldThrow<IOException> { fixture.commit("output", rawMask = mask(width = 5)) }
        shouldThrow<IOException> { fixture.commit("output", rawMask = byteArrayOf(1, 2, 3)) }

        fixture.io.materials.keys shouldBe setOf(ORIGINAL)
        fixture.io.replacementCount shouldBe 0
    }

    @Test
    fun `foreign page journal cannot affect another page even when filenames are copied`() {
        val fixture = Fixture()
        fixture.commit("first partial")
        val originalPrefix = "partial-${PartialPageStore.sha256(PAGE.toByteArray())}-"
        val foreignPage = "other.png"
        val foreignPrefix = "partial-${PartialPageStore.sha256(foreignPage.toByteArray())}-"
        fixture.io.materials.toMap().filterKeys { it.startsWith(originalPrefix) }.forEach { (name, bytes) ->
            fixture.io.materials[name.replace(originalPrefix, foreignPrefix)] = bytes.copyOf()
        }

        fixture.store.applyDoneOverlay(setOf(PAGE, foreignPage, "third.png")) shouldBe setOf("third.png")
        shouldThrow<IOException> { fixture.store.load(foreignPage) }
        fixture.io.page.decodeToString() shouldBe "first partial"
    }

    @Test
    fun `first partial separates its OCR original from the preceding completed output baseline`() {
        val fixture = Fixture()
        fixture.io.page = "previous completed translation".toByteArray()
        val prior = fixture.io.page.copyOf()
        val state = fixture.commit("new partial", baseline = capture(prior), context = "{\"fingerprint\":\"first\"}")

        state.previousOutputSha256 shouldBe PartialPageStore.sha256(prior)
        state.initialBaseline!!.revision shouldBe state.revision
        fixture.store.readInitialBaseline(state)!!.contentEquals(prior) shouldBe true
        fixture.store.load(PAGE, recover = false) shouldBe state
        state.safeContextPayload shouldBe "{\"fingerprint\":\"first\"}"
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
        fixture.io.page.decodeToString() shouldBe "new partial"
        fixture.store.applyDoneOverlay(setOf(PAGE)) shouldBe emptySet()
    }

    @Test
    fun `first transition cancellation restores preceding translation instead of erasing it with the OCR source`() {
        val fixture = Fixture()
        fixture.io.page = "previous completed translation".toByteArray()
        val prior = fixture.io.page.copyOf()
        shouldThrow<CancellationException> {
            fixture.commit("new partial", baseline = capture(prior), cancel = {
                if (fixture.io.page.decodeToString() == "new partial") throw CancellationException("stop")
            })
        }

        fixture.io.page.contentEquals(prior) shouldBe true
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
        fixture.store.load(PAGE) shouldBe null
        fixture.store.applyDoneOverlay(setOf(PAGE)) shouldBe setOf(PAGE)
    }

    @Test
    fun `restart rolls back a truncated first transition to its separate verified baseline`() {
        val fixture = Fixture()
        fixture.io.page = "previous completed translation".toByteArray()
        val prior = fixture.io.page.copyOf()
        fixture.io.replace = { bytes ->
            fixture.io.page = bytes.copyOf(2)
            throw ProcessDeath()
        }
        shouldThrow<ProcessDeath> { fixture.commit("new partial", baseline = capture(prior)) }
        fixture.io.replace = null
        shouldThrow<IOException> { fixture.store.load(PAGE, recover = false) }
        fixture.io.page.size shouldBe 2

        PartialPageStore(fixture.io).load(PAGE) shouldBe null

        fixture.io.page.contentEquals(prior) shouldBe true
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `torn baseline blocks first transition before visible output is touched`() {
        val fixture = Fixture()
        fixture.io.page = "previous completed translation".toByteArray()
        val prior = fixture.io.page.copyOf()
        fixture.io.truncateSuffix = ".baseline"

        shouldThrow<IOException> { fixture.commit("new partial", baseline = capture(prior)) }

        fixture.io.page.contentEquals(prior) shouldBe true
        fixture.io.replacementCount shouldBe 0
        fixture.io.materials.keys.none { it.endsWith(".wip") || it.endsWith(".commit") } shouldBe true
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `stale captured output or digest cannot authorize first journal migration`() {
        val fixture = Fixture()
        fixture.io.page = "current completed translation".toByteArray()
        val old = capture("older completed translation".toByteArray())
        shouldThrow<IOException> { fixture.commit("partial", baseline = old) }
        shouldThrow<IOException> {
            fixture.commit(
                "partial",
                baseline = PartialPageStore.InitialBaseline(fixture.io.page.copyOf(), old.expectedTargetSha256),
            )
        }

        fixture.io.replacementCount shouldBe 0
        fixture.io.page.decodeToString() shouldBe "current completed translation"
        fixture.io.materials.keys shouldBe setOf(ORIGINAL)
    }

    @Test
    fun `subsequent failed generations restore the latest committed partial rather than first baseline`() {
        val fixture = Fixture()
        fixture.io.page = "old completed translation".toByteArray()
        val prior = fixture.io.page.copyOf()
        val partial = fixture.commit(
            "first new partial",
            baseline = capture(prior),
            context = "{\"fingerprint\":\"old-context\"}",
        )
        shouldThrow<CancellationException> {
            fixture.commit(
                "second new partial",
                base = partial.revision,
                context = "{\"fingerprint\":\"new-context\"}",
                cancel = {
                    if (fixture.io.page.decodeToString() == "second new partial") throw CancellationException("stop")
                },
            )
        }

        fixture.store.load(PAGE) shouldBe partial
        fixture.io.page.decodeToString() shouldBe "first new partial"
        fixture.store.readInitialBaseline(partial)!!.contentEquals(prior) shouldBe true
        partial.safeContextPayload shouldBe "{\"fingerprint\":\"old-context\"}"
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `completion inherits immutable baseline while context is explicitly supplied per generation`() {
        val fixture = Fixture()
        fixture.io.page = "old completed translation".toByteArray()
        val prior = fixture.io.page.copyOf()
        val partial = fixture.commit("partial", baseline = capture(prior), context = "{\"fingerprint\":\"old\"}")
        val completed = fixture.commit(
            "complete",
            base = partial.revision,
            complete = true,
            context = "{\"fingerprint\":\"new\"}",
        )

        completed.initialBaseline shouldBe partial.initialBaseline
        fixture.store.readInitialBaseline(completed)!!.contentEquals(prior) shouldBe true
        completed.safeContextPayload shouldBe "{\"fingerprint\":\"new\"}"
        fixture.store.load(PAGE, recover = false) shouldBe completed
        fixture.store.applyDoneOverlay(emptySet()) shouldBe setOf(PAGE)
        shouldThrow<IOException> {
            fixture.commit("cannot reset baseline", base = completed.revision, baseline = capture(fixture.io.page))
        }
        fixture.io.page.decodeToString() shouldBe "complete"
    }

    @Test
    fun `readonly verification rejects a damaged first baseline without altering current partial or original`() {
        val fixture = Fixture()
        fixture.io.page = "old completed translation".toByteArray()
        val partial = fixture.commit("new partial", baseline = capture(fixture.io.page))
        fixture.io.materials[partial.initialBaselineSnapshotName!!] = "damaged baseline".toByteArray()
        val replacements = fixture.io.replacementCount

        shouldThrow<IOException> { fixture.store.load(PAGE, recover = false) }

        fixture.io.replacementCount shouldBe replacements
        fixture.io.page.decodeToString() shouldBe "new partial"
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `legacy reencoded original retains its actual filename and provenance across cancellation and resume`() {
        val fixture = Fixture()
        fixture.io.materials.remove(ORIGINAL)
        val legacyBytes = "legacy reencoded WebP source".toByteArray()
        val legacyName = "001.orig.webp"
        fixture.io.materials[legacyName] = legacyBytes.copyOf()
        val legacy = PartialPageStore.Original(
            legacyName,
            PartialPageStore.sha256(legacyBytes),
            legacyBytes.size.toLong(),
            PartialPageStore.OriginalProvenance.LEGACY_REENCODED,
        )
        fixture.io.page = "existing PNG translation".toByteArray()
        val prior = fixture.io.page.copyOf()
        shouldThrow<CancellationException> {
            fixture.commit("first partial", original = legacy, baseline = capture(prior), cancel = {
                if (fixture.io.page.decodeToString() == "first partial") throw CancellationException("stop")
            })
        }
        fixture.io.page.contentEquals(prior) shouldBe true
        val state = fixture.commit("partial retry", original = legacy, baseline = capture(prior))

        fixture.store.load(PAGE) shouldBe state
        state.original shouldBe legacy
        fixture.io.materials.getValue(legacyName).contentEquals(legacyBytes) shouldBe true
        (ORIGINAL in fixture.io.materials) shouldBe false
        shouldThrow<IOException> {
            fixture.commit(
                "invalid promotion",
                base = state.revision,
                original = legacy.copy(provenance = PartialPageStore.OriginalProvenance.EXACT_BYTES),
            )
        }
        fixture.io.page.decodeToString() shouldBe "partial retry"
    }

    @Test
    fun `existing WebP source with indistinguishable provenance retains its real filename without exact promotion`() {
        val fixture = Fixture()
        fixture.io.materials.remove(ORIGINAL)
        val source = "existing WebP source of unknown encoding history".toByteArray()
        val sourceName = "001.orig.webp"
        fixture.io.materials[sourceName] = source.copyOf()
        val original = PartialPageStore.Original(
            sourceName,
            PartialPageStore.sha256(source),
            source.size.toLong(),
            PartialPageStore.OriginalProvenance.WEBP_COMPATIBLE_UNKNOWN,
        )
        fixture.io.page = "existing upper-case WebP output".toByteArray()
        val baseline = capture(fixture.io.page)
        val state = fixture.commit("partial WebP output", page = "001.WEBP", original = original, baseline = baseline)

        fixture.store.load("001.WEBP") shouldBe state
        state.original.provenance shouldBe PartialPageStore.OriginalProvenance.WEBP_COMPATIBLE_UNKNOWN
        state.original.name shouldBe sourceName
        fixture.io.materials.getValue(sourceName).contentEquals(source) shouldBe true
        fixture.io.materials.keys.none { it == "001.orig.WEBP" || it == ORIGINAL } shouldBe true
        shouldThrow<IOException> {
            fixture.commit(
                "invalid exact promotion",
                page = "001.WEBP",
                base = state.revision,
                original = original.copy(provenance = PartialPageStore.OriginalProvenance.EXACT_BYTES),
            )
        }
        fixture.io.page.decodeToString() shouldBe "partial WebP output"
    }

    @Test
    fun `WebP unknown provenance cannot be attached to a PNG page or a foreign source name`() {
        val fixture = Fixture()
        val unknown = fixture.original.copy(provenance = PartialPageStore.OriginalProvenance.WEBP_COMPATIBLE_UNKNOWN)
        shouldThrow<IOException> { fixture.commit("bad provenance", original = unknown) }
        shouldThrow<IOException> {
            fixture.commit("bad source", page = "001.webp", original = unknown.copy(name = "002.orig.webp"))
        }

        fixture.io.materials.keys shouldBe setOf(ORIGINAL)
        fixture.io.replacementCount shouldBe 0
    }

    @Test
    fun `failed transition equal to baseline leaves legacy completion unchanged`() {
        val fixture = Fixture()
        fixture.io.page = "existing PNG translation".toByteArray()
        val prior = fixture.io.page.copyOf()
        fixture.io.truncateSuffix = ".wip"
        shouldThrow<IOException> { fixture.commit("existing PNG translation", baseline = capture(prior)) }
        fixture.io.truncateSuffix = null

        fixture.store.load(PAGE) shouldBe null
        fixture.store.applyDoneOverlay(setOf(PAGE)) shouldBe setOf(PAGE)
        fixture.io.page.contentEquals(prior) shouldBe true
        fixture.io.materials.getValue(ORIGINAL).decodeToString() shouldBe "exact original bytes"
    }

    @Test
    fun `schema one records without provenance baseline or context remain readable without invented context`() {
        val fixture = Fixture()
        val state = fixture.commit("old-schema partial")
        val record = fixture.io.materials.entries.single { it.key.endsWith(".record") }.value.decodeToString()
        record.contains("\"provenance\"") shouldBe false
        record.contains("\"initialBaseline\"") shouldBe false
        record.contains("\"safeContextPayload\"") shouldBe false

        val reopened = PartialPageStore(fixture.io).load(PAGE)!!
        reopened shouldBe state
        reopened.original.provenance shouldBe PartialPageStore.OriginalProvenance.EXACT_BYTES
        reopened.initialBaseline shouldBe null
        reopened.safeContextPayload shouldBe null
        fixture.store.readInitialBaseline(reopened) shouldBe null
    }

    @Test
    fun `translation context must be a bounded JSON object before any generation is written`() {
        val fixture = Fixture()
        for (context in listOf("[]", "null", "{truncated", "{\"large\":\"${"x".repeat(8192)}\"}")) {
            shouldThrow<IOException> { fixture.commit("output", context = context) }
        }

        fixture.io.materials.keys shouldBe setOf(ORIGINAL)
        fixture.io.replacementCount shouldBe 0
        fixture.io.page.decodeToString() shouldBe "exact original bytes"
    }

    private class ProcessDeath : Error("simulated process termination without catch/finally")

    private class Fixture {
        val io = MemoryIo()
        val original = PartialPageStore.Original(ORIGINAL, PartialPageStore.sha256(io.page), io.page.size.toLong())
        val store = PartialPageStore(io)

        init {
            io.materials[ORIGINAL] = io.page.copyOf()
        }

        fun commit(
            text: String,
            base: String? = null,
            complete: Boolean = false,
            cancel: () -> Unit = {},
            page: String = PAGE,
            original: PartialPageStore.Original = this.original,
            width: Int = 4,
            payload: String = "{\"recognized\":[\"successful group\"],\"retry\":[\"failed group\"]}",
            rawMask: ByteArray = mask(),
            baseline: PartialPageStore.InitialBaseline? = null,
            context: String? = null,
        ): PartialPageStore.State = store.commit(
            pageName = page,
            original = original,
            width = width,
            height = 3,
            progressPayload = payload,
            outputBytes = text.toByteArray(),
            rawMaskBytes = rawMask,
            baseRevision = base,
            complete = complete,
            initialBaseline = baseline,
            safeContextPayload = context,
            checkCancellation = cancel,
        )
    }

    private class MemoryIo : PartialPageStore.Io {
        val materials = linkedMapOf<String, ByteArray>()
        var page = "exact original bytes".toByteArray()
        var truncateSuffix: String? = null
        var replace: ((ByteArray) -> Boolean)? = null
        var afterCreate: ((String) -> Unit)? = null
        var failDelete = false
        var unreadableCommitAfterWrite = false
        var replacementCount = 0

        override fun materialNames(): Set<String> = materials.keys.toSet()

        override fun readMaterial(name: String, maxBytes: Int): ByteArray? {
            if (unreadableCommitAfterWrite && name.endsWith(".commit") &&
                name in materials
            ) {
                throw IOException("temporarily unreadable")
            }
            val bytes = materials[name] ?: return null
            if (bytes.size > maxBytes) throw IOException("too large")
            return bytes.copyOf()
        }

        override fun createMaterial(name: String, bytes: ByteArray): Boolean {
            if (name in materials) return false
            materials[name] =
                if (truncateSuffix?.let(name::endsWith) == true) bytes.copyOf(bytes.size / 2) else bytes.copyOf()
            afterCreate?.invoke(name)
            return true
        }

        override fun deleteMaterial(name: String): Boolean {
            if (failDelete) return false
            materials.remove(name)
            return true
        }

        override fun readPage(name: String, maxBytes: Int): ByteArray? = page.copyOf()

        override fun replacePage(name: String, bytes: ByteArray): Boolean {
            replacementCount++
            return replace?.invoke(bytes) ?: run {
                page = bytes.copyOf()
                true
            }
        }
    }

    companion object {
        private const val PAGE = "001.png"
        private const val ORIGINAL = "001.orig.png"

        private fun capture(bytes: ByteArray): PartialPageStore.InitialBaseline =
            PartialPageStore.InitialBaseline(bytes.copyOf(), PartialPageStore.sha256(bytes))

        // Sufficient PNG header for storage-contract dimensions. Native image decoding is tested
        // separately; the store does not claim to validate PNG pixels or replace the native codec.
        private fun mask(width: Int = 4, height: Int = 3): ByteArray = ByteArray(33).apply {
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10).copyInto(this)
            this[11] = 13
            "IHDR".toByteArray().copyInto(this, 12)
            fun writeInt(offset: Int, value: Int) {
                for (i in 0..3) this[offset + i] = (value ushr (24 - i * 8)).toByte()
            }
            writeInt(16, width)
            writeInt(20, height)
        }
    }
}
