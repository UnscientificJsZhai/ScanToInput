package com.unscientificjszhai.scantoinput.widget

import android.graphics.Rect
import android.os.Bundle
import android.util.SparseIntArray
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.unscientificjszhai.scantoinput.R
import com.unscientificjszhai.scantoinput.text.TextProcessingRules

/**
 * 把逻辑 token 映射为不会复用身份的可访问虚拟节点。
 * @param host 实际选词控件。
 * @param firstVirtualId 当前实例起始可用正整数，允许调用方保留先前已占用的身份区间。
 */
internal class TokenSelectionAccessibilityHelper(
    private val host: TokenSelectionView,
    firstVirtualId: Int = 1
) : ExploreByTouchHelper(host) {
    private var nextId = firstVirtualId.toLong()
    private var ids = IntArray(0)
    private var descriptions: Array<String?> = emptyArray()
    private val indices = SparseIntArray()
    private val bounds = Rect()
    private val range = TokenIndexRange()
    private val whitespaceDescription =
        host.context.getString(R.string.token_accessibility_whitespace)
    private var delegateProvider: AccessibilityNodeProviderCompat? = null
    private val guardedProvider = object : AccessibilityNodeProviderCompat() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfoCompat? =
            if (isCurrentOrHost(virtualViewId)) delegateProvider?.createAccessibilityNodeInfo(
                virtualViewId
            ) else null

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean =
            isCurrentOrHost(virtualViewId) && delegateProvider?.performAction(
                virtualViewId,
                action,
                arguments
            ) == true

        override fun findFocus(focus: Int): AccessibilityNodeInfoCompat? =
            delegateProvider?.findFocus(focus)
    }

    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat {
        if (delegateProvider == null) delegateProvider = super.getAccessibilityNodeProvider(host)
        return guardedProvider
    }

    /** 清理旧焦点后才废弃映射，避免晚到动作命中新内容。 */
    fun clearTokens() {
        val keyboard = keyboardFocusedVirtualViewId
        if (keyboard != INVALID_ID) clearKeyboardFocusForVirtualView(keyboard)
        val accessibilityFocus = accessibilityFocusedVirtualViewId
        if (accessibilityFocus != INVALID_ID) {
            getAccessibilityNodeProvider(host).performAction(
                accessibilityFocus,
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
                null
            )
        }
        ids = IntArray(0)
        descriptions = emptyArray()
        indices.clear()
    }

    /** 为新列表创建身份；耗尽 Int 空间后不再发布新可操作节点。 */
    fun replaceTokens() {
        ids = IntArray(host.tokenCount())
        descriptions = arrayOfNulls(ids.size)
        var index = 0
        while (index < ids.size && nextId in 1..Int.MAX_VALUE.toLong()) {
            val id = nextId.toInt()
            nextId++
            ids[index] = id
            indices.put(id, index)
            if (host.tokenText(index).codePoints()
                    .allMatch(TextProcessingRules::isWhitespaceCodePoint)
            ) descriptions[index] = whitespaceDescription
            index++
        }
        invalidateRoot()
    }

    /**
     * 检查 provider 输入身份。
     * @param id 请求的节点身份。
     * @return 是否属于当前列表或宿主。
     */
    private fun isCurrentOrHost(id: Int): Boolean = id == HOST_ID || indices.get(id, -1) >= 0

    /**
     * 查询绘制焦点，供热路径复用整数身份。
     * @param index token 索引。
     * @return token 是否具有键盘或无障碍焦点。
     */
    fun isTokenFocused(index: Int): Boolean {
        if (index < 0 || index >= ids.size) return false
        val id = ids[index]
        return id > 0 && (id == keyboardFocusedVirtualViewId || id == accessibilityFocusedVirtualViewId)
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val index = host.tokenAt(x, y)
        return if (index >= 0 && index < ids.size && ids[index] > 0) ids[index] else INVALID_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        host.visibleTokens(range)
        var index = range.firstIndex
        if (index < 0) return
        while (index <= range.lastIndex && index < ids.size) {
            if (ids[index] > 0) virtualViewIds.add(ids[index])
            index++
        }
    }

    override fun onPopulateNodeForVirtualView(
        virtualViewId: Int,
        node: AccessibilityNodeInfoCompat
    ) {
        val index = indices.get(virtualViewId, -1)
        if (index < 0) {
            node.text = ""
            bounds.set(0, 0, 1, 1)
            node.setBoundsInParent(bounds)
            return
        }
        node.text = host.tokenText(index)
        node.contentDescription = descriptions[index]
        node.className = "android.widget.CheckBox"
        node.isCheckable = true
        node.isChecked = host.tokenSelected(index)
        node.isSelected = node.isChecked
        node.isClickable = true
        node.isFocusable = true
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
        node.setCollectionItemInfo(
            AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                index,
                1,
                0,
                1,
                false,
                node.isChecked
            )
        )
        host.tokenBounds(index, bounds)
        node.setBoundsInParent(bounds)
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?
    ): Boolean {
        val index = indices.get(virtualViewId, -1)
        if (index < 0) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> host.performTokenClick(index)
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id -> host.showToken(
                index
            )

            else -> false
        }
    }

    override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
        if (host.tokenCount() == 0) node.text = host.placeholderText
        val up = host.canScrollResult(-1)
        val down = host.canScrollResult(1)
        node.isScrollable = up || down
        if (up) node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
        if (down) node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> this.host.scrollPage(1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> this.host.scrollPage(-1)
            else -> super.performAccessibilityAction(host, action, args)
        }

    override fun onVirtualViewKeyboardFocusChanged(virtualViewId: Int, hasFocus: Boolean) {
        if (hasFocus) host.showToken(indices.get(virtualViewId, -1))
        host.invalidate()
    }
}
