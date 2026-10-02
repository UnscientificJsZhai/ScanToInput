package com.unscientificjszhai.scantoinput.ime

import org.junit.Assert.*
import org.junit.Test

/** 通过公开生命周期事件验证显示握手、幂等启动和重试身份。 */
class ImeCameraVisibilityControllerTest {
    /** 四个条件齐备才启动，重复事件和焦点变化不替换已绑定会话。 */
    @Test
    fun allVisibilityConditionsAndIdempotence() {
        val fixture = Fixture()
        val controller = fixture.controller
        assertFalse(controller.isTrulyVisible())
        assertFalse(controller.accepts(1))
        controller.onInputViewCreated()
        assertFalse(controller.isTrulyVisible())
        controller.onViewAttached()
        assertFalse(controller.isTrulyVisible())
        controller.onWindowVisibilityChanged(true)
        assertFalse(controller.isTrulyVisible())
        controller.onStartInputView()
        assertTrue(controller.accepts(1))
        assertFalse(controller.accepts(2))
        controller.onStartInputView()
        controller.onWindowFocusChanged(false)
        controller.onWindowFocusChanged(true)
        controller.notifyActive()
        controller.onWindowVisibilityChanged(true)
        assertEquals(listOf(1), fixture.starts)
        controller.onViewDetached()
        assertEquals(listOf(1), fixture.stops)
        assertFalse(controller.accepts(1))
        controller.onViewAttached()
        assertTrue(controller.accepts(2))
        controller.onWindowVisibilityChanged(false)
        controller.onWindowVisibilityChanged(false)
        assertEquals(listOf(1, 2), fixture.stops)
        controller.onWindowVisibilityChanged(true)
        controller.onInputViewCreated()
        assertFalse(controller.isTrulyVisible())
        controller.onViewAttached()
        controller.onInputViewDestroyed()
        controller.onViewAttached()
        controller.onWindowVisibilityChanged(true)
        assertFalse(controller.isTrulyVisible())
    }

    /** 编辑器重启立即停止旧扫描，必须等待本编辑器的 view-start，重复通知幂等。 */
    @Test
    fun restartingEditorRequiresFreshViewStartHandshake() {
        val fixture = Fixture()
        fixture.show()
        fixture.controller.onStartInput()
        fixture.controller.notifyActive()
        fixture.controller.onWindowFocusChanged(true)
        fixture.controller.onWindowVisibilityChanged(true)
        fixture.controller.onViewAttached()
        assertEquals(listOf(1), fixture.starts)
        assertEquals(listOf(1), fixture.stops)
        assertFalse(fixture.controller.accepts(1))
        fixture.controller.onStartInputView()
        fixture.controller.onStartInputView()
        assertEquals(listOf(1, 2), fixture.starts)
        fixture.controller.onFinishInputView()
        fixture.controller.onStartInputView()
        assertEquals(listOf(1, 2, 3), fixture.starts)
    }

    /** 重试等待期间任何活跃通知都不能重新打开；旧重试不能完成新重试。 */
    @Test
    fun retrySuspendsAcceptanceUntilMatchingCompletion() {
        val fixture = Fixture()
        assertNull(fixture.controller.beginRetry())
        assertNull(fixture.controller.restartActiveSession())
        assertNull(fixture.controller.completeRetry(123))
        fixture.show()
        val first = fixture.controller.beginRetry()!!
        assertFalse(fixture.controller.accepts(1))
        fixture.controller.notifyActive()
        fixture.controller.onStartInputView()
        assertEquals(listOf(1), fixture.starts)
        val second = fixture.controller.beginRetry()!!
        assertNull(fixture.controller.completeRetry(first))
        assertEquals(2, fixture.controller.completeRetry(second))
        assertNull(fixture.controller.completeRetry(second))
        assertTrue(fixture.controller.accepts(2))
        assertEquals(3, fixture.controller.restartActiveSession())
    }

    /** 每一种生命周期结束都会取消已登记重试。 */
    @Test
    fun hiddenDetachedFinishedAndDestroyedRetriesStayCancelled() {
        val hideActions: List<(ImeCameraVisibilityController) -> Unit> = listOf(
            { it.onWindowVisibilityChanged(false) }, { it.onViewDetached() },
            { it.onFinishInputView() }, { it.onStartInput() }, { it.onInputViewDestroyed() }
        )
        for (hide in hideActions) {
            val fixture = Fixture()
            fixture.show()
            val token = fixture.controller.beginRetry()!!
            hide(fixture.controller)
            assertNull(fixture.controller.completeRetry(token))
            assertEquals(listOf(1), fixture.starts)
        }
    }

    /** 停止回调重入时旧会话已经失效，不能再接受它的结果。 */
    @Test
    fun callbacksObserveAlreadyCommittedState() {
        lateinit var controller: ImeCameraVisibilityController
        controller = ImeCameraVisibilityController(
            { id -> assertTrue(controller.accepts(id)) },
            { id -> assertFalse(controller.accepts(id)) }
        )
        controller.onInputViewCreated()
        controller.onViewAttached()
        controller.onStartInputView()
        controller.onWindowVisibilityChanged(true)
        controller.onWindowVisibilityChanged(false)
    }

    /** 收集实际回调的测试夹具。 */
    private class Fixture {
        val starts = mutableListOf<Int>()
        val stops = mutableListOf<Int>()
        val controller = ImeCameraVisibilityController({ starts.add(it) }, { stops.add(it) })
        /** 通过四个公开事件完整显示输入视图。 */
        fun show() {
            controller.onInputViewCreated()
            controller.onViewAttached()
            controller.onWindowVisibilityChanged(true)
            controller.onStartInputView()
        }
    }
}
