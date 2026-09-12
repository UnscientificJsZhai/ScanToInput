package com.unscientificjszhai.scantoinput.widget

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** 以公开接口验证几何边界、原文与差集选择，不依赖 Android。 */
class TokenSelectionEngineTest {
    /** 空状态与无效调用不会产生几何或选择。 */
    @Test
    fun emptyAndInvalidOperationsAreSafe() {
        val engine = TokenSelectionEngine()
        val range = TokenIndexRange(7, 8)
        assertEquals("", engine.getFullText())
        assertEquals("", engine.getSelectedText())
        assertFalse(engine.hasSelection())
        assertFalse(engine.isSelected(-1))
        assertFalse(engine.isSelected(0))
        assertFalse(engine.setSelectionState(-1, true))
        assertFalse(engine.setSelectionState(0, true))
        assertFalse(engine.startDragSelection(-1))
        assertFalse(engine.startDragSelection(0))
        assertFalse(engine.applyDragRange(0))
        assertFalse(engine.clearSelection())
        assertEquals(-1, engine.findTokenAt(0f, 0f))
        assertEquals(-1, engine.findClosestTokenForDrag(0f, 0f, 1))
        engine.fillVisibleTokenRange(0f, 100f, range)
        assertEquals(TokenIndexRange(), range)
        layout(engine, 100f)
        assertEquals(0f, engine.totalHeight)
        engine.setTokens(listOf("a"))
        layout(engine, 0f)
        assertTrue(engine.layoutInfos.isEmpty())
        layout(engine, -1f)
        assertTrue(engine.lineInfos.isEmpty())
    }

    /** 混合 token 的原文、空白与换行均原样保留。 */
    @Test
    fun rawTextAndIndependentListSnapshotArePreserved() {
        val source = mutableListOf("a", " ", "\r\n", "👨‍👩‍👧‍👦", "")
        val engine = TokenSelectionEngine()
        engine.setTokens(source)
        source.clear()
        assertEquals("a \r\n👨‍👩‍👧‍👦", engine.getFullText())
        assertTrue(engine.setSelectionState(1, true))
        assertFalse(engine.setSelectionState(1, true))
        engine.setSelectionState(2, true)
        assertEquals(" \r\n", engine.getSelectedText())
        assertTrue(engine.hasSelection())
        engine.startDragSelection(0)
        assertTrue(engine.clearSelection())
        assertFalse(engine.isDragging)
        assertFalse(engine.clearSelection())
        engine.setTokens(listOf("b", "", "", "", ""))
        assertFalse(engine.hasSelection())
        engine.startDragSelection(1)
        engine.setTokens(emptyList())
        assertFalse(engine.isDragging)
        assertTrue(engine.layoutInfos.isEmpty())
    }

    /** 变高、独占行与恰好容纳的 token 使用明确几何。 */
    @Test
    fun variableHeightWholeLineAndExactFitLayout() {
        val engine = TokenSelectionEngine()
        engine.setTokens(listOf("a", "b", "c", "d", "e"))
        val widths = floatArrayOf(20f, 30f, 40f, 60f, 40f)
        val heights = floatArrayOf(20f, 40f, 70f, 10f, 15f)
        engine.calculateLayout(100f, 10f, 10f, 5f, 5f, TokenTextMeasurer { i, _, _ ->
            TokenTextMetrics(widths[i], heights[i], 8f, i == 2)
        })
        assertEquals(
            listOf(
                LineLayoutInfo(10f, 50f, 0, 1), LineLayoutInfo(55f, 125f, 2, 2),
                LineLayoutInfo(130f, 140f, 3, 3), LineLayoutInfo(145f, 160f, 4, 4)
            ), engine.lineInfos
        )
        assertEquals(TokenLayoutInfo(35f, 10f, 30f, 40f, 8f), engine.layoutInfos[1])
        assertEquals(160f, engine.totalHeight)
        engine.setTokens(listOf("a", "b", "c"))
        engine.calculateLayout(
            100f,
            0f,
            0f,
            5f,
            0f,
            TokenTextMeasurer { i, _, _ -> TokenTextMetrics(widths[i], 10f, 8f, false) })
        assertEquals(1, engine.lineInfos.size)
        engine.startDragSelection(0)
        layout(engine, 60f)
        assertFalse(engine.isDragging)
        assertTrue(engine.isSelected(0))
    }

    /** 半开矩形、短 token 下方空白及行间距不会误命中。 */
    @Test
    fun strictHitTestingUsesBothCoordinates() {
        val engine = geometry()
        assertEquals(0, engine.findTokenAt(0f, 0f))
        assertEquals(1, engine.findTokenAt(30f, 29f))
        assertEquals(2, engine.findTokenAt(1f, 35f))
        assertEquals(3, engine.findTokenAt(31f, 74f))
        for ((x, y) in listOf(
            -1f to 5f, 20f to 5f, 25f to 5f, 50f to 5f,
            1f to -1f, 1f to 10f, 1f to 30f, 1f to 34f, 1f to 75f, 1f to 100f
        )) {
            assertEquals(-1, engine.findTokenAt(x, y))
        }
    }

