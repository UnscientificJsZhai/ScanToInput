package com.unscientificjszhai.scantoinput

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.widget.Button
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.unscientificjszhai.scantoinput.launcher.LauncherResultPolicy
import com.unscientificjszhai.scantoinput.scanner.ScanResult
import com.unscientificjszhai.scantoinput.widget.TokenSelectionView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** 经真实保存格式重建 Hilt 页面，验证旋转及进程重建后的结果语义。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37], application = HiltApplication::class)
class MainActivityStateRestorationTest {
    /** 锁定期间的当前操作、选择、挂起结果与非文本提示必须同时保留。 */
    @Test
    fun lockedResultSurvivesRecreation() {
        Fixture().use { fixture ->
            fixture.scan(ScanResult.Text("https://example.com/path"))
            fixture.tokens.restoreSelectedTokens(intArrayOf(0))
            val selected = fixture.tokens.getSelectedText()
            fixture.scan(ScanResult.Text("https://pending.example/new"))
            fixture.scan(ScanResult.NonText)
            fixture.recreate()

            assertEquals("https://example.com/path", fixture.tokens.getFullText())
            assertEquals(selected, fixture.tokens.getSelectedText())
            assertArrayEquals(intArrayOf(0), fixture.tokens.selectedTokenIndices())
            assertTrue(fixture.policy.currentState().isLocked)
            assertTrue(fixture.policy.currentState().hasPendingResult)
            assertEquals(View.VISIBLE, fixture.activity.findViewById<View>(R.id.error_hint).visibility)
            assertEquals(View.VISIBLE, fixture.activity.findViewById<View>(R.id.quick_action_button).visibility)
            fixture.activity.findViewById<View>(R.id.copy_button).performClick()
            val clipboard = fixture.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(selected, clipboard.primaryClip!!.getItemAt(0).text.toString())

            fixture.tokens.clearSelection()
            fixture.advance(2_000)
            assertEquals("https://pending.example/new", fixture.tokens.getFullText())
            assertFalse(fixture.policy.currentState().isLocked)
            assertEquals(View.GONE, fixture.activity.findViewById<View>(R.id.error_hint).visibility)
        }
    }

    /** 旋转发生在等待窗口中时，只等待原截止时间剩余的部分。 */
    @Test
    fun recreationDoesNotRestartUnlockCountdown() {
        Fixture().use { fixture ->
            fixture.scan(ScanResult.Text("old"))
            fixture.tokens.restoreSelectedTokens(intArrayOf(0))
            fixture.scan(ScanResult.Text("new"))
            fixture.tokens.clearSelection()
            val deadline = ReflectionHelpers.getField<Long>(fixture.activity, "unlockDeadlineMillis")
            fixture.advance(500)
            fixture.recreate()
            assertEquals(deadline, ReflectionHelpers.getField<Long>(fixture.activity, "unlockDeadlineMillis"))
            val remaining = deadline - SystemClock.uptimeMillis()
            assertTrue(remaining > 0)
            fixture.advance(remaining - 1)
            assertEquals("old", fixture.tokens.getFullText())
            fixture.advance(1)
            assertEquals("new", fixture.tokens.getFullText())
        }
    }

    /** 页面销毁期间等待窗口已经超时，恢复后立即应用挂起结果。 */
    @Test
    fun elapsedCountdownAppliesPendingResultOnRestore() {
        Fixture().use { fixture ->
            fixture.scan(ScanResult.Text("old"))
            fixture.tokens.restoreSelectedTokens(intArrayOf(0))
            fixture.scan(ScanResult.Text("new"))
            fixture.tokens.clearSelection()
            fixture.recreate(elapsedWhileDestroyed = 3_000)
            fixture.advance(1)
            assertEquals("new", fixture.tokens.getFullText())
            assertFalse(fixture.policy.currentState().isLocked)
        }
    }

    /** 没有结果的页面恢复后保持复制禁用；非文本提示不能消失。 */
    @Test
    fun emptyAndNonTextStatesSurviveRecreation() {
        Fixture().use { fixture ->
            fixture.recreate()
            assertFalse(fixture.activity.findViewById<Button>(R.id.copy_button).isEnabled)
            fixture.scan(ScanResult.NonText)
            fixture.recreate()
            assertFalse(fixture.tokens.hasSelection())
            assertEquals("", fixture.tokens.getFullText())
            assertEquals(View.VISIBLE, fixture.activity.findViewById<View>(R.id.error_hint).visibility)
        }
    }

    /** 保存真实 Bundle 并序列化后创建新页面，避免依赖旧对象引用。 */
    private class Fixture : AutoCloseable {
        private val application = RuntimeEnvironment.getApplication() as Application
        private val previousMlKit = ReflectionHelpers.getStaticField<MlKitContext?>(MlKitContext::class.java, "zzb")
        private var controller = run {
            Shadows.shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
            MlKitContext.initializeIfNeeded(application)
            Robolectric.buildActivity(MainActivity::class.java).setup()
        }
        val activity: MainActivity get() = controller.get()
        val tokens: TokenSelectionView get() = activity.findViewById(R.id.token_selection_view)
        val policy: LauncherResultPolicy get() = ReflectionHelpers.getField(activity, "resultPolicy")

        /**
         * 经生产结果入口更新页面。
         * @param result 扫描器交付的结果。
         */
        fun scan(result: ScanResult) {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "handleScanResult",
                ReflectionHelpers.ClassParameter.from(ScanResult::class.java, result))
        }

        /**
         * 销毁旧页面后重新读取保存状态。
         * @param elapsedWhileDestroyed 页面销毁期间经过的毫秒数。
         */
        fun recreate(elapsedWhileDestroyed: Long = 0) {
            val state = Bundle()
            controller.saveInstanceState(state).pause().stop().destroy()
            advance(elapsedWhileDestroyed)
            val parcel = Parcel.obtain()
            val restored = try {
                parcel.writeBundle(state)
                parcel.setDataPosition(0)
                parcel.readBundle(MainActivity::class.java.classLoader)
            } finally {
                parcel.recycle()
            }
            controller = Robolectric.buildActivity(MainActivity::class.java).setup(restored)
        }

        /**
         * 仅推进主线程测试时钟。
         * @param millis 推进的毫秒数。
         */
        fun advance(millis: Long) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        }

        override fun close() {
            try {
                controller.pause().stop().destroy()
            } finally {
                ReflectionHelpers.setStaticField(MlKitContext::class.java, "zzb", previousMlKit)
            }
        }
    }
}
