package com.example.avideo.crypto

import android.content.Context
import com.avideo.core.container.ContainerConstants
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.MasterKeyDeriver
import com.avideo.core.crypto.PasswordNormalizer
import com.avideo.core.errors.VaultCorruptedError
import com.avideo.core.errors.VaultError
import com.avideo.core.vault.MasterKeyProbe
import com.avideo.core.vault.VaultDirectory
import com.avideo.core.vault.VaultEntry
import com.avideo.core.vault.VaultMeta
import com.avideo.core.vault.VaultMetaIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
                    // 不放行就永远导不进第一个文件。但界面 MUST 如实说「密码尚未被校验」。
                    holdMasterKey(candidate)
                    UnlockOutcome.Unlocked(probe)
                } else {
                    UnlockOutcome.WrongPassword
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

    /** 主动锁定（退出、切后台超时）。 */
    fun lock() = KeyRepository.clear()

    /**
     * 用**生产完全相同的路径**（NFC 规范化 + argon2kt）派生一把密钥，只给 debug 自检用。
     *
     * 为什么放到这里而不是让诊断页自己 `new MasterKeyDeriver(...)`：派生入口只能有一处。
     * 自检走的路径一旦和生产分家，「自检通过」就说明不了生产没问题——而这条自检要证的
     * 正是「真机上的 argon2kt 与 JVM/BC 产出同一份字节」。
     */
    suspend fun deriveForSelfTest(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray =
        withContext(Dispatchers.Default) { deriver.derive(password, salt, params) }

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

    object WrongPassword : UnlockOutcome
    object VaultCorrupted : UnlockOutcome
    data class Failed(val detail: String) : UnlockOutcome
}

/** 列表条目。显示名未知（条目损坏）时 [displayName] 为 null。 */
data class VaultListItem(
    val uuid: String,
    val displayName: String?,
    val durationMs: Long?,
    val corrupted: Boolean,
)
