package com.unscientificjszhai.scantoinput.scanner

import android.app.Application
import android.os.Looper
import com.google.mlkit.common.sdkinternal.MlKitContext
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** 在真实控制器、CameraX 用例及 Google Task 上验证异步生命周期。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37], application = Application::class)
class BarcodeScannerControllerTest {
    /** 已经识别的任务在停止后完成也必须关闭帧且丢弃结果。 */
    @Test
    fun completedRecognitionAfterStopDoesNotDeliver() {
        val fixture = ScannerTestHarness()
        fixture.start()
        val analyzer = fixture.provider.analyzer()!!
        val frame = TestFrame()
        analyzer.analyze(frame.proxy)
        fixture.controller.stop()
        fixture.recognizer.pending.single().setResult(ScanResult.Text("old"))
        assertEquals(1, frame.closes)
        assertTrue(fixture.results.isEmpty())
        val oldQueuedFrame = TestFrame()
        analyzer.analyze(oldQueuedFrame.proxy)
        assertEquals(1, oldQueuedFrame.closes)
        assertEquals(1, fixture.recognizer.pending.size)
        assertNull(fixture.provider.analyzer())
        fixture.controller.release()
    }

    /** 与另一个控制器共享提供者时只清理自身用例。 */
    @Test
    fun startAndStopPreserveAnotherOwnersUseCase() {
        val provider = TestCameraProvider()
        val external = Preview.Builder().build()
        provider.bound.add(external)
        val first = ScannerTestHarness(provider = provider)
        val second = ScannerTestHarness(provider = provider)
        first.start()
        second.start()
        val secondBinding = provider.bindings.last()
        first.controller.stop()
        assertEquals(setOf(external, secondBinding.preview, secondBinding.analysis), provider.bound)
        first.controller.release()
        second.controller.release()
        assertEquals(setOf(external), provider.bound)
    }

    /** 提供者晚完成不能绑定已停止请求，也不能阻断较新请求。 */
    @Test
    fun staleProviderCompletionDoesNotBind() {
        val fixture = ScannerTestHarness()
        fixture.start(false)
        fixture.controller.stop()
        fixture.start(false)
        fixture.source.requests[1](Result.success(fixture.provider))
        fixture.source.requests[0](Result.success(fixture.provider))
        assertEquals(1, fixture.provider.bindings.size)
        fixture.controller.release()
    }

    /** bind 返回之前跨线程 stop 立即失效，返回后只能清理局部用例。 */
    @Test
    fun stopWhileBindHasNotReturnedPreventsPublication() {
        val main = QueuedMainExecutor()
        val fixture = ScannerTestHarness(main)
        fixture.provider.onBind = {
            val frame = TestFrame()
            fixture.provider.analyzer()!!.analyze(frame.proxy)
            assertEquals(1, frame.closes)
            val stopped = CountDownLatch(1)
            Thread { fixture.controller.stop(); stopped.countDown() }.start()
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
        }
        fixture.start()
        main.first()
        assertTrue(fixture.provider.bound.isEmpty())
        assertNull(fixture.provider.analyzer())
        assertTrue(fixture.recognizer.pending.isEmpty())
        fixture.controller.release()
    }

    /** A 的 bind 内重入 stop 和 B 的绑定，A 返回后不得覆盖或解绑 B。 */
    @Test
    fun reentrantNewBindingSurvivesOldBindReturn() {
        val fixture = ScannerTestHarness()
        fixture.provider.onBind = {
            fixture.provider.onBind = null
            fixture.controller.stop()
            fixture.start()
        }
        fixture.start()
        val newest = fixture.provider.bindings.last()
        assertEquals(setOf(newest.preview, newest.analysis), fixture.provider.bound)
        assertNull(fixture.provider.analyzer(0))
        val frame = TestFrame()
        fixture.provider.analyzer(1)!!.analyze(frame.proxy)
        fixture.recognizer.pending.single().setResult(ScanResult.Text("new"))
        assertEquals(listOf(ScanResult.Text("new")), fixture.results)
        fixture.controller.release()
    }

    /** 旧 stop 排队的清理晚于新绑定执行时，仍只清理捕获的旧身份。 */
    @Test
    fun queuedCleanupCannotUnbindNewBindingAndRunsOnMainExecutor() {
        val main = QueuedMainExecutor()
        val fixture = ScannerTestHarness(main)
        fixture.start()
        main.first()
        val stopped = CountDownLatch(1)
        Thread { fixture.controller.stop(); stopped.countDown() }.start()
        assertTrue(stopped.await(3, TimeUnit.SECONDS))
        assertTrue(fixture.provider.removals.isEmpty())
        fixture.start()
        main.last()
        val newest = fixture.provider.bindings.last()
        main.first()
        assertEquals(setOf(newest.preview, newest.analysis), fixture.provider.bound)
        fixture.controller.release()
        main.drain()
        assertTrue(fixture.provider.bound.isEmpty())
    }