    /** 相交范围复用载体，并在空查询时抹除旧结果。 */
    @Test
    fun visibleRangeRespectsClipAndClearsPreviousValues() {
        val engine = geometry()
        val geometries = engine.layoutInfos
        val output = TokenIndexRange()
        for ((clip, expected) in listOf(
            (0f to 30f) to TokenIndexRange(0, 1),
            (35f to 75f) to TokenIndexRange(2, 3),
            (20f to 40f) to TokenIndexRange(0, 3),
            (-10f to 100f) to TokenIndexRange(0, 3),
            (30f to 35f) to TokenIndexRange(),
            (-20f to -1f) to TokenIndexRange(),
            (75f to 100f) to TokenIndexRange(),
            (10f to 10f) to TokenIndexRange(),
            (20f to 0f) to TokenIndexRange()
        )) {
            engine.fillVisibleTokenRange(clip.first, clip.second, output)
            assertEquals(expected, output)
        }
        engine.startDragSelection(0)
        engine.applyDragRange(3)
        engine.applyDragRange(1)
        engine.endDragSelection()
        assertSame(geometries, engine.layoutInfos)
    }

    /** 行间空白遵循方向，横向并列距离选择较小索引。 */
    @Test
    fun nearestDragTokenUsesDirectionAndStableTies() {
        val engine = geometry()
        for ((x, expected) in listOf(
            -10f to 0,
            10f to 0,
            20f to 0,
            22f to 0,
            25f to 0,
            28f to 1,
            31f to 1,
            100f to 1
        )) {
            assertEquals(expected, engine.findClosestTokenForDrag(x, 5f, 1))
        }
        assertEquals(0, engine.findClosestTokenForDrag(5f, 32f, -1))
        assertEquals(2, engine.findClosestTokenForDrag(5f, 32f, 1))
        assertEquals(0, engine.findClosestTokenForDrag(5f, -10f, -1))
        assertEquals(0, engine.findClosestTokenForDrag(5f, -10f, 1))
        assertEquals(2, engine.findClosestTokenForDrag(5f, 100f, -1))
        assertEquals(2, engine.findClosestTokenForDrag(5f, 100f, 1))
    }

    /** 结束、无效操作与外部选择修改不会复用旧拖选。 */
    @Test
    fun externalSelectionAndInvalidDragEndSafely() {
        val engine = TokenSelectionEngine()
        engine.setTokens(listOf("a", "b", "c"))
        assertTrue(engine.startDragSelection(1))
        assertFalse(engine.applyDragRange(-1))
        assertFalse(engine.applyDragRange(3))
        assertFalse(engine.applyDragRange(1))
        assertTrue(engine.setSelectionState(2, true))
        assertFalse(engine.isDragging)
        assertFalse(engine.applyDragRange(0))
        engine.startDragSelection(1)
        engine.endDragSelection()
        assertFalse(engine.isSelected(1))
        assertTrue(engine.isSelected(2))
        assertFalse(engine.startDragSelection(-1))
        assertFalse(engine.startDragSelection(3))
    }

    /** 五万次跨起点扩展与回拉，逐步对照完整快照参考模型。 */
    @Test
    fun fiftyThousandRangeChangesMatchSnapshotModel() {
        val random = Random(1739)
        val engine = TokenSelectionEngine()
        repeat(5000) {
            engine.setTokens(List(12) { it.toString() })
            val initial = BooleanArray(12) { random.nextBoolean() }
            initial.forEachIndexed { index, selected -> engine.setSelectionState(index, selected) }
            val anchor = random.nextInt(12)
            val target = !initial[anchor]
            var previous = initial.copyOf().also { it[anchor] = target }
            assertTrue(engine.startDragSelection(anchor))
            repeat(10) {
                val endpoint = random.nextInt(12)
                val expected = initial.copyOf()
                for (index in minOf(anchor, endpoint)..maxOf(anchor, endpoint)) expected[index] =
                    target
                val changed = engine.applyDragRange(endpoint)
                assertEquals(!previous.contentEquals(expected), changed)
                expected.forEachIndexed { index, selected ->
                    assertEquals(
                        selected,
                        engine.isSelected(index)
                    )
                }
                assertEquals(expected.any { it }, engine.hasSelection())
                previous = expected
            }
            engine.endDragSelection()
        }
    }

    /**
     * 建立两行变高布局。
     * @return 用于边界查询的引擎。
     */
    private fun geometry(): TokenSelectionEngine {
        val engine = TokenSelectionEngine()
        engine.setTokens(listOf("a", "b", "c", "d"))
        val heights = floatArrayOf(10f, 30f, 10f, 40f)
        engine.calculateLayout(
            60f,
            0f,
            0f,
            10f,
            5f,
            TokenTextMeasurer { index, _, _ -> TokenTextMetrics(20f, heights[index], 8f, false) })
        return engine
    }

    /**
     * 使用简单、受宽度约束的测量布局。
     * @param engine 待布局引擎。
     * @param width 可用宽度。
     */
    private fun layout(engine: TokenSelectionEngine, width: Float) {
        engine.calculateLayout(
            width,
            0f,
            0f,
            4f,
            4f,
            TokenTextMeasurer { _, _, maximum ->
                TokenTextMetrics(
                    minOf(20f, maximum),
                    20f,
                    15f,
                    false
                )
            })
    }
}
