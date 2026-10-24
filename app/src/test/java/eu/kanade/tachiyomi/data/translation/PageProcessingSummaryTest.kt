package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Modifier
import java.security.MessageDigest

class PageProcessingSummaryTest {
    @Test
    fun `explicit no text and both preservation stages have different explanations`() {
        outcome(PageProgress(100, 100, emptyList(), noTextDetected = true), 0, skipped = true) shouldBe
            PageProcessingOutcome(PageProcessingReason.NO_TEXT, PageProcessingCounts(0, 0, 0, 0, 0, 0, 0))
        outcome(progress(region(0, RegionState.INTENTIONALLY_KEPT, policyKept = true)), 1, skipped = true) shouldBe
            PageProcessingOutcome(PageProcessingReason.OCR_POLICY_KEPT, PageProcessingCounts(1, 1, 1, 1, 0, 0, 0))
        for (text in listOf("123", "unchanged response", "regex excluded", "private source")) {
            outcome(progress(region(0, RegionState.INTENTIONALLY_KEPT, text = text)), 1, skipped = true) shouldBe
                PageProcessingOutcome(PageProcessingReason.RESPONSE_FILTERED, PageProcessingCounts(1, 1, 0, 0, 1, 0, 0))
        }
        outcome(
            progress(
                region(0, RegionState.INTENTIONALLY_KEPT, policyKept = true),
                region(1, RegionState.INTENTIONALLY_KEPT),
            ),
            2,
        ) shouldBe PageProcessingOutcome(PageProcessingReason.MIXED_KEPT, PageProcessingCounts(2, 2, 1, 1, 1, 0, 0))
    }

    @Test
    fun `final counts include prior translated kept and still pending regions`() {
        val regions = arrayOf(
            region(0, RegionState.TRANSLATED),
            region(1, RegionState.INTENTIONALLY_KEPT, policyKept = true),
            region(2, RegionState.INTENTIONALLY_KEPT),
            region(3, RegionState.OCR_PENDING),
        )
        outcome(progress(*regions), 4) shouldBe
            PageProcessingOutcome(PageProcessingReason.PARTIAL, PageProcessingCounts(4, 4, 1, 1, 1, 1, 1))
        outcome(progress(*regions.take(3).toTypedArray()), 3) shouldBe
            PageProcessingOutcome(PageProcessingReason.TRANSLATED, PageProcessingCounts(3, 3, 1, 1, 1, 1, 0))
        outcome(progress(region(0, RegionState.TRANSLATED)), null).counts?.detectedLines shouldBe null
    }

    @Test
    fun `unknown or inconsistent results are never inferred from absence or zero statistics`() {
        val translated = progress(region(0, RegionState.TRANSLATED))
        val pending = progress(region(0, RegionState.OCR_PENDING))
        val noText = PageProgress(100, 100, emptyList(), noTextDetected = true)
        val unknown = PageProcessingOutcome(PageProcessingReason.UNKNOWN)
        outcome(null, 0, skipped = true) shouldBe unknown
        outcome(null, null) shouldBe unknown
        outcome(translated, 1, skipped = true) shouldBe unknown
        outcome(pending, 1, skipped = true) shouldBe unknown
        outcome(translated, 0) shouldBe unknown
        outcome(translated, -1) shouldBe unknown
        outcome(translated, MAX_SUMMARY_LINES + 1) shouldBe unknown
        outcome(noText, 1, skipped = true) shouldBe unknown
        outcome(progress(), 0) shouldBe unknown
        outcome(translated.copy(schemaVersion = 999), 1) shouldBe unknown
        outcome(translated.copy(noTextDetected = true), 1) shouldBe unknown
        outcome(translated.copy(width = 0), 1) shouldBe unknown
    }

    @Test
    fun `outcome and persisted record retain only fixed scalar data`() {
        val source = "private source 123"
        val target = "private translated response"
        val region = region(0, RegionState.TRANSLATED, text = source).copy(translatedText = target)
        val result = outcome(progress(region), 1)
        val bytes = PageProcessingSummaryCodec.encode(record(result))
        val text = bytes.toString(Charsets.UTF_8)
        val privateValues = listOf(
            source,
            target,
            PAGE_NAME,
            "intentionallyKept",
            "translatedText",
            "quad",
            "direction",
        )
        for (privateValue in privateValues) {
            text.contains(privateValue) shouldBe false
            result.toString().contains(privateValue) shouldBe false
        }
        PageProcessingOutcome::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet() shouldBe
            setOf("reason", "counts")
        (bytes.size < PageProcessingSummaryCodec.MAX_BYTES) shouldBe true
        PageProcessingSummaryCodec.decode(bytes) shouldBe record(result)
        val unknown = record(PageProcessingOutcome(PageProcessingReason.UNKNOWN))
        PageProcessingSummaryCodec.decode(PageProcessingSummaryCodec.encode(unknown)) shouldBe unknown
        val nullableBinding = record(result).copy(
            binding = binding().copy(sourceSha256 = null, contextSha256 = null, journalRevision = null),
        )
        PageProcessingSummaryCodec.decode(PageProcessingSummaryCodec.encode(nullableBinding)) shouldBe nullableBinding
    }

