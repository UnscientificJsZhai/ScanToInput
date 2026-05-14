package com.unscientificjszhai.scantoinput.widget

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Token 选择控件的 JVM 行为测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenSelectionViewTest {

    /**
     * 验证连续选择手势期间父容器不会拦截触摸事件。
     */
    @Test
    fun touchTokenDisallowsParentInterceptUntilGestureEnds() {
        val context = RuntimeEnvironment.getApplication()
        val parent = InterceptRecordingLayout(context)
        val view = measuredTokenSelectionView(context, parent, listOf("Hello", " ", "World"))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, 1f))
        assertEquals(listOf(true), parent.disallowInterceptRequests)

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, 1f))
        assertEquals(listOf(true, false), parent.disallowInterceptRequests)
    }

    /**
     * 验证滑动选择会选中起点到当前命中 Token 的完整区间。
     */
    @Test
    fun dragSelectionSelectsRangeFromStartToCurrentToken() {
        val context = RuntimeEnvironment.getApplication()
        val parent = InterceptRecordingLayout(context)
        val view = measuredTokenSelectionView(context, parent, listOf("A", "B", "C", "D"))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, tokenCenterX(view, 0)))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, tokenCenterX(view, 2)))

        assertEquals("ABC", view.getSelectedText())

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, tokenCenterX(view, 1)))

        assertEquals("AB", view.getSelectedText())
    }

    /**
     * 创建已测量的 Token 选择控件。
     *
     * @param context Android 上下文。
     * @param parent 用于承载控件的父容器。
     * @param tokens 要显示的 Token 列表。
     * @return 已完成测量的 Token 选择控件。
     */
    private fun measuredTokenSelectionView(
        context: Context,
        parent: ViewGroup,
        tokens: List<String>
    ): TokenSelectionView {
        val view = TokenSelectionView(context)
        parent.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        view.setTokens(tokens)

        val widthSpec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        parent.measure(widthSpec, heightSpec)
        return view
    }

    /**
     * 创建用于测试的触摸事件。
     *
     * @param action 触摸事件类型。
     * @param x 事件 X 坐标。
     * @return 新建的触摸事件。
     */
    private fun motionEvent(action: Int, x: Float): MotionEvent {
        return MotionEvent.obtain(0L, 0L, action, x, 1f, 0)
    }

    /**
     * 读取指定 Token 的水平中心坐标。
     *
     * @param view Token 选择控件。
     * @param index Token 索引。
     * @return 指定 Token 的水平中心坐标。
     */
    private fun tokenCenterX(view: TokenSelectionView, index: Int): Float {
        val layoutInfosField = TokenSelectionView::class.java.getDeclaredField("layoutInfos")
        layoutInfosField.isAccessible = true
        val layoutInfos = layoutInfosField.get(view) as Array<*>
        val info = layoutInfos[index] ?: error("Token layout info missing")
        val xField = info.javaClass.getDeclaredField("x")
        val widthField = info.javaClass.getDeclaredField("width")
        xField.isAccessible = true
        widthField.isAccessible = true
        return xField.getFloat(info) + widthField.getFloat(info) / 2f
    }

    /**
     * 记录子 View 请求父容器禁止拦截的测试容器。
     *
     * @param context Android 上下文。
     */
    private class InterceptRecordingLayout(context: Context) : LinearLayout(context) {
        val disallowInterceptRequests = mutableListOf<Boolean>()

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            disallowInterceptRequests.add(disallowIntercept)
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }
}
