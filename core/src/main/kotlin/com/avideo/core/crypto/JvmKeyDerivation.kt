package com.avideo.core.crypto

import de.mkammerer.argon2.Argon2Factory

/**
 * 纯 JVM 的 Argon2id：走 `de.mkammerer:argon2-jvm`（JNA 包装参考实现 C 库）。
 * - 模式 ARGON2id；输出长度 = 库默认的 32 字节；版本 = 1.3（RFC 9106）。
 * - `rawHash(iterations, memoryKiB, parallelism, password, salt)` 中 memory 的单位就是 KiB。
 * - 注意 argon2-jvm 2.12 里 `Argon2Types` 是 `Argon2Factory` 的嵌套枚举（旧版本才是顶层类）。
 *
 * internal：调用方只许依赖接口与 [MasterKeyDeriver]，不许引用具体 Argon2 库类型
 * （spec: vault-crypto「接口不泄漏实现细节」）。
 */
internal class JvmKeyDerivation : KeyDerivation {
    override fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val argon2 = Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
        return argon2.rawHash(
            params.iterations,
            params.memoryKib,
            params.parallelism,
            password,
            salt,
        )
    }
}
