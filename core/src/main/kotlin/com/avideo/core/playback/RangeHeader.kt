package com.avideo.core.playback

/**
 * HTTP Range 的解析结果（RFC 7233 的 `bytes` 单位子集）。
 *
 * 为什么要单独一个纯函数：Range 语义是播放桥最容易写错、也最难在真机上定位的地方
 * （拖进度条花屏、结尾几秒播不出来、VLC 反复重试）。它不依赖 Android，放在 :core 就能测。
 */
sealed interface RangeSpec {

    /** 没有 Range 头，或语法上无法理解 —— 按**整份**返回 200。 */
    object Whole : RangeSpec

    /** 合法区间，**闭区间** [start, end]，已按明文长度裁剪。对应 206。 */
    data class Bytes(val start: Long, val end: Long) : RangeSpec

    /** 越界（或空文件）—— 对应 416，且 MUST NOT 返回任何明文字节。 */
    object Unsatisfiable : RangeSpec
}

/**
 * 解析 `Range` 头。只支持单区间 —— 多区间（`multipart/byteranges`）播放器不会用，
 * 遇到时取第一段而不是回 200 全文：**回全文会让 2 GB 的视频一次性灌进播放器**。
 *
 * 规则上刻意区分三件事，别合并：
 *  - 语法不认识（`items=…`、`bytes=abc`）⇒ [RangeSpec.Whole]（RFC: 忽略该头，200 全文）
 *  - 语法认识但越界（`bytes=99999999-`、空文件）⇒ [RangeSpec.Unsatisfiable]（416）
 *  - 语法认识且合法 ⇒ [RangeSpec.Bytes]，终点超过明文长度时**裁剪**而不是报错
 *    （RFC 允许而且要求这样：客户端不知道文件多大时会发一个很大的终点）
 */
fun parseRange(raw: String?, plaintextLength: Long): RangeSpec {
    if (raw.isNullOrBlank()) return RangeSpec.Whole
    val value = raw.trim()
    // 单位必须是 bytes。其它单位（含大小写混写之外的）一律忽略。
    if (!value.startsWith("bytes=", ignoreCase = true)) return RangeSpec.Whole

    val first = value.substring(6).substringBefore(',').trim()
    val dash = first.indexOf('-')
    if (dash < 0) return RangeSpec.Whole
    val startText = first.substring(0, dash).trim()
    val endText = first.substring(dash + 1).trim()
    if (startText.isEmpty() && endText.isEmpty()) return RangeSpec.Whole

    // 空文件：任何区间都越界。MUST 在开读之前判掉，否则会走进 read(0, n) 的边界。
    if (plaintextLength <= 0L) return RangeSpec.Unsatisfiable

    return try {
        if (startText.isEmpty()) {
            // 后缀形式 bytes=-N：最后 N 个字节
            val n = endText.toLong()
            if (n <= 0L) RangeSpec.Unsatisfiable
            else RangeSpec.Bytes(maxOf(0L, plaintextLength - n), plaintextLength - 1L)
        } else {
            val start = startText.toLong()
            if (start < 0L) return RangeSpec.Whole
            if (start >= plaintextLength) return RangeSpec.Unsatisfiable
            val end = if (endText.isEmpty()) plaintextLength - 1L else endText.toLong()
            // 语法无效（end < start）：RFC 要求忽略该头
            if (end < start) RangeSpec.Whole else RangeSpec.Bytes(start, minOf(end, plaintextLength - 1L))
        }
    } catch (e: NumberFormatException) {
        // 数字溢出或非数字：语法无法理解 ⇒ 200 全文
        RangeSpec.Whole
    }
}
