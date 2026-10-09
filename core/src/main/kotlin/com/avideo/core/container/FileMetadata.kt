package com.avideo.core.container

import com.avideo.core.crypto.PasswordNormalizer
import com.avideo.core.errors.MalformedContainerError
import com.avideo.core.errors.UncommittedFileError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * metadata 区的**明文**结构（在写进预留区之前）：
 *
 *   real_len(2) | name_len(2) | display_name(NFC UTF-8) | duration_ms(8) | sha256(32) | flags(1) | 零填充
 *
 * `real_len = 45 + name_len`；`real_len == 0` 表示未提交。
 * 预告区总长 = metadata_len，未用部分全部零填充。
 *
 * 注意：本类只处理明文结构。整块 metadata 区在磁盘上是它的密文
 * （design.md D4 的措辞与本模块的取舍见实现计划 D-1）。
 */
class FileMetadata(
    val displayName: String,
    val durationMs: Long,
    val sha256: ByteArray,
) {

    init {
        require(sha256.size == ContainerConstants.SHA256_LEN) {
            "sha256 must be ${ContainerConstants.SHA256_LEN} bytes"
        }
    }

    /** duration_ms == 哨兵值时表示「时长未知」。 */
    val durationKnown: Boolean get() = durationMs != ContainerConstants.DURATION_UNKNOWN

    /**
     * 把显示名截到预留区放得下为止。
     * 截断按**码点**边界，超长时**截断而非拒绝**——文件名不该成为导入失败的理由。
     */
    fun truncatedTo(metadataLen: Int): FileMetadata {
        val max = ContainerConstants.maxDisplayNameBytes(metadataLen)
        var name = PasswordNormalizer.nfc(displayName)
        while (true) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            if (bytes.size <= max) return FileMetadata(name, durationMs, sha256)
            val cut = codepointBoundary(bytes, max)
            name = PasswordNormalizer.nfc(String(bytes, 0, cut, Charsets.UTF_8))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileMetadata) return false
        return displayName == other.displayName &&
            durationMs == other.durationMs &&
            sha256.contentEquals(other.sha256)
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + sha256.contentHashCode()
        return result
    }

    override fun toString(): String =
        "FileMetadata(displayNameLength=${displayName.length}, durationMs=$durationMs, sha256=<${sha256.size}B>)"

    companion object {

        /** 占位区：全零，于是 real_len == 0。 */
        fun encodePlaceholder(metadataLen: Int): ByteArray = ByteArray(metadataLen)

        /** 未提交判据之一：明文结构的前两个字节（real_len）为 0。 */
        fun isUncommitted(metadataPlaintext: ByteArray): Boolean =
            metadataPlaintext.size >= 2 &&
                metadataPlaintext[0] == 0.toByte() &&
                metadataPlaintext[1] == 0.toByte()

        fun encode(metadata: FileMetadata, metadataLen: Int): ByteArray {
            val effective = metadata.truncatedTo(metadataLen)
            val nameBytes = effective.displayName.toByteArray(Charsets.UTF_8)
            val realLen = ContainerConstants.METADATA_FIXED_FIELDS_LEN + nameBytes.size
            require(realLen <= metadataLen) { "metadata payload $realLen does not fit in $metadataLen" }
            val out = ByteArray(metadataLen)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
            bb.putShort(realLen.toShort())
            bb.putShort(nameBytes.size.toShort())
            bb.put(nameBytes)
            bb.putLong(effective.durationMs)
            bb.put(effective.sha256)
            bb.put(ContainerConstants.FLAGS_RESERVED)
            return out
        }

        /**
         * @throws UncommittedFileError real_len == 0
         * @throws MalformedContainerError real_len 与 name_len 不一致 / name_len 越界 / flags 非 0
         */
        fun decode(metadataPlaintext: ByteArray): FileMetadata {
            if (metadataPlaintext.size < ContainerConstants.METADATA_FIXED_FIELDS_LEN) {
                throw MalformedContainerError("metadata region too small: ${metadataPlaintext.size}")
            }
            val bb = ByteBuffer.wrap(metadataPlaintext).order(ByteOrder.BIG_ENDIAN)
            val realLen = bb.short.toInt() and 0xFFFF
            if (realLen == 0) throw UncommittedFileError()
            val nameLen = bb.short.toInt() and 0xFFFF
            if (nameLen > metadataPlaintext.size - ContainerConstants.METADATA_FIXED_FIELDS_LEN) {
                throw MalformedContainerError("display name length $nameLen exceeds the reserved region")
            }
            if (realLen != ContainerConstants.METADATA_FIXED_FIELDS_LEN + nameLen) {
                throw MalformedContainerError("real length $realLen is inconsistent with name length $nameLen")
            }
            val displayName = String(metadataPlaintext, 4, nameLen, Charsets.UTF_8)
            bb.position(4 + nameLen)
            val durationMs = bb.long
            val sha256 = ByteArray(ContainerConstants.SHA256_LEN).also { bb.get(it) }
            val flags = bb.get()
            if (flags != ContainerConstants.FLAGS_RESERVED) {
                throw MalformedContainerError("reserved metadata flags must be zero, got $flags")
            }
            return FileMetadata(displayName, durationMs, sha256)
        }

        /** 返回 ≤ max 的最大码点边界。 */
        private fun codepointBoundary(bytes: ByteArray, max: Int): Int {
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                val len = when {
                    b < 0x80 -> 1
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                if (i + len > max) break
                i += len
            }
            return i
        }
    }
}
