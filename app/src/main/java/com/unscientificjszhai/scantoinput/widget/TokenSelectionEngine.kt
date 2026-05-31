package com.unscientificjszhai.scantoinput.widget

import java.util.BitSet

/**
 * 负责 Token 选择控件底层几何排版与手势管理的逻辑引擎。
 *
 * 本引擎不依赖 Android 任何 UI 组件或绘图库，使得布局、高效碰撞检测、滑动选区以及可见范围计算
 * 能够完全脱耦并直接进行高效率的 JVM 单元测试。
 */
class TokenSelectionEngine {

    /**
     * 当前管理的 Token 字符串列表。
     */
    var tokens: List<String> = emptyList()
        private set

    /**
     * 缓存的单个 Token 布局几何坐标信息数组。
     */
    var layoutInfos: Array<TokenLayoutInfo> = emptyArray()
        private set

    /**
     * 缓存的每行流式排版区间及 Token 范围的列表。
     */
    var lineInfos: List<LineLayoutInfo> = emptyList()
        private set

    /**
     * 控件计算得出的内容总高度。
     */
    var totalHeight: Float = 0f
        private set

    // 选中状态掩码
    private val selectionState = BitSet()

    // 手势交互临时状态
    /**
     * 当前是否正在滑动选择。
     */
    var isDragging: Boolean = false
        private set

    private var dragStartIndex = -1
    private var dragRangeStart = -1
    private var dragRangeEnd = -1
    private var dragInitialSelectedState = false
    private val dragSelectionSnapshot = BitSet()

    /**
     * 设置要显示的 Token 列表，并清空历史状态与布局缓存。
     *
     * @param tokens 待显示的文本 Token 列表。
     */
    fun setTokens(tokens: List<String>) {
        this.tokens = tokens
        selectionState.clear()
        layoutInfos = emptyArray()
        lineInfos = emptyList()
        totalHeight = 0f
        isDragging = false
        dragStartIndex = -1
        dragRangeStart = -1
        dragRangeEnd = -1
    }

    /**
     * 计算并建立所有 Token 的流式排版几何布局索引。
     *
     * @param availableWidth 控件的可用宽度（总宽扣除左右内边距）。
     * @param paddingLeft 控件左边距，参与绝对坐标的定位。
     * @param paddingTop 控件顶边距，参与绝对坐标的定位。
     * @param tokenSpacingHorizontal Token 之间的水平间距。
     * @param tokenSpacingVertical Token 之间的垂直间距。
     * @param tokenPaddingHorizontal Token 左右内边距。
     * @param tokenPaddingVertical Token 上下内边距。
     * @param fontMetricsTop 文本测量的顶边界偏移（负值）。
     * @param fontMetricsBottom 文本测量的底边界偏移（正值）。
     * @param measureTextWidth 用于测量指定字符串宽度的逻辑函数。
     */
    fun calculateLayout(
        availableWidth: Float,
        paddingLeft: Float,
        paddingTop: Float,
        tokenSpacingHorizontal: Float,
        tokenSpacingVertical: Float,
        tokenPaddingHorizontal: Float,
        tokenPaddingVertical: Float,
        fontMetricsTop: Float,
        fontMetricsBottom: Float,
        measureTextWidth: (String) -> Float
    ) {
        val infos = ArrayList<TokenLayoutInfo>(tokens.size)
        val lines = ArrayList<LineLayoutInfo>()

        var currentX = 0f
        var currentY = 0f
        var maxLineHeight = 0f
        var currentLineFirstIndex = 0

        val tokenHeight = (fontMetricsBottom - fontMetricsTop) + tokenPaddingVertical * 2
        val baseline = -fontMetricsTop + tokenPaddingVertical

        for (i in tokens.indices) {
            val token = tokens[i]
            val textWidth = measureTextWidth(token)
            val tokenWidth = textWidth + tokenPaddingHorizontal * 2

            // 判断是否需要折行
            if (currentX + tokenWidth > availableWidth && currentX > 0) {
                // 记录前一行的行信息
                lines.add(
                    LineLayoutInfo(
                        top = currentY + paddingTop,
                        bottom = currentY + paddingTop + maxLineHeight,
                        firstTokenIndex = currentLineFirstIndex,
                        lastTokenIndex = i - 1
                    )
                )
                // 换行定位
                currentX = 0f
                currentY += maxLineHeight + tokenSpacingVertical
                maxLineHeight = 0f
                currentLineFirstIndex = i
            }

            infos.add(
                TokenLayoutInfo(
                    x = currentX + paddingLeft,
                    y = currentY + paddingTop,
                    width = tokenWidth,
                    height = tokenHeight,
                    baseline = baseline
                )
            )

            currentX += tokenWidth + tokenSpacingHorizontal
            maxLineHeight = maxOf(maxLineHeight, tokenHeight)
        }

        // 记录最后一行的剩余 Token
        if (tokens.isNotEmpty()) {
            lines.add(
                LineLayoutInfo(
                    top = currentY + paddingTop,
                    bottom = currentY + paddingTop + maxLineHeight,
                    firstTokenIndex = currentLineFirstIndex,
                    lastTokenIndex = tokens.size - 1
                )
            )
        }

        this.layoutInfos = infos.toTypedArray()
        this.lineInfos = lines
        this.totalHeight = if (lines.isNotEmpty()) {
            val last = lines.last()
            last.bottom
        } else {
            0f
        }
    }

