package com.unscientificjszhai.scantoinput.widget

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import com.unscientificjszhai.scantoinput.R
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * 使用真实布局和稳定窗口提供公开交互测试夹具。
 * @param tokens 初始逻辑 token。
 */
internal class TokenSelectionTestFixture(tokens: List<String>) {
    init {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root: View
    val scroll: ScrollView
    val view: TokenSelectionView

    init {
        activity.setTheme(R.style.Theme_ScanToInput)
        root = LayoutInflater.from(activity).inflate(R.layout.activity_main, null)
        activity.setContentView(root)
        scroll = root.findViewById(R.id.result_container)
        view = root.findViewById(R.id.token_selection_view)
        view.setTokens(tokens)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 320, 800)
        advance(64)
        scroll.scrollTo(0, 0)
        advance(16)
        assertTrue(view.isAttachedToWindow)
    }

    /**
     * 有限推进已暂停的真实帧时钟。
     * @param millis 本次推进毫秒数。
     */
    fun advance(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }

    /**
     * 读取实际祖先裁剪后的内容视口。
     * @return 可见内容矩形。
     */
    fun viewport(): Rect = Rect().also { assertTrue(view.getLocalVisibleRect(it)) }

    /**
     * 读取生产几何适配接口的 token 边界。
     * @param index 逻辑索引。
     * @return 内容坐标矩形。
     */
    fun bounds(index: Int): Rect = Rect().also { view.tokenBounds(index, it) }

    /**
     * 通过真实父容器分发内容坐标事件，保留正确 raw 坐标。
     * @param action 触摸动作。
     * @param x 内容横坐标。
     * @param y 内容纵坐标。
     */
    fun touch(action: Int, x: Float, y: Float) {
        val child = IntArray(2)
        val parent = IntArray(2)
        view.getLocationOnScreen(child)
        scroll.getLocationOnScreen(parent)
        val event = MotionEvent.obtain(
            0, SystemClock.uptimeMillis(), action,
            x + child[0], y + child[1], 0
        )
        event.offsetLocation(-parent[0].toFloat(), -parent[1].toFloat())
        try {
            scroll.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /**
     * 点击指定 token 中心。
     * @param index 当前逻辑索引。
     */
    fun tap(index: Int) {
        val rect = bounds(index)
        touch(MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
        touch(MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
    }

    /**
     * 从真实可见 token 横向启动拖选。
     * @return 起点逻辑索引。
     */
    fun beginDrag(): Int {
        val range = TokenIndexRange()
        view.visibleTokens(range)
        val visible = viewport()
        var index = range.firstIndex
        while (bounds(index).top < visible.top) index++
        val rect = bounds(index)
        touch(MotionEvent.ACTION_DOWN, rect.left + 2f, rect.exactCenterY())
        touch(MotionEvent.ACTION_MOVE, rect.left + 72f, rect.exactCenterY())
        assertTrue(view.hasSelection())
        return index
    }

    /**
     * 将活动指针放入真实视口边缘。
     * @param bottom 是否使用下边缘。
     * @param depth 从边缘带内边界进入的深度，null 表示距外边缘 2px。
     * @param x 内容横坐标，可定位行尾空白。
     */
    fun moveToEdge(bottom: Boolean, depth: Float? = null, x: Float = 75f) {
        val visible = viewport()
        val edge = minOf(48f * view.resources.displayMetrics.density, visible.height() / 2f)
        val y = if (bottom) {
            if (depth == null) visible.bottom - 2f else visible.bottom - edge + depth
        } else {
            if (depth == null) visible.top + 2f else visible.top + edge - depth
        }
        touch(MotionEvent.ACTION_MOVE, x, y)
    }

    /** 取消当前真实触摸流，保留已应用选择。 */
    fun cancel() {
        touch(MotionEvent.ACTION_CANCEL, 5f, viewport().top + 5f)
    }
}
