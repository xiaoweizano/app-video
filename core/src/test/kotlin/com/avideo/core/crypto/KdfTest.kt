package com.avideo.core.crypto

import com.avideo.core.DeterministicRandomSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KdfTest {

    private val deriver = MasterKeyDeriver.jvm()
    private val salt = ByteArray(16) { it.toByte() }
    private val params = KdfParams.TEST

    @Test
    fun `derivation is deterministic`() {
        val a = deriver.derive("correct horse".toCharArray(), salt, params)
        val b = deriver.derive("correct horse".toCharArray(), salt, params)
        assertContentEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun `different salt yields a different key`() {
        val other = ByteArray(16) { (it + 1).toByte() }
        assertFalse(deriver.derive("pw".toCharArray(), salt, params)
            .contentEquals(deriver.derive("pw".toCharArray(), other, params)))
    }

    @Test
    fun `different params yield a different key`() {
        val base = deriver.derive("pw".toCharArray(), salt, params)
        assertFalse(base.contentEquals(
            deriver.derive("pw".toCharArray(), salt, params.copy(memoryKib = params.memoryKib * 2))))
        assertFalse(base.contentEquals(
            deriver.derive("pw".toCharArray(), salt, params.copy(iterations = params.iterations + 1))))
        assertFalse(base.contentEquals(
            deriver.derive("pw".toCharArray(), salt, params.copy(parallelism = params.parallelism + 1))))
    }

    /**
     * 参数必须真的按 (t, m, p) 传进 Argon2，而不是传错位置。
     * 「两次派生结果不同」抓不住 t/p 互换——互换后 (t=1,p=2) 与 (t=2,p=1) 依然互不相等。
     * 所以这里用**独立的第二实现**（BouncyCastle 纯 Java）算出标准答案再比对，
     * 参数取不对称的 t=2 / p=3：换位置会让两个实现当场分家。
     * 这是 Task 10 跨平台 fixture 的先行版（那里用生产参数 + 提交进仓库的密文）。
     */
    @Test
    fun `parameter order matches an independent implementation`() {
        val asymmetric = KdfParams(memoryKib = 8 * 1024, iterations = 2, parallelism = 3)
        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        generator.init(
            org.bouncycastle.crypto.params.Argon2Parameters.Builder(
                org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id,
            )
                .withSalt(salt)
                .withIterations(asymmetric.iterations)
                .withMemoryAsKB(asymmetric.memoryKib)
                .withParallelism(asymmetric.parallelism)
                .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
                .build(),
        )
        val expected = ByteArray(32)
        generator.generateBytes("pw".toByteArray(Charsets.UTF_8), expected)
        assertContentEquals(expected, deriver.derive("pw".toCharArray(), salt, asymmetric))
    }

    /** spec: vault-crypto「NFC 与 NFD 形式的同一密码产出同一密钥」。 */
    @Test
    fun `nfc and nfd forms of the same password derive the same key`() {
        val nfc = "é"            // é 预组合
        val nfd = "e\u0301"           // e + combining acute
        assertContentEquals(
            deriver.derive(("caf" + nfc).toCharArray(), salt, params),
            deriver.derive(("caf" + nfd).toCharArray(), salt, params),
        )
    }

    /**
     * spec 的「含 emoji 的密码」场景：真正成立的性质是
     * **带 emoji 的密码里，可组合的部分仍然会被规范化**。
     * 注意 emoji 本身没有等价分解——`🔒` 与带上 VS16 的那个是两个不同的密码，
     * 不该被当成同一把，所以这条测试不去断言它们相等。
     */
    @Test
    fun `password containing an emoji and combining marks normalizes consistently`() {
        val composed = "🔒" + "café"          // emoji + 预组合 é
        val decomposed = "🔒" + "café"       // 同一串的 NFD 形式
        assertContentEquals(
            deriver.derive(composed.toCharArray(), salt, params),
            deriver.derive(decomposed.toCharArray(), salt, params),
        )
    }

    @Test
    fun `emoji passes through normalization unchanged`() {
        assertEquals("🔒", PasswordNormalizer.nfc("🔒"))
    }

    /**
     * 规范化必须由共享入口做，不依赖调用方自觉。
     * 用 spy 实现直接观察 KDF **收到**的密码；断言「两次调用结果相等」近乎同义反复。
     */
    @Test
    fun `normalization is applied inside the shared entry point`() {
        val received = mutableListOf<String>()
        val spy = object : KeyDerivation {
            override fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
                received += String(password)
                return ByteArray(32)
            }
        }
        // 必须喂 **NFD** 形式。若喂进去的本来就是 NFC，断言两边恒等，
        // 这条测试就抓不到「MasterKeyDeriver 忘了规范化」—— 而它守的正是这件事。
        val nfd = "e\u0301"                 // e + combining acute，即 NFD（用转义，不用裸组合字符）
        assertTrue(nfd != PasswordNormalizer.nfc(nfd), "测试输入本身必须是非 NFC 形式")
        MasterKeyDeriver(spy).derive(nfd.toCharArray(), salt, params)
        assertEquals(listOf(PasswordNormalizer.nfc(nfd)), received)
    }

    @Test
    fun `kdf params encode and decode round trip`() {
        val original = KdfParams(memoryKib = 65_536, iterations = 3, parallelism = 1)
        assertContentEquals(original.encode(), original.encode())
        assertEquals(12, original.encode().size)
        assertEquals(original, KdfParams.decode(original.encode()))
    }

    @Test
    fun `random source is injectable and reproducible`() {
        val a = ByteArray(32).also { DeterministicRandomSource(7).nextBytes(it) }
        val b = ByteArray(32).also { DeterministicRandomSource(7).nextBytes(it) }
        assertContentEquals(a, b)
    }

    /** 生产参数只跑一次作为基准，默认关闭（-Pbenchmark=true 时才跑）。 */
    @Test
    fun `production parameter benchmark`() {
        if (System.getProperty("avault.benchmark") != "true") return
        val started = System.nanoTime()
        deriver.derive("benchmark".toCharArray(), salt, KdfParams.PRODUCTION)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("Argon2id m=64MiB t=3 p=1 用时 ${elapsedMs}ms")
        assertTrue(elapsedMs < 10_000, "生产参数耗时 ${elapsedMs}ms，需要按 T21 重新调参")
    }
}