    @Test
    fun `names hash the full UTF8 filename in a separate strict namespace`() {
        for (name in listOf(PAGE_NAME, "한글.001.png", "001.png", "001.jpg", "x\ny.png")) {
            val exact = PageProcessingSummaryCodec.exactName(name)
            exact shouldBe "page-summary-${digest(name.toByteArray(Charsets.UTF_8))}.summary"
            PageProcessingSummaryCodec.isSummaryName(exact) shouldBe true
            exact.endsWith(".json") shouldBe false
            PageProcessingSummaryCodec.isSummaryName("$exact.extra") shouldBe false
            PageProcessingSummaryCodec.isSummaryName("/$exact") shouldBe false
            PageProcessingSummaryCodec.isSummaryName(exact.uppercase()) shouldBe false
        }
        PageProcessingSummaryCodec.isSummaryName("page-summary-${"g".repeat(64)}.summary") shouldBe false
        PageProcessingSummaryCodec.isSummaryName("partial-${"a".repeat(64)}.json") shouldBe false
        val pngName = PageProcessingSummaryCodec.exactName("001.png")
        (pngName == PageProcessingSummaryCodec.exactName("001.jpg")) shouldBe false
    }

    @Test
    fun `truncation checksums oversized input duplicate members and alternate JSON are rejected`() {
        val bytes = PageProcessingSummaryCodec.encode(record())
        for (length in bytes.indices) {
            withClue("length $length") { PageProcessingSummaryCodec.decode(bytes.copyOf(length)) shouldBe null }
        }
        val text = bytes.toString(Charsets.UTF_8)
        PageProcessingSummaryCodec.decode(text.replace("TRANSLATED", "PARTIAL").toByteArray()) shouldBe null
        PageProcessingSummaryCodec.decode(
            text.replace("\"recordedAtEpochMs\":1700000000000", "\"recordedAtEpochMs\":1700000000001").toByteArray(),
        ) shouldBe null
        PageProcessingSummaryCodec.decode(" $text".toByteArray()) shouldBe null
        PageProcessingSummaryCodec.decode((text.dropLast(1) + ",\"schemaVersion\":1}").toByteArray()) shouldBe null
        PageProcessingSummaryCodec.decode((text + "extra").toByteArray()) shouldBe null
        PageProcessingSummaryCodec.decode(ByteArray(PageProcessingSummaryCodec.MAX_BYTES + 1)) shouldBe null
        PageProcessingSummaryCodec.decode("{}".toByteArray()) shouldBe null
        PageProcessingSummaryCodec.decode("[]".toByteArray()) shouldBe null
        PageProcessingSummaryCodec.decode("null".toByteArray()) shouldBe null
        PageProcessingSummaryCodec.decode(
            text.replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray(),
        ) shouldBe null
    }

