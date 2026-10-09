package com.avideo.core.crypto

import com.avideo.core.errors.MalformedContainerError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Argon2id 参数，编码为 12 字节大端序：m(4) | t(4) | p(4)。
 * m 的单位是 KiB（即 Argon2 的 m_cost），所以 64 MiB 写作 65536。
 */
data class KdfParams(val memoryKib: Int, val iterations: Int, val parallelism: Int) {

    init {
        require(memoryKib > 0) { "memoryKib must be positive" }
        require(iterations > 0) { "iterations must be positive" }
        require(parallelism > 0) { "parallelism must be positive" }
    }

    fun encode(): ByteArray = ByteBuffer.allocate(ENCODED_LEN).order(ByteOrder.BIG_ENDIAN)
        .putInt(memoryKib)
        .putInt(iterations)
        .putInt(parallelism)
        .array()

    companion object {
        const val ENCODED_LEN = 12

        /** ARCHITECTURE.md 参数表：m=64 MiB, t=3, p=1。实际取值待 T21 在目标机型实测。 */
        val PRODUCTION = KdfParams(memoryKib = 64 * 1024, iterations = 3, parallelism = 1)

        /** 单元测试用的小参数（design.md D2 的退化方案同款）。 */
        val TEST = KdfParams(memoryKib = 8 * 1024, iterations = 1, parallelism = 1)

        fun decode(bytes: ByteArray): KdfParams {
            if (bytes.size != ENCODED_LEN) {
                throw MalformedContainerError("kdf params must be $ENCODED_LEN bytes, got ${bytes.size}")
            }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val m = bb.int
            val t = bb.int
            val p = bb.int
            if (m <= 0 || t <= 0 || p <= 0) {
                throw MalformedContainerError("kdf params must be positive")
            }
            return KdfParams(m, t, p)
        }
    }
}
