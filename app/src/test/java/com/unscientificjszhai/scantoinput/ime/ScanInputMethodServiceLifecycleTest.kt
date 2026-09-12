package com.unscientificjszhai.scantoinput.ime

import android.Manifest
import android.app.Activity
import android.app.Application
import android.text.Selection
import android.view.View
import android.view.inputmethod.EditorInfo
import com.unscientificjszhai.scantoinput.HiltApplication
import com.unscientificjszhai.scantoinput.R
import com.unscientificjszhai.scantoinput.scanner.ScanResult
import com.unscientificjszhai.scantoinput.scanner.ScannerTestHarness
import com.unscientificjszhai.scantoinput.scanner.TestFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** 使用真实 Hilt 服务创建、XML View 附着和 Editable 验证平台接线。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltApplication::class)
class ScanInputMethodServiceLifecycleTest {
    /** 编辑器连续重启必须完成新 view-start 握手，并保留 Service 级去重。 */
    @Test
    fun editorRestartStopsOldBindingAndWaitsForMatchingViewStart() {
        Fixture().use { fixture ->
            fixture.emit("A")
            val oldAnalyzer = fixture.scanner.provider.analyzer()!!
            val oldFrame = TestFrame()
            oldAnalyzer.analyze(oldFrame.proxy)
            val oldTask = fixture.scanner.recognizer.pending.last()
            fixture.service.onStartInput(fixture.info, true)
            fixture.visibility.notifyActive()
            fixture.visibility.onWindowVisibilityChanged(true)
            assertEquals(1, fixture.scanner.source.requests.size)
            oldTask.setResult(ScanResult.Text("old"))
            assertEquals("A", fixture.editor.content.toString())
            assertEquals(1, oldFrame.closes)
            fixture.service.onStartInputView(fixture.info, true)
            fixture.bindPending()
            fixture.service.onStartInputView(fixture.info, true)
            assertEquals(2, fixture.scanner.source.requests.size)
            fixture.emit("A")
            assertEquals(1, fixture.editor.commits)
            fixture.root.findViewById<View>(R.id.next_scan_button).performClick()
            fixture.emit("A")
            assertEquals("AA", fixture.editor.content.toString())
        }
    }

    /** 真实服务消费延迟合并选区通知后，空格和扫码均可逐层安全撤销。 */
    @Test
    fun selectionCallbacksAndUndoUseAbsoluteEditablePositions() {
        Fixture().use { fixture ->
            fixture.emit("😀")
            fixture.root.findViewById<View>(R.id.space_button).performClick()
            fixture.service.onUpdateSelection(0, 0, 2, 2, -1, -1)
            fixture.service.onUpdateSelection(2, 2, 3, 3, -1, -1)
            val undo = fixture.root.findViewById<View>(R.id.undo_button)
            assertTrue(undo.isEnabled)
            undo.performClick()
            assertEquals("😀", fixture.editor.content.toString())
            fixture.editor.acceptDelete = false
            undo.performClick()
            assertTrue(undo.isEnabled)
            fixture.editor.acceptDelete = true
            undo.performClick()
            assertEquals("", fixture.editor.content.toString())
            assertFalse(undo.isEnabled)
            fixture.emit("😀")
            assertEquals("", fixture.editor.content.toString())
        }
    }

    /** 同样后缀出现在另一位置及另一个连接中时，真实服务不会删除它。 */
    @Test
    fun changedCaretAndConnectionPreventDeletion() {
        Fixture().use { fixture ->
            fixture.emit("same")
            fixture.editor.content.append("same")
            Selection.setSelection(fixture.editor.content, 8)
            fixture.root.findViewById<View>(R.id.undo_button).performClick()
            assertEquals(0, fixture.editor.deletes)
            fixture.emit("new")
            val other = EditableInputConnection("new")
            ReflectionHelpers.setField(fixture.service, "mStartedInputConnection", other)
            fixture.root.findViewById<View>(R.id.undo_button).performClick()
            assertEquals(0, other.deletes)
            assertFalse(fixture.root.findViewById<View>(R.id.undo_button).isEnabled)
            assertFalse(fixture.visibility.isTrulyVisible())
            assertNull(fixture.scanner.provider.analyzer())
        }
    }

    /** 回车按钮遵守全部动作类别、缺少 EditorInfo 和 NO_ENTER_ACTION 标志。 */
    @Test
    fun enterRoutingUsesActualButtonAndEditorActionFlags() {
        Fixture().use { fixture ->
            val newlineOptions = listOf(null, EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            for (options in newlineOptions) {
                ReflectionHelpers.setField(fixture.service, "mInputEditorInfo", options?.let { EditorInfo().apply { imeOptions = it } })
                fixture.root.findViewById<View>(R.id.enter_button).performClick()
            }
            assertEquals("\n\n\n\n", fixture.editor.content.toString())
            assertTrue(fixture.editor.actions.isEmpty())
            for (action in listOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_DONE,
                                  EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS)) {
                ReflectionHelpers.setField(fixture.service, "mInputEditorInfo", EditorInfo().apply { imeOptions = action })
                fixture.root.findViewById<View>(R.id.enter_button).performClick()
                assertEquals(action, fixture.editor.actions.last())
            }
            fixture.editor.acceptAction = false
            fixture.root.findViewById<View>(R.id.enter_button).performClick()
            assertEquals("\n\n\n\n", fixture.editor.content.toString())
            assertFalse(fixture.root.findViewById<View>(R.id.undo_button).isEnabled)
        }
    }

