package com.unscientificjszhai.scantoinput.ime

import android.view.inputmethod.InputConnection

/**
 * 将 Android 编辑器查询结果转成可确认绝对位置的纯数据。
 * @property connection 底层编辑器连接，也是会话身份。
 */
internal class InputConnectionEditorAdapter(private val connection: InputConnection) : EditorConnection {
    override val identity: Any get() = connection

    override fun snapshot(beforeLength: Int): EditorSnapshot? = try {
        val surrounding = connection.getSurroundingText(beforeLength, 0, 0)
        if (surrounding == null || surrounding.offset < 0 ||
            surrounding.selectionStart !in 0..surrounding.text.length ||
            surrounding.selectionEnd !in 0..surrounding.text.length ||
            surrounding.offset.toLong() + maxOf(surrounding.selectionStart, surrounding.selectionEnd) > Int.MAX_VALUE) {
            null
        } else {
            EditorSnapshot(
                surrounding.text.subSequence(0, surrounding.selectionStart).toString(),
                surrounding.offset + surrounding.selectionStart,
                surrounding.offset + surrounding.selectionEnd
            )
        }
    } catch (_: Exception) {
        // 自定义编辑器不支持或拒绝快照时，仍允许普通输入，但不允许猜测撤销范围。
        null
    }

    override fun commit(text: String): Boolean = try {
        connection.commitText(text, 1)
    } catch (_: Exception) {
        false
    }

    override fun deleteBefore(length: Int): Boolean = try {
        connection.deleteSurroundingText(length, 0)
    } catch (_: Exception) {
        false
    }
}
