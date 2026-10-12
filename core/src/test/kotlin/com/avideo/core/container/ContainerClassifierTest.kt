package com.avideo.core.container

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.interruptedPlaceholder
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

    /**
     * 「写占位写到一半被杀」留下的文件：**固定头读得出来、metadata_len 已知**，而一个已提交的
     * 容器至少有 `dataStart(metadata_len)` 字节 —— 所以它不可能是已提交容器，也不该被当成
     * 结构损坏。判据 MUST 是「未提交」：spec 明写中断的导入 MUST NOT 被判为「仓库损坏」。
     * 两种中间态（固定头刚写完 = 97 字节、metadata 区刚写完 = 1121 字节）都要覆盖。
     */
    @Test
    fun `a placeholder interrupted mid write is uncommitted, not malformed`() {
        val dir = tempDir()

        val afterHeader = interruptedPlaceholder(File(dir, "a.bin"), masterKey, afterWrites = 1)
        assertEquals(
            OffsetMapping.METADATA_OFFSET,
            afterHeader.length(),
            "测试没有复现出「固定头+header_tag 已写、metadata 区未写」那个瞬间",
        )
        assertEquals(
            ContainerClassifier.State.UNCOMMITTED,
            FileContainerChannel.openForRead(afterHeader).use { ContainerClassifier.classify(it) },
        )

        val afterRegion = interruptedPlaceholder(File(dir, "b.bin"), masterKey, afterWrites = 2)
        assertEquals(
            OffsetMapping.metadataTagOffset(ContainerConstants.METADATA_LEN),
            afterRegion.length(),
            "测试没有复现出「metadata 区已写、全零 tag 未写」那个瞬间",
        )
        assertEquals(
            ContainerClassifier.State.UNCOMMITTED,
            FileContainerChannel.openForRead(afterRegion).use { ContainerClassifier.classify(it) },
        )
    }
}
