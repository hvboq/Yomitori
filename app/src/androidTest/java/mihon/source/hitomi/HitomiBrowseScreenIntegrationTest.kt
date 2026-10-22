package mihon.source.hitomi

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.androidx.AndroidScreenLifecycleOwner
import cafe.adriel.voyager.core.lifecycle.ScreenLifecycleStore
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Opt in with -e liveHitomiBrowse true. These tests retain MainActivity's real composition,
 * navigate from SourcesTab, and use production Hitomi transport and remote suggestions.
 * Accessibility text/selection edits and ACTION_IME_ENTER are intentional: this covers the
 * app's editor action, not physical keyboard keys or Samsung/Gboard composing sequences.
 * Read-only inspection of the already created screen ViewModel verifies that selection does
 * not submit a search. No ViewModel action, replacement content, or synthetic source is used.
 */
@Suppress("DEPRECATION", "RestrictedApi")
@RunWith(AndroidJUnit4::class)
class HitomiBrowseScreenIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun toolbarRemoteSuggestionReplacesCurrentTokenAndAccessibilityImeSubmits() = verifyBrowse(floating = false)

    @Test
    fun floatingRemoteSuggestionStaysExpandedAndAccessibilityImeSubmits() = verifyBrowse(floating = true)

    private fun verifyBrowse(floating: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveHitomiBrowse") == "true")
        assumeTrue("Accessibility IME action requires Android 11+", Build.VERSION.SDK_INT >= 30)
        val sources = Injekt.get<SourcePreferences>()
        val library = Injekt.get<LibraryPreferences>()
        val base = Injekt.get<BasePreferences>()
        val saved = SavedPreferences()
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        val report = JSONObject()
            .put("floating", floating)
            .put("actualMainActivityAndSourcesTab", true)
            .put("remoteSuggestions", true)
            .put("editingMethod", "accessibility-set-text-and-selection")
            .put("submissionMethod", "accessibility-ime-enter")
            .put("physicalKeyboardOrCompositionVerified", false)
            .put("forcedResponseReorderingVerified", false)
            .put("passed", false)
        var completed = false
        try {
            saved.set(sources.showNsfwSource, true)
            saved.set(sources.enabledLanguages, sources.enabledLanguages.get() + "all")
            saved.set(sources.disabledSources, sources.disabledSources.get() - HitomiSource.ID.toString())
            saved.set(library.floatingSearchBar, floating)
            saved.preserve(sources.lastUsedSource)
            // This covers source browsing after onboarding, not the separate first-run flow.
            saved.set(base.shownOnboardingFlow, true)
            // MainActivity resets incognito on a fresh launch; restore both value and presence.
            saved.preserve(base.incognitoMode)
            saved.set(sources.sourceDisplayMode, LibraryDisplayMode.List)
            saved.set(sources.hideInLibraryItems, false)
            saved.set(sources.browseFilterFavorite, TriState.DISABLED)
            saved.set(sources.browseFilterRead, TriState.DISABLED)
            saved.set(sources.browseFilterFetched, TriState.DISABLED)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val source = runBlocking {
                withTimeout(30_000) {
                    val manager = Injekt.get<SourceManager>()
                    manager.isInitialized.first { it }
                    manager.get(HitomiSource.ID) as HitomiSource
                }
            }
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Constants.SHORTCUT_SOURCES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                // This is the real SourcesTab row, not a pushed screen or fixture composition.
                withNode("Hitomi source row", { it.text?.toString()?.lineSequence()?.firstOrNull() == "Hitomi" }) {
                    touch(it)
                }
                lateinit var viewModel: BrowseSourceViewModel
                waitUntil("Real Hitomi BrowseSourceScreen did not open") {
                    var observed: BrowseSourceViewModel? = null
                    scenario.onActivity { observed = existingBrowseModel(it) }
                    observed?.also { viewModel = it } != null
                }
                assertEquals(HitomiSource.ID, viewModel.source.id)
                val initialListing = viewModel.state.value.listing
                assertTrue(
                    "SourcesTab must open a normal remote listing",
                    initialListing is BrowseSourceViewModel.Listing.Latest ||
                        initialListing is BrowseSourceViewModel.Listing.Popular,
                )
                report.put("initialListing", initialListing.javaClass.simpleName)
                openEditor()
                setText("jap")
                awaitSuggestion(JAPANESE)
                assertEquals(
                    "Typing and remote suggestions must not submit",
                    initialListing,
                    viewModel.state.value.listing,
                )
                report.put("remoteJapaneseSuggestionFromJap", true)

                if (floating) verifySuggestionScrollDoesNotCollapse()

                // Exercise rapidly superseded token requests. Live latency is uncontrolled, so
                // this proves the final response is usable, not a forced out-of-order schedule.
                setText("artist:na type:manga")
                select("artist:na".length)
                SystemClock.sleep(300)
                setText("language:ko type:manga")
                select("language:ko".length)
                SystemClock.sleep(300)
                setText("jap type:manga")
                select(3)
                awaitSuggestion(JAPANESE)
                withNode("Japanese suggestion row", { isSuggestionLabel(it, JAPANESE) }) { touch(it) }
                val expectedQuery = "$JAPANESE type:manga"
                assertEditor(expectedQuery, JAPANESE.length + 1)
                waitUntil("Suggestion did not update the actual screen query") {
                    viewModel.state.value.toolbarQuery == expectedQuery
                }
                assertEquals(
                    "Choosing a suggestion must retain the old listing until submit",
                    initialListing,
                    viewModel.state.value.listing,
                )
                assertAbsent("Chosen suggestion list must close") { isSuggestionLabel(it, JAPANESE) }
                report.put("currentTokenSuffixAndCaretPreserved", true).put("suggestionDidNotSubmit", true)
                if (floating) {
                    // The ordinary three-second idle timer must not remove a nonempty editor.
                    SystemClock.sleep(3_250)
                    assertEditor(expectedQuery, JAPANESE.length + 1)
                    report.put("nonemptyEditorSurvivedIdleCollapseDeadline", true)
                }
                withNode("Real search editor", { it.isEditable }) { node ->
                    if (!node.isFocused) {
                        assertTrue("Editor must accept focus", node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                    }
                    assertTrue(
                        "Real Compose editor did not expose its Search IME action",
                        node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id },
                    )
                    assertTrue(
                        "Editor rejected the accessibility IME action",
                        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id),
                    )
                }
                waitUntil("Editor action did not submit the real BrowseSourceScreen query") {
                    (viewModel.state.value.listing as? BrowseSourceViewModel.Listing.Search)?.query == expectedQuery
                }
                report.put("editorActionSubmittedActualScreen", true)

                // Fetch the same live search as an oracle, without manufacturing screen results.
                // Only a title rendered by the actual list satisfies the final UI assertion.
                val expectedTitles = runBlocking {
                    withTimeout(90_000) {
                        source.getSearchManga(1, expectedQuery, source.getFilterList()).mangas.map { it.title }.toSet()
                    }
                }
                assertTrue("Broad Japanese manga live search must return results", expectedTitles.isNotEmpty())
                waitUntil("Actual browse list did not render a live search result", timeoutMs = 90_000) {
                    findNode { node ->
                        !node.isEditable && node.text?.toString()?.lineSequence()?.any { it in expectedTitles } == true
                    }
                        ?.also { it.recycle() } != null
                }
                report.put("actualSearchResultRendered", true)
            }
            completed = true
        } finally {
            try {
                saved.restoreAndVerify()
                report.put("preferencesRestoredIncludingPresence", true).put("passed", completed)
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
                val name = if (floating) "floating" else "toolbar"
                val output = File(context.getExternalFilesDir(null), "hitomi-browse-screen/$name.json")
                check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                output.writeText(report.toString(2))
                println("HITOMI_BROWSE_SCREEN_REPORT=${output.absolutePath}")
                println(report.toString(2))
            }
        }
    }

    private fun openEditor() {
        val existing = findNode { it.isEditable }
        if (existing != null) {
            try {
                touch(existing)
            } finally {
                existing.recycle()
            }
        } else {
            val search = context.stringResource(MR.strings.action_search)
            withNode("Search control", { it.contentDescription?.toString() == search }) { touch(it) }
        }
        withNode("Source search editor", { it.isEditable }) { touch(it) }
    }

    private fun verifySuggestionScrollDoesNotCollapse() {
        setText("language:")
        var scrolled = false
        waitUntil("Remote language suggestions did not provide a scrollable list", timeoutMs = 45_000) {
            val label = findNode { it.text?.toString()?.startsWith("language:") == true && !it.isEditable }
                ?: return@waitUntil false
            var current: AccessibilityNodeInfo? = label
            try {
                while (current != null) {
                    val candidate = current
                    if (candidate.isScrollable && candidate.actionList.any {
                            it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        }
                    ) {
                        scrolled = candidate.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        return@waitUntil scrolled
                    }
                    current = candidate.parent
                    candidate.recycle()
                }
                false
            } finally {
                current?.recycle()
            }
        }
        assertTrue(scrolled)
        SystemClock.sleep(500)
        assertEditor("language:", "language:".length)
    }

    /** Observe the owner already populated by normal navigation; never create a test ViewModel. */
    private fun existingBrowseModel(activity: MainActivity): BrowseSourceViewModel? {
        val navigator = MainActivity::class.java.getDeclaredField("navigator").apply { isAccessible = true }
            .get(activity) as? Navigator ?: return null
        val screen = navigator.lastItem as? BrowseSourceScreen ?: return null
        val owner = runCatching {
            ScreenLifecycleStore.get<AndroidScreenLifecycleOwner>(screen) {
                error("Actual BrowseSourceScreen has not created its lifecycle owner")
            }
        }.getOrNull() ?: return null
        val key = "androidx.lifecycle.ViewModelProvider.DefaultKey:${BrowseSourceViewModel::class.java.name}"
        return owner.viewModelStore[key] as? BrowseSourceViewModel
    }

    private fun setText(text: String) {
        withNode("Source search editor", { it.isEditable }) { node ->
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            assertTrue(
                "Editor rejected text edit",
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
            )
        }
        assertEditor(text, text.length)
    }

    private fun select(caret: Int) {
        withNode("Source search editor", { it.isEditable }) { node ->
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
            assertTrue(
                "Editor rejected caret edit",
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments),
            )
        }
        waitUntil("Editor caret did not move") {
            findNode { it.isEditable && it.textSelectionStart == caret && it.textSelectionEnd == caret }
                ?.also { it.recycle() } != null
        }
    }

    private fun awaitSuggestion(label: String) {
        waitUntil("Remote Japanese recommendation did not appear", timeoutMs = 45_000) {
            findNode { isSuggestionLabel(it, label) }?.also { it.recycle() } != null
        }
    }

    private fun isSuggestionLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        if (node.isEditable) return false
        val text = node.text?.toString() ?: return false
        // A merged Compose TextButton may expose its result count after the tag label.
        return text == label || text.startsWith("$label\n") || text.startsWith("$label, ") || text.startsWith("$label ")
    }

    private fun assertEditor(text: String, caret: Int) {
        waitUntil("Real editor text/caret differs from expected token replacement") {
            findNode {
                it.isEditable && it.text?.toString() == text &&
                    it.textSelectionStart == caret && it.textSelectionEnd == caret
            }?.also { it.recycle() } != null
        }
    }

    private fun assertAbsent(message: String, matches: (AccessibilityNodeInfo) -> Boolean) {
        waitUntil(message) { findNode(matches)?.also { it.recycle() } == null }
    }

    private fun withNode(
        label: String,
        matches: (AccessibilityNodeInfo) -> Boolean,
        action: (AccessibilityNodeInfo) -> Unit,
    ) {
        var node: AccessibilityNodeInfo? = null
        waitUntil("Missing $label") { findNode(matches).also { node = it } != null }
        try {
            action(checkNotNull(node))
        } finally {
            node?.recycle()
        }
    }

    /** A real touch reaches Compose even when a labelled wrapper lacks a semantic click action. */
    private fun touch(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        assertFalse("Control has no visible touch bounds", bounds.isEmpty)
        assertTrue("Control is not enabled", node.isEnabled)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue("Platform rejected UI touch", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
    }

    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.isVisibleToUser && matches(node)) return AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    visit(child)?.let { return it }
                } finally {
                    child.recycle()
                }
            }
            return null
        }
        val roots = mutableListOf<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(roots::add)
        for (window in automation.windows) {
            window.root?.let(roots::add)
            window.recycle()
        }
        try {
            for (root in roots) {
                if (root.packageName?.toString() == context.packageName) visit(root)?.let { return it }
            }
            return null
        } finally {
            roots.forEach(AccessibilityNodeInfo::recycle)
        }
    }

    private fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(75)
        }
        throw AssertionError(message)
    }

    private class SavedPreferences {
        private val restores = linkedMapOf<String, () -> Unit>()
        private val verifies = mutableListOf<() -> Unit>()

        fun <T> preserve(preference: Preference<T>) {
            if (preference.key() in restores) return
            val existed = preference.isSet()
            val value = preference.get()
            restores[preference.key()] = { if (existed) preference.set(value) else preference.delete() }
            verifies += {
                assertTrue("Preference presence was not restored: ${preference.key()}", preference.isSet() == existed)
                assertTrue("Preference value was not restored: ${preference.key()}", preference.get() == value)
            }
        }

        fun <T> set(preference: Preference<T>, value: T) {
            preserve(preference)
            preference.set(value)
        }

        fun restoreAndVerify() {
            restores.values.toList().asReversed().forEach { it() }
            verifies.forEach { it() }
        }
    }

    private companion object {
        const val JAPANESE = "language:japanese"
    }
}
