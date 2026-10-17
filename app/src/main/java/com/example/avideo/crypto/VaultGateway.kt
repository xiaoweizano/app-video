package com.example.avideo.crypto

import android.content.Context
import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileReader
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.MasterKeyDeriver
import com.avideo.core.crypto.PasswordNormalizer
import com.avideo.core.importing.ImportPipeline
import com.avideo.core.errors.VaultCorruptedError
import com.avideo.core.errors.VaultError
import com.avideo.core.vault.MasterKeyProbe
import com.avideo.core.vault.VaultDirectory
import com.avideo.core.vault.VaultEntry
import com.avideo.core.vault.VaultMeta
import com.avideo.core.vault.VaultMetaIO
import com.example.avideo.importing.NotHiddenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 仓库的唯一入口：创建、解锁、列出条目。UI 只跟这一个类打交道。
 *
 * 三条不可让步的约束（都来自 spec）：
 *  1. **仓库目录 = `filesDir/vault`，不是 `getExternalFilesDir`。**
 *     真机实测（B 探针）：外部那份 `/storage/emulated/0/Android/data/…` 会被文件管理器
 *     「深度搜索」翻出来，内部这份翻不到。密文放外部等于把威胁模型 Q1（防普通非 root 用户）作废。
 *  2. **KDF 一律在 `Dispatchers.Default` 上跑。** `argon2kt` 构造时要加载 .so，
 *     且 m=64 MiB 本身就是几百毫秒到几秒的计算，放主线程会 ANR。
 *  3. **密码校验顺序：先校验 vault.meta（无密钥、CRC32），再派生主密钥。**
 *     spec: vault-meta 要求「仓库损坏」在**不**用被改坏的参数跑 KDF 的前提下就能报出来。
 */
object VaultGateway {

    /** `filesDir` 下的目录名。 */
    const val DIR_NAME = "vault"

    /** 密码最短长度。创建与解锁用同一条规则，避免「能设不能开」。 */
    const val MIN_PASSWORD_LENGTH = 4

    /**
     * 生产用的派生器：`MasterKeyDeriver(AndroidKeyDerivation())`，即 NFC 规范化 + argon2kt。
     *
     * 这是本模块**唯一**允许构造 `MasterKeyDeriver` 的地方，而且 MUST NOT 换成
     * `MasterKeyDeriver.forJvmOnly()` —— 那个实现经 JNA 加载桌面平台的原生库，
     * Android 上编译通过、运行到 `derive()` 才崩。
     */
    private val deriver: MasterKeyDeriver by lazy { MasterKeyDeriver(AndroidKeyDerivation()) }

    fun vaultDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun directory(context: Context): VaultDirectory = VaultDirectory(vaultDir(context))

    /** 仓库状态。只读边车（两次 37 字节），不跑 KDF —— 解锁页一进来就要问。 */
    fun state(context: Context): VaultState {
        val directory = directory(context)
        val anySidecarExists = directory.metaFile.isFile || directory.backupFile.isFile
        if (!anySidecarExists) return VaultState.NotCreated
        return when (VaultMetaIO.load(directory.root)) {
            is VaultMetaIO.LoadResult.Ok, is VaultMetaIO.LoadResult.BothValid -> VaultState.Ready
            VaultMetaIO.LoadResult.Corrupted -> VaultState.Corrupted
        }
    }

    /**
     * 首次设置：生成新盐、写出 vault.meta 与 .bak，并直接用该密码进入。
     *
     * [VaultDirectory.create] 在已有仓库上会抛 `IllegalStateException`（新盐会让已提交的文件
     * 永远解不开），这里不吞掉它 —— 但也不把消息透给用户，因为那是个不该发生的状态。
     */
    suspend fun create(context: Context, password: CharArray): UnlockOutcome =
        withContext(Dispatchers.Default) {
            val directory = directory(context)
            val meta = try {
                directory.create(KdfParams.PRODUCTION)
            } catch (e: IllegalStateException) {
                return@withContext UnlockOutcome.Failed("仓库已存在，未做任何改动")
            } catch (e: IOException) {
                // 磁盘满、目录不可写之类。**不**翻译成「仓库损坏」：那是两回事。
                return@withContext UnlockOutcome.Failed("写不进仓库目录（${e.javaClass.simpleName}）")
            }
            holdMasterKey(derive(password, meta))
            // 刚创建的仓库一定是空的 ⇒ 密码无法校验，如实标记，MUST NOT 声称已验证。
            sessionPasswordProven = false
            UnlockOutcome.Unlocked(MasterKeyProbe(sampled = 0, unwrapped = 0))
        }

