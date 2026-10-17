package com.avideo.core.importing

import java.io.InputStream

/**
 * 一个导入源。两条导入路径（文档选择器 / 分享 Intent）都实现这个接口，
 * 于是加密写盘只有一份实现（spec: import-flow「两条导入路径共用同一条写盘逻辑」）。
 *
 * 设计上刻意只暴露「给我一个流」：
 *  - [open] MUST 返回**原始字节**，不许解码、缩放或转码（spec: media-source）。
 *    用 `ACTION_PICK` / `ACTION_GET_CONTENT` 拿到的可能是相册转码后的副本，
 *    所以实现方 MUST 用 `ACTION_OPEN_DOCUMENT`。
 *  - [statedSize] 只是提示，可能不准。写入的长度以**实读字节数**为准
 *    （stage-0 的 commit 会按实读值重写 `plaintext_len`）。
 *  - [cleanup] 只在密文**已提交**之后调用。删除失败是预期分支，不是异常
 *    （spec: source-cleanup「删除失败是预期可能发生的情况」）。
 */
interface ImportSource {

    /** 放进**加密 metadata** 的显示名。通常来自 provider，MUST NOT 来自路径。 */
    val displayName: String

    /** provider 声明的大小；未知或不可信时为 null。 */
    val statedSize: Long?

    /** 打开原始字节流。调用方负责关闭。 */
    fun open(): InputStream

    /**
     * 删除源文件并**独立验证**它确实不可读了。
     *
     * 只允许在 [ImportPipeline] 提交成功之后调用 —— 否则会删掉用户唯一的那份。
     */
    fun cleanup(): CleanupResult

    /** 结果与日志里的短标识。不得含完整路径（spec: vault-crypto「日志不泄漏敏感信息」）。 */
    val label: String get() = displayName
}

/** [ImportSource.cleanup] 的结果。 */
sealed interface CleanupResult {

    /** 删除后验证通过：源 URI 已不可读，且尽力检查过的位置都不再可见。 */
    object Hidden : CleanupResult

    /**
     * 删除失败、或无法验证。条目 MUST 进入「尚未隐藏」。
     *
     * [detail] 只许含结构性信息（哪一处检查没通过 / 哪个 API 失败），不含路径或文件名 ——
     * 这个值会进 `source-cleanup` 的持久化状态与诊断输出。
     */
    data class NotHidden(val detail: String) : CleanupResult
}

/** 用户对一条重复项的处置（spec: duplicate-detection「重复时给出三选一」）。 */
enum class DuplicateChoice {
    /** 不导入，保留库里已有的那条。 */
    SKIP,

    /** 用新的替换已有的：先写新的，成功后再删旧的（顺序不能反，否则写失败就白丢一条）。 */
    REPLACE,

    /** 两份都留着。 */
    KEEP_BOTH,
}

/**
 * 预检阶段判定的一条重复。
 *
 * [existingName] 是**库里已有条目**的显示名，用于提示「与哪一个重复」
 * （spec 要求报重复时指出对方是谁）。
 */
data class DedupMatch(
    val existingUuid: String,
    val existingName: String,
)
