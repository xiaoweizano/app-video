package com.example.avideo.importing

import android.content.Context

/**
 * 「尚未隐藏」状态的持久化（spec: source-cleanup「尚未隐藏是持久化的一等状态」）。
 *
 * **只存 UUID，不存文件名、不存 URI。** 存文件名等于把「那个没删掉的文件叫什么」写进明文；
 * 存 URI 更糟（它能把文件名一并带出来）。代价是：进程重启后没法再自动重试删除，
 * 只能给「请在相册/文件管理里手动删除」的指引 —— 这也是 spec 允许的路径。
 *
 * 位置：`SharedPreferences`，即 App 私有存储，第三方不可读。
 */
object NotHiddenStore {

    private const val PREFS = "import_not_hidden"
    private const val KEY = "uuids"

    fun mark(context: Context, uuid: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // getStringSet 返回的集合不得修改，必须取副本再写回
        val next = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        if (next.add(uuid)) prefs.edit().putStringSet(KEY, next).apply()
    }

    fun clear(context: Context, uuid: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        if (next.remove(uuid)) prefs.edit().putStringSet(KEY, next).apply()
    }

    fun uuids(context: Context): Set<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            ?.toSet()
            ?: emptySet()
}
