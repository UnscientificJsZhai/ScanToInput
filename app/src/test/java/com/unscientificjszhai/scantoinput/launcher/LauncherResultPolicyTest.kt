package com.unscientificjszhai.scantoinput.launcher

import com.unscientificjszhai.scantoinput.actions.QuickAction
import com.unscientificjszhai.scantoinput.text.TextProcessingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通过公开事件和渲染快照验证锁定、等待、复制与非文本处理规则。 */
class LauncherResultPolicyTest {

    /** 保存与恢复必须保留选择锁定、非文本提示、当前操作和最新待处理结果。 */
    @Test
    fun restoreLockedStateKeepsPendingResultAndHint() {
        val original = LauncherResultPolicy()
        val current = success("https://example.com", QuickAction.Url("https://example.com"))
        val pending = success("pending")
        original.onProcessedResult(current)
        original.onSelectionChanged(true)
        original.onProcessedResult(pending)
        original.onProcessedResult(TextProcessingResult.NonText)

        val restored = LauncherResultPolicy()
        val update = restored.restoreState(original.saveState())
        assertEquals(original.currentState(), update.renderState)
        assertTrue(update.resultChanged)
        assertNull(update.scheduleUnlockDelayMillis)
        assertFalse(update.cancelUnlock)
        assertEquals(current.tokens.joinToString(""), restored.resolveCopyText(false, ""))
        assertEquals(pending, restored.onUnlockTimeout().renderState.currentResult)
        assertFalse(restored.currentState().nonTextHintVisible)
    }

    /** 重建期间不得消费等待结果或重新安排完整的解锁窗口。 */
    @Test
    fun restoreWaitingStateDoesNotRestartUnlock() {
        val original = LauncherResultPolicy()
        original.onProcessedResult(success("old"))
        original.onSelectionChanged(true)
        original.onProcessedResult(success("new"))
        original.onSelectionChanged(false)
        val saved = original.saveState()
        assertTrue(saved.unlockScheduled)

        val restored = LauncherResultPolicy()
        restored.restoreState(saved)
        assertNull(restored.onSelectionChanged(false).scheduleUnlockDelayMillis)
        assertEquals("new", restored.onUnlockTimeout().renderState.currentResult?.tokens?.joinToString(""))
    }

    /** 空页面和未锁定的普通结果均可完整恢复。 */
    @Test
    fun restoreEmptyAndUnlockedStates() {
        val original = LauncherResultPolicy()
        val restored = LauncherResultPolicy()
        restored.onProcessedResult(success("discarded"))
        restored.restoreState(original.saveState())
        assertEquals(original.currentState(), restored.currentState())
        assertFalse(restored.saveState().unlockScheduled)
        original.onProcessedResult(success("visible"))
        restored.restoreState(original.saveState())
        assertEquals(original.currentState(), restored.currentState())
    }

    /** 相同全文的不同分词结果保留当前显示结果。 */
    @Test
    fun sameContentDoesNotRefreshCurrentResult() {
        val policy = LauncherResultPolicy()
        val first = success("hello")
        val second = TextProcessingResult.Success(listOf("he", "llo"))

        assertTrue(policy.onProcessedResult(first).resultChanged)
        val update = policy.onProcessedResult(second)

        assertFalse(update.resultChanged)
        assertSame(first, update.renderState.currentResult)
    }

    /** 未锁定时立即显示不同内容及其快速操作。 */
    @Test
    fun differentContentAppliesImmediatelyWhenUnlocked() {
        val policy = LauncherResultPolicy()
        val action = QuickAction.Url("https://example.com")
        val result = success("https://example.com", action)

        val update = policy.onProcessedResult(result)

        assertTrue(update.resultChanged)
        assertSame(result, update.renderState.currentResult)
        assertFalse(update.renderState.hasPendingResult)
        assertSame(action, update.renderState.quickAction)
        assertTrue(update.renderState.copyEnabled)
    }

    /** 锁定期间保留当前内容，并缓存最新不同结果。 */
    @Test
    fun differentContentBecomesPendingWhenLocked() {
        val policy = LauncherResultPolicy()
        val firstAction = QuickAction.Url("https://old.example")
        val first = success("https://old.example", firstAction)
        val second = success("new")

        policy.onProcessedResult(first)
        policy.onSelectionChanged(hasSelection = true)
        val update = policy.onProcessedResult(second)

        assertFalse(update.resultChanged)
        assertSame(first, update.renderState.currentResult)
        assertSame(firstAction, update.renderState.quickAction)
        assertTrue(update.renderState.hasPendingResult)
    }

