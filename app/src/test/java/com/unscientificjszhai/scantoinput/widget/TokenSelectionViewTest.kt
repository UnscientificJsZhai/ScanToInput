package com.unscientificjszhai.scantoinput.widget

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import com.unscientificjszhai.scantoinput.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** 使用真实布局、触摸分发、原生画布及无障碍接口验证选词控件。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TokenSelectionViewTest {
    /** 显式暂停 vsync，避免框架自动推进至全部帧结束。 */
    @Before
    fun configureFrameClock() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    /** 空结果仍具有可见占位高度。 */
    @Test
    fun emptyTokenListKeepsPlaceholderVisible() {
        val fixture = fixture(emptyList())
        assertTrue(fixture.view.measuredHeight > 0)
    }

    /** 按下只进入待判定状态，抬手才进行一次点击。 */
    @Test
    fun downOnTokenWaitsForGestureDirection() {
        val f = fixture(listOf("Hello", "World"))
        var notifications = 0
        var clicks = 0
        f.view.onSelectionChangedListener = { notifications++ }
        f.view.setOnClickListener { clicks++ }
        val point = pointInScroll(f, 5f, 5f)
        dispatch(f, MotionEvent.ACTION_DOWN, point[0], point[1])
        assertFalse("DOWN 不应选择", f.view.hasSelection())
        assertEquals(0, notifications)
        assertEquals(0, clicks)
        dispatch(f, MotionEvent.ACTION_UP, point[0], point[1])
        assertEquals("Hello", f.view.getSelectedText())
        assertEquals(1, notifications)
        assertEquals(1, clicks)
        dispatch(f, MotionEvent.ACTION_DOWN, point[0], point[1])
        dispatch(f, MotionEvent.ACTION_UP, point[0], point[1])
        assertFalse(f.view.hasSelection())
        assertEquals(2, notifications)
        assertEquals(2, clicks)
    }

    /** 纵向手势经过真实父容器分发后滚动，且不选词。 */
    @Test
    fun verticalSwipeScrollsWithoutSelecting() {
        val f = fixture(List(1000) { "word" })
        assertTrue(f.scroll.canScrollVertically(1))
        var notifications = 0
        var clicks = 0
        f.view.onSelectionChangedListener = { notifications++ }
        f.view.setOnClickListener { clicks++ }
        val point = pointInScroll(f, 5f, 5f)
        dispatch(f, MotionEvent.ACTION_DOWN, point[0], point[1])
        dispatch(f, MotionEvent.ACTION_MOVE, point[0], point[1] - 40f)
        dispatch(f, MotionEvent.ACTION_MOVE, point[0], point[1] - 100f)
        dispatch(f, MotionEvent.ACTION_UP, point[0], point[1] - 100f)
        assertTrue("纵滑应使父容器滚动", f.scroll.scrollY > 0)
        assertFalse(f.view.hasSelection())
        assertEquals(0, notifications)
        assertEquals(0, clicks)
    }

    /** 取消待判定手势和完整水平拖选均不触发点击回调。 */
    @Test
    fun cancellationAndHorizontalDragDoNotDispatchClick() {
        val f = TokenSelectionTestFixture(listOf("one", "two", "three"))
        var clicks = 0
        f.view.setOnClickListener { clicks++ }
        f.touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        f.cancel()
        assertFalse(f.view.hasSelection())
        assertEquals(0, clicks)
        f.beginDrag()
        val last = f.bounds(2)
        f.touch(MotionEvent.ACTION_MOVE, last.exactCenterX(), last.exactCenterY())
        f.touch(MotionEvent.ACTION_UP, last.exactCenterX(), last.exactCenterY())
        assertEquals("onetwothree", f.view.getSelectedText())
        assertEquals(0, clicks)
    }

    /** 超长 token 受宽度约束换行，选中时保持完整原文。 */
    @Test
    fun longLogicalTokenWrapsWithinAvailableWidth() {
        val short = fixture(listOf("x"))
        val raw = "https://example.com/" + "x".repeat(2000)
        val f = fixture(listOf(raw))
        assertTrue("单逻辑 token 应增加排版高度", f.view.height > short.view.height * 2)
        val canvas = recordingCanvas(f.view)
        f.view.draw(canvas)
        assertTrue(canvas.backgrounds.all { it.right <= f.view.width - f.view.paddingRight })
        tapFirst(f)
        assertEquals(raw, f.view.getSelectedText())
        assertEquals(raw, f.view.getFullText())
    }

    /** 显式换行保留多行高度和完整复制文本。 */
    @Test
    fun explicitNewlinesIncreaseSingleTokenHeight() {
        val short = fixture(listOf("A"))
        val raw = "A\nB\nC\n\n"
        val f = fixture(listOf(raw))
        assertTrue("显式换行不能画成单行", f.view.height > short.view.height * 2)
        tapFirst(f)
        assertEquals(raw, f.view.getSelectedText())
    }

    /** 实际画布裁剪后仅遍历相交 token，而不是全文。 */
    @Test
    fun clippedCanvasDrawsOnlyIntersectingTokens() {
        val f = fixture(List(2000) { "word" })
        f.scroll.scrollTo(0, 300)
        val canvas = recordingCanvas(f.view)
        canvas.translate(0f, -300f)
        canvas.clipRect(0f, 300f, f.view.width.toFloat(), 500f)
        f.view.draw(canvas)
        assertTrue(canvas.backgrounds.isNotEmpty())
        assertTrue("局部裁剪不应遍历全文", canvas.backgrounds.size < 200)
        assertTrue(canvas.backgrounds.all { it.bottom > 300f && it.top < 500f })
    }

    /** 实际 provider 公开 token 原文，并将点击映射到选择。 */
    @Test
    fun virtualProviderExposesTextAndChangesSelection() {
        val f = fixture(listOf("Hello"))
        val provider = f.view.accessibilityNodeProvider
        assertNotNull("选词控件必须提供虚拟节点", provider)
        val node = provider!!.createAccessibilityNodeInfo(1)!!
        assertNotNull(node)
        assertEquals("Hello", node.text.toString())
        assertFalse(node.isChecked)
        var notifications = 0
        var clicks = 0
        f.view.onSelectionChangedListener = { notifications++ }
        f.view.setOnClickListener { clicks++ }
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("Hello", f.view.getSelectedText())
        assertEquals(1, notifications)
        assertEquals(1, clicks)
        assertTrue(provider.createAccessibilityNodeInfo(1)!!.isChecked)
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertFalse(f.view.hasSelection())
        assertEquals(2, notifications)
        assertEquals(2, clicks)
    }

    /** 水平激活后固定边缘指针，后续帧仍继续滚动。 */
    @Test
    fun stationaryEdgePointerContinuesScrolling() {
        val f = fixture(List(2000) { "word" })
        val point = pointInScroll(f, 5f, 5f)
        dispatch(f, MotionEvent.ACTION_DOWN, point[0], point[1])
        dispatch(f, MotionEvent.ACTION_MOVE, point[0] + 70f, point[1])
        val visible = android.graphics.Rect()
        assertTrue(f.view.getLocalVisibleRect(visible))
        val edge = pointInScroll(f, 75f, visible.bottom - 2f)
        dispatch(f, MotionEvent.ACTION_MOVE, edge[0], edge[1])
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
        val firstScroll = f.scroll.scrollY
        f.view.getLocalVisibleRect(visible)
        assertTrue("固定边缘指针应触发逐帧滚动", firstScroll > 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
        assertTrue(f.scroll.scrollY > firstScroll)
        dispatch(f, MotionEvent.ACTION_CANCEL, point[0], point[1])
    }

    /** 抬手最终位移超过阈值时，即使仍在同一个宽 token 内也不点击。 */
    @Test
    fun pendingUpRechecksFinalDisplacementWithoutStartingDrag() {
        val f = TokenSelectionTestFixture(listOf("abcdefghijklmnop"))
        val slop = android.view.ViewConfiguration.get(f.activity).scaledTouchSlop.toFloat()
        var notifications = 0
        f.view.onSelectionChangedListener = { notifications++ }
        f.touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        f.touch(MotionEvent.ACTION_UP, 5f + slop * 2f, 5f)
        assertFalse(f.view.hasSelection())
        assertEquals(0, notifications)
        f.touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        f.touch(MotionEvent.ACTION_MOVE, 5f + slop / 2f, 5f)
        f.touch(MotionEvent.ACTION_UP, 5f + slop / 2f, 5f)
        assertTrue(f.view.hasSelection())
        assertEquals(1, notifications)
    }

    /** 真实父分发保留斜向横滑，同时把对角线并列方向交给纵向滚动。 */
    @Test
    fun parentDispatchKeepsDominantHorizontalAndVerticalTieRules() {
        val horizontal = TokenSelectionTestFixture(List(1000) { "word" })
        horizontal.touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        horizontal.touch(MotionEvent.ACTION_MOVE, 85f, 45f)
        assertTrue(horizontal.view.hasSelection())
        assertEquals(0, horizontal.scroll.scrollY)
        horizontal.cancel()
        val vertical = TokenSelectionTestFixture(List(1000) { "word" })
        vertical.touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        vertical.touch(MotionEvent.ACTION_MOVE, 45f, -35f)
        vertical.touch(MotionEvent.ACTION_MOVE, 85f, -75f)
        vertical.touch(MotionEvent.ACTION_UP, 85f, -75f)
        assertTrue(vertical.scroll.scrollY > 0)
        assertFalse(vertical.view.hasSelection())
    }

    /** 待判定取消不选词；已激活回拉恢复快照，取消保留本次实际选区。 */
    @Test
    fun cancellationRetractionAndNextGestureUseCommittedSelection() {
        val f = TokenSelectionTestFixture(listOf("one", "two", "three", "four"))
        f.touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        f.cancel()
        assertFalse(f.view.hasSelection())
        f.tap(1)
        val first = f.bounds(0)
        val last = f.bounds(3)
        val middle = f.bounds(1)
        f.touch(MotionEvent.ACTION_DOWN, first.exactCenterX(), first.exactCenterY())
        f.touch(MotionEvent.ACTION_MOVE, last.exactCenterX(), last.exactCenterY())
        assertEquals("onetwothreefour", f.view.getSelectedText())
        f.touch(MotionEvent.ACTION_MOVE, middle.exactCenterX(), middle.exactCenterY())
        assertEquals("onetwo", f.view.getSelectedText())
        f.cancel()
        assertEquals("onetwo", f.view.getSelectedText())
        f.touch(MotionEvent.ACTION_DOWN, middle.exactCenterX(), middle.exactCenterY())
        f.touch(MotionEvent.ACTION_MOVE, last.exactCenterX(), last.exactCenterY())
        f.touch(MotionEvent.ACTION_UP, last.exactCenterX(), last.exactCenterY())
        assertEquals("one", f.view.getSelectedText())
    }

    /** 起点之外的空白与离开起点的抬手都不制造点击。 */
    @Test
    fun blankDownAndUpOutsideOriginalTokenDoNotSelect() {
        val f = TokenSelectionTestFixture(listOf("one", "two"))
        var count = 0
        f.view.onSelectionChangedListener = { count++ }
        f.touch(MotionEvent.ACTION_DOWN, f.view.width - 1f, 5f)
        f.touch(MotionEvent.ACTION_UP, 5f, 5f)
        f.touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        f.touch(MotionEvent.ACTION_UP, f.view.width - 1f, 5f)
        assertFalse(f.view.hasSelection())
        assertEquals(0, count)
    }

    /** 选择通知同步替换列表后，当前事件不能恢复旧拖选或再挂旧帧。 */
    @Test
    fun selectionCallbackMayReplaceTokensReentrantly() {
        val f = TokenSelectionTestFixture(List(1000) { "word" })
        var count = 0
        f.view.onSelectionChangedListener = {
            count++
            if (f.view.getFullText().startsWith("word")) f.view.setTokens(listOf("replacement"))
        }
        f.touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        f.touch(MotionEvent.ACTION_MOVE, 75f, 5f)
        f.advance(300)
        assertEquals("replacement", f.view.getFullText())
        assertFalse(f.view.hasSelection())
        assertEquals(2, count)
        assertEquals(0, f.scroll.scrollY)
    }

    /** 异常活动指针与多指流都结束已有交互，不把选择转交第二根手指。 */
    @Test
    fun missingActivePointerAndMultiPointerTransitionsCleanInteraction() {
        for (action in listOf(
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        )) {
            val f = TokenSelectionTestFixture(List(1000) { "word" })
            f.beginDrag()
            f.moveToEdge(true)
            val ids =
                if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) intArrayOf(
                    7
                ) else intArrayOf(0, 7)
            sendPointers(f.view, action, ids)
            if (action == (MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))) {
                // 非活动指针离开不接管选择；随后活动指针离开完成清理。
                f.advance(16)
                assertTrue("非活动指针离开后原手势仍持续滚动", f.scroll.scrollY > 0)
                sendPointers(f.view, MotionEvent.ACTION_POINTER_UP, intArrayOf(0, 7))
            }
            val selected = f.view.getSelectedText()
            val scroll = f.scroll.scrollY
            f.advance(300)
            assertEquals(selected, f.view.getSelectedText())
            assertEquals(scroll, f.scroll.scrollY)
        }
    }

    /**
     * 经公开触摸入口发送带指定身份的多指事件。
     * @param view 生产选词控件。
     * @param action 含活动索引的动作。
     * @param ids 本次事件中的指针身份。
     */
    private fun sendPointers(view: View, action: Int, ids: IntArray) {
        val properties = Array(ids.size) {
            MotionEvent.PointerProperties()
                .apply {
                    id = ids[it]
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
        }
        val coordinates = Array(ids.size) {
            MotionEvent.PointerCoords().apply {
                x = 75f
                y = 5f
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            0, android.os.SystemClock.uptimeMillis(), action, ids.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /**
     * 创建已附着、按真实 XML 测量的结果区域。
     * @param tokens 原始 token 列表。
     * @return 可驱动公开接口的测试容器。
     */
    private fun fixture(tokens: List<String>): Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_ScanToInput)
        val root = LayoutInflater.from(activity).inflate(R.layout.activity_main, null)
        activity.setContentView(root)
        val scroll = root.findViewById<ScrollView>(R.id.result_container)
        val view = root.findViewById<TokenSelectionView>(R.id.token_selection_view)
        view.setTokens(tokens)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 320, 800)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        scroll.scrollTo(0, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        assertTrue(view.isAttachedToWindow)
        return Fixture(activity, scroll, view)
    }

    /**
     * 把内容坐标换为父容器坐标。
     * @param fixture 当前容器。
     * @param x 内容横坐标。
     * @param y 内容纵坐标。
     * @return 父容器中的坐标。
     */
    private fun pointInScroll(fixture: Fixture, x: Float, y: Float): FloatArray {
        val child = IntArray(2)
        val parent = IntArray(2)
        fixture.view.getLocationOnScreen(child)
        fixture.scroll.getLocationOnScreen(parent)
        return floatArrayOf(x + child[0] - parent[0], y + child[1] - parent[1])
    }

    /**
     * 通过实际父容器分发携带正确屏幕坐标的触摸事件。
     * @param fixture 当前容器。
     * @param action 触摸动作。
     * @param x 父容器横坐标。
     * @param y 父容器纵坐标。
     */
    private fun dispatch(fixture: Fixture, action: Int, x: Float, y: Float) {
        val origin = IntArray(2)
        fixture.scroll.getLocationOnScreen(origin)
        val event = MotionEvent.obtain(
            0, android.os.SystemClock.uptimeMillis(), action,
            x + origin[0], y + origin[1], 0
        )
        event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
        try {
            fixture.scroll.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /**
     * 点击首 token。
     * @param fixture 当前容器。
     */
    private fun tapFirst(fixture: Fixture) {
        val point = pointInScroll(fixture, 5f, 5f)
        dispatch(fixture, MotionEvent.ACTION_DOWN, point[0], point[1])
        dispatch(fixture, MotionEvent.ACTION_UP, point[0], point[1])
    }

    /**
     * 创建固定大小的原生裁剪画布。
     * @param view 待绘制控件。
     * @return 记录实际背景调用的画布。
     */
    private fun recordingCanvas(view: View): RecordingCanvas =
        RecordingCanvas(Bitmap.createBitmap(view.width, 200, Bitmap.Config.ARGB_8888))

    /**
     * 公开 UI 测试所需的容器。
     * @property activity 承载窗口的 Activity。
     * @property scroll 生产布局的真实滚动容器。
     * @property view 生产选词控件。
     */
    private data class Fixture(
        val activity: Activity,
        val scroll: ScrollView,
        val view: TokenSelectionView
    )

    /**
     * 记录应用背景调用，并保留实际画布裁剪行为。
     * @param bitmap 画布像素存储。
     */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val backgrounds = mutableListOf<RectF>()
        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
            backgrounds.add(RectF(rect))
            super.drawRoundRect(rect, rx, ry, paint)
        }
    }
}
