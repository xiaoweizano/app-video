plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // 注意：这里**不能**声明 org.jetbrains.kotlin.plugin.compose。
    // 该插件从 2.0.0 才有，Kotlin 1.9.x 下 Compose 编译器由
    // app/build.gradle.kts 的 composeOptions.kotlinCompilerExtensionVersion 配置。
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}
