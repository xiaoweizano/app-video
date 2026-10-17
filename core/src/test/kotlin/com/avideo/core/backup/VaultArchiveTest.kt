package com.avideo.core.backup

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.MasterKeyDeriver
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import com.avideo.core.vault.VaultDirectory
import com.avideo.core.vault.VaultEntry
import com.avideo.core.vault.VaultMetaIO
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 仓库归档的往返与拒绝路径。
 *
 * 这组测试守的是「逃生通道」本身：一个打不开的导出文件比没有导出更糟
 * （spec: vault-export「导出的文件能被重新导入并打开」）。
 */
class VaultArchiveTest {

    private val params = KdfParams.TEST
    private val password = "仓库密码".toCharArray()

    private class Fixture(val directory: VaultDirectory, val masterKey: ByteArray, val root: File)

    private fun newVault(): Fixture {
        val root = File(tempDir(), "vault")
        val directory = VaultDirectory(root)
        val meta = directory.create(params, DeterministicRandomSource(5))
        val masterKey = MasterKeyDeriver.forJvmOnly().derive(password, meta.kdfSalt, meta.kdfParams)
        return Fixture(directory, masterKey, root)
    }

    private fun VaultDirectory.put(uuid: String, masterKey: ByteArray, len: Int = 1000, name: String = "$uuid.mp4") {
        newWriter(uuid, masterKey).use {
            it.writeFrom(ByteArrayInputStream(patternBytes(len)), name, len.toLong(), 1234L)
        }
    }

    private fun uuid(i: Int) = "00000000-0000-4000-8000-%012d".format(i)

    private fun archiveOf(root: File): ByteArray {
        val out = ByteArrayOutputStream()
        VaultArchive.write(root, out)
        return out.toByteArray()
    }

    @Test
    fun `round trip restores the same bytes and the same entries`() {
        val vault = newVault()
        vault.directory.put(uuid(1), vault.masterKey, 1000, "第一.mp4")
        vault.directory.put(uuid(2), vault.masterKey, 2000, "第二.mkv")
        val archive = archiveOf(vault.root)

        val target = File(tempDir(), "restored")
        val result = VaultArchive.restore(ByteArrayInputStream(archive), target, allowOverwrite = false)
        val success = assertIs<VaultArchive.RestoreResult.Success>(result)
        assertEquals(2, success.entries)
        assertEquals(0, success.sampleFailures)

        // 边车逐字节相同 ⇒ 盐与参数没变 ⇒ 原密码还能解锁
        assertContentEquals(
            File(vault.root, VaultArchive.META).readBytes(),
            File(target, VaultArchive.META).readBytes(),
        )
        val restored = VaultDirectory(target)
        assertEquals(2, restored.cipherFiles().size)
        val names = restored.list(vault.masterKey)
            .filterIsInstance<VaultEntry.Committed>()
            .map { it.metadata.displayName }
            .sorted()
        assertEquals(listOf("第一.mp4", "第二.mkv"), names)
    }

