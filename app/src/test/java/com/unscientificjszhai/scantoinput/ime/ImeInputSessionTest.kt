package com.unscientificjszhai.scantoinput.ime

import org.junit.Assert.*
import org.junit.Test

/** 验证会话身份、编辑返回值和有序选区通知，而不依赖 Android。 */
class ImeInputSessionTest {
    /** 失败输入可重试，成功去重跨编辑器保留，只有显式下一个扫码清除。 */
    @Test
    fun successfulScanAloneUpdatesServiceLifetimeDedup() {
        val fixture = Fixture()
        val editor = fixture.editor
        editor.acceptCommit = false
        assertFalse(fixture.commit("A"))
        assertNull(fixture.session.lastSuccessfulScanText)
        editor.acceptCommit = true
        assertTrue(fixture.commit("A"))
        assertFalse(fixture.commit("A"))
        assertTrue(fixture.session.undo(editor) { true })
        assertFalse(fixture.commit("A"))
        fixture.session.startEditor(null)
        fixture.session.startEditor(editor.identity)
        assertFalse(fixture.commit("A"))
        fixture.session.nextScan()
        assertTrue(fixture.commit("A"))
        assertTrue(fixture.commit(" ", false))
        assertEquals("A", fixture.session.lastSuccessfulScanText)
    }

    /** 连续提交允许逐层撤销，延迟的单次通知和合并通知都不回退最新光标。 */
    @Test
    fun delayedAndCoalescedSelfSelectionsPreserveUndo() {
        for (coalesced in listOf(false, true)) {
            val fixture = Fixture()
            assertTrue(fixture.commit("1234"))
            assertTrue(fixture.commit(" ", false))
            if (coalesced) fixture.selection(0, 5) else {
                fixture.selection(0, 4)
                fixture.selection(4, 5)
            }
            fixture.selection(5, 5)
            assertTrue(fixture.session.canUndo())
            assertTrue(fixture.session.undo(fixture.editor) { true })
            fixture.selection(5, 4)
            assertEquals("1234", fixture.editor.text)
            assertTrue(fixture.session.undo(fixture.editor) { true })
            fixture.selection(4, 0)
            assertFalse(fixture.session.canUndo())
            assertFalse(fixture.session.undo(fixture.editor) { true })
        }
    }

    /** 同步重入的自更新通知在编辑器返回之前也能正确消费。 */
    @Test
    fun synchronousSelectionIsRegisteredBeforeCommitAndDelete() {
        val fixture = Fixture()
        fixture.editor.onMutation = fixture::selection
        assertTrue(fixture.commit("😀"))
        assertTrue(fixture.session.canUndo())
        assertTrue(fixture.session.undo(fixture.editor) { true })
        assertEquals("", fixture.editor.text)
    }

    /** 同样的文本位于另一绝对位置或连接中时不得删除。 */
    @Test
    fun undoChecksAbsolutePositionAndConnection() {
        val fixture = Fixture()
        fixture.commit("same")
        fixture.editor.text = "samesame"
        fixture.editor.start = 8
        fixture.editor.end = 8
        assertFalse(fixture.session.undo(fixture.editor) { true })
        assertEquals(0, fixture.editor.deletes)
        fixture.commit("again")
        val other = Editor().apply { text = fixture.editor.text; start = text.length; end = start }
        assertFalse(fixture.session.undo(other) { true })
        assertEquals(0, other.deletes)
        fixture.session.startEditor(fixture.editor.identity)
        fixture.commit("later")
        fixture.session.startEditor(fixture.editor.identity)
        assertFalse(fixture.session.canUndo())
    }

    /** 未知快照、替换选区和已知组合文本都允许输入，但不得生成删除式撤销。 */
    @Test
    fun untrustedOrReplacingEditsRemainSuccessfulWithoutUndo() {
        val unknown = Fixture()
        unknown.editor.snapshotsAvailable = false
        assertTrue(unknown.commit("unknown"))
        assertEquals("unknown", unknown.session.lastSuccessfulScanText)
        assertFalse(unknown.session.canUndo())
        val selected = Fixture()
        selected.editor.text = "original"
        selected.editor.end = 8
        assertTrue(selected.commit("replacement"))
        assertFalse(selected.session.canUndo())
        assertEquals("replacement", selected.editor.text)
        val composing = Fixture()
        composing.session.onSelection(composing.editor.identity, 0, 0, 0, 0, 0, 1)
        assertTrue(composing.commit("composition"))
        assertFalse(composing.session.canUndo())
        composing.selection(11, 11)
        assertTrue(composing.commit("finished"))
        assertTrue(composing.session.canUndo())
    }

