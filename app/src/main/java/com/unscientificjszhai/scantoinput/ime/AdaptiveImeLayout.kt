package com.unscientificjszhai.scantoinput.ime

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.unscientificjszhai.scantoinput.R

/**
 * 按当前窗口高度缩小相机预览，给宿主编辑器保留空间。
 * @param context 布局使用的主题上下文。
 * @param attrs XML 属性。
 */
class AdaptiveImeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    private lateinit var preview: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var navigationSpacer: View
    private var preferredPreviewHeight = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        preview = findViewById(R.id.preview_view)
        topBar = findViewById(R.id.top_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        navigationSpacer = findViewById(R.id.navigation_bar_spacer)
        preferredPreviewHeight = preview.layoutParams.height
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val windowHeight = (resources.configuration.screenHeightDp * resources.displayMetrics.density).toInt()
        // 输入法最多使用窗口高度的六成；横屏和分屏时优先保留上下操作栏。
        val windowLimit = (windowHeight * 0.6f).toInt()
        val heightLimit = if (mode == MeasureSpec.UNSPECIFIED) windowLimit else
            minOf(windowLimit, MeasureSpec.getSize(heightMeasureSpec))
        val fixedHeight = topBar.layoutParams.height + bottomBar.layoutParams.height +
            navigationSpacer.layoutParams.height.coerceAtLeast(0) + paddingTop + paddingBottom
        val previewHeight = minOf(preferredPreviewHeight, (heightLimit - fixedHeight).coerceAtLeast(0))
        if (preview.layoutParams.height != previewHeight) {
            preview.layoutParams.height = previewHeight
            // 通知 ConstraintLayout 重建子控件约束，不能只修改已经缓存的布局参数。
            preview.forceLayout()
        }
        val boundedSpec = if (mode == MeasureSpec.EXACTLY) heightMeasureSpec else
            MeasureSpec.makeMeasureSpec(heightLimit, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, boundedSpec)
    }
}
