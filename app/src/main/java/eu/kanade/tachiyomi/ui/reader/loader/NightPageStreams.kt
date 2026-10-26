package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.nightread.NightPolicy
import eu.kanade.tachiyomi.data.nightread.NightSourceRevision
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 能端出夜讀版的 loader（已下載鬆散章 [DownloadPageLoader]、本機資料夾章 [DirectoryPageLoader]，以及包著它們的
 * [TranslatingPageLoader]）。[nightStreams] 為 null＝這章不支援夜讀。
 */
internal interface NightPageSource {
    val nightStreams: NightPageStreams?
}

/**
 * Night stream selection for a loose downloaded/local chapter. Effective mode is the translation
 * master Night switch AND reader Night mode; the preferred [NightLevel] is read on every decode.
 *
 * Loaders register exact source URIs and call [refresh] during setup. Explicit source/proof events,
 * availability checks and tier changes also refresh. Refresh prepares all positive/negative entries
 * and canonical capture witnesses before atomically publishing one complete [NightPages.Index].
 *
 * [open] only uses the prepared entry: a complete immutable std/more generation pair, committed
 * source revision, actual RGB policy and known source/material metadata must match before/after
 * opening the exact image URI. Missing, corrupt, pending or legacy entries fall back to [open]'s
 * original stream. Decode never lazily lists, scans history, hashes images, recovers or writes;
 * negative reads and open failures do not trigger automatic refresh.
 *
 * Served tokens contain generation AND filename, so regeneration under the same name is observable.
 * Per-page mutation/publication events reload affected decoded pages even when a job pauses/fails;
 * successful chapter completion remains a backstop. Original and translation-completion authority
 * stay in their existing stores. Ordinary Night read exceptions fall back to source.
 */
