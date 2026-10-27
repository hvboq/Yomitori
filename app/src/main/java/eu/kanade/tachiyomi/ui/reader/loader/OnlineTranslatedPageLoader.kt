package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationFailureCode
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** HTTP image loading and selected-page OCR are deliberately different entry points. */
internal class OnlineTranslatedPageLoader(
    private val delegate: HttpPageLoader,
    private val chapter: ReaderChapter,
    private val manga: Manga,
    context: Context,
    cache: OnlinePageCache? = null,
    translate: (suspend (StoredPage) -> PageTranslator.SinglePageOutcome)? = null,
) : PageLoader() {
    data class StoredPage(val chapterDir: UniFile, val pageFile: UniFile)

    enum class Stage { WAITING_IMAGE, CAPTURING, TRANSLATING, FINISHED, FAILED }

    data class Progress(
        val page: ReaderPage,
        val stage: Stage,
        val result: PageTranslator.SinglePageResult? = null,
        val retryVersion: Long = 0L,
        val failureCode: TranslationFailureCode? = null,
    ) {
        val pageIndex: Int get() = page.index

        /** Bind progress to the selection that requested it, before showing state or a result. */
        fun matchesSelection(
            selectedPage: ReaderPage,
            retryVersion: Long,
            activeLoader: PageLoader,
            activeChapter: ReaderChapter?,
            currentChapter: ReaderChapter?,
        ): Boolean = page === selectedPage && this.retryVersion == retryVersion &&
            activeChapter != null && currentChapter === activeChapter &&
            page.chapter === activeChapter && page.chapter.pageLoader === activeLoader
    }

    private data class Request(
        val page: ReaderPage,
        val translate: Boolean,
        val explicitRetry: Boolean,
        val generation: Long,
        val retryVersion: Long,
    )

    private data class TerminalResult(
        val stage: Stage,
        val result: PageTranslator.SinglePageResult? = null,
        val failureCode: TranslationFailureCode? = null,
    )

    private sealed interface Command {
        data class Select(val request: Request) : Command
        data class Stop(
            val complete: CompletableDeferred<Unit>? = null,
            val releaseSelection: Boolean = false,
        ) : Command
    }

    private data class Captured(val page: ReaderPage, val entry: OnlinePageCache.Entry)

    private val translator = PageTranslator(context)

    // Failure to create the translation cache must not prevent the HTTP original from loading.
    private val cacheStore by lazy { cache ?: OnlinePageCache(context) }
    private val translationManager: TranslationManager = Injekt.get()
    private val translatePage: suspend (StoredPage) -> PageTranslator.SinglePageOutcome = translate ?: { stored ->
        translator.translateSinglePageDetailed(
            stored.chapterDir,
            checkNotNull(stored.pageFile.name),
            translationManager.liveInpaintMethod(),
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val monitor = Any()
    private val captureMutex = Mutex()
    private val operationMutex = Mutex()
    private val captured = mutableMapOf<Int, Captured>()
    private val attempted = ConcurrentHashMap.newKeySet<String>()
    private val terminalResults = ConcurrentHashMap<String, TerminalResult>()
    private val explicitlyStopped = mutableSetOf<Int>()
    private var generation = 0L
    private var requested: Request? = null
    private var selectedPage: ReaderPage? = null
    private var selectedLease: OnlinePageCache.Lease? = null
    private var pages: List<ReaderPage> = emptyList()
    private val mutableProgress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = mutableProgress.asStateFlow()

    override var isLocal: Boolean = false

    /** A command is acknowledged only after the previous owned coroutine/native call has drained. */
    private val controller = scope.launch {
        var active: Job? = null
        var pendingAcknowledgement: CompletableDeferred<Unit>? = null
        try {
            for (command in commands) {
                if (command is Command.Select && !isCurrent(command.request)) continue
                pendingAcknowledgement = (command as? Command.Stop)?.complete
                active?.cancelAndJoin()
                active = null
                when (command) {
                    is Command.Select -> {
                        // A newer request may arrive while the previous native call drains.
                        if (!isCurrent(command.request)) continue
                        if (!command.request.translate) {
                            captured(command.request.page)?.entry?.directory?.path?.let {
                                attempted.remove(it)
                                terminalResults.remove(it)
                            }
                        }
                        releaseSelected()
                        mutableProgress.value = null
                        active = scope.launch { process(command.request) }
                    }
                    is Command.Stop -> {
                        // Manual re-rendering belongs to this loader too. A chapter handoff
                        // must wait for its transactional write/native call to drain.
                        operationMutex.withLock { }
                        mutableProgress.value = null
                        if (command.releaseSelection) releaseSelected()
                        command.complete?.complete(Unit)
                        pendingAcknowledgement = null
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                active?.cancelAndJoin()
                operationMutex.withLock { }
                releaseSelected()
                mutableProgress.value = null
                // recycle() can cancel the controller after a Stop was dequeued but before
                // its drain completed. That caller still needs its acknowledgement.
                pendingAcknowledgement?.complete(Unit)
                while (true) {
                    val pending = commands.tryReceive().getOrNull() ?: break
                    (pending as? Command.Stop)?.complete?.complete(Unit)
                }
            }
        }
    }

    override suspend fun getPages(): List<ReaderPage> = delegate.getPages().onEach { it.chapter = chapter }.also {
        synchronized(monitor) { pages = it }
    }

    override suspend fun loadPage(page: ReaderPage) {
        val hit = captured(page)
        if (hit != null && cacheStore.exists(hit.entry)) {
            val held = runCatching { cacheStore.retain(hit.entry) }.getOrNull()
            if (held != null) {
                held.use {
                    attach(hit, reload = false)
                    awaitCancellation()
                }
            }
        }
        if (hit != null) {
            forgetExpired(hit)
            withContext(Dispatchers.Main.immediate) {
                page.clearOriginalPreview()
                page.uri = null
                page.stream = null
                page.status = Page.State.Queue
            }
        }
        delegate.loadPage(page)
    }

    /** Explicit image refresh never reruns OCR on an already captured authority. */
    override fun retryPage(page: ReaderPage) {
        val hit = captured(page)
        if (hit != null && cacheStore.exists(hit.entry)) {
            scope.launch {
                // Existing manual writes drain this owner first. Keep their method/original
                // choice instead of undoing it automatically when the user revisits the page.
                synchronized(monitor) {
                    if (requested == null && selectedPage === page) explicitlyStopped.add(page.index)
                }
                attach(hit, reload = true)
            }
        } else {
            page.status = Page.State.Queue
            delegate.retryPage(page)
            val old = synchronized(monitor) { requested }
            if (old?.page === page) select(page, old.translate, explicitRetry = true, retryVersion = old.retryVersion)
        }
    }

    /** Only the reader's selected-page event (or explicit retry) may request page inference. */
    fun select(page: ReaderPage, translate: Boolean, explicitRetry: Boolean = false, retryVersion: Long = 0L) {
        synchronized(monitor) {
            if (isRecycled || pages.getOrNull(page.index) !== page) return
            val old = requested
            if (!explicitRetry && old?.page === page && old.translate == translate &&
                old.retryVersion == retryVersion
            ) {
                return
            }
            generation++
            selectedPage = page
            if (explicitRetry) explicitlyStopped.remove(page.index)
            val next = Request(page, translate, explicitRetry, generation, retryVersion).also { requested = it }
            // The non-blocking enqueue must have the same order as the state change.
            commands.trySend(Command.Select(next))
        }
    }

    /** Preserve the visible-page lease while stopping inference; the Stop UI waits for drain. */
    fun stop() {
        synchronized(monitor) {
            if (isRecycled) return
            selectedPage?.let { explicitlyStopped.add(it.index) }
            generation++
            requested = null
            commands.trySend(Command.Stop())
        }
    }

    /** Chapter/reader ownership handoff. A new loader must not infer before this returns. */
    suspend fun stopAndJoin(keepSelection: Boolean = false): Long {
        val done = CompletableDeferred<Unit>()
        val (stoppedGeneration, submitted) = synchronized(monitor) {
            generation++
            requested = null
            if (!keepSelection) selectedPage = null
            generation to commands.trySend(Command.Stop(done, releaseSelection = !keepSelection)).isSuccess
        }
        if (submitted) {
            done.await()
        } else {
            controller.join()
        }
        return stoppedGeneration
    }

    /** Exact memory-bound URI resolution; it never searches arbitrary private/user paths. */
    fun resolvePage(page: ReaderPage): StoredPage? {
        val hit = captured(page) ?: return null
        if (page.uri != hit.entry.uri || !cacheStore.exists(hit.entry)) return null
        return StoredPage(hit.entry.chapterDir, hit.entry.pageFile)
    }

    fun retainPage(page: ReaderPage): Closeable? {
        val hit = captured(page) ?: return null
        if (page.uri != hit.entry.uri) return null
        return runCatching { cacheStore.retain(hit.entry) }.getOrNull()
    }

    /** Manual storage/native operations share the selected-page drain and disk reservation. */
    suspend fun withPageOperation(page: ReaderPage, block: suspend (StoredPage) -> Boolean): Boolean {
        if (synchronized(monitor) { isRecycled || selectedPage !== page }) return false
        val operationGeneration = stopAndJoin(keepSelection = true)
        return operationMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (synchronized(monitor) {
                    isRecycled || selectedPage !== page || generation != operationGeneration
                }
            ) {
                return@withLock false
            }
            val hit = captured(page) ?: return@withLock false
            cacheStore.retain(hit.entry).use {
                cacheStore.reserveProcessing(hit.entry).use {
                    cacheStore.invalidateCompletion(hit.entry)
                    block(StoredPage(hit.entry.chapterDir, hit.entry.pageFile)).also { complete ->
                        if (complete) runCatching { cacheStore.recordCompletion(hit.entry) }
                    }
                }
            }
        }
    }

    /** Capture-only entry for selected-page menus. It never initializes a model or calls an API. */
    suspend fun ensurePage(page: ReaderPage): StoredPage? = captureMutex.withLock {
        if (isRecycled || synchronized(monitor) { selectedPage !== page }) return@withLock null
        page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
            .let { if (it is Page.State.Error) throw IOException("Original online page failed to load") }
        currentCoroutineContext().ensureActive()
        val previous = captured(page)
        val existing = previous?.takeIf { cacheStore.exists(it.entry) }
        if (previous != null && existing == null) forgetExpired(previous)
        val hit = if (existing != null) {
            currentCoroutineContext().ensureActive()
            if (isRecycled || synchronized(monitor) { selectedPage !== page }) return@withLock null
            if (selectedLease?.entry?.directory != existing.entry.directory) {
                selectedLease?.close()
                selectedLease = cacheStore.retain(existing.entry)
            }
            existing
        } else {
            val identity = OnlinePageCache.sha(
                listOf(
                    "online-reader-v1",
                    manga.source.toString(),
                    manga.url,
                    chapter.chapter.url,
                    page.index.toString(),
                    page.url,
                ).joinToString("") { "${it.length}:$it" },
            )
            val held = cacheStore.capture(identity) { delegate.openOriginalStream(page) }
            if (isRecycled || synchronized(monitor) { selectedPage !== page }) {
                held.close()
                return@withLock null
            }
            selectedLease?.close()
            selectedLease = held
            Captured(page, held.entry).also { synchronized(monitor) { captured[page.index] = it } }
        }
        synchronized(monitor) {
            captured.values.filter { it.page !== page && !cacheStore.exists(it.entry) }.forEach(::forgetExpired)
        }
        attach(hit, reload = false)
        StoredPage(hit.entry.chapterDir, hit.entry.pageFile)
    }

    private suspend fun process(request: Request) = operationMutex.withLock {
        processLocked(request)
    }

    private suspend fun processLocked(request: Request) {
        var attemptKey: String? = null
        try {
            update(request, Stage.WAITING_IMAGE)
            request.page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                .let { if (it is Page.State.Error) throw IOException("Original online page failed to load") }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(request)) return
            update(request, Stage.CAPTURING)
            val stored = ensurePage(request.page) ?: return
            val hit = captured(request.page) ?: return
            if (!request.translate) {
                if (isCurrent(request)) mutableProgress.value = null
                return
            }
            if (!request.explicitRetry && synchronized(monitor) { request.page.index in explicitlyStopped }) {
                if (isCurrent(request)) mutableProgress.value = null
                return
            }
            val key = hit.entry.directory.path
            if (!request.explicitRetry) {
                val completed = translator.isPageTranslated(stored.chapterDir, stored.pageFile.name!!)
                if (completed || key in attempted) {
                    val previous = terminalResults[key]
                    if (!completed && previous?.result == PageTranslator.SinglePageResult.TRANSLATED) {
                        // A manual restore may revoke a formerly completed page between visits.
                        if (isCurrent(request)) mutableProgress.value = null
                        return
                    }
                    update(
                        request,
                        if (completed) Stage.FINISHED else previous?.stage ?: Stage.FAILED,
                        previous?.result ?: if (completed) {
                            PageTranslator.SinglePageResult.TRANSLATED
                        } else {
                            PageTranslator.SinglePageResult.NOT_TRANSLATED
                        },
                        previous?.failureCode,
                    )
                    return
                }
            }
            attempted += key
            attemptKey = key
            currentCoroutineContext().ensureActive()
            if (!isCurrent(request)) return
            cacheStore.retain(hit.entry).use {
                cacheStore.reserveProcessing(hit.entry).use {
                    val before = OnlinePageCache.sha(hit.entry.page)
                    update(request, Stage.TRANSLATING)
                    try {
                        cacheStore.invalidateCompletion(hit.entry)
                        val outcome = translatePage(stored)
                        val result = outcome.result
                        val failureCode = outcome.failureCode.takeIf {
                            result == PageTranslator.SinglePageResult.NOT_TRANSLATED
                        }
                        currentCoroutineContext().ensureActive()
                        if (result == PageTranslator.SinglePageResult.TRANSLATED) {
                            runCatching { cacheStore.recordCompletion(hit.entry) }
                        }
                        if (result == PageTranslator.SinglePageResult.TRANSLATED ||
                            result == PageTranslator.SinglePageResult.PARTIAL ||
                            result == PageTranslator.SinglePageResult.SKIPPED
                        ) {
                            withContext(Dispatchers.Main.immediate) {
                                if (isCurrent(request)) request.page.clearOriginalPreview()
                            }
                        }
                        val final = when (result) {
                            PageTranslator.SinglePageResult.TRANSLATED,
                            PageTranslator.SinglePageResult.PARTIAL,
                            PageTranslator.SinglePageResult.SKIPPED,
                            -> Stage.FINISHED
                            else -> if (translator.isPageTranslated(stored.chapterDir, stored.pageFile.name!!)) {
                                Stage.FINISHED
                            } else {
                                Stage.FAILED
                            }
                        }
                        terminalResults[key] = TerminalResult(final, result, failureCode)
                        update(request, final, result, failureCode)
                    } finally {
                        withContext(NonCancellable) {
                            if (cacheStore.exists(hit.entry) && OnlinePageCache.sha(hit.entry.page) != before) {
                                attach(hit, reload = true)
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            attemptKey?.let {
                attempted.remove(it)
                terminalResults.remove(it)
            }
            throw cancelled
        } catch (_: OnlinePageCache.TooManyPixels) {
            update(request, Stage.FAILED, PageTranslator.SinglePageResult.TOO_LARGE)
        } catch (_: OnlinePageCache.TooLarge) {
            attemptKey?.let { terminalResults[it] = TerminalResult(Stage.FAILED) }
            // This is the private online-cache bound, not PageTranslator's different pixel cap.
            update(request, Stage.FAILED, PageTranslator.SinglePageResult.NOT_TRANSLATED)
        } catch (_: Exception) {
            attemptKey?.let { terminalResults[it] = TerminalResult(Stage.FAILED) }
            update(request, Stage.FAILED, PageTranslator.SinglePageResult.NOT_TRANSLATED)
        } finally {
            runCatching { cacheStore.trim() }
        }
    }

    private fun isCurrent(request: Request) = synchronized(monitor) {
        !isRecycled && requested?.generation == request.generation && selectedPage === request.page
    }

    private fun update(
        request: Request,
        stage: Stage,
        result: PageTranslator.SinglePageResult? = null,
        failureCode: TranslationFailureCode? = null,
    ) {
        if (request.translate && isCurrent(request)) {
            mutableProgress.value = Progress(request.page, stage, result, request.retryVersion, failureCode)
        }
    }

    private fun captured(page: ReaderPage): Captured? = synchronized(monitor) {
        captured[page.index]?.takeIf { it.page === page }
    }

    private fun forgetExpired(hit: Captured) {
        synchronized(monitor) {
            if (captured.remove(hit.page.index, hit)) {
                // Re-creation at the same identity/source hash is a new cache authority. A previous
                // successful attempt must not be mistaken for a manual original restore.
                attempted.remove(hit.entry.directory.path)
                terminalResults.remove(hit.entry.directory.path)
            }
        }
    }

    private suspend fun attach(hit: Captured, reload: Boolean) = withContext(Dispatchers.Main.immediate) {
        val valid = synchronized(monitor) {
            !isRecycled && captured[hit.page.index] === hit && pages.getOrNull(hit.page.index) === hit.page
        }
        if (!valid || !cacheStore.exists(hit.entry)) return@withContext
        val page = hit.page
        val authorityChanged = page.uri != hit.entry.uri
        val replacesReadyImage = authorityChanged && page.status == Page.State.Ready
        if (authorityChanged) page.clearOriginalPreview()
        page.stream = { cacheStore.open(hit.entry) }
        page.uri = hit.entry.uri
        page.status = Page.State.Ready
        // Ready-to-Ready is conflated even when the cached display differs from the HTTP original.
        if (reload || replacesReadyImage) page.reload()
    }

    private suspend fun releaseSelected() = captureMutex.withLock {
        selectedLease?.close()
        selectedLease = null
    }

    override fun recycle() {
        super.recycle()
        synchronized(monitor) {
            generation++
            requested = null
            selectedPage = null
        }
        commands.close()
        scope.cancel()
        delegate.recycle()
    }
}
