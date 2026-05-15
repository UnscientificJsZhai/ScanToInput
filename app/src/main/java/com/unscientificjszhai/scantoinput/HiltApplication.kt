package com.unscientificjszhai.scantoinput

import android.app.Application
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt Application 类。
 * 用于初始化 Hilt 依赖注入框架。
 */
@HiltAndroidApp
class HiltApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 接入 Material You 动态颜色
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
