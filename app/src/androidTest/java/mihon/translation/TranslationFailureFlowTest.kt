package mihon.translation

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStatsStore
import eu.kanade.tachiyomi.data.translation.TranslationStore
import eu.kanade.tachiyomi.data.translation.model.TranslationFailureCode
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Actual bounded loopback LLM HTTP, scripted inference, real page storage and singleton queue. */
@RunWith(AndroidJUnit4::class)
class TranslationFailureFlowTest {
    @Test(timeout = 120_000)
    fun actualHttpFailuresReachDetailedChapterResultWithoutCompletingOrReplacingThePage(): Unit = runBlocking {
        withTranslationFailureFixture { f ->
            for ((status, expected, postProcessFailure) in listOf(
                Triple(401, TranslationFailureCode.SERVER_AUTH, false),
                Triple(403, TranslationFailureCode.SERVER_AUTH, false),
                Triple(429, TranslationFailureCode.SERVER_RATE_LIMIT, false),
                Triple(503, TranslationFailureCode.SERVER_HTTP, false),
                Triple(200, TranslationFailureCode.SERVER_RESPONSE, false),
                Triple(200, TranslationFailureCode.UNKNOWN, true),
            )) {
                FailureLoopbackServer(status, validResponse = postProcessFailure).use { server ->
                    val engine = FailureHttpEngine(server, FAILURE_QUERIES, postProcessFailure = postProcessFailure)
                    f.install(engine)
                    val original = f.page.readBytes()
                    val result = f.translator.translateChapterDetailed(f.chapterFile, FAILURE_METHOD)
                    server.assertRequests(FAILURE_QUERIES)
                    assertEquals("FAILURE_HTTP_LOGICAL_PAGE_ATTEMPT", 1, engine.calls)
                    assertEquals("FAILURE_HTTP_CODE_$status", expected, result.failureCode)
                    assertEquals("FAILURE_HTTP_NO_COMPLETED_PAGE_$status", 0, result.translatedPages)
                    assertArrayEquals("FAILURE_HTTP_SOURCE_PRESERVED_$status", original, f.page.readBytes())
                    assertFalse(f.translator.isChapterTranslated(f.chapterFile))
                    f.service.shutdown()
                }
            }
        }
    }

    @Test(timeout = 120_000)
    fun actualHttpCausePersistsThroughQueueAndStoreAndPublicRetryClearsIt(): Unit = runBlocking {
        withTranslationFailureFixture { f ->
            FailureLoopbackServer(401).use { server ->
                f.installAndRun(FailureHttpEngine(server, FAILURE_QUERIES)) {
                    f.manager.translate(f.manga, listOf(f.chapter), method = FAILURE_METHOD)
                }
                f.awaitError(TranslationFailureCode.SERVER_AUTH)
                server.assertRequests(FAILURE_QUERIES)
                f.joinDrain()
                assertEquals(
                    "FAILURE_QUEUE_STORE_CODE",
                    TranslationFailureCode.SERVER_AUTH,
                    f.store.restore().single().failureCode,
                )
                val raw = requireNotNull(f.queuePreferences.getString("0", null))
                assertFalse("FAILURE_STORE_NEVER_PERSISTS_PROVIDER_BODY", raw.contains(FAILURE_CANARY))
                assertFalse("FAILURE_STORE_NEVER_PERSISTS_OCR_TEXT", raw.contains(FAILURE_QUERIES.first()))
                assertEquals("server_auth", JSONObject(raw).getString("failureCode"))
                val unknown = JSONObject(raw).put("failureCode", "future_neutral_failure").toString()
                check(f.queuePreferences.edit().putString("0", unknown).commit())
                assertEquals(
                    "FAILURE_STORE_UNKNOWN_WIRE",
                    TranslationFailureCode.UNKNOWN,
                    f.store.restore().single().failureCode,
                )
                val legacy = JSONObject(raw).apply { remove("failureCode") }.toString()
                check(f.queuePreferences.edit().putString("0", legacy).commit())
                assertEquals("FAILURE_STORE_LEGACY_WIRE", null, f.store.restore().single().failureCode)
                check(f.queuePreferences.edit().putString("0", raw).commit())
                f.assertOriginalAndPartialPreserved()
                f.retryAndHold { f.manager.retry(listOf(f.chapter.id)) }
                assertEquals("FAILURE_PUBLIC_RETRY_CLEARS_CAUSE", null, f.manager.queueState.value.single().failureCode)
                assertEquals("FAILURE_PUBLIC_RETRY_PERSISTS_CLEAR", null, f.store.restore().single().failureCode)
                f.assertOriginalAndPartialPreserved()
            }
        }
    }

