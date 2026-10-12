package com.avideo.core.fixture

import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileReader
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.container.OffsetMapping
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.MasterKeyDeriver
import com.avideo.core.crypto.PasswordNormalizer
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossImplementationFixtureTest {

    private val root = File("src/test/resources/fixtures")
    private val vector: Properties = Properties().also { properties ->
        val stream = CrossImplementationFixtureTest::class.java
            .getResourceAsStream("/fixtures/vector.properties")
            ?: error("fixture 缺失：请先跑 ./gradlew :core:generateFixtures")
        InputStreamReader(stream, Charsets.UTF_8).use { properties.load(it) }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** T7 / F6.1：两个独立实现必须产出同一把主密钥。 */
    @Test
    fun `jvm implementation reproduces the key recorded from the second implementation`() {
        val salt = hexToBytes(vector.getProperty("kdf.salt.hex"))
        val params = KdfParams(
            memoryKib = vector.getProperty("kdf.m").toInt(),
            iterations = vector.getProperty("kdf.t").toInt(),
            parallelism = vector.getProperty("kdf.p").toInt(),
        )
        val expected = hexToBytes(vector.getProperty("master.key.hex"))

        val fromJvm = MasterKeyDeriver.jvm()
            .derive(vector.getProperty("password").toCharArray(), salt, params)
        val fromBouncyCastle = BouncyCastleKeyDerivation()
            .derive(PasswordNormalizer.normalize(vector.getProperty("password").toCharArray()), salt, params)

        assertContentEquals(expected, fromJvm, "argon2-jvm 与 fixture 记录的主密钥不一致")
        assertContentEquals(expected, fromBouncyCastle, "BouncyCastle 与 fixture 记录的主密钥不一致")
    }

    /** 顺带证明 NFC 规范化：NFD 形式的同一密码必须派生出同一把主密钥。 */
    @Test
    fun `nfd form of the fixture password derives the same key`() {
        val salt = hexToBytes(vector.getProperty("kdf.salt.hex"))
        val params = KdfParams(
            memoryKib = vector.getProperty("kdf.m").toInt(),
            iterations = vector.getProperty("kdf.t").toInt(),
            parallelism = vector.getProperty("kdf.p").toInt(),
        )
        val nfd = java.text.Normalizer.normalize(
            vector.getProperty("password"),
            java.text.Normalizer.Form.NFD,
        )
        assertTrue(nfd != vector.getProperty("password"), "fixture 密码本身就是 NFD，这条测试无效")
        assertContentEquals(
            hexToBytes(vector.getProperty("master.key.hex")),
            MasterKeyDeriver.jvm().derive(nfd.toCharArray(), salt, params),
        )
    }

    /** T7 的主体：拿 fixture 的密文文件，用 fixture 的密码完整解出来。 */
    @Test
    fun `fixture container decrypts to the recorded plaintext hash`() {
        val salt = hexToBytes(vector.getProperty("kdf.salt.hex"))
        val params = KdfParams(
            memoryKib = vector.getProperty("kdf.m").toInt(),
            iterations = vector.getProperty("kdf.t").toInt(),
            parallelism = vector.getProperty("kdf.p").toInt(),
        )
        val masterKey = MasterKeyDeriver.jvm()
            .derive(vector.getProperty("password").toCharArray(), salt, params)

        val container = File(root, "vault/${vector.getProperty("container.data.file")}")
        assertTrue(container.isFile, "缺 fixture 密文：$container")
        assertEquals(
            OffsetMapping.expectedFileSize(
                vector.getProperty("plaintext.length").toLong(),
                vector.getProperty("container.metadata.len").toInt(),
            ),
            container.length(),
        )

        EncryptedFileReader.open(FileContainerChannel.openForRead(container), masterKey).use { reader ->
            assertEquals(vector.getProperty("plaintext.length").toLong(), reader.plaintextLength)
            assertEquals(vector.getProperty("container.display.name"), reader.metadata.displayName)
            assertEquals(vector.getProperty("container.duration.ms").toLong(), reader.metadata.durationMs)

            val expectedSha = hexToBytes(vector.getProperty("plaintext.sha256.hex"))
            assertContentEquals(expectedSha, reader.metadata.sha256)

            // 全量明文（分块读，避免一次性分配）→ 独立算一遍 SHA-256
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            val bufferSize = ContainerConstants.CHUNK_PLAINTEXT
            while (offset < reader.plaintextLength) {
                val len = minOf(bufferSize.toLong(), reader.plaintextLength - offset).toInt()
                digest.update(reader.read(offset, len))
                offset += len
            }
            assertContentEquals(expectedSha, digest.digest(), "解出的明文哈希与 fixture 记录不符")

            // 抽查明文模式本身（任何平台都能复算 byte(i) = (i*31) xor (i shr 8) xor 0x5A）
            assertContentEquals(
                FixtureSpec.plaintext().copyOfRange(0, 64),
                reader.read(0L, 64),
            )
            assertContentEquals(
                FixtureSpec.plaintext().copyOfRange(FixtureSpec.PLAINTEXT_LEN - 64, FixtureSpec.PLAINTEXT_LEN),
                reader.read((FixtureSpec.PLAINTEXT_LEN - 64).toLong(), 64),
            )
        }
    }

    @Test
    fun `fixture vault meta loads and its crc32 passes`() {
        val fromDisk = File(root, "vault/vault.meta").readBytes()
        val meta = com.avideo.core.vault.VaultMeta.decode(fromDisk)
        assertEquals(vector.getProperty("kdf.m").toInt(), meta.kdfParams.memoryKib)
        assertContentEquals(hexToBytes(vector.getProperty("kdf.salt.hex")), meta.kdfSalt)
    }
}