    /**
     * 解锁。判定规则见 [VaultDirectory.probeMasterKey]：**任意一个文件解包成功 ⇒ 密码正确**。
     *
     * 三种结局各自给出不同提示（spec 要求 MUST NOT 合并）：
     *  - [UnlockOutcome.WrongPassword] 抽样全部失败
     *  - [UnlockOutcome.VaultCorrupted] 两份 `vault.meta` 的 CRC32 都不过
     *  - [UnlockOutcome.Unlocked]，其 `probe.unverifiable == true` 表示**空仓库无法校验密码**
     */
    suspend fun unlock(context: Context, password: CharArray): UnlockOutcome =
        withContext(Dispatchers.Default) {
            val directory = directory(context)
            val meta = try {
                directory.loadMeta()
            } catch (e: VaultCorruptedError) {
                // vault.meta 与 .bak 的 CRC32 都不过。**没有**跑过 KDF —— 这正是
                // 「仓库损坏」与「密码错误」能分开报的前提。
                return@withContext UnlockOutcome.VaultCorrupted
            } catch (e: IOException) {
                return@withContext UnlockOutcome.Failed("读不到仓库文件（${e.javaClass.simpleName}）")
            }
            val candidate = derive(password, meta)
            try {
                val probe = directory.probeMasterKey(candidate)
                if (probe.keyProven || probe.unverifiable) {
                    // 空仓库（unverifiable）也放行：没有任何东西可以验证主密钥，
                    // 不放行就永远导不进第一个文件。但界面 MUST 如实说「密码尚未被校验」，
                    // 而且这个「尚未被校验」的密码会在第一次写入时被永久锚定（见成员注释）。
                    holdMasterKey(candidate)
                    sessionPasswordProven = probe.keyProven
                    UnlockOutcome.Unlocked(probe)
                } else {
                    // 带上候选密钥的指纹：它让「我确定密码没记错」这句话可以被核对 ——
                    // 与写入时打印的指纹一致却仍解不开，那就是代码的问题，不是记性。
                    UnlockOutcome.WrongPassword(keyFingerprint(candidate))
                }
            } catch (e: VaultError) {
                // 只带错误类型名（结构性信息），不带路径/密钥/盐
                UnlockOutcome.Failed("解锁时出错（${e.javaClass.simpleName}）")
            } finally {
                // 未被采纳的候选密钥同样必须清掉，不能留给 GC
                PasswordNormalizer.wipe(candidate)
            }
        }

    /**
     * 列表数据源。
     *
     * @return null 表示**未解锁** —— 调用方 MUST NOT 继续，MUST 提示「未解锁」并回解锁页
     *   （spec: key-repository「未解锁时拒绝操作」）。
     */
    suspend fun entries(context: Context): List<VaultListItem>? = withContext(Dispatchers.Default) {
        val masterKey = KeyRepository.getMasterKey() ?: return@withContext null
        try {
            directory(context).list(masterKey).map { entry ->
                when (entry) {
                    is VaultEntry.Committed -> VaultListItem(
                        uuid = entry.uuid,
                        displayName = entry.metadata.displayName,
                        durationMs = entry.metadata.durationMs.takeIf { entry.metadata.durationKnown },
                        corrupted = false,
                    )
                    // reason 只含错误类型名，可以直接显示：不含路径、不含密钥。
                    is VaultEntry.Corrupted -> VaultListItem(
                        uuid = entry.uuid,
                        displayName = null,
                        durationMs = null,
                        corrupted = true,
                    )
                }
            }
        } finally {
            PasswordNormalizer.wipe(masterKey)
        }
    }

