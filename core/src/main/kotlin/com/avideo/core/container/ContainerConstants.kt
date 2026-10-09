package com.avideo.core.container

/**
 * 容器格式的全部常量。
 * 这是本模块里**唯一允许出现字面量数字**的文件（BOUNDARIES.md Q4/Q10 冻结的布局）。
 */
object ContainerConstants {

    /** 密文文件的 magic。跨平台实现必须用同一取值（见计划 D-5）。 */
    val MAGIC: ByteArray = byteArrayOf(0x41, 0x56, 0x4C, 0x54) // "AVLT"

    const val VERSION: Byte = 1

    const val MAGIC_LEN = 4
    const val VERSION_LEN = 1
    const val WRAPPED_FILE_KEY_LEN = 60   // nonce(12) + ct(32) + tag(16)
    const val BASE_NONCE_LEN = 4
    const val PLAINTEXT_LEN_LEN = 8
    const val METADATA_LEN_LEN = 4

    /** 4 + 1 + 60 + 4 + 8 + 4 */
    const val FIXED_HEADER_LEN = MAGIC_LEN + VERSION_LEN + WRAPPED_FILE_KEY_LEN +
        BASE_NONCE_LEN + PLAINTEXT_LEN_LEN + METADATA_LEN_LEN

    const val HEADER_TAG_LEN = 16
    const val METADATA_TAG_LEN = 16
    const val AEAD_TAG_LEN = 16

    const val DATA_KEY_LEN = 32
    const val KEYS_LEN = 32
    const val KEY_WRAP_NONCE_LEN = 12

    /** Q10：定长预留区。可调，但**不是**格式冻结项——读者永远从文件头读这个数。 */
    const val METADATA_LEN = 1024

    /** 明文块 = 1 MiB − tag。 */
    const val CHUNK_STRIDE = 1_048_576
    const val CHUNK_PLAINTEXT = CHUNK_STRIDE - AEAD_TAG_LEN

    /** real_len(2) + name_len(2) + duration_ms(8) + sha256(32) + flags(1) */
    const val METADATA_FIXED_FIELDS_LEN = 2 + 2 + 8 + 32 + 1
    const val SHA256_LEN = 32

    const val COUNTER_HEADER = 0L
    const val COUNTER_METADATA = 1L
    const val COUNTER_FIRST_DATA_CHUNK = 2L

    /** duration 探测失败的哨兵值 = 0xFFFFFFFFFFFFFFFF（无符号全 1）。 */
    const val DURATION_UNKNOWN = -1L

    /** flags 保留位，本阶段必须为 0（BOUNDARIES.md Q16）。 */
    const val FLAGS_RESERVED: Byte = 0x00

    /**
     * 显示名可用字节数 = 预留区 − 固定字段（1024 时为 979）。
     * MUST NOT 为负：预留区连固定字段都装不下时，截断循环永远终止不了
     * （max < 0 时连空名字都满足不了 `bytes.size <= max`）。
     */
    fun maxDisplayNameBytes(metadataLen: Int): Int {
        require(metadataLen >= METADATA_FIXED_FIELDS_LEN) {
            "metadata reserve $metadataLen is smaller than the fixed fields ($METADATA_FIXED_FIELDS_LEN)"
        }
        return metadataLen - METADATA_FIXED_FIELDS_LEN
    }
}
