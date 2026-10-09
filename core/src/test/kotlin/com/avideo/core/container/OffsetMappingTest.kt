package com.avideo.core.container

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OffsetMappingTest {

    private val p = ContainerConstants.CHUNK_PLAINTEXT.toLong()
    private val stride = ContainerConstants.CHUNK_STRIDE.toLong()

    @Test
    fun `chunking constants are the frozen numbers`() {
        assertEquals(1_048_560, ContainerConstants.CHUNK_PLAINTEXT)
        assertEquals(1_048_576, ContainerConstants.CHUNK_STRIDE)
        assertEquals(81, ContainerConstants.FIXED_HEADER_LEN)
        assertEquals(1024, ContainerConstants.METADATA_LEN)
        assertEquals(45, ContainerConstants.METADATA_FIXED_FIELDS_LEN)
    }

    /** 用途是钉住公式，不是钉住常数：1024 与 4096 必须分别得到两个已知值。 */
    @Test
    fun `data start is derived from metadata len`() {
        assertEquals(1137, OffsetMapping.dataStart(1024))
        assertEquals(4209, OffsetMapping.dataStart(4096))
        assertEquals(OffsetMapping.metadataTagOffset(1024) + 16, OffsetMapping.dataStart(1024))
    }

    @Test
    fun `chunk count handles exact multiples`() {
        assertEquals(0, OffsetMapping.chunkCount(0))
        assertEquals(1, OffsetMapping.chunkCount(1))
        assertEquals(1, OffsetMapping.chunkCount(p - 1))
        assertEquals(1, OffsetMapping.chunkCount(p))
        assertEquals(2, OffsetMapping.chunkCount(p + 1))
        assertEquals(2, OffsetMapping.chunkCount(2 * p))
        assertEquals(3, OffsetMapping.chunkCount(2 * p + 1))
    }

    @Test
    fun `disk offset at boundaries`() {
        val start = OffsetMapping.dataStart(1024)
        val len = 2 * p + 5
        assertEquals(start, OffsetMapping.diskOffset(0, 1024))
        assertEquals(start + 1, OffsetMapping.diskOffset(1, 1024))
        assertEquals(start + p - 1, OffsetMapping.diskOffset(p - 1, 1024))
        assertEquals(start + stride, OffsetMapping.diskOffset(p, 1024))
        assertEquals(start + stride + 1, OffsetMapping.diskOffset(p + 1, 1024))
        assertEquals(start + 2 * stride, OffsetMapping.diskOffset(2 * p, 1024))
        assertEquals(start + 2 * stride + 4, OffsetMapping.diskOffset(2 * p + 4, 1024))
        assertEquals(start + 2 * stride + 4, OffsetMapping.diskOffset(len - 1, 1024))
    }

    @Test
    fun `expected file size covers even and ragged tails`() {
        val start = OffsetMapping.dataStart(1024)
        assertEquals(start, OffsetMapping.expectedFileSize(0, 1024))
        assertEquals(start + 1 + 16, OffsetMapping.expectedFileSize(1, 1024))
        assertEquals(start + 2 * stride, OffsetMapping.expectedFileSize(2 * p, 1024))
        assertEquals(start + 2 * stride + 1 + 16, OffsetMapping.expectedFileSize(2 * p + 1, 1024))
    }

    /**
     * D3 的硬约束：data_start 只许由公式算出。硬编码之后所有其他测试仍然是绿的，
     * 所以只能直接扫源码。
     */
    @Test
    fun `no hardcoded data start literal in main sources`() {
        val sourceRoot = File("src/main/kotlin")
        assertTrue(sourceRoot.isDirectory, "未找到 $sourceRoot（测试工作目录应为 core/）")
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("1137") || it.contains("1153") } }
            .map { it.path }
            .toList()
        assertTrue(offenders.isEmpty(), "以下文件硬编码了 data_start 字面量：$offenders")
    }
}
