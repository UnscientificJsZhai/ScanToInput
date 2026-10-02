package com.unscientificjszhai.scantoinput

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
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
import androidx.core.view.updatePadding
import com.google.android.material.color.DynamicColors
import com.unscientificjszhai.scantoinput.actions.QuickAction
import com.unscientificjszhai.scantoinput.actions.QuickActionIntentFactory
import com.unscientificjszhai.scantoinput.launcher.LauncherResultPolicy
import com.unscientificjszhai.scantoinput.launcher.LauncherResultSnapshot
import com.unscientificjszhai.scantoinput.launcher.LauncherResultUpdate
import com.unscientificjszhai.scantoinput.launcher.QuickActionButtonController
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
    private lateinit var quickActionButtonController: QuickActionButtonController

    private val resultPolicy = LauncherResultPolicy()
    private val handler = Handler(Looper.getMainLooper())
    private var unlockRunnable: Runnable? = null
    private var unlockDeadlineMillis: Long? = null

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
        quickActionButtonController = QuickActionButtonController(quickActionButton, ::areAnimationsEnabled)

        val buttonRow: View = findViewById(R.id.button_row)
        ViewCompat.setOnApplyWindowInsetsListener(buttonRow) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = systemBars.bottom + dpToPx(8f).toInt())
            insets
        }
        val content: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = safeArea.left, right = safeArea.right)
            insets
        }

        scannerController = BarcodeScannerController(this)

        restoreLauncherState(savedInstanceState?.getBundle(STATE_LAUNCHER))

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

    /** 检查相机权限并通过系统界面请求用户授权。 */
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

    /** 为当前可见页面启动相机扫描。 */
    private fun startScanner() {
        scannerController.start(this, previewView) { result ->
            runOnUiThread {
                handleScanResult(result)
            }
        }
    }

    /**
     * 将相机结果交给文本处理器和页面策略。
     * @param result 当前扫描会话交付的结果。
     */
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
            unlockDeadlineMillis = null
        }

        val delayMillis = update.scheduleUnlockDelayMillis
        if (delayMillis != null && unlockRunnable == null) {
            scheduleUnlock(delayMillis)
        }
    }

    /**
     * 按主线程时钟安排解锁，并保存跨页面重建的截止时间。
     * @param delayMillis 距离解锁剩余的毫秒数。
     */
    private fun scheduleUnlock(delayMillis: Long) {
        val runnable = Runnable {
            unlockRunnable = null
            unlockDeadlineMillis = null
            applyLauncherResultUpdate(resultPolicy.onUnlockTimeout())
        }
        unlockRunnable = runnable
        unlockDeadlineMillis = SystemClock.uptimeMillis() + delayMillis
        handler.postDelayed(runnable, delayMillis)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val snapshot = resultPolicy.saveState()
        outState.putBundle(STATE_LAUNCHER, Bundle().apply {
            putStringArrayList(STATE_CURRENT_TOKENS, snapshot.currentResult?.tokens?.let(::ArrayList))
            putStringArrayList(STATE_PENDING_TOKENS, snapshot.pendingResult?.tokens?.let(::ArrayList))
            putBoolean(STATE_LOCKED, snapshot.isLocked)
            putBoolean(STATE_UNLOCK_SCHEDULED, snapshot.unlockScheduled)
            putBoolean(STATE_NON_TEXT_HINT, snapshot.nonTextHintVisible)
            putIntArray(STATE_SELECTION, tokenSelectionView.selectedTokenIndices())
            putLong(STATE_UNLOCK_DEADLINE, unlockDeadlineMillis ?: 0L)
        })
        super.onSaveInstanceState(outState)
    }

    /**
     * 从保存的 token 重建操作与页面状态，避免旋转后丢失选择或重设等待窗口。
     * @param savedState 页面保存的结果状态，首次创建时为 null。
     */
    private fun restoreLauncherState(savedState: Bundle?) {
        if (savedState == null) {
            applyLauncherResultUpdate(LauncherResultUpdate(resultPolicy.currentState(), true, null, false))
            return
        }
        val snapshot = LauncherResultSnapshot(
            currentResult = restoreResult(savedState.getStringArrayList(STATE_CURRENT_TOKENS)),
            pendingResult = restoreResult(savedState.getStringArrayList(STATE_PENDING_TOKENS)),
            isLocked = savedState.getBoolean(STATE_LOCKED),
            unlockScheduled = savedState.getBoolean(STATE_UNLOCK_SCHEDULED),
            nonTextHintVisible = savedState.getBoolean(STATE_NON_TEXT_HINT)
        )
        applyLauncherResultUpdate(resultPolicy.restoreState(snapshot))
        tokenSelectionView.restoreSelectedTokens(savedState.getIntArray(STATE_SELECTION) ?: intArrayOf())
        if (snapshot.unlockScheduled) {
            val remaining = (savedState.getLong(STATE_UNLOCK_DEADLINE) - SystemClock.uptimeMillis())
                .coerceAtLeast(0L)
            scheduleUnlock(remaining)
        }
    }

    /**
     * 保留原始分词，并从原文重新识别快速操作。
     * @param tokens 已保存的 token 列表，没有结果时为 null。
     * @return 完整的文本结果，没有可显示文本时为 null。
     */
    private fun restoreResult(tokens: ArrayList<String>?): TextProcessingResult.Success? {
        if (tokens == null) return null
        val result = TextProcessor.process(tokens.joinToString("")) as? TextProcessingResult.Success
            ?: return null
        return result.copy(tokens = tokens)
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
        try {
            when (val result = QuickActionIntentFactory.createIntent(action)) {
                is QuickActionIntentFactory.CreationResult.Success -> startActivity(result.intent)
                is QuickActionIntentFactory.CreationResult.Failure -> {
                    Toast.makeText(this, result.messageResId, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_handle, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.cannot_perform_action, Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, R.string.cannot_perform_action, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 更新快速操作按钮展示状态。
     *
     * @param action 当前显示结果关联的快速操作。
     */
    private fun updateQuickActionButton(action: QuickAction?) {
        quickActionButtonController.render(action)
    }

    /**
     * 读取系统动画开关，保留页面既有设置语义。
     * @return 动画时长倍率大于零时为 true。
     */
    private fun areAnimationsEnabled(): Boolean {
        val durationScale = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        return durationScale > 0
    }

    /**
     * 将密度无关像素转换为当前屏幕像素。
     * @param dp 密度无关像素值。
     * @return 当前屏幕的像素值。
     */
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
        quickActionButtonController.dispose()
        super.onDestroy()
        scannerController.release()
        unlockRunnable?.let { handler.removeCallbacks(it) }
    }

    /** 页面保存格式的键，不包含与相机生命周期相关的对象。 */
    private companion object {
        const val STATE_LAUNCHER = "launcher_result"
        const val STATE_CURRENT_TOKENS = "current_tokens"
        const val STATE_PENDING_TOKENS = "pending_tokens"
        const val STATE_LOCKED = "locked"
        const val STATE_UNLOCK_SCHEDULED = "unlock_scheduled"
        const val STATE_NON_TEXT_HINT = "non_text_hint"
        const val STATE_SELECTION = "selection"
        const val STATE_UNLOCK_DEADLINE = "unlock_deadline"
    }
}
