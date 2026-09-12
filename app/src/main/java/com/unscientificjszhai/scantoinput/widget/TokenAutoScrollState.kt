package com.unscientificjszhai.scantoinput.widget

/**
 * 保存边缘滚动的小数像素并计算每帧位移。
 * @param edgeSizePx 边缘触发带宽度。
 * @param maximumSpeedPxPerSecond 最大每秒像素速度。
 */
class TokenAutoScrollState(
    private val edgeSizePx: Float,
    private val maximumSpeedPxPerSecond: Float
) {
    /** 即使当前整数位移为零，仍可能正在累计小数。 */
    var isActive = false
        private set

    /** 当前方向，-1 向上、1 向下、0 已停止。 */
    var direction = 0
        private set
    private var remainder = 0f

    /**
     * 计算当前帧的整数位移。
     * @param pointerY 可见坐标系中的指针纵坐标。
     * @param viewportTop 视口顶部。
     * @param viewportBottom 视口底部。
     * @param elapsedMillis 自上帧经过的毫秒数，限制在 0..50。
     * @param canScrollUp 是否还能向上滚动。
     * @param canScrollDown 是否还能向下滚动。
     * @return 本帧有符号的像素位移。
     */
    fun calculateDelta(
        pointerY: Float, viewportTop: Float, viewportBottom: Float,
        elapsedMillis: Long, canScrollUp: Boolean, canScrollDown: Boolean
    ): Int {
        val edge = minOf(edgeSizePx, (viewportBottom - viewportTop) / 2f)
        if (edge <= 0f || maximumSpeedPxPerSecond <= 0f) {
            reset()
            return 0
        }
        val nextDirection: Int
        val depth: Float
        if (pointerY < viewportTop + edge) {
            nextDirection = -1
            depth = viewportTop + edge - pointerY
        } else if (pointerY > viewportBottom - edge) {
            nextDirection = 1
            depth = pointerY - viewportBottom + edge
        } else {
            reset()
            return 0
        }
        if (nextDirection < 0 && !canScrollUp || nextDirection > 0 && !canScrollDown) {
            reset()
            return 0
        }
        if (direction != nextDirection) remainder = 0f
        direction = nextDirection
        isActive = true
        val elapsed = elapsedMillis.coerceIn(0L, 50L)
        val pixels = direction * minOf(
            depth / edge,
            1f
        ) * maximumSpeedPxPerSecond * elapsed / 1000f + remainder
        val delta = pixels.toInt()
        remainder = pixels - delta
        return delta
    }

    /** 停止并清除方向与剩余小数。 */
    fun reset() {
        isActive = false
        direction = 0
        remainder = 0f
    }
}
