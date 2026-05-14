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
     * @param onResult 识别结果回调。
     */
    @Synchronized
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onResult: (ScanResult) -> Unit
    ) {
        resultCallback = onResult
        if (isStarted) return
        isStarted = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                if (isStarted) {
                    bindCameraUseCases(lifecycleOwner, previewView)
                }
            } catch (_: Exception) {
                isStarted = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val provider = cameraProvider ?: return
        val executor = analysisExecutor ?: return

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
            isStarted = false
        }
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
        isStarted = false
        resultCallback = null
        cameraProvider?.unbindAll()
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
