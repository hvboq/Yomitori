package eu.kanade.tachiyomi.data.nightread

import com.hippo.unifile.UniFile
import li.joye.yakuyomi.nightread.NightRead
import li.joye.yakuyomi.nightread.NightTier

/** 閱讀器裡一章的夜讀狀態（見 [NightPages.availability]）。 */
enum class NightAvailability {
    /** At least one complete current-policy recipe exists; opening still validates its witness. */
    READY,

    /** Legacy or unproved artifacts need regeneration; reader streams fall back to source. */
    LEGACY,

    /** 沒有夜讀版：檔位鈕灰，點了可產生。 */
    NONE,

    /** 壓縮檔章或線上章：不支援夜讀（要先下載成鬆散資料夾）。 */
    UNSUPPORTED,
}

/**
 * Shared naming, metadata-only migration summaries and prepared reader indexes for derived Night
 * images in `<chapter>/.yakuyomi/`. Standard is L2; more is L3. A distinct more image is optional
 * only when its immutable proof explicitly records `more.same`.
 *
 * Reader authority requires a complete std/more proof pair with one UUID generation and the actual
 * analysis RGB policy, plus a matching committed auxiliary source revision, canonical capture epoch
 * and known source/material metadata. Names and numeric rules stamps alone never authorize reads.
 * Legacy single/three-tier images and unproved std images remain migration/history metadata; the
 * reader falls back to the visible source until they are regenerated. Original/translation/journal
 * authority remains in the existing translation stores, not in this derived-cache protocol.
 *
 * Page names keep their extension so mixed source formats remain distinct. Long legal UTF-8 names
 * use a hashed image stem; revision/proof names are always hashed and fit the filesystem limit.
 * Temporary images are never served. Producers revoke proof authority before fixed-image writes.
 *
 * [Index] prepares exact URIs and positive/negative policy entries during initial or explicit refresh.
 * A complete snapshot is published atomically. Decode uses only those exact URIs, bounded revision/
 * proof records and known metadata before/after opening an image; it never lists, hashes an image,
 * recovers, writes or lazily refreshes. Internal writes advance revision and publish per-page events.
 * External edits preserving all observed metadata are outside this freshness guarantee; discovery
 * of new external material files requires explicit refresh.
 */
object NightPages {

    /** 目前的規則版本（nightread 的 `NightRead.RULES_VERSION`）：產生端寫這個、章節列與設定頁拿它判舊版。 */
    const val RULES_VERSION: Int = NightRead.RULES_VERSION

    /** 章節內的素材子夾（與 `PageTranslator.MATERIALS_DIR` 同名——同一個夾）。 */
    const val DIR = ".yakuyomi"

    /** 舊版單檔的尾綴（接在**完整**頁檔名之後）：`012.jpg.night.webp`。 */
    const val LEGACY_SUFFIX = ".night.webp"

    /** 暫存檔的尾綴（接在最終名之後）：`012.jpg.night.std.webp.tmp`。reader 不認它。 */
    const val TMP_SUFFIX = ".tmp"

    /** 任何夜讀檔（兩檔、三檔各檔位、舊版單檔，以及它們的暫存）。只錨定結尾：頁名本身含 `.night.` 也不會誤判。 */
    private val ARTIFACT = Regex("""\.night(\.(?:l[123]|std|more))?\.webp(\.tmp)?$""")

    /** 夜讀最終檔（不含暫存），第 1 組＝頁檔名。 */
    private val FINAL = Regex("""^(.+)\.night(\.(?:l[123]|std|more))?\.webp$""")

    /** 規則版本記號的中段（接在完整頁檔名之後、版本號之前）。 */
    private const val RULES_MARK = ".night.rules"

    /** 規則版本記號：第 1 組＝頁檔名、第 2 組＝版本（1–6 位數字）。 */
    private val RULES_STAMP = Regex("""^(.+)\.night\.rules(\d{1,6})$""")

