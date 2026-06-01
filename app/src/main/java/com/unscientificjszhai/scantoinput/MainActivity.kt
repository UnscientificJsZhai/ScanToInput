package com.unscientificjszhai.scantoinput

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.color.DynamicColors
import com.unscientificjszhai.scantoinput.actions.QuickAction
import com.unscientificjszhai.scantoinput.actions.QuickActionIntentFactory
import com.unscientificjszhai.scantoinput.launcher.LauncherResultPolicy
import com.unscientificjszhai.scantoinput.launcher.LauncherResultUpdate
import com.unscientificjszhai.scantoinput.scanner.BarcodeScannerController
import com.unscientificjszhai.scantoinput.scanner.ScanResult
import com.unscientificjszhai.scantoinput.text.TextProcessingResult
import com.unscientificjszhai.scantoinput.text.TextProcessor
import com.unscientificjszhai.scantoinput.widget.TokenSelectionView
import dagger.hilt.android.AndroidEntryPoint

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

    private val resultPolicy = LauncherResultPolicy()
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
        // 接入 Material You 动态颜色
        DynamicColors.applyToActivityIfAvailable(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        tokenSelectionView = findViewById(R.id.token_selection_view)
        errorHint = findViewById(R.id.error_hint)
        copyButton = findViewById(R.id.copy_button)
        quickActionButton = findViewById(R.id.quick_action_button)

        val buttonRow: View = findViewById(R.id.button_row)
        ViewCompat.setOnApplyWindowInsetsListener(buttonRow) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = systemBars.bottom + dpToPx(8f).toInt())
            insets
        }

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
                applyLauncherResultUpdate(resultPolicy.onProcessedResult(processed))
            }

            ScanResult.NonText -> {
                applyLauncherResultUpdate(
                    resultPolicy.onProcessedResult(TextProcessingResult.NonText)
                )
            }
        }
    }

    /**
     * 将启动器结果策略更新同步到 Android View。
     *
     * @param update 策略层返回的更新指令。
     */
    private fun applyLauncherResultUpdate(update: LauncherResultUpdate) {
        if (update.resultChanged) {
            tokenSelectionView.setTokens(update.renderState.currentResult?.tokens.orEmpty())
        }
        errorHint.visibility = if (update.renderState.nonTextHintVisible) View.VISIBLE else View.GONE
        copyButton.isEnabled = update.renderState.copyEnabled
        updateQuickActionButton(update.renderState.quickAction)
        scheduleOrCancelUnlock(update)
    }

    /**
     * 根据策略更新调度或取消解锁等待任务。
     *
     * @param update 策略层返回的更新指令。
     */
    private fun scheduleOrCancelUnlock(update: LauncherResultUpdate) {
        if (update.cancelUnlock) {
            unlockRunnable?.let { handler.removeCallbacks(it) }
            unlockRunnable = null
        }

        val delayMillis = update.scheduleUnlockDelayMillis
        if (delayMillis != null && unlockRunnable == null) {
            val runnable = Runnable {
                unlockRunnable = null
                applyLauncherResultUpdate(resultPolicy.onUnlockTimeout())
            }
            unlockRunnable = runnable
            handler.postDelayed(runnable, delayMillis)
        }
    }

    /**
     * 处理 token 选择状态变化。
     */
    private fun handleSelectionChanged() {
        applyLauncherResultUpdate(resultPolicy.onSelectionChanged(tokenSelectionView.hasSelection()))
    }

    /**
     * 复制当前选择文本或当前显示结果全文。
     */
    private fun copyText() {
        val textToCopy = resultPolicy.resolveCopyText(
            hasSelection = tokenSelectionView.hasSelection(),
            selectedText = tokenSelectionView.getSelectedText()
        )

        if (textToCopy.isNotEmpty()) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.app_name), textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 执行当前显示结果关联的快速操作。
     */
    private fun performQuickAction() {
        val action = resultPolicy.currentState().quickAction ?: return
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

    /**
     * 更新快速操作按钮展示状态。
     *
     * @param action 当前显示结果关联的快速操作。
     */
    private fun updateQuickActionButton(action: QuickAction?) {
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

    private fun dpToPx(dp: Float): Float {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
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
