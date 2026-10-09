package com.avideo.core.vault

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.crypto.KdfParams
import com.avideo.core.errors.VaultCorruptedError
import com.avideo.core.errors.VaultMetaFormatError
import java.io.File
import com.avideo.core.tempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VaultMetaTest {

    private val params = KdfParams.PRODUCTION

    private fun sample() = VaultMeta.create(DeterministicRandomSource(11), params)

    @Test
    fun `encoded meta is exactly thirty seven bytes`() {
        assertEquals(37, sample().encode().size)
        assertEquals(VaultMeta.ENCODED_LEN, sample().encode().size)
    }

    @Test
    fun `round trip keeps salt params and version`() {
        val original = sample()
        val decoded = VaultMeta.decode(original.encode())
        assertEquals(original.version, decoded.version)
        assertEquals(original.kdfParams, decoded.kdfParams)
        assertContentEquals(original.kdfSalt, decoded.kdfSalt)
    }

    @Test
    fun `salt is fresh per creation`() {
        // sample() 每次调用新建一个同种子的随机源，两次产出的盐必然相同——那是 fixture 的
        // 性质，不是「盐会刷新」这条不变量。要测刷新，必须让两次创建共用一个随机源：
        // 随机源状态若在创建之间推进，盐就该不同；实现若忽略随机源用常量盐，这里会失败。
        val random = DeterministicRandomSource(11)
        val first = VaultMeta.create(random, params)
        val second = VaultMeta.create(random, params)
        assert(!first.kdfSalt.contentEquals(second.kdfSalt))
    }

    /** spec: vault-meta「不需要密码即可判损坏」。 */
    @Test
    fun `crc32 catches a single flipped byte without any password`() {
        val bytes = sample().encode()
        bytes[5] = (bytes[5] + 1).toByte()
        assertFailsWith<VaultMetaFormatError> { VaultMeta.decode(bytes) }
    }

    @Test
    fun `wrong magic is rejected`() {
        val bytes = sample().encode().also { it[0] = 0x00 }
        assertFailsWith<VaultMetaFormatError> { VaultMeta.decode(bytes) }
    }

    @Test
    fun `unsupported version is rejected`() {
        val bytes = sample().encode().also { it[4] = 2 }
        // version 参与 crc32，所以先重算校验和，确保测的是 version 而不是校验和
        val fixed = bytes.copyOf()
        java.nio.ByteBuffer.wrap(fixed, 33, 4).order(java.nio.ByteOrder.BIG_ENDIAN)
            .putInt(VaultMeta.crc32Of(fixed.copyOf(33)).toInt())
        assertFailsWith<VaultMetaFormatError> { VaultMeta.decode(fixed) }
    }

    @Test
    fun `wrong length is rejected`() {
        assertFailsWith<VaultMetaFormatError> { VaultMeta.decode(sample().encode().copyOf(10)) }
    }

    /**
     * spec 的「不含敏感内容」场景。
     * 断言「扫不到 .mp4 字符串」是空转的（本来就没往里面放过文件名）；
     * 真正值得钉住的是**布局不变量**：两份只有盐不同的 meta 逐字节比对，
     * 差异只能落在盐区与（覆盖盐的）CRC 上——其他任何一个字节都不许跟着盐变。
     */
    @Test
    fun `only the salt region and its crc vary between two vault metas`() {
        val a = VaultMeta(VaultMeta.VERSION, ByteArray(VaultMeta.SALT_LEN) { 0x11 }, KdfParams.PRODUCTION).encode()
        val b = VaultMeta(VaultMeta.VERSION, ByteArray(VaultMeta.SALT_LEN) { 0x22 }, KdfParams.PRODUCTION).encode()
        assertEquals(VaultMeta.ENCODED_LEN, a.size)
        assertContentEquals(VaultMeta.MAGIC, a.copyOfRange(0, 4))

        val allowed = (VaultMeta.OFF_SALT until VaultMeta.OFF_PARAMS).toSet() +
            (VaultMeta.OFF_CRC until VaultMeta.ENCODED_LEN).toSet()
        val differing = a.indices.filter { a[it] != b[it] }
        assertTrue(differing.all { it in allowed }, "盐之外的字节跟着变了：${differing.filter { it !in allowed }}")
        assertTrue(differing.count { it in allowed && it < VaultMeta.OFF_PARAMS } >= 14, "盐应当整体参与编码")
    }

    @Test
    fun `write mirrors to bak and read falls back when the primary is truncated`() {
        val dir = tempDir()
        VaultMetaIO.write(dir, sample())
        assertEquals(37, File(dir, VaultMetaIO.FILE_NAME).length())
        assertEquals(37, File(dir, VaultMetaIO.BACKUP_NAME).length())

        File(dir, VaultMetaIO.FILE_NAME).writeBytes(ByteArray(10))
        val loaded = VaultMetaIO.load(dir)
        val ok = assertIs<VaultMetaIO.LoadResult.Ok>(loaded)
        assertEquals(true, ok.fromBackup)
    }

    @Test
    fun `both files corrupted raises vault corrupted and not wrong password`() {
        val dir = tempDir()
        VaultMetaIO.write(dir, sample())
        File(dir, VaultMetaIO.FILE_NAME).writeBytes(ByteArray(10))
        File(dir, VaultMetaIO.BACKUP_NAME).writeBytes(ByteArray(10))
        assertEquals(VaultMetaIO.LoadResult.Corrupted, VaultMetaIO.load(dir))

        // 静态类型取共同父类 VaultError：两个 final 兄弟类之间的 is 检查 Kotlin 直接报
        // 「Incompatible types」，而这里要测的正是运行期类型，不是编译期可判定的。
        val corrupted: com.avideo.core.errors.VaultError =
            assertFailsWith<VaultCorruptedError> { VaultMetaIO.loadOrThrow(dir) }
        assert(corrupted !is com.avideo.core.errors.WrongPasswordError) {
            "「仓库损坏」与「密码错误」必须是不同类型"
        }
    }

    @Test
    fun `both valid but different is reported so the caller can try both`() {
        val dir = tempDir()
        VaultMetaIO.write(dir, VaultMeta.create(DeterministicRandomSource(1), params))
        File(dir, VaultMetaIO.BACKUP_NAME)
            .writeBytes(VaultMeta.create(DeterministicRandomSource(2), params).encode())
        val result = VaultMetaIO.load(dir)
        assertIs<VaultMetaIO.LoadResult.BothValid>(result)
    }

    @Test
    fun `missing bak is tolerated`() {
        val dir = tempDir()
        VaultMetaIO.write(dir, sample())
        File(dir, VaultMetaIO.BACKUP_NAME).delete()
        assertNotNull(VaultMetaIO.loadOrThrow(dir))
    }

    @Test
    fun `missing everything is corrupted`() {
        assertEquals(VaultMetaIO.LoadResult.Corrupted, VaultMetaIO.load(tempDir()))
    }

    @Test
    fun `integrity reports each sidecar separately`() {
        val dir = tempDir()
        assertEquals(false to false, VaultMetaIO.integrityOf(dir))

        VaultMetaIO.write(dir, sample())
        assertEquals(true to true, VaultMetaIO.integrityOf(dir))

        File(dir, VaultMetaIO.FILE_NAME).writeBytes(ByteArray(10))
        assertEquals(false to true, VaultMetaIO.integrityOf(dir))
    }

    @Test
    fun `wrong password error type exists and differs from corrupted`() {
        val wrong: com.avideo.core.errors.VaultError = com.avideo.core.errors.WrongPasswordError()
        assert(wrong !is VaultCorruptedError)
    }
}
