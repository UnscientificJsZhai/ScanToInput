import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    jacoco
}

// 全部缺省时维持本地未签名构建；只要提供一项，就必须完整配置发布签名。
val signingVariableNames = listOf(
    "ANDROID_KEYSTORE_FILE",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD"
)
val signingValues = signingVariableNames.associateWith {
    providers.environmentVariable(it).orNull
}
val environmentKeystoreFile = if (signingValues.values.any { !it.isNullOrEmpty() }) {
    val missingNames = signingVariableNames.filter { signingValues[it].isNullOrEmpty() }
    check(missingNames.isEmpty()) {
        "发布签名缺少环境变量：${missingNames.joinToString()}"
    }
    val keystoreFile = file(signingValues.getValue("ANDROID_KEYSTORE_FILE")!!)
    check(keystoreFile.isFile) {
        "ANDROID_KEYSTORE_FILE 必须指向存在的普通文件"
    }
    keystoreFile
} else {
    null
}

configure<ApplicationExtension> {
    namespace = "com.unscientificjszhai.scantoinput"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.unscientificjszhai.scantoinput"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val environmentSigningConfig = if (environmentKeystoreFile != null) {
        signingConfigs.create("releaseEnvironment") {
            storeFile = environmentKeystoreFile
            storePassword = signingValues.getValue("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = signingValues.getValue("ANDROID_KEY_ALIAS")
            keyPassword = signingValues.getValue("ANDROID_KEY_PASSWORD")
        }
    } else {
        null
    }

    buildTypes {
        release {
            signingConfig = environmentSigningConfig
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    lint {
        abortOnError = true
        absolutePaths = false
    }
}

configure<JacocoPluginExtension> {
    toolVersion = "0.8.14"
}

tasks.withType<Test>().configureEach {
    // 不同 Android SDK 的 Robolectric 原生运行时必须使用独立测试 JVM。
    systemProperty("robolectric.enabledSdks", "37")
    // Robolectric 的 API 37 共享内存初始化需要访问 JDK 的文件描述符接口。
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val testDebugUnitTestApi34 = tasks.register<Test>("testDebugUnitTestApi34") {
    group = "verification"
    description = "在独立 JVM 中运行 API 34 回归测试"
    val targetTests = tasks.named<Test>("testDebugUnitTest").get()
    // 复用 AGP 生成的测试类、资源配置及依赖；先完成目标 SDK 测试与编译。
    dependsOn(targetTests)
    testClassesDirs = targetTests.testClassesDirs
    classpath = targetTests.classpath
    workingDir = targetTests.workingDir
    javaLauncher.set(targetTests.javaLauncher)
    setJvmArgs(targetTests.jvmArgs)
    minHeapSize = targetTests.minHeapSize
    maxHeapSize = targetTests.maxHeapSize
    systemProperties(targetTests.systemProperties)
    systemProperty("robolectric.enabledSdks", "34")
    // AGP 还跟踪资源 APK、合并清单和 assets，资源变更必须使旧平台回归失效。
    inputs.files(targetTests.inputs.files)
        .withPropertyName("androidPlatformInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.configureEach {
    if (name == "test") dependsOn(testDebugUnitTestApi34)
}

/**
 * 通过 AGP 公开接口接收当前项目的最终类产物，生成核心业务覆盖率报告。
 *
 * @property inputClassJars 项目类所在的 JAR 产物，不包含外部依赖。
 * @property inputClassDirectories 项目类所在的目录产物。
 */
abstract class CoreCoverageReportTask : JacocoReport() {
    @get:Classpath
    abstract val inputClassJars: ListProperty<RegularFile>

    @get:Classpath
    abstract val inputClassDirectories: ListProperty<Directory>
}

// 明确列举手写业务入口，包含其嵌套业务类；独立数据载体和 Android 适配层不在范围内。
val coreClassNames = listOf(
    "text/TextProcessor",
    "text/TextProcessingRules",
    "text/WifiQrParser",
    "text/CalendarEventParser",
    "launcher/LauncherResultPolicy",
    "ime/ImeCameraVisibilityController",
    "ime/ImeInputSession",
    "scanner/ScanSessionGate",
    "widget/TokenSelectionEngine",
    "widget/TokenGestureState",
    "widget/TokenAutoScrollState"
)
val coreClassPatterns = coreClassNames.flatMap { name ->
    listOf(
        "com/unscientificjszhai/scantoinput/$name.class",
        "com/unscientificjszhai/scantoinput/$name\$*.class"
    )
}

val coreCoverageReport = tasks.register<CoreCoverageReportTask>("coreCoverageReport") {
    group = "verification"
    description = "生成核心业务的 JaCoCo XML 和 HTML 覆盖率报告"
    dependsOn("validateCoreCoverageInputs")
    executionData.setFrom(
        layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"),
        layout.buildDirectory.file("jacoco/testDebugUnitTestApi34.exec")
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    classDirectories.setFrom(
        inputClassDirectories.map { directories ->
            directories.map { directory ->
                fileTree(directory).matching { include(coreClassPatterns) }
            }
        },
        inputClassJars.map { jars ->
            jars.map { jar ->
                zipTree(jar).matching { include(coreClassPatterns) }
            }
        }
    )
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/coreCoverageReport/coreCoverageReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/coreCoverageReport/html"))
        csv.required.set(false)
    }
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(coreCoverageReport)
            .toGet(
                ScopedArtifact.CLASSES,
                CoreCoverageReportTask::inputClassJars,
                CoreCoverageReportTask::inputClassDirectories
            )
    }
}

// 独立前置任务可防止 JaCoCo 在缺少执行数据时以 SKIPPED 代替失败。
tasks.register("validateCoreCoverageInputs") {
    group = "verification"
    description = "拒绝缺少执行数据或没有核心业务类的覆盖率输入"
    dependsOn(testDebugUnitTestApi34)
    doLast {
        val report = coreCoverageReport.get()
        check(report.executionData.files.all { it.isFile && it.length() > 0L }) {
            "核心覆盖率必须同时包含 API 34 与 API 37 的非空执行数据"
        }
        check(report.classDirectories.files.any { it.isFile && it.extension == "class" }) {
            "核心覆盖率范围没有业务类，禁止生成空报告"
        }
    }
}

tasks.register<JacocoCoverageVerification>("verifyCoreCoverage") {
    group = "verification"
    description = "要求核心业务的行覆盖率和分支覆盖率均为 100%"
    dependsOn(coreCoverageReport)
    executionData.setFrom(coreCoverageReport.map { it.executionData })
    classDirectories.setFrom(coreCoverageReport.map { it.classDirectories })
    sourceDirectories.setFrom(coreCoverageReport.map { it.sourceDirectories })
    doFirst {
        val xmlFile = coreCoverageReport.get().reports.xml.outputLocation.get().asFile
        check(xmlFile.isFile && xmlFile.length() > 0L) {
            "核心覆盖率 XML 不存在或为空：${xmlFile.absolutePath}"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val root = factory.newDocumentBuilder().parse(xmlFile).documentElement
        check(root.getElementsByTagName("class").length > 0) {
            "核心覆盖率报告没有业务类"
        }
        val classNodes = root.getElementsByTagName("class")
        val actualClassNames = (0 until classNodes.length)
            .map { (classNodes.item(it) as Element).getAttribute("name") }
            .toSet()
        val missingClassNames = coreClassNames
            .map { "com/unscientificjszhai/scantoinput/$it" }
            .filterNot { it in actualClassNames }
        check(missingClassNames.isEmpty()) {
            "核心覆盖率报告缺少预期根类：${missingClassNames.joinToString()}"
        }
        val counters = (0 until root.childNodes.length)
            .mapNotNull { root.childNodes.item(it) as? Element }
            .filter { it.tagName == "counter" }
        for (counterType in listOf("LINE", "BRANCH")) {
            val counter = counters.singleOrNull { it.getAttribute("type") == counterType }
            check(counter != null &&
                counter.getAttribute("covered").toLong() +
                counter.getAttribute("missed").toLong() > 0L) {
                "核心覆盖率报告缺少有效的 $counterType 计数"
            }
        }
    }
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "1.0".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("verifyCoreCoverage")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit
    implementation(libs.google.mlkit.barcode.scanning)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
}
