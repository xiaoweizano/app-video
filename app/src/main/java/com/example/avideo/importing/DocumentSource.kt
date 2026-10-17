package com.example.avideo.importing

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.avideo.core.importing.CleanupResult
import com.avideo.core.importing.ImportSource
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 用 SAF 文档 URI 作为导入源。
 *
 * 拿到的必须已经是 `ACTION_OPEN_DOCUMENT` 的文档 URI（见 `ImportFlowScreen`）：
 * `ACTION_PICK` / `ACTION_GET_CONTENT` 返回的可能是相册**转码后的副本**，既掉画质，
 * 又让 AVI / RMVB 这类格式根本选不出来（spec: media-source）。
 */
class DocumentSource(
    context: Context,
    /** 源 URI。只在内存里用（持久化状态 MUST NOT 存它，那会把文件名带出来）。 */
    val uri: Uri,
) : ImportSource {

    // 只留 applicationContext：这个对象可能活到 Activity 被销毁之后（导入在服务里跑）。
    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    override val displayName: String by lazy {
        columnString(OpenableColumns.DISPLAY_NAME) ?: "未命名视频"
    }

    override val statedSize: Long? by lazy {
        // provider 不给或给 -1 都算未知。写入的长度一律以实读为准，这里只是提示。
        columnString(OpenableColumns.SIZE)?.toLongOrNull()?.takeIf { it >= 0 }
    }

    /** SAF 的显示名差不多可以当 label 用；它已经是 provider 给的名字，不含路径。 */
    override val label: String get() = displayName

    override fun open(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("provider 没有返回输入流")

    /**
     * 删除源文件并**独立验证**。
     *
     * 只用 `DocumentsContract.deleteDocument`（spec: source-cleanup「MUST NOT 依赖 URI 的写入权限
     * 或 File.delete() 的副作用」）。删除在这台设备上经常需要额外授权 —— 那是**预期分支**，
     * 不是异常：返回 [CleanupResult.NotHidden]，条目进「尚未隐藏」，界面给手动删除的指引。
     */
    override fun cleanup(): CleanupResult {
        val deleted = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: FileNotFoundException) {
            true                                              // 本来就不在了 = 删掉了
        } catch (e: SecurityException) {
            // 包括 RecoverableSecurityException（需要用户在系统弹窗里确认）
            return CleanupResult.NotHidden("没有删除权限（需要在系统弹窗里确认，或手动删除）")
        } catch (e: UnsupportedOperationException) {
            return CleanupResult.NotHidden("这个来源不支持程序删除")
        } catch (e: IllegalArgumentException) {
            return CleanupResult.NotHidden("这个来源不支持文档删除")
        }
        if (!deleted) return CleanupResult.NotHidden("删除 API 报告失败")

        // 验证 MUST 独立于删除 API 的返回值（spec: source-cleanup「删除结果必须验证」）。
        return if (stillReadable()) {
            CleanupResult.NotHidden("删除 API 报告成功，但文件仍可读")
        } else {
            CleanupResult.Hidden
        }
    }

    /** 重新打开该 URI 一次：还能打开就说明它还在。 */
    private fun stillReadable(): Boolean = try {
        val descriptor = resolver.openFileDescriptor(uri, "r")
        if (descriptor == null) {
            false
        } else {
            descriptor.close()
            true
        }
    } catch (e: FileNotFoundException) {
        false
    } catch (e: SecurityException) {
        // 读权限也没了 ⇒ 我们（以及同样没有权限的别人）读不到了
        false
    }

    private fun columnString(name: String): String? = runCatching {
        resolver.query(uri, arrayOf(name), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(name)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    /** 只给日志/断言用，不含路径。 */
    override fun toString(): String = "DocumentSource(scheme=${uri.scheme})"
}
