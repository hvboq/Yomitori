package mihon.manga

import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.translation.OnlineReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Renders the public MangaScreen's actual small/large branches with an in-memory neutral chapter list.
 * This is an isolated Compose host, not a production manga Activity or a physical tablet test.
 * A real lazy-list swipe and measured FAB width distinguish collapsed from expanded coverage.
 * No manga/chapter is inserted into the database and no source, OCR, or translation call is made.
 */
@RunWith(AndroidJUnit4::class)
class MangaReadingActionAccessibilityTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(OwnPrewarmGate()).around(compose)

    @Test
    fun smallStartExpandedHasAccessibleReadingAction() = verifyReadingAction(tablet = false, resume = false)

    @Test
    fun smallResumeExpandedHasAccessibleReadingAction() = verifyReadingAction(tablet = false, resume = true)

    @Test
    fun smallStartCollapsedHasAccessibleReadingAction() =
        verifyReadingAction(tablet = false, resume = false, collapsed = true)

    @Test
    fun smallResumeCollapsedHasAccessibleReadingAction() =
        verifyReadingAction(tablet = false, resume = true, collapsed = true)

    @Test
    fun largeStartExpandedHasAccessibleReadingAction() = verifyReadingAction(tablet = true, resume = false)

    @Test
    fun largeResumeExpandedHasAccessibleReadingAction() = verifyReadingAction(tablet = true, resume = true)

    @Test
    fun largeStartCollapsedHasAccessibleReadingAction() =
        verifyReadingAction(tablet = true, resume = false, collapsed = true)

    @Test
    fun largeResumeCollapsedHasAccessibleReadingAction() =
        verifyReadingAction(tablet = true, resume = true, collapsed = true)

    private fun verifyReadingAction(tablet: Boolean, resume: Boolean, collapsed: Boolean = false) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Use the isolated audit package", AUDIT_PACKAGE, target.packageName)
        val widthDp = if (tablet) 800 else 360
        val configuration = Configuration(target.resources.configuration).apply {
            setLocale(Locale.KOREAN)
            screenWidthDp = widthDp
            screenHeightDp = 600
            smallestScreenWidthDp = widthDp
            orientation = if (tablet) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        val localized = target.createConfigurationContext(configuration)
        val expectedLabel = localized.stringResource(if (resume) MR.strings.action_resume else MR.strings.action_start)
        val state = neutralState(resume)
        var readingClicks = 0
        var otherClicks = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = 1f, fontScale = 1f),
            ) {
                MaterialTheme {
                    Box(Modifier.requiredSize(widthDp.dp, 600.dp).testTag(VIEWPORT)) {
                        MangaScreen(
                            state = state,
                            snackbarHostState = remember { SnackbarHostState() },
                            nextUpdate = null,
                            isTabletUi = tablet,
                            chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                            chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                            navigateUp = { otherClicks++ },
                            onChapterClicked = { otherClicks++ },
                            onDownloadChapter = null,
                            onTranslateChapter = null,
                            onReRenderChapter = null,
                            onNightRenderChapter = null,
                            nightReadEnabled = false,
                            onAddToLibraryClicked = { otherClicks++ },
                            onWebViewClicked = null,
                            onWebViewLongClicked = null,
                            onTrackingClicked = { otherClicks++ },
                            onTagSearch = { otherClicks++ },
                            onFilterButtonClicked = { otherClicks++ },
                            onRefresh = { otherClicks++ },
                            onContinueReading = { readingClicks++ },
                            onSearch = { _, _ -> otherClicks++ },
                            onCoverClicked = { otherClicks++ },
                            onShareClicked = null,
                            onDownloadActionClicked = null,
                            onEditCategoryClicked = null,
                            onEditFetchIntervalClicked = null,
                            onSetAnchorClicked = null,
                            onMigrateClicked = null,
                            onReRenderAllClicked = null,
                            onContinueCaptureClicked = null,
                            onEditNotesClicked = { otherClicks++ },
                            onMultiBookmarkClicked = { _, _ -> otherClicks++ },
                            onMultiMarkAsReadClicked = { _, _ -> otherClicks++ },
                            onMarkPreviousAsReadClicked = { otherClicks++ },
                            onMultiDeleteClicked = { otherClicks++ },
                            onChapterSwipe = { _, _ -> otherClicks++ },
                            onChapterSelected = { _, _, _ -> otherClicks++ },
                            onAllChapterSelected = { otherClicks++ },
                            onInvertSelection = { otherClicks++ },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        val list = compose.onNode(LAZY_LIST)
        val initialRange = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Fixture must begin at the list start", 0f, initialRange.value(), 0.001f)
        assertTrue("Fixture must have enough chapters to scroll", initialRange.maxValue() > 5f)
        val initialButton = expandedReadingButton(viewport)
        val action = compose.onNode(
            SemanticsMatcher("Reading FAB id=${initialButton.id}") { it.id == initialButton.id },
        )
        val expandedBounds = initialButton.boundsInRoot
        assertTrue("Initial reading FAB must be expanded", expandedBounds.width > expandedBounds.height + 8f)

        if (collapsed) {
            var scrollPosition = 0f
            repeat(4) {
                if (scrollPosition <= 1f) {
                    list.performTouchInput {
                        swipeUp(startY = height * 0.85f, endY = height * 0.25f, durationMillis = 400)
                    }
                    compose.waitForIdle()
                    val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                    scrollPosition = range.value()
                }
            }
            val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("Swipe must reach the middle of the real chapter list", range.value() > 1f)
            assertTrue("Do not reach the end, where the FAB expands again", range.value() < range.maxValue() - 1f)
        }

        val button = action.fetchSemanticsNode()
        val bounds = button.boundsInRoot
        if (collapsed) {
            assertTrue("A swipe must actually shrink the FAB", bounds.width < expandedBounds.width - 8f)
            assertEquals("Collapsed FAB must have icon-only width", bounds.height, bounds.width, 1f)
        } else {
            assertEquals("Expanded FAB width must remain stable", expandedBounds.width, bounds.width, 1f)
        }
        assertEquals("Reading action must retain Button role", Role.Button, button.config[SemanticsProperties.Role])
        action.assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals("Reading callback must run exactly once", 1, readingClicks)
            assertEquals("The fixture must not activate another control", 0, otherClicks)
        }

        // Assert the name last: an unchanged baseline must first prove the fixture, shape and callback work.
        val config = action.fetchSemanticsNode().config
        val descriptions = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        report(
            "MANGA_READING_ACTION tablet=$tablet resume=$resume collapsed=$collapsed " +
                "expandedWidth=${expandedBounds.width} finalWidth=${bounds.width} height=${bounds.height} " +
                "buttonRole=true onClick=true readingClicks=1 otherClicks=0 " +
                "descriptionCount=${descriptions.size} textCount=${texts.size}",
        )
        val accessibleName = if (descriptions.isNotEmpty()) descriptions.joinToString(" ") else texts.joinToString(" ")
        assertEquals("Reading FAB must expose its localized action name", expectedLabel, accessibleName)
        assertEquals(
            "Reading action name must not be announced twice",
            1,
            (descriptions + texts).count { it == expectedLabel },
        )
    }

    /**
     * Identify the initially expanded FAB by position and shape, without depending on its name.
     * Nearby chapter download buttons have the same role but square bounds. Keep this node's ID
     * across the later scroll so a collapsed FAB cannot be confused with one of those buttons.
     */
    private fun expandedReadingButton(viewport: Rect): SemanticsNode {
        val bottomEndButtons = compose.onAllNodes(READING_BUTTON).fetchSemanticsNodes().filter { node ->
            val bounds = node.boundsInRoot
            bounds.width in 40f..240f && bounds.height in 40f..100f &&
                bounds.right <= viewport.right && bounds.right >= viewport.right - 100f &&
                bounds.bottom <= viewport.bottom && bounds.bottom >= viewport.bottom - 180f
        }
        report(
            "MANGA_READING_CANDIDATES viewport=$viewport " +
                bottomEndButtons.joinToString(" ") { node ->
                    "id=${node.id},bounds=${node.boundsInRoot},role=${node.config[SemanticsProperties.Role]}," +
                        "onClick=${node.config.contains(SemanticsActions.OnClick)}"
                },
        )
        val candidates = bottomEndButtons.filter { it.boundsInRoot.width > it.boundsInRoot.height + 8f }
        assertEquals("Exactly one expanded bottom-end reading FAB must exist", 1, candidates.size)
        return candidates.single()
    }

    private fun neutralState(resume: Boolean): MangaViewModel.State.Success {
        val manga = Manga.create().copy(
            id = -8_100L,
            source = -8_101L,
            url = "fixture/manga",
            title = "Neutral reading action fixture",
            initialized = true,
            chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC,
            thumbnailUrl = null,
        )
        return MangaViewModel.State.Success(
            manga = manga,
            source = StubSource(manga.source, "en", "Neutral fixture source"),
            isFromSource = false,
            chapters = (1..48).map { number ->
                ChapterList.Item(
                    chapter = Chapter.create().copy(
                        id = number.toLong(),
                        mangaId = manga.id,
                        name = "Neutral chapter $number",
                        url = "fixture/chapter/$number",
                        chapterNumber = number.toDouble(),
                        sourceOrder = number.toLong(),
                        dateUpload = 0,
                        read = resume && number == 1,
                    ),
                    downloadState = Download.State.NOT_DOWNLOADED,
                    downloadProgress = 0,
                )
            },
            availableScanlators = emptySet(),
            excludedScanlators = emptySet(),
        )
    }

    private companion object {
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val VIEWPORT = "manga-reading-action-viewport"
        val LAZY_LIST = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)
        val READING_BUTTON = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

        fun report(message: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "$message\n")
                },
            )
        }
    }

    /** The host is a foreground Activity; suppress native prewarm and restore only after it closes. */
    private class OwnPrewarmGate : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                assertEquals("Use the isolated audit package", AUDIT_PACKAGE, context.packageName)
                val manager = Injekt.get<TranslationManager>()
                val downloads = Injekt.get<DownloadManager>()
                val service = Injekt.get<TranslationEngineService>()
                runBlocking { withTimeout(10_000) { manager.ensureRestored() } }
                assertTrue("Translation queue must be empty", manager.queueState.value.isEmpty())
                assertTrue("Download queue must be idle", downloads.queueState.value.isEmpty() && !downloads.isRunning)
                assertEquals("Translation work must be idle", 0, TranslationBusy.active.value)
                assertFalse("Engine must already be cold", service.warm.value)
                assertFalse("Engine must not be loading", service.loading.value)
                awaitBackground()
                report("MANGA_READING_GATE phase=beforeHost warm=false loading=false busy=0 background=true")
                val settings = OnlineReaderSettings(context)
                var failure: Throwable? = null
                try {
                    settings.set(Injekt.get<TranslationPreferences>().liveTranslate, false)
                    settings.set(Injekt.get<UiPreferences>().coverBasedTheme, false)
                    base.evaluate()
                } catch (caught: Throwable) {
                    failure = caught
                } finally {
                    fun cleanup(block: () -> Unit) {
                        try {
                            block()
                        } catch (caught: Throwable) {
                            if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                        }
                    }
                    cleanup {
                        awaitBackground()
                        assertFalse("Test host must not warm the engine", service.warm.value)
                        assertFalse("Test host must not load the engine", service.loading.value)
                        assertEquals("Test host must not start translation", 0, TranslationBusy.active.value)
                        assertTrue("Test host must not enqueue translation", manager.queueState.value.isEmpty())
                        assertTrue("Test host must not enqueue downloads", downloads.queueState.value.isEmpty())
                        assertFalse("Test host must not start downloads", downloads.isRunning)
                        report("MANGA_READING_GATE phase=afterHost warm=false loading=false busy=0 background=true")
                    }
                    cleanup {
                        settings.close()
                        report(
                            "MANGA_READING_GATE liveAndCoverPreferencesRestoredIncludingPresence=true diskBarrier=true",
                        )
                    }
                }
                failure?.let { throw it }
            }
        }

        private fun awaitBackground() = runBlocking {
            withTimeout(10_000) {
                withContext(Dispatchers.Main.immediate) {
                    ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first {
                        !it.isAtLeast(Lifecycle.State.STARTED)
                    }
                }
            }
        }
    }
}
