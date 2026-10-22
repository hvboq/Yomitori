package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.crash.TraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.Yakuyomi
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

/**
 * 翻譯引擎的**常駐（warm）服務**（process singleton，於 [AppModule] 註冊）。
 *
 * 用途：讓整章翻（[PageTranslator.translateChapter] 逐章透過本服務）共用**同一顆**引擎實例，
 * 避免佇列一章接一章 drain 時、每章 `Yakuyomi.create(...).use { }` 都重新載入 NCNN 模型
 * （M4 ⑦ 引擎生命週期）。即時翻譯開著時尤其關鍵：reader 連讀多章＝佇列連翻多章。
 *
 * **去字法可變**：[translatePage] 帶 `methodRaw` 參數（boxfill / auto_whole / auto_tile）。
 * 同一個去字法連續呼叫＝復用 warm 引擎；去字法在章與章間變了＝簽章變→重建（見 [ensureEngine]/[configSignature]）。
 * （即時翻走自己的 liveInpaintMethod pref、不是固定值；本服務服務的是受管理佇列的整章翻。）
 *
 * **並發（跨頁流水線）**：warm 引擎的 [translatePage] **可並發呼叫**（偵測/OCR/翻譯/去字 session 共用、真機實測
 * 併發翻多頁不 crash、不汙染輸出）→ reader/佇列可把「頁 N 的網路翻譯」疊上「頁 N+1 的裝置端偵測/OCR」，
 * 淺併發（~4）達約 2× 循序速率（見 [PageTranslator] 的 pipelineDepth）。[mutex] 只序列化**引擎生命週期**
 * （建/重建/關），不再序列化每頁推論；在飛頁數由 [inFlight] 計數，關閉前等它歸零（見 [shutdown]/[shutdownBlocking]）。
 * **生命週期**：lazy 建（首次 [translatePage] 或 [warmUp] 才建、不拖 app 冷啟）；設定改了（簽章變）下次呼叫重建；
 *   **不在翻完一頁/一章後關**。背景閒置 60 秒、前景閒置 5 分鐘後釋放；系統背景回收或舊版記憶體壓力訊號
 *   提前要求釋放。即時翻關或不需保暖的佇列收尾仍可呼叫 [shutdown]。
 *
 * **記憶體**：模型的實際常駐成本依後端、執行設定及裝置而異，不能以模型檔案大小估計。
 * 釋放只在所有已登記的操作結束後進行；新操作可取消過時的閒置決定，下次需要時 lazy 重建。
 */
class TranslationEngineService(private val context: Context) {

    private val translationPreferences: TranslationPreferences = Injekt.get()

    /**
     * 只序列化引擎**生命週期**（建/重建/關）＋在飛計數的註冊點——**不**序列化每頁推論（跨頁流水線靠此）。
     * 每頁翻譯只在此鎖下短暫「確保引擎已建 + [inFlight]++」，隨即放鎖去跑 `engine.translatePage`（可多頁並發）。
     */
    private val mutex = Mutex()

    /** 目前在引擎內飛的頁數（跨頁併發下 >1）。關閉前等它歸零，避免關掉正在用的 native session（use-after-close）。 */
    private val inFlight = AtomicInteger(0)

    /**
     * 目前這個 build 是否已完成過**至少一次**推論（各原生 session 的首次 lazy 初始化都在單緒跑過了）。
     * false＝冷引擎（剛建好、還沒推論過）→ 第一頁**持鎖單緒**跑完再放行併發，避免多頁同時打進未初始化的
     * NCNN session → 原生 SIGSEGV（「開 app 後第一本翻譯就閃退」的真凶）。重建（[closeEngine]）會重置成 false。
     * 只在 [mutex] 下讀寫。
     */
    private var warmedUp = false

    /** Warmup/release always use IO; main-thread lifecycle callbacks never close native models. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val idleRelease: IdleModelReleaseController = IdleModelReleaseController(scope, release = { request ->
        scope.launch { releaseRequestedEngine(request) }
    })

    /** 常駐引擎（lazy 建、跨頁/章復用）。null＝還沒建或已 [shutdown]。只在 [mutex] 下讀寫。 */
    private var engine: TranslationEngine? = null

