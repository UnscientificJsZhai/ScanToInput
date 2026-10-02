package com.unscientificjszhai.scantoinput.widget

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** 使用原生文字布局验证换行、字素、尺寸约束及缓存。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TokenSelectionRenderingTest {
    /** 各种换行解释只影响显示缓存，复制始终保留原文。 */
    @Test
    fun lineSeparatorsAndLargeVCardKeepRawTextAndReachableTail() {
        val inputs = listOf(
            "A\nB\n\n", "A\r\nB\r\n\r\n", "A\rB\r\r", "A\u2028B\u2029\u2029",
            "BEGIN:VCARD\r\nNOTE:" + "中文🙂".repeat(500) + "\r\nEND:VCARD"
        )
        for (raw in inputs) {
            val f = TokenSelectionTestFixture(listOf(raw))
            val layout = f.view.cachedTextLayout(0)!!
            assertTrue(layout.lineCount >= 4)
            assertEquals(raw, f.view.getFullText())
            f.view.performTokenClick(0)
            assertEquals(raw, f.view.getSelectedText())
            repeat(30) {
                if (f.scroll.canScrollVertically(1)) {
                    val visible = f.viewport()
                    f.touch(android.view.MotionEvent.ACTION_DOWN, 5f, visible.top + 5f)
                    f.touch(android.view.MotionEvent.ACTION_MOVE, 5f, visible.top - 35f)
                    f.touch(android.view.MotionEvent.ACTION_MOVE, 5f, visible.top - 115f)
                    f.touch(android.view.MotionEvent.ACTION_CANCEL, 5f, visible.top - 115f)
                }
            }
            assertFalse(
                "文本尾部应可以到达 length=${raw.length}, scroll=${f.scroll.scrollY}, height=${f.view.height}",
                f.scroll.canScrollVertically(1)
            )
            assertTrue(f.bounds(0).right <= f.view.width - f.view.paddingRight)
        }
    }

    /** 正常手机宽度下平台断行不切开代理对、组合音标和家庭 emoji。 */
    @Test
    fun nativeLineBoundariesPreserveRepresentativeGraphemesAndRtl() {
        val clusters = listOf("e\u0301", "🙂", "👨‍👩‍👧‍👦", "中")
        val raw = clusters.joinToString(" ").repeat(80)
        val f = TokenSelectionTestFixture(listOf(raw, "العربية 123 שלום", "123"))
        val layout = f.view.cachedTextLayout(0)!!
        assertTrue(layout.lineCount > 1)
        val legalBoundaries = mutableSetOf(0)
        var offset = 0
        while (offset < raw.length) {
            val cluster = clusters.firstOrNull { raw.startsWith(it, offset) } ?: " "
            offset += cluster.length
            legalBoundaries.add(offset)
        }
        for (line in 0 until layout.lineCount) {
            assertTrue("行首必须是完整字素边界", layout.getLineStart(line) in legalBoundaries)
            assertTrue("行尾必须是完整字素边界", layout.getLineEnd(line) in legalBoundaries)
        }
        assertEquals(-1, f.view.cachedTextLayout(1)!!.getParagraphDirection(0))
        assertEquals(raw + "العربية 123 שלום123", f.view.getFullText())
        val neutral = f.view.cachedTextLayout(2)!!
        assertEquals(1, neutral.getParagraphDirection(0))
        val actualWidth = f.view.width
        f.view.layoutDirection = View.LAYOUT_DIRECTION_RTL
        measure(f.view, actualWidth, View.MeasureSpec.EXACTLY, 0, View.MeasureSpec.UNSPECIFIED)
        assertEquals(-1, f.view.cachedTextLayout(1)!!.getParagraphDirection(0))
        assertEquals(-1, f.view.cachedTextLayout(2)!!.getParagraphDirection(0))
        assertNotSame(neutral, f.view.cachedTextLayout(2))
        val canvas = Canvas(Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888))
        f.view.draw(canvas)
    }

    /** 宽高约束、同宽 padding 变化及零宽恢复采用同一布局失效规则。 */
    @Test
    fun dimensionsPaddingAndCacheInvalidationRemainConsistent() {
        val view = TokenSelectionView(ApplicationProvider.getApplicationContext())
        view.setTokens(listOf("word", "word"))
        measure(view, 320, View.MeasureSpec.EXACTLY, 0, View.MeasureSpec.UNSPECIFIED)
        val initialHeight = view.height
        val initial = view.cachedTextLayout(0)
        measure(view, 320, View.MeasureSpec.EXACTLY, 0, View.MeasureSpec.UNSPECIFIED)
        assertSame(initial, view.cachedTextLayout(0))
        view.setPadding(3, 7, 5, 11)
        measure(view, 320, View.MeasureSpec.EXACTLY, 0, View.MeasureSpec.UNSPECIFIED)
        assertEquals(initialHeight + 18, view.height)
        assertNotSame(initial, view.cachedTextLayout(0))
        val withPadding = view.cachedTextLayout(0)
        measure(view, 320, View.MeasureSpec.EXACTLY, 10, View.MeasureSpec.EXACTLY)
        assertEquals(10, view.height)
        assertSame(withPadding, view.cachedTextLayout(0))
        measure(view, 320, View.MeasureSpec.AT_MOST, 12, View.MeasureSpec.AT_MOST)
        assertEquals(12, view.height)
        assertTrue(view.width <= 320)
        measure(view, 180, View.MeasureSpec.EXACTLY, 0, View.MeasureSpec.UNSPECIFIED)
        assertNotSame(withPadding, view.cachedTextLayout(0))
        val beforeTextSize = view.cachedTextLayout(0)!!
        beforeTextSize.paint.textSize += 3f
        view.requestLayout()
        measure(view, 180, View.MeasureSpec.EXACTLY, 0, View.MeasureSpec.UNSPECIFIED)
        assertNotSame(beforeTextSize, view.cachedTextLayout(0))
        val beforeTypeface = view.cachedTextLayout(0)!!
        beforeTypeface.paint.typeface = android.graphics.Typeface.MONOSPACE
        view.requestLayout()
        measure(view, 180, View.MeasureSpec.EXACTLY, 0, View.MeasureSpec.UNSPECIFIED)
        assertNotSame(beforeTypeface, view.cachedTextLayout(0))
        measure(view, 0, View.MeasureSpec.EXACTLY, 0, View.MeasureSpec.UNSPECIFIED)
        assertNull(view.cachedTextLayout(0))
        view.setPadding(0, 0, 0, 0)
        view.setTokens(listOf("", "🙂"))
        measure(view, 1, View.MeasureSpec.EXACTLY, 0, View.MeasureSpec.UNSPECIFIED)
        assertEquals(1, view.cachedTextLayout(1)!!.width)
        val bounds = android.graphics.Rect()
        view.tokenBounds(1, bounds)
        assertEquals(1, bounds.right)
        assertTrue(view.height > 0)
        view.setTokens(emptyList())
        measure(view, 0, View.MeasureSpec.UNSPECIFIED, 0, View.MeasureSpec.UNSPECIFIED)
        assertTrue(view.width > 0 && view.height > 0)
    }

    /** 巨型单 token 仅调用一次背景绘制，文字绘制沿用真实局部裁剪。 */
    @Test
    fun giantTokenUsesOneCachedLayoutAndOneBackgroundUnderClip() {
        val f = TokenSelectionTestFixture(listOf("line\n".repeat(2000)))
        val cached = f.view.cachedTextLayout(0)
        val canvas = CountingCanvas(Bitmap.createBitmap(f.view.width, 200, Bitmap.Config.ARGB_8888))
        canvas.translate(0f, -300f)
        canvas.clipRect(0f, 300f, f.view.width.toFloat(), 500f)
        f.view.draw(canvas)
        assertEquals(1, canvas.backgroundCount)
        assertSame(cached, f.view.cachedTextLayout(0))
        f.view.performTokenClick(0)
        f.view.performTokenClick(0)
        assertSame(cached, f.view.cachedTextLayout(0))
    }

    /**
     * 以明确规格测量和安放测试控件。
     * @param view 生产控件。
     * @param width 宽规格大小。
     * @param widthMode 宽规格模式。
     * @param height 高规格大小。
     * @param heightMode 高规格模式。
     */
    private fun measure(view: View, width: Int, widthMode: Int, height: Int, heightMode: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, widthMode),
            View.MeasureSpec.makeMeasureSpec(height, heightMode)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    /**
     * 只计数应用实际背景调用的原生画布。
     * @param bitmap 像素存储。
     */
    private class CountingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        var backgroundCount = 0
        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
            backgroundCount++
            super.drawRoundRect(rect, rx, ry, paint)
        }
    }
}
