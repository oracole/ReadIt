import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.oracole.readit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oracole.readit"
        minSdk = 33
        targetSdk = 34
        versionCode = 160
        versionName = "1.11.1"
    }

    // 签名配置从 local.properties 读，那个文件不进仓库。
    // 自己构建时在里面写四行，或者干脆删掉这一段用 debug 签名。
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        val storePath = localProps.getProperty("RELEASE_STORE_FILE")
        if (!storePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 有配置就用，没有就退回 debug 签名，保证别人 clone 下来能直接构建
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // debug 也开启 Compose 的基线优化，手感更接近正式版
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // 页面导航与转场。之前手写的 navStack + AnimatedContent 是简易替代品，
    // 转场固定不跟手、新旧页面同时存在会闪一下。
    implementation("androidx.navigation:navigation-compose:2.7.7")
    // Material 官方的页面转场（SharedAxis / FadeThrough / Fade / ElevationScale）。
    // 声明是对 com.google.android.material.transition 的移植，
    // 也就是 Google 自家 app 用的那套。1.2.x 对应 Compose 1.7。
    implementation("io.github.fornewid:material-motion-compose-core:1.2.1")

    // 只为了用里面内嵌的 material-color-utilities 算配色。
    // 这是 Android 12 动态取色用的同一套算法（Hct 色彩空间 + SchemeContent），
    // 用它算出来的主题不会出现「自己调色调得很土」的问题。
    //
    // 它是个 Views 库，但这里只调用静态方法算颜色，不加载任何 MDC 主题，
    // 不会把 Views 那套引入 Compose。aar-metadata 里 minCompileSdk=1，
    // 不要求升 compileSdk。
    implementation("com.google.android.material:material:1.13.0")

    // 解析器单元测试。只测纯函数，不涉及 Android 框架。
    testImplementation("junit:junit:4.13.2")

    // 单元测试里 android.jar 的 org.json 全是空实现（调用抛 "not mocked"），
    // 必须用真实的 org.json，否则任何 JSON 解析都测不了。
    // 只在测试期生效，不进 APK。
    testImplementation("org.json:json:20240303")
}
