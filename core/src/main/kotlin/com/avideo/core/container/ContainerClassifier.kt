package com.avideo.core.container

/**
 * 不持有任何密钥的分类器：读固定头 → 定位 metadata_tag → 判断它是否全零。
 *
 * 为什么不需要密码：Q10 选定的提交协议下，占位阶段把 metadata_tag 写成 16 个零字节，
 * commit 的最后一步才把真 tag 写上去。于是「未提交」这个状态在磁盘上是**明文可判**的，
 * 恢复流程（stage-1 的孤儿清理）因此不必先解锁。
 *
 * 已知取舍：这里的 metadata_tag 偏移来自**未经认证**的 metadata_len。威胁模型
 * （BOUNDARIES.md Q1）不包含能改写 getFilesDir() 的攻击者，所以只用来做分类是安全的；
 * 真正的完整性由 [EncryptedFileReader] 的 header_tag 校验负责。
 */
object ContainerClassifier {

    enum class State {
        /** metadata_tag 非全零：看起来已提交（完整性仍待带密钥的路径确认）。 */
        COMMITTED,

        /** metadata_tag 全零：占位已写、尚未提交。列表页 MUST 排除它。 */
        UNCOMMITTED,

        /** 连固定头都读不出来 / 读不到 tag 的位置：结构损坏。 */
        MALFORMED,
    }

    fun classify(channel: ContainerChannel): State {
        val headerBytes = ByteArray(ContainerConstants.FIXED_HEADER_LEN)
        if (channel.readAt(0L, headerBytes, headerBytes.size) != headerBytes.size) return State.MALFORMED
        val header = try {
            FixedHeader.decode(headerBytes)
        } catch (e: Exception) {
            return State.MALFORMED
        }
        val tag = ByteArray(ContainerConstants.METADATA_TAG_LEN)
        val tagOffset = OffsetMapping.metadataTagOffset(header.metadataLen)
        if (channel.readAt(tagOffset, tag, tag.size) != tag.size) return State.MALFORMED
        return if (tag.all { it == 0.toByte() }) State.UNCOMMITTED else State.COMMITTED
    }
}
