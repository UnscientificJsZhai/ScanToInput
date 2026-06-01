package com.unscientificjszhai.scantoinput.launcher

import com.unscientificjszhai.scantoinput.actions.QuickAction
import com.unscientificjszhai.scantoinput.text.TextProcessingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherResultPolicyTest {

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

    @Test
    fun copyTextNeverUsesPendingResult() {
        val policy = LauncherResultPolicy()

        policy.onProcessedResult(success(listOf("old", " ", "text")))
        policy.onSelectionChanged(hasSelection = true)
        policy.onProcessedResult(success("pending"))

        assertEquals("old text", policy.resolveCopyText(hasSelection = false, selectedText = ""))
        assertEquals("old", policy.resolveCopyText(hasSelection = true, selectedText = "old"))
    }

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