    @Test
    fun `valid checksums cannot legitimize invalid schema hashes enums counters or freeform fields`() {
        val bytes = PageProcessingSummaryCodec.encode(record())
        val invalidRecords: List<(MutableMap<String, JsonElement>) -> Unit> = listOf(
            { it["recordedAtEpochMs"] = JsonPrimitive(-1) },
            { it["source"] = JsonPrimitive("private source") },
            { record -> nested(record, "binding") { it["pageNameSha256"] = JsonPrimitive("a".repeat(63)) } },
            { record -> nested(record, "binding") { it["sourceSha256"] = JsonPrimitive("G".repeat(64)) } },
            { record -> nested(record, "binding") { it["displaySha256"] = JsonPrimitive("A".repeat(64)) } },
            { record -> nested(record, "binding") { it["sourceTokenSha256"] = JsonPrimitive("x".repeat(64)) } },
            { record -> nested(record, "binding") { it["contextSha256"] = JsonPrimitive("https://fixture.invalid") } },
            { record -> nested(record, "binding") { it["journalRevision"] = JsonPrimitive("path/to/page") } },
            { record -> nested(record, "outcome") { it["reason"] = JsonPrimitive("NUMERIC_OR_IDENTICAL") } },
            { record -> nested(record, "outcome") { it["reason"] = JsonPrimitive("UNKNOWN") } },
            { record -> nested(record, "outcome") { it["reasonText"] = JsonPrimitive("private reason") } },
            { record ->
                nested(record, "outcome") { outcome ->
                    nested(outcome, "counts") { it["translatedRegions"] = JsonPrimitive(-1) }
                }
            },
            { record ->
                nested(record, "outcome") { outcome ->
                    nested(outcome, "counts") { it["regions"] = JsonPrimitive(MAX_SUMMARY_REGIONS + 1) }
                }
            },
            { record ->
                nested(record, "outcome") { outcome ->
                    nested(outcome, "counts") { it["detectedLines"] = JsonPrimitive(MAX_SUMMARY_LINES + 1) }
                }
            },
            { record ->
                nested(record, "outcome") { outcome ->
                    nested(outcome, "counts") { it["ocrPolicyKeptLines"] = JsonPrimitive(2) }
                }
            },
            { record ->
                nested(record, "outcome") { outcome ->
                    nested(outcome, "counts") { it["pendingRegions"] = JsonPrimitive(1) }
                }
            },
        )
        invalidRecords.forEachIndexed { index, mutate ->
            withClue("mutation $index") { PageProcessingSummaryCodec.decode(resign(bytes, mutate)) shouldBe null }
        }
        shouldThrow<IllegalArgumentException> {
            PageProcessingSummaryCodec.encode(
                record().copy(outcome = PageProcessingOutcome(PageProcessingReason.TRANSLATED)),
            )
        }
    }

    @Test
    fun `store verifies the filename every binding field and readback without completion semantics`() {
        val files = mutableMapOf<String, ByteArray>()
        val store = store(files)
        val saved = record()
        store.writeBestEffort(PAGE_NAME, saved) shouldBe true
        store.read(PAGE_NAME, saved.binding) shouldBe saved
        val mismatches = listOf(
            saved.binding.copy(pageNameSha256 = "9".repeat(64)),
            saved.binding.copy(sourceSha256 = null),
            saved.binding.copy(sourceSha256 = "9".repeat(64)),
            saved.binding.copy(displaySha256 = "9".repeat(64)),
            saved.binding.copy(sourceTokenSha256 = "9".repeat(64)),
            saved.binding.copy(journalRevision = null),
            saved.binding.copy(journalRevision = "22222222-2222-2222-2222-222222222222"),
            saved.binding.copy(contextSha256 = null),
            saved.binding.copy(contextSha256 = "9".repeat(64)),
        )
        mismatches.forEach { store.read(PAGE_NAME, it) shouldBe null }
        store.read("another.png", saved.binding) shouldBe null
        store.writeBestEffort("another.png", saved) shouldBe false
        files[PageProcessingSummaryCodec.exactName(PAGE_NAME)] = "corrupt".toByteArray()
        store.read(PAGE_NAME, saved.binding) shouldBe null
        files[PageProcessingSummaryCodec.exactName(PAGE_NAME)] = ByteArray(PageProcessingSummaryCodec.MAX_BYTES + 1)
        store.read(PAGE_NAME, saved.binding) shouldBe null
        store.invalidateBestEffort(PAGE_NAME) shouldBe true
        files.isEmpty() shouldBe true
    }

    @Test
    fun `ordinary read write delete and readback failures remain optional`() {
        val saved = record()
        val files = mutableMapOf(
            PageProcessingSummaryCodec.exactName(PAGE_NAME) to PageProcessingSummaryCodec.encode(saved),
        )
        PageProcessingSummaryStore({ throw IOException("synthetic read") }, { _, _ -> }, {})
            .read(PAGE_NAME, saved.binding) shouldBe null
        val events = mutableListOf<String>()
        val failure = PageProcessingSummaryStore(
            readBytes = { files[it] },
            writeBytes = { _, _ ->
                events += "write"
                throw IOException("synthetic write")
            },
            delete = {
                events += "delete"
                files.remove(it)
            },
        )
        failure.writeBestEffort(PAGE_NAME, saved) shouldBe false
        events shouldBe listOf("delete", "write")
        files.isEmpty() shouldBe true

        val deletionFailure = PageProcessingSummaryStore({ files[it] }, { name, bytes -> files[name] = bytes }, {
            throw IOException("synthetic delete")
        })
        deletionFailure.invalidateBestEffort(PAGE_NAME) shouldBe false
        deletionFailure.writeBestEffort(PAGE_NAME, saved) shouldBe true
        deletionFailure.read(PAGE_NAME, saved.binding) shouldBe saved

        PageProcessingSummaryStore({ null }, { _, _ -> }, {}).writeBestEffort(PAGE_NAME, saved) shouldBe false
        PageProcessingSummaryStore({ "corrupt".toByteArray() }, { _, _ -> }, {})
            .writeBestEffort(PAGE_NAME, saved) shouldBe false
        PageProcessingSummaryStore({ throw IOException("synthetic readback") }, { _, _ -> }, {})
            .writeBestEffort(PAGE_NAME, saved) shouldBe false
    }