    @Test(timeout = 120_000)
    fun failedHttpResumePreservesPartialOutputOriginalAndPreviouslySuccessfulRegion(): Unit = runBlocking {
        withTranslationFailureFixture { f ->
            f.install(PartialSeedEngine())
            val seeded = f.translator.translateChapterDetailed(f.chapterFile, FAILURE_METHOD)
            assertEquals("FAILURE_PARTIAL_SEED", TranslationFailureCode.OCR_PENDING, seeded.failureCode)
            assertTrue(f.translator.hasPartialProgress(f.chapterFile))
            assertFalse(f.translator.isChapterTranslated(f.chapterFile))
            val before = f.protectedHashes()
            f.service.shutdown()
            FailureLoopbackServer(401).use { server ->
                val engine = FailureHttpEngine(server, listOf(FAILURE_QUERIES.last()), expectedResume = true)
                f.install(engine)
                val failed = f.translator.translateChapterDetailed(f.chapterFile, FAILURE_METHOD)
                server.assertRequests(listOf(FAILURE_QUERIES.last()))
                assertEquals("FAILURE_PARTIAL_HTTP_CAUSE", TranslationFailureCode.SERVER_AUTH, failed.failureCode)
                assertEquals("FAILURE_PARTIAL_RESUME_PATH", 1, engine.resumes)
                assertEquals("FAILURE_PARTIAL_EXACT_MATERIALS", before, f.protectedHashes())
                assertFalse(f.translator.isChapterTranslated(f.chapterFile))
            }
        }
    }
}

internal const val FAILURE_METHOD = "boxfill"
internal const val FAILURE_CANARY = "neutral-provider-body-must-never-be-persisted"
private const val FAILURE_POSTPROCESS_CANARY = "neutral-postprocess-marker-must-not-be-displayed"
internal val FAILURE_QUERIES = listOf("合成の第一文です。", "合成の第二文です。")
internal val failureContext: TranslationContext = TranslationContext.fromTranslator(
    TranslatorConfig(fromLangName = "Japanese", toLangName = "Korean", sampleSource = "", sampleTarget = ""),
)

