package com.example.avideo.crypto

import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.KeyDerivation
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.util.Arrays

/**
 * `:core` 的 [KeyDerivation] 在 Android 上的实现，用 `argon2kt`（NDK 编译的 .so）。
 *
 * 为什么不能直接用 `:core` 里的 JVM 实现：那是 `argon2-jvm`（JNA 包装），
 * 它的原生库是给桌面平台的，在 Android 上加载不了。两者必须产出**相同的 32 字节**，
 * 这一点由 `:core` 的 T7 跨实现 fixture 守住（Argon2id / V13 / 32 字节 / m 以 KiB 计）。
 *
 * 两个容易写错的点：
 *  1. **参数必须显式传全**。argon2kt 的默认值是 `t=1 / p=2`，与我们的生产参数 `t=3 / p=1` 不同，
 *     漏传会静默产出另一把主密钥 —— 表现为「密码明明对却解不开任何文件」。
 *  2. `Argon2Kt()` 构造会**加载 .so**，官方注释要求不在主线程调用。调用方负责放到后台线程
 *     （见 `VaultGateway` 里的 `withContext(Dispatchers.Default)`）。
 *
 * 传入的 password 已由 `MasterKeyDeriver` 做过 NFC 规范化；这里只做 UTF-8 编码，
 * 与 `:core` 的 JVM 实现（argon2-jvm 内部同样按 UTF-8 处理 char[]）保持一致。
 */
class AndroidKeyDerivation : KeyDerivation {

    override fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            return Argon2Kt()
                .hash(
                    mode = Argon2Mode.ARGON2_ID,
                    password = passwordBytes,
                    salt = salt,
                    tCostInIterations = params.iterations,
                    mCostInKibibyte = params.memoryKib,
                    parallelism = params.parallelism,
                    hashLengthInBytes = HASH_LENGTH_BYTES,
                    version = Argon2Version.V13,
                )
                .rawHashAsByteArray()
        } finally {
            Arrays.fill(passwordBytes, 0)
        }
    }

    private companion object {
        /** 与 `:core` 的 `ContainerConstants.KEYS_LEN` 一致。 */
        const val HASH_LENGTH_BYTES = 32
    }
}
