package com.avideo.core.vault

import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.RandomSource
import com.avideo.core.errors.MalformedContainerError
import com.avideo.core.errors.VaultMetaFormatError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * vault.meta —— **明文**边车：只存派生主密钥所需的东西，不含任何敏感内容。
 *
 *   magic(4) | version(1) | kdf_salt(16) | kdf_params(12) | crc32(4)   = 37 字节
 *
 * CRC32 覆盖前 33 字节、大端序。用**不带密钥**的校验和是刻意的（design.md D6）：
 * 目的是把「文件坏了」和「密码错了」分成两个可以独立回答的问题。
 * 用带密钥的 tag 反而做不到——tag 失败时无法区分两者，而且有循环
 * （派生主密钥需要 vault.meta，校验 tag 又需要主密钥）。
 */
class VaultMeta(
    val version: Byte,
    val kdfSalt: ByteArray,
    val kdfParams: KdfParams,
) {

    init {
        require(kdfSalt.size == SALT_LEN) { "kdf salt must be $SALT_LEN bytes" }
    }

    fun encode(): ByteArray {
        val body = ByteArray(BODY_LEN)
        MAGIC.copyInto(body, 0)
        body[OFF_VERSION] = version
        kdfSalt.copyInto(body, OFF_SALT)
        kdfParams.encode().copyInto(body, OFF_PARAMS)
        val crc = crc32Of(body)
        return body + ByteBuffer.allocate(CRC_LEN).order(ByteOrder.BIG_ENDIAN).putInt(crc.toInt()).array()
    }

    override fun equals(other: Any?): Boolean =
        other is VaultMeta && version == other.version &&
            kdfSalt.contentEquals(other.kdfSalt) && kdfParams == other.kdfParams

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + kdfSalt.contentHashCode()
        result = 31 * result + kdfParams.hashCode()
        return result
    }

    override fun toString(): String =
        "VaultMeta(version=$version, kdfParams=$kdfParams, kdfSalt=<redacted>)"

    companion object {
        /** "AVMT" —— 跨平台实现必须用同一取值（计划 D-5）。 */
        val MAGIC: ByteArray = byteArrayOf(0x41, 0x56, 0x4D, 0x54)

        const val VERSION: Byte = 1
        const val SALT_LEN = 16
        const val OFF_VERSION = 4
        const val OFF_SALT = 5
        const val OFF_PARAMS = 21
        const val BODY_LEN = 33                 // OFF_PARAMS + KdfParams.ENCODED_LEN
        const val CRC_LEN = 4
        const val ENCODED_LEN = BODY_LEN + CRC_LEN
        const val OFF_CRC = BODY_LEN

        fun crc32Of(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

        fun create(random: RandomSource, params: KdfParams): VaultMeta {
            val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
            return VaultMeta(VERSION, salt, params)
        }

        /**
         * @throws VaultMetaFormatError 长度 / magic / version / crc32 / params 任一不符
         *
         * 这里**只抛一种**错误类型是刻意的：`VaultMetaIO` 的回退逻辑只捕获
         * `VaultMetaFormatError`，多一种类型就多一条漏网路径（version 不受支持时漏掉回退）。
         * 因此 params 的解析错误也必须翻译成 `VaultMetaFormatError`——`KdfParams.decode`
         * 抛的是 `MalformedContainerError`，放任它逃出去就会穿过 `VaultMetaIO.readCandidate`
         * 的 catch，把回退逻辑整条绕开。
         */
        fun decode(bytes: ByteArray): VaultMeta {
            if (bytes.size != ENCODED_LEN) {
                throw VaultMetaFormatError("vault.meta must be $ENCODED_LEN bytes, got ${bytes.size}")
            }
            for (i in MAGIC.indices) {
                if (bytes[i] != MAGIC[i]) throw VaultMetaFormatError("bad vault.meta magic")
            }
            val version = bytes[OFF_VERSION]
            if (version != VERSION) throw VaultMetaFormatError("unsupported vault.meta version: $version")
            val expected = ByteBuffer.wrap(bytes, OFF_CRC, CRC_LEN).order(ByteOrder.BIG_ENDIAN).int
            val actual = crc32Of(bytes.copyOf(BODY_LEN)).toInt()
            if (expected != actual) throw VaultMetaFormatError("vault.meta crc32 mismatch")
            val params = try {
                KdfParams.decode(bytes.copyOfRange(OFF_PARAMS, BODY_LEN))
            } catch (e: MalformedContainerError) {
                throw VaultMetaFormatError("invalid kdf params in vault.meta")
            }
            return VaultMeta(
                version = version,
                kdfSalt = bytes.copyOfRange(OFF_SALT, OFF_PARAMS),
                kdfParams = params,
            )
        }
    }
}