internal class NightPageStreams(
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {

    /** Atomically prepared exact-URI and positive/negative policy cache for this chapter. */
    val index = NightPages.Index()

    /** 鬆散章節夾；null＝還沒解析到（或不是鬆散夾）。 */
    @Volatile
    var chapterDir: UniFile? = null

    /**
     * page.index -> last served generation:filename token; [ORIGINAL] is source, [PENDING] is opening.
     * 沒解碼過的頁不在表裡。
     */
    private val served = ConcurrentHashMap<Int, String>()

    /** page.index → 頁檔名（算一次就快取）。 */
    private val pageNames = ConcurrentHashMap<Int, String>()

    /** page.index → 怎麼算頁檔名（loader 列頁時登記；便宜的 lambda，真的要名字時才呼叫）。 */
    private val nameFns = ConcurrentHashMap<Int, () -> String?>()
    private val sourceFns = ConcurrentHashMap<Int, () -> UniFile?>()
    private val sources = ConcurrentHashMap<Int, UniFile>()

    /** 有效夜讀模式＝夜讀總開關 AND reader 夜讀模式。 */
    fun effectiveNight(): Boolean =
        translationPreferences.nightReadEnabled.get() && readerPreferences.nightReadMode.get()

    /** 目前要顯示的檔位（偏好 `nightread_fill_level`）。 */
    fun currentTier(): NightLevel = NightLevel.fromPref(translationPreferences.nightReadFillLevel.get())

    /** 登記第 [pageIndex] 頁怎麼取頁檔名（loader 列頁時呼叫）。 */
    fun registerPage(pageIndex: Int, name: () -> String?) {
        nameFns[pageIndex] = name
    }

    /** Exact source URI registered by the loader; decode never searches the chapter directory. */
    fun registerSource(pageIndex: Int, source: () -> UniFile?) {
        sourceFns[pageIndex] = source
        registerPage(pageIndex) { sourceFile(pageIndex)?.name }
    }

    private fun sourceFile(pageIndex: Int): UniFile? = sources[pageIndex]
        ?: sourceFns[pageIndex]?.invoke()?.also { sources[pageIndex] = it }

    /** Loader setup and explicit event/tier refresh only. Never invoked from a decode lambda. */
    fun refresh(): Map<String, UniFile> {
        val dir = chapterDir ?: return emptyMap()
        val registered = sourceFns.keys.mapNotNull { pageIndex ->
            val source = sourceFile(pageIndex) ?: return@mapNotNull null
            val page = pageName(pageIndex) ?: return@mapNotNull null
            page to source
        }
        return index.refreshPrepared(dir, registered)
    }

    private fun pageName(pageIndex: Int): String? {
        pageNames[pageIndex]?.let { return it }
        val n = runCatching { nameFns[pageIndex]?.invoke() }.getOrNull() ?: return null
        pageNames[pageIndex] = n
        return n
    }

    /**
     * Open the prepared, currently verified Night recipe when enabled; otherwise use [original].
     * [dirFallback]：[chapterDir] 沒有時的退路（例如頁檔的 parentFile；SAF 文件 URI 拿不到 parent，所以只是退路）。
     */
    fun open(pageIndex: Int, dirFallback: () -> UniFile? = { null }, original: () -> InputStream): InputStream {
        // Register before bounded proof/metadata reads and image open so a concurrent tier/event
        // refresh includes this in-flight decode. No directory search occurs in this path.
        served[pageIndex] = PENDING
        val night = if (effectiveNight()) {
            try {
                openNight(pageIndex, dirFallback)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "夜讀版讀取失敗，退回原圖" }
                null
            }
        } else {
            null
        }
        served[pageIndex] = night?.first ?: ORIGINAL
        return night?.second ?: original()
    }

    private fun openNight(pageIndex: Int, dirFallback: () -> UniFile?): Pair<String, InputStream>? {
        // 退路找到的資料夾記下來：之後的頁與 [pagesToReload] 都用同一個
        val dir = chapterDir ?: dirFallback()?.takeIf { it.isDirectory }?.also { chapterDir = it } ?: return null
        val page = pageName(pageIndex) ?: return null
        val entry = index.cachedPage(dir, page) ?: return null
        // Both successful and negative decode paths retain the refresh snapshot. A missing,
        // corrupt, pending or legacy result NEVER causes a per-decode listing/history scan.
        return NightPolicy.openCurrent(entry.files.keys, page, currentTier(), entry.io)
    }

    /**
     * Explicit tier/mode refresh, then compare generation+filename tokens with already decoded
     * pages. In-flight pages always reload; untouched pages use current policy on first decode.
     * Caller runs on IO. This refresh is not part of a decode or a negative-read retry.
     */
    fun pagesToReload(pages: List<ReaderPage>): List<ReaderPage> {
        val on = effectiveNight()
        val tier = currentTier()
        val dir = chapterDir
        if (on && dir != null) refresh()
        return pages.filter { p ->
            val sent = served[p.index] ?: return@filter false
            if (sent == PENDING) return@filter true
            val want = if (on && dir != null) {
                runCatching {
                    val name = pageName(p.index) ?: return@runCatching null
                    val entry = index.cachedPage(dir, name) ?: return@runCatching null
                    val recipe = NightPolicy.recipe(entry.files.keys, name, tier) ?: return@runCatching null
                    if (!NightPolicy.reusable(entry.files.keys, name, entry.io)) return@runCatching null
                    "${recipe.generation}:${recipe.imageName}"
                }.getOrNull()
            } else {
                null
            }
            (sent.takeIf { it != ORIGINAL }) != want
        }
    }

    /** 第 [pageIndex] 頁最近一次送出的夜讀檔名；null＝送的是原圖或還沒解碼過。 */
    fun servedNightName(pageIndex: Int): String? = served[pageIndex]?.takeIf { it != ORIGINAL && it != PENDING }
        ?.substringAfter(':')

    /** Source writes and same-name proof publication must refresh already decoded holders too. */
    fun changedPages(change: NightSourceRevision.Change, pages: List<ReaderPage>): List<ReaderPage> {
        if (chapterDir?.let(NightSourceRevision::chapterKey) != change.chapterUri) return emptyList()
        refresh()
        return pages.filter { served.containsKey(it.index) && pageName(it.index) == change.page }
    }

    private companion object {
        /** [served] 裡代表「送的是原圖」（ConcurrentHashMap 不收 null）。 */
        const val ORIGINAL = ""

        /** [served] 裡代表「正在開檔、還不知道送哪個」（不可能是檔名：檔名不含換行）。 */
        const val PENDING = "\n"
    }
}
