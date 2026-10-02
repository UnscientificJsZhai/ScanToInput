package com.unscientificjszhai.scantoinput.widget

import kotlin.math.abs

/**
 * 父滚动容器和选词控件共享的单次方向判定。
 * @param touchSlop 允许点击微动的像素阈值。
 */
class TokenGestureState(private val touchSlop: Float) {
    /** 当前手势方向，确定后持续到 reset。 */
    var mode = IDLE
        private set
    private var downX = 0f
    private var downY = 0f

    /**
     * 开始等待方向。
     * @param x 起点横坐标。
     * @param y 起点纵坐标。
     */
    fun begin(x: Float, y: Float) {
        downX = x
        downY = y
        mode = PENDING
    }

    /**
     * 更新位移，斜向距离相等时归入纵向。
     * @param x 当前横坐标。
     * @param y 当前纵坐标。
     * @return 当前方向。
     */
    fun move(x: Float, y: Float): Int {
        if (mode != PENDING) return mode
        val dx = abs(x - downX)
        val dy = abs(y - downY)
        if (maxOf(dx, dy) > touchSlop) mode = if (dx > dy) HORIZONTAL else VERTICAL
        return mode
    }

    /** 清理本次方向。 */
    fun reset() {
        mode = IDLE
    }

    /** 不分配临时对象的手势状态常量。 */
    companion object {
        const val IDLE = 0
        const val PENDING = 1
        const val HORIZONTAL = 2
        const val VERTICAL = 3
    }
}
