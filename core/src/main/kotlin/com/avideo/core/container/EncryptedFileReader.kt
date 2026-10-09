package com.avideo.core.container

import com.avideo.core.crypto.KeyWrapping
import com.avideo.core.crypto.PasswordNormalizer
import com.avideo.core.errors.ChunkAuthenticationFailedError
import com.avideo.core.errors.HeaderAuthenticationFailedError
import com.avideo.core.errors.MetadataAuthenticationFailedError
import com.avideo.core.errors.RangeOutOfBoundsError
import com.avideo.core.errors.TruncatedContainerError
import com.avideo.core.errors.UncommittedFileError
import java.io.Closeable
import javax.crypto.AEADBadTagException

/**
 * 密文文件的读取路径：按**明文区间**取字节，不整份解密。
 *
 * 打开时的校验链（顺序是规格的一部分）：
 *   ① 读固定头 → 结构校验（magic / version / metadata_len 合理性）
 *   ② 读 metadata_tag；**全零 ⇒ 未提交**（无需密码，也不必解包）
 *   ③ 解 wrapped_file_key（自认证）→ data key
 *   ④ 用 data key 验 header_tag
 *   ⑤ 验 + 解 metadata 区 → FileMetadata
 *   ⑥ 实际大小 < 期望大小 ⇒ 截断
 *
 * ② 必须在 ③ 之前：commit 中途被杀（正文已写、tag 未写）的文件，其头部可能还是
 * 旧的、header_tag 校验会失败——那种情况下必须判「未提交」，MUST NOT 判「仓库损坏」。
 *
 * reader 实例独占一份 data key，关闭时清零。
 */
