package com.avideo.core

import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileWriter
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.crypto.RandomSource
import java.io.File
import java.nio.file.Files
import java.util.Random
import kotlin.test.assertFailsWith

/** 固定种子的随机源：fixture 可复现的唯一前提（tasks 8.1）。 */
class DeterministicRandomSource(seed: Long) : RandomSource {
    private val random = Random(seed)
    override fun nextBytes(dst: ByteArray) = random.nextBytes(dst)
}

fun tempDir(prefix: String = "avault-test"): File =
    Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

/**
 * 复现「写占位写到一半被杀」的磁盘状态：`writePlaceholder` 的前 [afterWrites] 次写入成功、
 * 之后中断（计划 D-3 的替身）。
 *
 * `writePlaceholder` 的写入序列是 ① 固定头+header_tag（0..97）② metadata 区（97..1121）
 * ③ 全零 metadata_tag（1121..1137），所以 [afterWrites] = 1 得到 97 字节、= 2 得到 1121 字节。
 * 这两种文件都属于「连 tag 的位置都还没写到」的中间态：MUST 判为**未提交**，
 * MUST NOT 判为「仓库损坏」（spec: import-commit-protocol「未提交文件的恢复」）。
 */
fun interruptedPlaceholder(
    file: File,
    masterKey: ByteArray,
    afterWrites: Int,
    metadataLen: Int = ContainerConstants.METADATA_LEN,
): File {
    val recording = RecordingChannel(FileContainerChannel.openForWrite(file), failAtWriteIndex = afterWrites)
    val writer = EncryptedFileWriter(recording, masterKey, metadataLen, DeterministicRandomSource(seed = 7))
    try {
        assertFailsWith<SimulatedCrashError> { writer.writePlaceholder(3_000L) }
    } finally {
        writer.close()
    }
    return file
}

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