    /** 编辑器变换文本、截短快照或移动到不同选区会使撤销关闭。 */
    @Test
    fun postCommitMustProveExactInsertion() {
        for (mode in 0..3) {
            val fixture = Fixture()
            fixture.editor.afterCommit = {
                when (mode) {
                    0 -> fixture.editor.text = "changed"
                    1 -> fixture.editor.snapshotsAvailable = false
                    2 -> fixture.editor.start = 0
                    3 -> fixture.editor.end = 0
                }
            }
            assertTrue(fixture.commit("ABC"))
            assertFalse(fixture.session.canUndo())
        }
    }

    /** 删除失败保留记录和扫码去重；再次操作时仍核对真实编辑器。 */
    @Test
    fun failedDeleteRetainsRetryableRecord() {
        val fixture = Fixture()
        fixture.commit("A")
        fixture.editor.acceptDelete = false
        assertFalse(fixture.session.undo(fixture.editor) { true })
        assertTrue(fixture.session.canUndo())
        assertEquals("A", fixture.session.lastSuccessfulScanText)
        fixture.editor.acceptDelete = true
        assertTrue(fixture.session.undo(fixture.editor) { true })
        assertEquals(2, fixture.editor.deletes)
    }

    /** 重复经过相同位置时，失败删除只能取消本次操作的通知身份。 */
    @Test
    fun failedDeleteCancelsItsOwnTransitionIdentity() {
        val fixture = Fixture()
        fixture.commit("x", false)
        assertTrue(fixture.session.undo(fixture.editor) { true })
        fixture.commit("x", false)
        fixture.editor.acceptDelete = false
        assertFalse(fixture.session.undo(fixture.editor) { true })
        fixture.selection(0, 1)
        fixture.selection(1, 0)
        fixture.selection(0, 1)
        assertTrue(fixture.session.canUndo())
        fixture.editor.acceptDelete = true
        assertTrue(fixture.session.undo(fixture.editor) { true })
        assertEquals("", fixture.editor.text)
    }

    /** 声称失败却更改内容的编辑器，在下次核对时必须失效历史。 */
    @Test
    fun lyingFailureCannotDeleteUnrelatedTextOnRetry() {
        val fixture = Fixture()
        fixture.commit("A")
        fixture.editor.acceptDelete = false
        fixture.session.undo(fixture.editor) { true }
        fixture.editor.text = "B"
        assertFalse(fixture.session.undo(fixture.editor) { true })
        assertFalse(fixture.session.canUndo())
        assertEquals(1, fixture.editor.deletes)
    }

    /** 删除后快照不确定时保留已成功删除的事实，但清空更早记录。 */
    @Test
    fun uncertainPostDeleteInvalidatesRemainingHistory() {
        val fixture = Fixture()
        fixture.commit("A")
        fixture.commit("B")
        fixture.editor.afterDelete = { fixture.editor.snapshotsAvailable = false }
        assertTrue(fixture.session.undo(fixture.editor) { true })
        assertFalse(fixture.session.canUndo())
    }