internal suspend fun withTranslationFailureFixture(block: suspend (TranslationFailureFixture) -> Unit) {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    check(target.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit package required" }
    check(InstrumentationRegistry.getArguments().getString("translationFailureAudit") == "true") {
        "Explicit -e translationFailureAudit true is required before queue, settings, DB or file mutation"
    }
    val fixture = TranslationFailureFixture(target)
    var failure: Throwable? = null
    try {
        fixture.prepare()
        block(fixture)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        withContext(NonCancellable) {
            try {
                fixture.close()
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }
}

internal class TranslationFailureFixture(val target: Context) {
    val manager: TranslationManager = Injekt.get()
    val service: TranslationEngineService = Injekt.get()
    val preferences: TranslationPreferences = Injekt.get()
    val settings = OnlineReaderSettings(target)
    private val uuid = UUID.randomUUID().toString()
    private val prefix = "translation-failure-$uuid"
    private val root = File(target.cacheDir, prefix)
    private val privatePreferences = mutableSetOf<String>()
    private val files = File(root, "files")
    private val cache = File(root, "cache")
    val context = object : ContextWrapper(target) {
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            privatePreferences += isolated
            return target.getSharedPreferences(isolated, mode)
        }
    }
    lateinit var translator: PageTranslator
    lateinit var store: TranslationStore
    lateinit var manga: Manga
    lateinit var chapter: Chapter
    lateinit var page: File
    lateinit var chapterFile: UniFile
    val queuePreferences get() = context.getSharedPreferences("active_translations", Context.MODE_PRIVATE)
    private val source = StubSource(
        (uuid.hashCode().toLong() and 0x7fffffff) + 10_000_000,
        "all",
        "Neutral failure $uuid",
    )
    private var registration: OnlineReaderSourceRegistration? = null
    private var ownedStorage: StorageManager? = null
    private val replaced = mutableListOf<Triple<Any, String, Any?>>()
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private var mangaId: Long? = null
    private var chapterId: Long? = null
    private var oldPools: Pair<Boolean, Boolean>? = null
    private var beforeWork: Map<String, Set<UUID>> = emptyMap()
    private var initialOriginal: ByteArray? = null

    suspend fun prepare() {
        failureAwait { failureField(manager, "restored").getBoolean(manager) }
        check(manager.queueState.value.isEmpty() && Injekt.get<DownloadManager>().queueState.value.isEmpty())
        check(TranslationBusy.active.value == 0 && !service.warm.value && !service.loading.value)
        check(failureField(service, "engine").get(service) == null)
        val existing = TranslationStore(target)
        check(existing.restore().isEmpty())
        val marks = existing.restorePendingMarks(Long.MIN_VALUE)
        check(marks.translate.isEmpty() && marks.night.isEmpty())
        val work = WorkManager.getInstance(target)
        beforeWork = FAILURE_TAGS.associateWith { tag ->
            work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS).also {
                check(it.all { info -> info.state.isFinished })
            }.map { it.id }.toSet()
        }
        check(root.parentFile.canonicalFile == target.cacheDir.canonicalFile && root.mkdir())
        check(files.mkdir() && cache.mkdir())
        settings.set(preferences.liveTranslate, false)
        settings.set(preferences.translationEnabled, false)
        settings.set(preferences.nightReadEnabled, false)
        settings.set(preferences.keepMaterials, true)
        settings.set(preferences.inpaintMethod, FAILURE_METHOD)
        settings.set(preferences.translationMasterEnabled, true)
        oldPools = manager.isTranslatePaused.value to manager.isNightPaused.value
        val models = File(files, "models").apply { check(mkdir()) }
        for (name in listOf("dbnet_detect.ncnn", "ocr_48px_ctc.ncnn", "aot")) {
            File(models, "$name.param").writeBytes(byteArrayOf(0))
            File(models, "$name.bin").writeBytes(byteArrayOf(0))
        }
        val modelStorage = StoragePreferences(folder(root, ""), InMemoryPreferenceStore())
        replace(TranslationEngineConfig, "storagePreferences", modelStorage)
        check(TranslationEngineConfig.modelsResolvable(context))
        val storageRoot = File(root, "storage").apply { check(mkdir()) }
        val storage = StorageManager(context, StoragePreferences(folder(storageRoot), InMemoryPreferenceStore()))
        ownedStorage = storage
        val provider = DownloadProvider(context, storage, LibraryPreferences(InMemoryPreferenceStore()))
        store = TranslationStore(context)
        translator = PageTranslator(context).also {
            failureField(it, "statsStore").set(it, TranslationStatsStore(context))
        }
        replace(manager, "downloadProvider", provider)
        replace(manager, "translationStore", store)
        replace(manager, "pageTranslator", translator)
        withTimeout(10_000) { Injekt.get<SourceManager>().isInitialized.first { it } }
        registration = OnlineReaderSourceRegistration(Injekt.get(), source)
        manga = Injekt.get<MangaRepository>().insertNetworkManga(
            listOf(Manga.create().copy(source = source.id, url = "/neutral/$uuid", title = "Neutral failure $uuid")),
        ).single().also { mangaId = it.id }
        chapter = Injekt.get<ChapterRepository>().addAll(
            listOf(
                Chapter.create().copy(
                    mangaId = manga.id,
                    name = "Synthetic failure chapter",
                    url = "/neutral/chapter",
                ),
            ),
        ).single().also { chapterId = it.id }
        val directory = File(
            storageRoot,
            "downloads/${provider.getSourceDirName(source)}/${provider.getMangaDirName(manga.title)}/" +
                provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url),
        ).apply { check(mkdirs()) }
        chapterFile = requireNotNull(UniFile.fromFile(directory))
        initialOriginal = failureEncoded(Color.WHITE)
        page = File(directory, "001.png").apply { writeBytes(requireNotNull(initialOriginal)) }
    }

    fun install(engine: TranslationEngine) {
        check(failureField(service, "engine").get(service) == null) { "Previous owned engine must have settled" }
        val signature = service.javaClass.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        ).apply { isAccessible = true }.invoke(service, FAILURE_METHOD, preferences.snapshot())
        failureField(service, "engine").set(service, engine)
        failureField(service, "builtSignature").set(service, signature)
        failureField(service, "warmedUp").setBoolean(service, true)
        @Suppress("UNCHECKED_CAST")
        (failureField(service, "_warm").get(service) as MutableStateFlow<Boolean>).value = true
    }

    suspend fun installAndRun(engine: TranslationEngine, action: () -> Unit) {
        val mutex = failureField(manager, "drainMutex").get(manager) as Mutex
        withTimeout(15_000) {
            mutex.withLock {
                install(engine)
                action()
            }
        }
    }

    suspend fun awaitError(code: TranslationFailureCode) {
        failureAwait {
            manager.queueState.value.singleOrNull()?.let {
                it.chapter.id == chapter.id && it.status == TranslationItem.Status.ERROR && it.failureCode == code
            } == true
        }
        failureAwait { queuePreferences.getString("0", null)?.contains(code.wireValue) == true }
    }

    suspend fun joinDrain() {
        withTimeout(15_000) { (failureField(manager, "drainMutex").get(manager) as Mutex).withLock { Unit } }
    }

    suspend fun retryAndHold(action: () -> Unit) {
        joinDrain()
        service.shutdown()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also(releases::add)
        val engine = object : TranslationEngine {
            override val translationContext = failureContext
            override suspend fun translatePage(page: Bitmap): PageResult {
                entered.complete(Unit)
                release.await()
                return PageResult.Failed("Neutral held retry finished without a completion")
            }
            override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                translatePage(original)
            override fun warmUp() = error("Native warmup is forbidden in scripted failure fixtures")
            override fun close() = Unit
        }
        val mutex = failureField(manager, "drainMutex").get(manager) as Mutex
        withTimeout(15_000) {
            mutex.withLock {
                install(engine)
                action()
                assertEquals(
                    "FAILURE_RETRY_QUEUED",
                    TranslationItem.Status.QUEUE,
                    manager.queueState.value.single().status,
                )
                assertEquals(
                    "FAILURE_RETRY_CAUSE_CLEAR_AT_ENQUEUE",
                    null,
                    manager.queueState.value.single().failureCode,
                )
            }
            entered.await()
        }
        assertEquals(
            "FAILURE_RETRY_ACTIVE",
            TranslationItem.Status.TRANSLATING,
            manager.queueState.value.single().status,
        )
    }

    suspend fun restoreLegacyError() {
        joinDrain()
        val legacy = JSONObject(requireNotNull(queuePreferences.getString("0", null))).apply {
            remove("failureCode")
        }.toString()
        manager.cancelPendingTranslation(chapter.id)
        failureAwait { manager.queueState.value.isEmpty() }
        joinDrain()
        check(queuePreferences.edit().putString("0", legacy).commit())
        failureField(manager, "restored").setBoolean(manager, false)
        manager.ensureRestored()
        failureAwait {
            manager.queueState.value.singleOrNull()?.let {
                it.status == TranslationItem.Status.ERROR && it.failureCode == null && it.chapter.id == chapter.id
            } == true
        }
    }

    fun protectedHashes(): Map<String, String> {
        val directory = File(requireNotNull(chapterFile.filePath))
        return directory.walkTopDown().filter {
            it.isFile && (it == page || it.relativeTo(directory).invariantSeparatorsPath.startsWith(".yakuyomi/"))
        }.associate { it.relativeTo(directory).invariantSeparatorsPath to failureSha(it.readBytes()) }
    }

    fun assertOriginalAndPartialPreserved() {
        assertArrayEquals("FAILURE_VISIBLE_ORIGINAL_RETAINED", initialOriginal, page.readBytes())
        val original = File(requireNotNull(chapterFile.filePath), ".yakuyomi/001.orig.png")
        if (original.exists()) assertArrayEquals("FAILURE_BACKUP_RETAINED", initialOriginal, original.readBytes())
        assertFalse("FAILURE_NEVER_COMPLETED", translator.isChapterTranslated(chapterFile))
    }

    suspend fun close() {
        if (!root.exists()) return
        settings.set(preferences.translationMasterEnabled, false)
        chapterId?.let(manager::cancelPendingTranslation)
        releases.forEach { it.complete(Unit) }
        failureAwait {
            TranslationBusy.active.value == 0 && manager.queueState.value.none { it.chapter.id == chapterId }
        }
        joinDrain()
        service.shutdown()
        val work = WorkManager.getInstance(target)
        for (tag in FAILURE_TAGS) {
            work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS)
                .filter { it.id !in beforeWork.getValue(tag) && !it.state.isFinished }
                .forEach { work.cancelWorkById(it.id).result.get(5, TimeUnit.SECONDS) }
        }
        failureAwait {
            FAILURE_TAGS.all { tag -> work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS).all { it.state.isFinished } }
        }
        chapterId?.let { manager.forgetChapterOutputs(listOf(it)) }
        chapterId?.let { Injekt.get<ChapterRepository>().removeChaptersWithIds(listOf(it)) }
        mangaId?.let { id ->
            val own = Injekt.get<MangaRepository>().getMangaById(id)
            check(own.source == source.id && own.url == "/neutral/$uuid")
            val driver = Injekt.get<SqlDriver>()
            check(
                driver.execute(null, "DELETE FROM mangas WHERE _id = ? AND source = ? AND url = ?", 3) {
                    bindLong(0, id)
                    bindLong(1, source.id)
                    bindString(2, "/neutral/$uuid")
                }.await() == 1L,
            )
            driver.notifyListeners("mangas", "chapters", "history")
        }
        oldPools?.let { (translation, night) ->
            if (translation) manager.pause(false) else manager.resume(false)
            if (night) manager.pause(true) else manager.resume(true)
        }
        joinDrain()
        replaced.asReversed().forEach { (owner, name, previous) -> failureField(owner, name).set(owner, previous) }
        registration?.close()
        ownedStorage?.let {
            val scope = failureField(it, "scope").get(it) as CoroutineScope
            withTimeout(10_000) { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
        }
        settings.close()
        for (name in privatePreferences) {
            check(target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
            check(target.deleteSharedPreferences(name))
        }
        check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile && root.name == prefix)
        check(root.deleteRecursively())
        assertFalse(
            target.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == Notifications.ID_TRANSLATION_PROGRESS
            },
        )
    }

    private fun replace(owner: Any, name: String, value: Any) {
        val field = failureField(owner, name)
        replaced += Triple(owner, name, field.get(owner))
        field.set(owner, value)
    }

    private fun folder(directory: File, path: String = Uri.fromFile(directory).toString()) = object : FolderProvider {
        override fun directory(): File = directory
        override fun path(): String = path
    }
}

