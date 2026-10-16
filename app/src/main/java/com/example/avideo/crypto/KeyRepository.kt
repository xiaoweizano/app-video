package com.example.avideo.crypto

import java.util.Arrays

/**
 * 主密钥在进程内的唯一持有者（规格见 `openspec/changes/key-repository/spec.md`）。
 *
 * 为什么需要一个单例而不是各页面自己存：
 * - 解锁（stage-3）、导入（stage-1）、播放桥（stage-2）都要用主密钥，
 *   各存一份就会出现「一处清了、另一处还留着」；
 * - 规格明确 MUST NOT 在 Activity/ViewModel/Service 里各自持有副本。
 *
 * 生命周期 = **进程存活**。进程死了密钥自然没了（这是设计要的：
 * 密钥由密码派生，不落盘、不进 Keystore —— 用户会换 iPhone，设备绑定就等于换机即废）。
 * 后台超过 5 分钟由 [com.example.avideo.MainActivity] 调用 [clear]（Q13）。
 *
 * **这里不存 data key**：每把 data key 由对应的 reader 实例持有并在关闭时清零（`:core` 已实现）。
 */
object KeyRepository {

    /** 与 `:core` 的 `ContainerConstants.KEYS_LEN` 一致。 */
    private const val MASTER_KEY_LEN = 32

    private var masterKey: ByteArray? = null

    /** 存入前 `copyOf()`：调用方随后改自己那份数组不会影响这里。 */
    @Synchronized
    fun setMasterKey(key: ByteArray) {
        require(key.size == MASTER_KEY_LEN) { "master key must be $MASTER_KEY_LEN bytes" }
        wipeLocked()
        masterKey = key.copyOf()
    }

    /** 返回**副本**：调用方改返回值不会影响这里。未解锁时返回 null。 */
    @Synchronized
    fun getMasterKey(): ByteArray? = masterKey?.copyOf()

    /** 同步、立即生效；不清零就要等 GC，而 GC 不保证擦除。 */
    @Synchronized
    fun clear() = wipeLocked()

    @Synchronized
    fun isUnlocked(): Boolean = masterKey != null

    private fun wipeLocked() {
        masterKey?.let { Arrays.fill(it, 0) }
        masterKey = null
    }
}
