package com.avideo.core.container

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 单块原语。密钥 32 字节，tag 128 位。
 * `sealed` 的磁盘布局就是 `ciphertext || tag(16)` —— 与 JCE 的 doFinal 输出一致。
 *
 * 认证失败抛 [AEADBadTagException]；调用方必须把它翻译成本层的错误类型
 * （[com.avideo.core.errors.VaultError] 的子类），不要让 JCE 异常或其消息漏到日志/UI。
 */
object Aead {

    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private val EMPTY = ByteArray(0)

    /** tag 长度 = [ContainerConstants.AEAD_TAG_LEN]。 */
    const val TAG_LEN = ContainerConstants.AEAD_TAG_LEN

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(requireKey(key), "AES"), GCMParameterSpec(TAG_BITS, requireNonce(nonce)))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * @throws javax.crypto.AEADBadTagException 认证失败。
     */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= TAG_LEN) { "sealed input must be at least $TAG_LEN bytes, got ${sealed.size}" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(requireKey(key), "AES"), GCMParameterSpec(TAG_BITS, requireNonce(nonce)))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(sealed)
    }

    /** 空明文封装：结果恰好 16 字节，用于 header_tag。 */
    fun tagOnly(key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray = seal(key, nonce, aad, EMPTY)

    /**
     * 校验只含 tag 的封装。
     * @throws javax.crypto.AEADBadTagException 认证失败。
     */
    fun verifyTagOnly(key: ByteArray, nonce: ByteArray, aad: ByteArray, tag: ByteArray) {
        require(tag.size == TAG_LEN) { "tag must be $TAG_LEN bytes, got ${tag.size}" }
        open(key, nonce, aad, tag)
    }

    private fun requireKey(key: ByteArray): ByteArray {
        require(key.size == ContainerConstants.KEYS_LEN) { "AEAD key must be ${ContainerConstants.KEYS_LEN} bytes" }
        return key
    }

    private fun requireNonce(nonce: ByteArray): ByteArray {
        require(nonce.size == Nonce.LEN) { "nonce must be ${Nonce.LEN} bytes" }
        return nonce
    }
}
