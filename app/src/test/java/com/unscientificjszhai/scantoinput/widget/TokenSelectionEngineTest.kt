package com.unscientificjszhai.scantoinput.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TokenSelectionEngine 的纯 JVM 单元测试。
 *
 * 覆盖流式布局排版换行、二分定位命中检测、拖拽滑选快照与回滚以及视口可见范围裁剪等逻辑。
 */
class TokenSelectionEngineTest {

    /**
     * 验证 TokenSelectionEngine 在不同宽度边界下的多行流式排版换行计算。
     */
    @Test
    fun testLayoutCalculationAndLineWrapping() {
        val engine = TokenSelectionEngine()
        val tokens = listOf("A", "BB", "CCC", "DDDD")
        engine.setTokens(tokens)

        // 设定测量规则：每个字符宽 10f
        val measureTextWidth = { text: String -> text.length * 10f }

        // 参数：
        // 容器宽度 100f
        // 控件边距：Left=10f, Top=10f
        // 间距：Horizontal=5f, Vertical=5f
        // Token内边距：Horizontal=5f, Vertical=5f
        // 文字度量：Top=-15f, Bottom=5f (文字高 20f)
        // Token总高度 = (5 - (-15)) + 5 * 2 = 30f
        // Baseline = 15 + 5 = 20f
        engine.calculateLayout(
            availableWidth = 100f,
            paddingLeft = 10f,
            paddingTop = 10f,
            tokenSpacingHorizontal = 5f,
            tokenSpacingVertical = 5f,
            tokenPaddingHorizontal = 5f,
            tokenPaddingVertical = 5f,
            fontMetricsTop = -15f,
            fontMetricsBottom = 5f,
            measureTextWidth = measureTextWidth
        )

        // Token 宽度明细：
        // "A": textWidth=10f, tokenWidth=20f
        // "BB": textWidth=20f, tokenWidth=30f
        // "CCC": textWidth=30f, tokenWidth=40f
        // "DDDD": textWidth=40f, tokenWidth=50f

        // 容器可用宽 100f。
        // 第 1 行: "A"(宽20) + 间距5 + "BB"(宽30) + 间距5 + "CCC"(宽40) = 100f。刚好装下。
        // 第 2 行: "DDDD"(宽50)。放不下折行。
        assertEquals(4, engine.layoutInfos.size)
        assertEquals(2, engine.lineInfos.size)

        // 第 1 行坐标验证
        val line1 = engine.lineInfos[0]
        assertEquals(10f, line1.top)
        assertEquals(40f, line1.bottom) // 10f + height(30)
        assertEquals(0, line1.firstTokenIndex)
        assertEquals(2, line1.lastTokenIndex)

        // 第 2 行坐标验证
        val line2 = engine.lineInfos[1]
        assertEquals(45f, line2.top) // 40 + spacing(5)
        assertEquals(75f, line2.bottom) // 45 + height(30)
        assertEquals(3, line2.firstTokenIndex)
        assertEquals(3, line2.lastTokenIndex)

        assertEquals(75f, engine.totalHeight)
    }

    /**
     * 验证基于二分查找的快速命中检测算法。
     */
    @Test
    fun testHitTestingWithBinarySearch() {
        val engine = TokenSelectionEngine()
        val tokens = listOf("A", "B", "C")
        engine.setTokens(tokens)

        // 每个 Token 宽 20f (textWidth=10 + padding=10)
        val measureTextWidth = { _: String -> 10f }

        engine.calculateLayout(
            availableWidth = 200f,
            paddingLeft = 0f,
            paddingTop = 0f,
            tokenSpacingHorizontal = 10f,
            tokenSpacingVertical = 10f,
            tokenPaddingHorizontal = 5f,
            tokenPaddingVertical = 5f,
            fontMetricsTop = -10f,
            fontMetricsBottom = 10f, // 文字高 20f, Token 高 30f
            measureTextWidth = measureTextWidth
        )

        // "A": x=0..20, y=0..30
        // "B": x=30..50, y=0..30
        // "C": x=60..80, y=0..30

        // 命中测试验证
        assertEquals(0, engine.findTokenAt(10f, 15f))
        assertEquals(1, engine.findTokenAt(40f, 15f))
        assertEquals(2, engine.findTokenAt(70f, 15f))

        // 间距区未命中验证
        assertEquals(-1, engine.findTokenAt(25f, 15f)) // 横向间距
        assertEquals(-1, engine.findTokenAt(10f, 35f)) // 纵向越界
        assertEquals(-1, engine.findTokenAt(-5f, 15f)) // 左侧越界
    }

