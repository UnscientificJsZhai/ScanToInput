package com.unscientificjszhai.scantoinput.scanner

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 扫码控制器，封装了 CameraX 和 ML Kit 的集成逻辑。
 *
 * @property context 应用程序上下文。
 */
class BarcodeScannerController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = Executors.newSingleThreadExecutor()
    private var scanner: BarcodeScanner? = null

    private var isStarted = false
    private var startRequestId = 0
    private var resultCallback: ((ScanResult) -> Unit)? = null

    /**
     * 初始化扫码引擎。
     */
    init {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        scanner = BarcodeScanning.getClient(options)
    }

    /**
     * 开始扫码。
     *
     * @param lifecycleOwner 生命周期所有者。
     * @param previewView 用于显示相机预览的 View。
     * @param onCameraUnavailable 相机无法绑定或启动时的回调。
     * @param onResult 识别结果回调。
     */
    @Synchronized
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onCameraUnavailable: (() -> Unit)? = null,
        onResult: (ScanResult) -> Unit
    ) {
        resultCallback = onResult
        if (isStarted) return
        isStarted = true
        val requestId = ++startRequestId

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val shouldBind = synchronized(this) {
                    if (isStarted && requestId == startRequestId) {
                        cameraProvider = provider
                        true
                    } else {
                        false
                    }
                }
                if (shouldBind) {
                    bindCameraUseCases(
                        provider,
                        lifecycleOwner,
                        previewView,
                        requestId,
                        onCameraUnavailable
                    )
                }
            } catch (_: Exception) {
                handleCameraUnavailable(null, requestId, onCameraUnavailable)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        requestId: Int,
        onCameraUnavailable: (() -> Unit)?
    ) {
        val executor = analysisExecutor ?: run {
            handleCameraUnavailable(provider, requestId, onCameraUnavailable)
            return
        }

        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(executor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (_: Exception) {
            handleCameraUnavailable(provider, requestId, onCameraUnavailable)
        }
    }

    /**
     * 将相机启动失败统一清理为可再次启动的状态。
     *
     * @param provider 当前启动流程获得的相机提供者。
     * @param requestId 当前启动请求编号，旧请求失败时不影响新请求。
     * @param onCameraUnavailable 相机不可用回调。
     */
    @Synchronized
    private fun handleCameraUnavailable(
        provider: ProcessCameraProvider?,
        requestId: Int?,
        onCameraUnavailable: (() -> Unit)?
    ) {
        if (requestId != null && requestId != startRequestId) {
            return
        }
        isStarted = false
        resultCallback = null
        try {
            provider?.unbindAll()
            if (provider == null) {
                cameraProvider?.unbindAll()
            }
            cameraProvider = null
        } catch (_: Exception) {
            // 相机已经处于失败状态时，清理失败可以忽略，后续重试会重新绑定。
        }
        onCameraUnavailable?.invoke()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(
        imageProxy: ImageProxy
    ) {
        val mediaImage = imageProxy.image
        val scannerInstance = scanner
        val callback = resultCallback
        if (mediaImage != null && scannerInstance != null && callback != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scannerInstance.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        val barcode = barcodes[0]
                        val text = barcode.rawValue
                        if (text != null) {
                            callback(ScanResult.Text(text))
                        } else {
                            callback(ScanResult.NonText)
                        }
                    }
                }
                .addOnFailureListener {
                    // 识别失败通常不需要特别处理，等待下一帧即可
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    /**
     * 停止扫码。
     */
    @Synchronized
    fun stop() {
        startRequestId++
        isStarted = false
        resultCallback = null
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
            // 停止路径需要保持幂等，CameraX 已失败时忽略解绑异常。
        }
        cameraProvider = null
    }

    /**
     * 释放资源。
     */
    @Synchronized
    fun release() {
        stop()
        scanner?.close()
        scanner = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }
}
