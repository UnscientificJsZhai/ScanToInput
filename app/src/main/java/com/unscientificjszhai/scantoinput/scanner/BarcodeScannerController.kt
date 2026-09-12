package com.unscientificjszhai.scantoinput.scanner

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次绑定创建的局部用例，清理时始终使用这一份身份。
 * @property provider 绑定这些用例的提供者。
 * @property preview 本次预览用例。
 * @property analysis 本次分析用例。
 */
internal data class ScannerBinding(val provider: CameraProviderAdapter, val preview: Preview, val analysis: ImageAnalysis)

/**
 * 扫码控制器，将相机请求身份贯穿提供者获取、绑定、识别和最终交付。
 * @property providerSource 异步提供者来源。
 * @property mainExecutor 平台绑定与结果交付执行器。
 * @property recognizer 本控制器拥有的识别器。
 * @property analysisExecutor 本控制器拥有的分析执行器。
 */
class BarcodeScannerController internal constructor(
    private val providerSource: CameraProviderSource,
    private val mainExecutor: Executor,
    private var recognizer: FrameRecognizer?,
    private var analysisExecutor: ExecutorService?
) {
    private val stateLock = Any()
    private val gate = ScanSessionGate()
    private var binding: ScannerBinding? = null
    private var resultCallback: ((ScanResult) -> Unit)? = null

    /**
     * 创建使用真实 CameraX 与 ML Kit 的控制器。
     * @param context 用于获取相机和主线程执行器的上下文。
     */
    constructor(context: Context) : this(
        AndroidCameraProviderSource(context.applicationContext), ContextCompat.getMainExecutor(context),
        MlKitFrameRecognizer(), Executors.newSingleThreadExecutor()
    )

    /**
     * 开始一个扫描请求；重复调用保持原请求及原回调。
     * @param lifecycleOwner 绑定生命周期。
     * @param previewView 显示预览的视图。
     * @param onCameraUnavailable 当前请求失败的通知。
     * @param onResult 当前请求识别到的结果。
     */
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView,
              onCameraUnavailable: (() -> Unit)? = null, onResult: (ScanResult) -> Unit) {
        val generation = synchronized(stateLock) {
            val started = gate.start() ?: return
            resultCallback = onResult
            started
        }
        try {
            providerSource.request(mainExecutor) { outcome ->
                outcome.fold(
                    onSuccess = { provider -> bind(provider, lifecycleOwner, previewView, generation, onCameraUnavailable) },
                    onFailure = { fail(generation, onCameraUnavailable) }
                )
            }
        } catch (_: Exception) {
            fail(generation, onCameraUnavailable)
        }
    }

    /**
     * 在锁外执行平台绑定，返回后再次原子核对请求身份并发布。
     * @param provider 本请求获得的提供者。
     * @param owner 绑定生命周期。
     * @param previewView 预览视图。
     * @param generation 请求身份。
     * @param unavailable 当前请求绑定失败通知。
     */
    private fun bind(provider: CameraProviderAdapter, owner: LifecycleOwner, previewView: PreviewView,
                     generation: Long, unavailable: (() -> Unit)?) {
        val executor = synchronized(stateLock) {
            if (!gate.isCurrent(generation)) return
            analysisExecutor
        } ?: return
        var local: ScannerBinding? = null
        try {
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            local = ScannerBinding(provider, preview, analysis)
            analysis.setAnalyzer(executor) { frame -> processImageProxy(frame, generation) }
            if (!synchronized(stateLock) { gate.isCurrent(generation) }) {
                cleanup(local)
                return
            }
            provider.bind(owner, preview, analysis)
            val published = synchronized(stateLock) {
                if (gate.publish(generation)) {
                    binding = local
                    true
                } else false
            }
            if (!published) cleanup(local)
        } catch (_: Exception) {
            fail(generation, unavailable)
            cleanup(local)
        }
    }

    /**
     * 只失效仍为当前身份的失败请求；过期失败不能修改新请求 UI。
     * @param generation 失败的请求身份。
     * @param unavailable 当前请求失败通知。
     */
    private fun fail(generation: Long, unavailable: (() -> Unit)?) {
        synchronized(stateLock) {
            if (!gate.isCurrent(generation)) return
            gate.stop()
            resultCallback = null
            unavailable?.invoke()
        }
    }

    /**
     * 将分析器安装时捕获的身份带过整个异步识别，完成时再验证。
     * @param imageProxy 控制器负责且只负责关闭一次的帧。
     * @param generation 创建分析器时的请求身份。
     */
    private fun processImageProxy(imageProxy: ImageProxy, generation: Long) {
        val closed = AtomicBoolean(false)
        val closeFrame = { if (closed.compareAndSet(false, true)) imageProxy.close() }
        try {
            val task = synchronized(stateLock) {
                if (gate.canDeliver(generation)) recognizer?.recognize(imageProxy) else null
            }
            if (task == null) {
                closeFrame()
                return
            }
            task.addOnCompleteListener(mainExecutor) { completed ->
                try {
                    if (completed.isSuccessful) {
                        val result = completed.result
                        if (result != null) synchronized(stateLock) {
                            if (gate.canDeliver(generation)) resultCallback?.invoke(result)
                        }
                    }
                } finally {
                    closeFrame()
                }
            }
        } catch (_: Exception) {
            // 同步识别失败或执行器拒绝任务时也必须释放帧。
            closeFrame()
        }
    }

    /** 先原子失效请求并捕获旧绑定，再只清理捕获的用例身份。 */
    fun stop() {
        val oldBinding = synchronized(stateLock) {
            gate.stop()
            resultCallback = null
            binding.also { binding = null }
        }
        scheduleCleanup(oldBinding)
    }

    /** 进入不可再次启动的终态，只释放本控制器拥有的资源。 */
    fun release() {
        val resources = synchronized(stateLock) {
            gate.release()
            resultCallback = null
            Triple(binding, recognizer, analysisExecutor).also {
                binding = null
                recognizer = null
                analysisExecutor = null
            }
        }
        scheduleCleanup(resources.first)
        try {
            resources.second?.close()
        } finally {
            resources.third?.shutdown()
        }
    }

    /**
     * 清理一个明确的局部绑定，不读取当前成员绑定，也不影响其他所有者。
     * @param oldBinding 要清理的旧绑定。
     */
    private fun cleanup(oldBinding: ScannerBinding?) {
        if (oldBinding == null) return
        try {
            oldBinding.analysis.clearAnalyzer()
        } finally {
            try {
                oldBinding.provider.unbind(oldBinding.preview, oldBinding.analysis)
            } catch (_: Exception) {
                // CameraX 失败后解绑也可能失败；请求已失效，后续重试不复用它。
            }
        }
    }

    /**
     * 停止可以由任意线程发起；平台用例清理始终投递到主执行器。
     * @param oldBinding 已在状态锁内捕获、不会指向后续新请求的绑定。
     */
    private fun scheduleCleanup(oldBinding: ScannerBinding?) {
        if (oldBinding != null) mainExecutor.execute {
            try { cleanup(oldBinding) } catch (_: Exception) {
                // 分析器清理失败也已通过 finally 尝试解绑这两个用例。
            }
        }
    }
}
