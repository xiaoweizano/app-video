package com.avideo.core.vault

import com.avideo.core.container.ContainerChannel
import com.avideo.core.container.ContainerClassifier
import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileReader
import com.avideo.core.container.EncryptedFileWriter
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.container.FixedHeader
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.KeyWrapping
import com.avideo.core.crypto.PasswordNormalizer
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

    /**
     * 新条目的写入器。
     *
     * [metadataLen] / [random] 可以注入，只为一件事：让导入管线在 JVM 测试里可用确定性的随机源。
     * 生产路径不要传它们。
     */
    fun newWriter(
        uuid: String,
        masterKey: ByteArray,
        metadataLen: Int = ContainerConstants.METADATA_LEN,
        random: RandomSource = RandomSource.secure(),
    ): EncryptedFileWriter =
        EncryptedFileWriter(FileContainerChannel.openForWrite(cipherFile(uuid)), masterKey, metadataLen, random)

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

    /**
     * 去重预检用的轻量索引：`(uuid, 显示名, 明文长度, 内容 SHA-256)`。
     *
     * 只读固定头与 metadata 区，**MUST NOT** 解密任何数据块
     * （spec: import-flow「导入前取库内哈希集合」+「不读数据块」）。
     * `plaintext_len` 来自固定头，是免费的 —— 导入时先拿它做长度闸门，
     * 只有长度撞上才有必要额外读一遍源文件。
     *
     * 未提交的文件与读不出来的条目一律跳过：单个坏条目 MUST NOT 让整个索引失败
     * （spec 同条：「单个条目损坏不影响集合」）。跳过的代价是「可能漏判一条重复」，
     * 那比「因为一个坏文件就导不进来」轻得多。
     */
    fun indexEntries(masterKey: ByteArray): List<VaultIndexEntry> = cipherFiles().mapNotNull { file ->
        val uuid = file.name.removeSuffix(SUFFIX)
        var reader: EncryptedFileReader? = null
        var channel: ContainerChannel? = null
        try {
            channel = channelFactory(file)
            reader = EncryptedFileReader.open(channel, masterKey)
            VaultIndexEntry(
                uuid = uuid,
                displayName = reader.metadata.displayName,
                plaintextLength = reader.plaintextLength,
                sha256 = reader.metadata.sha256,
            )
        } catch (e: UncommittedFileError) {
            null
        } catch (e: VaultError) {
            null
        } catch (e: IOException) {
            null
        } finally {
            when {
                reader != null -> reader.close()
                channel != null -> channel.close()
            }
        }
    }

    /**
     * 删除一条密文文件（「替换」时删旧条目、stage-1 的删除入口）。
     *
     * @return 文件确实不存在了 ⇒ true。uuid 形状不合法（含路径穿越）也返回 false ——
     *   那是编程错误，但这里不该抛：调用点在删文件的循环里，抛出去会让整批导入失败。
     */
    fun deleteEntry(uuid: String): Boolean = try {
        val file = cipherFile(uuid)
        !file.exists() || file.delete()
    } catch (e: IllegalArgumentException) {
        false
    }

    /** 不需要密码：靠全零的 metadata_tag 判定（计划 D-1）。孤儿清理的入口。 */
    fun listUncommitted(): List<File> = cipherFiles().filter { file ->
        channelFactory(file).use { ContainerClassifier.classify(it) == ContainerClassifier.State.UNCOMMITTED }
    }

    /**
     * 抽样探测主密钥是否正确（spec: vault-unlock「解锁判定必须抽样多个文件」）。
     *
     * **只做「解包 wrapped_file_key」这一步**，因为那是整条读取链里唯一由主密钥自认证的操作
     * （AES-GCM，128 位 tag）：解包成功**证明**主密钥正确——不存在「碰巧对上」；
     * 解包失败则无法区分「密钥错」与「这 60 字节被损坏」，所以必须抽多个文件，
     * 任意一个成功就足够（spec:「一个成功即证明密码正确」）。
     *
     * 为什么不拿 [list] 的 `Corrupted.reason` 反推：那是错误**类型名**，而
     * 「哪种错误发生在解包之前」是 [EncryptedFileReader] 内部的语句顺序——结构错误在解包之前、
     * header_tag 错误在解包之后。拿它判密码对错，等于把解锁绑定在另一处代码的执行顺序上，
     * 那处顺序一改，解锁判定会**静默**变错。
     *
     * 空仓库返回 `sampled == 0`（密码无法校验），调用方 MUST NOT 因此拒绝进入——
     * 否则用户永远导入不了第一个文件（spec: vault-unlock「空仓库的解锁不校验密码」）。
     *
     * 未提交的占位文件同样参与抽样：它们的固定头是**完整写过**的，解包照样成立，
     * 于是「上次导入被杀、仓库里只有一个孤儿」时密码仍然可校验。
     *
     * @param masterKey MUST 是 32 字节；长度不对时 [KeyWrapping.unwrap] 抛 `IllegalArgumentException`
     *   （不是 [VaultError]），会从这里直接传播出去——那是编程错误，不该被伪装成「探测失败」。
     */
    fun probeMasterKey(masterKey: ByteArray): MasterKeyProbe {
        val files = cipherFiles()
        if (files.isEmpty()) return MasterKeyProbe(sampled = 0, unwrapped = 0)
        val sample = sampleOf(files, PROBE_SAMPLE_MAX)
        var unwrapped = 0
        for (file in sample) if (unwrapsFileKey(file, masterKey)) unwrapped++
        return MasterKeyProbe(sampled = sample.size, unwrapped = unwrapped)
    }

    /** 等距取样：只取前几个的话，「前几个恰好损坏」的仓库会被误判成「密码错」。 */
    private fun sampleOf(files: List<File>, limit: Int): List<File> {
        if (files.size <= limit) return files
        val step = files.size.toDouble() / limit
        return (0 until limit).map { files[(it * step).toInt()] }
    }

    /** 只读固定头这 81 字节，不碰数据区，不碰 metadata。 */
    private fun unwrapsFileKey(file: File, masterKey: ByteArray): Boolean {
        var channel: ContainerChannel? = null
        return try {
            channel = channelFactory(file)
            val headerBytes = ByteArray(ContainerConstants.FIXED_HEADER_LEN)
            if (channel.readAt(0L, headerBytes, headerBytes.size) != headerBytes.size) return false
            PasswordNormalizer.wipe(KeyWrapping.unwrap(masterKey, FixedHeader.decode(headerBytes).wrappedFileKey))
            true
        } catch (e: VaultError) {
            false                                    // 结构坏了 / 解包失败：这一份证明不了任何事
        } catch (e: IOException) {
            false                                    // 文件消失、被占用等：同样只影响这一次探测
        } finally {
            // 关不掉的探测通道不该让解锁失败——它只是没被及时释放。
            runCatching { channel?.close() }
        }
    }

    companion object {
        const val DATA_DIR = "data"
        const val SUFFIX = ".bin"
        const val DEFAULT_METADATA_LEN = ContainerConstants.METADATA_LEN

        /**
         * [probeMasterKey] 的抽样上限。1000 个文件的仓库也必须只读几个文件头
         * （spec: vault-unlock「抽样有上限：MUST NOT 尝试全部」）。
         * 每次抽样只是一次 81 字节的读取 + 一次 AES-GCM 解包，代价与仓库大小无关。
         */
        const val PROBE_SAMPLE_MAX = 5

        /**
         * 规范的 8-4-4-4-12 十六进制 UUID。**全串匹配**（[Regex.matches] 要求整个字符串吻合），
         * 所以 `a/b`、`..`、带扩展名的、少一段的一律拒绝 —— 这些字符根本不在字符类里。
         */
        private val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

/**
 * 去重预检索引的一条。[sha256] 来自**加密 metadata 区**，不是文件名。
 */
data class VaultIndexEntry(
    val uuid: String,
    val displayName: String,
    /** 来自固定头。用它做长度闸门：长度不同必然不是重复。 */
    val plaintextLength: Long,
    val sha256: ByteArray,
) {
    // data class 里放了 ByteArray，equals/hashCode 必须自己给，否则是引用比较。
    override fun equals(other: Any?): Boolean =
        other is VaultIndexEntry && uuid == other.uuid && displayName == other.displayName &&
            plaintextLength == other.plaintextLength && sha256.contentEquals(other.sha256)

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + plaintextLength.hashCode()
        result = 31 * result + sha256.contentHashCode()
        return result
    }

    override fun toString(): String =
        "VaultIndexEntry(uuid=$uuid, displayNameLength=${displayName.length}, " +
            "plaintextLength=$plaintextLength, sha256=<${sha256.size}B>)"
}

/**
 * [VaultDirectory.probeMasterKey] 的结果。
 *
 * 只含计数，不含任何密钥材料或文件名 —— 它可以出现在日志与错误提示里。
 */
data class MasterKeyProbe(val sampled: Int, val unwrapped: Int) {
    /** 至少一个文件解包成功 ⇒ 主密钥被**证明**正确（不是「大概对」）。 */
    val keyProven: Boolean get() = unwrapped > 0

    /** 一个文件都没抽到（空仓库）：密码**无法**被校验，系统 MUST NOT 声称已验证。 */
    val unverifiable: Boolean get() = sampled == 0
}

/** 列表条目。 */
sealed class VaultEntry {    abstract val uuid: String
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
