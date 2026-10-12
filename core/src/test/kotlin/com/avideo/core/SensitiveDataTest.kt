package com.avideo.core

import com.avideo.core.container.*
import com.avideo.core.errors.VaultError
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** spec: vault-crypto「日志与异常不泄漏敏感信息」。 */
class SensitiveDataTest {

    private val masterKey = ByteArray(32) { (it * 13 + 5).toByte() }
    private val secretName = "私人视频-不要出现.mp4"

    @Test
    fun `exception messages never contain key bytes or the display name`() {
        // 明文里**真的放进**一个可辨识的标记。不这么做的话，「异常消息不含明文」那条断言是空转的：
        // 消息里本来就不可能出现一个从未被写入过的字符串。
        val marker = "AVLT-SECRET-MARK".toByteArray()
        val plaintext = patternBytes(5000).also { marker.copyInto(it, 5) }

        val file = File(tempDir(), "leak.bin")
        EncryptedFileWriter(
            FileContainerChannel.openForWrite(file),
            masterKey,
            ContainerConstants.METADATA_LEN,
            DeterministicRandomSource(8),
        ).use { it.writeFrom(ByteArrayInputStream(plaintext), secretName, plaintext.size.toLong()) }

        // 篡改 metadata 密文区的一个字节 → metadata_tag 校验失败
        val bytes = file.readBytes()
        val tamperAt = OffsetMapping.METADATA_OFFSET.toInt() + 40
        bytes[tamperAt] = (bytes[tamperAt] + 1).toByte()
        file.writeBytes(bytes)

        val error = try {
            EncryptedFileReader.open(FileContainerChannel.openForRead(file), masterKey)
            null
        } catch (e: VaultError) {
            e
        }
        assertTrue(error != null, "篡改后应当抛错")

        val text = error!!.message.orEmpty() + error.toString()
        assertTrue(!text.contains(secretName), "异常消息泄漏了显示名：$text")
        assertTrue(
            !text.contains(masterKey.joinToString("") { "%02x".format(it) }),
            "异常消息把主密钥按十六进制插值了：$text",
        )
        assertTrue(
            !text.contains(String(marker, Charsets.UTF_8)),
            "异常消息泄漏了明文内容：$text",
        )
    }
}
