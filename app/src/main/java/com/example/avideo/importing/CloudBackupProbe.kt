package com.example.avideo.importing

import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * 云端备份的**尽力而为**探测（spec: cloud-backup-warning）。
 *
 * Android 没有可靠接口能问「这个文件是否已上传到某个云」。所以这里只有三态，
 * 而且措辞必须让「没有探测到证据」与「无法判断」分得开：
 *
 *  - [POSSIBLY_BACKED_UP]：manifest 里出现了疑似云/备份/同步字段且值非零
 *  - [NO_EVIDENCE]：查到了 manifest，里面**没有**任何这类字段
 *  - [UNKNOWN]：查不到 manifest、权限不足、provider 抛异常
 *
 * **「没有探测到证据」不等于「没备份」** —— UI MUST NOT 把它表述成「未备份」。
 * 也 MUST NOT 因为探测不到就跳过警告：本 App 的通知里的警告默认总是出现。
 */
enum class CloudState {
    POSSIBLY_BACKED_UP,
    NO_EVIDENCE,
    UNKNOWN,
}

object CloudBackupProbe {

    /** 字段名里出现这些词就当作「与云/备份/同步有关」的线索（大小写不敏感）。 */
    private val HINTS = listOf("cloud", "backup", "sync", "upload")

    /**
     * 表达式体、**没有任何 return**：Kotlin 不允许在表达式体函数里写 return，
     * 而这里的分支多，写成「值表达式」比块体更不容易出错。
     */
    fun probe(context: Context, uri: Uri): CloudState = try {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        if (cursor == null) {
            CloudState.UNKNOWN
        } else {
            cursor.use { c ->
                val cloudColumns = c.columnNames
                    .filter { name -> HINTS.any { hint -> name.contains(hint, ignoreCase = true) } }
                when {
                    cloudColumns.isEmpty() -> CloudState.NO_EVIDENCE
                    !c.moveToFirst() -> CloudState.NO_EVIDENCE
                    else -> {
                        val flagged = cloudColumns.any { name ->
                            val index = c.getColumnIndex(name)
                            if (index < 0) {
                                false
                            } else {
                                when (c.getType(index)) {
                                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(index) != 0L
                                    Cursor.FIELD_TYPE_STRING -> !c.getString(index).isNullOrBlank()
                                    // 其它类型（blob/null）不当作证据
                                    else -> false
                                }
                            }
                        }
                        if (flagged) CloudState.POSSIBLY_BACKED_UP else CloudState.NO_EVIDENCE
                    }
                }
            }
        }
    } catch (e: Exception) {
        // 权限不足、provider 不支持、URI 失效：都是「无法判断」，不影响导入
        CloudState.UNKNOWN
    }

    /** 一批文件的汇总，用于警告卡片。空列表 = 还没选文件。 */
    fun summarize(states: List<CloudState>): String? {
        if (states.isEmpty()) return null
        val backedUp = states.count { it == CloudState.POSSIBLY_BACKED_UP }
        val noEvidence = states.count { it == CloudState.NO_EVIDENCE }
        val unknown = states.count { it == CloudState.UNKNOWN }
        val parts = buildList {
            if (backedUp > 0) add("$backedUp 个文件带有云/备份相关标记")
            if (noEvidence > 0) add("$noEvidence 个文件没有探测到云端标记（这不等于没备份）")
            if (unknown > 0) add("$unknown 个文件的云端状态无法判断")
        }
        return parts.joinToString("；") + "。"
    }
}