    /**
     * 验证拖拽滑选中的状态翻转、快照保存以及往回拖动时的状态回滚。
     */
    @Test
    fun testDragSelectionAndSnapshotRollback() {
        val engine = TokenSelectionEngine()
        val tokens = listOf("A", "B", "C", "D")
        engine.setTokens(tokens)

        // 构造简单的布局，使 findTokenAt 能使用
        engine.calculateLayout(
            availableWidth = 500f,
            paddingLeft = 0f,
            paddingTop = 0f,
            tokenSpacingHorizontal = 10f,
            tokenSpacingVertical = 10f,
            tokenPaddingHorizontal = 5f,
            tokenPaddingVertical = 5f,
            fontMetricsTop = -10f,
            fontMetricsBottom = 10f,
            measureTextWidth = { 10f }
        )

        // 初始状态下全部未选中
        assertFalse(engine.isSelected(0))
        assertFalse(engine.isSelected(1))

        // 1. 在索引 0 处开始滑选。因为 0 处原来为 false，目标状态为 true
        engine.startDragSelection(0)
        assertTrue(engine.isDragging)
        assertTrue(engine.isSelected(0))

        // 2. 向右滑动到索引 2，即选区为 [0..2]
        val changedTo2 = engine.applyDragRange(2)
        assertTrue(changedTo2)
        assertTrue(engine.isSelected(0))
        assertTrue(engine.isSelected(1))
        assertTrue(engine.isSelected(2))
        assertFalse(engine.isSelected(3))

        // 重复滑动到相同的 2，应该返回 false 避免冗余 invalidate
        assertFalse(engine.applyDragRange(2))

        // 3. 往回滑动到索引 1，此时索引 2 应该被回滚为初始状态（即未选中）
        val changedTo1 = engine.applyDragRange(1)
        assertTrue(changedTo1)
        assertTrue(engine.isSelected(0))
        assertTrue(engine.isSelected(1))
        assertFalse(engine.isSelected(2)) // 成功回退

        // 4. 结束滑选手势
        engine.endDragSelection()
        assertFalse(engine.isDragging)
        assertTrue(engine.isSelected(0))
        assertTrue(engine.isSelected(1))
    }

    /**
     * 验证可见范围裁剪算法，确保在大视口和滑动滚动下只重绘可见区域。
     */
    @Test
    fun testVisibleRangeCalculation() {
        val engine = TokenSelectionEngine()
        // 构造多行，每行 1 个 Token
        engine.setTokens(listOf("1", "2", "3", "4", "5"))

        // 参数使得每行高度固定为 30f，垂直间距为 10f。
        // 第 0 行 (index 0): y=0..30 (Line top=0, bottom=30)
        // 第 1 行 (index 1): y=40..70 (Line top=40, bottom=70)
        // 第 2 行 (index 2): y=80..110 (Line top=80, bottom=110)
        // 第 3 行 (index 3): y=120..150 (Line top=120, bottom=150)
        // 第 4 行 (index 4): y=160..190 (Line top=160, bottom=190)
        engine.calculateLayout(
            availableWidth = 5f,
            paddingLeft = 0f,
            paddingTop = 0f,
            tokenSpacingHorizontal = 0f,
            tokenSpacingVertical = 10f,
            tokenPaddingHorizontal = 0f,
            tokenPaddingVertical = 5f,
            fontMetricsTop = -10f,
            fontMetricsBottom = 10f,
            measureTextWidth = { 10f }
        )

        // 场景 1: 视口完全覆盖全部 Token (0..200)
        assertEquals(0..4, engine.getVisibleTokenIndices(0f, 200f))

        // 场景 2: 视口在中间，局部交叉 (50..130)
        // 覆盖第 1 行 (y=40..70), 第 2 行 (y=80..110), 第 3 行 (y=120..150)
        // 注意：第 1 行 bottom=70 >= 50 (可见)；第 3 行 top=120 <= 130 (可见)；
        assertEquals(1..3, engine.getVisibleTokenIndices(50f, 130f))

        // 场景 3: 视口越出边界
        assertEquals(4..4, engine.getVisibleTokenIndices(180f, 250f))
        assertEquals(IntRange.EMPTY, engine.getVisibleTokenIndices(200f, 300f))
    }
}
