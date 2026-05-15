package com.unscientificjszhai.scantoinput.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
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
import dagger.hilt.android.AndroidEntryPoint

/**
 * 扫码输入法服务。
 */
@AndroidEntryPoint
class ScanInputMethodService : InputMethodService(), LifecycleOwner {

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
    private var lastInputText: String? = null
    private val undoStack = java.util.ArrayDeque<String>()

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        scannerController = BarcodeScannerController(this)
        visibilityController = ImeCameraVisibilityController(
            onShouldStart = { sessionId ->
                currentSessionId = sessionId
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
                                showCameraClosedFallback()
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

    /**
     * 清理当前相机绑定，并在 CameraX 有机会完成解绑后重新请求启动。
     */
    private fun retryOpenCamera() {
        scannerController.stop()
        showCameraClosedFallback()
        cameraClosedText?.postDelayed(
            {
                if (visibilityController.isTrulyVisible()) {
                    visibilityController.restartActiveSession()
                    updateCameraClosedFallback()
                }
            },
            CAMERA_RETRY_DELAY_MS
        )
    }

    private fun handleScanResult(result: com.unscientificjszhai.scantoinput.scanner.ScanResult, sessionId: Int) {
        if (sessionId != currentSessionId) return

        when (result) {
            is com.unscientificjszhai.scantoinput.scanner.ScanResult.Text -> {
                errorHint?.visibility = View.GONE
                val text = result.text
                if (text != lastInputText) {
                    commitText(text)
                    lastInputText = text
                }
            }
            is com.unscientificjszhai.scantoinput.scanner.ScanResult.NonText -> {
                errorHint?.visibility = View.VISIBLE
            }
        }
    }

    private fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        if (ic.commitText(text, 1)) {
            undoStack.push(text)
            updateUndoButton()
            visibilityController.notifyActive()
        }
    }

    private fun updateUndoButton() {
        undoButton?.isEnabled = undoStack.isNotEmpty()
        undoButton?.alpha = if (undoStack.isNotEmpty()) 0.8f else 0.4f
    }

    override fun onCreateInputView(): View {
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ScanToInput_IME)
        val dynamicContext = DynamicColors.wrapContextIfAvailable(themedContext)
        val root = android.view.LayoutInflater.from(dynamicContext).inflate(R.layout.input_method, null)
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
            lastInputText = null
            visibilityController.notifyActive()
        }

        root.findViewById<View>(R.id.space_button).setOnClickListener {
            commitText(" ")
        }

        root.findViewById<View>(R.id.enter_button).setOnClickListener {
            val ic = currentInputConnection
            if (ic != null) {
                val action = currentInputEditorInfo.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
                if (action != android.view.inputmethod.EditorInfo.IME_ACTION_NONE && 
                    action != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                    visibilityController.notifyActive()
                } else {
                    commitText("\n")
                }
            }
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
        visibilityController.onWindowVisibilityChanged(false)
        hideCameraClosedFallback()
    }

    private fun performUndo() {
        val ic = currentInputConnection ?: return
        val lastText = undoStack.peek() ?: return
        
        val before = ic.getTextBeforeCursor(lastText.length, 0)
        if (before == lastText) {
            ic.deleteSurroundingText(lastText.length, 0)
            undoStack.pop()
            updateUndoButton()
            visibilityController.notifyActive()
        } else {
            // 当前光标前文本不匹配时不能安全撤销。
            android.widget.Toast.makeText(this, R.string.cannot_undo_safely, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        visibilityController.onStartInputView()
        updateCameraClosedFallback()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        visibilityController.onFinishInputView()
        hideCameraClosedFallback()
    }

    override fun onDestroy() {
        super.onDestroy()
        visibilityController.onInputViewDestroyed()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scannerController.release()
    }
}
