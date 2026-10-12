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
 *   ② 读 metadata_tag；**全零 ⇒ 未提交**（无需密码，也不必解包）。
 *      位置读不到 tag 时分两种：文件短到不可能是已提交容器（`size < dataStart(metadata_len)`）
 *      ⇒ 同样是**未提交**（写占位写到一半被杀）；长度够却读不满 ⇒ 截断。
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
        // 判据 MUST NOT 用 `offset + len`：offset 来自外部输入（stage-2 的 Range 头），
        // `offset > Long.MAX_VALUE - len` 时那个加法溢出成负数，越界会被算成越界内，
        // 一路走到 ByteArray(sealedLen) 抛 NegativeArraySizeException —— 不是 VaultError 家族。
        // 先判 `offset > plaintextLength`，后面的减法就非负、不可能溢出。
        if (offset < 0 || offset > plaintextLength || len > plaintextLength - offset) {
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
            // 上面的越界判据保证 cursor < plaintextLength，所以 take 恒 > 0。
            // 这条是**守卫**：将来若有人放宽那个判据，这里要炸得响亮，而不是死循环挂住。
            check(take > 0) { "range read made no progress at offset $cursor" }
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
         * 打开一个密文文件。[open] 在**成功与失败两种情况下都取得 channel 的所有权**：
         * 成功后由返回实例的 `close()` 关闭，失败时由本方法自己关闭（否则调用方在
         * 「扫一遍满是损坏文件的目录」这条路径上会一条一个 fd 泄漏到 GC）。
         *
         * @throws UncommittedFileError 未提交（metadata_tag 全零、real_len == 0，或文件短到
         *   不可能是已提交容器 —— 占位的写入中途被杀，见校验链 ②）
         * @throws KeyUnwrapFailedError 主密钥不对，或 wrapped_file_key 被篡改
         * @throws HeaderAuthenticationFailedError 固定头被篡改
         * @throws MetadataAuthenticationFailedError metadata 区被篡改
         * @throws TruncatedContainerError 文件被截断
         */
        fun open(channel: ContainerChannel, masterKey: ByteArray): EncryptedFileReader {
            // 解包出密钥后才有东西要清零；在那之前它一直是 null。
            var dataKey: ByteArray? = null
            try {
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
                    // 读不满 tag 的位置有两种可能，MUST 分开判：
                    //   · 81 ≤ size < dataStart(metadata_len)：固定头已解析、metadata_len 已知，
                    //     而已提交的容器至少有 dataStart 字节 ⇒ 它不可能是已提交容器。
                    //     这就是「写占位写到一半被杀」留下的中间态（97 / 1121 字节），
                    //     判**未提交**——spec 的 MUST NOT：中断的导入不得被判为「仓库损坏」。
                    //   · 长度够却读不满：真的短读，按截断处理（MUST NOT 当成完整文件）。
                    if (channel.size() < OffsetMapping.dataStart(header.metadataLen)) {
                        throw UncommittedFileError()
                    }
                    throw TruncatedContainerError(tagOffset + tag.size, channel.size())
                }
                if (tag.all { it == 0.toByte() }) throw UncommittedFileError()

                // ③ data key（自认证）
                val key = KeyWrapping.unwrap(masterKey, header.wrappedFileKey)
                dataKey = key

                // ④ 头部完整性：tag 紧跟在固定头之后（偏移 81）。
                // 这一次读**必然读满**：② 已经在 97 + metadataLen ≥ 98 处成功读满 16 字节，
                // 所以 81..97 一定存在，读不满的分支不可达，不留死代码。
                val headerTag = ByteArray(ContainerConstants.HEADER_TAG_LEN)
                channel.readAt(OffsetMapping.HEADER_TAG_OFFSET, headerTag, headerTag.size)
                try {
                    Aead.verifyTagOnly(key, Nonce.header(header.baseNonce), headerBytes, headerTag)
                } catch (e: AEADBadTagException) {
                    throw HeaderAuthenticationFailedError(cause = e)
                }

                // ⑤ metadata 区
                val sealed = ByteArray(header.metadataLen + ContainerConstants.METADATA_TAG_LEN)
                if (channel.readAt(OffsetMapping.METADATA_OFFSET, sealed, sealed.size) != sealed.size) {
                    throw TruncatedContainerError(
                        OffsetMapping.dataStart(header.metadataLen),
                        channel.size(),
                    )
                }
                val metadataPlaintext = try {
                    Aead.open(key, Nonce.metadata(header.baseNonce), headerBytes, sealed)
                } catch (e: AEADBadTagException) {
                    throw MetadataAuthenticationFailedError(cause = e)
                }
                // 含 UncommittedFileError（real_len == 0，带密码时的第二条判据）与结构错误。
                val metadata = FileMetadata.decode(metadataPlaintext)

                // ⑥ 拖尾长度可判定
                val expected = OffsetMapping.expectedFileSize(header.plaintextLen, header.metadataLen)
                val actual = channel.size()
                if (actual < expected) throw TruncatedContainerError(expected, actual)

                return EncryptedFileReader(
                    channel = channel,
                    metadataLen = header.metadataLen,
                    baseNonce = header.baseNonce,
                    dataKey = key,
                    plaintextLength = header.plaintextLen,
                    metadata = metadata,
                )
            } catch (t: Throwable) {
                // 单一失败路径：有 key 就清零，然后归还 channel，最后让原始异常原样传播
                // （MUST NOT 把 AEADBadTagException 之类的 JCE 异常漏出去，上面已逐个翻译好）。
                // 整段都在 try 里，所以「主密钥不对」这条最常见的失败路径也走这里，
                // 不会成为漏网的 fd。
                if (dataKey != null) PasswordNormalizer.wipe(dataKey)
                runCatching { channel.close() }
                throw t
            }
        }
    }
}
