package mihon.translation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.presentation.core.components.CheckboxItem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The reader's actual production CheckboxItem, rendered in the existing isolated Compose host.
 * This checks the component's role/state and single row action with local state only. It does not
 * open ReaderSettingsDialog, read or change landscapeZoom, prove Android's accessibility bridge,
 * or simulate a physical user's screen touch. The prewarm gate restores its own live setting.
 */
@RunWith(AndroidJUnit4::class)
class ReaderCheckboxAccessibilityTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(OwnPrewarmGate()).around(compose)

    @Test
    fun productionCheckboxItemExposesStateAndLabelClicksToggleExactlyOnce() {
        val checked = mutableStateOf(true)
        var callbackCount = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(360.dp)) {
                        CheckboxItem(
                            label = LABEL,
                            checked = checked.value,
                            onClick = {
                                callbackCount++
                                checked.value = !checked.value
                            },
                        )
                    }
                }
            }
        }

        fun assertState(expected: Boolean, expectedCallbacks: Int) {
            val row = compose.onNode(hasText(LABEL) and hasClickAction())
                .assertIsDisplayed()
                .assertHasClickAction()
            val node = row.fetchSemanticsNode()
            val role = node.config.getOrNull(SemanticsProperties.Role)
            val state = node.config.getOrNull(SemanticsProperties.ToggleableState)
            // Keep the original observation even when the old component fails the first assertion.
            println(
                "READER_CHECKBOX_COMPONENT expected=$expected role=$role toggleableState=$state " +
                    "bounds=${node.boundsInRoot} expectedCallbacks=$expectedCallbacks " +
                    "actualReaderSettingsDialog=false actualReaderPreference=false",
            )
            assertEquals("Production CheckboxItem row must expose Checkbox role", Role.Checkbox, role)
            assertEquals(
                "Production CheckboxItem row must expose its current checked state",
                if (expected) ToggleableState.On else ToggleableState.Off,
                state,
            )
            compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
                useUnmergedTree = true,
            ).assertCountEquals(1)
            compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
                useUnmergedTree = true,
            ).assertCountEquals(1)
            compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
            compose.runOnIdle {
                assertEquals("One row click must invoke exactly one callback", expectedCallbacks, callbackCount)
                assertEquals("The callback must update the local checked value", expected, checked.value)
            }
        }

        assertState(expected = true, expectedCallbacks = 0)
        // Pointer input lands on the actual Text child; the owning whole-row action handles it.
        compose.onNodeWithText(LABEL, useUnmergedTree = true)
            .assertIsDisplayed()
            .performTouchInput { click(center) }
        assertState(expected = false, expectedCallbacks = 1)
        // Exercise the same owning row's semantics action as a separate single activation.
        compose.onNode(hasText(LABEL) and hasClickAction()).performClick()
        assertState(expected = true, expectedCallbacks = 2)
    }

    /** Same guarded Compose Activity lifecycle as EngineStatusPanelLayoutTest. */
    private class OwnPrewarmGate : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                assertEquals("Use the isolated audit package", "app.yomitori.yakuyomi.audit", context.packageName)
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
                val settings = OnlineReaderSettings(context)
                var failure: Throwable? = null
                try {
                    settings.set(Injekt.get<TranslationPreferences>().liveTranslate, false)
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
                        assertFalse("Component test must not warm the engine", service.warm.value)
                        assertFalse("Component test must not load the engine", service.loading.value)
                        assertEquals("Component test must not start translation", 0, TranslationBusy.active.value)
                        assertTrue("Component test must not enqueue translation", manager.queueState.value.isEmpty())
                    }
                    cleanup {
                        settings.close()
                        println("READER_CHECKBOX_COMPONENT_GATE livePreferenceRestoredIncludingPresence=true")
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

    companion object {
        private const val LABEL = "넓은 이미지로 자동 확대"
    }
}
