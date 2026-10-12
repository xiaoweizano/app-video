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
    fun `round trip keeps salt and params`() {
        val original = sample()
        val decoded = VaultMeta.decode(original.encode())
        // version 不做往返断言：create 只写 VERSION、decode 又在返回前拒掉任何别的值，
        // 断言它等于 VERSION 就是拿两个编译期常量互比。真正能变的是盐与参数。
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
        assertTrue(!first.kdfSalt.contentEquals(second.kdfSalt))
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
        // 改 magic 会连带把 crc32 弄坏，那样即使删掉 magic 校验这条测试也照样绿。
        // 先把校验和按新内容重算回去，确保失败只可能是 magic 这条判据拦下的。
        val fixed = bytes.copyOf()
        java.nio.ByteBuffer.wrap(fixed, 33, 4).order(java.nio.ByteOrder.BIG_ENDIAN)
            .putInt(VaultMeta.crc32Of(fixed.copyOf(33)).toInt())
        assertFailsWith<VaultMetaFormatError> { VaultMeta.decode(fixed) }
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

    /**
     * `.bak` MUST 与主文件**逐字节相同**，且读取优先级必须是「主 → 备」。
     *
     * 注意这里**不能只断言两个文件都是 37 字节**：那样「备份由另一个 salt 生成」这类
     * 不再镜像的回归照样全绿（两个文件都是 37 字节），而 `.bak` 一旦偏离就失去了它存在的意义。
     * 还要单独断言「目录未被动过时 load 必须说 fromBackup=false」——否则
     * 「把两份**相同**的边车判成 BothValid（叫调用方两个都试）」这种回归也不会被发现。
     */
    @Test
    fun `write mirrors bak byte for byte and read prefers the primary`() {
        val dir = tempDir()
        val meta = sample()
        VaultMetaIO.write(dir, meta)

        val primaryBytes = File(dir, VaultMetaIO.FILE_NAME).readBytes()
        val backupBytes = File(dir, VaultMetaIO.BACKUP_NAME).readBytes()
        assertEquals(VaultMeta.ENCODED_LEN, primaryBytes.size)
        assertContentEquals(primaryBytes, backupBytes)          // 镜像：逐字节相同
        assertContentEquals(meta.encode(), primaryBytes)        // 而且写进去的就是这份 meta

        // 目录未被动过 → 主文件可用，且必须报 fromBackup=false
        val untouched = assertIs<VaultMetaIO.LoadResult.Ok>(VaultMetaIO.load(dir))
        assertEquals(false, untouched.fromBackup)

        // 主文件被截断 → 回退到备份
        File(dir, VaultMetaIO.FILE_NAME).writeBytes(ByteArray(10))
        val ok = assertIs<VaultMetaIO.LoadResult.Ok>(VaultMetaIO.load(dir))
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
        assertTrue("「仓库损坏」与「密码错误」必须是不同类型") {
            corrupted !is com.avideo.core.errors.WrongPasswordError
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
        val meta = sample()
        VaultMetaIO.write(dir, meta)
        File(dir, VaultMetaIO.BACKUP_NAME).delete()
        // 断言返回的确实是写进去的那份（而不是「调用没抛异常」——后者靠返回类型非空恒真）
        assertContentEquals(meta.kdfSalt, VaultMetaIO.loadOrThrow(dir).kdfSalt)
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
        assertTrue(wrong !is VaultCorruptedError)
    }
}
