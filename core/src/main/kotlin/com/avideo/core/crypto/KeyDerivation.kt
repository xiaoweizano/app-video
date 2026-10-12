package com.avideo.core.crypto

/**
 * 密码派生接口（design.md D2）。`argon2kt` 是 Android AAR，在纯 JVM 模块里用不了，
 * 所以这一层必须是接口：JVM 侧用 argon2-jvm，Android 侧 stage-1 再接 argon2kt。
 *
 * 传入的 password MUST 已经是 NFC 形式——由 [MasterKeyDeriver] 保证，不要直接调用本接口。
 *
 * **Android MUST NOT 调用 [MasterKeyDeriver.forJvmOnly]**：那个实现依赖 argon2-jvm
 * （经 JNA 加载原生库），Android 上会构建成功、只在运行时崩。Android 侧 MUST
 * 自己构造 `MasterKeyDeriver(AndroidKeyDerivation())`（stage-1 接入 argon2kt）。
 * 接口本身与平台无关，两端 MUST 产出相同的 32 字节（`CrossImplementationFixtureTest` 守住）。
 */
interface KeyDerivation {
    /** 返回 32 字节主密钥。 */
    fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray
}

/**
 * **唯一允许的 KDF 入口**：先做 NFC 规范化，再交给实现。
 * 规范化放在这里而不是各调用点，是为了满足 spec 的
 * 「该行为 MUST 被任何调用 KDF 的路径共同遵守，不依赖调用方自觉」。
 */
class MasterKeyDeriver(private val impl: KeyDerivation) {

    fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val normalized = PasswordNormalizer.normalize(password)
        try {
            return impl.derive(normalized, salt, params)
        } finally {
            PasswordNormalizer.wipe(normalized)
        }
    }

    companion object {
        /**
         * 纯 JVM 实现（argon2-jvm，经 JNA 加载原生库）。
         *
         * **Android MUST NOT 调用本方法。** 它没有任何内部类型出现在签名里，所以 Android 代码
         * 调它是**编译通过、运行时才崩**（JNA 在 Android 上加载不了原生库）。名字刻意带
         * `forJvmOnly`，就是为了让误用在阅读与评审阶段就被看见；Android 侧 MUST 改成
         * `MasterKeyDeriver(AndroidKeyDerivation())`（stage-1）。
         *
         * 「把 JNA 的原生库从 APK 里排掉」是 AGP 的事，属于 stage-1，不在本模块处理。
         */
        fun forJvmOnly(): MasterKeyDeriver = MasterKeyDeriver(JvmKeyDerivation())
    }
}