    /** 取消选择后沿用产品规定的默认解锁等待时长。 */
    @Test
    fun clearingSelectionSchedulesUnlockDelay() {
        val policy = LauncherResultPolicy()

        policy.onSelectionChanged(hasSelection = true)
        val update = policy.onSelectionChanged(hasSelection = false)

        assertEquals(
            LauncherResultPolicy.DEFAULT_UNLOCK_DELAY_MILLIS,
            update.scheduleUnlockDelayMillis
        )
    }

    /** 再次选择取消已安排的解锁，保留当前和待处理结果。 */
    @Test
    fun newSelectionCancelsScheduledUnlock() {
        val policy = LauncherResultPolicy()
        val first = success("old")
        val second = success("new")

        policy.onProcessedResult(first)
        policy.onSelectionChanged(hasSelection = true)
        policy.onProcessedResult(second)
        policy.onSelectionChanged(hasSelection = false)
        val update = policy.onSelectionChanged(hasSelection = true)

        assertTrue(update.cancelUnlock)
        assertTrue(update.renderState.isLocked)
        assertSame(first, update.renderState.currentResult)
        assertTrue(update.renderState.hasPendingResult)
    }

    /** 超时只应用一次最新待处理结果。 */
    @Test
    fun unlockTimeoutAppliesLatestPendingResultOnce() {
        val policy = LauncherResultPolicy()
        val first = success("old")
        val second = success("new")
        val third = success("latest")

        policy.onProcessedResult(first)
        policy.onSelectionChanged(hasSelection = true)
        policy.onProcessedResult(second)
        policy.onProcessedResult(third)
        policy.onSelectionChanged(hasSelection = false)

        val update = policy.onUnlockTimeout()
        val secondTimeoutUpdate = policy.onUnlockTimeout()

        assertTrue(update.resultChanged)
        assertSame(third, update.renderState.currentResult)
        assertFalse(update.renderState.hasPendingResult)
        assertFalse(secondTimeoutUpdate.resultChanged)
        assertSame(third, secondTimeoutUpdate.renderState.currentResult)
    }

    /** 复制始终使用已选文本或当前结果，不提前使用待处理内容。 */
    @Test
    fun copyTextNeverUsesPendingResult() {
        val policy = LauncherResultPolicy()

        policy.onProcessedResult(success(listOf("old", " ", "text")))
        policy.onSelectionChanged(hasSelection = true)
        policy.onProcessedResult(success("pending"))

        assertEquals("old text", policy.resolveCopyText(hasSelection = false, selectedText = ""))
        assertEquals("old", policy.resolveCopyText(hasSelection = true, selectedText = "old"))
    }

    /** 非文本在未锁定时清空结果，在锁定时只显示提示。 */
    @Test
    fun nonTextClearsOnlyWhenUnlocked() {
        val unlockedPolicy = LauncherResultPolicy()
        unlockedPolicy.onProcessedResult(success("text"))

        val unlockedUpdate = unlockedPolicy.onProcessedResult(TextProcessingResult.NonText)

        assertTrue(unlockedUpdate.resultChanged)
        assertNull(unlockedUpdate.renderState.currentResult)
        assertFalse(unlockedUpdate.renderState.copyEnabled)
        assertTrue(unlockedUpdate.renderState.nonTextHintVisible)

        val lockedPolicy = LauncherResultPolicy()
        val current = success("current")
        lockedPolicy.onProcessedResult(current)
        lockedPolicy.onSelectionChanged(hasSelection = true)
        lockedPolicy.onProcessedResult(success("pending"))

        val lockedUpdate = lockedPolicy.onProcessedResult(TextProcessingResult.NonText)

        assertFalse(lockedUpdate.resultChanged)
        assertSame(current, lockedUpdate.renderState.currentResult)
        assertTrue(lockedUpdate.renderState.copyEnabled)
        assertTrue(lockedUpdate.renderState.nonTextHintVisible)
        assertTrue(lockedUpdate.renderState.hasPendingResult)
    }

    /** 重复读取初始或锁定快照不改变状态，也不消费等待超时的结果。 */
    @Test
    fun currentStateReadsDoNotConsumePendingResult() {
        val policy = LauncherResultPolicy()
        val empty = LauncherResultRenderState(null, null, false, false, false, false)
        repeat(3) { assertEquals(empty, policy.currentState()) }

        val action = QuickAction.Url("https://old.example")
        val current = success("old", action)
        val pending = success("pending")
        policy.onProcessedResult(current)
        policy.onSelectionChanged(hasSelection = true)
        policy.onProcessedResult(pending)
        policy.onSelectionChanged(hasSelection = false)
        val locked = LauncherResultRenderState(current, action, true, false, true, true)
        repeat(3) { assertEquals(locked, policy.currentState()) }

        val update = policy.onUnlockTimeout()
        assertTrue(update.resultChanged)
        assertEquals(pending, update.renderState.currentResult)
        assertFalse(update.renderState.isLocked)
        assertFalse(update.renderState.hasPendingResult)
    }