    /**
     * 產生端開跑前試寫的探測檔（`.yakuyomi/` 內）：試建這個空檔、確認檔名沒被儲存位置改掉、再刪掉，確定這裡寫得出版本記號
     * 才開始做。寫不出記號時每頁都會一直是舊版，使用者每按一次更新就整章重做一次，所以寧可整章報錯。它不是記號（沒有版本號）、
     * 不是夜讀檔、也不是重繪素材，誰都不認它；被殺而留下也無害，下次探測重用同一個檔。
     */
    const val STAMP_PROBE = "night.rules.probe"

    /** 兩檔格式：`012.jpg` + 標準 → `012.jpg.night.std.webp`；更多 → `012.jpg.night.more.webp`。 */
    fun levelName(pageFileName: String, level: NightLevel): String {
        val stem = if (pageFileName.toByteArray(Charsets.UTF_8).size <= 220) {
            pageFileName
        } else {
            "night-${NightSourceRevision.pageKey(pageFileName)}"
        }
        return "$stem.night.${level.fileKey}.webp"
    }

    /** Historical three-tier name for migration/cleanup, not current read authority. */
    fun tierName(pageFileName: String, tier: NightTier): String = "$pageFileName.night.${tier.key}.webp"

    /** `012.jpg` → `012.jpg.night.webp`（舊版單檔）。 */
    fun legacyName(pageFileName: String): String = pageFileName + LEGACY_SUFFIX

    /** 最終名 → 暫存名（`<最終名>.tmp`）。 */
    fun tmpName(finalName: String): String = finalName + TMP_SUFFIX

    /** 是否任一種夜讀檔（含暫存）。不會誤中重繪素材 `.orig.<副檔名>`（含舊版 `.orig.webp` 與暫存）／`.mask.png`／`.json`。 */
    fun isNightArtifact(name: String): Boolean = ARTIFACT.containsMatchIn(name)

    /** 夜讀暫存檔（產生到一半、或中途被殺留下的）。 */
    fun isNightTmp(name: String): Boolean = name.endsWith(TMP_SUFFIX) && isNightArtifact(name)

    /** 夜讀最終檔（任一格式，不含暫存）。 */
    fun isFinalNightFile(name: String): Boolean = isNightArtifact(name) && !name.endsWith(TMP_SUFFIX)

    /** Final image -> source name or hashed stem; non-final names return null. */
    fun pageOf(name: String): String? = FINAL.find(name)?.groupValues?.get(1)

    /** Current fixed image names, standard first; a complete proof pair is also required. */
    fun currentNames(pageFileName: String): List<String> = NightLevel.entries.map { levelName(pageFileName, it) }

    /** 舊格式的檔名（新標準檔落地後要清掉的），**標記在前**：l1、l2、l3、舊版單檔。 */
    fun oldFormatNames(pageFileName: String): List<String> =
        NightTier.entries.map { tierName(pageFileName, it) } + legacyName(pageFileName)

    /**
     * Per-page historical/current final image names in cleanup order. Proof authority and revision
     * must be revoked before replacement/deletion; this list itself grants no write/read authority.
     */
    fun artifactNames(pageFileName: String): List<String> = listOf(
        legacyName(pageFileName),
        tierName(pageFileName, NightTier.L1),
        levelName(pageFileName, NightLevel.STANDARD),
        levelName(pageFileName, NightLevel.MORE),
        tierName(pageFileName, NightTier.L2),
        tierName(pageFileName, NightTier.L3),
    )

    /**
     * Metadata-only recipe selection. A complete current-policy pair is required; explicit
     * more.same selects standard. Legacy, partial and mixed recipes return null. Actual streams
     * must additionally verify the revision, proof payloads and source/material witness.
     */
    fun resolveName(names: Set<String>, pageFileName: String, level: NightLevel): String? {
        return NightPolicy.recipe(names, pageFileName, level)?.imageName
    }

    /** Standard image of a complete current-policy recipe; never a translation completion marker. */
    fun markerName(names: Set<String>, pageFileName: String): String? =
        NightPolicy.recipe(names, pageFileName, NightLevel.STANDARD)?.imageName