    /** 识别已经完成但结果尚未在主执行器交付时，停止仍能拦住回调。 */
    @Test
    fun queuedMainDeliveryRechecksGeneration() {
        val main = QueuedMainExecutor()
        val fixture = ScannerTestHarness(main)
        fixture.start()
        main.drain()
        val frame = TestFrame()
        fixture.provider.analyzer()!!.analyze(frame.proxy)
        fixture.recognizer.pending.single().setResult(ScanResult.Text("queued"))
        fixture.controller.stop()
        main.drain()
        assertTrue(fixture.results.isEmpty())
        assertEquals(1, frame.closes)
        fixture.controller.release()
    }

    /** 重复 start 不替换当前分析器捕获的结果回调。 */
    @Test
    fun duplicateStartKeepsOriginalCallback() {
        val fixture = ScannerTestHarness()
        fixture.start()
        var replacementCalls = 0
        fixture.controller.start(ScannerTestOwner(), PreviewView(RuntimeEnvironment.getApplication()),
            onResult = { replacementCalls++ })
        fixture.provider.analyzer()!!.analyze(TestFrame().proxy)
        fixture.recognizer.pending.single().setResult(ScanResult.NonText)
        assertEquals(listOf(ScanResult.NonText), fixture.results)
        assertEquals(0, replacementCalls)
        assertEquals(1, fixture.source.requests.size)
        fixture.controller.release()
    }

    /** 失败请求可重试，旧请求的失败不得显示新请求的不可用 UI。 */
    @Test
    fun providerAndBindFailureRespectRequestIdentity() {
        val fixture = ScannerTestHarness()
        fixture.start(false)
        fixture.source.requests[0](Result.failure(IllegalStateException("provider")))
        assertEquals(1, fixture.unavailable)
        fixture.start(false)
        fixture.source.requests[0](Result.failure(IllegalStateException("stale")))
        assertEquals(1, fixture.unavailable)
        fixture.provider.onBind = { throw IllegalStateException("bind") }
        fixture.source.requests[1](Result.success(fixture.provider))
        assertEquals(2, fixture.unavailable)
        assertTrue(fixture.provider.bound.isEmpty())
        assertNull(fixture.provider.analyzer())
        fixture.provider.onBind = {
            fixture.provider.onBind = null
            fixture.controller.stop()
            fixture.start()
            throw IllegalStateException("old bind returns failure")
        }
        fixture.start()
        assertEquals(2, fixture.unavailable)
        val newest = fixture.provider.bindings.last()
        assertEquals(setOf(newest.preview, newest.analysis), fixture.provider.bound)
        fixture.controller.release()
    }

    /** 成功、空帧、失败、取消和同步异常都只关闭帧一次。 */
    @Test
    fun allRecognitionOutcomesCloseExactlyOnce() {
        val fixture = ScannerTestHarness()
        fixture.start()
        for (outcome in 0..4) {
            val frame = TestFrame()
            fixture.provider.analyzer()!!.analyze(frame.proxy)
            val pending = fixture.recognizer.pending.last()
            when (outcome) {
                0 -> pending.setResult(ScanResult.Text("text"))
                1 -> pending.setResult(ScanResult.NonText)
                2 -> pending.setResult(null)
                3 -> pending.setException(IllegalStateException("recognize"))
                4 -> {
                    fixture.recognizer.cancellations.last().cancel()
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
            }
            assertEquals(1, frame.closes)
        }
        fixture.recognizer.throwOnRecognize = true
        val throwing = TestFrame()
        fixture.provider.analyzer()!!.analyze(throwing.proxy)
        assertEquals(1, throwing.closes)
        assertEquals(listOf(ScanResult.Text("text"), ScanResult.NonText), fixture.results)
        fixture.controller.release()
    }

    /** release 是终态；未完成识别仍关闭帧，拥有的资源只释放一次。 */
    @Test
    fun releaseIsTerminalAndIdempotent() {
        val fixture = ScannerTestHarness()
        fixture.start()
        val frame = TestFrame()
        fixture.provider.analyzer()!!.analyze(frame.proxy)
        fixture.controller.release()
        fixture.recognizer.pending.single().setResult(ScanResult.Text("released"))
        fixture.controller.release()
        fixture.start()
        assertEquals(1, fixture.source.requests.size)
        assertEquals(1, fixture.recognizer.closes)
        assertTrue(fixture.analysisExecutor.isShutdown)
        assertEquals(1, frame.closes)
        assertTrue(fixture.results.isEmpty())
    }

    /** ML Kit 适配器遇到不含底层图像的帧返回空结果，关闭仍由控制器负责。 */
    @Test
    fun mlKitAdapterHandlesMissingImage() {
        val previousContext = ReflectionHelpers.getStaticField<MlKitContext?>(MlKitContext::class.java, "zzb")
        MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication())
        val recognizer = MlKitFrameRecognizer()
        try {
            val frame = TestFrame()
            val task = recognizer.recognize(frame.proxy)
            assertTrue(task.isSuccessful)
            assertNull(task.result)
            assertEquals(0, frame.closes)
        } finally {
            recognizer.close()
            ReflectionHelpers.setStaticField(MlKitContext::class.java, "zzb", previousContext)
        }
    }
}
