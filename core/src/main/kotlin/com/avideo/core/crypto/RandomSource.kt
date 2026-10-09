package com.avideo.core.crypto

import java.security.SecureRandom

/**
 * 随机字节的唯一来源。生产用 [secure]；测试/fixture 用固定种子实现，
 * 否则跨平台 fixture 无法复现（tasks 8.1）。
 */
fun interface RandomSource {
    fun nextBytes(dst: ByteArray)

    companion object {
        fun secure(): RandomSource {
            val rng = SecureRandom()
            return RandomSource { rng.nextBytes(it) }
        }
    }
}
