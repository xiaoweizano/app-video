package com.example.avideo.ui.screens.importflow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avideo.core.importing.DuplicateCandidate
import com.avideo.core.importing.DuplicateChoice
import com.example.avideo.importing.ImportController
import com.example.avideo.ui.theme.*

/**
 * 导入流程 —— 选文件 → （必要时）重复裁决 → 加密写入 → 逐条结果。
 *
 * 这一屏的每一条行为都来自 spec，不是随手加的：
 *  - 用 `ACTION_OPEN_DOCUMENT`（显式构造 Intent，见 [ImportFlowScreen] 里的 picker）：
 *    相册/文件管理器的 `ACTION_PICK` / `ACTION_GET_CONTENT` 可能返回转码后的副本，既掉画质，
 *    又让 AVI/RMVB 这类格式根本选不出来（spec: media-source）。
 *  - 空选择什么都不做、也不报错（spec: import-flow「空选择」）。
 *  - 云端备份警告**默认出现**，且措辞是「无法判断」而不是「未备份」
 *    （spec: cloud-backup-warning 三态里我们只敢说最保守的那一态）。
 *  - 重复项三选一，文案里写明「跳过会保留哪一条」（spec: duplicate-detection）。
 *  - 结果逐条显示：已藏好 / 尚未隐藏（原文件可能仍可被找到）/ 跳过 / 失败。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFlowScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by ImportController.state.collectAsState()

    // 显式构造 ACTION_OPEN_DOCUMENT，而不是用 ActivityResultContracts.OpenMultipleDocuments：
    // 后者不带 FLAG_GRANT_WRITE_URI_PERMISSION，于是 cleanup 里的 deleteDocument 几乎必然
    // 因缺少写权限失败 —— 每条导入都会落到「尚未隐藏」。写权限是「删掉原文件」的前提。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val clip = data.clipData
        val uris: List<Uri> = when {
            clip != null -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            data.data != null -> listOf(data.data!!)
            else -> emptyList()
        }
        // 空选择 MUST NOT 产生任何写盘动作，也不报错（spec: import-flow「空选择」）
        if (uris.isNotEmpty()) ImportController.prepare(context, uris)
    }

    fun launchPicker() {
        picker.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
        )
    }

    // 离开本页时把状态收回 Idle，否则下次进来会先看到上一次的结果。
    DisposableEffect(Unit) {
        onDispose { ImportController.finish() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("导入视频") },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CloudBackupWarning()

            when (val current = state) {
                ImportController.State.Idle -> {
                    Text(
                        "选中的文件会被加密写进保险箱，然后尝试删除原文件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { launchPicker() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("选择视频") }
                }

                is ImportController.State.Rejected -> {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { launchPicker() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重新选择") }
                }

                is ImportController.State.Preparing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在预检 ${current.total} 个文件（判重）…")
                    }
                    Text(
                        "只有大小与库内某条相同的文件才需要算一遍哈希，其余直接开始写。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ImportController.State.NeedsDedup -> {
                    Text(
                        "还有一个重复项要确认：${current.candidate.source.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ImportController.State.Running -> {
                    Text("正在加密写入…", style = MaterialTheme.typography.titleSmall)
                    current.rows.forEach { RowItem(it) }
                }

                is ImportController.State.Finished -> {
                    Text("导入结果", style = MaterialTheme.typography.titleSmall)
                    current.rows.forEach { RowItem(it) }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                }
            }
        }
    }

    (state as? ImportController.State.NeedsDedup)?.let { s ->
        DedupDialog(
            candidate = s.candidate,
            remaining = s.remaining,
            onChoose = { ImportController.chooseDuplicate(it) },
        )
    }
}

@Composable
private fun DedupDialog(
    candidate: DuplicateCandidate,
    remaining: Int,
    onChoose: (DuplicateChoice) -> Unit,
) {
    AlertDialog(
        // 必须选一个：点外面关掉会让整批导入卡在这个状态
        onDismissRequest = { },
        title = { Text("这个视频已经导入过") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "「${candidate.source.displayName}」与库里的「${candidate.match.existingName}」" +
                        "内容完全相同（按内容哈希判断，不是按文件名）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "跳过 = 保留库里的「${candidate.match.existingName}」，不导入这一份。\n" +
                        "替换 = 用这次的显示名替换掉它。\n" +
                        "保留两份 = 两条都留着（内容相同，占两份空间）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (remaining > 0) {
                    Text(
                        "后面还有 $remaining 个重复项要确认。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.warning,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(DuplicateChoice.KEEP_BOTH) }) { Text("保留两份") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onChoose(DuplicateChoice.SKIP) }) { Text("跳过") }
                TextButton(onClick = { onChoose(DuplicateChoice.REPLACE) }) { Text("替换") }
            }
        },
    )
}

@Composable
private fun RowItem(row: ImportController.Row) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (val st = row.state) {
            ImportController.RowState.Pending -> Text(
                "等待中",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )

            is ImportController.RowState.Writing -> {
                val total = st.statedSize
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = (st.bytesRead.toFloat() / total).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                    Text(
                        "${formatBytes(st.bytesRead)} / ${formatBytes(total)}（已写入，按实读字节计）",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 声明大小未知：给不确定态，不编一个百分比
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    Text(
                        "已写入 ${formatBytes(st.bytesRead)}（源未声明大小）",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }

            ImportController.RowState.Hidden -> Text(
                "已藏好 · 原文件已删除并验证",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.success,
            )

            is ImportController.RowState.NotHidden -> {
                Text(
                    "尚未隐藏 · 原文件可能仍能在相册或文件管理里找到",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.warning,
                )
                st.detail?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
                }
                Text(
                    "请在相册 / 文件管理里手动删除原文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            is ImportController.RowState.Skipped -> Text(
                st.reason,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )

            is ImportController.RowState.Failed -> Text(
                "失败：${st.reason}",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.error,
            )
        }
    }
}

@Composable
private fun CloudBackupWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.warning.copy(alpha = 0.12f)
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "云端备份提醒（无法判断）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.warning,
            )
            Text(
                "如果这个视频曾经上传到云相册（Google 相册 / 小米云 / 华为云…），" +
                    "删除本地文件不会撤销云端那一份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Android 没有可靠接口能查「某个文件是否已上传」，所以这里只能提醒，不能替你确认。" +
                    "导入后请自行到对应云相册里看一眼。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1024L * 1024L) "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else if (bytes >= 1024L) "%.0f KB".format(bytes / 1024.0)
    else "$bytes B"
