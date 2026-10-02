// 统一声明 Android 构建和代码生成插件，Kotlin 编译由 AGP 内置支持提供。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
}
