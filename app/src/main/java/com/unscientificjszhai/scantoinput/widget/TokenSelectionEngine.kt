package com.unscientificjszhai.scantoinput.widget

/** 管理纯 Kotlin 的流式几何、可见索引和保留快照的连续选择。 */
class TokenSelectionEngine {
    /** 当前逻辑 token 的不可变列表快照。 */
    var tokens: List<String> = emptyList()
        private set

    /** 冷路径建立的 token 几何缓存。 */
    var layoutInfos: Array<TokenLayoutInfo> = emptyArray()
        private set

    /** 按纵坐标排序的行缓存。 */
    var lineInfos: List<LineLayoutInfo> = emptyList()
        private set

    /** 最后一行的底部坐标，已经包含顶部 padding。 */
    var totalHeight = 0f
        private set

    /** 是否存在正在处理的连续选择。 */
    var isDragging = false
        private set
    private var selection = BooleanArray(0)
    private var snapshot = BooleanArray(0)
    private var selectedCount = 0
    private var dragAnchor = -1
    private var rangeStart = -1
    private var rangeEnd = -1
    private var dragTarget = false

    /**
     * 替换 token，并在冷路径准备选择数组和清理旧几何。
     * @param tokens 原始逻辑 token 列表。
     */
    fun setTokens(tokens: List<String>) {
        this.tokens = tokens.toList()
        if (selection.size != tokens.size) {
            selection = BooleanArray(tokens.size)
            snapshot = BooleanArray(tokens.size)
        } else {
            selection.fill(false)
        }
        selectedCount = 0
        endDragSelection()
        layoutInfos = emptyArray()
        lineInfos = emptyList()
        totalHeight = 0f
    }

    /**
     * 在测量阶段建立支持不同高度及独占行的布局。
     * @param availableWidth 扣除 View 左右 padding 的可用宽度。
     * @param paddingLeft View 左 padding。
     * @param paddingTop View 顶 padding，仅计入一次。
     * @param tokenSpacingHorizontal token 水平间距。
     * @param tokenSpacingVertical 流式行垂直间距。
     * @param measurer 测量并缓存平台文字布局的协作者。
     */
    fun calculateLayout(
        availableWidth: Float, paddingLeft: Float, paddingTop: Float,
        tokenSpacingHorizontal: Float, tokenSpacingVertical: Float, measurer: TokenTextMeasurer
    ) {
        endDragSelection()
        layoutInfos = emptyArray()
        lineInfos = emptyList()
        totalHeight = 0f
        if (availableWidth <= 0f || tokens.isEmpty()) return
        val infos = ArrayList<TokenLayoutInfo>(tokens.size)
        val lines = ArrayList<LineLayoutInfo>()
        var x = 0f
        var y = paddingTop
        var lineHeight = 0f
        var first = 0
        var previousWholeLine = false
        var i = 0
        while (i < tokens.size) {
            val measured = measurer.measure(i, tokens[i], availableWidth)
            if (i > first && (previousWholeLine || measured.occupiesWholeLine || x + measured.width > availableWidth)) {
                lines.add(LineLayoutInfo(y, y + lineHeight, first, i - 1))
                y += lineHeight + tokenSpacingVertical
                x = 0f
                lineHeight = 0f
                first = i
            }
            infos.add(
                TokenLayoutInfo(
                    paddingLeft + x,
                    y,
                    measured.width,
                    measured.height,
                    measured.baseline
                )
            )
            x += measured.width + tokenSpacingHorizontal
            lineHeight = maxOf(lineHeight, measured.height)
            previousWholeLine = measured.occupiesWholeLine
            i++
        }
        lines.add(LineLayoutInfo(y, y + lineHeight, first, tokens.lastIndex))
        layoutInfos = infos.toTypedArray()
        lineInfos = lines
        totalHeight = lines.last().bottom
    }

    /**
     * 查询单个 token 的选择状态。
     * @param index token 索引。
     * @return 索引有效且被选中时为 true。
     */
    fun isSelected(index: Int): Boolean = index >= 0 && index < selection.size && selection[index]

    /** @return 是否至少有一个 token 被选中，查询为常数时间。 */
    fun hasSelection(): Boolean = selectedCount > 0

    /** @return 按原始顺序无分隔拼接的完整原文。 */
    fun getFullText(): String = tokens.joinToString("")