    /**
     * 当前会话的密码**是否被已有文件证明过**。
     *
     * 为什么必须记着：空仓库对任何密码都放行（spec 要求），所以「能进来」不等于「密码是对的」。
     * 而**第一个写入的文件会用当前这把密钥包裹自己的 wrapped_file_key** —— 从那一刻起，
     * 仓库就永久只认这把密码了。这就是「密码被锚定」：真机上实测踩过一次，
     * 表现是「我确定密码没记错，但再也进不去了」。
     */
    @Volatile
    private var sessionPasswordProven = false

    /** 见 [sessionPasswordProven]。 */
    fun isPasswordProven(): Boolean = sessionPasswordProven

    /**
     * **仅 debug 自检用**：往仓库里写一个合成条目，再立刻读回来逐块核对 SHA-256。
     *
     * 为什么现在就需要它：stage-1 的导入还没接通，而**空仓库对任何密码都放行** ——
     * 没有条目就无法验证列表、密码校验、锁定/解锁、区间读这条完整路径。真机上唯一能验的方式
     * 就是自己写一条进去。
     *
     * 走的全是生产代码（`EncryptedFileWriter` / `EncryptedFileReader` / `VaultDirectory.list`），
     * 没有为自检另开一条路径 —— 否则「自检通过」说明不了生产没问题。
     *
     * 尺寸刻意取 2 块 + 一个短末块：跨块与变长末块是容器里最容易写错的两处
     * （块序号、末块明文长度、disk offset 映射）。
     *
     * 输出里会带上**当前主密钥的指纹**，并且在密码未被证明过时给出明确警告 ——
     * 因为这一次写入会把当前密码永久锚定，而用户可能并不知道自己锚的是哪把。
     */
    suspend fun writeAndVerifyProbeEntry(context: Context): String = withContext(Dispatchers.Default) {
        val masterKey = KeyRepository.getMasterKey()
            ?: return@withContext "[D] 未解锁：先在上一页输入密码进来，再跑这条自检。"
        try {
            val directory = directory(context)
            val uuid = java.util.UUID.randomUUID().toString()
            val size = 2 * ContainerConstants.CHUNK_PLAINTEXT + 12_345
            val plaintext = ByteArray(size) { i -> ((i * 31) xor (i shr 8) xor 0x5A).toByte() }
            val expected = MessageDigest.getInstance("SHA-256").digest(plaintext)

            val sb = StringBuilder("[D] 写入 + 读回自检\n")
            sb.append("    本次使用的主密钥指纹 ${keyFingerprint(masterKey)}（解锁失败时会拿它对照）\n")
            if (!sessionPasswordProven) {
                sb.append("    ⚠ 写入前仓库是空的：本次会话输入的密码已作为锚定密码写进这个文件。\n")
            }

            directory.newWriter(uuid, masterKey).use { writer ->
                writer.writeFrom(
                    source = ByteArrayInputStream(plaintext),
                    displayName = "probe-${uuid.take(8)}.mp4",
                    declaredPlaintextLen = size.toLong(),
                    // 合成条目没有真实时长，用哨兵值 —— 列表页会显示「时长未知」，
                    // 不会编一个 0:00 出来。
                    durationMs = ContainerConstants.DURATION_UNKNOWN,
                )
            }

            sb.append("    条目 uuid=$uuid\n    明文 $size 字节\n")
            val entry = directory.list(masterKey)
                .filterIsInstance<VaultEntry.Committed>()
                .firstOrNull { it.uuid == uuid }
            if (entry == null) {
                sb.append("    ✗ 写完了但列表里没有它 —— 提交协议（metadata_tag）有问题\n")
                return@withContext sb.toString()
            }
            sb.append("    列表读回：${entry.metadata.displayName}，时长未知=${!entry.metadata.durationKnown}\n")

            // 逐块读回（每次 ≤64 KiB，跨块由 reader 自己按块边界拆）
            val digest = MessageDigest.getInstance("SHA-256")
            EncryptedFileReader.open(FileContainerChannel.openForRead(directory.cipherFile(uuid)), masterKey)
                .use { reader ->
                    var offset = 0L
                    while (offset < reader.plaintextLength) {
                        val n = minOf(64L * 1024, reader.plaintextLength - offset).toInt()
                        digest.update(reader.read(offset, n))
                        offset += n
                    }
                    sb.append("    读回明文 ${reader.plaintextLength} 字节（与写入一致=${reader.plaintextLength == size.toLong()}）\n")
                }
            val ok = digest.digest().contentEquals(expected)
            sb.append(
                if (ok) "    ✓ SHA-256 一致：写→读整条路径在真机上成立。\n"
                else "    ✗ SHA-256 不一致！解密出来的字节和写进去的不是同一份。\n"
            )
            sb.toString()
        } catch (e: VaultError) {
            "[D] 自检失败：${e.javaClass.simpleName}: ${e.message}\n"
        } catch (e: IOException) {
            "[D] 自检失败（I/O）：${e.javaClass.simpleName}: ${e.message}\n"
        } finally {
            PasswordNormalizer.wipe(masterKey)
        }
    }