    /** 未锁定的空选择不调度，等待期间重复通知也不重设自定义延迟。 */
    @Test
    fun repeatedEmptySelectionDoesNotRescheduleUnlock() {
        val policy = LauncherResultPolicy(unlockDelayMillis = 1234L)
        val emptyState = policy.currentState()
        val initial = policy.onSelectionChanged(hasSelection = false)
        assertEquals(emptyState, initial.renderState)
        assertNull(initial.scheduleUnlockDelayMillis)
        assertFalse(initial.cancelUnlock)
        assertFalse(initial.resultChanged)

        val current = success("current")
        val pending = success("pending")
        policy.onProcessedResult(current)
        policy.onSelectionChanged(hasSelection = true)
        policy.onProcessedResult(pending)
        val first = policy.onSelectionChanged(hasSelection = false)
        assertEquals(1234L, first.scheduleUnlockDelayMillis)
        assertFalse(first.cancelUnlock)
        val repeated = policy.onSelectionChanged(hasSelection = false)
        assertEquals(first.renderState, repeated.renderState)
        assertEquals(current, repeated.renderState.currentResult)
        assertTrue(repeated.renderState.hasPendingResult)
        assertNull(repeated.scheduleUnlockDelayMillis)
        assertFalse(repeated.cancelUnlock)
        assertFalse(repeated.resultChanged)

        assertEquals(pending, policy.onUnlockTimeout().renderState.currentResult)
        assertFalse(policy.onUnlockTimeout().resultChanged)
    }

    /** 空选中文本回退当前全文，忽略无选择时的参数及尚未显示的待处理结果。 */
    @Test
    fun emptySelectedTextFallsBackToCurrentResult() {
        val policy = LauncherResultPolicy()
        policy.onProcessedResult(success(listOf("old", " ", "text")))
        policy.onSelectionChanged(hasSelection = true)
        policy.onProcessedResult(success("pending"))

        assertEquals("old text", policy.resolveCopyText(hasSelection = true, selectedText = ""))
        assertEquals("old text", policy.resolveCopyText(hasSelection = false, selectedText = "不应使用"))
        assertEquals("old", policy.resolveCopyText(hasSelection = true, selectedText = "old"))
    }

    /** 从未有结果或被非文本清空时，两种空选择输入均无法生成复制文本。 */
    @Test
    fun copyWithoutCurrentResultRemainsEmpty() {
        val policy = LauncherResultPolicy()
        assertEquals("", policy.resolveCopyText(hasSelection = false, selectedText = ""))
        assertEquals("", policy.resolveCopyText(hasSelection = true, selectedText = ""))
        policy.onProcessedResult(success("old"))
        policy.onProcessedResult(TextProcessingResult.NonText)
        assertNull(policy.currentState().currentResult)
        assertEquals("", policy.resolveCopyText(hasSelection = false, selectedText = ""))
        assertEquals("", policy.resolveCopyText(hasSelection = true, selectedText = ""))
    }

    /** 空状态连续收到非文本只显示提示，不报告结果替换或安排解锁。 */
    @Test
    fun nonTextWithoutCurrentResultDoesNotReportChanges() {
        val policy = LauncherResultPolicy()
        val expected = LauncherResultRenderState(null, null, false, true, false, false)
        repeat(2) {
            val update = policy.onProcessedResult(TextProcessingResult.NonText)
            assertEquals(expected, update.renderState)
            assertFalse(update.resultChanged)
            assertNull(update.scheduleUnlockDelayMillis)
            assertFalse(update.cancelUnlock)
        }
    }

    /**
     * 创建无快速操作的文本处理成功结果。
     *
     * @param text 完整文本。
     * @return 文本处理成功结果。
     */
    private fun success(text: String): TextProcessingResult.Success {
        return success(listOf(text))
    }

    /**
     * 创建指定快速操作的文本处理成功结果。
     *
     * @param text 完整文本。
     * @param quickAction 快速操作。
     * @return 文本处理成功结果。
     */
    private fun success(text: String, quickAction: QuickAction): TextProcessingResult.Success {
        return TextProcessingResult.Success(listOf(text), quickAction)
    }

    /**
     * 创建无快速操作的文本处理成功结果。
     *
     * @param tokens token 列表。
     * @return 文本处理成功结果。
     */
    private fun success(tokens: List<String>): TextProcessingResult.Success {
        return TextProcessingResult.Success(tokens)
    }
}
