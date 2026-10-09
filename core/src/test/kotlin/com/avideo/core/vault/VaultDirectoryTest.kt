package com.avideo.core.vault

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.container.ContainerChannel
import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileWriter
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.container.OffsetMapping
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.MasterKeyDeriver
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultDirectoryTest {

    private val params = KdfParams.TEST
    private val password = "仓库密码".toCharArray()

    private fun newVault(): Triple<VaultDirectory, ByteArray, File> {
        val root = File(tempDir(), "vault")
        val directory = VaultDirectory(root)
        val meta = directory.create(params, DeterministicRandomSource(5))
        val masterKey = MasterKeyDeriver.jvm().derive(password, meta.kdfSalt, meta.kdfParams)
        return Triple(directory, masterKey, root)
    }

    private fun VaultDirectory.put(uuid: String, masterKey: ByteArray, len: Int, name: String = "$uuid.mp4") {
        newWriter(uuid, masterKey).use {
            it.writeFrom(ByteArrayInputStream(patternBytes(len)), name, len.toLong(), 1000L)
        }
    }

    @Test
    fun `create writes meta and bak and a data directory`() {
        val (_, _, root) = newVault()
        assertTrue(File(root, VaultMetaIO.FILE_NAME).isFile)
        assertTrue(File(root, VaultMetaIO.BACKUP_NAME).isFile)
        assertTrue(File(root, "data").isDirectory)
    }

    /** spec: vault-meta「无索引文件」。 */
    @Test
    fun `no index file exists in the vault`() {
        val (directory, masterKey, root) = newVault()
        directory.put("0f8fad5b-d9cb-469f-a165-70867728950e", masterKey, 3000)

        val allFiles = root.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertTrue(allFiles.none { it == "manifest.bin" }, "manifest.bin 已废弃（OV-7）")
        assertEquals(
            listOf("vault.meta", "vault.meta.bak", "0f8fad5b-d9cb-469f-a165-70867728950e.bin").sorted(),
            allFiles.sorted(),
        )
        // 列表靠枚举 data/ 得出，不靠任何索引
        assertEquals(listOf("0f8fad5b-d9cb-469f-a165-70867728950e"), directory.list(masterKey).map { it.uuid })
    }

    @Test
    fun `cipher file names never contain the original name`() {
        val (directory, masterKey, _) = newVault()
        val uuid = "11111111-2222-4333-8444-555555555555"
        directory.put(uuid, masterKey, 100, name = "我的私人视频.mp4")
        val files = directory.dataDir.listFiles()!!
        assertEquals(1, files.size)
        val name = files[0].name
        assertEquals("$uuid.bin", name)
        assertTrue(!name.contains("我的私人视频"))
    }

    @Test
    fun `listing finds a cipher file placed by hand`() {
        val (directory, masterKey, _) = newVault()
        val uuid = "aaaabbbb-cccc-4ddd-8eee-ffff00001111"
        val external = File(directory.dataDir, "$uuid.bin")
        EncryptedFileWriter(FileContainerChannel.openForWrite(external), masterKey, 1024, DeterministicRandomSource(4))
            .use { it.writeFrom(ByteArrayInputStream(patternBytes(5000)), "外来条目.mkv", 5000L, 77L) }
        val entries = directory.list(masterKey)
        val committed = assertIs<VaultEntry.Committed>(entries.single())
        assertEquals(uuid, committed.uuid)
        assertEquals("外来条目.mkv", committed.metadata.displayName)
        assertEquals(77L, committed.metadata.durationMs)
    }

    /** spec: import-commit-protocol「未提交文件不影响他人」。 */
    @Test
    fun `uncommitted files never appear in the listing and do not disturb neighbours`() {
        val (directory, masterKey, _) = newVault()
        directory.put("11111111-1111-4111-8111-111111111111", masterKey, 2000)
        directory.put("22222222-2222-4222-8222-222222222222", masterKey, 3000)

        val orphan = File(directory.dataDir, "33333333-3333-4333-8333-333333333333.bin")
        val writer = EncryptedFileWriter(FileContainerChannel.openForWrite(orphan), masterKey, 1024, DeterministicRandomSource(6))
        writer.writePlaceholder(9000L)
        writer.writeChunk(patternBytes(4000), 4000)     // 数据区 50% 处被杀
        writer.close()

        val entries = directory.list(masterKey)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it is VaultEntry.Committed })
        assertEquals(listOf(orphan), directory.listUncommitted())
    }

    /** spec: vault-meta「列出条目靠枚举」+ stage-2 的「单条目损坏不影响其他条目」。 */
    @Test
    fun `a single corrupted entry is surfaced without failing the whole listing`() {
        val (directory, masterKey, _) = newVault()
        directory.put("11111111-1111-4111-8111-111111111111", masterKey, 1000)
        directory.put("22222222-2222-4222-8222-222222222222", masterKey, 1000)
        val broken = File(directory.dataDir, "33333333-3333-4333-8333-333333333333.bin")
        broken.writeBytes(patternBytes(2000))

        val entries = directory.list(masterKey)
        assertEquals(3, entries.size)
        assertEquals(2, entries.count { it is VaultEntry.Committed })
        assertEquals(1, entries.count { it is VaultEntry.Corrupted })
    }

    /** 列出条目时 MUST NOT 解密任何数据块（ARCHITECTURE.md：100 个文件 = 100 次小 AEAD 解密）。 */
    @Test
    fun `listing never reads the data area`() {
        val root = File(tempDir(), "vault")
        val dataStart = com.avideo.core.container.OffsetMapping.dataStart(ContainerConstants.METADATA_LEN)
        val observed = mutableListOf<Pair<Long, Int>>()
        val directory = VaultDirectory(root) { file ->
            RecordingReads(FileContainerChannel.openForRead(file), observed)
        }
        val meta = directory.create(params, DeterministicRandomSource(5))
        val masterKey = MasterKeyDeriver.jvm().derive(password, meta.kdfSalt, meta.kdfParams)
        VaultDirectory(root).run {
            repeat(20) { index ->
                put("00000000-0000-4000-8000-%012d".format(index), masterKey, 3 * ContainerConstants.CHUNK_PLAINTEXT)
            }
        }
        observed.clear()
        directory.list(masterKey)
        assertTrue(observed.isNotEmpty(), "没有观察到任何读取，测试无效")
        assertTrue(
            observed.all { it.first + it.second <= dataStart },
            "列出条目时读到了数据区：$observed",
        )
    }

    private class RecordingReads(
        private val delegate: ContainerChannel,
        private val observed: MutableList<Pair<Long, Int>>,
    ) : ContainerChannel {
        override fun writeAt(offset: Long, src: ByteArray, length: Int) = delegate.writeAt(offset, src, length)
        override fun readAt(offset: Long, dst: ByteArray, length: Int): Int {
            observed += offset to length
            return delegate.readAt(offset, dst, length)
        }
        override fun size(): Long = delegate.size()
        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