    /** 重试的旧 Runnable 即使被手动完成，也不能越过替代重试或隐藏状态。 */
    @Test
    fun retryCallbacksAreCancelledAndCannotReviveOldView() {
        Fixture().use { fixture ->
            val button = fixture.root.findViewById<View>(R.id.camera_closed_text)
            button.performClick()
            val old = ReflectionHelpers.getField<Runnable>(fixture.service, "retryRunnable")
            fixture.visibility.notifyActive()
            button.performClick()
            val replacement = ReflectionHelpers.getField<Runnable>(fixture.service, "retryRunnable")
            old.run()
            assertSame(replacement, ReflectionHelpers.getField(fixture.service, "retryRunnable"))
            assertEquals(1, fixture.scanner.source.requests.size)
            fixture.service.onFinishInputView(false)
            replacement.run()
            assertEquals(1, fixture.scanner.source.requests.size)
            assertNull(ReflectionHelpers.getField(fixture.service, "retryRunnable"))
            fixture.service.onStartInputView(fixture.info, false)
            fixture.bindPending()
            button.performClick()
            val current = ReflectionHelpers.getField<Runnable>(fixture.service, "retryRunnable")
            current.run()
            fixture.bindPending()
            assertEquals(3, fixture.scanner.source.requests.size)
        }
    }

    /** 共享校验会拒绝空文本；成功提交途中隐藏时不得更新去重或撤销。 */
    @Test
    fun scanValidationAndCommitReentrancyRespectVisibleSession() {
        Fixture().use { fixture ->
            fixture.emit("")
            assertEquals(0, fixture.editor.commits)
            assertEquals(View.VISIBLE, fixture.root.findViewById<View>(R.id.error_hint).visibility)
            fixture.editor.afterCommit = { fixture.service.onFinishInputView(false) }
            fixture.emit("x")
            val session = ReflectionHelpers.getField<ImeInputSession>(fixture.service, "inputSession")
            assertNull(session.lastSuccessfulScanText)
            assertFalse(session.canUndo())
        }
    }

    /** 结束输入后，相同文本在下一连接仍被去重，销毁后旧任务不能提交。 */
    @Test
    fun finishInputAndNewConnectionPreserveDedupButInvalidateUndo() {
        Fixture().use { fixture ->
            fixture.emit("A")
            fixture.service.onFinishInput()
            val next = EditableInputConnection()
            ReflectionHelpers.setField(fixture.service, "mStartedInputConnection", next)
            fixture.service.onStartInput(fixture.info, false)
            fixture.service.onStartInputView(fixture.info, false)
            fixture.bindPending()
            fixture.emit("A")
            assertEquals(0, next.commits)
            assertFalse(fixture.root.findViewById<View>(R.id.undo_button).isEnabled)
        }
    }

    /** 包含完整服务和附着视图的夹具；仅相机硬件边界替换为受控依赖。 */
    private class Fixture : AutoCloseable {
        val scanner = ScannerTestHarness()
        private val serviceController = Robolectric.buildService(ScanInputMethodService::class.java)
        val service: ScanInputMethodService = serviceController.get()
        val editor = EditableInputConnection()
        val info = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_NONE }
        private val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val root: View
        val visibility: ImeCameraVisibilityController
        private var completedRequests = 0
        init {
            Shadows.shadowOf(RuntimeEnvironment.getApplication() as Application).grantPermissions(Manifest.permission.CAMERA)
            service.scannerFactory = { scanner.controller }
            serviceController.create()
            ReflectionHelpers.setField(service, "mStartedInputConnection", editor)
            ReflectionHelpers.setField(service, "mInputEditorInfo", info)
            service.onStartInput(info, false)
            root = service.onCreateInputView()
            activityController.get().setContentView(root)
            service.onWindowShown()
            service.onStartInputView(info, false)
            visibility = ReflectionHelpers.getField(service, "visibilityController")
            assertTrue(visibility.isTrulyVisible())
            bindPending()
        }
        /** 完成服务真实启动所发起的新提供者请求。 */
        fun bindPending() {
            while (completedRequests < scanner.source.requests.size) {
                scanner.source.requests[completedRequests++](Result.success(scanner.provider))
            }
        }
        /**
         * 经由服务所绑定的真实分析器和异步识别任务交付扫码结果。
         * @param text 原始扫码文本。
         */
        fun emit(text: String) {
            val frame = TestFrame()
            scanner.provider.analyzer()!!.analyze(frame.proxy)
            scanner.recognizer.pending.last().setResult(ScanResult.Text(text))
            assertEquals(1, frame.closes)
        }
        override fun close() {
            activityController.pause().stop().destroy()
            serviceController.destroy()
        }
    }
}
