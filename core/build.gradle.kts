plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Argon2id：不在 Android/JCE 内，必须引第三方原生库（ARCHITECTURE.md 依赖表）。
    // 该 artifact 自带 win32-x86-64 / linux-* / darwin-* 的原生库，JNA 为 runtime 作用域。
    implementation("de.mkammerer:argon2-jvm:2.12")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // 纯 Java 的第二实现，**仅测试**：fixture 用它生成，再用 argon2-jvm 校验，
    // 这样跨实现一致性才不是自证（spec: vault-crypto「KDF 通过接口暴露，实现可替换」）。
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.81")
    // JUnit 5 的 launcher 与 jupiter 版本号不同（5.10.2 ↔ 1.10.2），必须显式给版本
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
    // 生产参数（m=64 MiB）的基准测试默认不跑；需要时 ./gradlew :core:test -Pbenchmark=true
    systemProperty("avault.benchmark", providers.gradleProperty("benchmark").getOrElse("false"))
}

// 跨实现 fixture 的重新生成入口（Task 10）。产物提交进仓库，正常流程不需要跑。
tasks.register<JavaExec>("generateFixtures") {
    group = "verification"
    description = "重新生成 src/test/resources/fixtures 下的跨实现 fixture"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.avideo.core.fixture.GenerateFixturesKt")
    workingDir = projectDir
}