    /**
     * **仅 debug**：清空整个仓库（删除 vault 目录）并清掉主密钥。
     *
     * 存在的唯一理由：密码一旦被锚定就**没有**恢复路径（这是设计，不是缺陷），
     * 而开发阶段很容易把密码锚错（空仓库对任何密码都放行）。没有这条路，
     * 真机就卡死在「再也进不去」的状态。
     *
     * 会先统计要删什么，再删 —— 让屏幕上的输出能证明它删掉的确实是这个仓库。
     */
    suspend fun resetVault(context: Context): String = withContext(Dispatchers.IO) {
        val dir = vaultDir(context)
        if (!dir.exists()) {
            KeyRepository.clear()
            sessionPasswordProven = false
            return@withContext "[R] 没有仓库目录（${dir.absolutePath}），无需重置。已清掉内存里的密钥。"
        }
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val bytes = files.sumOf { it.length() }
        val listing = files.joinToString("\n      ") { "${it.name}（${it.length()} 字节）" }

        KeyRepository.clear()
        sessionPasswordProven = false
        val deleted = dir.deleteRecursively()

        val sb = StringBuilder("[R] 重置仓库\n    目录 ${dir.absolutePath}\n")
        sb.append("    待删 ${files.size} 个文件，共 $bytes 字节：\n      $listing\n")
        sb.append(if (deleted) "    ✓ 已删除，目录不再存在=${!dir.exists()}\n    请返回上一页重新设置密码。\n"
                 else "    ✗ 删除失败（目录或文件被占用？）\n")
        sb.toString()
    }

    /**
     * 导入管线（stage-1）。加密写盘只有这一份实现（spec: import-flow MUST NOT 有第二份）。
     * 每次新建即可：它不持有任何状态。
     */
    fun pipeline(context: Context): ImportPipeline = ImportPipeline(directory(context))

    /**
     * 把一条密文**整份**解密写到 [sink]（导出）。
     *
     * 与播放走同一条读取路径（`EncryptedFileReader.read`），区别只是连续读完并写出。
     * [onProgress] 在 IO 线程上回调，UI 侧自己切主线程。
     *
     * 注意：目标由用户在系统选择器里指定 —— 明文写在**用户选择的位置**，这是用户明确要求的动作，
     * 与本项目「磁盘上不留明文」的约束不冲突（那条约束约束的是 App 自己偷偷落盘）。
     */
    suspend fun exportEntry(
        context: Context,
        uuid: String,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val masterKey = KeyRepository.getMasterKey()
            ?: throw IllegalStateException("未解锁")
        try {
            val file = directory(context).cipherFile(uuid)
            FileContainerChannel.openForRead(file).use { channel ->
                EncryptedFileReader.open(channel, masterKey).use { reader ->
                    val total = reader.plaintextLength
                    var offset = 0L
                    while (offset < total) {
                        val n = minOf(EXPORT_CHUNK, total - offset).toInt()
                        sink.write(reader.read(offset, n))
                        offset += n
                        onProgress(offset, total)
                    }
                    sink.flush()
                    offset
                }
            }
        } finally {
            PasswordNormalizer.wipe(masterKey)
        }
    }

