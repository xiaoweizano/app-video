package com.avideo.core.container

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerClassifierTest {

    private val masterKey = ByteArray(32) { it.toByte() }

    /**
     * 三分支都要走到：短读（<81 字节）、解码抛错（≥81 字节但 magic/版本/metadata_len 不合法）、
     * 以及正常已提交。只用一个 64 字节的垃圾文件只会走到第一条。
     */
    @Test
    fun `committed placeholder and malformed are told apart without a key`() {
        val dir = tempDir()
        val committed = File(dir, "a.bin")
        EncryptedFileWriter(FileContainerChannel.openForWrite(committed), masterKey, 1024, DeterministicRandomSource(1))
            .use { it.writeFrom(ByteArrayInputStream(patternBytes(3000)), "a.mp4", 3000L) }
        assertEquals(
            ContainerClassifier.State.COMMITTED,
            FileContainerChannel.openForRead(committed).use { ContainerClassifier.classify(it) },
        )

        val placeholder = File(dir, "b.bin")
        EncryptedFileWriter(FileContainerChannel.openForWrite(placeholder), masterKey, 1024, DeterministicRandomSource(2))
            .use { it.writePlaceholder(3000L) }
        assertEquals(
            ContainerClassifier.State.UNCOMMITTED,
            FileContainerChannel.openForRead(placeholder).use { ContainerClassifier.classify(it) },
        )

        // 短于固定头：读到不足 81 字节就必须判 MALFORMED
        val tooShort = File(dir, "c.bin")
        tooShort.writeBytes(patternBytes(64))
        assertEquals(
            ContainerClassifier.State.MALFORMED,
            FileContainerChannel.openForRead(tooShort).use { ContainerClassifier.classify(it) },
        )

        // ≥81 字节、但 magic 不对：必须走到 decode 抛错那条分支
        val fullLengthBadMagic = File(dir, "d.bin")
        fullLengthBadMagic.writeBytes(patternBytes(200))
        assertEquals(
            ContainerClassifier.State.MALFORMED,
            FileContainerChannel.openForRead(fullLengthBadMagic).use { ContainerClassifier.classify(it) },
        )

        // ≥81 字节、magic 正确但 version 不受支持：同样走 decode 抛错分支
        val badVersion = File(dir, "e.bin")
        val badHeader = FixedHeader(
            version = 9,
            wrappedFileKey = ByteArray(60),
            baseNonce = ByteArray(4),
            plaintextLen = 1L,
            metadataLen = 1024,
        ).encode()
        badVersion.writeBytes(badHeader + ByteArray(200))
        assertEquals(
            ContainerClassifier.State.MALFORMED,
            FileContainerChannel.openForRead(badVersion).use { ContainerClassifier.classify(it) },
        )
    }
}
