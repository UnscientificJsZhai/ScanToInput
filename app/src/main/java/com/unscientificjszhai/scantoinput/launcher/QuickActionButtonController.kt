package com.unscientificjszhai.scantoinput.launcher

import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import com.unscientificjszhai.scantoinput.actions.QuickAction

/**
 * 协调快速操作按钮的可见目标与 Android 动画，拒绝被替换或销毁后的回调。
 * @param button 本控制器独占动画和可见性更新的按钮。
 * @param animationsEnabled 当前系统是否启用动画。
 */
class QuickActionButtonController(
    private val button: Button,
    private val animationsEnabled: () -> Boolean
) {
    private var targetVisible = button.visibility == View.VISIBLE
    private var animationGeneration = 0L
    private var activeAnimation: Animation? = null
    private var disposed = false

    /**
     * 展示当前快速操作；相同目标保留进行中的动画，只更新有效操作文案。
     * @param action 当前快速操作；null 表示按钮应隐藏。
     */
    fun render(action: QuickAction?) {
        if (disposed) return
        action?.let { button.setText(it.labelResId) }
        val visible = action != null
        val visibility = if (visible) View.VISIBLE else View.GONE
        if (!animationsEnabled()) {
            if (targetVisible != visible || activeAnimation != null || button.visibility != visibility) {
                targetVisible = visible
                cancelAnimation()
                button.visibility = visibility
            }
            return
        }
        if (targetVisible == visible) return

        val wasVisible = button.visibility == View.VISIBLE
        targetVisible = visible
        cancelAnimation()
        if (visible) {
            button.visibility = View.VISIBLE
            if (!wasVisible) startTransition(visible = true)
        } else if (wasVisible) {
            startTransition(visible = false)
        } else {
            button.visibility = View.GONE
        }
    }

    /** 终止本控制器并取消动画，先失效身份再清理可能同步回调的平台对象。 */
    fun dispose() {
        if (disposed) return
        disposed = true
        targetVisible = false
        cancelAnimation()
    }

    /** 清除当前动画；版本与本地引用先失效，防止 clearAnimation 中的旧回调提交状态。 */
    private fun cancelAnimation() {
        animationGeneration++
        activeAnimation = null
        button.clearAnimation()
    }

    /**
     * 启动原有 200 ms 淡入或淡出动画，并在完成时重验版本、目标和对象身份。
     * @param visible 动画结束时按钮是否可见。
     */
    private fun startTransition(visible: Boolean) {
        val generation = animationGeneration
        val transition = if (visible) AlphaAnimation(0f, 1f) else AlphaAnimation(1f, 0f)
        transition.duration = ANIMATION_DURATION_MILLIS
        transition.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) = Unit
            override fun onAnimationRepeat(animation: Animation?) = Unit
            override fun onAnimationEnd(animation: Animation?) {
                if (disposed || generation != animationGeneration || targetVisible != visible || activeAnimation !== transition) return
                activeAnimation = null
                button.visibility = if (visible) View.VISIBLE else View.GONE
            }
        })
        activeAnimation = transition
        button.startAnimation(transition)
    }

    /** 保持页面原有的动画时长。 */
    private companion object {
        const val ANIMATION_DURATION_MILLIS = 200L
    }
}
