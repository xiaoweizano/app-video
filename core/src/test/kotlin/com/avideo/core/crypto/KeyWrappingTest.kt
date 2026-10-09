package com.avideo.core.crypto

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.errors.KeyUnwrapFailedError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeyWrappingTest {

    private val masterKey = ByteArray(32) { (it + 1).toByte() }
    private val dataKey = ByteArray(32) { (it * 3 + 2).toByte() }

    @Test
    fun `wrap is sixty bytes and unwrap recovers the data key`() {
        val wrapped = KeyWrapping.wrap(masterKey, dataKey, DeterministicRandomSource(1))
        assertEquals(60, wrapped.size)
        assertContentEquals(dataKey, KeyWrapping.unwrap(masterKey, wrapped))
    }

    @Test
    fun `wrap nonce is fresh per call`() {
        val a = KeyWrapping.wrap(masterKey, dataKey, DeterministicRandomSource(1))
        val b = KeyWrapping.wrap(masterKey, dataKey, DeterministicRandomSource(2))
        assertEquals(60, a.size)
        assertEquals(60, b.size)
        assert(!a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12))) { "包裹 nonce 必须每次重新随机" }
        assert(!a.contentEquals(b)) { "同一 data key 两次包裹的结果不应相同" }
    }

    @Test
    fun `wrong master key cannot unwrap`() {
        val wrapped = KeyWrapping.wrap(masterKey, dataKey, DeterministicRandomSource(1))
        val wrong = ByteArray(32) { (it + 9).toByte() }
        assertFailsWith<KeyUnwrapFailedError> { KeyWrapping.unwrap(wrong, wrapped) }
    }

    /** spec: vault-crypto「wrapped_file_key 被篡改 → 解包失败，且不进入 header_tag 校验阶段」。 */
    @Test
    fun `tampered wrapped key fails to unwrap`() {
        val wrapped = KeyWrapping.wrap(masterKey, dataKey, DeterministicRandomSource(1))
        for (index in intArrayOf(0, 11, 12, 43, 44, 59)) {
            val tampered = wrapped.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertFailsWith<KeyUnwrapFailedError>("第 $index 字节被篡改后仍解包成功") {
                KeyWrapping.unwrap(masterKey, tampered)
            }
        }
    }
}
