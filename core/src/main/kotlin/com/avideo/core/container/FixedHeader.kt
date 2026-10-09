package com.avideo.core.container

import com.avideo.core.errors.MalformedContainerError
import com.avideo.core.errors.UnsupportedContainerVersionError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 固定头 81 字节：
 *
 *   0..3   magic(4)
 *   4      version(1) = 1
 *   5..64  wrapped_file_key(60)
 *   65..68 base_nonce(4)
 *   69..76 plaintext_len(8)
 *   77..80 metadata_len(4)
 *
 * 所有多字节整数为大端序。数据区起点由 [OffsetMapping.dataStart] 用这里的 metadata_len
 * 算出——这个字段被 header_tag 认证，改它会把 data_start 指到别处，所以必须被拦住。
 *
 * 本类不持有任何密钥，也不做任何解密：它只负责结构与整数。
 */
class FixedHeader(
    val version: Byte,
    val wrappedFileKey: ByteArray,
    val baseNonce: ByteArray,
    val plaintextLen: Long,
    val metadataLen: Int,
) {

    init {
        require(wrappedFileKey.size == ContainerConstants.WRAPPED_FILE_KEY_LEN) {
            "wrapped file key must be ${ContainerConstants.WRAPPED_FILE_KEY_LEN} bytes"
        }
        require(baseNonce.size == ContainerConstants.BASE_NONCE_LEN) {
            "base nonce must be ${ContainerConstants.BASE_NONCE_LEN} bytes"
        }
        require(plaintextLen >= 0) { "plaintext length must be non-negative" }
        require(metadataLen > 0) { "metadata length must be positive" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(ContainerConstants.FIXED_HEADER_LEN)
        ContainerConstants.MAGIC.copyInto(out, OFF_MAGIC)
        out[OFF_VERSION] = version
        wrappedFileKey.copyInto(out, OFF_WRAPPED_FILE_KEY)
        baseNonce.copyInto(out, OFF_BASE_NONCE)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(OFF_PLAINTEXT_LEN, plaintextLen)
        bb.putInt(OFF_METADATA_LEN, metadataLen)
        return out
    }

    /** 供 commit 阶段按实读长度修正头部（计划 D-2）。 */
    fun withPlaintextLen(newPlaintextLen: Long): FixedHeader =
        FixedHeader(version, wrappedFileKey, baseNonce, newPlaintextLen, metadataLen)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FixedHeader) return false
        return version == other.version &&
            wrappedFileKey.contentEquals(other.wrappedFileKey) &&
            baseNonce.contentEquals(other.baseNonce) &&
            plaintextLen == other.plaintextLen &&
            metadataLen == other.metadataLen
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + wrappedFileKey.contentHashCode()
        result = 31 * result + baseNonce.contentHashCode()
        result = 31 * result + plaintextLen.hashCode()
        result = 31 * result + metadataLen
        return result
    }

    /** 刻意不含任何密钥字节。 */
    override fun toString(): String =
        "FixedHeader(version=$version, plaintextLen=$plaintextLen, metadataLen=$metadataLen, " +
            "wrappedFileKey=<redacted ${wrappedFileKey.size}B>, baseNonce=<redacted ${baseNonce.size}B>)"

    companion object {
        const val OFF_MAGIC = 0
        const val OFF_VERSION = 4
        const val OFF_WRAPPED_FILE_KEY = 5
        const val OFF_BASE_NONCE = 65
        const val OFF_PLAINTEXT_LEN = 69
        const val OFF_METADATA_LEN = 77

        /** metadata 预留区的合理性上界：超过它一定是结构损坏而不是「预留区调大了」。 */
        const val MAX_METADATA_LEN = 1 shl 20

        fun decode(bytes: ByteArray): FixedHeader {
            if (bytes.size != ContainerConstants.FIXED_HEADER_LEN) {
                throw MalformedContainerError(
                    "fixed header must be ${ContainerConstants.FIXED_HEADER_LEN} bytes, got ${bytes.size}"
                )
            }
            for (i in ContainerConstants.MAGIC.indices) {
                if (bytes[OFF_MAGIC + i] != ContainerConstants.MAGIC[i]) {
                    throw MalformedContainerError("bad container magic")
                }
            }
            val version = bytes[OFF_VERSION]
            if (version != ContainerConstants.VERSION) throw UnsupportedContainerVersionError(version.toInt())

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val plaintextLen = bb.getLong(OFF_PLAINTEXT_LEN)
            val metadataLen = bb.getInt(OFF_METADATA_LEN)
            if (plaintextLen < 0) throw MalformedContainerError("plaintext length must be non-negative")
            if (metadataLen <= 0 || metadataLen > MAX_METADATA_LEN) {
                throw MalformedContainerError("metadata length out of range: $metadataLen")
            }
            return FixedHeader(
                version = version,
                wrappedFileKey = bytes.copyOfRange(OFF_WRAPPED_FILE_KEY, OFF_BASE_NONCE),
                baseNonce = bytes.copyOfRange(OFF_BASE_NONCE, OFF_PLAINTEXT_LEN),
                plaintextLen = plaintextLen,
                metadataLen = metadataLen,
            )
        }
    }
}
