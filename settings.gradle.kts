pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "a-video"

// :core 是纯 Kotlin/JVM（容器格式 + 加解密核心），不需要 Android SDK。
include(":core")

// :app / :ui 需要 Android SDK。未安装时纳入它们会让 AGP 在配置阶段抛
// 「SDK location not found」，把纯 JVM 的 :core 也一起挡死。
// 装了 SDK（或写出 local.properties/sdk.dir）之后这两个模块自动重新纳入。
// 移除条件：stage-1 安装 Android SDK 之后，本段可以直接删掉、恢复成无条件的 include。
val hasAndroidSdk = !System.getenv("ANDROID_HOME").isNullOrBlank() ||
    !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
    file("local.properties").exists()
if (hasAndroidSdk) {
    include(":app")
    include(":ui")
}
