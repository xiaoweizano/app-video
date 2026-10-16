package com.avideo.core.vault

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.MasterKeyDeriver
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec: vault-unlock「解锁判定必须抽样多个文件」。
 *
 * 这一组测试钉住的是解锁判定的**唯一依据**：解包 wrapped_file_key 是否成功。
 * 它们是 :core 里唯一能在 CI 上跑的解锁逻辑验证 —— app 层没有 instrumentation 测试基建，
 * 所以判定规则 MUST 留在这里，而不是写在 ViewModel 里。
 */
class MasterKeyProbeTest {

    private val params = KdfParams.TEST
    private val password = "仓库密码".toCharArray()

    private fun newVault(): Triple<VaultDirectory, ByteArray, File> {
        val root = File(tempDir(), "vault")
        val directory = VaultDirectory(root)
        val meta = directory.create(params, DeterministicRandomSource(5))
        val masterKey = MasterKeyDeriver.forJvmOnly().derive(password, meta.kdfSalt, meta.kdfParams)
        return Triple(directory, masterKey, root)
    }

    private fun VaultDirectory.put(uuid: String, masterKey: ByteArray, len: Int = 1000) {
        newWriter(uuid, masterKey).use {
            it.writeFrom(ByteArrayInputStream(patternBytes(len)), "$uuid.mp4", len.toLong(), 1000L)
        }
    }

    private fun uuid(index: Int) = "00000000-0000-4000-8000-%012d".format(index)

    /** 空仓库：抽不到任何文件 ⇒ 密码无法校验（调用方据此放行并如实告知）。 */
    @Test
    fun `an empty vault samples nothing and proves nothing`() {
        val (directory, masterKey, _) = newVault()
        val probe = directory.probeMasterKey(masterKey)
        assertEquals(0, probe.sampled)
        assertEquals(0, probe.unwrapped)
        assertTrue(probe.unverifiable, "空仓库必须被标成「密码无法校验」")
        assertTrue(!probe.keyProven, "空仓库不得声称密码已被证明")
    }

    @Test
    fun `the correct master key is proven by a single committed file`() {
        val (directory, masterKey, _) = newVault()
        directory.put(uuid(0), masterKey)
        val probe = directory.probeMasterKey(masterKey)
        assertEquals(1, probe.sampled)
        assertTrue(probe.keyProven)
        assertTrue(!probe.unverifiable)
    }

    /** 非空仓库 + 错误密码 ⇒ 一个都解不开 ⇒ 调用方报「密码错误」（MUST NOT 说成仓库损坏）。 */
    @Test
    fun `a wrong master key is never proven`() {
        val (directory, masterKey, _) = newVault()
        repeat(3) { directory.put(uuid(it), masterKey) }
        val wrong = ByteArray(masterKey.size) { 0x5A }
        assertTrue(!wrong.contentEquals(masterKey), "测试用错了密钥：它和被拒绝的那把是同一把")

        val probe = directory.probeMasterKey(wrong)
        assertEquals(3, probe.sampled)
        assertEquals(0, probe.unwrapped)
        assertTrue(!probe.keyProven)
        assertTrue(!probe.unverifiable, "仓库非空，所以「无法校验」不成立")
    }

    /**
     * spec:「一个成功即证明密码正确」。10 个文件里第 1 个的 wrapped_file_key 被改坏，
     * 正确密码仍然必须被判为正确（并因此可以指出「部分文件损坏」）。
     *
     * 这条同时钉住抽样**不能只取前 N 个**：等距抽样恰好把索引 0 也抽进来，
     * 但它坏了不影响结论。
     */
    @Test
    fun `one corrupt file key does not hide a correct master key`() {
        val (directory, masterKey, _) = newVault()
        repeat(10) { directory.put(uuid(it), masterKey) }

        // 改坏第 1 个文件的 wrapped_file_key（固定头里 5..64，落在 header_tag 覆盖区内也照样改：
        // 这里只关心解包这一步的结果）
        val broken = directory.cipherFile(uuid(0))
        val bytes = broken.readBytes()
        bytes[ContainerConstants.MAGIC_LEN + 10] = (bytes[ContainerConstants.MAGIC_LEN + 10] + 1).toByte()
        broken.writeBytes(bytes)

        val probe = directory.probeMasterKey(masterKey)
        assertEquals(5, probe.sampled, "抽样上限为 5")
        assertTrue(probe.unwrapped >= 1, "坏了一个文件不该让正确密码被判错：$probe")
        assertTrue(probe.keyProven)
        // 同一个仓库用错误密码必须仍然判错，否则上面的「证明」没有意义
        assertTrue(!directory.probeMasterKey(ByteArray(masterKey.size) { 0x11 }).keyProven)
    }

