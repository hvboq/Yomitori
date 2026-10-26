package eu.kanade.tachiyomi.data.nightread

import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.nightread.NightRead
import li.joye.yakuyomi.nightread.NightTier
import org.junit.jupiter.api.Test

/** Numeric stamps remain migration metadata; current policy proof pairs establish rules freshness. */
class NightRulesVersionTest {

    private val page = "012.jpg"
    private val std = NightPages.levelName(page, NightLevel.STANDARD)
    private val more = NightPages.levelName(page, NightLevel.MORE)
    private val l1 = NightPages.tierName(page, NightTier.L1)
    private val l2 = NightPages.tierName(page, NightTier.L2)
    private val l3 = NightPages.tierName(page, NightTier.L3)
    private val legacy = NightPages.legacyName(page)
    private val r1 = NightPages.rulesStampName(page, 1)
    private val r2 = NightPages.rulesStampName(page, 2)

    private fun stdOf(p: String) = NightPages.levelName(p, NightLevel.STANDARD)
    private fun stampOf(p: String, v: Int) = NightPages.rulesStampName(p, v)

    @Test
    fun `版本號來自 nightread、從 1 起算`() {
        NightPages.RULES_VERSION shouldBe NightRead.RULES_VERSION
        (NightPages.RULES_VERSION >= 1) shouldBe true
        NightPages.rulesStampName(page) shouldBe NightPages.rulesStampName(page, NightRead.RULES_VERSION)
    }

    @Test
    fun `記號命名與解析：完整頁檔名加 night rules 加版本`() {
        r1 shouldBe "012.jpg.night.rules1"
        NightPages.rulesStampName(page, 12) shouldBe "012.jpg.night.rules12"
        NightPages.parseRulesStamp(r1) shouldBe (page to 1)
        NightPages.parseRulesStamp("012.jpg.night.rules12") shouldBe (page to 12)
        NightPages.isRulesStamp(r1) shouldBe true
        // 頁名本身含 night 也拆得對
        NightPages.parseRulesStamp(NightPages.rulesStampName("a.night.jpg", 3)) shouldBe ("a.night.jpg" to 3)
        // 反例：沒有版本號、非數字、暫存、夜讀檔、素材、空頁名
        listOf(
            "012.jpg.night.rules",
            "012.jpg.night.rulesx",
            "012.jpg.night.rules1.tmp",
            "012.jpg.night.rules1.webp",
            "012.jpg.night.rules-1",
            ".night.rules1",
            std,
            legacy,
            "012.json",
            "012.orig.jpg",
        ).forEach {
            NightPages.isRulesStamp(it) shouldBe false
            NightPages.parseRulesStamp(it) shouldBe null
        }
    }

    @Test
    fun `記號不是夜讀檔、不是重繪素材、reader 查找不認它`() {
        listOf(r1, r2).forEach {
            NightPages.isNightArtifact(it) shouldBe false
            NightPages.isFinalNightFile(it) shouldBe false
            NightPages.isNightTmp(it) shouldBe false
            NightPages.pageOf(it) shouldBe null
            OriginalMaterial.isOriginalMaterial(it) shouldBe false
        }
        // 有沒有記號，兩個檔位查到的檔都一樣
        NightLevel.entries.forEach { level ->
            NightPages.resolveName(setOf(std, more, r1), page, level) shouldBe
                NightPages.resolveName(setOf(std, more), page, level)
        }
        NightPages.markerName(setOf(std, r1), page) shouldBe null
        NightPages.leftovers(setOf(std, more, r1, r2)) shouldBe emptySet()
    }

    @Test
    fun `頁的版本：三檔格式、舊版單檔一律 0，殘留的記號不替它們背書`() {
        NightPages.pageRulesVersion(setOf(l1), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(l1, l2, l3), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(l1, r1), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(legacy), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(legacy, r1), page) shouldBe 0
    }

    @Test
    fun `頁的版本：沒有可顯示的夜讀版＝null（只剩記號、孤兒 more l2 l3 都不算）`() {
        NightPages.pageRulesVersion(emptySet(), page) shouldBe null
        NightPages.pageRulesVersion(setOf(r1), page) shouldBe null
        NightPages.pageRulesVersion(setOf(more, l2, l3, r1), page) shouldBe null
        NightPages.pageRulesVersion(setOf("$std.tmp", r1), page) shouldBe null
    }

