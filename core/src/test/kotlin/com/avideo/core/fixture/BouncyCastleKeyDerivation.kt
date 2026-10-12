package com.avideo.core.fixture

import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.KeyDerivation
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * 独立的第二实现（纯 Java Bouncy Castle），**只用于测试**。
 *
 * 它存在的唯一理由：fixture 里的期望主密钥必须由一个**不是被测实现**的实现算出，
 * 否则「跨实现一致性」就是自证（spec: vault-crypto「KDF 通过接口暴露，实现可替换」要求
 * 同一密码+盐+参数在不同实现上产出完全相同的 32 字节）。
 *
 * 显式走 byte[] 重载并自己转 UTF-8，避免依赖 BC 默认的 CharToByteConverter。
 */
class BouncyCastleKeyDerivation : KeyDerivation {
    override fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withIterations(params.iterations)
                .withMemoryAsKB(params.memoryKib)
                .withParallelism(params.parallelism)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .build(),
        )
        val out = ByteArray(32)
        val written = generator.generateBytes(String(password).toByteArray(Charsets.UTF_8), out)
        require(written == out.size) { "BouncyCastle 只写了 $written 字节" }
        return out
    }
}
