package com.avideo.core.container

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerClassifierTest {

    private val masterKey = ByteArray(32) { it.toByte() }

    @Test
    fun `committed placeholder and malformed are told apart without a key`() {
        val dir = tempDir()
        val committed = File(dir, "a.bin")
        EncryptedFileWriter(FileContainerChannel.openForWrite(committed), masterKey, 1024, DeterministicRandomSource(1))
            .use { it.writeFrom(ByteArrayInputStream(patternBytes(3000)), "a.mp4", 3000L) }
        assertEquals(ContainerClassifier.State.COMMITTED, FileContainerChannel.openForRead(committed).use { ContainerClassifier.classify(it) })

        val placeholder = File(dir, "b.bin")
        EncryptedFileWriter(FileContainerChannel.openForWrite(placeholder), masterKey, 1024, DeterministicRandomSource(2))
            .use { it.writePlaceholder(3000L) }
        assertEquals(ContainerClassifier.State.UNCOMMITTED, FileContainerChannel.openForRead(placeholder).use { ContainerClassifier.classify(it) })

        val junk = File(dir, "c.bin")
        junk.writeBytes(ByteArray(64) { 0x5A })
        assertEquals(ContainerClassifier.State.MALFORMED, FileContainerChannel.openForRead(junk).use { ContainerClassifier.classify(it) })
    }
}
