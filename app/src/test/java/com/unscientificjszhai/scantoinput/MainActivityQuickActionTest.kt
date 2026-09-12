package com.unscientificjszhai.scantoinput

import android.Manifest
import android.app.Application
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ActivityNotFoundException
import android.os.IBinder
import android.os.Bundle
import android.content.Intent
import android.view.View
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.unscientificjszhai.scantoinput.actions.QuickAction
import com.unscientificjszhai.scantoinput.launcher.LauncherResultPolicy
import com.unscientificjszhai.scantoinput.text.TextProcessingResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowInstrumentation
import org.robolectric.util.ReflectionHelpers

/** 使用真实 Activity、Hilt 和按钮验证 Intent 创建失败的界面恢复。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltApplication::class)
class MainActivityQuickActionTest {
    /** 配置错误只显示固定提示，不启动系统 Activity。 */
    @Test
    fun invalidWifiAndCalendarShowFixedErrorsWithoutLaunch() {
        val cases = listOf(
            QuickAction.Wifi("WIFI:S:SecretSSID;T:WPA;P:秘密password;") to R.string.invalid_wifi_config,
            QuickAction.Wifi("WIFI:S:SecretSSID;T:WEP;P:password;") to R.string.unsupported_wifi_config,
            QuickAction.CalendarEvent("BEGIN:VEVENT\nDTSTART:20260229T080000Z\nEND:VEVENT") to R.string.invalid_calendar_event,
            QuickAction.Url("Note: buy milk") to R.string.invalid_action_link
        )
        for ((action, message) in cases) withActivity { activity ->
            selectAction(activity, action)
            activity.findViewById<View>(R.id.quick_action_button).performClick()
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            assertEquals(activity.getString(message), ShadowToast.getTextOfLatestToast())
        }
    }

    /** 有效动作仍从真实按钮交给系统，完整参数不变。 */
    @Test
    fun validActionLaunchesCompleteIntent() {
        withActivity { activity ->
            selectAction(activity, QuickAction.Url("HTTPS://Example.com/Case?q=A%2FB"))
            activity.findViewById<View>(R.id.quick_action_button).performClick()
            val intent = Shadows.shadowOf(activity).nextStartedActivity
            assertNotNull(intent)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://Example.com/Case?q=A%2FB", intent.data.toString())
        }
    }

    /** 系统拒绝启动时，真实点击路径恢复为固定提示且不显示异常原文。 */
    @Test
    @Config(shadows = [RejectingInstrumentation::class])
    fun systemLaunchFailuresRemainRecoverable() {
        val failures = listOf(
            ActivityNotFoundException("sensitive detail") to R.string.no_app_to_handle,
            SecurityException("sensitive detail") to R.string.cannot_perform_action,
            IllegalArgumentException("sensitive detail") to R.string.cannot_perform_action
        )
        for ((failure, message) in failures) withActivity { activity ->
            selectAction(activity, QuickAction.Url("myapp://open"))
            RejectingInstrumentation.failure = failure
            try {
                activity.findViewById<View>(R.id.quick_action_button).performClick()
                assertEquals(activity.getString(message), ShadowToast.getTextOfLatestToast())
                assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            } finally {
                RejectingInstrumentation.failure = null
            }
        }
    }

    /** 仅替换系统启动边界，保留 Activity 的生产按钮与异常处理。 */
    @Implements(Instrumentation::class)
    class RejectingInstrumentation : ShadowInstrumentation() {
        @Implementation
        override fun execStartActivity(
            who: Context?, contextThread: IBinder?, token: IBinder?, target: Activity?,
            intent: Intent?, requestCode: Int, options: Bundle?
        ): Instrumentation.ActivityResult? {
            failure?.let { throw it }
            return super.execStartActivity(who, contextThread, token, target, intent, requestCode, options)
        }

        /** 本次测试要注入的系统启动失败。 */
        companion object {
            var failure: RuntimeException? = null
        }
    }

    /**
     * 将动作放入实际页面策略，保留真正按钮监听器。
     * @param activity 页面实例。
     * @param action 当前显示的操作。
     */
    private fun selectAction(activity: MainActivity, action: QuickAction) {
        val policy = ReflectionHelpers.getField<LauncherResultPolicy>(activity, "resultPolicy")
        policy.onProcessedResult(TextProcessingResult.Success(listOf(action.rawText), action))
    }

    /**
     * 创建真实资源页面，并在无相机权限下验证操作。
     * @param block 在页面存活期间执行的断言。
     */
    private fun withActivity(block: (MainActivity) -> Unit) {
        Shadows.shadowOf(RuntimeEnvironment.getApplication() as Application).denyPermissions(Manifest.permission.CAMERA)
        // SDK 静态单例不由 Robolectric 自动重置；夹具退出时恢复，避免污染其他真实适配测试。
        val previousContext = ReflectionHelpers.getStaticField<MlKitContext?>(MlKitContext::class.java, "zzb")
        MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", Shadows.shadowOf(activity).nextStartedActivity.action)
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            block(activity)
        } finally {
            controller.pause().stop().destroy()
            ReflectionHelpers.setStaticField(MlKitContext::class.java, "zzb", previousContext)
        }
    }
}