    /** 上次建引擎時的設定簽章（含去字法）；與當前簽章不同 → 設定/去字法改過 → 重建。只在 [mutex] 下讀寫。 */
    private var builtSignature: String? = null

    /** 引擎是否正在建構：給 reader 角落指示器顯示「引擎載入中…」。建構期間 true、完成/失敗轉 false。 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 引擎是否已預載（warm，建好常駐中）：給書庫工具列圖示顯示「有/無預載」。建好＝true、釋放＝false。 */
    private val _warm = MutableStateFlow(false)
    val warm: StateFlow<Boolean> = _warm.asStateFlow()

    /**
     * 引擎是否就緒：key 有設 + 3 顆模型齊。給 [ChapterLoader] 決定要不要包 [TranslatingPageLoader]。
     *
     * **刻意不檢查 [TranslationPreferences.translationEnabled]**——那是「下載時翻譯章節」（離線整章翻）的開關，
     * 手動翻的使用者常關著它；即時翻譯由 [TranslationPreferences.liveTranslate] 獨立控制（在 [ChapterLoader.shouldTranslateLive] 檢查），
     * 故此處只看 key + 模型，否則即時翻會被 translationEnabled 靜默擋掉。
     */
    fun isReady(): Boolean {
        if (!translationPreferences.hasCredentials()) return false
        if (TranslationEngineConfig.isProviderBaseMissing(translationPreferences)) return false
        return TranslationEngineConfig.modelsResolvable(context)
    }

