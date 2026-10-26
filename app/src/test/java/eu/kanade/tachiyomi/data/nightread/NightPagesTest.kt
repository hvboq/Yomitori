package eu.kanade.tachiyomi.data.nightread

import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.nightread.NightTier
import org.junit.jupiter.api.Test

/** Filename/cleanup compatibility remains; only complete policy pairs authorize reader output. */
class NightPagesTest {

    private val page = "012.jpg"
    private val std = "012.jpg.night.std.webp"
    private val more = "012.jpg.night.more.webp"
    private val l1 = "012.jpg.night.l1.webp"
    private val l2 = "012.jpg.night.l2.webp"
    private val l3 = "012.jpg.night.l3.webp"
    private val legacy = "012.jpg.night.webp"

    private fun resolveBoth(names: Set<String>) = NightLevel.entries.map { NightPages.resolveName(names, page, it) }

    @Test
    fun `檔名規則：完整頁檔名加尾綴、暫存＝最終名加 tmp`() {
        NightPages.levelName(page, NightLevel.STANDARD) shouldBe std
        NightPages.levelName(page, NightLevel.MORE) shouldBe more
        NightPages.tierName(page, NightTier.L1) shouldBe l1
        NightPages.tierName(page, NightTier.L2) shouldBe l2
        NightPages.tierName(page, NightTier.L3) shouldBe l3
        NightPages.legacyName(page) shouldBe legacy
        NightPages.tmpName(std) shouldBe "$std.tmp"
    }

    @Test
    fun `刪除順序：標記照查找優先序反過來（舊版單檔、l1、std），附屬檔最後`() {
        NightPages.currentNames(page) shouldBe listOf(std, more)
        NightPages.oldFormatNames(page) shouldBe listOf(l1, l2, l3, legacy)
        NightPages.artifactNames(page) shouldBe listOf(legacy, l1, std, more, l2, l3)
    }

    @Test
    fun `產品檔位：標準＝L2、更多＝L3，交給引擎的順序標準在前`() {
        NightLevel.entries shouldBe listOf(NightLevel.STANDARD, NightLevel.MORE)
        NightLevel.engineTiers shouldBe listOf(NightTier.L2, NightTier.L3)
        NightLevel.ofTier(NightTier.L2) shouldBe NightLevel.STANDARD
        NightLevel.ofTier(NightTier.L3) shouldBe NightLevel.MORE
        NightLevel.ofTier(NightTier.L1) shouldBe null
        NightLevel.entries.map { it.fileKey } shouldBe listOf("std", "more")
    }

    @Test
    fun `isNightArtifact 正例：兩檔、三檔各檔位、舊版單檔、它們的暫存`() {
        listOf(std, more, l1, l2, l3, legacy).forEach {
            NightPages.isNightArtifact(it) shouldBe true
            NightPages.isNightArtifact("$it.tmp") shouldBe true
            NightPages.isNightTmp("$it.tmp") shouldBe true
            NightPages.isNightTmp(it) shouldBe false
            NightPages.isFinalNightFile(it) shouldBe true
            NightPages.isFinalNightFile("$it.tmp") shouldBe false
            NightPages.pageOf(it) shouldBe page
        }
    }

    @Test
    fun `isNightArtifact 反例：不誤中重繪素材與頁圖`() {
        listOf(
            "012.orig.webp",
            "012.orig.jpg",
            "012.orig.png",
            "012.orig.jpg.tmp",
            "012.orig.webp.tmp",
            "012.mask.png",
            "012.json",
            "012.jpg",
            "012.webp",
            "012.jpg.night.l4.webp",
            "012.jpg.night.standard.webp",
            "012.jpg.night.mores.webp",
            "012.jpg.nightly.webp",
            "012.jpg.night.l1.png",
            "012.jpg.night.std.png",
            "012.jpg.night.std.webp.bak",
            "012.jpg.night.webp.tmp.old",
        ).forEach {
            NightPages.isNightArtifact(it) shouldBe false
        }
    }

