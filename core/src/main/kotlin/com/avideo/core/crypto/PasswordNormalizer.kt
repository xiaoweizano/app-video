package com.avideo.core.crypto

import java.text.Normalizer
import java.util.Arrays

/**
 * 密码的 Unicode NFC 规范化（F4.1 / spec: vault-crypto）。
 * MUST 发生在编码为 UTF-8 之前 —— 本模块的实现都是先规范化 CharArray，
 * 再交给 KDF（由 KDF 自己做 UTF-8 编码），所以顺序天然正确。
 */
object PasswordNormalizer {

    fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    /** 返回**新**数组；调用方用完负责 [wipe]。 */
    fun normalize(password: CharArray): CharArray = nfc(String(password)).toCharArray()

    fun wipe(chars: CharArray) = Arrays.fill(chars, '\u0000')

    fun wipe(bytes: ByteArray) = Arrays.fill(bytes, 0)
}