    /** 外部选择、负位置、未知连接和组合范围不能伪装成延迟自更新。 */
    @Test
    fun externalSelectionsInvalidateHistory() {
        val updates = listOf(
            listOf(0, 0, 2, 2, -1, -1), listOf(-1, -1, 1, 1, -1, -1),
            listOf(0, 0, -1, -1, -1, -1), listOf(0, 1, 1, 1, -1, -1),
            listOf(0, 0, 0, 1, -1, -1), listOf(0, 0, 1, 1, 0, -1),
            listOf(0, 0, 1, 1, -1, 1), listOf(8, 8, 9, 9, -1, -1)
        )
        for (update in updates) {
            val fixture = Fixture()
            fixture.commit("A")
            fixture.session.onSelection(fixture.editor.identity, update[0], update[1], update[2], update[3], update[4], update[5])
            assertFalse(fixture.session.canUndo())
        }
        val fixture = Fixture()
        fixture.commit("A")
        fixture.session.onSelection(Any(), 0, 0, 1, 1, -1, -1)
        assertFalse(fixture.session.canUndo())
        fixture.commit("B")
        fixture.selection(1, 2)
        fixture.selection(2, 3)
        assertFalse(fixture.session.canUndo())
    }

    /** 新提交前的真实光标变动会截断旧的多级撤销链。 */
    @Test
    fun movedCaretStartsNewUndoChain() {
        val fixture = Fixture()
        fixture.commit("AB")
        fixture.editor.start = 0
        fixture.editor.end = 0
        fixture.commit("C")
        assertTrue(fixture.session.undo(fixture.editor) { true })
        assertFalse(fixture.session.canUndo())
        assertEquals("AB", fixture.editor.text)
    }

    /** 每个编辑器调用边界之后都重验会话，不能发布过期提交状态。 */
    @Test
    fun commitRevalidatesAtEveryExternalBoundary() {
        val mismatch = Fixture()
        assertFalse(mismatch.session.commit(Editor(), "x", true) { true })
        assertFalse(mismatch.session.commit(mismatch.editor, "x", true) { false })
        for (boundary in 1..3) {
            val fixture = Fixture()
            var checks = 0
            assertFalse(fixture.session.commit(fixture.editor, "x", true) { ++checks <= boundary })
            assertNull(fixture.session.lastSuccessfulScanText)
        }
        for (boundary in 0..2) {
            val fixture = Fixture()
            var reads = 0
            if (boundary == 1) fixture.editor.afterCommit = { fixture.session.startEditor(fixture.editor.identity) }
            else fixture.editor.onSnapshot = {
                if (reads++ == boundary / 2) fixture.session.startEditor(fixture.editor.identity)
            }
            assertFalse(fixture.commit("old"))
            assertNull(fixture.session.lastSuccessfulScanText)
        }
    }

    /** 外部通知在 commit 内重入时，提交可成功但不能建立不可信撤销。 */
    @Test
    fun unexpectedReentrantSelectionDisablesUndoOnly() {
        val fixture = Fixture()
        fixture.editor.afterCommit = { fixture.selection(0, 99) }
        assertTrue(fixture.commit("x"))
        assertFalse(fixture.session.canUndo())
        assertEquals("x", fixture.session.lastSuccessfulScanText)
    }

    /** 撤销的前后边界失效时必须停止推进历史。 */
    @Test
    fun undoRevalidatesAtEveryExternalBoundary() {
        for (boundary in 0..1) {
            val fixture = Fixture()
            fixture.commit("x")
            var checks = 0
            assertFalse(fixture.session.undo(fixture.editor) { ++checks <= boundary })
        }
        val after = Fixture()
        after.commit("x")
        var checks = 0
        assertTrue(after.session.undo(after.editor) { ++checks <= 2 })
        assertFalse(after.session.canUndo())
        val duringRead = Fixture()
        duringRead.commit("x")
        duringRead.editor.onSnapshot = { duringRead.session.invalidateUndo() }
        assertFalse(duringRead.session.undo(duringRead.editor) { true })
        val duringDelete = Fixture()
        duringDelete.commit("x")
        duringDelete.editor.afterDelete = { duringDelete.session.invalidateUndo() }
        assertFalse(duringDelete.session.undo(duringDelete.editor) { true })
    }