    /**
     * 判断指定索引的 Token 是否处于选中状态。
     *
     * @param index Token 索引。
     * @return 如果选中则返回 true，否则返回 false。
     */
    fun isSelected(index: Int): Boolean {
        if (index !in tokens.indices) return false
        return selectionState.get(index)
    }

    /**
     * 获取当前选中的文本。
     * 按照 Token 在原文中的顺序进行拼接。
     *
     * @return 拼接后的选中文本，若无选中则返回空字符串。
     */
    fun getSelectedText(): String {
        if (selectionState.isEmpty) return ""
        val sb = StringBuilder()
        for (i in tokens.indices) {
            if (selectionState.get(i)) {
                sb.append(tokens[i])
            }
        }
        return sb.toString()
    }

    /**
     * 判断当前是否有任何 Token 被选中。
     *
     * @return 若有至少一个选中则返回 true，否则返回 false。
     */
    fun hasSelection(): Boolean = !selectionState.isEmpty

    /**
     * 清空当前所有的选择状态。
     *
     * @return 如果状态实际发生变化（即原来存在被选中的项）则返回 true，否则返回 false。
     */
    fun clearSelection(): Boolean {
        if (!selectionState.isEmpty) {
            selectionState.clear()
            return true
        }
        return false
    }

    /**
     * 设置指定 Token 的选择状态。
     *
     * @param index Token 索引。
     * @param state 目标选中状态。
     * @return 如果状态实际发生变化则返回 true，否则返回 false。
     */
    fun setSelectionState(index: Int, state: Boolean): Boolean {
        if (index !in tokens.indices) return false
        if (selectionState.get(index) == state) return false
        selectionState.set(index, state)
        return true
    }

