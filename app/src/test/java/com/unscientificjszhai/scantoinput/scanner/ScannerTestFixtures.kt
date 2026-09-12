package com.unscientificjszhai.scantoinput.scanner

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingDeque
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers

/** 测试中可精确调度主执行器任务，不依赖真实时间等待。 */
internal class QueuedMainExecutor : Executor {
    val tasks = LinkedBlockingDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.add(command) }
    /** 执行最早排队任务。 */
    fun first() { tasks.removeFirst().run() }
    /** 执行最后排队任务，用于模拟旧清理排队后新绑定先完成。 */
    fun last() { tasks.removeLast().run() }
    /** 排空所有已经登记的任务。 */
    fun drain() { while (tasks.isNotEmpty()) first() }
}

/** 手动完成提供者获取，通过调用者提供的真实执行器交付。 */
internal class ControlledProviderSource : CameraProviderSource {
    val requests = mutableListOf<(Result<CameraProviderAdapter>) -> Unit>()
    override fun request(executor: Executor, callback: (Result<CameraProviderAdapter>) -> Unit) {
        requests.add { outcome -> executor.execute { callback(outcome) } }
    }
}

/** 记录 CameraX 用例身份，允许在 bind 返回及 unbind 执行边界重入。 */
internal class TestCameraProvider : CameraProviderAdapter {
    val bound = mutableSetOf<UseCase>()
    val bindings = mutableListOf<ScannerBinding>()
    val removals = mutableListOf<List<UseCase>>()
    var onBind: ((ScannerBinding) -> Unit)? = null
    var onUnbind: (() -> Unit)? = null
    override fun bind(owner: LifecycleOwner, preview: Preview, analysis: ImageAnalysis) {
        val binding = ScannerBinding(this, preview, analysis)
        bindings.add(binding)
        bound.add(preview)
        bound.add(analysis)
        onBind?.invoke(binding)
    }
    override fun unbind(vararg useCases: UseCase) {
        removals.add(useCases.toList())
        onUnbind?.invoke()
        bound.removeAll(useCases.toSet())
    }

    /**
     * 获取实际安装在指定 CameraX 用例上的分析器。
     * @param index 绑定序号，默认最后一次。
     * @return 控制器安装的回调，清理后为 null。
     */
    fun analyzer(index: Int = bindings.lastIndex): ImageAnalysis.Analyzer? =
        ReflectionHelpers.getField(bindings[index].analysis, "mSubscribedAnalyzer")
}

/** 用 Google Task 完成源精确控制识别成功、失败及取消。 */
internal class ControlledRecognizer : FrameRecognizer {
    val pending = mutableListOf<TaskCompletionSource<ScanResult?>>()
    val cancellations = mutableListOf<CancellationTokenSource>()
    var closes = 0
    var throwOnRecognize = false
    override fun recognize(frame: ImageProxy): Task<ScanResult?> {
        if (throwOnRecognize) throw IllegalStateException("受控同步识别失败")
        val cancellation = CancellationTokenSource()
        val completion = TaskCompletionSource<ScanResult?>(cancellation.token)
        cancellations.add(cancellation)
        pending.add(completion)
        return completion.task
    }
    override fun close() { closes++ }
}

/** 只记录帧资源被实际关闭的次数。 */
internal class TestFrame {
    var closes = 0
    val proxy = Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
        if (method.name == "close") closes++
        null
    } as ImageProxy
}

/** 使用真实 LifecycleRegistry 的扫描所有者。 */
internal class ScannerTestOwner : LifecycleOwner {
    override val lifecycle: Lifecycle = LifecycleRegistry(this)
}

/**
 * 装配真实控制器和仅替换外部边界的测试依赖。
 * @property main 主执行器，可传入可排队版本。
 * @property provider 可共享并计数的相机提供者。
 */
internal class ScannerTestHarness(
    val main: Executor = Executor(Runnable::run),
    val provider: TestCameraProvider = TestCameraProvider()
) {
    val source = ControlledProviderSource()
    val recognizer = ControlledRecognizer()
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val controller = BarcodeScannerController(source, main, recognizer, analysisExecutor)
    val results = mutableListOf<ScanResult>()
    var unavailable = 0

    /**
     * 启动控制器，并可在调用之后完成提供者请求。
     * @param complete 是否立即交付本次产生的新请求。
     */
    fun start(complete: Boolean = true) {
        val previous = source.requests.size
        controller.start(ScannerTestOwner(), PreviewView(RuntimeEnvironment.getApplication()),
            onCameraUnavailable = { unavailable++ }, onResult = results::add)
        if (complete && source.requests.size > previous) source.requests.last()(Result.success(provider))
    }
}