    @Test
    fun `什麼都沒有、暫存、別頁的檔都不算`() {
        resolveBoth(emptySet()) shouldBe listOf(null, null)
        resolveBoth(setOf("$std.tmp", "$l1.tmp", "$legacy.tmp")) shouldBe listOf(null, null)
        resolveBoth(
            setOf("013.jpg.night.std.webp", "012.png.night.std.webp", "013.jpg.night.l1.webp", "012.png.night.webp"),
        ) shouldBe listOf(null, null)
    }

    @Test
    fun `可清的殘留：被 std 取代的舊格式、孤兒 more、孤兒 l2 l3`() {
        // 有 std：同頁三檔與舊版單檔都永遠不會被顯示
        NightPages.leftovers(setOf(std, more, l1, l2, l3, legacy)) shouldBe setOf(l1, l2, l3, legacy)
        // 沒有 std：more 是孤兒；有 l1 的三檔照留
        NightPages.leftovers(setOf(more, l1, l3)) shouldBe setOf(more)
        // 沒有 std 也沒有 l1：l2／l3 是孤兒，舊版單檔照留
        NightPages.leftovers(setOf(l2, l3, legacy)) shouldBe setOf(l2, l3)
        // 完整的各格式都不動
        NightPages.leftovers(setOf(std, more)) shouldBe emptySet()
        NightPages.leftovers(setOf(l1, l2, l3)) shouldBe emptySet()
        NightPages.leftovers(setOf(legacy)) shouldBe emptySet()
        // 每頁各自判斷
        val other = "013.jpg"
        NightPages.leftovers(
            setOf(std, l1, NightPages.tierName(other, NightTier.L1), NightPages.levelName(other, NightLevel.MORE)),
        ) shouldBe setOf(l1, NightPages.levelName(other, NightLevel.MORE))
        // 殘留永遠不是任何檔位的查找結果
        val mixed = setOf(std, more, l1, l2, l3, legacy)
        val dead = NightPages.leftovers(mixed)
        NightLevel.entries.forEach { (NightPages.resolveName(mixed, page, it) in dead) shouldBe false }
    }

    @Test
    fun `hasAnyArtifact：含孤兒與舊版，不含暫存`() {
        NightPages.hasAnyArtifact(setOf(more), page) shouldBe true
        NightPages.hasAnyArtifact(setOf(l3), page) shouldBe true
        NightPages.hasAnyArtifact(setOf(legacy), page) shouldBe true
        NightPages.hasAnyArtifact(setOf("$std.tmp"), page) shouldBe false
        NightPages.hasAnyArtifact(setOf("013.jpg.night.std.webp"), page) shouldBe false
    }

    @Test
    fun `偏好對應：l2＝標準、l3＝更多；舊值 l1 protect＝標準、aggressive＝更多；其餘＝標準`() {
        NightLevel.fromPref("l2") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("l3") shouldBe NightLevel.MORE
        NightLevel.fromPref("l1") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("protect") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("aggressive") shouldBe NightLevel.MORE
        NightLevel.fromPref("") shouldBe NightLevel.STANDARD
        NightLevel.fromPref(null) shouldBe NightLevel.STANDARD
        NightLevel.fromPref("standard") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("L3") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("garbage") shouldBe NightLevel.STANDARD
        // 產品只寫 l2／l3，往返不變
        NightLevel.entries.map { it.prefValue } shouldBe listOf("l2", "l3")
        NightLevel.entries.forEach { NightLevel.fromPref(it.prefValue) shouldBe it }
    }

    @Test
    fun `legacy and numeric-only combinations cannot authorize either reader tier`() {
        val all = NightPages.artifactNames(page) + NightPages.rulesStampName(page)
        for (mask in 0 until (1 shl all.size)) {
            val names = all.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            resolveBoth(names) shouldBe listOf(null, null)
            NightPages.markerName(names, page) shouldBe null
            NightPages.needsRender(names, page) { true } shouldBe true
        }
    }

