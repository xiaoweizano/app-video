package com.avideo.core.crypto

import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileWriter
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.container.FixedHeader
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 生产随机源 [RandomSource.secure] 在生产路径上**只作为默认参数**出现，
 * 除了本文件没有任何测试直接调用它：`KdfTest.random source is injectable and reproducible`
 * 走的是测试替身（`java.util.Random`）。
 *
 * 缺口很致命：把默认值改成任何一个确定性源，整个测试套件**全绿**，而每个文件都会拿到
 * 同一把 data key 与同一个 base nonce —— 同一主密钥下的 GCM nonce 复用是灾难性的。
 * 所以这里直接钉住默认路径本身，而不是钉它的替身。
 */
class RandomSourceTest {

    @Test
    fun `secure returns different non all zero bytes on every instance`() {
        val a = ByteArray(32).also { RandomSource.secure().nextBytes(it) }
        val b = ByteArray(32).also { RandomSource.secure().nextBytes(it) }
        assertTrue(a.any { it != 0.toByte() }, "secure() 返回了全零")
        assertTrue(!a.contentEquals(b), "两个 secure() 实例产出了同一串字节：默认随机源不是随机的")
    }

    /**
     * 最强的一条：**不注入随机源**、写两次同一份明文，落盘头部里的
     * `wrapped_file_key(5..65)` 与 `base_nonce(65..69)` 必须不同。
     * 这两块正是「同一主密钥下 nonce 复用」的直接来源，且它们是文件里
     * 唯一由随机源决定、又不被其它字节掩盖的区域。
     */
    @Test
    fun `writing the same plaintext twice with default arguments yields a different header`() {
        val dir = tempDir()

        fun writeOnce(name: String): ByteArray {
            val file = File(dir, name)
            // 刻意不传随机源：这里守的就是默认路径
            EncryptedFileWriter(FileContainerChannel.openForWrite(file), MASTER_KEY)
                .use { it.writeFrom(ByteArrayInputStream(patternBytes(5000)), "same.mp4", 5000L) }
            return file.readBytes()
        }

        val one = writeOnce("one.bin")
        val two = writeOnce("two.bin")

        val wrappedOne = one.copyOfRange(FixedHeader.OFF_WRAPPED_FILE_KEY, FixedHeader.OFF_BASE_NONCE)
        val wrappedTwo = two.copyOfRange(FixedHeader.OFF_WRAPPED_FILE_KEY, FixedHeader.OFF_BASE_NONCE)
        assertTrue(
            !wrappedOne.contentEquals(wrappedTwo),
            "wrapped_file_key 两次相同：密钥包裹用的 nonce 被复用了",
        )

        val nonceOne = one.copyOfRange(FixedHeader.OFF_BASE_NONCE, FixedHeader.OFF_PLAINTEXT_LEN)
        val nonceTwo = two.copyOfRange(FixedHeader.OFF_BASE_NONCE, FixedHeader.OFF_PLAINTEXT_LEN)
        assertTrue(
            !nonceOne.contentEquals(nonceTwo),
            "base_nonce 两次相同：同一主密钥下的 GCM nonce 复用",
        )
    }

    private companion object {
        val MASTER_KEY = ByteArray(ContainerConstants.KEYS_LEN) { (it * 13 + 5).toByte() }
    }
}
