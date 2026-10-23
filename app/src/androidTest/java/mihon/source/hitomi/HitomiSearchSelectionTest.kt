package mihon.source.hitomi

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.browse.components.BrowseSourceFloatingBall
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Real search widgets and platform text editing, with a synthetic suggestion button.
 * No Hitomi/translation requests are made by this fixture. This is widget integration,
 * not coverage of BrowseSourceScreen's debounce, remote suggestions, or navigation.
 * Intercepting the platform session isolates synthetic composing commands from the host IME.
 * This verifies widget composition state, not Samsung/Gboard keyboard behavior.
 * The final commit and search action share one main-thread turn to detect stale query submission.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class HitomiSearchSelectionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun toolbarPreservesCaretSelectionSuffixAndUnicodeComposition() = verifyWidget(floating = false)

    @Test
    fun floatingSearchPreservesCaretSelectionSuffixAndUnicodeComposition() = verifyWidget(floating = true)

    private fun verifyWidget(floating: Boolean) {
        val state = FixtureState()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.ready = true
                    activity.setContent { Fixture(state, floating) }
                }
                assertEditable("fixture", TextRange(2))

                setText(state, "jap type:manga")
                select(state, TextRange(3))
                chooseSuggestion(state)
                assertReplacement(state, "language:japanese type:manga", "language:japanese ".length)

                // Canonical text stays identical, but the explicit replacement still moves the caret.
                select(state, TextRange("language:ja".length))
                chooseSuggestion(state)
                assertReplacement(state, "language:japanese type:manga", "language:japanese ".length)

                setText(state, "type:manga -jap tag:full_color")
                select(state, TextRange("type:manga -ja".length))
                chooseSuggestion(state)
                assertReplacement(
                    state,
                    "type:manga -language:japanese tag:full_color",
                    "type:manga -language:japanese ".length,
                )

                setText(state, "jap type:manga")
                select(state, TextRange(0, 3))
                onMain { assertNull(state.token()) }
                waitUntil("A selected text range must not offer a suggestion") {
                    val node = findNode { it.contentDescription?.toString() == CHOOSE }
                    try {
                        node == null
                    } finally {
                        node?.recycle()
                    }
                }

                verifyComposition(state)
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun verifyComposition(state: FixtureState) {
        setText(state, "artist: type:manga")
        withNode({ it.isEditable }) {
            assertTrue("Click must focus the real text editor", it.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        instrumentation.waitForIdleSync()
        select(state, TextRange("artist:".length))
        waitUntil("The focused widget did not request a platform InputConnection") { state.input.get() != null }
        val input = checkNotNull(state.input.get())
        try {
            onMain { assertTrue(input.setComposingText("か", 1)) }
            waitUntil("First composing edit was not received") { onMain { state.query == "artist:か type:manga" } }
            assertEditable("artist:か type:manga", TextRange("artist:か".length))
            // Updating the same composing span after recomposition must replace か, not append to it.
            onMain { assertTrue(input.setComposingText("かな", 1)) }
            try {
                waitUntil("Recomposition lost the IME composing span") {
                    onMain { state.query == "artist:かな type:manga" }
                }
            } catch (failure: AssertionError) {
                throw AssertionError(
                    "IME result was ${onMain {
                        state.query
                    }}; selection=${onMain { state.selection }}",
                    failure,
                )
            }
            assertEditable("artist:かな type:manga", TextRange("artist:かな".length))
            val committedQuery = "artist:かな漢 type:manga"
            // An IME may commit its final candidate and immediately submit before Compose
            // recomposes. Keep both input commands and their assertions in this same turn.
            onMain {
                assertEquals(emptyList<String>(), state.submissions)
                assertTrue("The final candidate must commit through InputConnection", input.commitText("かな漢", 1))
                assertEquals("The text callback must observe the final commit immediately", committedQuery, state.query)
                assertTrue(
                    "The editor must accept the search action",
                    input.performEditorAction(EditorInfo.IME_ACTION_SEARCH),
                )
                assertEquals(
                    "Search must submit the just-committed query exactly once",
                    listOf(committedQuery),
                    state.submissions,
                )
            }
            assertEditable(committedQuery, TextRange("artist:かな漢".length))
        } finally {
            onMain { input.closeConnection() }
        }
    }

    private fun setText(state: FixtureState, text: String) {
        withNode({ it.isEditable }) { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            assertTrue(
                "Editable node refused ACTION_SET_TEXT",
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
            )
        }
        waitUntil("Text callback was not delivered") { onMain { state.query == text } }
        instrumentation.waitForIdleSync()
        assertEditable(text, onMain { state.selection })
    }

    private fun select(state: FixtureState, selection: TextRange) {
        val textBefore = onMain { state.query }
        val editsBefore = onMain { state.textChanges }
        val selectionsBefore = onMain { state.selectionChanges }
        withNode({ it.isEditable && it.text?.toString() == textBefore }) { node ->
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selection.start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selection.end)
            }
            assertTrue(
                "Editable node refused ACTION_SET_SELECTION",
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments),
            )
        }
        waitUntil("Selection-only callback was not delivered") {
            onMain { state.selection == selection && state.selectionChanges > selectionsBefore }
        }
        onMain {
            assertEquals(textBefore, state.query)
            assertEquals("Moving the caret must not report a text edit", editsBefore, state.textChanges)
        }
        assertEditable(textBefore, selection)
    }

    private fun chooseSuggestion(state: FixtureState) {
        val choicesBefore = onMain { state.choices }
        val diagnostics = mutableListOf<String>()
        repeat(3) {
            withNode({ it.contentDescription?.toString() == CHOOSE }) { node ->
                if (node.refresh()) {
                    assertTrue("Suggestion should be enabled at the caret", node.isEnabled)
                    clickSemanticControl(node, diagnostics)
                }
            }
            instrumentation.waitForIdleSync()
            if (onMain { state.choices } > choicesBefore) {
                assertEquals("A suggestion must be selected exactly once", choicesBefore + 1, onMain { state.choices })
                return
            }
            SystemClock.sleep(100)
        }
        // Some Compose accessibility trees expose a labelled wrapper without a working click action.
        // Use a real platform touch on that visible control, never call the fixture callback directly.
        Log.w("HitomiSearchSelection", "Semantic click fallback: ${diagnostics.distinct().joinToString()}")
        withNode({ it.contentDescription?.toString() == CHOOSE }) { node ->
            assertTrue("Suggestion must remain enabled", node.isEnabled)
            val bounds = Rect().also(node::getBoundsInScreen)
            assertFalse("Suggestion must have visible bounds", bounds.isEmpty)
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int) {
                val event = MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0,
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    assertTrue(
                        "Platform rejected suggestion touch",
                        instrumentation.uiAutomation.injectInputEvent(event, true),
                    )
                } finally {
                    event.recycle()
                }
            }
            send(MotionEvent.ACTION_DOWN)
            SystemClock.sleep(40)
            send(MotionEvent.ACTION_UP)
        }
        waitUntil("Real suggestion touch did not invoke onClick") { onMain { state.choices > choicesBefore } }
        assertEquals("A suggestion must be selected exactly once", choicesBefore + 1, onMain { state.choices })
    }

    private fun clickSemanticControl(label: AccessibilityNodeInfo, diagnostics: MutableList<String>): Boolean {
        fun clickable(node: AccessibilityNodeInfo) = node.isVisibleToUser && node.isEnabled && node.isClickable &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(label)
        try {
            while (candidate != null) {
                val current = candidate
                diagnostics += "${current.className}:clickable=${current.isClickable}," +
                    "enabled=${current.isEnabled},actions=${current.actionList.map { it.id }}"
                if (clickable(current)) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                candidate = current.parent
                current.recycle()
            }
        } finally {
            candidate?.recycle()
        }
        fun descendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    if (clickable(child)) return AccessibilityNodeInfo.obtain(child)
                    descendant(child)?.let { return it }
                } finally {
                    child.recycle()
                }
            }
            return null
        }
        val child = descendant(label) ?: return false
        return try {
            child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            child.recycle()
        }
    }

    private fun assertReplacement(state: FixtureState, query: String, caret: Int) {
        waitUntil("Suggestion did not update the query and caret") {
            onMain { state.query == query && state.selection == TextRange(caret) }
        }
        assertEditable(query, TextRange(caret))
    }

    private fun assertEditable(text: String, selection: TextRange) {
        waitUntil("Widget did not render expected text/selection") {
            val node = findNode { it.isEditable }
            try {
                node != null && node.text?.toString() == text &&
                    node.textSelectionStart == selection.start && node.textSelectionEnd == selection.end
            } finally {
                node?.recycle()
            }
        }
    }

    private fun withNode(matches: (AccessibilityNodeInfo) -> Boolean, action: (AccessibilityNodeInfo) -> Unit) {
        var node: AccessibilityNodeInfo? = null
        waitUntil("Expected widget accessibility node is missing") {
            findNode(matches).also { node = it } != null
        }
        try {
            action(checkNotNull(node))
        } finally {
            node?.recycle()
        }
    }

    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // Compose accessibility events are throttled separately from main-thread idle. A cached
        // editor may still carry its preceding text, making a selection action restore stale text.
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
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
        instrumentation.uiAutomation.rootInActiveWindow?.let(roots::add)
        for (window in instrumentation.uiAutomation.windows) {
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

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private class FixtureState {
        var query by mutableStateOf("fixture")
        var selection by mutableStateOf(TextRange(2))
        var textChanges = 0
        var selectionChanges = 0
        var choices = 0
        val submissions = mutableListOf<String>()
        val input = AtomicReference<InputConnection?>()

        fun token() = HitomiSearchQuery.activeToken(query, selection.start, selection.end)

        fun changeText(value: String?) {
            query = value.orEmpty()
            textChanges++
        }

        fun changeSelection(value: TextRange) {
            selection = value
            selectionChanges++
        }

        fun submit(value: String) {
            submissions += value
        }

        fun choose() {
            val token = token() ?: return
            val replacement = HitomiSearchQuery.replaceAt(query, token, "language:japanese") ?: return
            query = replacement.query
            selection = TextRange(replacement.caret)
            choices++
        }
    }

    @Composable
    private fun Fixture(state: FixtureState, floating: Boolean) {
        InterceptPlatformTextInput(
            interceptor = { request, _ ->
                val connection = request.createInputConnection(EditorInfo())
                state.input.set(connection)
                try {
                    awaitCancellation()
                } finally {
                    state.input.compareAndSet(connection, null)
                    connection.closeConnection()
                }
            },
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column {
                        if (floating) {
                            BrowseSourceFloatingBall(
                                expanded = true,
                                onBallClick = {},
                                focusRequester = remember { FocusRequester() },
                                searchQuery = state.query,
                                onSearchQueryChange = state::changeText,
                                onSubmitSearch = state::submit,
                                onClearSearch = {},
                                hasActiveFilters = false,
                                onClickFilter = {},
                                isSnapshot = false,
                                onFetchDetails = {},
                                fetchRunning = false,
                                tapMenuItems = emptyList(),
                                longPressMenuItems = emptyList(),
                                menuExpanded = false,
                                onMenuExpandedChange = {},
                                searchSelection = state.selection,
                                onSearchSelectionChange = state::changeSelection,
                            )
                        } else {
                            SearchToolbar(
                                searchQuery = state.query,
                                onChangeSearchQuery = state::changeText,
                                onSearch = state::submit,
                                searchSelection = state.selection,
                                onSearchSelectionChange = state::changeSelection,
                            )
                        }
                        if (state.token() != null) {
                            Button(
                                onClick = state::choose,
                                modifier = Modifier.semantics { contentDescription = CHOOSE },
                            ) {
                                Text("Choose Japanese")
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val CHOOSE = "Synthetic Japanese suggestion"
    }
}