    /** spec: vault-export「使用 STORE 模式」—— 密文再压一遍只是浪费 CPU。 */
    @Test
    fun `every entry is stored without compression`() {
        val vault = newVault()
        vault.directory.put(uuid(1), vault.masterKey)
        val archive = archiveOf(vault.root)

        var count = 0
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertEquals(ZipEntry.STORED, entry.method, "条目 ${entry.name} 被压缩了")
                count++
            }
        }
        // vault.meta + vault.meta.bak + 1 个条目
        assertEquals(3, count)
    }

    /** spec: vault-export「不含未提交文件」。 */
    @Test
    fun `uncommitted files are excluded and reported`() {
        val vault = newVault()
        vault.directory.put(uuid(1), vault.masterKey)
        val orphan = vault.directory.newWriter(uuid(2), vault.masterKey)
        orphan.writePlaceholder(9000L)                  // 写完占位就被杀
        orphan.close()

        val out = ByteArrayOutputStream()
        val report = VaultArchive.write(vault.root, out)
        assertEquals(1, report.skippedUncommitted)

        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
        }
        assertTrue(names.none { it.contains(uuid(2)) }, "未提交文件进了归档：$names")
    }

    @Test
    fun `an archive without vault meta is refused`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("${VaultArchive.DATA_DIR}${uuid(1)}.bin"))
            zip.write(patternBytes(100))
            zip.closeEntry()
        }
        val target = File(tempDir(), "restored")
        val result = VaultArchive.restore(ByteArrayInputStream(out.toByteArray()), target, false)

        val failed = assertIs<VaultArchive.RestoreResult.Failed>(result)
        assertTrue(failed.reason.contains("vault.meta"), "拒绝理由要说明缺什么：${failed.reason}")
        assertTrue(!target.exists(), "拒绝时不得留下目标目录")
    }

    @Test
    fun `an archive with an unknown entry is refused`() {
        val vault = newVault()
        vault.directory.put(uuid(1), vault.masterKey)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("evil.txt"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        val target = File(tempDir(), "restored")
        val result = VaultArchive.restore(ByteArrayInputStream(out.toByteArray()), target, false)

        assertIs<VaultArchive.RestoreResult.Failed>(result)
        assertTrue(!target.exists())
    }

    /**
     * 路径穿越：`data/../x.bin` 的 uuid 形状不合法，必须在**落盘之前**被拒绝。
     * 这里不依赖任何字符串清洗，而是复用 `VaultDirectory.cipherFile` 的 uuid 校验。
     */
    @Test
    fun `a path traversal entry is refused`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("${VaultArchive.DATA_DIR}../evil.bin"))
            zip.write(patternBytes(10))
            zip.closeEntry()
        }
        val parent = tempDir()
        val target = File(parent, "restored")
        val result = VaultArchive.restore(ByteArrayInputStream(out.toByteArray()), target, false)

        assertIs<VaultArchive.RestoreResult.Failed>(result)
        assertTrue(!File(parent, "evil.bin").exists(), "路径穿越在仓库目录之外写出了文件")
    }

    @Test
    fun `a truncated archive is refused and leaves nothing behind`() {
        val vault = newVault()
        vault.directory.put(uuid(1), vault.masterKey, 50_000)
        val full = archiveOf(vault.root)
        val truncated = full.copyOf(full.size / 2)

        val target = File(tempDir(), "restored")
        val result = VaultArchive.restore(ByteArrayInputStream(truncated), target, false)

        assertIs<VaultArchive.RestoreResult.Failed>(result)
        assertTrue(!target.exists(), "截断的归档不得留下半个仓库")
    }

    /** spec: vault-restore「恢复到已有仓库前必须询问」—— 未得到允许时 MUST NOT 动它。 */
    @Test
    fun `an existing vault is untouched without explicit overwrite`() {
        val source = newVault()
        source.directory.put(uuid(1), source.masterKey)
        val archive = archiveOf(source.root)

        val target = newVault().root                        // 另一个已有仓库
        val before = target.walkTopDown().filter { it.isFile }.map { it.name }.sorted().toList()

        val result = VaultArchive.restore(ByteArrayInputStream(archive), target, allowOverwrite = false)

        assertIs<VaultArchive.RestoreResult.Failed>(result)
        assertEquals(before, target.walkTopDown().filter { it.isFile }.map { it.name }.sorted().toList())
    }

    @Test
    fun `an existing vault is replaced only when overwrite is allowed`() {
        val source = newVault()
        source.directory.put(uuid(1), source.masterKey)
        val archive = archiveOf(source.root)

        val oldVault = newVault()
        oldVault.directory.put(uuid(9), oldVault.masterKey)

        val result = VaultArchive.restore(
            ByteArrayInputStream(archive),
            oldVault.root,
            allowOverwrite = true,
            masterKey = source.masterKey,
        )
        assertIs<VaultArchive.RestoreResult.Success>(result)
        assertEquals(
            listOf("${uuid(1)}.bin"),
            VaultDirectory(oldVault.root).cipherFiles().map { it.name },
        )
    }

    /** 抽样校验必须**带密钥**才有意义：错密钥要能被抽样发现。 */
    @Test
    fun `sampling catches a wrong key`() {
        val vault = newVault()
        vault.directory.put(uuid(1), vault.masterKey)
        vault.directory.put(uuid(2), vault.masterKey)
        val archive = archiveOf(vault.root)

        val good = assertIs<VaultArchive.RestoreResult.Success>(
            VaultArchive.restore(ByteArrayInputStream(archive), File(tempDir(), "a"), false, vault.masterKey)
        )
        assertEquals(2, good.sampled)
        assertEquals(0, good.sampleFailures)

        val wrongKey = ByteArray(32) { 0x5A }
        val bad = assertIs<VaultArchive.RestoreResult.Success>(
            VaultArchive.restore(ByteArrayInputStream(archive), File(tempDir(), "b"), false, wrongKey)
        )
        assertEquals(bad.sampled, bad.sampleFailures, "错密钥必须让每一条抽样都失败")
    }

    /** 只用 `.bak` 的归档也能恢复（spec: 从归档恢复时 .bak 可用）。 */
    @Test
    fun `an archive with only the backup meta still restores`() {
        val vault = newVault()
        vault.directory.put(uuid(1), vault.masterKey)
        val archive = archiveOf(vault.root)

        // 重新打一个只含 .bak 的归档
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf(
                VaultArchive.META_BAK to File(vault.root, VaultArchive.META_BAK),
                "${VaultArchive.DATA_DIR}${uuid(1)}.bin" to File(vault.directory.dataDir, "${uuid(1)}.bin"),
            ).forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        val target = File(tempDir(), "restored")
        val success = assertIs<VaultArchive.RestoreResult.Success>(
            VaultArchive.restore(ByteArrayInputStream(out.toByteArray()), target, false)
        )
        assertTrue(success.usedBackupMeta, "只解到 .bak 时必须报告这一点")
        // 恢复出来的 .bak 能被 VaultMetaIO 当成可用边车
        assertTrue(VaultMetaIO.load(target) !is VaultMetaIO.LoadResult.Corrupted)
    }

    /** 导出的归档必须能被重新读出一模一样的内容（往返的强断言）。 */
    @Test
    fun `content hashes survive the round trip`() {
        val vault = newVault()
        val payload = patternBytes(70_000)
        vault.directory.newWriter(uuid(1), vault.masterKey).use {
            it.writeFrom(ByteArrayInputStream(payload), "往返.mp4", payload.size.toLong(), 1L)
        }
        val before = vault.directory.indexEntries(vault.masterKey).single()

        val target = File(tempDir(), "restored")
        assertIs<VaultArchive.RestoreResult.Success>(
            VaultArchive.restore(ByteArrayInputStream(archiveOf(vault.root)), target, false)
        )
        val after = VaultDirectory(target).indexEntries(vault.masterKey).single()

        assertContentEquals(before.sha256, after.sha256)
        assertEquals(before.plaintextLength, after.plaintextLength)
        assertEquals(before.displayName, after.displayName)
    }

    @Test
    fun `an interrupted placeholder never gets a writer in the archive`() {
        // 与上面那条互补：MALFORMED 的文件同样不该进归档。
        val vault = newVault()
        vault.directory.put(uuid(1), vault.masterKey)
        File(vault.directory.dataDir, "${uuid(2)}.bin").writeBytes(patternBytes(50))

        val out = ByteArrayOutputStream()
        val report = VaultArchive.write(vault.root, out)
        assertEquals(1, report.skippedUncommitted, "损坏文件也计入跳过数")
        // vault.meta + vault.meta.bak + 那一个已提交条目
        assertEquals(3, report.entries)
    }
}
