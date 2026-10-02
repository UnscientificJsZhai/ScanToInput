package com.unscientificjszhai.scantoinput.ime

/**
 * 结合视图创建、附着、窗口可见和当前编辑器的 view-start 握手控制相机。
 * @property onShouldStart 新会话需要开始相机绑定时的回调。
 * @property onShouldStop 当前会话失效后的停止回调。
 */
class ImeCameraVisibilityController(
    private val onShouldStart: (Int) -> Unit,
    private val onShouldStop: (Int) -> Unit
) {
    private var viewCreated = false
    private var viewAttached = false
    private var windowVisible = false
    private var inputViewStarted = false
    private var sequence = 0
    private var activeSession: Int? = null
    private var retrySequence = 0L
    private var pendingRetry: Long? = null

    /**
     * 判断当前编辑器的输入视图是否真正显示。
     * @return 四个显示条件是否全部成立。
     */
    fun isTrulyVisible(): Boolean = viewCreated && viewAttached && windowVisible && inputViewStarted

    /**
     * 在最终提交之前核对结果所属的可见会话。
     * @param sessionId 扫码回调捕获的会话编号。
     * @return 当前是否仍可接受该会话的结果。
     */
    fun accepts(sessionId: Int): Boolean = isTrulyVisible() && activeSession == sessionId

    /** 创建新视图时等待该视图自己的附着事件，禁止复用旧视图状态。 */
    fun onInputViewCreated() {
        viewCreated = true
        viewAttached = false
        updateState()
    }

    /** 销毁输入视图并取消其会话与重试。 */
    fun onInputViewDestroyed() {
        viewCreated = false
        viewAttached = false
        inputViewStarted = false
        updateState()
    }

    /** 记录当前视图已经附着。 */
    fun onViewAttached() {
        viewAttached = true
        updateState()
    }

    /** 脱离窗口立即失效相机会话。 */
    fun onViewDetached() {
        viewAttached = false
        updateState()
    }

    /**
     * 更新输入法窗口是否可见。
     * @param visible 窗口是否显示。
     */
    fun onWindowVisibilityChanged(visible: Boolean) {
        windowVisible = visible
        updateState()
    }

    /**
     * 焦点变化不代表输入法窗口隐藏；只重新核对完整显示条件。
     * @param focused 当前窗口焦点，由系统通知提供。
     */
    @Suppress("UNUSED_PARAMETER")
    fun onWindowFocusChanged(focused: Boolean) {
        updateState()
    }

    /** 切换或重启编辑器时立即停止旧扫描，等待对应的 onStartInputView。 */
    fun onStartInput() {
        inputViewStarted = false
        updateState()
    }

    /** 当前编辑器完成 view-start 握手；同一次展示中的重复通知幂等。 */
    fun onStartInputView() {
        inputViewStarted = true
        updateState()
    }

    /** 输入视图停止展示，立即关闭该会话。 */
    fun onFinishInputView() {
        inputViewStarted = false
        updateState()
    }

    /** 活跃操作只核对显示条件，不能越过重试等待或编辑器握手。 */
    fun notifyActive() {
        updateState()
    }

    /**
     * 立即停止旧扫描并登记等待重试，不在延迟期间接收结果。
     * @return 可见时返回重试身份，否则返回 null。
     */
    fun beginRetry(): Long? {
        if (!isTrulyVisible()) return null
        val token = ++retrySequence
        pendingRetry = token
        stopSession()
        return token
    }

    /**
     * 仅完成仍属于当前可见输入视图的重试。
     * @param token 延迟任务捕获的重试身份。
     * @return 成功启动的新会话编号，过期时为 null。
     */
    fun completeRetry(token: Long): Int? {
        if (pendingRetry != token) return null
        pendingRetry = null
        updateState()
        return activeSession
    }

    /**
     * 立即重新启动可见输入视图的扫描，用于无需延迟的调用者。
     * @return 新会话编号，不可见时为 null。
     */
    fun restartActiveSession(): Int? {
        val token = beginRetry() ?: return null
        return completeRetry(token)
    }

    /** 根据完整状态只在真正发生启停变化时通知平台适配层。 */
    private fun updateState() {
        if (!isTrulyVisible()) {
            pendingRetry = null
            stopSession()
        } else if (pendingRetry == null && activeSession == null) {
            val session = ++sequence
            activeSession = session
            onShouldStart(session)
        }
    }

    /** 先失效旧身份再调用外部停止函数，允许其安全重入。 */
    private fun stopSession() {
        val old = activeSession ?: return
        activeSession = null
        onShouldStop(old)
    }
}
