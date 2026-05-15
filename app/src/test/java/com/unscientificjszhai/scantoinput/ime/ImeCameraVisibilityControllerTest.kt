package com.unscientificjszhai.scantoinput.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 输入法相机可见性控制器测试。
 */
class ImeCameraVisibilityControllerTest {

    /**
     * 验证可见状态下的重试会生成新的输入会话并请求启动相机。
     */
    @Test
    fun restartActiveSession_whenVisible_startsNewSession() {
        val startedSessions = mutableListOf<Int>()
        val controller = ImeCameraVisibilityController(
            onShouldStart = { sessionId -> startedSessions.add(sessionId) },
            onShouldStop = {}
        )

        controller.onInputViewCreated()
        controller.onViewAttached()
        controller.onWindowVisibilityChanged(true)
        controller.onStartInputView()

        val restartedSessionId = controller.restartActiveSession()

        assertEquals(2, restartedSessionId)
        assertEquals(listOf(1, 2), startedSessions)
    }

    /**
     * 验证不可见状态下的重试不会启动相机。
     */
    @Test
    fun restartActiveSession_whenNotVisible_doesNotStartCamera() {
        val startedSessions = mutableListOf<Int>()
        val controller = ImeCameraVisibilityController(
            onShouldStart = { sessionId -> startedSessions.add(sessionId) },
            onShouldStop = {}
        )

        controller.onInputViewCreated()
        controller.onViewAttached()
        controller.onStartInputView()

        val restartedSessionId = controller.restartActiveSession()

        assertNull(restartedSessionId)
        assertEquals(emptyList<Int>(), startedSessions)
    }
}
