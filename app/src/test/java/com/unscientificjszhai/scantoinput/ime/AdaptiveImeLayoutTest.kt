package com.unscientificjszhai.scantoinput.ime

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.unscientificjszhai.scantoinput.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** 通过实际 XML 测量验证横屏、分屏及窗口恢复后的输入法操作区域。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37], application = Application::class, qualifiers = "w400dp-h1000dp-port-mdpi")
class AdaptiveImeLayoutTest {
    /** 正常竖屏保持预览高度，窄高度分屏压缩预览并保留全部底部按键。 */
    @Test
    fun smallWindowShrinksPreviewAndRestoresWhenExpanded() {
        val root = inflate()
        val preview = root.findViewById<View>(R.id.preview_view)
        val original = preview.layoutParams.height
        measure(root, 400, 1_000)
        assertEquals(original, preview.measuredHeight)
        val spacer = root.findViewById<View>(R.id.navigation_bar_spacer)
        spacer.layoutParams.height = 24
        measure(root, 400, 230)
        assertTrue(preview.measuredHeight < original)
        assertControlsVisible(root)
        assertTrue(root.measuredHeight <= 230)
        measure(root, 400, 1_000)
        assertEquals(original, preview.measuredHeight)
        assertControlsVisible(root)
    }

    /** 同宽窗口反复缩小、展开时，预览约束不能沿用旧高度。 */
    @Test
    fun repeatedHeightChangesRefreshConstraints() {
        val root = inflate()
        val preview = root.findViewById<View>(R.id.preview_view)
        val original = preview.layoutParams.height
        measure(root, 400, 1_000)
        assertControlsVisible(root)
        for (height in listOf(230, 190, 350, 1_000)) {
            root.forceLayout()
            measure(root, 400, height)
            assertControlsVisible(root)
            assertTrue(root.measuredHeight <= height)
        }
        assertEquals(original, preview.measuredHeight)
    }

    /** 大屏横向窗口中，底部按键和未使用导航栏占位保持正确位置。 */
    @Test
    @Config(qualifiers = "sw600dp-w960dp-h600dp-land-mdpi")
    fun largeLandscapeWindowKeepsBottomControlsInsideBounds() {
        val root = inflate()
        measure(root, 960, 600)
        assertControlsVisible(root)
        assertTrue(root.measuredHeight <= 360)
        root.forceLayout()
        root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 960, 280)
        assertEquals(280, root.measuredHeight)
        assertControlsVisible(root)
    }

    /** 横屏禁止全屏提取模式，并给真实编辑器保留窗口空间。 */
    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeKeepsControlsVisibleWithoutFullscreenExtraction() {
        val service = ScanInputMethodService()
        ReflectionHelpers.setField(service, "mBase", RuntimeEnvironment.getApplication())
        assertFalse(service.onEvaluateFullscreenMode())
        val root = inflate()
        measure(root, 800, 360)
        assertTrue(root.measuredHeight <= (root.resources.configuration.screenHeightDp *
            root.resources.displayMetrics.density * 0.6f).toInt())
        assertControlsVisible(root)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
        root.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.UNSPECIFIED))
        assertTrue(root.measuredHeight < 360)
    }

    /**
     * 使用真实 IME 主题及系统容器参数创建布局。
     * @return 尚未附着的生产根布局。
     */
    private fun inflate(): AdaptiveImeLayout {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_ScanToInput_IME)
        return LayoutInflater.from(context).inflate(R.layout.input_method, FrameLayout(context), false) as AdaptiveImeLayout
    }

    /**
     * 按受约束窗口的真实测量规则安装布局。
     * @param root 输入法根布局。
     * @param width 窗口宽度，单位像素。
     * @param height 可用高度，单位像素。
     */
    private fun measure(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    /**
     * 确认输入键和导航栏占位都未被窗口裁剪。
     * @param root 已测量的输入法布局。
     */
    private fun assertControlsVisible(root: View) {
        val bar = root.findViewById<View>(R.id.bottom_bar)
        val spacer = root.findViewById<View>(R.id.navigation_bar_spacer)
        assertTrue("root=${root.measuredHeight}, bar=${bar.top}..${bar.bottom}, preview=" +
            root.findViewById<View>(R.id.preview_view).measuredHeight + ", heightDp=" +
            root.resources.configuration.screenHeightDp, bar.top >= 0)
        assertTrue(bar.bottom <= root.measuredHeight)
        assertEquals(bar.bottom, spacer.top)
        assertEquals(root.measuredHeight, spacer.bottom)
        for (id in listOf(R.id.space_button, R.id.enter_button)) {
            val button = root.findViewById<View>(id)
            assertTrue(button.height > 0)
            assertTrue(button.bottom <= bar.height)
        }
    }
}