    /**
     * [names]（`.yakuyomi/` 內的夜讀最終檔）裡**永遠不會被顯示**、可以直接刪的檔（產生端開跑時清掉）：
     *  - 有 std 的頁：它的 l1／l2／l3／舊版單檔（被兩檔取代、刪到一半被殺留下的）。
     *  - 沒有 std 的頁：它的 more（孤兒）；再沒有 l1 的話，它的 l2／l3（孤兒）。
     * 回傳的都不是任何 [resolveName] 的結果，刪除順序無所謂。
     */
    fun leftovers(names: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (n in names) {
            val page = pageOf(n) ?: continue
            val dead = when {
                // 有 std：被它取代的舊格式
                levelName(page, NightLevel.STANDARD) in names -> n in oldFormatNames(page)
                // 沒有 std：孤兒 more
                n == levelName(page, NightLevel.MORE) -> true
                // Historical l1 keeps its siblings for migration; none is current read authority.
                tierName(page, NightTier.L1) in names -> false
                else -> n == tierName(page, NightTier.L2) || n == tierName(page, NightTier.L3)
            }
            if (dead) out += n
        }
        return out
    }

    // —— 規則版本記號（見類別說明「規則版本」） ——

    /** Historical empty numeric stamp name; existence grants no current read authority. */
    fun rulesStampName(pageFileName: String, version: Int = RULES_VERSION): String = "$pageFileName$RULES_MARK$version"

    /** 是不是規則版本記號（不是夜讀檔、也不是重繪素材）。 */
    fun isRulesStamp(name: String): Boolean = RULES_STAMP.matches(name)

    /** 記號檔名 → (頁檔名, 版本)；不是記號 → null。 */
    fun parseRulesStamp(name: String): Pair<String, Int>? =
        RULES_STAMP.matchEntire(name)?.let { it.groupValues[1] to it.groupValues[2].toInt() }

    /** [names] 裡每頁記號的**最大**版本（頁檔名 → 版本）。不管那頁有沒有 std；std 的判斷在 [pageRulesVersion]。 */
    fun rulesStamps(names: Iterable<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (n in names) {
            val (page, v) = parseRulesStamp(n) ?: continue
            if (v > (out[page] ?: -1)) out[page] = v
        }
        return out
    }

    /**
     * Metadata-only migration version: a complete current policy pair uses [RULES_VERSION];
     * unproved std, historical l1 or single images use zero; orphan-only sets return null.
     * [stamps] is retained for call compatibility; numeric stamps cannot promote an old image.
     */
    fun pageRulesVersion(
        names: Set<String>,
        pageFileName: String,
        stamps: Map<String, Int> = rulesStamps(names),
    ): Int? = when {
        levelName(pageFileName, NightLevel.STANDARD) in names ->
            if (NightPolicy.recipe(names, pageFileName, NightLevel.STANDARD) != null) RULES_VERSION else 0
        tierName(pageFileName, NightTier.L1) in names || legacyName(pageFileName) in names -> 0
        else -> null
    }

    /**
     * 這頁有夜讀版、而且是舊規則產生的（版本 < [current]）。產生端把這種頁當不新鮮、重做；沒有夜讀版的頁不算舊版
     * （要不要做由完成標記決定）。
     */
    fun isPageOutdated(
        names: Set<String>,
        pageFileName: String,
        stamps: Map<String, Int> = rulesStamps(names),
        current: Int = RULES_VERSION,
    ): Boolean = pageRulesVersion(names, pageFileName, stamps)?.let { it < current } ?: false

    /**
     * 可以直接刪的記號（產生端開跑時清掉；不影響任何頁的版本判斷）：
     *  - 孤兒：同頁沒有 std（夜讀版被作廢、重做時刪了、或只剩三檔格式／舊版單檔）。
     *  - 被取代：同頁有更大版本的記號。
     */
    fun staleStamps(names: Iterable<String>): Set<String> {
        val set = names as? Set<String> ?: names.toHashSet()
        val max = rulesStamps(set)
        val out = LinkedHashSet<String>()
        for (n in set) {
            val (page, v) = parseRulesStamp(n) ?: continue
            if (levelName(page, NightLevel.STANDARD) !in set || v < (max[page] ?: v)) out += n
        }
        return out
    }

