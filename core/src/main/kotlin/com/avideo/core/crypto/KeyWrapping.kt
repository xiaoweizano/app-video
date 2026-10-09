package com.avideo.core.crypto

import com.avideo.core.container.Aead
import com.avideo.core.container.ContainerConstants
import com.avideo.core.errors.KeyUnwrapFailedError
import com.avideo.core.errors.MalformedContainerError
import javax.crypto.AEADBadTagException

/**
 *   wrapped_file_key(60) = nonce(12) || ciphertext(32) || tag(16)
 *
 * 用主密钥包裹该文件自己的 data key。nonce 每个文件重新随机（每个文件只做一次）。
 * AAD 为空：这一层是**自认证**的 GCM 封装，解包失败即中止，不需要 header_tag 兜底
 * （spec: vault-crypto「解包顺序不产生循环依赖」）。
 */
object KeyWrapping {

    /** 12 + 32 + 16 */
    const val WRAPPED_LEN: Int = ContainerConstants.KEY_WRAP_NONCE_LEN +
        ContainerConstants.DATA_KEY_LEN + ContainerConstants.AEAD_TAG_LEN

    private val EMPTY_AAD = ByteArray(0)

    fun wrap(masterKey: ByteArray, dataKey: ByteArray, random: RandomSource): ByteArray {
        require(masterKey.size == ContainerConstants.KEYS_LEN) { "master key must be ${ContainerConstants.KEYS_LEN} bytes" }
        require(dataKey.size == ContainerConstants.DATA_KEY_LEN) { "data key must be ${ContainerConstants.DATA_KEY_LEN} bytes" }
        val nonce = ByteArray(ContainerConstants.KEY_WRAP_NONCE_LEN).also { random.nextBytes(it) }
        return nonce + Aead.seal(masterKey, nonce, EMPTY_AAD, dataKey)
    }

    /**
     * @throws KeyUnwrapFailedError 主密钥不对，或这 60 字节被篡改。
     */
    fun unwrap(masterKey: ByteArray, wrapped: ByteArray): ByteArray {
        require(masterKey.size == ContainerConstants.KEYS_LEN) { "master key must be ${ContainerConstants.KEYS_LEN} bytes" }
        if (wrapped.size != WRAPPED_LEN) {
            throw MalformedContainerError("wrapped file key must be $WRAPPED_LEN bytes, got ${wrapped.size}")
        }
        val nonce = wrapped.copyOfRange(0, ContainerConstants.KEY_WRAP_NONCE_LEN)
        val sealed = wrapped.copyOfRange(ContainerConstants.KEY_WRAP_NONCE_LEN, WRAPPED_LEN)
        return try {
            Aead.open(masterKey, nonce, EMPTY_AAD, sealed)
        } catch (e: AEADBadTagException) {
            throw KeyUnwrapFailedError(cause = e)
        }
    }
}
