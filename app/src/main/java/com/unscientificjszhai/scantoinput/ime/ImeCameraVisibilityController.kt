package com.unscientificjszhai.scantoinput.ime

/**
 * 输入法相机可见性控制器。
 *
 * 结合输入视图生命周期、窗口可见性和焦点状态，判断是否应该开启或保持相机。
 */
class ImeCameraVisibilityController(
    private val onShouldStart: (Int) -> Unit,
    private val onShouldStop: (Int) -> Unit
) {
    private var isViewCreated = false
    private var isViewAttached = false
    private var isWindowVisible = false
    private var isInputStarted = false

    private var currentSessionId = 0

    /**
     * 判断输入法界面是否真正可见。
     *
     * @return 如果真正可见则返回 true。
     */
    fun isTrulyVisible(): Boolean {
        return isViewCreated && isViewAttached && isWindowVisible && isInputStarted
    }

    /**
     * 当输入视图创建时调用。
     */
    fun onInputViewCreated() {
        isViewCreated = true
        updateState()
    }

    /**
     * 当输入视图销毁时调用。
     */
    fun onInputViewDestroyed() {
        isViewCreated = false
        updateState()
    }

    /**
     * 当视图附着到窗口时调用。
     */
    fun onViewAttached() {
        isViewAttached = true
        updateState()
    }

    /**
     * 当视图从窗口脱离时调用。
     */
    fun onViewDetached() {
        isViewAttached = false
        updateState()
    }

    /**
     * 当窗口可见性改变时调用。
     *
     * @param visible 是否可见。
     */
    fun onWindowVisibilityChanged(visible: Boolean) {
        isWindowVisible = visible
        updateState()
    }

    /**
     * 当窗口焦点状态改变时调用。
     *
     * @param focused 是否获得焦点。
     */
    fun onWindowFocusChanged(focused: Boolean) {
        // 焦点状态目前不再直接控制相机的启停，但保留接口以便后续扩展
        updateState()
    }

    /**
     * 当输入视图开始展示时调用。
     */
    fun onStartInputView() {
        isInputStarted = true
        currentSessionId++
        updateState()
    }

    /**
     * 当输入视图结束展示时调用。
     */
    fun onFinishInputView() {
        isInputStarted = false
        updateState()
    }

    /**
     * 在发生任何活跃操作时调用。
     */
    fun notifyActive() {
        if (isTrulyVisible()) {
            onShouldStart(currentSessionId)
        }
    }

    private fun updateState() {
        if (isTrulyVisible()) {
            onShouldStart(currentSessionId)
        } else {
            onShouldStop(currentSessionId)
        }
    }
}
