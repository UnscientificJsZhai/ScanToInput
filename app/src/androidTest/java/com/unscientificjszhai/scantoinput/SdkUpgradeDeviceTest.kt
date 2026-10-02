package com.unscientificjszhai.scantoinput

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.PowerManager
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.unscientificjszhai.scantoinput.scanner.ScanResult
import com.unscientificjszhai.scantoinput.widget.TokenSelectionView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 在设备运行时验证 ML Kit 原生识别及 CameraX 页面重建，不依赖 Robolectric。 */
@RunWith(AndroidJUnit4::class)
class SdkUpgradeDeviceTest {
    /** 默认初始化提供者必须足以运行随 APK 打包的原生条码模型。 */
    @Test(timeout = 30_000)
    fun bundledModelRecognizesQrOnDevice() {
        assertEquals(SMOKE_TEXT, recognizeFixture())
    }

    /** 真实相机预览在页面重建与后台切换后恢复，结果和选择不会丢失。 */
    @Test(timeout = 60_000)
    fun cameraAndSelectionRecoverAfterRecreationAndBackground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("请先点亮测试设备屏幕", context.getSystemService(PowerManager::class.java).isInteractive)
        assertFalse("请先解锁测试设备", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertEquals("请先在系统界面授予相机权限", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        val decoded = recognizeFixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitStreaming(scenario)
            scenario.onActivity { activity ->
                // 使用原生模型识别出的结果经过页面真实入口；相机光学扫码另行验收。
                MainActivity::class.java.getDeclaredMethod("handleScanResult", ScanResult::class.java).apply {
                    isAccessible = true
                }.invoke(activity, ScanResult.Text(decoded))
                activity.findViewById<TokenSelectionView>(R.id.token_selection_view)
                    .restoreSelectedTokens(intArrayOf(0))
            }
            scenario.recreate()
            awaitStreaming(scenario)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitStreaming(scenario)
            scenario.onActivity { activity ->
                val tokens = activity.findViewById<TokenSelectionView>(R.id.token_selection_view)
                assertEquals(decoded, tokens.getFullText())
                assertTrue(tokens.hasSelection())
                assertArrayEquals(intArrayOf(0), tokens.selectedTokenIndices())
            }
        }
    }

    /**
     * 通过实际设备模型识别固定、无敏感内容的二维码夹具。
     * @return 模型识别出的原始文本。
     */
    private fun recognizeFixture(): String {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("sdk37-smoke-qr.png").use(BitmapFactory::decodeStream)
        assertNotNull(bitmap)
        val scanner = BarcodeScanning.getClient()
        return try {
            val codes = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
            assertEquals(1, codes.size)
            codes.single().rawValue!!
        } finally {
            scanner.close()
            bitmap.recycle()
        }
    }

    /**
     * 等待当前页面的真实 CameraX 流状态，超时必须失败。
     * @param scenario 被测页面的设备生命周期控制器。
     */
    private fun awaitStreaming(scenario: ActivityScenario<MainActivity>) {
        val ready = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.findViewById<PreviewView>(R.id.preview_view).previewStreamState.observe(activity) { state ->
                if (state == PreviewView.StreamState.STREAMING) ready.countDown()
            }
        }
        assertTrue("CameraX 预览未在 10 秒内进入 STREAMING", ready.await(10, TimeUnit.SECONDS))
    }

    /** 无用户数据的固定识别样本。 */
    private companion object {
        const val SMOKE_TEXT = "scantoinput-sdk37-smoke"
    }
}
