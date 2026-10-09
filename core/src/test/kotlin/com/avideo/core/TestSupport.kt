package com.avideo.core

import com.avideo.core.crypto.RandomSource
import java.io.File
import java.nio.file.Files
import java.util.Random

/** 固定种子的随机源：fixture 可复现的唯一前提（tasks 8.1）。 */
class DeterministicRandomSource(seed: Long) : RandomSource {
    private val random = Random(seed)
    override fun nextBytes(dst: ByteArray) = random.nextBytes(dst)
}

fun tempDir(prefix: String = "avault-test"): File =
    Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

/** 确定性测试明文：既不重复也不含可被误判的模式。 */
fun patternBytes(len: Int): ByteArray = ByteArray(len) { i ->
    ((i * 31) xor (i shr 8) xor 0x5A).toByte()
}

/** 朴素子串查找，用于断言明文模式未出现在密文中。 */
fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}
