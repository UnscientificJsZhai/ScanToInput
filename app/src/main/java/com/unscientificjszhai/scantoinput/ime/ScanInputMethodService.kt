package com.unscientificjszhai.scantoinput.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import androidx.camera.view.PreviewView
import androidx.camera.view.PreviewView.StreamState
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.unscientificjszhai.scantoinput.R
import com.google.android.material.color.DynamicColors
import com.unscientificjszhai.scantoinput.scanner.BarcodeScannerController
import com.unscientificjszhai.scantoinput.scanner.ScanResult
import com.unscientificjszhai.scantoinput.text.TextProcessor
import dagger.hilt.android.AndroidEntryPoint

/**
 * 扫码输入法服务。
 */
@AndroidEntryPoint
class ScanInputMethodService : InputMethodService(), LifecycleOwner {

    /** 相机重试的界面等待时长。 */
    private companion object {
        private const val CAMERA_RETRY_DELAY_MS = 300L
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var scannerController: BarcodeScannerController
    private lateinit var visibilityController: ImeCameraVisibilityController

    private var previewView: PreviewView? = null
    private var errorHint: TextView? = null
    private var cameraClosedBackground: View? = null
    private var cameraClosedText: TextView? = null
    private var undoButton: View? = null

    private var currentSessionId = -1
    private val inputSession = ImeInputSession()
    private var scanEditorGeneration = -1L
    private var retryRunnable: Runnable? = null
    private var retryHost: View? = null
    internal var scannerFactory: (Context) -> BarcodeScannerController = ::BarcodeScannerController

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        scannerController = scannerFactory(this)
        visibilityController = ImeCameraVisibilityController(
            onShouldStart = { sessionId ->
                currentSessionId = sessionId
                scanEditorGeneration = inputSession.generation
                val preview = previewView
                if (preview != null) {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        scannerController.start(
                            this,
                            preview,
                            onResult = { result ->
                                handleScanResult(result, sessionId)
                            },
                            onCameraUnavailable = {
                                if (acceptsScan(sessionId)) showCameraClosedFallback()
                            }
                        )
                        updateCameraClosedFallback()
                    } else {
                        errorHint?.text = getString(R.string.no_camera_permission)
                        errorHint?.visibility = View.VISIBLE
                        showCameraClosedFallback()
                    }
                }
            },
            onShouldStop = { _ ->
                currentSessionId = -1
                cancelCameraRetry()
                scannerController.stop()
                hideCameraClosedFallback()
            }
        )
    }

    /**
     * 根据相机预览流状态刷新相机关闭兜底层。
     */
    private fun updateCameraClosedFallback() {
        val preview = previewView ?: return
        if (!visibilityController.isTrulyVisible()) {
            hideCameraClosedFallback()
            return
        }

        if (preview.previewStreamState.value == StreamState.STREAMING) {
            hideCameraClosedFallback()
        } else {
            showCameraClosedFallback()
        }
    }

    /**
     * 显示相机关闭兜底层。
     */
    private fun showCameraClosedFallback() {
        cameraClosedBackground?.visibility = View.VISIBLE
        cameraClosedText?.visibility = View.VISIBLE
    }

    /**
     * 隐藏相机关闭兜底层。
     */
    private fun hideCameraClosedFallback() {
        cameraClosedBackground?.visibility = View.GONE
        cameraClosedText?.visibility = View.GONE
    }

    /** 立即失效当前扫描，再以可取消身份安排既有的延迟重试。 */
    private fun retryOpenCamera() {
        cancelCameraRetry()
        val token = visibilityController.beginRetry() ?: return
        val generation = inputSession.generation
        val host = cameraClosedText ?: return
        showCameraClosedFallback()
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (retryRunnable !== runnable) return@Runnable
            retryRunnable = null
            retryHost = null
            if (generation == inputSession.generation && currentInputConnection === inputSession.editorIdentity) {
                visibilityController.completeRetry(token)
                updateCameraClosedFallback()
            }
        }
        retryRunnable = runnable
        retryHost = host
        host.postDelayed(runnable, CAMERA_RETRY_DELAY_MS)
    }

    /** 取消实际排队的旧视图任务；纯业务控制器另行核对重试身份。 */
    private fun cancelCameraRetry() {
        retryRunnable?.let { retryHost?.removeCallbacks(it) }
        retryRunnable = null
        retryHost = null
    }

    /**
     * 在最终提交边界重验扫码会话、可见性和底层编辑连接。
     * @param sessionId 回调捕获的扫描会话。
     * @return 是否仍属于当前可见编辑器。
     */
    private fun acceptsScan(sessionId: Int): Boolean = sessionId == currentSessionId &&
        visibilityController.accepts(sessionId) && scanEditorGeneration == inputSession.generation &&
        currentInputConnection != null && currentInputConnection === inputSession.editorIdentity

    /**
     * 只接受当前可见编辑会话的可显示文本。
     * @param result 扫码识别结果。
     * @param sessionId 扫描开始时捕获的身份。
     */
    private fun handleScanResult(result: ScanResult, sessionId: Int) {
        if (!acceptsScan(sessionId)) return
        when (result) {
            is ScanResult.Text -> {
                if (!TextProcessor.isDisplayableText(result.text)) {
                    errorHint?.visibility = View.VISIBLE
                    return
                }
                errorHint?.visibility = View.GONE
                commitToEditor(result.text, scanned = true) { acceptsScan(sessionId) }
            }
            ScanResult.NonText -> errorHint?.visibility = View.VISIBLE
        }
    }

    /**
     * 提交空格、换行等显式按键文本，不改变扫码去重记录。
     * @param text 要提交的文本。
     * @return 是否提交成功。
     */
    private fun commitText(text: String): Boolean = commitToEditor(text, scanned = false) { true }

    /**
     * 将平台连接交给纯业务会话，并在每次平台调用后核对连接身份。
     * @param text 要提交的文本。
     * @param scanned 是否属于扫码输入。
     * @param isCurrent 额外的扫码可见性检查。
     * @return 是否已提交到仍有效的编辑器。
     */
    private fun commitToEditor(text: String, scanned: Boolean, isCurrent: () -> Boolean): Boolean {
        val connection = currentInputConnection
        synchronizeEditor(connection)
        if (connection == null) return false
        val generation = inputSession.generation
        val committed = inputSession.commit(InputConnectionEditorAdapter(connection), text, scanned) {
            generation == inputSession.generation && currentInputConnection === connection && isCurrent()
        }
        updateUndoButton()
        if (committed) visibilityController.notifyActive()
        return committed
    }

    /**
     * 发现系统未先通知的当前连接变化时，失效旧撤销与扫描并等待新 view-start。
     * @param connection 系统当前提供的底层连接，缺少连接时为 null。
     */
    private fun synchronizeEditor(connection: InputConnection?) {
        if (connection !== inputSession.editorIdentity) {
            inputSession.startEditor(connection)
            visibilityController.onStartInput()
            cancelCameraRetry()
            updateUndoButton()
        }
    }

    /** 更新撤销按钮；真正删除前仍需要读取编辑器并重新核对。 */
    private fun updateUndoButton() {
        val enabled = inputSession.canUndo()
        undoButton?.isEnabled = enabled
        undoButton?.alpha = if (enabled) 0.8f else 0.4f
    }

    /** 尊重编辑器的 NO_ENTER_ACTION 标志，否则执行明确指定的动作。 */
    private fun performEnter() {
        val connection = currentInputConnection
        synchronizeEditor(connection)
        if (connection == null) return
        val options = currentInputEditorInfo?.imeOptions ?: EditorInfo.IME_ACTION_UNSPECIFIED
        val action = options and EditorInfo.IME_MASK_ACTION
        if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            commitText("\n")
        } else {
            inputSession.invalidateUndo()
            updateUndoButton()
            val accepted = try { connection.performEditorAction(action) } catch (_: Exception) { false }
            if (accepted) visibilityController.notifyActive()
        }
    }

    override fun onCreateInputView(): View {
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ScanToInput_IME)
        val dynamicContext = DynamicColors.wrapContextIfAvailable(themedContext)
        val root = android.view.LayoutInflater.from(dynamicContext).inflate(R.layout.input_method, null)
        cancelCameraRetry()
        previewView?.previewStreamState?.removeObservers(this)
        previewView = root.findViewById(R.id.preview_view)
        errorHint = root.findViewById(R.id.error_hint)
        cameraClosedBackground = root.findViewById(R.id.camera_closed_background)
        cameraClosedText = root.findViewById(R.id.camera_closed_text)
        undoButton = root.findViewById(R.id.undo_button)

        previewView?.previewStreamState?.removeObservers(this)
        previewView?.previewStreamState?.observe(this) { streamState ->
            if (streamState == StreamState.STREAMING) {
                hideCameraClosedFallback()
            } else {
                updateCameraClosedFallback()
            }
        }

        root.findViewById<View>(R.id.undo_button).setOnClickListener {
            performUndo()
        }

        root.findViewById<View>(R.id.next_scan_button).setOnClickListener {
            inputSession.nextScan()
            visibilityController.notifyActive()
        }

        root.findViewById<View>(R.id.space_button).setOnClickListener {
            commitText(" ")
        }

        root.findViewById<View>(R.id.enter_button).setOnClickListener {
            performEnter()
        }

        previewView?.setOnClickListener {
            visibilityController.notifyActive()
        }

        cameraClosedText?.setOnClickListener {
            retryOpenCamera()
        }

        val navigationBarSpacer = root.findViewById<View>(R.id.navigation_bar_spacer)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            navigationBarSpacer.layoutParams.height = navBars.bottom
            navigationBarSpacer.requestLayout()
            insets
        }

        root.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            visibilityController.onWindowFocusChanged(hasFocus)
        }

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                visibilityController.onViewAttached()
            }

            override fun onViewDetachedFromWindow(v: View) {
                cancelCameraRetry()
                visibilityController.onViewDetached()
            }
        })

        visibilityController.onInputViewCreated()
        updateUndoButton()
        return root
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        visibilityController.onWindowVisibilityChanged(true)
        updateCameraClosedFallback()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        cancelCameraRetry()
        visibilityController.onWindowVisibilityChanged(false)
        hideCameraClosedFallback()
    }

    /** 对真实编辑器重新核对绝对位置后执行撤销，失败删除不丢弃可重试记录。 */
    private fun performUndo() {
        val connection = currentInputConnection
        synchronizeEditor(connection)
        if (connection == null) return
        val generation = inputSession.generation
        val undone = inputSession.undo(InputConnectionEditorAdapter(connection)) {
            generation == inputSession.generation && currentInputConnection === connection
        }
        updateUndoButton()
        if (undone) visibilityController.notifyActive()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        cancelCameraRetry()
        inputSession.startEditor(currentInputConnection)
        visibilityController.onStartInput()
        updateUndoButton()
    }

    override fun onFinishInput() {
        cancelCameraRetry()
        inputSession.startEditor(null)
        visibilityController.onStartInput()
        updateUndoButton()
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val connection = currentInputConnection
        synchronizeEditor(connection)
        inputSession.onSelection(connection, oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateUndoButton()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        visibilityController.onStartInputView()
        updateCameraClosedFallback()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelCameraRetry()
        visibilityController.onFinishInputView()
        hideCameraClosedFallback()
    }

    override fun onDestroy() {
        cancelCameraRetry()
        inputSession.startEditor(null)
        visibilityController.onInputViewDestroyed()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scannerController.release()
        super.onDestroy()
    }
}
