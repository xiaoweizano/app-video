package com.avideo.core.container

/**
 * 明文偏移 ↔ 磁盘偏移的映射。
 *
 *   P      = CHUNK_PLAINTEXT
 *   stride = CHUNK_STRIDE
 *   chunk(off) = off / P
 *   disk(off)  = data_start + chunk(off) * stride + (off % P)
 *
 * data_start 一律由 `113 + metadata_len` 的等价公式算出，**不许硬编码**（design.md D3）：
 * metadata_len 在文件头里且被 header_tag 认证，所以将来调大预留区
 * 不是破坏性格式变更——前提是偏移真的按公式算。
 */
object OffsetMapping {

    /** header_tag 的起始偏移 = FIXED_HEADER_LEN。 */
    val HEADER_TAG_OFFSET: Long = ContainerConstants.FIXED_HEADER_LEN.toLong()

    /** metadata 预留区的起始偏移 = 固定头 + header_tag。 */
    val METADATA_OFFSET: Long =
        (ContainerConstants.FIXED_HEADER_LEN + ContainerConstants.HEADER_TAG_LEN).toLong()

    fun metadataTagOffset(metadataLen: Int): Long = METADATA_OFFSET + metadataLen

    /** = 113 + metadata_len。 */
    fun dataStart(metadataLen: Int): Long =
        metadataTagOffset(metadataLen) + ContainerConstants.METADATA_TAG_LEN

    fun chunkCount(plaintextLen: Long): Long {
        require(plaintextLen >= 0) { "plaintext length must be non-negative" }
        if (plaintextLen == 0L) return 0L
        return (plaintextLen - 1) / ContainerConstants.CHUNK_PLAINTEXT + 1
    }

    fun diskOffset(plaintextOffset: Long, metadataLen: Int): Long {
        require(plaintextOffset >= 0) { "plaintext offset must be non-negative" }
        val p = ContainerConstants.CHUNK_PLAINTEXT.toLong()
        return dataStart(metadataLen) +
            (plaintextOffset / p) * ContainerConstants.CHUNK_STRIDE +
            (plaintextOffset % p)
    }

    /** 末块的明文长度。plaintextLen == 0 时返回 0。 */
    fun lastChunkPlaintextLen(plaintextLen: Long): Long {
        val n = chunkCount(plaintextLen)
        if (n == 0L) return 0L
        return plaintextLen - (n - 1) * ContainerConstants.CHUNK_PLAINTEXT
    }

    /** 完整密文文件的期望字节数。 */
    fun expectedFileSize(plaintextLen: Long, metadataLen: Int): Long {
        if (plaintextLen == 0L) return dataStart(metadataLen)
        val n = chunkCount(plaintextLen)
        return dataStart(metadataLen) +
            (n - 1) * ContainerConstants.CHUNK_STRIDE +
            lastChunkPlaintextLen(plaintextLen) +
            ContainerConstants.AEAD_TAG_LEN
    }
}
