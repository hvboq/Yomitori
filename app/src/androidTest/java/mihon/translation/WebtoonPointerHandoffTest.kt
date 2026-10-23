package mihon.translation

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonFrame
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screen-free dispatch through the real Frame/ViewGroup coordinate transforms and recycler detector.
 * Zoom is prepared with onScale, so these tests do not claim a physical pinch or Activity UI run.
 */
@RunWith(AndroidJUnit4::class)
class WebtoonPointerHandoffTest {

    @Test
    fun liftingTrackedPointerKeepsRemainingFingerZoomPanResponsive() = fixture {
        val first = Finger(11, 300f, 1000f)
        val tracked = Finger(19, 700f, 1000f)
        dispatch(MotionEvent.ACTION_DOWN, listOf(first))
        dispatch(MotionEvent.ACTION_POINTER_DOWN, listOf(first, tracked), actionIndex = 1)

        val beforeLiftX = recycler.translationX
        val beforeLiftY = recycler.translationY
        dispatch(MotionEvent.ACTION_POINTER_UP, listOf(first, tracked), actionIndex = 1)
        assertPosition("Pointer handoff itself must not move the image", beforeLiftX, beforeLiftY)

        dispatch(MotionEvent.ACTION_MOVE, listOf(first))
        assertPosition("A stationary remaining pointer must not jump", beforeLiftX, beforeLiftY)

        dispatch(MotionEvent.ACTION_MOVE, listOf(first.copy(x = 480f)))
        assertTrue(
            "The remaining pointer must continue actual zoom pan without a fresh DOWN",
            recycler.translationX > beforeLiftX + 1f,
        )
        assertEquals("Handoff must preserve zoom", 2f, recycler.scaleX, EPSILON)
    }

    @Test
    fun liftingUntrackedPointerKeepsTrackedFingerZoomPanResponsive() = fixture {
        val first = Finger(11, 300f, 1000f)
        val tracked = Finger(19, 700f, 1000f)
        dispatch(MotionEvent.ACTION_DOWN, listOf(first))
        dispatch(MotionEvent.ACTION_POINTER_DOWN, listOf(first, tracked), actionIndex = 1)

        val beforeLiftX = recycler.translationX
        val beforeLiftY = recycler.translationY
        dispatch(MotionEvent.ACTION_POINTER_UP, listOf(first, tracked), actionIndex = 0)
        assertPosition("Lifting an untracked pointer must not move the image", beforeLiftX, beforeLiftY)

        dispatch(MotionEvent.ACTION_MOVE, listOf(tracked))
        assertPosition("The stationary tracked pointer must not jump", beforeLiftX, beforeLiftY)

        dispatch(MotionEvent.ACTION_MOVE, listOf(tracked.copy(x = 880f)))
        assertTrue(
            "The original tracked pointer must continue actual zoom pan",
            recycler.translationX > beforeLiftX + 1f,
        )
        assertEquals("Pointer removal must preserve zoom", 2f, recycler.scaleX, EPSILON)
    }

    @Test
    fun cancelThenFreshDownAllowsZoomPanAgain() = fixture {
        val first = Finger(11, 300f, 1000f)
        val tracked = Finger(19, 700f, 1000f)
        dispatch(MotionEvent.ACTION_DOWN, listOf(first))
        dispatch(MotionEvent.ACTION_POINTER_DOWN, listOf(first, tracked), actionIndex = 1)
        dispatch(MotionEvent.ACTION_CANCEL, listOf(first, tracked))

        val beforeFreshX = recycler.translationX
        val fresh = Finger(23, 350f, 1000f)
        dispatch(MotionEvent.ACTION_DOWN, listOf(fresh), newGesture = true)
        dispatch(MotionEvent.ACTION_MOVE, listOf(fresh.copy(x = 530f)))
        assertTrue(
            "A new gesture after CANCEL must pan the zoomed recycler",
            recycler.translationX > beforeFreshX + 1f,
        )
        assertEquals("Cancellation must preserve prepared zoom", 2f, recycler.scaleX, EPSILON)
    }

    private fun fixture(block: PointerFixture.() -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals(
            "Pointer audit must target the isolated app",
            "app.yomitori.yakuyomi.audit",
            instrumentation.targetContext.packageName,
        )
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            var fixture: PointerFixture? = null
            try {
                val initialized = PointerFixture(instrumentation.targetContext)
                fixture = initialized
                initialized.block()
            } catch (caught: Throwable) {
                // Expected regression failures must reach JUnit, not escape the app's main looper.
                failure = caught
            } finally {
                try {
                    fixture?.close()
                } catch (cleanupFailure: Throwable) {
                    if (failure == null) failure = cleanupFailure else failure!!.addSuppressed(cleanupFailure)
                }
            }
        }
        failure?.let { throw it }
    }

    private data class Finger(val id: Int, val x: Float, val y: Float)

    private class PointerFixture(context: Context) {
        val frame = WebtoonFrame(context)
        val recycler = WebtoonRecyclerView(context)
        private var downTime = SystemClock.uptimeMillis()
        private var eventTime = downTime
        private var lastPointers = listOf(Finger(11, 300f, 1000f))

        init {
            recycler.layoutManager = LinearLayoutManager(context)
            recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val view = View(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
                    }
                    return object : RecyclerView.ViewHolder(view) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

                override fun getItemCount() = 20
            }
            frame.addView(
                recycler,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            // Avoid double-tap/quick-scale animations; this fixture checks pointer ownership while zoomed.
            frame.doubleTapZoom = false
            measureAndLayout()
            assertTrue("Neutral adapter must have real laid-out children", recycler.childCount > 0)
            recycler.onScale(2f)
            measureAndLayout()
            assertEquals("Prepare actual recycler zoom", 2f, recycler.scaleX, EPSILON)
        }

        fun dispatch(
            action: Int,
            fingers: List<Finger>,
            actionIndex: Int = 0,
            newGesture: Boolean = false,
        ) {
            eventTime += 16
            if (newGesture) downTime = eventTime
            lastPointers = fingers
            val properties = fingers.map { finger ->
                MotionEvent.PointerProperties().apply {
                    id = finger.id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }.toTypedArray()
            val coordinates = fingers.map { finger ->
                MotionEvent.PointerCoords().apply {
                    x = finger.x
                    y = finger.y
                    pressure = 1f
                    size = 1f
                }
            }.toTypedArray()
            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                fingers.size,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
            try {
                assertTrue("The real frame must dispatch the gesture", frame.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun assertPosition(message: String, x: Float, y: Float) {
            assertEquals("$message (x)", x, recycler.translationX, EPSILON)
            assertEquals("$message (y)", y, recycler.translationY, EPSILON)
        }

        fun close() {
            try {
                // Both frame detectors and the custom recycler detector receive cancellation.
                dispatch(MotionEvent.ACTION_CANCEL, lastPointers)
            } finally {
                recycler.stopScroll()
                recycler.adapter = null
                recycler.layoutManager = null
                frame.removeAllViews()
            }
        }

        private fun measureAndLayout() {
            frame.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            frame.layout(0, 0, 1080, 2000)
        }
    }

    private companion object {
        const val EPSILON = 0.01f
    }
}