internal class FailureHttpEngine(
    private val server: FailureLoopbackServer,
    private val queries: List<String>,
    private val expectedResume: Boolean = false,
    private val postProcessFailure: Boolean = false,
) : TranslationEngine {
    override val translationContext = failureContext
    var calls = 0
    var resumes = 0
    private suspend fun attempt(): PageResult {
        calls++
        val result = LlmTranslator(
            "",
            TranslatorConfig(
                provider = "custom",
                apiBase = server.url,
                model = "neutral-failure-fixture",
                fromLangName = "Japanese",
                toLangName = "Korean",
                sampleSource = "",
                sampleTarget = "",
            ),
            postProcess = if (postProcessFailure) {
                { _: String -> throw UnknownHostException(FAILURE_POSTPROCESS_CANARY) }
            } else {
                null
            },
        ).translateDetailed(queries)
        check(result.error != null && result.failure != null)
        check(result.raw == null && !requireNotNull(result.error).contains(FAILURE_CANARY))
        check(!requireNotNull(result.error).contains(FAILURE_POSTPROCESS_CANARY))
        return PageResult.Failed(requireNotNull(result.error), result.failure)
    }
    override suspend fun translatePage(page: Bitmap): PageResult {
        check(!expectedResume)
        return attempt()
    }
    override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
        check(expectedResume)
        resumes++
        check(analysis.progress?.regions?.first()?.translatedText == "이미 성공한 합성 번역")
        return attempt()
    }
    override fun warmUp() = error("Native warmup is forbidden in scripted failure fixtures")
    override fun close() = Unit
}

