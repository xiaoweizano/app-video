package com.example.avideo.playback

import android.content.Context

/**
 * 「上次看到哪」的持久化。
 *
 * 只存 `uuid → 毫秒`，**不存显示名、不存路径** —— 和 [com.example.avideo.importing.NotHiddenStore]
 * 同一条理由：把文件名写进明文，等于把「保险箱里有什么」泄漏出去。
 *
 * 位置：App 私有 SharedPreferences，第三方不可读，卸载即删。
 */
object PlaybackPositionStore {

    private const val PREFS = "playback_positions"

    /** 播放进度存到哪：条目 uuid。 */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, uuid: String): Long = prefs(context).getLong(uuid, 0L)

    fun put(context: Context, uuid: String, positionMs: Long) {
        if (positionMs <= 0L) return
        prefs(context).edit().putLong(uuid, positionMs).apply()
    }

    /** 播完、删除条目、从头重播时清掉。 */
    fun clear(context: Context, uuid: String) {
        prefs(context).edit().remove(uuid).apply()
    }
}
