package com.unscientificjszhai.scantoinput.widget

import android.app.Application
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import com.unscientificjszhai.scantoinput.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** 通过实际平台 provider 验证文本、虚拟身份、焦点和选择操作。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TokenSelectionAccessibilityTest {
    /** 节点文本和边界来自生产缓存，两次点击与触摸选择语义一致。 */
    @Test
    fun providerClickTogglesTextStateAndNotifiesExactlyOnce() {
        val f = TokenSelectionTestFixture(listOf("Hello", "世界"))
        val provider = f.view.accessibilityNodeProvider!!
        val initial = provider.createAccessibilityNodeInfo(1)!!
        assertEquals("Hello", initial.text.toString())
        assertTrue(initial.isCheckable && initial.isClickable && initial.isFocusable)
        assertFalse(initial.isSelected || initial.isChecked)
        val bounds = Rect()
        initial.getBoundsInParent(bounds)
        assertEquals(f.bounds(0), bounds)
        assertEquals(0, initial.collectionItemInfo!!.rowIndex)
        var notifications = 0
        f.view.onSelectionChangedListener = { notifications++ }
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("Hello", f.view.getSelectedText())
        assertTrue(f.view.hasSelection())
        val selected = provider.createAccessibilityNodeInfo(1)!!
        assertTrue(selected.isChecked && selected.isSelected)
        assertEquals(1, notifications)
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertFalse(f.view.hasSelection())
        assertEquals(2, notifications)
        f.tap(0)
        assertTrue(provider.createAccessibilityNodeInfo(1)!!.isChecked)
        assertEquals(3, notifications)
    }

    /** Unicode 空白说明覆盖 NEL，原始 node.text 不被说明文字替换。 */
    @Test
    fun whitespaceIncludingNelAndEmptyTokensRemainReadableAndExact() {
        val tokens = listOf("", " \t\r\n", "\u0085", " \u0085\u2028", "A\u0085")
        val f = TokenSelectionTestFixture(tokens)
        val provider = f.view.accessibilityNodeProvider!!
        for (index in tokens.indices) {
            val node = provider.createAccessibilityNodeInfo(index + 1)!!
            assertEquals(tokens[index], node.text.toString())
            if (index < tokens.lastIndex) assertEquals(
                f.activity.getString(R.string.token_accessibility_whitespace),
                node.contentDescription
            )
            else assertNull(node.contentDescription)
            assertTrue(provider.performAction(index + 1, AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        assertTrue(f.view.hasSelection())
        assertEquals(tokens.joinToString(""), f.view.getSelectedText())
        f.view.setTokens(emptyList())
        f.advance(64)
        val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        assertEquals(
            f.activity.getString(R.string.token_selection_placeholder),
            host.text.toString()
        )
        assertEquals(0, host.childCount)
    }

    /** 滚动、显示目标和键盘焦点都使真正父容器移动，并更新可见节点集合。 */
    @Test
    fun hostScrollShowOnScreenAndKeyboardFocusMoveRealViewport() {
        val f = TokenSelectionTestFixture(List(1000) { "token$it" })
        val provider = f.view.accessibilityNodeProvider!!
        val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        assertTrue(host.isScrollable)
        assertTrue(host.childCount in 1..100)
        assertTrue(
            provider.performAction(
                View.NO_ID,
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                null
            )
        )
        assertTrue(f.scroll.scrollY > 0)
        assertTrue(
            provider.performAction(
                View.NO_ID,
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                null
            )
        )
        assertEquals(0, f.scroll.scrollY)
        assertFalse(
            provider.performAction(
                View.NO_ID,
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                null
            )
        )
        assertTrue(
            provider.performAction(
                901,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id,
                null
            )
        )
        assertTrue(f.scroll.scrollY > 0)
        val viewport = f.viewport()
        val bounds = f.bounds(900)
        assertTrue(bounds.bottom > viewport.top && bounds.top < viewport.bottom)
        assertFalse(provider.createAccessibilityNodeInfo(1)!!.isVisibleToUser)
        assertTrue(f.view.requestFocusFromTouch())
        provider.performAction(1, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, null)
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_FOCUS, null))
        assertTrue(f.scroll.scrollY < 100)
        assertTrue(f.bounds(0).bottom > f.viewport().top)
        assertTrue(f.bounds(0).top < f.viewport().bottom)
        assertNotNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        assertTrue(
            f.view.dispatchKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_RIGHT
                )
            )
        )
        assertTrue(f.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(f.view.hasSelection())
    }

    /** 重排保留身份，但列表替换后旧查询和动作都不能命中新列表同位置。 */
    @Test
    fun replacingTokensInvalidatesOldProviderIdsWhileWidthRelayoutKeepsThem() {
        val f = TokenSelectionTestFixture(listOf("old".repeat(100), "second"))
        val provider = f.view.accessibilityNodeProvider!!
        val oldId = 1
        val original = provider.createAccessibilityNodeInfo(oldId)!!
        val originalBounds = Rect().also { original.getBoundsInParent(it) }
        f.view.measure(
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        f.view.layout(0, 0, 180, f.view.measuredHeight)
        val resized = provider.createAccessibilityNodeInfo(oldId)!!
        assertEquals(original.text, resized.text)
        val resizedBounds = Rect().also { resized.getBoundsInParent(it) }
        assertNotEquals(originalBounds, resizedBounds)
        assertTrue(f.view.requestFocusFromTouch())
        provider.performAction(oldId, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, null)
        assertTrue(provider.performAction(oldId, AccessibilityNodeInfo.ACTION_FOCUS, null))
        f.view.setTokens(listOf("replacement", "new tail"))
        f.advance(64)
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        val current = provider.createAccessibilityNodeInfo(3)!!
        assertEquals("replacement", current.text.toString())
        var notifications = 0
        f.view.onSelectionChangedListener = { notifications++ }
        val scroll = f.scroll.scrollY
        for (stale in listOf(oldId, 2, 0, 999999)) {
            assertNull(provider.createAccessibilityNodeInfo(stale))
            assertFalse(provider.performAction(stale, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(
                provider.performAction(
                    stale,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id,
                    null
                )
            )
        }
        assertFalse(provider.performAction(3, AccessibilityNodeInfo.ACTION_SET_TEXT, null))
        assertFalse(f.view.hasSelection())
        assertEquals(0, notifications)
        assertEquals(scroll, f.scroll.scrollY)
        assertTrue(provider.performAction(3, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("replacement", f.view.getSelectedText())
        assertEquals(1, notifications)
    }

    /** 分配空间耗尽后拒绝新节点，不回绕到任何已使用的正整数身份。 */
    @Test
    fun idExhaustionNeverReusesOldIdentity() {
        val f = TokenSelectionTestFixture(listOf("one", "two", "three"))
        val helper = TokenSelectionAccessibilityHelper(f.view, Int.MAX_VALUE - 1)
        ViewCompat.setAccessibilityDelegate(f.view, helper)
        helper.replaceTokens()
        val provider = f.view.accessibilityNodeProvider!!
        assertEquals(
            "one",
            provider.createAccessibilityNodeInfo(Int.MAX_VALUE - 1)!!.text.toString()
        )
        assertEquals("two", provider.createAccessibilityNodeInfo(Int.MAX_VALUE)!!.text.toString())
        assertNull(provider.createAccessibilityNodeInfo(1))
        assertFalse(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        helper.clearTokens()
        helper.replaceTokens()
        assertNull(provider.createAccessibilityNodeInfo(Int.MAX_VALUE))
        assertFalse(provider.performAction(Int.MAX_VALUE, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(0, provider.createAccessibilityNodeInfo(View.NO_ID)!!.childCount)
        assertFalse(f.view.hasSelection())
    }

    /** 触摸探索由真实 helper 接管；旧无障碍焦点在列表替换前清理。 */
    @Test
    fun hoverAndAccessibilityFocusUseCurrentVirtualNodes() {
        val f = TokenSelectionTestFixture(listOf("first", "second"))
        val manager = f.activity.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        val provider = f.view.accessibilityNodeProvider!!
        assertTrue(
            provider.performAction(
                1,
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null
            )
        )
        assertNotNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, 5f, 5f, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(f.view.dispatchGenericMotionEvent(event))
        } finally {
            event.recycle()
        }
        val exit = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_EXIT, 5f, 5f, 0)
        exit.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(f.view.dispatchGenericMotionEvent(exit))
        } finally {
            exit.recycle()
        }
        f.view.setTokens(listOf("replacement"))
        f.advance(64)
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        assertFalse(
            provider.performAction(
                1,
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null
            )
        )
        assertEquals("replacement", provider.createAccessibilityNodeInfo(3)!!.text.toString())
    }
}