class EncryptedFileReader private constructor(
    private val channel: ContainerChannel,
    val metadataLen: Int,
    private val baseNonce: ByteArray,
    private val dataKey: ByteArray,
    val plaintextLength: Long,
    val metadata: FileMetadata,
) : Closeable {

    private var closed = false

    /**
     * 读取明文区间 `[offset, offset + len)`。
     *
     * @throws RangeOutOfBoundsError 区间越界（MUST NOT 返回被截断的数据）
     * @throws TruncatedContainerError 磁盘上缺少块数据
     * @throws ChunkAuthenticationFailedError 块 tag 校验失败
     */
    fun read(offset: Long, len: Int): ByteArray {
        check(!closed) { "reader is closed" }
        require(len >= 0) { "length must be non-negative" }
        if (offset < 0 || offset + len > plaintextLength) {
            throw RangeOutOfBoundsError(offset, len, plaintextLength)
        }
        val out = ByteArray(len)
        if (len == 0) return out

        val p = ContainerConstants.CHUNK_PLAINTEXT.toLong()
        val chunkCount = OffsetMapping.chunkCount(plaintextLength)
        var cursor = offset
        var written = 0
        while (written < len) {
            val chunkIndex = cursor / p
            // 块起点：整块必须**从块起点**读盘再解密——disk_offset(cursor) 已经含了 withinChunk，
            // 拿它当读起点会让密文错位（GCM 直接失败），也会在末块读出文件尾。
            val chunkStart = chunkIndex * p
            val withinChunk = (cursor - chunkStart).toInt()
            // 末块的明文长度是 min(P, plaintextLength - chunkStart)，**不是** P：
            // 末块在盘上只占 lastLen + 16 字节，假定它占满 stride 会读到下一块/文件尾。
            val chunkPlaintextLen = if (chunkIndex < chunkCount - 1) {
                ContainerConstants.CHUNK_PLAINTEXT
            } else {
                (plaintextLength - chunkStart).toInt()
            }
            val take = minOf(len - written, chunkPlaintextLen - withinChunk)
            val sealedLen = chunkPlaintextLen + ContainerConstants.AEAD_TAG_LEN
            val diskOffset = OffsetMapping.diskOffset(chunkStart, metadataLen)
            val sealed = ByteArray(sealedLen)
            val got = channel.readAt(diskOffset, sealed, sealedLen)
            if (got != sealedLen) {
                throw TruncatedContainerError(
                    OffsetMapping.expectedFileSize(plaintextLength, metadataLen),
                    channel.size(),
                )
            }
            val plaintextChunk = try {
                Aead.open(dataKey, Nonce.chunk(baseNonce, chunkIndex), EMPTY_AAD, sealed)
            } catch (e: AEADBadTagException) {
                throw ChunkAuthenticationFailedError(chunkIndex, e)
            }
            plaintextChunk.copyInto(out, written, withinChunk, withinChunk + take)
            written += take
            cursor += take
        }
        return out
    }

    override fun close() {
        if (closed) return
        closed = true
        PasswordNormalizer.wipe(dataKey)
        channel.close()
    }

    companion object {

        private val EMPTY_AAD = ByteArray(0)

        /**
         * @throws UncommittedFileError 未提交（metadata_tag 全零，或 real_len == 0）
         * @throws KeyUnwrapFailedError 主密钥不对，或 wrapped_file_key 被篡改
         * @throws HeaderAuthenticationFailedError 固定头被篡改
         * @throws MetadataAuthenticationFailedError metadata 区被篡改
         * @throws TruncatedContainerError 文件被截断
         */
        fun open(channel: ContainerChannel, masterKey: ByteArray): EncryptedFileReader {
            val headerBytes = ByteArray(ContainerConstants.FIXED_HEADER_LEN)
            if (channel.readAt(0L, headerBytes, headerBytes.size) != headerBytes.size) {
                throw TruncatedContainerError(
                    ContainerConstants.FIXED_HEADER_LEN.toLong(),
                    channel.size(),
                )
            }
            val header = FixedHeader.decode(headerBytes)

            // ② 未提交判据（不需要密钥）
            val tagOffset = OffsetMapping.metadataTagOffset(header.metadataLen)
            val tag = ByteArray(ContainerConstants.METADATA_TAG_LEN)
            if (channel.readAt(tagOffset, tag, tag.size) != tag.size) {
                throw TruncatedContainerError(tagOffset + tag.size, channel.size())
            }
            if (tag.all { it == 0.toByte() }) throw UncommittedFileError()

            // ③ data key（自认证）
            val dataKey = KeyWrapping.unwrap(masterKey, header.wrappedFileKey)

            // ④ 头部完整性（tag 在固定头之后，固定偏移 81）
            val headerTag = ByteArray(ContainerConstants.HEADER_TAG_LEN)
            if (channel.readAt(OffsetMapping.HEADER_TAG_OFFSET, headerTag, headerTag.size) != headerTag.size) {
                PasswordNormalizer.wipe(dataKey)
                throw TruncatedContainerError(
                    OffsetMapping.HEADER_TAG_OFFSET + headerTag.size,
                    channel.size(),
                )
            }
            try {
                Aead.verifyTagOnly(dataKey, Nonce.header(header.baseNonce), headerBytes, headerTag)
            } catch (e: AEADBadTagException) {
                PasswordNormalizer.wipe(dataKey)
                throw HeaderAuthenticationFailedError(cause = e)
            }

            // ⑤ metadata 区
            val sealed = ByteArray(header.metadataLen + ContainerConstants.METADATA_TAG_LEN)
            if (channel.readAt(OffsetMapping.METADATA_OFFSET, sealed, sealed.size) != sealed.size) {
                PasswordNormalizer.wipe(dataKey)
                throw TruncatedContainerError(
                    OffsetMapping.dataStart(header.metadataLen),
                    channel.size(),
                )
            }
            val metadataPlaintext = try {
                Aead.open(dataKey, Nonce.metadata(header.baseNonce), headerBytes, sealed)
            } catch (e: AEADBadTagException) {
                PasswordNormalizer.wipe(dataKey)
                throw MetadataAuthenticationFailedError(cause = e)
            }
            val metadata = try {
                FileMetadata.decode(metadataPlaintext)
            } catch (e: Exception) {
                // 含 UncommittedFileError（real_len == 0，带密码时的第二条判据）与结构错误。
                PasswordNormalizer.wipe(dataKey)
                throw e
            }

            // ⑥ 拖尾长度可判定
            val expected = OffsetMapping.expectedFileSize(header.plaintextLen, header.metadataLen)
            val actual = channel.size()
            if (actual < expected) {
                PasswordNormalizer.wipe(dataKey)
                throw TruncatedContainerError(expected, actual)
            }

            return EncryptedFileReader(
                channel = channel,
                metadataLen = header.metadataLen,
                baseNonce = header.baseNonce,
                dataKey = dataKey,
                plaintextLength = header.plaintextLen,
                metadata = metadata,
            )
        }
    }
}
