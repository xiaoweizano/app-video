package com.avideo.core.fixture

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileWriter
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.MasterKeyDeriver
import com.avideo.core.crypto.PasswordNormalizer
import com.avideo.core.vault.VaultMeta
import com.avideo.core.vault.VaultMetaIO
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * fixture 生成器。用 `./gradlew :core:generateFixtures` 跑（workingDir = core/）。
 * 产物提交进仓库，正常运行测试时不会再生成。
 */
object FixtureSpec {
    const val PASSWORD = "café-密码-🔒"
    const val SALT_SEED = 20261009L
    const val RANDOM_SEED = 424242L
    const val PLAINTEXT_LEN = 1_048_561        // 恰好跨块 + 末块变长
    const val DISPLAY_NAME = "固定素材.mp4"
    const val DURATION_MS = 987_654L
    const val UUID = "00000000-0000-4000-8000-000000000001"
    val PARAMS: KdfParams = KdfParams.PRODUCTION

    /** 明文模式：byte i = ((i * 31) xor (i shr 8) xor 0x5A)。任何平台都能复算。 */
    fun plaintext(): ByteArray = ByteArray(PLAINTEXT_LEN) { i ->
        ((i * 31) xor (i shr 8) xor 0x5A).toByte()
    }
}

fun main() {
    val fixturesRoot = File("src/test/resources/fixtures")
    val vaultDir = File(fixturesRoot, "vault")
    vaultDir.deleteRecursively()
    File(vaultDir, "data").mkdirs()

    val plaintext = FixtureSpec.plaintext()

    // 1) vault.meta：盐由固定种子生成 → 可复现
    val meta = VaultMeta(
        version = VaultMeta.VERSION,
        kdfSalt = ByteArray(VaultMeta.SALT_LEN).also { DeterministicRandomSource(FixtureSpec.SALT_SEED).nextBytes(it) },
        kdfParams = FixtureSpec.PARAMS,
    )
    VaultMetaIO.write(vaultDir, meta)

    // 2) 主密钥：由**第二实现**算出并记录 —— fixture 的信任来源
    val masterKey = BouncyCastleKeyDerivation().derive(
        PasswordNormalizer.normalize(FixtureSpec.PASSWORD.toCharArray()),
        meta.kdfSalt,
        meta.kdfParams,
    )
    // 3) 用生产实现再算一次，不一致就直接拒绝生成（生成阶段就把问题暴露出来）
    val reproduced = MasterKeyDeriver.jvm().derive(FixtureSpec.PASSWORD.toCharArray(), meta.kdfSalt, meta.kdfParams)
    check(masterKey.contentEquals(reproduced)) { "argon2-jvm 与 BouncyCastle 产出了不同的主密钥" }

    // 4) 容器密文：固定随机种子 → 可复现
    val container = File(vaultDir, "data/${FixtureSpec.UUID}.bin")
    EncryptedFileWriter(
        FileContainerChannel.openForWrite(container),
        masterKey,
        ContainerConstants.METADATA_LEN,
        DeterministicRandomSource(FixtureSpec.RANDOM_SEED),
    ).use {
        it.writeFrom(
            ByteArrayInputStream(plaintext),
            FixtureSpec.DISPLAY_NAME,
            plaintext.size.toLong(),
            FixtureSpec.DURATION_MS,
        )
    }

    val sha = MessageDigest.getInstance("SHA-256").digest(plaintext)
    File(fixturesRoot, "vector.properties").writeText(
        buildString {
            appendLine("# 由 ./gradlew :core:generateFixtures 生成。改任何一行都必须重新生成 vault/ 下的密文。")
            appendLine("# 复算步骤见同目录 README.md。")
            appendLine("password=${FixtureSpec.PASSWORD}")
            appendLine("kdf.salt.hex=${meta.kdfSalt.toHex()}")
            appendLine("kdf.m=${meta.kdfParams.memoryKib}")
            appendLine("kdf.t=${meta.kdfParams.iterations}")
            appendLine("kdf.p=${meta.kdfParams.parallelism}")
            appendLine("kdf.mode=Argon2id")
            appendLine("kdf.version=13")
            appendLine("master.key.hex=${masterKey.toHex()}")
            appendLine("plaintext.length=${plaintext.size}")
            appendLine("plaintext.pattern=byte(i) = (i*31) xor (i shr 8) xor 0x5A")
            appendLine("plaintext.sha256.hex=${sha.toHex()}")
            appendLine("container.metadata.len=${ContainerConstants.METADATA_LEN}")
            appendLine("container.display.name=${FixtureSpec.DISPLAY_NAME}")
            appendLine("container.duration.ms=${FixtureSpec.DURATION_MS}")
            appendLine("container.data.file=data/${FixtureSpec.UUID}.bin")
        },
        Charsets.UTF_8,
    )

    println("fixture 已写出到 ${fixturesRoot.absolutePath}")
    println("  master key = ${masterKey.toHex()}")
    println("  容器大小   = ${container.length()} 字节（期望 ${com.avideo.core.container.OffsetMapping.expectedFileSize(plaintext.size.toLong(), ContainerConstants.METADATA_LEN)}）")
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