private class PartialSeedEngine : TranslationEngine {
    override val translationContext = failureContext
    override suspend fun translatePage(page: Bitmap): PageResult {
        val progress = PageProgress(
            32,
            32,
            (0..1).map { id ->
                RegionProgress(
                    id,
                    if (id == 0) RegionState.TRANSLATED else RegionState.OCR_PENDING,
                    listOf(
                        OcrLineProgress(
                            listOf(Pt(0f, 0f), Pt(16f, 0f), Pt(16f, 16f), Pt(0f, 16f)),
                            1f,
                            "h",
                            if (id == 0) FAILURE_QUERIES.first() else "",
                        ),
                    ),
                    "h",
                    translatedText = if (id == 0) "이미 성공한 합성 번역" else "",
                )
            },
        )
        val mask = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val output = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return PageResult.Partial(
            output,
            PageStats(2, 2, 1, 0, 0, 0, 0, 0),
            PageAnalysis(mask, progress.toTextRegions(), progress),
        )
    }
    override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult = error("Seed only")
    override fun warmUp() = error("Native warmup is forbidden")
    override fun close() = Unit
}

/** Finite loopback script. Retries are physical requests for one logical engine/page attempt. */
internal class FailureLoopbackServer(
    private val status: Int,
    private val validResponse: Boolean = false,
) : AutoCloseable {
    private val expected = if (status == 503) 2 else 1
    private val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    private val failure = AtomicReference<Throwable?>()
    private val activeSocket = AtomicReference<Socket?>()
    private val finished = CountDownLatch(1)
    private val requests = mutableListOf<ByteArray>()
    val url = "http://127.0.0.1:${listener.localPort}/v1/chat/completions"
    private val worker = thread(name = "translation-failure-loopback", isDaemon = true) {
        try {
            repeat(expected) {
                listener.soTimeout = 15_000
                listener.accept().use { socket ->
                    activeSocket.set(socket)
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream()
                    val header = ByteArrayOutputStream()
                    while (!header.toString("US-ASCII").endsWith("\r\n\r\n")) {
                        check(header.size() < 16_384)
                        val next = input.read()
                        check(next >= 0)
                        header.write(next)
                    }
                    val length = Regex("(?im)^Content-Length: ([0-9]+)\\r?$")
                        .find(header.toString("US-ASCII"))?.groupValues?.get(1)?.toInt() ?: error("No bounded body")
                    check(length in 1..131_072)
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < body.size) {
                        val read = input.read(body, offset, body.size - offset)
                        check(read > 0)
                        offset += read
                    }
                    synchronized(requests) { requests += body }
                    val reply = when {
                        validResponse -> JSONObject().put(
                            "choices",
                            JSONArray().put(
                                JSONObject().put("finish_reason", "stop").put(
                                    "message",
                                    JSONObject().put(
                                        "content",
                                        "<|1|>합성 첫 문장입니다.\n<|2|>합성 둘째 문장입니다.",
                                    ),
                                ),
                            ),
                        ).toString()
                        status == 200 -> "{\"choices\":[]}"
                        else -> JSONObject().put("error", JSONObject().put("message", FAILURE_CANARY)).toString()
                    }
                    val bytes = reply.toByteArray()
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 $status Synthetic\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        write(bytes)
                        flush()
                    }
                }
                activeSocket.set(null)
            }
        } catch (error: Throwable) {
            failure.set(error)
        } finally {
            finished.countDown()
        }
    }

    fun assertRequests(queries: List<String>) {
        check(finished.await(5, TimeUnit.SECONDS)) { "Loopback script did not finish" }
        failure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
        val actual = synchronized(requests) { requests.toList() }
        assertEquals("FAILURE_PHYSICAL_REQUESTS", expected, actual.size)
        actual.forEach { body ->
            val request = JSONObject(body.toString(Charsets.UTF_8))
            val messages = request.getJSONArray("messages")
            assertEquals("FAILURE_NO_MULTIMODAL_MESSAGES", 2, messages.length())
            for (index in 0 until messages.length()) assertTrue(messages.getJSONObject(index).get("content") is String)
            val regions = JSONArray(messages.getJSONObject(messages.length() - 1).getString("content"))
            assertEquals("FAILURE_WHOLE_PAGE_REGION_COUNT", queries.size, regions.length())
            for (index in queries.indices) {
                val region = regions.getJSONObject(index)
                assertEquals(setOf("id", "text"), region.keys().asSequence().toSet())
                assertEquals(index + 1, region.getInt("id"))
                assertEquals(queries[index], region.getString("text"))
            }
        }
        if (expected == 2) assertArrayEquals("FAILURE_IDENTICAL_PHYSICAL_RETRY", actual[0], actual[1])
    }

    override fun close() {
        listener.close()
        activeSocket.getAndSet(null)?.close()
        worker.join(3000)
        check(!worker.isAlive) { "Own loopback thread did not stop" }
    }
}

internal val FAILURE_TAGS = listOf("Translator", "TranslatorHeartbeat")
internal fun failureField(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply {
    isAccessible = true
}
internal suspend fun failureAwait(condition: () -> Boolean) = withTimeout(20_000) {
    while (!condition()) delay(20)
}
internal fun failureSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
private fun failureEncoded(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return try {
        ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}
