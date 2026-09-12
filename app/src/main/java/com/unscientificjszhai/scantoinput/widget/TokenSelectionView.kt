package com.unscientificjszhai.scantoinput.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import com.unscientificjszhai.scantoinput.R
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 缓存多行文字、协调方向手势与可访问节点的选词控件。
 * @param context 当前主题上下文。
 * @param attrs XML 样式属性。
 * @param defStyleAttr 默认样式属性。
 */
class TokenSelectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val engine = TokenSelectionEngine()
    private var tokenTextSize = spToPx(14f)
    private var tokenTextColor = Color.BLACK
    private var tokenSelectedTextColor = Color.BLACK
    private var tokenBackgroundColor = 0xFFE0E0E0.toInt()
    private var tokenSelectedBackgroundColor = 0xFF80CBC4.toInt()
    private var tokenPaddingHorizontal = dpToPx(8f)
    private var tokenPaddingVertical = dpToPx(4f)
    private var tokenCornerRadius = dpToPx(4f)
    private var tokenSpacingHorizontal = dpToPx(4f)
    private var tokenSpacingVertical = dpToPx(4f)
    internal val placeholderText: String = context.getString(R.string.token_selection_placeholder)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2f)
        }
    private val backgroundRect = RectF()
    private val drawClip = Rect()
    private val viewport = Rect()
    private val accessibilityViewport = Rect()
    private val requestedRect = Rect()
    private val drawRange = TokenIndexRange()
    private val screenOrigin = IntArray(2)
    private var textLayouts: Array<StaticLayout?> = emptyArray()
    private var placeholderLayout: StaticLayout? = null
    private var horizontalInset = 0f
    private var lastWidth = -1
    private var lastPaddingLeft = -1
    private var lastPaddingTop = -1
    private var lastPaddingRight = -1
    private var lastPaddingBottom = -1
    private var lastTextSize = -1f
    private var lastTypeface: Typeface? = null
    private var lastDirection = -1
    private val gesture =
        TokenGestureState(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private val autoScroll = TokenAutoScrollState(dpToPx(48f), dpToPx(720f))
    private var scrollParent: ScrollView? = null
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var downToken = -1
    private var pointerRawX = 0f
    private var pointerRawY = 0f
    private var framePosted = false
    private var previousFrameTime = 0L
    private val frameCallback = object : Runnable {
        override fun run() {
            runScrollFrame()
        }
    }
    private val accessibility = TokenSelectionAccessibilityHelper(this)
    private val ancestorScrollListener =
        android.view.ViewTreeObserver.OnScrollChangedListener { accessibility.invalidateRoot() }
    private val measurer = TokenTextMeasurer { index, text, maximumWidth ->
        val display = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u2028', '\n')
            .replace('\u2029', '\n')
        val contentWidth = maxOf(1, floor(maximumWidth - horizontalInset * 2f).toInt())
        val naturalWidth = Layout.getDesiredWidth(display, textPaint)
        val wholeLine = display.indexOf('\n') >= 0 || naturalWidth > contentWidth
        val layoutWidth =
            if (wholeLine) contentWidth else ceil(naturalWidth).toInt().coerceIn(1, contentWidth)
        val layout = buildTextLayout(display, layoutWidth, Layout.Alignment.ALIGN_NORMAL)
        textLayouts[index] = layout
        TokenTextMetrics(
            minOf(maximumWidth, layoutWidth + horizontalInset * 2f),
            layout.height + tokenPaddingVertical * 2f,
            layout.getLineBaseline(0) + tokenPaddingVertical,
            wholeLine
        )
    }

    /** 仅在选择实际变化后通知既有业务层。 */
    var onSelectionChangedListener: (() -> Unit)? = null

    init {
        val values =
            context.obtainStyledAttributes(attrs, R.styleable.TokenSelectionView, defStyleAttr, 0)
        try {
            tokenTextSize =
                values.getDimension(R.styleable.TokenSelectionView_tokenTextSize, tokenTextSize)
            tokenTextColor =
                values.getColor(R.styleable.TokenSelectionView_tokenTextColor, tokenTextColor)
            tokenSelectedTextColor = values.getColor(
                R.styleable.TokenSelectionView_tokenSelectedTextColor,
                tokenSelectedTextColor
            )
            tokenBackgroundColor = values.getColor(
                R.styleable.TokenSelectionView_tokenBackgroundColor,
                tokenBackgroundColor
            )
            tokenSelectedBackgroundColor = values.getColor(
                R.styleable.TokenSelectionView_tokenSelectedBackgroundColor,
                tokenSelectedBackgroundColor
            )
            tokenPaddingHorizontal = values.getDimension(
                R.styleable.TokenSelectionView_tokenPaddingHorizontal,
                tokenPaddingHorizontal
            )
            tokenPaddingVertical = values.getDimension(
                R.styleable.TokenSelectionView_tokenPaddingVertical,
                tokenPaddingVertical
            )
            tokenCornerRadius = values.getDimension(
                R.styleable.TokenSelectionView_tokenCornerRadius,
                tokenCornerRadius
            )
            tokenSpacingHorizontal = values.getDimension(
                R.styleable.TokenSelectionView_tokenSpacingHorizontal,
                tokenSpacingHorizontal
            )
            tokenSpacingVertical = values.getDimension(
                R.styleable.TokenSelectionView_tokenSpacingVertical,
                tokenSpacingVertical
            )
        } finally {
            values.recycle()
        }
        textPaint.textSize = tokenTextSize
        focusPaint.color = tokenSelectedTextColor
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
    }

    /**
     * 替换逻辑 token，并废弃旧手势与旧虚拟节点身份。
     * @param tokens 原始 token 列表。
     */
    fun setTokens(tokens: List<String>) {
        finishInteraction()
        val changed = engine.hasSelection()
        accessibility.clearTokens()
        engine.setTokens(tokens)
        accessibility.replaceTokens()
        lastWidth = -1
        requestLayout()
        invalidate()
        if (changed) onSelectionChangedListener?.invoke()
    }

    /** @return 按原始顺序拼接的选择文本。 */
    fun getSelectedText(): String = engine.getSelectedText()

    /** @return 未改变字符和逻辑 token 顺序的全文。 */
    fun getFullText(): String = engine.getFullText()

    /** @return 是否有选择。 */
    fun hasSelection(): Boolean = engine.hasSelection()

    /** 清空选择，同时清理未完成手势及帧。 */
    fun clearSelection() {
        finishInteraction()
        if (engine.clearSelection()) notifySelectionChanged()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val targetWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            maxOf(
                suggestedMinimumWidth,
                ceil(
                    Layout.getDesiredWidth(
                        placeholderText,
                        textPaint
                    )
                ).toInt() + paddingLeft + paddingRight
            )
        else MeasureSpec.getSize(widthMeasureSpec)
        if (targetWidth != lastWidth || paddingLeft != lastPaddingLeft || paddingTop != lastPaddingTop ||
            paddingRight != lastPaddingRight || paddingBottom != lastPaddingBottom || textPaint.textSize != lastTextSize ||
            textPaint.typeface != lastTypeface || layoutDirection != lastDirection
        ) {
            finishInteraction()
            textLayouts = arrayOfNulls(engine.tokens.size)
            val availableWidth = targetWidth - paddingLeft - paddingRight
            horizontalInset = minOf(tokenPaddingHorizontal, maxOf(0f, (availableWidth - 1f) / 2f))
            engine.calculateLayout(
                availableWidth.toFloat(), paddingLeft.toFloat(), paddingTop.toFloat(),
                tokenSpacingHorizontal, tokenSpacingVertical, measurer
            )
            placeholderLayout = if (availableWidth > 0) buildTextLayout(
                placeholderText,
                availableWidth,
                Layout.Alignment.ALIGN_CENTER
            ) else null
            lastWidth = targetWidth
            lastPaddingLeft = paddingLeft
            lastPaddingTop = paddingTop
            lastPaddingRight = paddingRight
            lastPaddingBottom = paddingBottom
            lastTextSize = textPaint.textSize
            lastTypeface = textPaint.typeface
            lastDirection = layoutDirection
            accessibility.invalidateRoot()
        }
        val desiredHeight = if (engine.tokens.isEmpty())
            paddingTop + paddingBottom + (placeholderLayout?.height
                ?: 0) + tokenPaddingVertical * 2f
        else engine.totalHeight + paddingBottom
        setMeasuredDimension(
            resolveSize(targetWidth, widthMeasureSpec),
            resolveSize(ceil(desiredHeight).toInt(), heightMeasureSpec)
        )
    }

    /**
     * 在冷路径构造平台文字布局。
     * @param text 用于显示的文本。
     * @param width 合法的正整数文字宽度。
     * @param alignment 行对齐方式。
     * @return 保留字体边界、字素和双向文字规则的布局。
     */
    private fun buildTextLayout(
        text: String,
        width: Int,
        alignment: Layout.Alignment
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(alignment).setIncludePad(true)
            .setTextDirection(if (layoutDirection == LAYOUT_DIRECTION_RTL) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()

    override fun onDraw(canvas: Canvas) {
        if (!canvas.getClipBounds(drawClip)) return
        if (engine.tokens.isEmpty()) {
            val layout = placeholderLayout ?: return
            textPaint.color = tokenTextColor
            val saved = canvas.save()
            canvas.translate(
                paddingLeft.toFloat(),
                paddingTop + maxOf(0f, (height - paddingTop - paddingBottom - layout.height) / 2f)
            )
            layout.draw(canvas)
            canvas.restoreToCount(saved)
            return
        }
        engine.fillVisibleTokenRange(
            maxOf(drawClip.top, paddingTop).toFloat(),
            minOf(drawClip.bottom, height - paddingBottom).toFloat(),
            drawRange
        )
        var i = drawRange.firstIndex
        if (i < 0) return
        while (i <= drawRange.lastIndex) {
            val info = engine.layoutInfos[i]
            val layout = cachedTextLayout(i)
            if (layout != null && info.y + info.height > drawClip.top && info.y < drawClip.bottom &&
                info.x + info.width > drawClip.left && info.x < drawClip.right
            ) {
                val selected = engine.isSelected(i)
                backgroundPaint.color =
                    if (selected) tokenSelectedBackgroundColor else tokenBackgroundColor
                backgroundRect.set(info.x, info.y, info.x + info.width, info.y + info.height)
                canvas.drawRoundRect(
                    backgroundRect,
                    tokenCornerRadius,
                    tokenCornerRadius,
                    backgroundPaint
                )
                if (accessibility.isTokenFocused(i)) canvas.drawRoundRect(
                    backgroundRect,
                    tokenCornerRadius,
                    tokenCornerRadius,
                    focusPaint
                )
                textPaint.color = if (selected) tokenSelectedTextColor else tokenTextColor
                val saved = canvas.save()
                canvas.translate(info.x + horizontalInset, info.y + tokenPaddingVertical)
                canvas.clipRect(0, 0, layout.width, layout.height)
                layout.draw(canvas)
                canvas.restoreToCount(saved)
            }
            i++
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            finishInteraction()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishInteraction()
                downToken = engine.findTokenAt(event.x, event.y)
                if (downToken < 0) return false
                activePointer = event.getPointerId(0)
                rememberPointer(event, 0)
                gesture.begin(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointer = event.findPointerIndex(activePointer)
                if (pointer < 0) {
                    finishInteraction()
                    return false
                }
                rememberPointer(event, pointer)
                if (gesture.move(
                        event.getX(pointer),
                        event.getY(pointer)
                    ) == TokenGestureState.HORIZONTAL
                ) {
                    var changed = false
                    if (!engine.isDragging) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        changed = engine.startDragSelection(downToken)
                    }
                    val index = engine.findTokenAt(event.getX(pointer), event.getY(pointer))
                    if (engine.applyDragRange(index)) changed = true
                    refreshAutoScroll()
                    if (changed) notifySelectionChanged()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val pointer = event.findPointerIndex(activePointer)
                if (pointer < 0) {
                    finishInteraction()
                    return false
                }
                val index = engine.findTokenAt(event.getX(pointer), event.getY(pointer))
                if (gesture.move(
                        event.getX(pointer),
                        event.getY(pointer)
                    ) == TokenGestureState.PENDING && index == downToken
                ) return performTokenClick(index)
                val changed = engine.applyDragRange(index)
                finishInteraction()
                if (changed) notifySelectionChanged()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                finishInteraction()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointer) finishInteraction()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                finishInteraction()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 保存活动指针屏幕坐标，滚动后不沿用过期内容坐标。
     * @param event 当前触摸事件。
     * @param index 活动指针在事件中的位置。
     */
    private fun rememberPointer(event: MotionEvent, index: Int) {
        pointerRawX = event.getRawX(index)
        pointerRawY = event.getRawY(index)
    }

    /** 检查边缘并保证仅挂起一个已有帧回调。 */
    private fun refreshAutoScroll() {
        val scroll = scrollParent
        if (scroll == null || !engine.isDragging || !getLocalVisibleRect(viewport)) {
            stopAutoScroll()
            return
        }
        getLocationOnScreen(screenOrigin)
        autoScroll.calculateDelta(
            pointerRawY - screenOrigin[1], viewport.top.toFloat(), viewport.bottom.toFloat(), 0L,
            scroll.canScrollVertically(-1), scroll.canScrollVertically(1)
        )
        if (!autoScroll.isActive) {
            stopAutoScroll()
            return
        }
        if (!framePosted) {
            previousFrameTime = android.os.SystemClock.uptimeMillis()
            postScrollFrame()
        }
    }

    /** 执行一帧滚动及坐标重算，通知重入后不再启动旧任务。 */
    private fun runScrollFrame() {
        framePosted = false
        val scroll = scrollParent
        if (scroll == null || !engine.isDragging || activePointer == MotionEvent.INVALID_POINTER_ID || !getLocalVisibleRect(
                viewport
            )
        ) {
            stopAutoScroll()
            return
        }
        getLocationOnScreen(screenOrigin)
        val now = android.os.SystemClock.uptimeMillis()
        val delta = autoScroll.calculateDelta(
            pointerRawY - screenOrigin[1], viewport.top.toFloat(), viewport.bottom.toFloat(),
            now - previousFrameTime, scroll.canScrollVertically(-1), scroll.canScrollVertically(1)
        )
        previousFrameTime = now
        if (!autoScroll.isActive) return
        if (delta != 0) {
            val before = scroll.scrollY
            scroll.scrollBy(0, delta)
            if (scroll.scrollY == before) {
                stopAutoScroll()
                return
            }
        }
        getLocationOnScreen(screenOrigin)
        if (!getLocalVisibleRect(viewport)) {
            stopAutoScroll()
            return
        }
        val y = (pointerRawY - screenOrigin[1]).coerceIn(
            viewport.top.toFloat(),
            maxOf(viewport.top.toFloat(), viewport.bottom - 0.01f)
        )
        val index =
            engine.findClosestTokenForDrag(pointerRawX - screenOrigin[0], y, autoScroll.direction)
        val changed = engine.applyDragRange(index)
        postScrollFrame()
        accessibility.invalidateRoot()
        if (changed) notifySelectionChanged()
    }

    /** 挂起唯一下一帧；已挂起时不重复添加。 */
    private fun postScrollFrame() {
        if (!framePosted) {
            framePosted = true
            postOnAnimation(frameCallback)
        }
    }

    /** 清除帧与小数状态。 */
    private fun stopAutoScroll() {
        removeCallbacks(frameCallback)
        framePosted = false
        autoScroll.reset()
    }

    /** 统一结束所有手势状态，但保留已应用选择。 */
    private fun finishInteraction() {
        stopAutoScroll()
        engine.endDragSelection()
        gesture.reset()
        activePointer = MotionEvent.INVALID_POINTER_ID
        downToken = -1
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** 通知真实变化；该调用必须位于当前事件或帧的末尾。 */
    private fun notifySelectionChanged() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
        accessibility.invalidateRoot()
        onSelectionChangedListener?.invoke()
    }

    /**
     * 共用的触摸/无障碍点击入口。
     * @param index 目标索引。
     * @return 有效 token 被切换时为 true。
     */
    internal fun performTokenClick(index: Int): Boolean {
        finishInteraction()
        if (!engine.setSelectionState(index, !engine.isSelected(index))) return false
        performClick()
        notifySelectionChanged()
        return true
    }

    /**
     * 读取绘制阶段实际使用的布局缓存。
     * @param index 当前 token 索引。
     * @return 已建立的文字布局，尚未测量时为 null。
     */
    internal fun cachedTextLayout(index: Int): StaticLayout? = textLayouts.getOrNull(index)

    /** @return 当前逻辑 token 数量。 */
    internal fun tokenCount(): Int = engine.tokens.size

    /**
     * 读取节点原文。
     * @param index 已验证的索引。
     * @return 完整原文。
     */
    internal fun tokenText(index: Int): String = engine.tokens[index]

    /**
     * 读取节点状态。
     * @param index token 索引。
     * @return 是否选中。
     */
    internal fun tokenSelected(index: Int): Boolean = engine.isSelected(index)

    /**
     * 提供 helper 严格命中。
     * @param x 内容横坐标。
     * @param y 内容纵坐标。
     * @return token 索引或 -1。
     */
    internal fun tokenAt(x: Float, y: Float): Int = engine.findTokenAt(x, y)

    /**
     * 将缓存边界写入 helper 的复用矩形。
     * @param index token 索引。
     * @param result 调用方矩形。
     */
    internal fun tokenBounds(index: Int, result: Rect) {
        if (index < 0 || index >= engine.layoutInfos.size) {
            result.setEmpty()
            return
        }
        val info = engine.layoutInfos[index]
        result.set(
            floor(info.x).toInt(),
            floor(info.y).toInt(),
            ceil(info.x + info.width).toInt(),
            ceil(info.y + info.height).toInt()
        )
    }

    /**
     * 提供实际可见 token 范围。
     * @param result helper 独有的输出载体。
     */
    internal fun visibleTokens(result: TokenIndexRange) {
        if (getLocalVisibleRect(accessibilityViewport)) engine.fillVisibleTokenRange(
            accessibilityViewport.top.toFloat(),
            accessibilityViewport.bottom.toFloat(),
            result
        )
        else {
            result.firstIndex = -1
            result.lastIndex = -1
        }
    }

    /**
     * 显示目标；超高 token 先显示顶部，后续由正常滚动阅读。
     * @param index token 索引。
     * @return 有有效布局目标时为 true。
     */
    internal fun showToken(index: Int): Boolean {
        tokenBounds(index, requestedRect)
        if (requestedRect.isEmpty) return false
        val scroll = scrollParent
        if (scroll != null) requestedRect.bottom = minOf(
            requestedRect.bottom,
            requestedRect.top + scroll.height - scroll.paddingTop - scroll.paddingBottom
        )
        requestRectangleOnScreen(requestedRect, true)
        accessibility.invalidateRoot()
        return true
    }

    /**
     * 执行无障碍的一页滚动。
     * @param direction -1 向前，1 向后。
     * @return 是否实际移动。
     */
    internal fun scrollPage(direction: Int): Boolean {
        finishInteraction()
        val scroll = scrollParent ?: return false
        val before = scroll.scrollY
        scroll.scrollBy(
            0,
            direction * maxOf(1, scroll.height - scroll.paddingTop - scroll.paddingBottom)
        )
        accessibility.invalidateRoot()
        return scroll.scrollY != before
    }

    /**
     * 查询父容器可滚动性。
     * @param direction 目标方向。
     * @return 当前是否可滚动。
     */
    internal fun canScrollResult(direction: Int): Boolean =
        scrollParent?.canScrollVertically(direction) == true

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibility.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) finishInteraction()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        var ancestor = parent
        while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
        scrollParent = ancestor as? ScrollView
        viewTreeObserver.addOnScrollChangedListener(ancestorScrollListener)
    }

    override fun onDetachedFromWindow() {
        finishInteraction()
        scrollParent = null
        viewTreeObserver.removeOnScrollChangedListener(ancestorScrollListener)
        super.onDetachedFromWindow()
    }

    /**
     * 转换密度像素。
     * @param dp 密度无关像素。
     * @return 实际像素。
     */
    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    /**
     * 转换文字尺寸。
     * @param sp 缩放无关像素。
     * @return 实际像素。
     */
    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}
