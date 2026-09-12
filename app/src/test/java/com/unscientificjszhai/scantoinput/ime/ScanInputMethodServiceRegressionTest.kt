package com.unscientificjszhai.scantoinput.ime

import android.app.Application
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.unscientificjszhai.scantoinput.R
import com.unscientificjszhai.scantoinput.scanner.ScanResult
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** 验证真实输入法入口的历史故障，使用可计数的编辑器连接。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScanInputMethodServiceRegressionTest {
    /** 提交失败后，同一个扫码必须再次尝试提交。 */
    @Test
    fun failedCommitRemainsRetryable() {
        val service = service()
        val editor = CountingEditor(accepts = false)
        ReflectionHelpers.setField(service, "mStartedInputConnection", editor.connection)
        bindSession(service, editor.connection)
        scan(service, "scan-123")
        scan(service, "scan-123")
        assertEquals(2, editor.commits)
    }

    /** 切换连接后，撤销不得删除另一个输入框的相同文本。 */
    @Test
    fun undoNeverDeletesAnotherEditorsText() {
        val service = service()
        ReflectionHelpers.setField(service, "mStartedInputConnection", CountingEditor().connection)
        invoke(service, "commitText", String::class.java to "1234")
        val other = CountingEditor(before = "1234")
        ReflectionHelpers.setField(service, "mStartedInputConnection", other.connection)
        invoke(service, "performUndo")
        assertEquals(0, other.deletes)
    }

    /** 输入视图结束后，已经捕获的扫码回调不得提交。 */
    @Test
    fun hiddenInputRejectsLateResult() {
        val service = service()
        val visibility = ReflectionHelpers.getField<ImeCameraVisibilityController>(service, "visibilityController")
        visibility.onFinishInputView()
        val editor = CountingEditor()
        ReflectionHelpers.setField(service, "mStartedInputConnection", editor.connection)
        scan(service, "late")
        assertEquals(0, editor.commits)
    }

    /** 真正的回车按钮必须尊重禁止执行编辑器动作的标志。 */
    @Test
    fun enterHonorsNoEnterAction() {
        val service = service()
        val editor = CountingEditor()
        ReflectionHelpers.setField(service, "mStartedInputConnection", editor.connection)
        ReflectionHelpers.setField(service, "mInputEditorInfo", EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        })
        service.onCreateInputView().findViewById<View>(R.id.enter_button).performClick()
        assertEquals(0, editor.actions)
        assertEquals(1, editor.commits)
    }

    /**
     * 为最小服务回归夹具登记底层连接；完整生命周期另由集成测试验证。
     * @param service 被测服务。
     * @param connection 底层连接。
     */
    private fun bindSession(service: ScanInputMethodService, connection: InputConnection) {
        val session = ReflectionHelpers.getField<ImeInputSession>(service, "inputSession")
        session.startEditor(connection)
        ReflectionHelpers.setField(service, "scanEditorGeneration", session.generation)
    }

    /**
     * 创建已显示的真实服务实例，避开本用例无关的相机硬件启动。
     * @return 可调用真实私有输入入口的服务。
     */
    private fun service(): ScanInputMethodService {
        val service = ScanInputMethodService()
        ReflectionHelpers.setField(service, "mBase", RuntimeEnvironment.getApplication())
        ReflectionHelpers.setField(service, "currentSessionId", 1)
        val visibility = ImeCameraVisibilityController({}, {})
        ReflectionHelpers.setField(service, "visibilityController", visibility)
        visibility.onInputViewCreated()
        visibility.onViewAttached()
        visibility.onWindowVisibilityChanged(true)
        visibility.onStartInputView()
        return service
    }

    /**
     * 投递已绑定到第一个可见会话的真实扫码回调。
     * @param service 被测输入法。
     * @param text 原始扫码内容。
     */
    private fun scan(service: ScanInputMethodService, text: String) {
        invoke(service, "handleScanResult", ScanResult::class.java to ScanResult.Text(text), Int::class.javaPrimitiveType!! to 1)
    }

    /**
     * 调用真实服务的原有私有入口。
     * @param service 被测输入法。
     * @param name 方法名称。
     * @param parameters 参数类型与值。
     */
    private fun invoke(service: ScanInputMethodService, name: String, vararg parameters: Pair<Class<*>, Any>) {
        service.javaClass.getDeclaredMethod(name, *parameters.map { it.first }.toTypedArray()).apply {
            isAccessible = true
        }.invoke(service, *parameters.map { it.second }.toTypedArray())
    }

    /**
     * 记录编辑器实际收到的操作，不模拟输入法业务判断。
     * @property accepts 是否接受提交。
     * @property before 编辑器当前光标前的文本。
     */
    private class CountingEditor(val accepts: Boolean = true, val before: String = "") {
        var commits = 0
        var deletes = 0
        var actions = 0
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "commitText" -> { commits++; accepts }
                "getTextBeforeCursor" -> before
                "deleteSurroundingText" -> { deletes += arguments!![0] as Int; true }
                "performEditorAction" -> { actions++; true }
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } as InputConnection
    }
}