    /**
     * 用 warm 引擎翻譯單頁，回**原始 [PageResult]**（Translated/Skipped/Failed 不收斂成 null——
     * 呼叫端 [PageTranslator.translateChapter] 要靠這三態做 §11：成功覆蓋+記 manifest／略過記 manifest／失敗留原圖可重試）。
     *
     * 不 recycle 輸入 [src]（所有權屬呼叫端）；成功時 [PageResult.Translated.page] 是引擎產出的新 bitmap。
     *
     * 引擎建不起來（缺模型/缺 key/建構例外）→ 回 [PageResult.Failed]（不丟例外）：呼叫端把它當「該頁失敗、留原圖」處理，
     * 不會誤把原圖蓋掉、也不會中斷整章迴圈（§11）。
     *
     * **並發**：只在 [mutex] 下短暫「確保引擎已建（模型 SAF→filesDir 複製也在此）＋ [inFlight]++」，
     * 隨即放鎖去跑 `engine.translatePage`——所以多頁可同時在引擎內（跨頁流水線）。關閉會等 [inFlight] 歸零。
     *
     * @param methodRaw 去字法原始字串（boxfill / auto_whole / auto_tile）；與 warm 引擎當前去字法不同 → 重建引擎。
     *   同一章逐頁去字法相同 → 首頁後 [ensureEngine] 是 no-op、不重建（重建只在章邊界、此時 [inFlight]=0）。
     */
    suspend fun translatePage(src: Bitmap, methodRaw: String): PageResult = withEngineWork {
        // 在鎖下：確保引擎已建、註冊在飛頁，並判定「這是不是這個 build 的第一次推論」（[warmedUp]，鎖下讀無 TOCTOU）。
        // 冷引擎的第一頁 → **持鎖單緒**跑完（讓 NCNN 各 session 首次 lazy 初始化在單緒完成），之後才放行併發。
        // 修「開 app 後第一本翻譯（下載/即時皆是），多頁同時打進剛載好、還沒推論過的原生 session → SIGSEGV 閃退」。
        TraceLog.log(
            "svc",
            "translatePage.enter ${src.width}x${src.height} method=$methodRaw warm=$warmedUp if=${inFlight.get()}",
        )
        var engineForConcurrent: TranslationEngine? = null
        val handled: PageResult? = mutex.withLock {
            val e = ensureEngine(methodRaw)
                ?: return@withLock PageResult.Failed(context.stringResource(MR.strings.engine_unavailable))
            inFlight.incrementAndGet() // 在鎖下註冊 → 關閉不會在註冊中途插入而漏算在飛頁
            if (!warmedUp) {
                // 冷引擎首次推論：整段持鎖跑（其他頁在 mutex.withLock 上等），暖完設 warmedUp=true 才放行併發。
                // 代價＝只有冷啟動後第一本的第一頁不被跨頁重疊（含一次網路往返）；換得不再撞冷 session。
                try {
                    e.translatePage(src).also { warmedUp = true }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ex: Throwable) {
                    logcat(LogPriority.ERROR, ex) { "warm 引擎翻譯單頁失敗（冷引擎首次）" }
                    PageResult.Failed(ex.message ?: context.stringResource(MR.strings.translate_exception))
                } finally {
                    inFlight.decrementAndGet()
                }
            } else {
                engineForConcurrent = e // 已暖 → 放鎖後併發跑
                null
            }
        }
        if (handled != null) return@withEngineWork handled
        // 已暖 → 放鎖、多頁並發進引擎（跨頁流水線）。
        val engine = engineForConcurrent!!
        TraceLog.log("svc", "translatePage.concurrent.call inFlight=${inFlight.get()}")
        try {
            engine.translatePage(src)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "warm 引擎翻譯單頁失敗" }
            PageResult.Failed(e.message ?: context.stringResource(MR.strings.translate_exception))
        } finally {
            inFlight.decrementAndGet()
            TraceLog.log("svc", "translatePage.concurrent.done inFlight=${inFlight.get()}")
        }
    }

    /**
     * 預暖機：先把引擎建好（不翻任何頁），讓第一章/第一頁瞬間就緒。best-effort，建不起來只記 log、不丟例外。
     * 由即時翻譯開關打開時呼叫（見 [SettingsTranslationScreen]）；不呼叫也無妨——首次 [translatePage] 會 lazy 建。
     *
     * @param methodRaw 預暖用的去字法（預設＝目前全域去字偏好；為即時翻譯預暖走 [warmUpAsync] 的 forLive，用即時翻譯的去字法）；
     * 之後 [translatePage] 帶不同去字法仍會按簽章重建。
     */
    suspend fun warmUp(methodRaw: String = translationPreferences.inpaintMethod.get()) = withEngineWork {
        mutex.withLock {
            ensureEngine(methodRaw)
            Unit
        }
    }

    private suspend fun <T> withEngineWork(block: suspend () -> T): T {
        idleRelease.onWorkStarted()
        return try {
            block()
        } finally {
            // Synchronous bookkeeping survives cancellation. Deferred close runs in the service's
            // independent IO scope, rather than inheriting the cancelled page's Job.
            idleRelease.onWorkFinished()
        }
    }

    /**
     * 取常駐引擎；簽章（含去字法）變了先關舊再重建，沒建過就建。建不起來（模型缺/例外）回 null。
     * **僅在 [mutex] 下呼叫**（[translatePage]/[warmUp] 內）。
     *
     * **併發安全（關鍵）**：重建要 [closeEngine]（釋放 native session）——但**絕不可**在有頁在飛（[inFlight]>0）時關，
     * Wait for existing calls to finish while holding the registration mutex. Their completion uses
     * only the atomic counter, so they can drain without acquiring this mutex. New calls must never
     * reuse an engine with stale endpoint/key settings. Cancellation releases the lock without closing
     * sessions that are still in use.
     */
    private suspend fun ensureEngine(methodRaw: String): TranslationEngine? {
        var preferences = translationPreferences.snapshot()
        var signature = configSignature(methodRaw, preferences)
        val current = engine
        if (current != null && signature == builtSignature) return current

        // Configuration changed: drain old calls before replacing their native sessions.
        if (current != null) {
            // Existing calls decrement without this mutex. Keep registration locked while waiting,
            // so a new request can never be sent using the previous endpoint, key or language.
            while (inFlight.get() > 0) delay(20)
            preferences = translationPreferences.snapshot()
            signature = configSignature(methodRaw, preferences)
            if (signature == builtSignature) return current
        }
        // inFlight==0（或還沒建過）→ 安全關舊重建，舊 native sessions 釋放後才建立新模型。
        // ★ 診斷紀錄是給使用者分享的：簽章含 API key，只能記遮蔽版（0.16.8–0.22.0 曾整串明文寫進紀錄）
        TraceLog.log("svc", "ensureEngine.rebuild method=${signatureMethod(methodRaw)} hadEngine=${current != null}")
        closeEngine()

        // 真正建構期間 → loading=true，給 reader 指示器顯示「引擎載入中…」。finally 確保任何出口都歸位。
        _loading.value = true
        return try {
            // 模型解析 + 字元表（SAF→filesDir 複製在此；缺模型回 null）。與離線翻共用同一份解析。
            TraceLog.log("svc", "ensureEngine.resolveModels")
            val bundle = TranslationEngineConfig.resolveModelSet(context) ?: return null
            // 去字法照呼叫端傳入（佇列逐章帶來的去字法），其餘參數（語言/緒數/排版…）照使用者設定。
            val cfg = TranslationEngineConfig.buildEngineConfig(preferences, methodRaw)
            TraceLog.log("svc", "ensureEngine.create.start")
            val built = Yakuyomi.create(bundle.models, bundle.alphabet, preferences.activeApiKey(), cfg)
            TraceLog.log("svc", "ensureEngine.create.done")
            engine = built
            builtSignature = signature
            _warm.value = true
            // 建構後**單緒**暖各原生 session（detector/OCR/去字 各空跑一次推論）→ 首次真推論不再撞冷 session。
            // 成功 → warmedUp=true：第一頁即可全併發、零損失。失敗 → 留 false，退回 translatePage 的第一頁序列化保險。
            runCatching { built.warmUp() }
                .onSuccess { warmedUp = true }
                .onFailure { logcat(LogPriority.WARN, it) { "引擎暖機失敗，退回第一頁序列化保險" } }
            built
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "建翻譯引擎失敗" }
            closeEngine()
            null
        } finally {
            _loading.value = false
        }
    }

    /**
     * 影響引擎建構的設定 + 去字法的簽章。值變了＝要重建引擎（設定/去字法即時生效）。
     * 去字法（[methodRaw] 映射後的引擎 method，見 [signatureMethod]）納入 → 章與章間換去字法會重建；其餘語言/緒數/OCR/排版等
     * 改了也重建。
     *
     * **維護鐵則：本清單必須涵蓋 [TranslationEngineConfig.buildEngineConfig] 讀到的每一個 pref**
     * （＋ [TranslationPreferences.activeApiKey]，它不在 buildEngineConfig 裡、是另外傳給 `Yakuyomi.create`）；
     * pref 那部分在 [EngineConfigMapping.signatureFields]。
     * 漏一個＝改了那個設定卻沿用 warm 引擎、舊值繼續生效（曾漏 provider/model/apiBase/temperature →
     * 使用者在設定換 model 後請求仍打舊 model、持續 HTTP 400；改 API key 反而生效，只因 key 有在簽章裡）。
     * 日後在 buildEngineConfig 加讀任何 pref，**同時**在 [EngineConfigMapping.signatureFields] 加一行
     * （EngineConfigMappingTest 會擋）。
     */
    private fun configSignature(methodRaw: String, preferences: TranslationPreferences): String =
        // NUL 分隔避免相鄰欄位串接後碰撞
        (
            listOf(
                signatureMethod(methodRaw),
                preferences.activeApiKey(),
            ) + settingsFields(preferences)
            ).joinToString("\u0000")

    /**
     * 簽章裡的去字法用映射後的引擎 method（boxfill／aot），不用原始字串：引擎只看映射後的值，auto_whole 與舊版的
     * auto_tile／auto_aot 建出來是同一個引擎。用原始字串的話，舊使用者的即時翻去字法還是 auto_tile、一般去字法已遷移成
     * auto_whole，reader「翻譯這頁」與即時翻整章交替時每次都重新載入模型並暖機。
     */
    private fun signatureMethod(methodRaw: String): String = EngineConfigMapping.mapInpaintMethod(methodRaw)

    /**
     * [configSignature] 去字法與 API key 以外的欄位（API key 不經 buildEngineConfig，另外直接餵 Yakuyomi.create）。
     * 清單本身在 [EngineConfigMapping.signatureFields]，與 buildEngineConfig 同檔，
     * 有反射測試守著「buildEngineConfig 讀到的 pref 都在簽章裡」。
     */
    private fun settingsFields(
        preferences: TranslationPreferences,
    ): List<String> = EngineConfigMapping.signatureFields(preferences)

    /** Clear observable state under [mutex]; no native work while holding the policy's short lock. */
    private fun detachEngine(): TranslationEngine? {
        val detached = engine
        engine = null
        builtSignature = null
        _warm.value = false
        warmedUp = false
        return detached
    }

    /** Native close stays serialized with acquisition, so old/new model generations never overlap. */
    private fun closeDetachedEngine(detached: TranslationEngine?) {
        try {
            detached?.close()
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "關閉翻譯引擎時例外" }
        }
    }

    /** Existing configuration replacement already holds [mutex] and has drained [inFlight]. */
    private fun closeEngine() = closeDetachedEngine(detachEngine())

    private data class ReleaseTarget(val engine: TranslationEngine?)

    private suspend fun releaseRequestedEngine(request: IdleModelReleaseController.Request): Unit = withContext(
        Dispatchers.IO,
    ) {
        mutex.withLock {
            // consume() rechecks generation and *all* registered operations after acquiring the
            // lifecycle mutex. New work/foreground can invalidate a queued idle close meanwhile.
            val target = idleRelease.consume(request) {
                check(inFlight.get() == 0) { "Cannot release an engine with active pages" }
                ReleaseTarget(detachEngine())
            }
            if (target != null) {
                try {
                    closeDetachedEngine(target.engine)
                } finally {
                    request.completed.complete(Unit)
                }
                if (target.engine != null) {
                    TraceLog.log("svc", "engine.release reason=${request.reason.name.lowercase()}")
                }
            }
        }
    }

    /**
     * Explicit shutdown never interrupts current work and is not cancelled by foreground reentry.
     * If pages are active, their final completion consumes the retained request asynchronously.
     * This suspending entry waits for native close to finish; no lifecycle lock is held while
     * waiting for pages, and cancellation of the caller does not cancel the retained request.
     */
    suspend fun shutdown() {
        val request = idleRelease.request(IdleModelReleaseController.Reason.EXPLICIT)
        releaseRequestedEngine(request)
        request.completed.await()
    }

    fun onForeground() = idleRelease.onForeground()

    fun onBackground() = idleRelease.onBackground()

    /** Safe from memory callbacks: queue IO close, or defer it until current work drains. */
    fun onMemoryPressure() {
        idleRelease.request(IdleModelReleaseController.Reason.MEMORY_PRESSURE)
    }

    /**
     * [warmUp] 的 fire-and-forget 版（背景 IO、不阻塞呼叫端）：給 UI 即時翻開關用，避免在主執行緒載入模型。
     *
     * [forLive]＝為即時翻譯預暖（預設：即時翻譯開著就是）：用即時翻譯的去字法（[TranslationPreferences.liveInpaintMethod]）
     * 建——即時翻譯的頁帶這個去字法進來，用全域去字法建的話兩者設得不同時第一頁就整個重建、預暖白做（2026-10-07 審查）。
     * 否則用全域去字法（佇列的下載／手動翻）。去字法在呼叫當下讀：即時翻譯開關的 onValueChanged 在偏好寫入**之前**跑，那裡要明講
     * `forLive = true`。
     */
    fun warmUpAsync(forLive: Boolean = translationPreferences.liveTranslate.get()) {
        val method = if (forLive) {
            translationPreferences.liveInpaintMethod.get()
        } else {
            translationPreferences.inpaintMethod.get()
        }
        scope.launch { runCatching { warmUp(method) } }
    }

    /** [shutdown] 的 fire-and-forget 版（背景 IO、不阻塞呼叫端）：給 UI 即時翻開關用，避免在主執行緒上等鎖/關閉 → ANR/crash。 */
    fun shutdownAsync() {
        idleRelease.request(IdleModelReleaseController.Reason.EXPLICIT)
    }

    /**
     * Compatibility entry point. Despite its old name, it never blocks a callback or closes on
     * main. Unlike the old tryLock/drop path, a busy engine retains the pressure request.
     */
    fun shutdownBlocking() {
        onMemoryPressure()
    }
}
