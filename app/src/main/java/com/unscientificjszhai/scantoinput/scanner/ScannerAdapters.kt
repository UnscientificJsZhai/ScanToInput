package com.unscientificjszhai.scantoinput.scanner

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor

/** 将相机提供者的异步获取与控制器隔离，便于控制完成时序。 */
internal fun interface CameraProviderSource {
    /**
     * 异步获取相机提供者。
     * @param executor 交付完成通知的执行器。
     * @param callback 提供者或获取异常。
     */
    fun request(executor: Executor, callback: (Result<CameraProviderAdapter>) -> Unit)
}

/** 只封装 CameraX 的绑定边界，保留用例的对象身份。 */
internal interface CameraProviderAdapter {
    /**
     * 将本次请求创建的两个用例绑定到生命周期。
     * @param owner 生命周期所有者。
     * @param preview 本次预览用例。
     * @param analysis 本次分析用例。
     */
    fun bind(owner: LifecycleOwner, preview: Preview, analysis: ImageAnalysis)

    /**
     * 解绑明确列举的用例。
     * @param useCases 需要解绑的本控制器用例。
     */
    fun unbind(vararg useCases: UseCase)
}

/**
 * CameraX 提供者的生产获取适配。
 * @property context 应用上下文。
 */
internal class AndroidCameraProviderSource(private val context: Context) : CameraProviderSource {
    override fun request(executor: Executor, callback: (Result<CameraProviderAdapter>) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ callback(runCatching { AndroidCameraProviderAdapter(future.get()) }) }, executor)
    }
}

/**
 * 委托给真实 CameraX 提供者。
 * @property provider CameraX 的进程级提供者。
 */
internal class AndroidCameraProviderAdapter(private val provider: ProcessCameraProvider) : CameraProviderAdapter {
    override fun bind(owner: LifecycleOwner, preview: Preview, analysis: ImageAnalysis) {
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    override fun unbind(vararg useCases: UseCase) {
        provider.unbind(*useCases)
    }
}

/** 对一帧运行识别；控制器始终拥有该帧的关闭责任。 */
internal interface FrameRecognizer {
    /**
     * 提交一帧识别，空结果表示当前帧没有条码。
     * @param frame CameraX 帧。
     * @return 可异步完成的识别任务。
     */
    fun recognize(frame: ImageProxy): Task<ScanResult?>

    /** 释放识别器自身资源。 */
    fun close()
}

/** 将真实 ML Kit 首个条码结果转换为应用结果。 */
internal class MlKitFrameRecognizer : FrameRecognizer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun recognize(frame: ImageProxy): Task<ScanResult?> {
        val mediaImage = frame.image ?: return Tasks.forResult(null)
        return scanner.process(InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees))
            .continueWith { task ->
                task.result.firstOrNull()?.let { barcode ->
                    barcode.rawValue?.let(ScanResult::Text) ?: ScanResult.NonText
                }
            }
    }

    override fun close() {
        scanner.close()
    }
}
