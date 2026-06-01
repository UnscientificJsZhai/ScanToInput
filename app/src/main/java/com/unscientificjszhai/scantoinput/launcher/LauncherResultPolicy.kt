package com.unscientificjszhai.scantoinput.launcher

import com.unscientificjszhai.scantoinput.actions.QuickAction
import com.unscientificjszhai.scantoinput.text.TextProcessingResult

/**
 * 启动器扫码结果更新策略。
 *
 * @param unlockDelayMillis 取消选择后等待解锁的毫秒数。
 */
class LauncherResultPolicy(private val unlockDelayMillis: Long = DEFAULT_UNLOCK_DELAY_MILLIS) {

    private var currentResult: TextProcessingResult.Success? = null
    private var pendingResult: TextProcessingResult.Success? = null
    private var isLocked: Boolean = false
    private var unlockScheduled: Boolean = false
    private var nonTextHintVisible: Boolean = false

    /**
     * 返回当前渲染状态，不修改策略内部状态。
     *
     * @return 当前完整渲染状态。
     */
    fun currentState(): LauncherResultRenderState {
        return renderState()
    }

    /**
     * 接收新的文本处理结果并计算 UI 更新指令。
     *
     * @param result 文本处理结果。
     * @return 应用于启动器页面的更新指令。
     */
    fun onProcessedResult(result: TextProcessingResult): LauncherResultUpdate {
        return when (result) {
            TextProcessingResult.NonText -> onNonTextResult()
            is TextProcessingResult.Success -> onSuccessResult(result)
        }
    }

    /**
     * 接收 token 选择状态变化并计算锁定或解锁等待指令。
     *
     * @param hasSelection 当前是否存在已选择 token。
     * @return 应用于启动器页面的更新指令。
     */
    fun onSelectionChanged(hasSelection: Boolean): LauncherResultUpdate {
        if (hasSelection) {
            isLocked = true
            unlockScheduled = false
            return update(cancelUnlock = true)
        }

        if (isLocked && !unlockScheduled) {
            unlockScheduled = true
            return update(scheduleUnlockDelayMillis = unlockDelayMillis)
        }

        return update()
    }

    /**
     * 处理解锁等待窗口超时。
     *
     * @return 应用于启动器页面的更新指令。
     */
    fun onUnlockTimeout(): LauncherResultUpdate {
        isLocked = false
        unlockScheduled = false

        val result = pendingResult
        return if (result != null) {
            currentResult = result
            pendingResult = null
            nonTextHintVisible = false
            update(resultChanged = true)
        } else {
            update()
        }
    }

    /**
     * 解析复制按钮应复制的文本。
     *
     * @param hasSelection 当前是否存在已选择 token。
     * @param selectedText 按原文顺序拼接的已选择文本。
     * @return 应复制的文本；没有可复制内容时返回空字符串。
     */
    fun resolveCopyText(hasSelection: Boolean, selectedText: String): String {
        return if (hasSelection && selectedText.isNotEmpty()) {
            selectedText
        } else {
            currentResult?.tokens?.joinToString(separator = "").orEmpty()
        }
    }

    /**
     * 处理可显示文本结果。
     *
     * @param result 可显示文本结果。
     * @return 应用于启动器页面的更新指令。
     */
    private fun onSuccessResult(result: TextProcessingResult.Success): LauncherResultUpdate {
        nonTextHintVisible = false

        if (contentKey(result) == currentResult?.let(::contentKey)) {
            return update()
        }

        return if (isLocked) {
            pendingResult = result
            update()
        } else {
            currentResult = result
            pendingResult = null
            update(resultChanged = true)
        }
    }

    /**
     * 处理不可显示文本结果。
     *
     * @return 应用于启动器页面的更新指令。
     */
    private fun onNonTextResult(): LauncherResultUpdate {
        nonTextHintVisible = true

        if (isLocked) {
            return update()
        }

        val hadCurrentResult = currentResult != null
        currentResult = null
        pendingResult = null
        return update(resultChanged = hadCurrentResult)
    }

    /**
     * 计算可显示文本结果的内容键。
     *
     * @param result 可显示文本结果。
     * @return 由 token 无分隔拼接得到的内容键。
     */
    private fun contentKey(result: TextProcessingResult.Success): String {
        return result.tokens.joinToString(separator = "")
    }

    /**
     * 创建当前状态对应的更新指令。
     *
     * @param resultChanged 当前显示结果是否发生变化。
     * @param scheduleUnlockDelayMillis 需要启动解锁等待窗口时的延迟毫秒数。
     * @param cancelUnlock 是否需要取消已有解锁等待任务。
     * @return 应用于启动器页面的更新指令。
     */
    private fun update(
        resultChanged: Boolean = false,
        scheduleUnlockDelayMillis: Long? = null,
        cancelUnlock: Boolean = false
    ): LauncherResultUpdate {
        return LauncherResultUpdate(
            renderState = renderState(),
            resultChanged = resultChanged,
            scheduleUnlockDelayMillis = scheduleUnlockDelayMillis,
            cancelUnlock = cancelUnlock
        )
    }

    /**
     * 创建当前状态对应的渲染快照。
     *
     * @return 当前完整渲染状态。
     */
    private fun renderState(): LauncherResultRenderState {
        return LauncherResultRenderState(
            currentResult = currentResult,
            quickAction = currentResult?.quickAction,
            copyEnabled = currentResult != null,
            nonTextHintVisible = nonTextHintVisible,
            isLocked = isLocked,
            hasPendingResult = pendingResult != null
        )
    }

    companion object {
        /**
         * 默认取消选择后的解锁等待窗口。
         */
        const val DEFAULT_UNLOCK_DELAY_MILLIS: Long = 2_000L
    }
}

/**
 * 启动器结果区域渲染状态。
 *
 * @property currentResult 当前显示的可处理文本结果。
 * @property quickAction 当前显示结果关联的快速操作。
 * @property copyEnabled 复制按钮是否可用。
 * @property nonTextHintVisible 非文本提示是否可见。
 * @property isLocked 当前结果区域是否因 token 选择而锁定。
 * @property hasPendingResult 是否存在等待解锁后应用的结果。
 */
data class LauncherResultRenderState(
    val currentResult: TextProcessingResult.Success?,
    val quickAction: QuickAction?,
    val copyEnabled: Boolean,
    val nonTextHintVisible: Boolean,
    val isLocked: Boolean,
    val hasPendingResult: Boolean
)

/**
 * 启动器结果策略对 Android 适配层发出的更新指令。
 *
 * @property renderState 当前渲染状态。
 * @property resultChanged 当前显示结果是否发生变化。
 * @property scheduleUnlockDelayMillis 需要启动解锁等待窗口时的延迟毫秒数。
 * @property cancelUnlock 是否需要取消已有解锁等待任务。
 */
data class LauncherResultUpdate(
    val renderState: LauncherResultRenderState,
    val resultChanged: Boolean,
    val scheduleUnlockDelayMillis: Long?,
    val cancelUnlock: Boolean
)