    /**
     * [names] 裡屬於**已經不在這章**的頁（[pages]＝章節夾頂層的頁檔名）的夜讀最終檔與記號——頁被換掉或刪掉後留下的。reader
     * 永遠不會顯示它們，但章節列會因為它們一直把這章算成「可更新」（產生端只做在的頁、永遠補不到它們），所以產生端開跑時清掉。
     * 暫存不在這裡（另外清）。
     */
    fun filesOfMissingPages(names: Iterable<String>, pages: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (n in names) {
            val page = pageOf(n) ?: parseRulesStamp(n)?.first ?: continue
            val hashedPage = page.removePrefix("night-").takeIf {
                page.startsWith("night-") &&
                    it.matches(Regex("[a-f0-9]{64}"))
            }
            if (page !in pages &&
                (hashedPage == null || pages.none { NightSourceRevision.pageKey(it) == hashedPage })
            ) {
                out += n
            }
        }
        return out
    }

    /**
     * 產生端開跑時要刪的檔（純函式；[names]＝`.yakuyomi/` 內的所有檔名，重繪素材等其他檔照樣傳進來、會被略過）：
     *  - 夜讀暫存（[isNightTmp]，上次中途被殺留下的）；
     *  - 永遠不會被顯示的最終檔（[leftovers]）；
     *  - 已經不在這章的頁留下的夜讀檔與記號（[filesOfMissingPages]）。[pageNames]＝章節夾頂層的檔名；null＝這次列目錄不可信
     *    （例如列出來是空的），這一項就不清——否則一次列目錄失敗會把整章的夜讀版刪光；
     *  - 上面刪完後的可清記號（[staleStamps]）。
     * 刪不掉的也當作已刪（reader 本來就不認、下次開跑再清），所以這一輪看得到的＝[names] 的夜讀最終檔與記號扣掉這些
     * （[needsRender] 拿那一份判斷）。
     */
    fun cleanupPlan(names: Iterable<String>, pageNames: Set<String>?): Set<String> {
        val out = LinkedHashSet<String>()
        val kept = HashSet<String>()
        for (n in names) {
            when {
                isNightTmp(n) -> out += n
                isFinalNightFile(n) || isRulesStamp(n) -> kept += n
            }
        }
        val dead = leftovers(kept) + (pageNames?.let { filesOfMissingPages(kept, it) } ?: emptySet())
        out += dead
        kept -= dead
        out += staleStamps(kept)
        return out
    }

    /**
     * 這頁這一輪要不要做（純函式；[names]＝開跑清完後 `.yakuyomi/` 的夜讀最終檔與記號，[stamps]＝[rulesStamps]）：沒有完成標記
     * （[markerName]）、標記不算數（[markerOk] 回 false：比頁圖舊，或早於強制重做的時間）、或是舊規則產生的（[isPageOutdated]）
     * 就要做。章節列標「可更新」的頁，這裡一定回 true。
     */
    fun needsRender(
        names: Set<String>,
        pageFileName: String,
        stamps: Map<String, Int> = rulesStamps(names),
        current: Int = RULES_VERSION,
        markerOk: (marker: String) -> Boolean,
    ): Boolean {
        val marker = markerName(names, pageFileName) ?: return true
        return !markerOk(marker) || isPageOutdated(names, pageFileName, stamps, current)
    }

    /** 一章的夜讀狀態（只看檔名；不支援的章由呼叫端另判）。 */
    enum class ChapterState {
        /** At least one complete current-policy recipe, subject to stream-time validation. */
        READY,

        /** Legacy/unproved image metadata exists and needs regeneration; source is displayed. */
        LEGACY,

        /** 都沒有。 */
        NONE,
    }

    /**
     * 一章的夜讀摘要：[state] 同 [chapterState]；[outdated]＝至少一頁有夜讀版而且是舊規則產生的（[isPageOutdated]）——
     * 章節列的「可更新」、設定頁「重新產生舊版夜讀頁」的計數都看它。只有舊版單檔、只有三檔格式的章一定是 true。
     */
    data class ChapterSummary(val state: ChapterState, val outdated: Boolean)

