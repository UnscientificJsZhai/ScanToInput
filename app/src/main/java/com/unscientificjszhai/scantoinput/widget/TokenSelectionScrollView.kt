package com.unscientificjszhai.scantoinput.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView

/**
 * 在原生父拦截之前保留已判定为水平的选词手势。
 * @param context 当前主题上下文。
 * @param attrs XML 属性。
 * @param defStyleAttr 默认滚动样式。
 */
class TokenSelectionScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle
) : ScrollView(context, attrs, defStyleAttr) {
    private val direction =
        TokenGestureState(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var pointerId = MotionEvent.INVALID_POINTER_ID

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                direction.begin(event.x, event.y)
                return super.onInterceptTouchEvent(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0 && direction.move(
                        event.getX(index),
                        event.getY(index)
                    ) == TokenGestureState.HORIZONTAL
                ) return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                direction.reset()
                pointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return super.onInterceptTouchEvent(event)
    }
}