    @Test
    fun `failed invalidation and replacement can retain only the earlier timestamped record`() {
        val earlier = record().copy(recordedAtEpochMs = 10)
        val bytes = PageProcessingSummaryCodec.encode(earlier)
        val store = PageProcessingSummaryStore({ bytes }, { _, _ -> throw IOException("synthetic write") }, {
            throw IOException("synthetic delete")
        })
        store.writeBestEffort(PAGE_NAME, earlier.copy(recordedAtEpochMs = 20)) shouldBe false
        store.read(PAGE_NAME, earlier.binding)?.recordedAtEpochMs shouldBe 10
    }

    @Test
    fun `cancellation and fatal failures propagate from every IO callback`() {
        val saved = record()
        for (failure in listOf(CancellationException("synthetic cancellation"), OutOfMemoryError("synthetic fatal"))) {
            for (operation in listOf("read", "write", "delete", "readback")) {
                val store = PageProcessingSummaryStore(
                    readBytes = { if (operation == "read" || operation == "readback") throw failure else null },
                    writeBytes = { _, _ -> if (operation == "write") throw failure },
                    delete = { if (operation == "delete") throw failure },
                )
                val thrown = shouldThrow<Throwable> {
                    when (operation) {
                        "read" -> store.read(PAGE_NAME, saved.binding)
                        "delete" -> store.invalidateBestEffort(PAGE_NAME)
                        else -> store.writeBestEffort(PAGE_NAME, saved)
                    }
                }
                (thrown === failure) shouldBe true
            }
        }
    }

    private fun outcome(progress: PageProgress?, detectedLines: Int?, skipped: Boolean = false) =
        PageProcessingOutcome.fromProgress(progress, detectedLines, skipped)

    private fun progress(vararg regions: RegionProgress) = PageProgress(100, 100, regions.toList())

    private fun region(id: Int, state: RegionState, policyKept: Boolean = false, text: String = "private source") =
        RegionProgress(
            id = id,
            state = state,
            lines = listOf(
                OcrLineProgress(
                    quad = listOf(Pt(1f, 1f), Pt(20f, 1f), Pt(20f, 20f), Pt(1f, 20f)),
                    score = 1f,
                    direction = "h",
                    text = if (state == RegionState.OCR_PENDING) "" else text,
                    intentionallyKept = policyKept,
                ),
            ),
            direction = "h",
            translatedText = if (state == RegionState.TRANSLATED) "private target" else "",
        )

    private fun binding() = PageProcessingBinding(
        pageNameSha256 = digest(PAGE_NAME.toByteArray(Charsets.UTF_8)),
        sourceSha256 = "1".repeat(64),
        displaySha256 = "2".repeat(64),
        sourceTokenSha256 = "3".repeat(64),
        journalRevision = "11111111-1111-1111-1111-111111111111",
        contextSha256 = "4".repeat(64),
    )

    private fun record(outcome: PageProcessingOutcome = outcome(progress(region(0, RegionState.TRANSLATED)), 1)) =
        PageProcessingRecord(binding(), outcome, recordedAtEpochMs = 1_700_000_000_000)

    private fun store(files: MutableMap<String, ByteArray>) = PageProcessingSummaryStore(
        readBytes = { files[it] },
        writeBytes = { name, bytes -> files[name] = bytes },
        delete = { files.remove(it) },
    )

    private fun resign(bytes: ByteArray, mutate: (MutableMap<String, JsonElement>) -> Unit): ByteArray {
        val envelope = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.toMutableMap()
        val record = envelope.getValue("record").jsonObject.toMutableMap().also(mutate)
        val payload = JsonObject(record)
        envelope["record"] = payload
        envelope["checksumSha256"] = JsonPrimitive(digest(payload.toString().toByteArray(Charsets.UTF_8)))
        return JsonObject(envelope).toString().toByteArray(Charsets.UTF_8)
    }

    private fun nested(
        parent: MutableMap<String, JsonElement>,
        key: String,
        mutate: (MutableMap<String, JsonElement>) -> Unit,
    ) {
        parent[key] = JsonObject(parent.getValue(key).jsonObject.toMutableMap().also(mutate))
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PAGE_NAME = "private-page.001.png"
    }
}