    /**
     * Metadata-only summary including immutable policy proof names. Complete current recipes are
     * READY; unproved std and historical images are LEGACY/outdated. Orphan/temp/stamp-only sets
     * do not count as available images. Stream-time payload/revision checks remain mandatory.
     */
    fun summarize(names: Iterable<String>, current: Int = RULES_VERSION): ChapterSummary {
        val set = names as? Set<String> ?: names.toHashSet()
        val stamps = rulesStamps(set)
        var ready = false
        var legacy = false
        var outdated = false
        // A maximum-length source uses a hashed image stem; the original filename is intentionally
        // absent from auxiliary names. Its complete proof pair still contributes to chapter UI.
        val policyByKey = set.mapNotNull(NightPolicy::parse).groupBy { it.pageKey }
        val hashedStems = set.mapNotNull(::pageOf).filterTo(hashSetOf()) {
            it.startsWith("night-") && it.removePrefix("night-").matches(Regex("[a-f0-9]{64}"))
        }
        for (stem in hashedStems) {
            if ("$stem.night.std.webp" !in set) continue
            val pair = policyByKey[stem.removePrefix("night-")].orEmpty()
            val standard = pair.singleOrNull { it.level == NightLevel.STANDARD }
            val more = pair.singleOrNull { it.level == NightLevel.MORE }
            val valid = pair.size == 2 && standard != null && more != null && pair.all {
                it.generation == standard.generation && it.version == RULES_VERSION &&
                    it.policy == li.joye.yakuyomi.engine.NightReadRenderer.INPUT_RGB_POLICY_ID
            } && (more.same != ("$stem.night.more.webp" in set))
            if (valid) {
                ready = true
                if (RULES_VERSION < current) outdated = true
            } else {
                legacy = true
                outdated = true
            }
        }
        for (n in set) {
            val page = pageOf(n) ?: continue
            if (page in hashedStems) continue
            when (n) {
                levelName(page, NightLevel.STANDARD) -> {
                    if (NightPolicy.recipe(set, page, NightLevel.STANDARD) != null) ready = true else legacy = true
                }
                tierName(page, NightTier.L1) -> legacy = true
                legacyName(page) -> legacy = true
                else -> continue // more／l2／l3：跟著標記走，不單獨算
            }
            if (!outdated && isPageOutdated(set, page, stamps, current)) outdated = true
        }
        val state = when {
            ready -> ChapterState.READY
            legacy -> ChapterState.LEGACY
            else -> ChapterState.NONE
        }
        return ChapterSummary(state, outdated)
    }

    /** Metadata-only READY/LEGACY/NONE summary; proof payload and revision checks occur on open. */
    fun chapterState(names: Iterable<String>): ChapterState = summarize(names).state

    /**
     * 閱讀器裡「這一章」的夜讀狀態（懸浮鈕的檔位可不可用、點了做什麼）。[supported]＝鬆散資料夾章（已下載或本機資料夾）；
     * 壓縮檔章、線上章 → [NightAvailability.UNSUPPORTED]。優先序：UNSUPPORTED > READY > LEGACY > NONE。
     */
    fun availability(supported: Boolean, names: Iterable<String>): NightAvailability = when {
        !supported -> NightAvailability.UNSUPPORTED
        else -> when (chapterState(names)) {
            ChapterState.READY -> NightAvailability.READY
            ChapterState.LEGACY -> NightAvailability.LEGACY
            ChapterState.NONE -> NightAvailability.NONE
        }
    }

    /** 這頁（[pageFileName]）在 [names] 裡有沒有任何夜讀最終檔（含孤兒、舊版單檔）。 */
    fun hasAnyArtifact(names: Set<String>, pageFileName: String): Boolean =
        artifactNames(pageFileName).any { it in names }

    /**
     * Explicit metadata scan of final images, historical numeric stamps and immutable policy proofs.
     * Does not authorize serving an image; stream readers use an atomically prepared [Index].
     */
    fun listFinal(chapterDir: UniFile): Map<String, UniFile> {
        val dir = chapterDir.findFile(DIR)?.takeIf { it.isDirectory } ?: return emptyMap()
        return NightSourceRevision.inventory(dir).filterKeys {
            isFinalNightFile(it) || isRulesStamp(it) ||
                NightPolicy.parse(it) != null
        }
    }

