package com.avideo.core.vault

import com.avideo.core.container.ContainerChannel
import com.avideo.core.container.ContainerClassifier
import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileReader
import com.avideo.core.container.EncryptedFileWriter
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.RandomSource
import com.avideo.core.errors.UncommittedFileError
import com.avideo.core.errors.VaultError
import java.io.File
import java.io.IOException

/**
 * 仓库目录：
 *
 *   <vault>/
 *     vault.meta        明文边车
 *     vault.meta.bak    镜像副本
 *     data/<uuid>.bin   每个视频一个密文文件
 *
 * 没有任何索引文件——列出条目 = 枚举 data/ 目录 + 逐个解 metadata 区。
 *
 * [channelFactory] 只为可测性存在：stage-2 的「列出条目不解任何数据块」
 * 需要观察到读取范围，生产路径用默认的 [FileContainerChannel.openForRead]。
 */
class VaultDirectory(
    val root: File,
    private val channelFactory: (File) -> ContainerChannel = { FileContainerChannel.openForRead(it) },
) {

    val metaFile: File get() = File(root, VaultMetaIO.FILE_NAME)
    val backupFile: File get() = File(root, VaultMetaIO.BACKUP_NAME)
    val dataDir: File get() = File(root, DATA_DIR)

    /**
     * 创建仓库：生成随机盐，写出 vault.meta 与 .bak。
     *
     * **不是幂等的，也不允许在已有仓库上重跑。** 每次调用都会生成**新的盐**；盐一变，
     * 由它派生的主密钥就变了，而所有已提交的 `<uuid>.bin` 都是用**旧**主密钥包裹 data key 的
     * ——它们从此再也解不开（`KeyUnwrapFailedError`），而旧盐已被 vault.meta/.bak 覆盖，
     * **没有任何恢复路径**。所以这里 MUST 拒绝，而不是「就当作重新初始化」。
     *
     * 拒绝的三种情况：vault.meta 已存在、.bak 已存在（一份丢了但另一份还在）、
     * 或 data/ 里已有密文文件（vault.meta 被手工删掉，但用户的视频还在）。
     * 后两种同样会造成不可逆的数据丢失，所以 MUST 一起拦住。
     *
     * 注意：名字刻意不叫 `createIfAbsent()` —— 那个名字会让人以为它「已有就返回已有的那份」，
     * 而这里是**抛错**。
     *
     * @throws IllegalStateException 目标位置已存在仓库（消息不含任何密钥/密码/显示名）
     */
    fun create(params: KdfParams, random: RandomSource = RandomSource.secure()): VaultMeta {
        check(!hasExistingVault()) {
            "refusing to create a vault over an existing one: a new salt would make every committed file unreadable"
        }
        root.mkdirs()
        dataDir.mkdirs()
        val meta = VaultMeta.create(random, params)
        VaultMetaIO.write(root, meta)
        return meta
    }

    /** 是否已经有仓库的痕迹（两份边车之一存在，或 data/ 里已有密文文件）。 */
    private fun hasExistingVault(): Boolean =
        metaFile.exists() || backupFile.exists() ||
            dataDir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }?.isNotEmpty() == true

    fun loadMeta(): VaultMeta = VaultMetaIO.loadOrThrow(root)

    /**
     * `data/<uuid>.bin`。
     *
     * uuid 是**字符串拼进路径**的，所以 MUST 先校验形状：含 `/` 或 `..` 的 uuid 会把路径带出
     * `data/`，而 [newWriter] 打开文件用的是 `TRUNCATE_EXISTING` —— 撞上已有条目就是
     * **静默销毁**（不是报错，是另一个视频被覆盖）。只接受规范的 8-4-4-4-12 十六进制 UUID。
     *
     * 消息里只放长度、不放 uuid 本身：uuid 来自外部输入，原样回显会把控制字符带进日志。
     *
     * @throws IllegalArgumentException uuid 不是规范的 UUID 形状
     */
    fun cipherFile(uuid: String): File {
        require(UUID_PATTERN.matches(uuid)) {
            "uuid must be a canonical 8-4-4-4-12 hex uuid, got ${uuid.length} chars"
        }
        return File(dataDir, "$uuid.bin")
    }

    /** data/ 下所有密文文件（按名字排序，保证列表稳定）。 */
    fun cipherFiles(): List<File> =
        dataDir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun newWriter(uuid: String, masterKey: ByteArray): EncryptedFileWriter =
        EncryptedFileWriter(FileContainerChannel.openForWrite(cipherFile(uuid)), masterKey)

    /**
     * 已提交的条目。未提交的（导入被杀留下的）直接排除；单个损坏的条目降级为
     * [VaultEntry.Corrupted] 而不是让整个列表失败。
     *
     * **主密钥不对时会怎样（本阶段刻意不处理，stage-1 MUST 处理）：** 解 wrapped_file_key
     * 是每文件独立做的，所以一把**错的主密钥**会让**每一条**都降级，
     * `reason == "KeyUnwrapFailedError"`。也就是说「密码打错」现在长得和「N 个文件损坏」
     * 一模一样；而 spec（vault-meta「读取优先级与回退」）要求
     * 「密码不对」（[com.avideo.core.errors.WrongPasswordError]）与「仓库元数据损坏」
     * （[com.avideo.core.errors.VaultCorruptedError]）是**不同**的类型、不同的提示，
     * MUST NOT 合并成一条消息。
     *
     * **stage-1 MUST 在显示任何消息之前，把这个「全库都是 KeyUnwrapFailedError」的全局情形
     * 与「少数几个文件真的坏了」区分开**（例如全库统一失败 ⇒ 先按密码错处理并提示重输）。
     * 本阶段不做这件事，只是把现状钉在测试
     * `a wrong master key degrades every entry instead of throwing` 里，
     * 免得它悄悄变成别的样子。
     *
     * @param masterKey MUST 是 32 字节。长度不对时 [com.avideo.core.crypto.KeyWrapping.unwrap]
     *   抛出的是 `IllegalArgumentException`（**不是** [VaultError]），它不在这里的捕获范围内，
     *   会直接从 `list` 传播出去 —— 那是编程错误，不该被伪装成「这一条损坏了」。
     */
    fun list(masterKey: ByteArray): List<VaultEntry> = cipherFiles().mapNotNull { file ->
        val uuid = file.name.removeSuffix(SUFFIX)
        var reader: EncryptedFileReader? = null
        var channel: ContainerChannel? = null
        try {
            // open 必须在 try 内：文件可能在 cipherFiles() 与打开之间被删/被占用（TOCTOU），
            // 那种 I/O 失败只该让这一条降级，不该让整个列表失败。
            channel = channelFactory(file)
            reader = EncryptedFileReader.open(channel, masterKey)
            VaultEntry.Committed(uuid, file, reader.metadata)
        } catch (e: UncommittedFileError) {
            null                                     // 未提交：列表 MUST 排除
        } catch (e: VaultError) {
            // 只带错误类型名，不带任何路径/文件名之外的敏感信息
            VaultEntry.Corrupted(uuid, file, e.javaClass.simpleName)
        } catch (e: IOException) {
            // 读取期间的 I/O 失败（文件消失、被占用等）：同样只降级这一条。
            // 不放宽到 Exception：那会把编程错误也伪装成「损坏」，掩盖真正的 bug。
            VaultEntry.Corrupted(uuid, file, e.javaClass.simpleName)
        } finally {
            when {
                reader != null -> reader.close()
                channel != null -> channel.close()
            }
        }
    }

    /** 不需要密码：靠全零的 metadata_tag 判定（计划 D-1）。孤儿清理的入口。 */
    fun listUncommitted(): List<File> = cipherFiles().filter { file ->
        channelFactory(file).use { ContainerClassifier.classify(it) == ContainerClassifier.State.UNCOMMITTED }
    }

    companion object {
        const val DATA_DIR = "data"
        const val SUFFIX = ".bin"
        const val DEFAULT_METADATA_LEN = ContainerConstants.METADATA_LEN

        /**
         * 规范的 8-4-4-4-12 十六进制 UUID。**全串匹配**（[Regex.matches] 要求整个字符串吻合），
         * 所以 `a/b`、`..`、带扩展名的、少一段的一律拒绝 —— 这些字符根本不在字符类里。
         */
        private val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

/** 列表条目。 */
sealed class VaultEntry {
    abstract val uuid: String
    abstract val file: File

    data class Committed(
        override val uuid: String,
        override val file: File,
        val metadata: com.avideo.core.container.FileMetadata,
    ) : VaultEntry()

    /** [reason] 只含错误类型名，不含敏感值。 */
    data class Corrupted(
        override val uuid: String,
        override val file: File,
        val reason: String,
    ) : VaultEntry()
}