    /** spec:「抽样有上限」：仓库再大也只读 [VaultDirectory.PROBE_SAMPLE_MAX] 个文件头。 */
    @Test
    fun `sampling stops at the cap no matter how many files exist`() {
        val (directory, masterKey, _) = newVault()
        repeat(12) { directory.put(uuid(it), masterKey) }
        assertEquals(12, directory.cipherFiles().size)
        assertEquals(VaultDirectory.PROBE_SAMPLE_MAX, directory.probeMasterKey(masterKey).sampled)
    }

    /** 非空仓库且文件数 ≥ 2 时抽样数 MUST ≥ 2（spec 的下限要求）。 */
    @Test
    fun `sampling never drops below two files when at least two exist`() {
        val (directory, masterKey, _) = newVault()
        directory.put(uuid(0), masterKey)
        assertEquals(1, directory.probeMasterKey(masterKey).sampled, "只有一个文件时只能抽一个")

        directory.put(uuid(1), masterKey)
        assertEquals(2, directory.probeMasterKey(masterKey).sampled)
    }

    /**
     * 未提交的占位文件（上次导入被杀）也参与抽样：它的固定头是完整写过的，解包照样成立。
     * 于是「仓库里只有一个孤儿」时密码仍然可校验 —— 否则用户会以为密码错了，
     * 而真相是这个文件根本没写完。
     */
    @Test
    fun `an uncommitted placeholder still proves the master key`() {
        val (directory, masterKey, _) = newVault()
        val orphan = File(directory.dataDir, "${uuid(0)}.bin")
        val writer = com.avideo.core.container.EncryptedFileWriter(
            FileContainerChannel.openForWrite(orphan), masterKey, 1024, DeterministicRandomSource(6),
        )
        writer.writePlaceholder(9000L)      // 写完占位就被杀：metadata_tag 全零
        writer.close()

        assertTrue(directory.list(masterKey).isEmpty(), "未提交的文件不该出现在列表里")
        val probe = directory.probeMasterKey(masterKey)
        assertEquals(1, probe.sampled)
        assertTrue(probe.keyProven, "占位文件的固定头是完整的，主密钥应当能被证明：$probe")
    }

    /** 打不开的文件只影响这一次探测，MUST NOT 让探测抛出去（文件被删/被占用的 TOCTOU）。 */
    @Test
    fun `a file that cannot be opened is skipped instead of failing the probe`() {
        val root = File(tempDir(), "vault")
        val seed = VaultDirectory(root)
        val meta = seed.create(params, DeterministicRandomSource(5))
        val masterKey = MasterKeyDeriver.forJvmOnly().derive(password, meta.kdfSalt, meta.kdfParams)
        repeat(2) { seed.put(uuid(it), masterKey) }

        val poisoned = uuid(0)
        val probe = VaultDirectory(root) { file ->
            if (file.name.startsWith(poisoned)) throw IOException("file vanished")
            FileContainerChannel.openForRead(file)
        }.probeMasterKey(masterKey)

        assertEquals(2, probe.sampled)
        assertEquals(1, probe.unwrapped, "坏掉的那一份不算成功，但另一份足以证明密钥")
        assertTrue(probe.keyProven)
    }

    /** 全是随机字节的假文件：既不能解包也不该让探测抛。 */
    @Test
    fun `garbage files never prove a key`() {
        val (directory, masterKey, _) = newVault()
        File(directory.dataDir, "${uuid(0)}.bin").writeBytes(patternBytes(5000))
        File(directory.dataDir, "${uuid(1)}.bin").writeBytes(patternBytes(5000))

        val probe = directory.probeMasterKey(masterKey)
        assertEquals(2, probe.sampled)
        assertTrue(!probe.keyProven)
        assertTrue(!probe.unverifiable, "有文件可抽，只是都证明不了")
    }
}