    /** Producer mtime prefilter only; reuse also requires a matching revision/policy witness. */
    fun isFresh(night: UniFile, page: UniFile): Boolean = night.lastModified() >= page.lastModified()

    /**
     * Exact-URI cache for one chapter. [refreshPrepared] builds positive and negative policy entries
     * privately, including read-only canonical epoch validation, then publishes the complete
     * snapshot. [cachedPage] performs no lazy preparation, listing, recovery or refresh. A missing,
     * corrupt, pending or legacy entry stays negative until an explicit event/status/tier refresh.
     */
    class Index {
        internal class PageFiles(val files: Map<String, UniFile>, val io: NightPolicy.Entry)
        private class Prepared(val value: PageFiles?)
        private class Snapshot(val dir: UniFile, val files: Map<String, UniFile>) {
            val pages = HashMap<String, Prepared>()

            // Group policy URIs once. Per decode never walks a chapter-sized name collection.
            val proofs = files.entries.mapNotNull { (name, file) ->
                NightPolicy.parse(name)?.let { Triple(it.pageKey, name, file) }
            }.groupBy { it.first }
        }

        @Volatile
        private var snapshot: Snapshot? = null
        private val refreshLock = Any()

        /** Discard the snapshot; decode remains negative until explicit prepared refresh. */
        fun invalidate() {
            snapshot = null
        }

        /** Metadata inventory API, with initial scan if needed; never called from decode. */
        fun files(chapterDir: UniFile): Map<String, UniFile> {
            val s = snapshot
            if (s != null && (s.dir === chapterDir || s.dir.uri == chapterDir.uri)) return s.files
            return refresh(chapterDir)
        }

        /** Acquire one bounded inventory without publishing partially prepared entries. */
        private fun readSnapshot(chapterDir: UniFile): Snapshot {
            val materials = chapterDir.findFile(DIR)
            val files = if (materials == null) {
                emptyMap()
            } else {
                if (!materials.isDirectory ||
                    materials.name != DIR
                ) {
                    throw java.io.IOException("Invalid Night materials directory")
                }
                NightSourceRevision.inventory(materials)
            }
            return Snapshot(chapterDir, files)
        }

        fun refresh(chapterDir: UniFile): Map<String, UniFile> = synchronized(refreshLock) {
            val next = readSnapshot(chapterDir)
            snapshot = next
            next.files
        }

        /** Build every positive/negative entry privately; decode sees either complete snapshot. */
        internal fun refreshPrepared(chapterDir: UniFile, sources: List<Pair<String, UniFile>>): Map<String, UniFile> =
            synchronized(refreshLock) {
                val next = readSnapshot(chapterDir)
                sources.forEach { (page, source) -> prepare(next, source, page) }
                snapshot = next
                next.files
            }

        private fun prepare(current: Snapshot, source: UniFile, page: String): PageFiles? {
            current.pages[page]?.let { return it.value }
            val value = runCatching {
                val selected = LinkedHashMap<String, UniFile>()
                current.proofs[NightSourceRevision.pageKey(page)].orEmpty().also {
                    if (it.size > 16) throw java.io.IOException("Ambiguous Night proof inventory")
                }.forEach { (_, name, file) -> selected[name] = file }
                NightPages.currentNames(page).forEach { name -> current.files[name]?.let { selected[name] = it } }
                if (NightPolicy.recipe(selected.keys, page, NightLevel.STANDARD) == null) return@runCatching null
                PageFiles(selected, NightPolicy.entry(source, page, current.files))
            }.getOrNull()
            current.pages[page] = Prepared(value)
            return value
        }

        /** Negative results are cached too. Only initial/explicit refresh can discover new files. */
        @Synchronized
        internal fun page(chapterDir: UniFile, source: UniFile, page: String): PageFiles? {
            files(chapterDir)
            val current = snapshot ?: return null
            return prepare(current, source, page)
        }

        /** Decode entry: no lazy refresh or lazy capture-history work, even for a negative cache. */
        @Synchronized
        internal fun cachedPage(chapterDir: UniFile, page: String): PageFiles? {
            val current = snapshot ?: return null
            if (current.dir !== chapterDir && current.dir.uri != chapterDir.uri) return null
            return current.pages[page]?.value
        }
    }
}
