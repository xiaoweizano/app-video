# a-video —— R8/ProGuard 规则
#
# 目前 release 构建未启用压缩（build.gradle.kts 里 isMinifyEnabled = false），
# 所以这里先留空。这个文件必须存在：app/build.gradle.kts 的 release 块引用了它，
# 缺失时 AGP 会在配置阶段报 "file not found"。
#
# 启用压缩前需要回来补规则，至少要处理：
#   - libVLC：JNI 反射调用的类/方法必须 keep（org.videolan.libvlc.**）
#   - Kotlin/Compose 的元数据（默认规则已覆盖大部分）
#   - 本项目 :core 里的异常类型（错误分型依赖类型名，混淆后 tier 判定会失效）
