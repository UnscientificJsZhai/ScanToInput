package com.unscientificjszhai.scantoinput.widget

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** 用有限真实帧推进验证边缘滚动和统一终止，而不读取私有手势状态。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TokenSelectionAutoScrollTest {
    /** 已滚动内容上的固定上下边缘指针持续选择新出现的逻辑 token。 */
    @Test
    fun preScrolledCoordinatesAndBothEdgesContinueSelection() {
        val tokens = List(1000) { "t$it" }
        for (down in listOf(true, false)) {
            val f = TokenSelectionTestFixture(tokens)
            f.scroll.scrollTo(0, 500)
            f.advance(16)
            val anchor = f.beginDrag()
            f.moveToEdge(down, x = f.view.width - 2f)
            val beforeText = f.view.getSelectedText()
            f.advance(160)
            val first = f.scroll.scrollY
            assertTrue(if (down) first > 500 else first < 500)
            f.advance(160)
            assertTrue(if (down) f.scroll.scrollY > first else f.scroll.scrollY < first)
            val after = f.view.getSelectedText()
            assertTrue(after.contains(tokens[anchor]))
            assertTrue(after.length > beforeText.length)
            f.cancel()
        }
    }

    /** 浅入边缘产生连续零整数位移帧，累积后仍真实滚动且重复 MOVE 不加倍速度。 */
    @Test
    fun activeZeroDeltaFramesAccumulateWithoutDuplicateScheduling() {
        val f = TokenSelectionTestFixture(List(1000) { "word" })
        f.beginDrag()
        val initialViewport = f.viewport()
        repeat(5) { f.moveToEdge(true, depth = 0.1f) }
        f.advance(16)
        assertEquals(0, f.scroll.scrollY)
        f.advance(16)
        assertEquals(0, f.scroll.scrollY)
        f.advance(768)
        assertTrue(
            "小数余量需要在后续帧积成真实位移，实际 scroll=${f.scroll.scrollY}, initial=$initialViewport, viewport=${f.viewport()}",
            f.scroll.scrollY in 1..2
        )
        f.cancel()
        val stopped = f.scroll.scrollY
        f.advance(500)
        assertEquals(stopped, f.scroll.scrollY)
    }

    /** 离开边缘立即停止，反向进入上边缘后按新方向滚动。 */
    @Test
    fun leavingEdgeStopsAndDirectionChangeRestarts() {
        val f = TokenSelectionTestFixture(List(1000) { "word" })
        f.scroll.scrollTo(0, 500)
        f.advance(16)
        f.beginDrag()
        f.moveToEdge(true)
        f.advance(64)
        val afterDown = f.scroll.scrollY
        val visible = f.viewport()
        f.touch(MotionEvent.ACTION_MOVE, 75f, visible.exactCenterY())
        f.advance(160)
        assertEquals(afterDown, f.scroll.scrollY)
        f.moveToEdge(false)
        f.advance(64)
        assertTrue(f.scroll.scrollY < afterDown)
        f.cancel()
    }

    /** 所有终止入口都移除帧，取消本身不回滚已提交选择。 */
    @Test
    fun terminalLifecycleAndDataChangesLeaveNoLateScrollOrCallback() {
        for (reason in listOf(
            "up",
            "cancel",
            "focus",
            "detach",
            "replace",
            "clear",
            "padding",
            "width",
            "accessibility",
            "disabled"
        )) {
            val f = TokenSelectionTestFixture(List(1000) { "word" })
            f.beginDrag()
            f.moveToEdge(true)
            var notifications = 0
            f.view.onSelectionChangedListener = { notifications++ }
            when (reason) {
                "up" -> f.touch(MotionEvent.ACTION_UP, 75f, f.viewport().bottom - 2f)
                "cancel" -> f.cancel()
                "focus" -> f.view.onWindowFocusChanged(false)
                "detach" -> f.activity.setContentView(View(f.activity))
                "replace" -> f.view.setTokens(listOf("new"))
                "clear" -> f.view.clearSelection()
                "padding", "width" -> {
                    if (reason == "padding") f.view.setPadding(0, 1, 0, 0)
                    val width = f.view.width - if (reason == "width") 1 else 0
                    f.view.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    f.view.layout(0, 0, width, f.view.measuredHeight)
                }

                "accessibility" -> assertTrue(
                    f.view.accessibilityNodeProvider!!.performAction(
                        1,
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null
                    )
                )

                "disabled" -> {
                    f.view.isEnabled = false
                    f.touch(MotionEvent.ACTION_MOVE, 75f, 5f)
                }
            }
            val scroll = f.scroll.scrollY
            val selected = f.view.getSelectedText()
            val count = notifications
            f.advance(16)
            if (reason !in listOf("replace", "padding", "width")) assertEquals(
                "首帧 $reason",
                scroll,
                f.scroll.scrollY
            )
            assertEquals("首帧 $reason", selected, f.view.getSelectedText())
            assertEquals("首帧 $reason", count, notifications)
            f.advance(48)
            val settledScroll = f.scroll.scrollY
            f.advance(300)
            assertEquals(reason, settledScroll, f.scroll.scrollY)
            assertEquals(reason, selected, f.view.getSelectedText())
            assertEquals(reason, count, notifications)
            if (reason == "cancel") assertTrue(f.view.hasSelection())
        }
    }

    /** 帧中的选择通知替换列表时，已经预约的下一帧也被同步清除。 */
    @Test
    fun autoScrollCallbackReplacementCannotRestartOldFrames() {
        val f = TokenSelectionTestFixture(List(1000) { "word" })
        f.beginDrag()
        f.moveToEdge(true)
        var callbacks = 0
        f.view.onSelectionChangedListener = {
            callbacks++
            if (f.view.getFullText().startsWith("word")) f.view.setTokens(listOf("replacement"))
        }
        f.advance(160)
        assertEquals("replacement", f.view.getFullText())
        assertFalse(f.view.hasSelection())
        assertEquals(2, callbacks)
        val stopped = f.scroll.scrollY
        f.advance(300)
        assertEquals(stopped, f.scroll.scrollY)
        assertEquals(2, callbacks)
    }

    /** 短内容和已到达边界不启动无穷帧；到达底部后选区保持稳定。 */
    @Test
    fun shortContentAndContentBoundsStopFrames() {
        val short = TokenSelectionTestFixture(listOf("abcdefghijklmnop"))
        short.touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        short.touch(MotionEvent.ACTION_MOVE, 75f, 5f)
        short.advance(300)
        assertEquals(0, short.scroll.scrollY)
        val f = TokenSelectionTestFixture(List(100) { "word" })
        f.scroll.scrollTo(0, f.view.height)
        f.advance(16)
        f.scroll.scrollBy(0, -10)
        f.beginDrag()
        f.moveToEdge(true)
        f.advance(300)
        assertFalse(f.scroll.canScrollVertically(1))
        val end = f.scroll.scrollY
        val selection = f.view.getSelectedText()
        f.advance(300)
        assertEquals(end, f.scroll.scrollY)
        assertEquals(selection, f.view.getSelectedText())
        f.cancel()
    }

    /** 父容器声明仍可滚动但拒绝非零移动时，仅尝试一次便停止。 */
    @Test
    fun nonzeroRequestedScrollWithNoActualMovementStopsScheduling() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scroll = BlockingScrollView(activity)
        val view = TokenSelectionView(activity)
        scroll.addView(view)
        activity.setContentView(scroll)
        view.setTokens(List(1000) { "word" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        val visible = android.graphics.Rect()
        assertTrue(view.getLocalVisibleRect(visible))
        send(view, MotionEvent.ACTION_DOWN, 5f, 5f)
        send(view, MotionEvent.ACTION_MOVE, 75f, 5f)
        send(view, MotionEvent.ACTION_MOVE, 75f, visible.bottom - 2f)
        scroll.nonzeroAttempts = 0
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertEquals(1, scroll.nonzeroAttempts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
        assertEquals(1, scroll.nonzeroAttempts)
    }

    /** 不在 ScrollView 内时仍可手动拖选，不调度自动滚动。 */
    @Test
    fun detachedFromScrollParentStillSupportsManualSelection() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TokenSelectionView(activity)
        activity.setContentView(view)
        view.setTokens(listOf("one", "two"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        send(view, MotionEvent.ACTION_DOWN, 5f, 5f)
        send(view, MotionEvent.ACTION_MOVE, 75f, 5f)
        assertTrue(view.hasSelection())
        send(view, MotionEvent.ACTION_CANCEL, 75f, 5f)
        val selected = view.getSelectedText()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(selected, view.getSelectedText())
    }

    /**
     * 发送具有正确屏幕坐标的直接事件，供自定义父容器边界夹具使用。
     * @param view 目标控件。
     * @param action 触摸动作。
     * @param x 内容横坐标。
     * @param y 内容纵坐标。
     */
    private fun send(view: View, action: Int, x: Float, y: Float) {
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        val event = MotionEvent.obtain(
            0,
            android.os.SystemClock.uptimeMillis(),
            action,
            x + origin[0],
            y + origin[1],
            0
        )
        event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /**
     * 模拟布局边界同时到达导致 scrollBy 不移动的父容器。
     * @param context 测试窗口上下文。
     */
    private class BlockingScrollView(context: Context) : ScrollView(context) {
        var nonzeroAttempts = 0
        override fun canScrollVertically(direction: Int): Boolean = direction > 0
        override fun scrollTo(x: Int, y: Int) {
            if (y != scrollY) nonzeroAttempts++
        }
    }
}
