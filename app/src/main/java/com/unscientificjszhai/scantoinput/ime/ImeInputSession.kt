package com.unscientificjszhai.scantoinput.ime

/**
 * 编辑器在一次读取中返回的绝对选区及光标前文本。
 * @property beforeCursor 选区起点之前实际取得的文本。
 * @property selectionStart 绝对选区起点，单位为 UTF-16。
 * @property selectionEnd 绝对选区终点，单位为 UTF-16。
 */
internal data class EditorSnapshot(val beforeCursor: String, val selectionStart: Int, val selectionEnd: Int)

/** 隔离 Android InputConnection 的文本操作边界。 */
internal interface EditorConnection {
    val identity: Any

    /**
     * 读取具有可信绝对位置的选区快照。
     * @param beforeLength 所需光标前 UTF-16 长度。
     * @return 可确认位置的快照；编辑器不支持时返回 null。
     */
    fun snapshot(beforeLength: Int): EditorSnapshot?

    /**
     * 在当前位置提交文本。
     * @param text 待提交文本。
     * @return 编辑器是否接受提交。
     */
    fun commit(text: String): Boolean

    /**
     * 删除光标前指定长度。
     * @param length UTF-16 长度。
     * @return 编辑器是否接受删除。
     */
    fun deleteBefore(length: Int): Boolean
}

/**
 * 已证实为纯插入的撤销记录。
 * @property generation 编辑会话身份。
 * @property text 实际插入文本。
 * @property before 插入前绝对光标位置。
 * @property after 插入后绝对光标位置。
 */
internal data class UndoInsertion(val generation: Long, val text: String, val before: Int, val after: Int)

/**
 * 本服务发起、等待编辑器通知的选区变化。每次操作保留独立对象身份，不按位置比较相等。
 * @property before 操作前光标位置。
 * @property after 操作后光标位置。
 */
internal class SelectionTransition(val before: Int, val after: Int)

/**
 * 管理编辑身份、安全撤销和 Service 实例级扫码去重；不依赖 Android。
 * 所有方法由输入法主线程调用，异步系统选区通知可在编辑操作中重入。
 */
internal class ImeInputSession {
    var generation: Long = 0
        private set
    var editorIdentity: Any? = null
        private set
    var lastSuccessfulScanText: String? = null
        private set
    private val undo = ArrayDeque<UndoInsertion>()
    private val pending = ArrayDeque<SelectionTransition>()
    private var revision = 0L
    private var latestCaret: Int? = null
    private var composing = false

    /**
     * 开始一个编辑会话。重启和连接切换也失效旧撤销，但保留扫码去重。
     * @param identity 当前底层连接身份；无编辑器时为 null。
     */
    fun startEditor(identity: Any?) {
        generation++
        editorIdentity = identity
        composing = false
        invalidateUndo()
    }

    /** 清除当前编辑历史；不改变扫码去重。 */
    fun invalidateUndo() {
        undo.clear()
        pending.clear()
        latestCaret = null
        revision++
    }

    /** 显式允许再次输入相同扫码内容。 */
    fun nextScan() {
        lastSuccessfulScanText = null
    }

    /**
     * 查询是否存在仍可尝试的撤销记录。
     * @return 是否存在记录；删除之前仍需重新验证编辑器。
     */
    fun canUndo(): Boolean = undo.isNotEmpty()