    /**
     * 查找指定坐标下的 Token 索引。
     *
     * 运用二分查找加速命中测试，先确定目标行，再对行内 Token 检索，平均时间复杂度降至 O(log(行数))。
     *
     * @param x 绝对水平坐标。
     * @param y 绝对垂直坐标。
     * @return 命中的 Token 全局索引，如果未命中任何 Token 则返回 -1。
     */
    fun findTokenAt(x: Float, y: Float): Int {
        if (lineInfos.isEmpty() || layoutInfos.isEmpty()) return -1

        var low = 0
        var high = lineInfos.size - 1
        var targetLineIndex = -1

        // 二分定位 Y 轴所属行
        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lineInfos[mid]
            if (y >= line.top && y <= line.bottom) {
                targetLineIndex = mid
                break
            } else if (y < line.top) {
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        if (targetLineIndex == -1) return -1

        // 在查到的行内线性搜索 X 坐标（每行 Token 较少，性能极佳）
        val line = lineInfos[targetLineIndex]
        for (i in line.firstTokenIndex..line.lastTokenIndex) {
            val info = layoutInfos[i]
            if (x >= info.x && x <= info.x + info.width) {
                return i
            }
        }
        return -1
    }

    /**
     * 开始一次滑选交互并捕获当前的选中状态快照。
     *
     * @param index 滑选起点的 Token 全局索引。
     */
    fun startDragSelection(index: Int) {
        if (index !in tokens.indices) return
        isDragging = true
        dragStartIndex = index
        dragRangeStart = -1
        dragRangeEnd = -1
        dragInitialSelectedState = !selectionState.get(index)
        dragSelectionSnapshot.clear()
        dragSelectionSnapshot.or(selectionState)
        applyDragRange(index)
    }

    /**
     * 扩展或缩小滑选选区。
     *
     * 将起点到当前 Token 的闭区间覆盖为目标选择状态，并回滚已被拖拽路径移出的 Token 到初始快照状态。
     *
     * @param currentIndex 当前滑到的 Token 全局索引。
     * @return 如果这步操作让整个选择集的状态相比上一步产生了实际改变，则返回 true，用以指导 View 按需 invalidate。
     */
    fun applyDragRange(currentIndex: Int): Boolean {
        if (!isDragging || currentIndex !in tokens.indices) return false
        val newRangeStart = minOf(dragStartIndex, currentIndex)
        val newRangeEnd = maxOf(dragStartIndex, currentIndex)
        var changed = false

        // 1. 回退在拖拽路线中被放弃（遗弃）的 Token 状态
        if (dragRangeStart != -1) {
            for (i in dragRangeStart..dragRangeEnd) {
                if (i !in newRangeStart..newRangeEnd) {
                    changed = setSelectionState(i, dragSelectionSnapshot.get(i)) || changed
                }
            }
        }

        // 2. 应用新的滑选目标状态
        for (i in newRangeStart..newRangeEnd) {
            changed = setSelectionState(i, dragInitialSelectedState) || changed
        }

        dragRangeStart = newRangeStart
        dragRangeEnd = newRangeEnd
        return changed
    }

    /**
     * 结束滑动选择，清理并释放用于拖拽的内部标记。
     */
    fun endDragSelection() {
        isDragging = false
        dragStartIndex = -1
        dragRangeStart = -1
        dragRangeEnd = -1
    }

    /**
     * 计算指定视口在 Y 轴可见坐标范围内的 Token 索引。
     *
     * 采用二分法查找与视口重叠的首行和尾行，避免在大文本（如 2000 个 Token）下进行全量绘制遍历。
     *
     * @param viewportTop 视口顶坐标。
     * @param viewportBottom 视口底坐标。
     * @return 处于可见区域内的 Token 的全局索引闭区间，如果无可画内容返回空范围。
     */
    fun getVisibleTokenIndices(viewportTop: Float, viewportBottom: Float): IntRange {
        if (lineInfos.isEmpty() || layoutInfos.isEmpty()) return IntRange.EMPTY

        // 1. 寻找第一个与 viewport 产生交叉的行 (line.bottom >= viewportTop)
        var firstVisibleLine = -1
        var low = 0
        var high = lineInfos.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lineInfos[mid]
            if (line.bottom >= viewportTop) {
                firstVisibleLine = mid
                high = mid - 1 // 往顶上找是否还有交叉的
            } else {
                low = mid + 1
            }
        }

        if (firstVisibleLine == -1) return IntRange.EMPTY

        // 2. 寻找最后一个与 viewport 产生交叉的行 (line.top <= viewportBottom)
        var lastVisibleLine = -1
        low = 0
        high = lineInfos.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lineInfos[mid]
            if (line.top <= viewportBottom) {
                lastVisibleLine = mid
                low = mid + 1 // 往底下找是否还有交叉的
            } else {
                high = mid - 1
            }
        }

        if (lastVisibleLine == -1 || lastVisibleLine < firstVisibleLine) return IntRange.EMPTY

        val startToken = lineInfos[firstVisibleLine].firstTokenIndex
        val endToken = lineInfos[lastVisibleLine].lastTokenIndex
        return startToken..endToken
    }
}
