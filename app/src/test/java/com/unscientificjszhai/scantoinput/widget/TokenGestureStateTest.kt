package com.unscientificjszhai.scantoinput.widget

import org.junit.Assert.*
import org.junit.Test

/** 验证共享方向判定的阈值和锁定规则。 */
class TokenGestureStateTest {
    /** 无开始事件时不判定，阈值内保持 pending。 */
    @Test
    fun idleThresholdAndReset() {
        val state = TokenGestureState(8f)
        assertEquals(TokenGestureState.IDLE, state.move(100f, 100f))
        state.begin(10f, 10f)
        assertEquals(TokenGestureState.PENDING, state.move(18f, 10f))
        assertEquals(TokenGestureState.PENDING, state.move(10f, 2f))
        state.reset()
        assertEquals(TokenGestureState.IDLE, state.mode)
    }

    /** 四个方向以及斜向等距的选择保持至本次结束。 */
    @Test
    fun signedDirectionsAndTieRemainLocked() {
        for ((x, y, expected) in listOf(
            Triple(20f, 12f, TokenGestureState.HORIZONTAL),
            Triple(-20f, -12f, TokenGestureState.HORIZONTAL),
            Triple(12f, 20f, TokenGestureState.VERTICAL),
            Triple(-12f, -20f, TokenGestureState.VERTICAL),
            Triple(20f, 20f, TokenGestureState.VERTICAL)
        )) {
            val state = TokenGestureState(8f)
            state.begin(0f, 0f)
            assertEquals(expected, state.move(x, y))
            assertEquals(expected, state.move(y * 10f, x * 10f))
        }
    }
}
