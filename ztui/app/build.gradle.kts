import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本号来自 rootProject/version.properties，每次出包 +1
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.demo.zt"
    // AGP 9.1.1 supports API 37.0 and platforms/android-37.0.
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.demo.zt"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode", "1").toInt()
        versionName = versionProps.getProperty("versionName", "V1")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 使用统计后端地址。留空 = 关闭上报（代码路径直接 return，不发任何请求）。
        // 服务器就绪后，在 gradle.properties 里写一行：
        //     staticlog.statsEndpoint=https://你的域名
        // 然后重新出包即可，不需要改代码。
        val statsEndpoint = (project.findProperty("staticlog.statsEndpoint") as String?)?.trim().orEmpty()
        buildConfigField("String", "STATS_ENDPOINT", "\"$statsEndpoint\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs.getByName("debug") {
        storeFile = rootProject.file(".android/debug.keystore")
    }

    // release 签名：本地生成的自签 keystore，用于正式分发
    signingConfigs.create("release") {
        storeFile = rootProject.file(".android/sl-release.jks")
        storePassword = "staticlog-release"
        keyAlias = "staticlog"
        keyPassword = "staticlog-release"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/DEPENDENCIES",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // JVM 单测：纯逻辑验证不依赖设备
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 在 JVM 单测中是 stub（调用返回 null），
    // 这里引入真实实现，才能真的把上报体解析一遍。
    testImplementation("org.json:json:20240303")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.foundation:foundation:1.10.5")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 自带 Conscrypt：平台隐藏的 exportKeyingMaterial 在 Android 16 已移除，
    // 配对必须用 TLS 1.3 exporter，只能靠这个 provider 提供公开 API
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
