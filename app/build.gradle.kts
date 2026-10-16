plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.avideo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.avideo"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // AGP 8.x 起不再默认生成 BuildConfig，而 PlayerScreen 用它决定是否加 FLAG_SECURE
        // （现逻辑：`if (BuildConfig.DEBUG == false)` —— 即只在 release 构建里加，debug 下便于排查）。
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    ndkVersion = "25.2.9519653"
}

dependencies {
    implementation(project(":ui"))

    // 容器格式与加解密核心（纯 Kotlin/JVM 模块，见 openspec stage-0）。
    // 排除 argon2-jvm 与其 JNA：:core 用它做 JVM 侧 KDF，但 Android 侧走下面的 argon2kt；
    // JNA 打包的那六个平台原生库（win32/linux/darwin…）进 APK 毫无用处。
    // 注：排除只影响运行时；:core 自己的编译类路径不受影响。若谁在 Android 上误调
    // MasterKeyDeriver.forJvmOnly()，现在会以 NoClassDefFoundError 响亮失败（而不是静默降级）。
    implementation(project(":core")) {
        exclude(group = "de.mkammerer")
        exclude(group = "net.java.dev.jna")
    }
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    // BOM 必须用 2024.02.00 或更高。2024.01.00 是不一致的组合：
    // 它把 material3 1.1.2（POM 里声明依赖 animation-core 1.4.1）与 animation-core 1.6.0 钉在一起，
    // 而 1.6.0 里 KeyframesSpecConfig.at() 的签名已变 —— 于是 CircularProgressIndicator 一渲染
    // 就抛 NoSuchMethodError（真机实测，UnlockScreen 的加载态）。
    // 压低 animation-core 不是选项：ui 1.6.0 是按 animation-core 1.6.0 编译的，压低会换成 ui 崩。
    // 方向只能是把 material3 抬到与 1.6.x 配套的 1.2.x。
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // libVLC for playback
    implementation("org.videolan.android:libvlc-all:3.6.5")

    // Argon2id KDF（**Android 侧实现**）。这里原本是 com.github.Aidoop:argon2-android:1.4.0（jitpack），
    // 全仓库无人引用且 jitpack 按需现场构建不稳定，已移除。
    // 现在用设计文档指定的 argon2kt（NDK 编译的 .so，Maven Central）。
    // 关键：它必须与 :core 的 argon2-jvm 产出**相同的 32 字节**，否则换平台解不开。
    // 由 T7 的跨实现 fixture 守住；参数必须显式传全 ——
    // argon2kt 的默认值是 t=1 / p=2，与我们的生产参数 (t=3, p=1) 不同，不能依赖默认值。
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.6.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
