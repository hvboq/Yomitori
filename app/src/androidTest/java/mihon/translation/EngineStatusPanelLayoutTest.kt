package mihon.translation

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
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
import eu.kanade.tachiyomi.ui.translation.EngineStatusPanelContent
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
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Real Compose measurement/semantics on an isolated host, not the production queue Activity.
 * Font scale and English resources are composition-local. Live translation is temporarily disabled
 * before the host launches and restored, including presence, only after it returns to the background.
 * The following lazy queue row must retain usable space even with the long warm-state action.
 */
@RunWith(AndroidJUnit4::class)
class EngineStatusPanelLayoutTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(OwnPrewarmGate()).around(compose)

    @Test
    fun warmLongActionLeavesQueueRowVisibleAtLargeFontScale() {
        verifyState(
            warm = true,
            expectedStatus = MR.strings.engine_status_warm,
            expectedAction = MR.strings.engine_unload,
        )
    }

    @Test
    fun warmLongActionLeavesQueueRowVisibleAtObservedDeviceFontScale() {
        verifyState(
            warm = true,
            fontScale = 1.3f,
            expectedStatus = MR.strings.engine_status_warm,
            expectedAction = MR.strings.engine_unload,
        )
    }

    @Test
    fun coldPreloadActionLeavesQueueRowVisibleAtLargeFontScale() {
        verifyState(
            expectedStatus = MR.strings.engine_status_cold,
            expectedAction = MR.strings.engine_preload_action,
        )
    }

    @Test
    fun missingModelsActionLeavesQueueRowVisibleAtLargeFontScale() {
        verifyState(
            needsModels = true,
            expectedStatus = MR.strings.engine_status_models_missing,
            expectedAction = MR.strings.pref_translation_download_models,
        )
    }

    @Test
    fun outdatedModelsActionLeavesQueueRowVisibleAtLargeFontScale() {
        verifyState(
            needsModels = true,
            outdated = true,
            expectedStatus = MR.strings.engine_status_models_outdated,
            expectedAction = MR.strings.pref_translation_update_models,
        )
    }

    @Test
    fun loadingIndicatorTakesPrecedenceAndLeavesQueueRowVisibleAtLargeFontScale() {
        verifyState(
            loading = true,
            warm = true,
            needsModels = true,
            outdated = true,
            expectedStatus = MR.strings.engine_status_loading,
        )
    }

    private fun verifyState(
        loading: Boolean = false,
        warm: Boolean = false,
        needsModels: Boolean = false,
        outdated: Boolean = false,
        fontScale: Float = 2f,
        expectedStatus: dev.icerock.moko.resources.StringResource,
        expectedAction: dev.icerock.moko.resources.StringResource? = null,
    ) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Use the isolated audit package", "app.yomitori.yakuyomi.audit", target.packageName)
        val configuration = Configuration(target.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val localized = target.createConfigurationContext(configuration)
        val clicks = IntArray(3)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale = fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.size(width = 360.dp, height = 560.dp).testTag(VIEWPORT)) {
                        Column {
                            EngineStatusPanelContent(
                                loading = loading,
                                warm = warm,
                                needsModels = needsModels,
                                outdated = outdated,
                                onUnload = { clicks[0]++ },
                                onPreload = { clicks[1]++ },
                                onOpenModelSettings = { clicks[2]++ },
                                modifier = Modifier.testTag(PANEL),
                            )
                            LazyColumn(Modifier.fillMaxWidth()) {
                                item {
                                    Text(
                                        "Pending chapter · 1/2",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 16.dp)
                                            .testTag(QUEUE_ROW),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag(PANEL).fetchSemanticsNode().boundsInRoot
        val minimumQueueHeight = with(compose.density) { 48.dp.toPx() }
        println(
            "ENGINE_PANEL_LAYOUT loading=$loading warm=$warm needsModels=$needsModels outdated=$outdated " +
                "fontScale=$fontScale viewport=$viewport panel=$panel minQueueHeightPx=$minimumQueueHeight",
        )
        assertTrue(
            "Engine panel consumed the queue viewport: panel=$panel viewport=$viewport",
            panel.bottom + minimumQueueHeight <= viewport.bottom,
        )
        compose.onNodeWithTag(QUEUE_ROW).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val queue = compose.onNodeWithTag(QUEUE_ROW).fetchSemanticsNode().boundsInRoot
        assertTrue("Queue row must follow the panel", queue.top >= panel.bottom)
        assertTrue("Queue row must fit the viewport", queue.bottom <= viewport.bottom)

        val status = compose.onNodeWithText(localized.stringResource(expectedStatus)).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        status.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Expected the complete status text layout", 1, layouts.size)
        assertFalse("Status text must not be clipped", layouts.single().hasVisualOverflow)
        if (expectedAction != null) {
            compose.onNodeWithText(localized.stringResource(expectedAction))
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            val expectedClicks = when {
                warm -> listOf(1, 0, 0)
                needsModels -> listOf(0, 0, 1)
                else -> listOf(0, 1, 0)
            }
            compose.runOnIdle { assertEquals("Only the intended action must run", expectedClicks, clicks.toList()) }
        } else {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .assertCountEquals(1)
            compose.runOnIdle { assertEquals("Loading must expose no action", listOf(0, 0, 0), clicks.toList()) }
        }
    }

    private companion object {
        const val VIEWPORT = "engine-panel-viewport"
        const val PANEL = "engine-status-panel"
        const val QUEUE_ROW = "following-queue-row"
    }

    /** The test host is also a foreground Activity, so block the application's delayed prewarm. */
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
                println("ENGINE_PANEL_GATE phase=beforeHost warm=false loading=false busy=0 background=true")
                val settings = OnlineReaderSettings(context)
                var failure: Throwable? = null
                try {
                    settings.set(Injekt.get<TranslationPreferences>().liveTranslate, false)
                    base.evaluate() // Compose creates and closes its own ComponentActivity inside this gate.
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
                        // Do not release a native engine here: any unexpected loading must fail the test.
                        awaitBackground()
                        assertFalse("Test host must not warm the engine", service.warm.value)
                        assertFalse("Test host must not load the engine", service.loading.value)
                        assertEquals("Test host must not start translation", 0, TranslationBusy.active.value)
                        assertTrue("Test host must not enqueue translation", manager.queueState.value.isEmpty())
                        println("ENGINE_PANEL_GATE phase=afterHost warm=false loading=false busy=0 background=true")
                    }
                    cleanup {
                        settings.close()
                        println("ENGINE_PANEL_GATE livePreferenceRestoredIncludingPresence=true")
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
