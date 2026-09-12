package com.unscientificjszhai.scantoinput.widget

import org.junit.Assert.*
import org.junit.Test

/** 验证独立于 Android 帧调度的边缘滚动数学。 */
class TokenAutoScrollStateTest {
    /** 极浅边缘的多个零位移帧保持活跃，最终积满像素。 */
    @Test
    fun fractionalFramesEventuallyMoveAndDirectionResetsRemainder() {
        val state = TokenAutoScrollState(48f, 720f)
        repeat(3) {
            assertEquals(
                0,
                state.calculateDelta(152.1f, 0f, 200f, 16L, true, true)
            )
            assertTrue(state.isActive)
        }
        var total = 0
        repeat(100) { total += state.calculateDelta(152.1f, 0f, 200f, 16L, true, true) }
        assertTrue(total > 0)
        val fresh = TokenAutoScrollState(48f, 720f)
        assertEquals(
            fresh.calculateDelta(47.9f, 0f, 200f, 16L, true, true),
            state.calculateDelta(47.9f, 0f, 200f, 16L, true, true)
        )
        assertEquals(-1, state.direction)
        state.reset()
        assertFalse(state.isActive)
        assertEquals(0, state.direction)
    }

    /** 停止区域、边界与时间限制的完整合法组合。 */
    @Test
    fun edgesBoundsAndElapsedMatrix() {
        for (y in listOf(-100f, 0f, 50f, 100f, 150f, 151f, 199f, 300f))
            for (elapsed in listOf(-1L, 0L, 1L, 50L, 100L))
                for (up in listOf(false, true)) for (down in listOf(false, true)) {
                    val state = TokenAutoScrollState(50f, 100f)
                    val delta = state.calculateDelta(y, 0f, 200f, elapsed, up, down)
                    val expectedActive = (y < 50 && up) || (y > 150 && down)
                    assertEquals(expectedActive, state.isActive)
                    if (!expectedActive) assertEquals(0, delta)
                    if (state.direction < 0) assertTrue(delta <= 0) else assertTrue(delta >= 0)
                    assertTrue(kotlin.math.abs(delta) <= 5)
                }
    }

    /** 无效视口/配置立即停止，极短视口仍保留合法边缘。 */
    @Test
    fun invalidViewportAndTinyHeight() {
        for (state in listOf(TokenAutoScrollState(0f, 100f), TokenAutoScrollState(50f, 0f))) {
            assertEquals(0, state.calculateDelta(0f, 0f, 200f, 16L, true, true))
            assertFalse(state.isActive)
        }
        val state = TokenAutoScrollState(50f, 100f)
        assertEquals(0, state.calculateDelta(0f, 20f, 10f, 16L, true, true))
        assertEquals(0, state.calculateDelta(0f, 0f, 0f, 16L, true, true))
        assertEquals(1, state.calculateDelta(2f, 0f, 2f, 16L, true, true))
        assertEquals(1, state.direction)
    }
}