    /** @return 按原始顺序拼接的选择文本，空选择返回空串。 */
    fun getSelectedText(): String {
        if (selectedCount == 0) return ""
        val result = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            if (selection[i]) result.append(tokens[i])
            i++
        }
        return result.toString()
    }

    /** @return 清空选择后是否发生实际改变；同时结束连续选择。 */
    fun clearSelection(): Boolean {
        endDragSelection()
        if (selectedCount == 0) return false
        selection.fill(false)
        selectedCount = 0
        return true
    }

    /**
     * 对外设置单个选择状态，并结束任何旧拖选。
     * @param index token 索引。
     * @param state 目标状态。
     * @return 是否发生实际改变。
     */
    fun setSelectionState(index: Int, state: Boolean): Boolean {
        endDragSelection()
        if (index < 0 || index >= selection.size) return false
        return changeSelection(index, state)
    }

    /**
     * 在有效索引处开始拖选，复用已有快照数组。
     * @param index 起点索引。
     * @return 起点选择是否发生改变。
     */
    fun startDragSelection(index: Int): Boolean {
        endDragSelection()
        if (index < 0 || index >= selection.size) return false
        System.arraycopy(selection, 0, snapshot, 0, selection.size)
        dragAnchor = index
        rangeStart = index
        rangeEnd = index
        dragTarget = !selection[index]
        isDragging = true
        return changeSelection(index, dragTarget)
    }

    /**
     * 仅处理旧、新区间差集；回拉离开的部分恢复原选择。
     * @param currentIndex 当前命中索引。
     * @return 是否有 token 状态实际改变。
     */
    fun applyDragRange(currentIndex: Int): Boolean {
        if (!isDragging || currentIndex < 0 || currentIndex >= selection.size) return false
        val start = minOf(dragAnchor, currentIndex)
        val end = maxOf(dragAnchor, currentIndex)
        if (start == rangeStart && end == rangeEnd) return false
        var changed = false
        var i = rangeStart
        while (i < start) {
            if (changeSelection(i, snapshot[i])) changed = true
            i++
        }
        i = end + 1
        while (i <= rangeEnd) {
            if (changeSelection(i, snapshot[i])) changed = true
            i++
        }
        i = start
        while (i < rangeStart) {
            if (changeSelection(i, dragTarget)) changed = true
            i++
        }
        i = rangeEnd + 1
        while (i <= end) {
            if (changeSelection(i, dragTarget)) changed = true
            i++
        }
        rangeStart = start
        rangeEnd = end
        return changed
    }

    /** 结束交互，保留当前已应用的选择，包括取消时的选择。 */
    fun endDragSelection() {
        isDragging = false
        dragAnchor = -1
        rangeStart = -1
        rangeEnd = -1
    }

    /**
     * 修改有效位置的状态及数量，仅供内部差集算法使用。
     * @param index 已经验证的索引。
     * @param state 目标状态。
     * @return 实际改变时为 true。
     */
    private fun changeSelection(index: Int, state: Boolean): Boolean {
        if (selection[index] == state) return false
        selection[index] = state
        selectedCount += if (state) 1 else -1
        return true
    }

    /**
     * 使用半开矩形边界严格命中，短 token 下方空白不会被选中。
     * @param x 内容横坐标。
     * @param y 内容纵坐标。
     * @return 命中索引，未命中时为 -1。
     */
    fun findTokenAt(x: Float, y: Float): Int {
        val lineIndex = lineAtOrInsertion(y)
        if (lineIndex < 0) return -1
        val line = lineInfos[lineIndex]
        var low = line.firstTokenIndex
        var high = line.lastTokenIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val token = layoutInfos[mid]
            if (x < token.x) high = mid - 1
            else if (x >= token.x + token.width) low = mid + 1
            else return if (y < token.y + token.height) mid else -1
        }
        return -1
    }

    /**
     * 写入实际视口相交的 token 范围，复用调用方对象。
     * @param viewportTop 视口顶部。
     * @param viewportBottom 视口底部，采用半开边界。
     * @param result 重复使用的输出载体。
     */
    fun fillVisibleTokenRange(viewportTop: Float, viewportBottom: Float, result: TokenIndexRange) {
        result.firstIndex = -1
        result.lastIndex = -1
        if (viewportBottom <= viewportTop) return
        var low = 0
        var high = lineInfos.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lineInfos[mid].bottom <= viewportTop) low = mid + 1 else high = mid
        }
        val first = low
        low = 0
        high = lineInfos.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lineInfos[mid].top < viewportBottom) low = mid + 1 else high = mid
        }
        val last = low - 1
        if (first > last) return
        result.firstIndex = lineInfos[first].firstTokenIndex
        result.lastIndex = lineInfos[last].lastTokenIndex
    }

    /**
     * 边缘自动滚动时，定位目标行中横向最近的 token。
     * @param x 内容横坐标。
     * @param y 已限制到可见区的内容纵坐标。
     * @param direction 滚动方向，负数向上，其他值向下。
     * @return 最近索引，空布局返回 -1；距离相同取原文中较小索引。
     */
    fun findClosestTokenForDrag(x: Float, y: Float, direction: Int): Int {
        if (lineInfos.isEmpty()) return -1
        val found = lineAtOrInsertion(y)
        val lineIndex = if (found >= 0) found else {
            val insertion = -found - 1
            (if (direction < 0) insertion - 1 else insertion).coerceIn(0, lineInfos.lastIndex)
        }
        val line = lineInfos[lineIndex]
        var low = line.firstTokenIndex
        var high = line.lastTokenIndex + 1
        while (low < high) {
            val mid = (low + high) ushr 1
            val token = layoutInfos[mid]
            if (token.x + token.width <= x) low = mid + 1 else high = mid
        }
        if (low > line.lastTokenIndex) return line.lastTokenIndex
        if (low == line.firstTokenIndex) return low
        val left = layoutInfos[low - 1]
        val right = layoutInfos[low]
        val leftDistance = x - left.x - left.width
        val rightDistance = maxOf(right.x - x, 0f)
        return if (leftDistance <= rightDistance) low - 1 else low
    }

    /**
     * 二分查找目标行或插入位置，避免为结果创建对象。
     * @param y 内容纵坐标。
     * @return 命中行；未命中返回负的插入位置减一。
     */
    private fun lineAtOrInsertion(y: Float): Int {
        var low = 0
        var high = lineInfos.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lineInfos[mid]
            if (y < line.top) high = mid - 1
            else if (y >= line.bottom) low = mid + 1
            else return mid
        }
        return -low - 1
    }
}