    /**
     * 提交文本并仅为可证实的纯插入创建撤销记录。
     * @param editor 当前连接适配器。
     * @param text 待插入文本。
     * @param scanned 是否为扫码输入，空格和换行传 false。
     * @param isCurrent 在每次编辑器调用后验证外部会话与连接仍有效。
     * @return 是否已成功提交到仍有效的编辑会话。
     */
    fun commit(editor: EditorConnection, text: String, scanned: Boolean, isCurrent: () -> Boolean): Boolean {
        if (editor.identity !== editorIdentity || !isCurrent()) return false
        if (scanned && text == lastSuccessfulScanText) return false
        val operationGeneration = generation
        val before = editor.snapshot(0)
        if (!isCurrent() || generation != operationGeneration) return false
        val afterPosition = before?.selectionStart?.toLong()?.plus(text.length)
        val trackable = before != null && before.selectionStart == before.selectionEnd &&
            !composing && afterPosition!! <= Int.MAX_VALUE
        val previousCaret = latestCaret
        if (!trackable || (previousCaret != null && previousCaret != before.selectionStart)) invalidateUndo()
        val transition = if (trackable) SelectionTransition(before.selectionStart, afterPosition.toInt()) else null
        val operationRevision = revision
        if (transition != null) {
            pending.addLast(transition)
            latestCaret = transition.after
        }
        val accepted = editor.commit(text)
        if (!isCurrent() || generation != operationGeneration) return false
        if (!accepted) {
            invalidateUndo()
            return false
        }
        val after = editor.snapshot(text.length)
        if (!isCurrent() || generation != operationGeneration) return false
        if (operationRevision == revision && transition != null && matches(after, transition.after, text)) {
            undo.addLast(UndoInsertion(generation, text, transition.before, transition.after))
        } else {
            invalidateUndo()
        }
        if (scanned) lastSuccessfulScanText = text
        return true
    }

    /**
     * 仅在编辑会话、绝对光标和完整后缀都匹配时删除最后一次插入。
     * @param editor 当前连接适配器。
     * @param isCurrent 外部会话与连接是否仍有效。
     * @return 编辑器是否成功完成了本次安全删除。
     */
    fun undo(editor: EditorConnection, isCurrent: () -> Boolean): Boolean {
        val record = undo.lastOrNull() ?: return false
        if (editor.identity !== editorIdentity) return false
        val operationRevision = revision
        val before = editor.snapshot(record.text.length)
        val currentBeforeDelete = isCurrent()
        if (operationRevision != revision) return false
        if (!currentBeforeDelete || !matches(before, record.after, record.text)) {
            invalidateUndo()
            return false
        }
        val transition = SelectionTransition(record.after, record.before)
        pending.addLast(transition)
        latestCaret = record.before
        val accepted = editor.deleteBefore(record.text.length)
        if (!isCurrent() || operationRevision != revision) return false
        if (!accepted) {
            pending.remove(transition)
            latestCaret = record.after
            return false
        }
        undo.removeLast()
        val remainingText = undo.lastOrNull()?.text ?: ""
        val after = editor.snapshot(remainingText.length)
        val currentAfterDelete = isCurrent()
        if (operationRevision != revision) return true
        if (!currentAfterDelete || !matches(after, record.before, remainingText)) invalidateUndo()
        return true
    }

    /**
     * 消费本服务预先登记的选区通知；其余变化立即失效撤销。
     * @param identity 产生通知时的底层连接身份。
     * @param oldStart 旧选区起点。
     * @param oldEnd 旧选区终点。
     * @param newStart 新选区起点。
     * @param newEnd 新选区终点。
     * @param composingStart 组合文本起点，未组合为 -1。
     * @param composingEnd 组合文本终点，未组合为 -1。
     */
    fun onSelection(identity: Any?, oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int,
                    composingStart: Int, composingEnd: Int) {
        composing = composingStart >= 0 || composingEnd >= 0
        if (identity !== editorIdentity || composing || oldStart < 0 || newStart < 0 ||
            oldStart != oldEnd || newStart != newEnd) {
            invalidateUndo()
            return
        }
        if (pending.isEmpty() && oldStart == newStart && newStart == latestCaret) return
        var cursor = oldStart
        var count = 0
        for (transition in pending) {
            if (transition.before != cursor) break
            cursor = transition.after
            count++
            if (cursor == newStart) {
                repeat(count) { pending.removeFirst() }
                return
            }
        }
        invalidateUndo()
    }

    /**
     * 检查一次快照是否完整证明当前绝对光标和文本后缀。
     * @param snapshot 编辑器读取结果。
     * @param caret 期望的绝对光标位置。
     * @param suffix 期望的完整后缀。
     * @return 快照是否匹配。
     */
    private fun matches(snapshot: EditorSnapshot?, caret: Int, suffix: String): Boolean =
        snapshot != null && snapshot.selectionStart == caret && snapshot.selectionEnd == caret &&
            snapshot.beforeCursor.endsWith(suffix)
}
