package com.avideo.core.vault

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.container.ContainerChannel
import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileWriter
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.container.OffsetMapping
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.MasterKeyDeriver
import com.avideo.core.interruptedPlaceholder
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultDirectoryTest {

    private val params = KdfParams.TEST
    private val password = "仓库密码".toCharArray()

    private fun newVault(): Triple<VaultDirectory, ByteArray, File> {
        val root = File(tempDir(), "vault")
        val directory = VaultDirectory(root)
        val meta = directory.create(params, DeterministicRandomSource(5))
        val masterKey = MasterKeyDeriver.forJvmOnly().derive(password, meta.kdfSalt, meta.kdfParams)
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

    /**
     * `create()` MUST NOT 在已有仓库上再跑一次：它每次生成**新盐**并覆写 vault.meta/.bak，
     * 主密钥随之改变 —— 已提交的 `<uuid>.bin` 全部再也解不开（KeyUnwrapFailedError），
     * 而旧盐已被覆盖，**没有任何恢复路径**。
     *
     * 这条测试同时钉住三件事：第二次调用抛错、两份边车字节不变（盐没换）、
     * 原来的文件仍然读得回来。
     */
    @Test
    fun `create over an existing vault is refused and leaves it untouched`() {
        val (directory, masterKey, _) = newVault()
        val uuid = "44444444-4444-8444-8444-444444444444"
        directory.put(uuid, masterKey, 3000)
        val metaPath = File(directory.root, VaultMetaIO.FILE_NAME)
        val bakPath = File(directory.root, VaultMetaIO.BACKUP_NAME)
        val metaBefore = metaPath.readBytes()
        val bakBefore = bakPath.readBytes()

        val thrown = assertFailsWith<IllegalStateException> {
            directory.create(params, DeterministicRandomSource(5))
        }
        // 消息只许含结构性信息：不得出现主密钥
        val masterKeyHex = masterKey.joinToString("") { "%02x".format(it) }
        assertTrue(!thrown.message.orEmpty().contains(masterKeyHex), "异常消息里出现了主密钥")

        assertContentEquals(metaBefore, metaPath.readBytes(), "vault.meta 被第二次 create 覆写了")
        assertContentEquals(bakBefore, bakPath.readBytes(), "vault.meta.bak 被第二次 create 覆写了")
        // 盐没变 ⇒ 原主密钥仍解得开原文件（覆写的话这里会是 Corrupted/KeyUnwrapFailedError）
        assertContentEquals(
            masterKey,
            MasterKeyDeriver.forJvmOnly().derive(password, directory.loadMeta().kdfSalt, directory.loadMeta().kdfParams),
        )
        val entry = assertIs<VaultEntry.Committed>(directory.list(masterKey).single())
        assertEquals(uuid, entry.uuid)
    }

    /**
     * `vault.meta` 丢了一份、但 data/ 里还有密文文件时同样 MUST 拒绝：
     * 新盐会让这些文件的 data key 永远解不开，等同于静默销毁用户数据。
     */
    @Test
    fun `create is refused when the data directory already holds cipher files`() {
        val root = File(tempDir(), "vault")
        val directory = VaultDirectory(root)
        directory.dataDir.mkdirs()
        File(directory.dataDir, "55555555-5555-4555-8555-555555555555.bin").writeBytes(patternBytes(64))

        assertFailsWith<IllegalStateException> { directory.create(params, DeterministicRandomSource(5)) }
        assertTrue(!directory.metaFile.exists(), "被拒绝时不得写出 vault.meta")
    }

    /**
     * uuid 会被直接拼进路径（`data/$uuid.bin`），所以 MUST 先校验形状：
     * 含 `/` 或 `..` 的 uuid 会把路径带出 data/，而 [VaultDirectory.newWriter] 打开文件时用的是
     * `TRUNCATE_EXISTING` —— 撞上已有条目就是**静默销毁**。
     */
    @Test
    fun `a uuid that would escape the data directory is refused`() {
        val (directory, masterKey, root) = newVault()
        val bad = listOf(
            "../escape",
            "..",
            "",
            "a/b",
            "0f8fad5b-d9cb-469f-a165-70867728950e/../../x",
            "..\\escape",
            "0f8fad5b-d9cb-469f-a165-70867728950e.bin",
            "0f8fad5b-d9cb-469f-a165-70867728950",          // 少一段
            "0f8fad5b-d9cb-469f-a165-70867728950gz",        // 非十六进制
        )
        for (uuid in bad) {
            assertFailsWith<IllegalArgumentException>("cipherFile 接受了 「$uuid」") { directory.cipherFile(uuid) }
            assertFailsWith<IllegalArgumentException>("newWriter 接受了 「$uuid」") {
                directory.newWriter(uuid, masterKey)
            }
        }

        // 拒绝不是「一律拒绝」：规范形状照常工作
        directory.put("0f8fad5b-d9cb-469f-a165-70867728950e", masterKey, 100)
        assertEquals(
            listOf("0f8fad5b-d9cb-469f-a165-70867728950e.bin"),
            directory.dataDir.list()!!.sorted(),
        )
        // 被拒绝的那些 MUST NOT 在 data/ 之外留下任何东西
        assertTrue(
            root.parentFile.listFiles().orEmpty().none { it.name.contains("escape") },
            "路径穿越的 uuid 在仓库目录之外留下了文件",
        )
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

    /**
     * 列表边界上的同一条规则（spec: import-commit-protocol「未提交文件的恢复」）：
     * 写占位写到一半被杀的文件 MUST 被**排除**，MUST NOT 作为
     * `VaultEntry.Corrupted` 出现 —— 那会把它显示成「仓库损坏」。
     * 两种中间态（固定头刚写完 97 字节、metadata 区刚写完 1121 字节）都要覆盖。
     */
    @Test
    fun `a placeholder interrupted mid write is excluded from the listing, not reported corrupt`() {
        val (directory, masterKey, _) = newVault()
        directory.put("66666666-6666-4666-8666-666666666666", masterKey, 2000)
        val interrupted = (1..2).map { afterWrites ->
            interruptedPlaceholder(
                File(directory.dataDir, "77777777-7777-4777-8777-77777777777$afterWrites.bin"),
                masterKey,
                afterWrites,
            )
        }

        val entries = directory.list(masterKey)
        assertEquals(1, entries.size, "中断的导入被当成条目返回了：$entries")
        assertIs<VaultEntry.Committed>(entries.single())
        assertEquals(interrupted.toSet(), directory.listUncommitted().toSet())
    }

    /**
     * **错误的主密钥在列举边界上的现状**（本阶段只钉住它，不重做错误策略）。
     *
     * 打错密码是最常见的用户错误，而 `list(masterKey)` 现在把它呈现成「N 个文件损坏」：
     * 每条都降级成 `VaultEntry.Corrupted(reason = "KeyUnwrapFailedError")`，`list` 本身不抛。
     * 这条测试固定这个已知缺口，免得它悄悄变成别的样子（例如 `list` 直接抛，或吞掉整页）。
     *
     * stage-1 MUST 在把消息显示给用户之前，把这个**全局**情形与真正的单文件损坏区分开
     * —— spec: vault-meta 要求「密码不对」与「仓库损坏」可区分、MUST NOT 合并成一条消息。
     */
    @Test
    fun `a wrong master key degrades every entry instead of throwing`() {
        val (directory, masterKey, _) = newVault()
        directory.put("88888888-8888-4888-8888-888888888888", masterKey, 1000)
        directory.put("99999999-9999-4999-8999-999999999999", masterKey, 1000)

        val rightKey = directory.list(masterKey)
        assertEquals(2, rightKey.count { it is VaultEntry.Committed }, "对照组：正确的密钥应当两条都读出来")

        val wrong = ByteArray(masterKey.size) { 0x5A }
        assertTrue(!wrong.contentEquals(masterKey), "测试用错了密钥：它和被拒绝的那把是同一把")
        val entries = directory.list(wrong)
        assertEquals(2, entries.size, "错误主密钥不该让整个列表失败")
        assertTrue(entries.all { it is VaultEntry.Corrupted }, "错误主密钥应当让每条都降级：$entries")
        assertEquals(
            listOf("KeyUnwrapFailedError", "KeyUnwrapFailedError"),
            entries.map { (it as VaultEntry.Corrupted).reason },
            "reason 只能是错误类型名（不含任何敏感值）",
        )
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
        val masterKey = MasterKeyDeriver.forJvmOnly().derive(password, meta.kdfSalt, meta.kdfParams)
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

    /**
     * 单条目在**打开阶段**抛 I/O 异常（文件在枚举与打开之间被删/被占用）时，
     * MUST 只把这一条降级为损坏，MUST NOT 让整个列表失败。
     * 这条断言钉住的是 `list` 里「open 必须在 try 内」+「IOException 与 VaultError 一起兜住」。
     */
    @Test
    fun `an entry whose channel fails to open degrades to corrupted without failing the listing`() {
        val root = File(tempDir(), "vault")
        val seed = VaultDirectory(root)
        val meta = seed.create(params, DeterministicRandomSource(5))
        val masterKey = MasterKeyDeriver.forJvmOnly().derive(password, meta.kdfSalt, meta.kdfParams)
        seed.put("11111111-1111-4111-8111-111111111111", masterKey, 1000)
        val poisoned = "22222222-2222-4222-8222-222222222222"
        seed.put(poisoned, masterKey, 1000)

        val listing = VaultDirectory(root) { file ->
            if (file.name.startsWith(poisoned)) throw java.io.IOException("file vanished")
            FileContainerChannel.openForRead(file)
        }.list(masterKey)

        assertEquals(2, listing.size)
        assertEquals(1, listing.count { it is VaultEntry.Committed })
        val corrupted = assertIs<VaultEntry.Corrupted>(listing.single { it is VaultEntry.Corrupted })
        assertEquals(poisoned, corrupted.uuid)
        // reason 只含错误类型名，不含 message（message 里可能带路径）
        assertEquals("IOException", corrupted.reason)
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
