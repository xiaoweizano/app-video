package com.avideo.core.container

import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AeadTest {

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = Nonce.header(byteArrayOf(1, 2, 3, 4))
    private val aad = ByteArray(81) { 7 }

    @Test
    fun `seal and open round trip`() {
        val plaintext = "命中注定的明文".toByteArray()
        val sealed = Aead.seal(key, nonce, aad, plaintext)
        assertEquals(plaintext.size + 16, sealed.size)
        assertContentEquals(plaintext, Aead.open(key, nonce, aad, sealed))
    }

    @Test
    fun `empty plaintext seal is exactly one tag`() {
        val tag = Aead.tagOnly(key, nonce, aad)
        assertEquals(16, tag.size)
        assertContentEquals(ByteArray(0), Aead.open(key, nonce, aad, tag))
    }

    @Test
    fun `tampered ciphertext fails`() {
        val sealed = Aead.seal(key, nonce, aad, ByteArray(32))
        sealed[3] = (sealed[3] + 1).toByte()
        assertFailsWith<AEADBadTagException> { Aead.open(key, nonce, aad, sealed) }
    }

    @Test
    fun `tampered aad fails`() {
        val sealed = Aead.seal(key, nonce, aad, ByteArray(32))
        val otherAad = aad.copyOf().also { it[0] = 9 }
        assertFailsWith<AEADBadTagException> { Aead.open(key, nonce, otherAad, sealed) }
    }

    @Test
    fun `wrong key fails`() {
        val sealed = Aead.seal(key, nonce, aad, ByteArray(32))
        val otherKey = ByteArray(32) { (it + 1).toByte() }
        assertFailsWith<AEADBadTagException> { Aead.open(otherKey, nonce, aad, sealed) }
    }

    /**
     * GCM 是确定性的：同一 key + 同一 nonce + 同一 AAD + 同一明文 → 同一密文。
     * 这条断言本身很弱（它是 GCM 的性质，不是我们的代码），但它把一条链子钉住了：
     * **不重号完全依赖 NonceTest 里的 counter 分配**——只要分配错了，
     * 两条不同的明文就会在同一个 nonce 下产出可比的密文。
     */
    @Test
    fun `seal is deterministic for the same key nonce aad and plaintext`() {
        val a = Aead.seal(key, nonce, aad, ByteArray(16) { 1 })
        val b = Aead.seal(key, nonce, aad, ByteArray(16) { 1 })
        assertContentEquals(a, b)
        assertEquals(a.size, Aead.seal(key, nonce, aad, ByteArray(16) { 2 }).size)
    }
}
