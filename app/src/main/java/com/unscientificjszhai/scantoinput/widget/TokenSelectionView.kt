package com.unscientificjszhai.scantoinput.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.unscientificjszhai.scantoinput.R
import java.util.*
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.toColorInt

/**
 * 高性能 Token 选择控件。
 *
 * 该控件支持以流式布局展示一系列文本 Token，并允许用户通过点击或滑动来选择特定的 Token。
 * 控件针对性能进行了优化，通过在测量阶段缓存布局信息来减少绘制阶段的对象分配和重复计算。
 *
 * @property tokenTextSize Token 文字大小。
 * @property tokenTextColor Token 未选中时的文字颜色。
 * @property tokenSelectedTextColor Token 选中时的文字颜色。
 * @property tokenBackgroundColor Token 未选中时的背景颜色。
 * @property tokenSelectedBackgroundColor Token 选中时的背景颜色。
 * @property tokenPaddingHorizontal Token 内部水平内边距。
 * @property tokenPaddingVertical Token 内部垂直内边距。
 * @property tokenCornerRadius Token 背景的圆角半径。
 * @property tokenSpacingHorizontal Token 之间的水平间距。
 * @property tokenSpacingVertical Token 之间的垂直间距。
 */
class TokenSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val engine = TokenSelectionEngine()

    // 样式属性
    private var tokenTextSize = spToPx(14f)
    private var tokenTextColor = Color.BLACK
    private var tokenSelectedTextColor = Color.BLACK
    private var tokenBackgroundColor = "#E0E0E0".toColorInt()
    private var tokenSelectedBackgroundColor = "#80CBC4".toColorInt()
    private var tokenPaddingHorizontal = dpToPx(8f)
    private var tokenPaddingVertical = dpToPx(4f)
    private var tokenCornerRadius = dpToPx(4f)
    private var tokenSpacingHorizontal = dpToPx(4f)
    private var tokenSpacingVertical = dpToPx(4f)
    private val placeholderText = runCatching {
        context.getString(R.string.token_selection_placeholder)
    }.getOrDefault("扫描二维码以读取文本")

    // 绘制资源
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rectF = RectF()
    private val drawingRect = android.graphics.Rect()

    // 布局缓存
    private var lastMeasuredWidth = -1

    // 交互状态
    private var lastTouchedIndex = -1

    var onSelectionChangedListener: (() -> Unit)? = null

    init {
        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.TokenSelectionView,
            0, 0
        ).apply {
            try {
                tokenTextSize =
                    getDimension(R.styleable.TokenSelectionView_tokenTextSize, tokenTextSize)
                tokenTextColor =
                    getColor(R.styleable.TokenSelectionView_tokenTextColor, tokenTextColor)
                tokenSelectedTextColor = getColor(
                    R.styleable.TokenSelectionView_tokenSelectedTextColor,
                    tokenSelectedTextColor
                )
                tokenBackgroundColor = getColor(
                    R.styleable.TokenSelectionView_tokenBackgroundColor,
                    tokenBackgroundColor
                )
                tokenSelectedBackgroundColor = getColor(
                    R.styleable.TokenSelectionView_tokenSelectedBackgroundColor,
                    tokenSelectedBackgroundColor
                )
                tokenPaddingHorizontal = getDimension(
                    R.styleable.TokenSelectionView_tokenPaddingHorizontal,
                    tokenPaddingHorizontal
                )
                tokenPaddingVertical = getDimension(
                    R.styleable.TokenSelectionView_tokenPaddingVertical,
                    tokenPaddingVertical
                )
                tokenCornerRadius = getDimension(
                    R.styleable.TokenSelectionView_tokenCornerRadius,
                    tokenCornerRadius
                )
                tokenSpacingHorizontal = getDimension(
                    R.styleable.TokenSelectionView_tokenSpacingHorizontal,
                    tokenSpacingHorizontal
                )
                tokenSpacingVertical = getDimension(
                    R.styleable.TokenSelectionView_tokenSpacingVertical,
                    tokenSpacingVertical
                )
            } finally {
                recycle()
            }
        }
        textPaint.textSize = tokenTextSize
    }

    /**
     * 设置显示的 token 列表。
     * 调用此方法会清空当前的选择状态并触发重新布局。
     *
     * @param tokens 要显示的 token 列表。
     */
    fun setTokens(tokens: List<String>) {
        engine.setTokens(tokens)
        lastMeasuredWidth = -1 // 强制重新计算布局
        requestLayout()
        invalidate()
        onSelectionChangedListener?.invoke()
    }

    /**
     * 获取当前选中的文本。
     * 选中的文本将按照其在原始列表中的顺序进行拼接。
     *
     * @return 拼接后的选中文本，如果没有选中则返回空字符串。
     */
    fun getSelectedText(): String {
        return engine.getSelectedText()
    }

    /**
     * 获取所有 Token 组成的全文。
     *
     * @return 拼接后的全文。
     */
    fun getFullText(): String = engine.tokens.joinToString("")

    /**
     * 判断当前是否有任何 Token 被选中。
     *
     * @return 如果有至少一个选中则返回 true，否则返回 false。
     */
    fun hasSelection(): Boolean = engine.hasSelection()

    /**
     * 清空当前所有的选择状态。
     */
    fun clearSelection() {
        if (engine.clearSelection()) {
            invalidate()
            onSelectionChangedListener?.invoke()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)

        if (widthMode == MeasureSpec.UNSPECIFIED) {
            setMeasuredDimension(0, 0)
            return
        }

        if (engine.tokens.isEmpty()) {
            lastMeasuredWidth = width
            setMeasuredDimension(width, resolveSize(placeholderHeight(), heightMeasureSpec))
            return
        }

        if (width != lastMeasuredWidth) {
            calculateLayout(width)
            lastMeasuredWidth = width
        }

        val totalHeight = (engine.totalHeight + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(width, resolveSize(totalHeight, heightMeasureSpec))
    }

    /**
     * 计算所有 Token 的布局位置。
     * 此方法在测量阶段调用，委托给 Engine 模块缓存结果。
     *
     * @param width 控件的总宽度。
     */
    private fun calculateLayout(width: Int) {
        val availableWidth = width - paddingLeft - paddingRight
        val fontMetrics = textPaint.fontMetrics
        engine.calculateLayout(
            availableWidth = availableWidth.toFloat(),
            paddingLeft = paddingLeft.toFloat(),
            paddingTop = paddingTop.toFloat(),
            tokenSpacingHorizontal = tokenSpacingHorizontal,
            tokenSpacingVertical = tokenSpacingVertical,
            tokenPaddingHorizontal = tokenPaddingHorizontal,
            tokenPaddingVertical = tokenPaddingVertical,
            fontMetricsTop = fontMetrics.top,
            fontMetricsBottom = fontMetrics.bottom,
            measureTextWidth = { textPaint.measureText(it) }
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (engine.tokens.isEmpty()) {
            drawPlaceholder(canvas)
            return
        }

        if (engine.layoutInfos.size != engine.tokens.size) return

        // 视口可见性分析优化绘制
        getDrawingRect(drawingRect)
        val visibleRange = engine.getVisibleTokenIndices(
            drawingRect.top.toFloat(),
            drawingRect.bottom.toFloat()
        )

        if (visibleRange.isEmpty()) return

        for (i in visibleRange) {
            val info = engine.layoutInfos[i]
            val isSelected = engine.isSelected(i)

            // 绘制背景
            backgroundPaint.color =
                if (isSelected) tokenSelectedBackgroundColor else tokenBackgroundColor
            rectF.set(info.x, info.y, info.x + info.width, info.y + info.height)
            canvas.drawRoundRect(rectF, tokenCornerRadius, tokenCornerRadius, backgroundPaint)

            // 绘制文字
            textPaint.color = if (isSelected) tokenSelectedTextColor else tokenTextColor
            canvas.drawText(
                engine.tokens[i],
                info.x + tokenPaddingHorizontal,
                info.y + info.baseline,
                textPaint
            )
        }
    }

    /**
     * 计算占位文案展示所需的最小高度。
     *
     * @return 包含上下内边距的占位区域高度。
     */
    private fun placeholderHeight(): Int {
        val fontMetrics = textPaint.fontMetrics
        return (fontMetrics.bottom - fontMetrics.top + paddingTop + paddingBottom + tokenPaddingVertical * 2).toInt()
    }

    /**
     * 绘制空列表状态下的占位文案。
     *
     * @param canvas 用于绘制占位文案的画布。
     */
    private fun drawPlaceholder(canvas: Canvas) {
        val fontMetrics = textPaint.fontMetrics
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom

        // 计算水平居中位置
        val textWidth = textPaint.measureText(placeholderText)
        val x = paddingLeft + (contentWidth - textWidth) / 2f

        // 计算垂直居中位置（基准线）
        val baseline =
            paddingTop + (contentHeight - fontMetrics.bottom + fontMetrics.top) / 2f - fontMetrics.top

        textPaint.color = tokenTextColor
        canvas.drawText(placeholderText, x, baseline, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val index = engine.findTokenAt(x, y)
                if (index != -1) {
                    startDragSelection(index)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (engine.isDragging) {
                    val index = engine.findTokenAt(x, y)
                    if (index != -1 && index != lastTouchedIndex) {
                        lastTouchedIndex = index
                        applyDragRange(index)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (engine.isDragging) {
                    endDragSelection()
                    performClick()
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (engine.isDragging) {
                    endDragSelection()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 设置父容器是否允许拦截当前触摸手势。
     *
     * @param disallowIntercept true 表示禁止父容器拦截，false 表示恢复默认拦截行为。
     */
    private fun setParentDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    /**
     * 开始一次连续选择手势。
     *
     * @param index 手势起点命中的 Token 索引。
     */
    private fun startDragSelection(index: Int) {
        setParentDisallowInterceptTouchEvent(true)
        engine.startDragSelection(index)
        lastTouchedIndex = index
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
        onSelectionChangedListener?.invoke()
    }

    /**
     * 结束当前连续选择手势并清理临时状态。
     */
    private fun endDragSelection() {
        engine.endDragSelection()
        lastTouchedIndex = -1
        setParentDisallowInterceptTouchEvent(false)
    }

    /**
     * 将选择状态更新为起点到当前 Token 的连续区间。
     *
     * @param currentIndex 当前命中的 Token 索引。
     */
    private fun applyDragRange(currentIndex: Int) {
        if (engine.applyDragRange(currentIndex)) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
            onSelectionChangedListener?.invoke()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
    }

    @Suppress("SameParameterValue")
    private fun spToPx(sp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    }
}
