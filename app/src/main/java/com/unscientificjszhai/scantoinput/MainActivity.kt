package com.unscientificjszhai.scantoinput

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.unscientificjszhai.scantoinput.actions.QuickAction
import com.unscientificjszhai.scantoinput.actions.QuickActionIntentFactory
import com.unscientificjszhai.scantoinput.scanner.BarcodeScannerController
import com.unscientificjszhai.scantoinput.scanner.ScanResult
import com.unscientificjszhai.scantoinput.text.TextProcessingResult
import com.unscientificjszhai.scantoinput.text.TextProcessor
import com.unscientificjszhai.scantoinput.widget.TokenSelectionView
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.view.isVisible

import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * 扫码启动器页面。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var scannerController: BarcodeScannerController
    private lateinit var previewView: PreviewView
    private lateinit var tokenSelectionView: TokenSelectionView
    private lateinit var errorHint: TextView
    private lateinit var copyButton: Button
    private lateinit var quickActionButton: Button

    private var currentResult: TextProcessingResult.Success? = null
    private var pendingResult: TextProcessingResult.Success? = null
    private var isLocked = false
    private val handler = Handler(Looper.getMainLooper())
    private var unlockRunnable: Runnable? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startScanner()
        } else {
            Toast.makeText(this, R.string.no_camera_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        tokenSelectionView = findViewById(R.id.token_selection_view)
        errorHint = findViewById(R.id.error_hint)
        copyButton = findViewById(R.id.copy_button)
        quickActionButton = findViewById(R.id.quick_action_button)

        scannerController = BarcodeScannerController(this)

        tokenSelectionView.onSelectionChangedListener = {
            handleSelectionChanged()
        }

        copyButton.setOnClickListener {
            copyText()
        }

        quickActionButton.setOnClickListener {
            performQuickAction()
        }

        checkPermissionAndStart()
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startScanner()
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.camera_permission_rationale)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        Toast.makeText(this, R.string.no_camera_permission, Toast.LENGTH_LONG).show()
                    }
                    .show()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startScanner() {
        scannerController.start(this, previewView) { result ->
            runOnUiThread {
                handleScanResult(result)
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        when (result) {
            is ScanResult.Text -> {
                val processed = TextProcessor.process(result.text)
                if (processed is TextProcessingResult.Success) {
                    updateResult(processed)
                    errorHint.visibility = View.GONE
                } else if (processed is TextProcessingResult.NonText) {
                    errorHint.visibility = View.VISIBLE
                }
            }

            ScanResult.NonText -> {
                errorHint.visibility = View.VISIBLE
            }
        }
    }

    private fun updateResult(newResult: TextProcessingResult.Success) {
        // 相同内容不刷新
        if (newResult.tokens.joinToString("") == currentResult?.tokens?.joinToString("")) {
            return
        }

        if (isLocked) {
            pendingResult = newResult
            return
        }

        applyResult(newResult)
    }

    private fun applyResult(result: TextProcessingResult.Success) {
        currentResult = result
        tokenSelectionView.setTokens(result.tokens)
        updateQuickActionButton(result)
    }

    private fun handleSelectionChanged() {
        val hasSelection = tokenSelectionView.hasSelection()
        if (hasSelection) {
            isLocked = true
            // 取消正在进行的解锁任务
            unlockRunnable?.let { handler.removeCallbacks(it) }
            unlockRunnable = null
        } else {
            // 开始 2 秒等待窗口
            if (unlockRunnable == null) {
                val runnable = Runnable {
                    isLocked = false
                    unlockRunnable = null
                    // 解锁后如果有挂起的结果，应用它
                    pendingResult?.let {
                        applyResult(it)
                        pendingResult = null
                    }
                }
                unlockRunnable = runnable
                handler.postDelayed(runnable, 2000)
            }
        }
    }

    private fun copyText() {
        val textToCopy = if (tokenSelectionView.hasSelection()) {
            tokenSelectionView.getSelectedText()
        } else {
            tokenSelectionView.getFullText()
        }

        if (textToCopy.isNotEmpty()) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.app_name), textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    }

    private fun performQuickAction() {
        val action = currentResult?.quickAction ?: return
        val intent = QuickActionIntentFactory.createIntent(action)

        if (intent != null) {
            try {
                if (intent.resolveActivity(packageManager) != null ||
                    // 有些 Intent 比如 Settings.ACTION_WIFI_ADD_NETWORKS 可能 resolveActivity 返回 null 但仍能启动
                    action is QuickAction.Wifi
                ) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.no_app_to_handle, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, R.string.cannot_perform_action, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateQuickActionButton(result: TextProcessingResult.Success) {
        val action = result.quickAction
        val animatorDuration = if (areAnimationsEnabled()) 200L else 0L

        if (action != null) {
            quickActionButton.setText(action.labelResId)
            if (quickActionButton.visibility != View.VISIBLE) {
                quickActionButton.visibility = View.VISIBLE
                if (animatorDuration > 0) {
                    val anim = AlphaAnimation(0f, 1f)
                    anim.duration = animatorDuration
                    quickActionButton.startAnimation(anim)
                }
            }
        } else {
            if (quickActionButton.isVisible) {
                if (animatorDuration > 0) {
                    val anim = AlphaAnimation(1f, 0f)
                    anim.duration = animatorDuration
                    anim.setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationRepeat(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) {
                            quickActionButton.visibility = View.GONE
                        }
                    })
                    quickActionButton.startAnimation(anim)
                } else {
                    quickActionButton.visibility = View.GONE
                }
            }
        }
    }

    private fun areAnimationsEnabled(): Boolean {
        val durationScale = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        return durationScale > 0
    }

    override fun onStop() {
        super.onStop()
        // 按照阶段八规划，Activity 暂停/停止时释放相机
        scannerController.stop()
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startScanner()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scannerController.release()
        unlockRunnable?.let { handler.removeCallbacks(it) }
    }
}
