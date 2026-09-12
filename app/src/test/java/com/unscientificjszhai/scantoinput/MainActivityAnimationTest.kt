package com.unscientificjszhai.scantoinput

import android.Manifest
import android.app.Application
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.Animation
import android.view.animation.Transformation
import android.widget.Button
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.unscientificjszhai.scantoinput.scanner.ScanResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** 从真实扫码结果入口验证按钮动画与当前业务结果的一致性。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltApplication::class)
class MainActivityAnimationTest {
    /** 原退出动画完成时，不能隐藏后来扫码得到的有效操作。 */
    @Test
    fun regressionOldFadeOutCannotHideNewAction() {
        Fixture().use { fixture ->
            fixture.scan("https://a.example")
            fixture.scan("普通文本")
            val previousExit = fixture.button.animation
            assertNotNull(previousExit)
            assertEquals(200L, previousExit.duration)
            previousExit.startTime = 100L
            previousExit.getTransformation(100L, Transformation())
            fixture.scan("mailto:b@example.com")
            assertEquals(fixture.activity.getString(R.string.action_send_email), fixture.button.text.toString())
            previousExit.getTransformation(301L, Transformation())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE, fixture.button.visibility)
        }
    }

    /** 连续无操作结果不能重启退出动画，原动画应能够正常结束。 */
    @Test
    fun regressionRepeatedEmptyTargetKeepsExitAnimation() {
        Fixture().use { fixture ->
            fixture.scan("https://a.example")
            fixture.scan("普通文本")
            val firstExit = fixture.button.animation
            fixture.scan("普通文本")
            assertSame(firstExit, fixture.button.animation)
            firstExit.startTime = 100L
            firstExit.getTransformation(100L, Transformation())
            firstExit.getTransformation(301L, Transformation())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, fixture.button.visibility)
        }
    }

    /** 真实页面销毁会取消动画，使已捕获的旧回调失效。 */
    @Test
    fun activityDestroyDisposesAnimationOwner() {
        Fixture().use { fixture ->
            fixture.scan("https://a.example")
            fixture.scan("普通文本")
            val oldExit = fixture.button.animation
            val oldEnd = ReflectionHelpers.getField<Animation.AnimationListener>(oldExit, "mListener")
            fixture.destroy()
            assertNull(fixture.button.animation)
            assertEquals(View.VISIBLE, fixture.button.visibility)
            oldEnd.onAnimationEnd(oldExit)
            assertEquals(View.VISIBLE, fixture.button.visibility)
        }
    }

    /** 系统动画关闭时，生产页面同步更新按钮，不留下过期动画。 */
    @Test
    fun activityRespectsDisabledSystemAnimations() {
        Fixture(animationScale = 0f).use { fixture ->
            fixture.scan("https://a.example")
            assertEquals(View.VISIBLE, fixture.button.visibility)
            assertNull(fixture.button.animation)
            fixture.scan("普通文本")
            assertEquals(View.GONE, fixture.button.visibility)
            assertNull(fixture.button.animation)
        }
    }

    /**
     * 真实 Hilt 页面、XML 与 SDK 初始化夹具，不授予相机权限。
     * @param animationScale 本次测试的系统动画倍率。
     */
    private class Fixture(animationScale: Float = 1f) : AutoCloseable {
        private val application = RuntimeEnvironment.getApplication() as Application
        private val previousScale = Settings.Global.getString(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        private val previousMlKit = ReflectionHelpers.getStaticField<MlKitContext?>(MlKitContext::class.java, "zzb")
        private val controller: ActivityController<MainActivity>
        private var destroyed = false
        val activity: MainActivity
        val button: Button

        init {
            Shadows.shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
            Settings.Global.putFloat(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, animationScale)
            MlKitContext.initializeIfNeeded(application)
            controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            activity = controller.get()
            button = activity.findViewById(R.id.quick_action_button)
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", Shadows.shadowOf(activity).nextStartedActivity.action)
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        }

        /**
         * 经生产扫描结果处理器执行识别、页面策略和按钮更新。
         * @param text 模拟扫描器交付的原文。
         */
        fun scan(text: String) {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "handleScanResult",
                ReflectionHelpers.ClassParameter.from(ScanResult::class.java, ScanResult.Text(text)))
        }

        /** 完成真实页面生命周期，允许测试显式销毁后再投递旧动画回调。 */
        fun destroy() {
            if (!destroyed) {
                controller.pause().stop().destroy()
                destroyed = true
            }
        }

        override fun close() {
            try {
                destroy()
            } finally {
                Settings.Global.putString(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
                ReflectionHelpers.setStaticField(MlKitContext::class.java, "zzb", previousMlKit)
            }
        }
    }
}