    @Test
    fun `整章：沒有可顯示的夜讀版就不算可更新（只剩記號、孤兒、暫存、素材）`() {
        NightPages.summarize(emptyList(), current = 1) shouldBe
            NightPages.ChapterSummary(NightPages.ChapterState.NONE, outdated = false)
        NightPages.summarize(
            listOf(
                stampOf("001.jpg", 1),
                NightPages.levelName("001.jpg", NightLevel.MORE),
                NightPages.tierName("002.jpg", NightTier.L2),
                "${stdOf("003.jpg")}.tmp",
                "003.json",
                "003.mask.png",
            ),
            current = 1,
        ) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.NONE, outdated = false)
    }

    @Test
    fun `可清的記號：孤兒（同頁沒有 std）與被更大版本取代的`() {
        val names = setOf(
            stdOf("001.jpg"),
            stampOf("001.jpg", 1), // 正常：留
            stampOf("002.jpg", 1), // 孤兒：作廢後 std 沒了
            NightPages.tierName("003.jpg", NightTier.L1),
            stampOf("003.jpg", 1), // 只有三檔格式：記號不是替它寫的
            stdOf("004.jpg"),
            stampOf("004.jpg", 1),
            stampOf("004.jpg", 2), // 被取代：留最大
        )
        NightPages.staleStamps(names) shouldBe
            setOf(stampOf("002.jpg", 1), stampOf("003.jpg", 1), stampOf("004.jpg", 1))
    }

    @Test
    fun `已經不在這章的頁留下的夜讀檔與記號`() {
        val names = listOf(
            stdOf("001.jpg"), stampOf("001.jpg", 1),
            stdOf("002.jpg"), NightPages.levelName("002.jpg", NightLevel.MORE), stampOf("002.jpg", 1),
            NightPages.legacyName("003.png"),
            "${stdOf("002.jpg")}.tmp", // 暫存另外清，不在這裡
            "002.json",
            "002.orig.jpg",
        )
        NightPages.filesOfMissingPages(names, setOf("001.jpg", "003.jpg")) shouldBe setOf(
            stdOf("002.jpg"),
            NightPages.levelName("002.jpg", NightLevel.MORE),
            stampOf("002.jpg", 1),
            NightPages.legacyName("003.png"), // 頁是 003.jpg、不是 003.png
        )
        NightPages.filesOfMissingPages(names, setOf("001.jpg", "002.jpg", "003.png")) shouldBe emptySet()
    }

    @Test
    fun `列目錄不可信時不清不在本章的頁（列出來是空的不能把整章夜讀版刪光）`() {
        val start = setOf(stdOf("001.jpg"), stampOf("001.jpg", 1), stdOf("002.jpg"), stampOf("002.jpg", 1))
        NightPages.cleanupPlan(start, pageNames = null) shouldBe emptySet()
        NightPages.cleanupPlan(start, pageNames = emptySet()) shouldBe start
        // 沒有頁圖、但列得到素材夾＝真的空了：清掉，章節列就不會一直「可更新」
        NightPages.cleanupPlan(start + NightPages.legacyName("003.jpg"), pageNames = setOf(NightPages.DIR)) shouldBe
            start + NightPages.legacyName("003.jpg")
    }

    @Test
    fun `cleanupPlan 只動夜讀檔與記號，探測檔與素材不碰`() {
        val names = setOf(
            NightPages.STAMP_PROBE,
            "001.json",
            "001.mask.png",
            "001.orig.jpg",
            "${stdOf("001.jpg")}.tmp",
            stdOf("001.jpg"),
            stampOf("001.jpg", 1),
        )
        NightPages.cleanupPlan(names, setOf("001.jpg")) shouldBe setOf("${stdOf("001.jpg")}.tmp")
        NightPages.isRulesStamp(NightPages.STAMP_PROBE) shouldBe false
        NightPages.isNightArtifact(NightPages.STAMP_PROBE) shouldBe false
        OriginalMaterial.isOriginalMaterial(NightPages.STAMP_PROBE) shouldBe false
    }

    @Test
    fun `numeric rules stamps cannot upgrade any unproven image`() {
        listOf(setOf(std), setOf(std, more), setOf(std, l1, legacy)).forEach { images ->
            val stamped = images + setOf(r1, r2, NightPages.rulesStampName(page, 999999))
            NightPages.pageRulesVersion(stamped, page) shouldBe 0
            NightPages.isPageOutdated(stamped, page) shouldBe true
            NightPages.needsRender(stamped, page) { true } shouldBe true
            NightPages.summarize(stamped) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.LEGACY, true)
        }
    }

    @Test
    fun `current complete pair establishes rules version independently of numeric stamps`() {
        val current = setOf(std, more) + NightPolicy.names(page, "1".repeat(32), moreDistinct = true)
        NightPages.pageRulesVersion(current, page) shouldBe NightPages.RULES_VERSION
        NightPages.pageRulesVersion(current + setOf(r1, r2), page) shouldBe NightPages.RULES_VERSION
        NightPages.summarize(current) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.READY, false)
        NightPages.needsRender(current, page) { true } shouldBe false
        NightPages.needsRender(current, page) { false } shouldBe true
        NightPages.isPageOutdated(current, page, current = NightPages.RULES_VERSION + 1) shouldBe true
    }

    @Test
    fun `wrong version or policy pair remains a migration candidate`() {
        val proof = NightPolicy.names(page, "2".repeat(32), moreDistinct = true)
        val wrongVersion = proof.map {
            it.replace(".rules${NightPages.RULES_VERSION}.", ".rules${NightPages.RULES_VERSION + 1}.")
        }
        val policy = checkNotNull(NightPolicy.parse(proof.first())).policy
        val wrongPolicy = proof.map { it.replace(".$policy.", ".other-policy.") }
        listOf(wrongVersion, wrongPolicy, proof.take(1)).forEach { stamps ->
            val names = setOf(std, more) + stamps
            NightPages.resolveName(names, page, NightLevel.STANDARD) shouldBe null
            NightPages.summarize(names) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.LEGACY, true)
            NightPages.needsRender(names, page) { true } shouldBe true
        }
    }

    @Test
    fun `chapter with proven and legacy pages advertises ready and still offers migration`() {
        val current =
            NightPages.currentNames("001.jpg").toSet() +
                NightPolicy.names("001.jpg", "3".repeat(32), moreDistinct = true)
        val legacyPage = setOf(stdOf("002.jpg"), stampOf("002.jpg", NightPages.RULES_VERSION))
        NightPages.summarize(current + legacyPage) shouldBe
            NightPages.ChapterSummary(NightPages.ChapterState.READY, true)
        val allCurrent = current + legacyPage + NightPages.levelName("002.jpg", NightLevel.MORE) +
            NightPolicy.names("002.jpg", "4".repeat(32), moreDistinct = true)
        NightPages.summarize(allCurrent) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.READY, false)
    }

    @Test
    fun `all image and proof subsets keep reader and migration decisions coherent`() {
        val proofs = NightPolicy.names(page, "5".repeat(32), moreDistinct = true)
        val all = listOf(std, more, l1, l2, l3, legacy, r1, r2) + proofs
        for (mask in 0 until (1 shl all.size)) {
            val names = all.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val proven = names.containsAll(listOf(std, more) + proofs)
            (NightPages.resolveName(names, page, NightLevel.STANDARD) != null) shouldBe proven
            NightPages.needsRender(names, page) { true } shouldBe !proven
            NightPages.summarize(names).outdated shouldBe NightPages.isPageOutdated(names, page)
            NightPages.pageRulesVersion(names - NightPages.staleStamps(names), page) shouldBe
                NightPages.pageRulesVersion(names, page)
        }
    }

    @Test
    fun `metadata migration produces complete proofs and a second pass has no pending work`() {
        val pages = listOf("001.jpg", "002.jpg", "003.jpg")
        val names = mutableSetOf(
            stdOf("001.jpg"),
            stampOf("001.jpg", NightPages.RULES_VERSION),
            NightPages.tierName("002.jpg", NightTier.L1),
            NightPages.tierName("002.jpg", NightTier.L3),
            NightPages.legacyName("003.jpg"),
            "001.orig.jpg",
            "001.mask.png",
            "001.json",
        )
        val pending = pages.filter { NightPages.needsRender(names, it) { true } }
        pending shouldBe pages
        pending.forEachIndexed { index, p ->
            names += NightPages.currentNames(p)
            names += NightPolicy.names(p, (index + 6).toString().repeat(32), moreDistinct = true)
            names -= NightPages.oldFormatNames(p).toSet()
        }
        NightPages.summarize(names) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.READY, false)
        pages.filter { NightPages.needsRender(names, it) { true } } shouldBe emptyList()
        names.containsAll(listOf("001.orig.jpg", "001.mask.png", "001.json")) shouldBe true
    }
}
