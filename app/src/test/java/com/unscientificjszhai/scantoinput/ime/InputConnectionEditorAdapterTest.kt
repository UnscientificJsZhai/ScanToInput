package com.unscientificjszhai.scantoinput.ime

import android.app.Application
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 使用真实 Editable 和平台 SurroundingText 验证输入适配器的安全边界。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37], application = Application::class)
class InputConnectionEditorAdapterTest {
    /** 真实 BaseInputConnection 的非零 offset 必须保留绝对位置和 UTF-16 长度。 */
    @Test
    fun editableSnapshotsKeepAbsoluteOffsetsAndEmojiLengths() {
        val connection = EditableInputConnection("prefix😀")
        val adapter = InputConnectionEditorAdapter(connection)
        assertSame(connection, adapter.identity)
        assertEquals(EditorSnapshot("😀", 8, 8), adapter.snapshot(2))
        assertTrue(adapter.commit("中"))
        assertEquals(EditorSnapshot("😀中", 9, 9), adapter.snapshot(3))
        assertTrue(adapter.deleteBefore(3))
        assertEquals("prefix", connection.content.toString())
    }

    /** 替换真实选中文本允许输入，但删除式撤销不能恢复原文，因此关闭撤销。 */
    @Test
    fun selectionReplacementCannotCreateUnsafeUndo() {
        val connection = EditableInputConnection("old text")
        Selection.setSelection(connection.content, 0, 3)
        val session = ImeInputSession()
        session.startEditor(connection)
        assertTrue(session.commit(InputConnectionEditorAdapter(connection), "new", true) { true })
        assertEquals("new text", connection.content.toString())
        assertFalse(session.canUndo())
        assertFalse(session.undo(InputConnectionEditorAdapter(connection)) { true })
        assertEquals("new text", connection.content.toString())
    }

    /** 默认自定义连接的 offset=-1 和非法边界都不能被误当作可信快照。 */
    @Test
    fun unknownOffsetsMalformedBoundsAndExceptionsAreRejected() {
        val snapshots = listOf(
            null, SurroundingText("abc", 3, 3, -1), SurroundingText("abc", -1, 0, 0),
            SurroundingText("abc", 0, 4, 0), SurroundingText("abc", 4, 0, 0),
            SurroundingText("abc", 0, -1, 0), SurroundingText("abc", 3, 3, Int.MAX_VALUE)
        )
        for (snapshot in snapshots) {
            val connection = stub { method -> if (method == "getSurroundingText") snapshot else true }
            val adapter = InputConnectionEditorAdapter(connection)
            assertNull(adapter.snapshot(3))
            assertTrue(adapter.commit("still allowed"))
        }
        val throwing = InputConnectionEditorAdapter(stub { throw IllegalStateException("unsupported") })
        assertNull(throwing.snapshot(0))
        assertFalse(throwing.commit("x"))
        assertFalse(throwing.deleteBefore(1))
    }

    /** 多层真实 Editable 撤销保留末次成功扫码记录。 */
    @Test
    fun multiLevelUndoWithRealEditable() {
        val connection = EditableInputConnection("prefix")
        val adapter = InputConnectionEditorAdapter(connection)
        val session = ImeInputSession()
        session.startEditor(connection)
        assertTrue(session.commit(adapter, "😀", true) { true })
        assertTrue(session.commit(adapter, "\n", false) { true })
        session.onSelection(connection, 6, 6, 9, 9, -1, -1)
        assertTrue(session.undo(adapter) { true })
        assertTrue(session.undo(adapter) { true })
        assertEquals("prefix", connection.content.toString())
        assertEquals("😀", session.lastSuccessfulScanText)
    }

    /**
     * 创建只替换平台返回值的连接，不实现任何输入法规则。
     * @param response 按方法名称提供返回值。
     * @return 平台连接代理。
     */
    private fun stub(response: (String) -> Any?): InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
    ) { _, method, _ -> response(method.name) } as InputConnection
}

/**
 * 在真实 BaseInputConnection 中编辑内存 Editable，可控制平台操作的返回值。
 * @param initial 初始编辑内容。
 */
internal class EditableInputConnection(initial: String = "") : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
    val content = SpannableStringBuilder(initial).also { Selection.setSelection(it, it.length) }
    var commits = 0
    var deletes = 0
    val actions = mutableListOf<Int>()
    var acceptCommit = true
    var acceptDelete = true
    var acceptAction = true
    var afterCommit: (() -> Unit)? = null
    override fun getEditable(): Editable = content
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        commits++
        if (!acceptCommit) return false
        return super.commitText(text, newCursorPosition).also { afterCommit?.invoke() }
    }
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        deletes++
        return acceptDelete && super.deleteSurroundingText(beforeLength, afterLength)
    }
    override fun performEditorAction(actionCode: Int): Boolean {
        actions.add(actionCode)
        return acceptAction
    }
}
