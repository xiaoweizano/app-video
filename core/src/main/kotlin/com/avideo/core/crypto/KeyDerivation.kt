package com.avideo.core.crypto

/**
 * 密码派生接口（design.md D2）。`argon2kt` 是 Android AAR，在纯 JVM 模块里用不了，
 * 所以这一层必须是接口：JVM 侧用 argon2-jvm，Android 侧 stage-1 再接 argon2kt。
 *
 * 传入的 password MUST 已经是 NFC 形式——由 [MasterKeyDeriver] 保证，不要直接调用本接口。
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
        /** 纯 JVM 实现。Android 侧实现（argon2kt）在 stage-1 接入。 */
        fun jvm(): MasterKeyDeriver = MasterKeyDeriver(JvmKeyDerivation())
    }
}
