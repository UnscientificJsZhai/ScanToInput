package com.unscientificjszhai.scantoinput.launcher

import android.content.Context
import android.view.View
import android.view.animation.Animation
import android.widget.Button
import com.unscientificjszhai.scantoinput.R
import com.unscientificjszhai.scantoinput.actions.QuickAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** 用真实 Android 按钮及受控动画回调验证动画所有权。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37])
class QuickActionButtonControllerTest {
    /** 初始空态不播放动画，也不重复修改按钮。 */
    @Test
    fun initialEmptyTargetIsStable() {
        val button = RecordingButton(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        val controller = QuickActionButtonController(button) { true }
        val writes = button.visibilityWrites
        controller.render(null)
        controller.render(null)
        assertEquals(View.GONE, button.visibility)
        assertNull(button.animation)
        assertEquals(0, button.starts)
        assertEquals(writes, button.visibilityWrites)
    }

    /** 相同可见目标只更新文案，重复空目标保留原退出动画的完成机会。 */
    @Test
    fun repeatedTargetsKeepCurrentAnimationAndUpdateLabel() {
        val button = RecordingButton(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        val controller = QuickActionButtonController(button) { true }
        controller.render(QuickAction.Url("https://a.example"))
        val enter = button.animation
        assertEquals(200L, enter.duration)
        controller.render(QuickAction.Email("mailto:b@example.com", "b@example.com"))
        assertSame(enter, button.animation)
        assertEquals(1, button.starts)
        assertEquals(button.context.getString(R.string.action_send_email), button.text.toString())
        listener(enter).onAnimationStart(enter)
        listener(enter).onAnimationRepeat(enter)
        listener(enter).onAnimationEnd(enter)
        assertEquals(View.VISIBLE, button.visibility)
        controller.render(null)
        val exit = button.animation
        assertEquals(200L, exit.duration)
        controller.render(null)
        controller.render(null)
        assertSame(exit, button.animation)
        assertEquals(2, button.starts)
        listener(exit).onAnimationEnd(exit)
        assertEquals(View.GONE, button.visibility)
        val writes = button.visibilityWrites
        listener(exit).onAnimationEnd(exit)
        assertEquals(writes, button.visibilityWrites)
    }

    /** 旧退出回调在新有效操作及后来另一轮退出中都不能提交可见性。 */
    @Test
    fun replacedExitCannotAffectEitherLaterTarget() {
        val button = RecordingButton(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        val controller = QuickActionButtonController(button) { true }
        controller.render(QuickAction.Url("https://a.example"))
        controller.render(null)
        val oldExit = button.animation
        val oldEnd = listener(oldExit)
        controller.render(QuickAction.Email("mailto:b@example.com", "b@example.com"))
        assertEquals(View.VISIBLE, button.visibility)
        assertNull(button.animation)
        val visibleWrites = button.visibilityWrites
        oldEnd.onAnimationEnd(oldExit)
        assertEquals(visibleWrites, button.visibilityWrites)
        assertEquals(View.VISIBLE, button.visibility)
        controller.render(null)
        val currentExit = button.animation
        val exitWrites = button.visibilityWrites
        oldEnd.onAnimationEnd(oldExit)
        assertSame(currentExit, button.animation)
        assertEquals(exitWrites, button.visibilityWrites)
        assertEquals(View.VISIBLE, button.visibility)
        listener(currentExit).onAnimationEnd(currentExit)
        assertEquals(View.GONE, button.visibility)
    }

    /** 清理同目标退出动画时，即便同步触发旧回调也必须先失效版本。 */
    @Test
    fun clearingAnimationInvalidatesBeforeSynchronousEndCallback() {
        val button = RecordingButton(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        var enabled = true
        val controller = QuickActionButtonController(button) { enabled }
        controller.render(QuickAction.Url("https://a.example"))
        controller.render(null)
        val oldExit = button.animation
        val oldEnd = listener(oldExit)
        val beforeClear = button.visibilityWrites
        button.onClear = {
            oldEnd.onAnimationEnd(oldExit)
            assertEquals(beforeClear, button.visibilityWrites)
            assertEquals(View.VISIBLE, button.visibility)
        }
        enabled = false
        controller.render(null)
        assertEquals(View.GONE, button.visibility)
        assertNull(button.animation)
    }

    /** 动画关闭时直接到达目标，开关中途变化会取消当前动画。 */
    @Test
    fun disabledAnimationsApplyTargetsSynchronously() {
        val button = RecordingButton(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        var enabled = false
        val controller = QuickActionButtonController(button) { enabled }
        controller.render(null)
        controller.render(QuickAction.Url("https://a.example"))
        assertEquals(View.VISIBLE, button.visibility)
        assertNull(button.animation)
        assertEquals(0, button.starts)
        controller.render(QuickAction.Email("mailto:b@example.com", "b@example.com"))
        assertEquals(button.context.getString(R.string.action_send_email), button.text.toString())
        controller.render(null)
        assertEquals(View.GONE, button.visibility)
        enabled = true
        controller.render(QuickAction.Url("https://c.example"))
        val enter = button.animation
        val end = listener(enter)
        enabled = false
        controller.render(QuickAction.Url("https://c.example"))
        assertNull(button.animation)
        assertEquals(View.VISIBLE, button.visibility)
        val writes = button.visibilityWrites
        end.onAnimationEnd(enter)
        assertEquals(writes, button.visibilityWrites)
    }

    /** 销毁先失效身份，重复销毁及后来 render、迟到回调均不能更新按钮。 */
    @Test
    fun disposeRejectsLateCallbacksAndFutureRendering() {
        val button = RecordingButton(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        val controller = QuickActionButtonController(button) { true }
        controller.render(QuickAction.Url("https://a.example"))
        controller.render(null)
        val oldExit = button.animation
        val oldEnd = listener(oldExit)
        val writes = button.visibilityWrites
        button.onClear = {
            oldEnd.onAnimationEnd(oldExit)
            assertEquals(writes, button.visibilityWrites)
        }
        controller.dispose()
        val clears = button.clears
        assertNull(button.animation)
        controller.dispose()
        controller.render(QuickAction.Email("mailto:b@example.com", "b@example.com"))
        oldEnd.onAnimationEnd(oldExit)
        assertEquals(clears, button.clears)
        assertEquals(writes, button.visibilityWrites)
        assertEquals(button.context.getString(R.string.action_open_url), button.text.toString())
    }

    /**
     * 捕获真实动画上由生产控制器安装的回调，模拟平台迟到交付。
     * @param animation 生产动画对象。
     * @return 原始完成监听器。
     */
    private fun listener(animation: Animation): Animation.AnimationListener =
        ReflectionHelpers.getField(animation, "mListener")

    /**
     * 记录真实按钮操作，允许在清理边界同步交付捕获的回调。
     * @param context Android 测试上下文。
     */
    private class RecordingButton(context: Context) : Button(context) {
        var starts = 0
        var clears = 0
        var visibilityWrites = 0
        var onClear: (() -> Unit)? = null

        override fun startAnimation(animation: Animation?) {
            starts++
            super.startAnimation(animation)
        }

        override fun clearAnimation() {
            clears++
            val callback = onClear
            onClear = null
            callback?.invoke()
            super.clearAnimation()
        }

        override fun setVisibility(visibility: Int) {
            visibilityWrites++
            super.setVisibility(visibility)
        }
    }
}