    /**
     * 删除一条密文条目，并清掉它的「尚未隐藏」记录。
     *
     * 删除就是删文件（不是覆写）：闪存的磨损均衡让「覆写后删除」没有意义，
     * 声称能安全擦除反而是在骗人。
     */
    suspend fun deleteEntry(context: Context, uuid: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = directory(context).deleteEntry(uuid)
        if (deleted) {
            NotHiddenStore.clear(context, uuid)
            // 条目没了，续播位置也必须清掉，否则同一个 uuid 再出现时会跳到荒谬的位置
            com.example.avideo.playback.PlaybackPositionStore.clear(context, uuid)
        }
        deleted
    }

    /** 主动锁定（退出、切后台超时）。 */
    fun lock() {
        KeyRepository.clear()
        sessionPasswordProven = false
    }

    /**
     * 用**生产完全相同的路径**（NFC 规范化 + argon2kt）派生一把密钥，只给 debug 自检用。
     *
     * 为什么放到这里而不是让诊断页自己 `new MasterKeyDeriver(...)`：派生入口只能有一处。
     * 自检走的路径一旦和生产分家，「自检通过」就说明不了生产没问题——而这条自检要证的
     * 正是「真机上的 argon2kt 与 JVM/BC 产出同一份字节」。
     */
    suspend fun deriveForSelfTest(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray =
        withContext(Dispatchers.Default) { deriver.derive(password, salt, params) }

    /** 导出时每次解密写出的明文块大小。64 KiB 足够摊薄 syscall，又不会占太多内存。 */
    private const val EXPORT_CHUNK = 64 * 1024L

    private fun derive(password: CharArray, meta: VaultMeta): ByteArray =
        deriver.derive(password, meta.kdfSalt, meta.kdfParams)

    /** 进 KeyRepository 的是副本（`setMasterKey` 内部再 `copyOf()`），这里立刻清掉本地那份。 */
    private fun holdMasterKey(key: ByteArray) {
        require(key.size == ContainerConstants.KEYS_LEN) { "master key must be 32 bytes" }
        KeyRepository.setMasterKey(key)
        PasswordNormalizer.wipe(key)
    }
}

/** 仓库状态。 */
sealed interface VaultState {
    /** 还没有仓库：走首次设置流程。 */
    object NotCreated : VaultState

    /** 有可用的 vault.meta。 */
    object Ready : VaultState

    /** 两份边车都在、但都过不了 CRC32。 */
    object Corrupted : VaultState
}

/** 解锁/创建的结局。 */
sealed interface UnlockOutcome {

    /**
     * 已进入仓库，主密钥已驻留 [KeyRepository]。
     *
     * [probe] 用来区分两种完全不同的进入方式：密码**已被证明**正确，
     * 还是仓库为空、**任何**密码都会被放行（此时 MUST NOT 说「密码正确」）。
     */
    data class Unlocked(val probe: MasterKeyProbe) : UnlockOutcome

    /**
     * 抽样全部失败：密码不对（或这批文件的 wrapped_file_key 全被损坏 —— 两者无法区分，
     * 所以按更可能的那个报）。
     *
     * [candidateFingerprint] 是**这次输入的密码**派生出的密钥指纹，用于人工与写入时的
     * 指纹对照。它不含密钥本身，只是一个 4 字节的标识，不落盘。
     */
    data class WrongPassword(val candidateFingerprint: String) : UnlockOutcome

    object VaultCorrupted : UnlockOutcome
    data class Failed(val detail: String) : UnlockOutcome
}

/**
 * 主密钥指纹：`SHA-256("avideo-master-key-id-v1" ‖ key)` 的前 4 字节。
 *
 * 用途只有屏幕上的**人工比对**：写入时打印一份、解锁失败时打印一份，一致就说明
 * 「密码记错了」这个解释不成立，该去查代码。4 字节不足以反推密钥，也不落盘、
 * 不进日志文件 —— 否则它就变成一个离线爆破的口令 oracle（那正是容器格式里
 * wrapped_file_key 已经提供的东西，不该再多一个）。
 */
internal fun keyFingerprint(masterKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("avideo-master-key-id-v1".toByteArray(Charsets.UTF_8))
    digest.update(masterKey)
    return digest.digest().take(4).joinToString("") { "%02x".format(it) }
}
/** 列表条目。显示名未知（条目损坏）时 [displayName] 为 null。 */
data class VaultListItem(
    val uuid: String,
    val displayName: String?,
    val durationMs: Long?,
    val corrupted: Boolean,
)