    @Test
    fun `complete current pair resolves two tiers while legacy siblings are ignored`() {
        val proofs = NightPolicy.names(page, "a".repeat(32), moreDistinct = true)
        val current = setOf(std, more) + proofs
        resolveBoth(current) shouldBe listOf(std, more)
        resolveBoth(current + setOf(l1, l2, l3, legacy)) shouldBe listOf(std, more)
        NightPages.markerName(current, page) shouldBe std
        val other = "a.night.jpg"
        val otherNames = NightPages.currentNames(other).toSet() +
            NightPolicy.names(other, "b".repeat(32), moreDistinct = true)
        NightPages.resolveName(otherNames, other, NightLevel.MORE) shouldBe NightPages.levelName(other, NightLevel.MORE)
        NightPages.resolveName(current + otherNames, other, NightLevel.STANDARD) shouldBe
            NightPages.levelName(other, NightLevel.STANDARD)
    }

    @Test
    fun `proof deletion never reveals an older legacy image`() {
        val proofs = NightPolicy.names(page, "c".repeat(32), moreDistinct = true)
        val start = NightPages.artifactNames(page).toSet() + proofs
        val remaining = start.toMutableSet()
        resolveBoth(remaining) shouldBe listOf(std, more)
        for (proof in proofs) {
            remaining -= proof
            resolveBoth(remaining) shouldBe listOf(null, null)
        }
        for (image in NightPages.artifactNames(page)) {
            remaining -= image
            resolveBoth(remaining) shouldBe listOf(null, null)
        }
    }

    @Test
    fun `availability advertises legacy migration without trusting legacy pixels`() {
        val proofs = NightPolicy.names(page, "d".repeat(32), moreDistinct = true)
        NightPages.availability(false, setOf(std, more) + proofs) shouldBe NightAvailability.UNSUPPORTED
        NightPages.availability(true, setOf(std, more) + proofs) shouldBe NightAvailability.READY
        listOf(
            setOf(std),
            setOf(l1, l2, l3),
            setOf(legacy),
            setOf(std, NightPages.rulesStampName(page)),
        ).forEach { names ->
            NightPages.availability(true, names) shouldBe NightAvailability.LEGACY
            resolveBoth(names) shouldBe listOf(null, null)
        }
        listOf(emptySet(), setOf(more), setOf(l2, l3), setOf("$std.tmp")).forEach { names ->
            NightPages.availability(true, names) shouldBe NightAvailability.NONE
        }
    }

    @Test
    fun `legal maximum UTF8 basenames use bounded hashed names without collisions`() {
        val longPage = "あ".repeat(83) + "ab.png"
        longPage.toByteArray(Charsets.UTF_8).size shouldBe 255
        val names = NightPages.currentNames(longPage)
        val proofs = NightPolicy.names(longPage, "e".repeat(32), moreDistinct = true)
        (names + names.map(NightPages::tmpName) + proofs + NightSourceRevision.fileName(longPage)).forEach {
            (it.toByteArray(Charsets.UTF_8).size <= 255) shouldBe true
        }
        names.toSet().intersect(NightPages.currentNames(longPage.replace("ab.png", "ac.png")).toSet()) shouldBe
            emptySet()
        val all = names.toSet() + proofs
        NightPages.resolveName(all, longPage, NightLevel.MORE) shouldBe names.last()
        NightPages.filesOfMissingPages(all, setOf(longPage)) shouldBe emptySet()
        NightPages.summarize(all) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.READY, false)
        val incomplete = all - proofs.first()
        NightPages.summarize(incomplete) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.LEGACY, true)
        val same = setOf(names.first()) + NightPolicy.names(longPage, "f".repeat(32), moreDistinct = false)
        NightPages.summarize(same) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.READY, false)
        NightPages.resolveName(same, longPage, NightLevel.MORE) shouldBe names.first()
    }
}
