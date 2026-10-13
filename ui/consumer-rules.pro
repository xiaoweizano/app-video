# a-video :ui —— 作为 library 对外暴露的混淆规则
#
# 该模块的 build.gradle.kts 通过 consumerProguardFiles("consumer-rules.pro") 引用本文件，
# 缺失会让 AGP 在配置阶段报错。当前 :ui 是空壳（Compose 屏幕都在 :app 里），
# 所以先留空；将来把屏幕下沉到 :ui 时，这里要补 Compose/反射相关的 keep 规则。