    /** 旧撤销的前后快照切换到新编辑器后，不得清除新会话已经建立的记录。 */
    @Test
    fun oldUndoSnapshotCannotInvalidateNewEditorsUndo() {
        for (afterDeletion in listOf(false, true)) {
            val fixture = Fixture()
            fixture.commit("A")
            val next = Editor()
            val switchEditor = {
                fixture.session.startEditor(next.identity)
                assertTrue(fixture.session.commit(next, "B", true) { true })
            }
            if (afterDeletion) fixture.editor.afterDelete = { fixture.editor.onSnapshot = switchEditor }
            else fixture.editor.onSnapshot = switchEditor
            assertEquals(afterDeletion, fixture.session.undo(fixture.editor) { fixture.session.editorIdentity === fixture.editor.identity })
            assertTrue(fixture.session.canUndo())
            assertEquals("B", fixture.session.lastSuccessfulScanText)
            assertFalse(fixture.session.undo(fixture.editor) { true })
            assertTrue(fixture.session.canUndo())
            assertTrue(fixture.session.undo(next) { true })
            assertEquals("", next.text)
        }
    }

    /** 绝对 UTF-16 位置接近上界时仍可输入，但不生成溢出的撤销范围。 */
    @Test
    fun overflowingAbsoluteCaretDisablesUndo() {
        val fixture = Fixture()
        fixture.editor.offset = Int.MAX_VALUE
        fixture.editor.afterCommit = { fixture.editor.snapshotsAvailable = false }
        assertTrue(fixture.commit("x"))
        assertFalse(fixture.session.canUndo())
    }

    /** 删除完成后的快照若收到外部编辑通知，旧的剩余记录立即失效。 */
    @Test
    fun selectionChangedDuringPostDeleteSnapshotInvalidatesRemainder() {
        val fixture = Fixture()
        fixture.commit("A")
        fixture.commit("B")
        fixture.editor.afterDelete = { fixture.editor.onSnapshot = { fixture.session.invalidateUndo() } }
        assertTrue(fixture.session.undo(fixture.editor) { true })
        assertFalse(fixture.session.canUndo())
        val unchanged = Fixture()
        unchanged.commit("A")
        unchanged.selection(0, 1)
        unchanged.selection(3, 3)
        assertFalse(unchanged.session.canUndo())
    }

    /**
     * 绑定一个独立编辑器到纯业务会话。
     * @property session 被测会话。
     * @property editor 内存编辑器，只负责实际文本编辑。
     */
    private class Fixture(val session: ImeInputSession = ImeInputSession(), val editor: Editor = Editor()) {
        init { session.startEditor(editor.identity) }
        /**
         * 提交文本。
         * @param text 待提交文本。
         * @param scanned 是否为扫码文本。
         * @return 是否提交成功。
         */
        fun commit(text: String, scanned: Boolean = true): Boolean = session.commit(editor, text, scanned) { true }
        /**
         * 投递一个无组合范围的折叠选区通知。
         * @param before 操作前位置。
         * @param after 操作后位置。
         */
        fun selection(before: Int, after: Int) = session.onSelection(editor.identity, before, before, after, after, -1, -1)
    }

    /** 执行内存文本编辑，并允许在真实接口返回前触发受控外部事件。 */
    private class Editor : EditorConnection {
        override val identity = Any()
        var text = ""
        var offset = 0
        var start = 0
        var end = 0
        var acceptCommit = true
        var acceptDelete = true
        var snapshotsAvailable = true
        var deletes = 0
        var onMutation: ((Int, Int) -> Unit)? = null
        var afterCommit: (() -> Unit)? = null
        var afterDelete: (() -> Unit)? = null
        var onSnapshot: (() -> Unit)? = null
        override fun snapshot(beforeLength: Int): EditorSnapshot? {
            onSnapshot?.invoke()
            if (!snapshotsAvailable) return null
            return EditorSnapshot(text.take(start).takeLast(beforeLength), start + offset, end + offset)
        }
        override fun commit(text: String): Boolean {
            if (!acceptCommit) return false
            val old = start
            this.text = this.text.substring(0, start) + text + this.text.substring(end)
            start += text.length
            end = start
            onMutation?.invoke(old, start)
            afterCommit?.invoke()
            return true
        }
        override fun deleteBefore(length: Int): Boolean {
            deletes++
            if (!acceptDelete) return false
            val old = start
            text = text.removeRange(start - length, start)
            start -= length
            end = start
            onMutation?.invoke(old, start)
            afterDelete?.invoke()
            return true
        }
    }
}
