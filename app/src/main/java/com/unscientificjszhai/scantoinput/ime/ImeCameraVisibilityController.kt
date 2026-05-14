package com.unscientificjszhai.scantoinput.ime

import android.os.Handler
import android.os.Looper

/**
 * 输入法相机可见性控制器。
 *
 * 结合输入视图生命周期、窗口可见性和焦点状态，判断是否应该开启或保持相机。
 */
class ImeCameraVisibilityController(
    private val onShouldStart: (Int) -> Unit,
    private val onShouldStop: (Int) -> Unit
) {
    companion object {
        /**
         * 相机空闲释放超时时间（毫秒）。
         */
        private const val IDLE_RELEASE_TIMEOUT_MS = 10_000L
    }

    private var isViewCreated = false
    private var isViewAttached = false
    private var isWindowVisible = false
    private var isInputStarted = false
    private var isFocused = false

    private var currentSessionId = 0
    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        // 超时释放相机，虽然窗口可能仍可见，但视为失焦或非活跃
        onShouldStop(currentSessionId)
    }

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
        isFocused = focused
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
            if (isFocused) {
                stopIdleTimer()
            } else {
                startIdleTimer()
            }
            onShouldStart(currentSessionId)
        }
    }

    private fun updateState() {
        if (isTrulyVisible() && isFocused) {
            stopIdleTimer()
            onShouldStart(currentSessionId)
        } else if (isTrulyVisible() && !isFocused) {
            // 可见但无焦点，保持当前状态并启动倒计时
            startIdleTimer()
        } else {
            // 不可见，立即停止
            stopIdleTimer()
            onShouldStop(currentSessionId)
        }
    }

    private fun startIdleTimer() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, IDLE_RELEASE_TIMEOUT_MS)
    }

    private fun stopIdleTimer() {
        handler.removeCallbacks(idleRunnable)
    }
}
