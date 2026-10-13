package com.example.avideo.ui.screens.debug

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * debug 专用诊断页。两件事，都回答「这个项目该不该按现在的设计继续做」：
 *
 * **B · 私有目录可见性（T2，威胁模型门禁）**
 * 把一个**真实的视频**复制进 `getFilesDir()` 与 `getExternalFilesDir()`，
 * 然后由人去看系统图库 / 文件管理器 / 第三方文件管理器能不能找到它。
 * 找不到 —— 「真的藏得住」这一层成立；找得到 —— 整个 B 层威胁模型要重写。
 * 注意必须用**真实视频**：随便造一个 `.mp4` 后缀的文件，图库不索引它可能是因为
 * 「不是合法视频」而不是「目录私有」，那样结论是不成立的。
 *
 * **A · 视频格式普查（T0 尽调的事实部分）**
 * 统计媒体库里按扩展名的分布与占用。它决定 libVLC 那 41 MB 值不值得：
 * 冷门格式（rmvb/avi 等）占比很低的话，换 Media3/ExoPlayer 能把 APK 从 ~36 MB 降到 ~8 MB。
 *
 * 本页只在 debug 构建可达（入口在解锁页底部），且媒体读取权限只声明在
 * `app/src/debug/AndroidManifest.xml` —— release 不声明，因为正式版走 SAF，不需要读媒体库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugProbeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var output by remember { mutableStateOf("") }

    fun append(line: String) {
        output = output + line + "\n"
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) append("[B] 未选择文件（取消了）") else append(copyProbe(context, uri))
    }

    val mediaPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) append(surveyVideos(context)) else append("[A] 未授予媒体读取权限，无法普查")
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("诊断探针") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("B · 私有目录可见性（威胁模型门禁）", style = MaterialTheme.typography.titleSmall)
            Text(
                "选一个相册里的真实视频，App 会把它复制进两处内部目录。复制完请去检查三处：" +
                    "① 系统图库有没有多出新条目 ② 文件管理（含「显示隐藏文件」）能否看到下面的路径 " +
                    "③ 第三方文件管理器。三处都找不到 = 真的藏得住；任一处找得到 = 设计前提要重写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { pickVideo.launch(arrayOf("video/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("选择视频并复制进私有目录") }

            HorizontalDivider()

            Text("A · 视频格式普查（决定要不要 41 MB 的 libVLC）", style = MaterialTheme.typography.titleSmall)
            Text(
                "统计媒体库里按扩展名的分布。冷门格式（rmvb / avi 等）占比很低的话，" +
                    "换 Media3 能把 APK 从约 36 MB 降到约 8 MB。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { askPermission.launch(mediaPermission) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("统计媒体库里的格式分布") }

            HorizontalDivider()

            Text("输出", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    text = output.ifEmpty { "（还没有输出）" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun displayNameOf(context: Context, uri: Uri): String {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it }
            }
        }
    return "probe-${System.currentTimeMillis()}.mp4"
}

/**
 * 复制一份真实视频到两个内部目录。两个都测，是因为它们的可见性规则不同：
 *  `getFilesDir()` → /data/data/<pkg>/files/，第三方文件管理器通常完全够不着；
 *  `getExternalFilesDir()` → /sdcard/Android/data/<pkg>/files/，Android 11+ 对文件管理器隐藏，
 *  但媒体库扫描是否覆盖它，各 ROM 行为不同 —— 这正是要测的。
 */
private fun copyProbe(context: Context, uri: Uri): String {
    val name = displayNameOf(context, uri)
    val internal = File(File(context.filesDir, "probe").apply { mkdirs() }, name)
    val externalDir = context.getExternalFilesDir(null)
    val targets = buildList {
        add("内部私有目录 getFilesDir()" to internal)
        if (externalDir != null) {
            add("外部应用目录 getExternalFilesDir()" to File(File(externalDir, "probe").apply { mkdirs() }, name))
        }
    }

    val sb = StringBuilder("[B] 探针文件：$name\n")
    targets.forEach { (label, target) ->
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: error("打不开输入流")
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        }.onSuccess {
            sb.append("  ✓ $label\n      ${target.absolutePath}\n      ${target.length()} 字节\n")
        }.onFailure {
            sb.append("  ✗ $label 复制失败：${it::class.simpleName}: ${it.message}\n")
        }
    }
    sb.append("  接着去：系统图库找「$name」；文件管理里试着走到上面两个路径；再用第三方文件管理器试一次。\n")
    return sb.toString()
}

/** 按扩展名统计媒体库里的视频。冷门格式单独提示，因为它直接决定播放器选型。 */
private fun surveyVideos(context: Context): String {
    val coldFormats = setOf("rmvb", "rm", "avi", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "vob", "mov")
    val count = mutableMapOf<String, Int>()
    val bytes = mutableMapOf<String, Long>()
    var total = 0

    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE),
        null, null, null
    )?.use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameIndex) ?: continue
            val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
            val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { "(无扩展名)" }
            count[ext] = (count[ext] ?: 0) + 1
            bytes[ext] = (bytes[ext] ?: 0L) + size
            total++
        }
    }

    if (total == 0) return "[A] 媒体库里没有查到视频（可能没有授予权限，或库里确实没有）"

    val sb = StringBuilder("[A] 媒体库视频总数：$total\n")
    sb.append(String.format("    %-12s %6s %12s  %s%n", "扩展名", "数量", "占用", ""))
    count.entries.sortedByDescending { it.value }.forEach { (ext, n) ->
        val gb = (bytes[ext] ?: 0L) / 1024.0 / 1024 / 1024
        val mark = if (ext in coldFormats) "← 冷门（libVLC 才能播）" else ""
        sb.append(String.format("    %-12s %6d %9.2f GB  %s%n", ext, n, gb, mark))
    }
    val coldCount = count.filterKeys { it in coldFormats }.values.sum()
    val coldBytes = bytes.filterKeys { it in coldFormats }.values.sum()
    val totalBytes = bytes.values.sum().coerceAtLeast(1L)
    sb.append("\n    冷门格式：$coldCount/$total = ${"%.1f".format(coldCount * 100.0 / total)}%")
    sb.append("（占用 ${"%.1f".format(coldBytes * 100.0 / totalBytes)}%）\n")
    sb.append(
        when {
            coldCount == 0 -> "    → 一个冷门格式都没有：换 Media3 完全够用，APK 可从 ~36 MB 降到 ~8 MB。"
            coldCount * 100.0 / total < 5 -> "    → 冷门占比 <5%：值得考虑 Media3 为主 + 少数用 libVLC，或直接放弃这部分。"
            else -> "    → 冷门占比不低：libVLC 这 41 MB 是必须付的。"
        }
    )
    return sb.toString()
}
