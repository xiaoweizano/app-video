package com.example.avideo.ui.screens.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.avideo.crypto.VaultGateway
import com.example.avideo.crypto.VaultListItem
import com.example.avideo.ui.theme.warning
import kotlinx.coroutines.launch

/**
 * 导出：把一条密文**解密**写成普通文件，位置由用户在系统选择器里指定。
 *
 * 为什么要先有这个才敢往里放真东西：在此之前导入是**单向**的 —— 放进去只能在 App 里看，
 * 卸载就连密文一起没了。导出让「拿得出来」成为可能。
 *
 * 刻意写清楚的一点：导出的文件是**明文**，而且写在用户选择的位置（相册/下载/云盘都行）。
 * 本项目「磁盘上不留明文」约束的是 App 自己偷偷落盘，不是用户明确要求的导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<VaultListItem>?>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingUuid by remember { mutableStateOf<String?>(null) }
    var backingUp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        items = VaultGateway.entries(context)
        loaded = true
    }

    val backupCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) {
            message = "已取消备份，没有写出任何文件"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            backingUp = true
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { sink ->
                    VaultGateway.exportVaultArchive(context, sink)
                } ?: throw IllegalStateException("打不开目标文件")
            }
            backingUp = false
            message = result.fold(
                onSuccess = { report ->
                    "已备份 ${report.entries} 个文件（${formatBytes(report.bytes)}）" +
                        if (report.skippedUncommitted > 0) {
                            "；跳过 ${report.skippedUncommitted} 个未提交/损坏文件"
                        } else ""
                },
                onFailure = { "备份失败：${it.javaClass.simpleName}" },
            )
        }
    }

    val creator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/*")
    ) { uri ->
        val uuid = pendingUuid
        pendingUuid = null
        if (uri == null) {
            message = "已取消，没有写出任何文件"
            return@rememberLauncherForActivityResult
        }
        if (uuid == null) return@rememberLauncherForActivityResult
        scope.launch {
            progress = 0L to 0L
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { sink ->
                    VaultGateway.exportEntry(context, uuid, sink) { written, total ->
                        progress = written to total
                    }
                } ?: throw IllegalStateException("打不开目标文件")
            }
            progress = null
            message = result.fold(
                onSuccess = { "已导出 $it 字节" },
                onFailure = { "导出失败：${it.javaClass.simpleName}" },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("导出") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 备份整个仓库：脱离本机的唯一方式（内容是密文，不含密钥）
            FilledTonalButton(
                onClick = {
                    message = null
                    backupCreator.launch(
                        "vault-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                            .format(java.util.Date()) + ".avault.zip"
                    )
                },
                enabled = !backingUp && progress == null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    if (backingUp) "正在备份…" else "备份整个仓库（.avault.zip）"
                )
            }
            Text(
                "备份出来的是原样的密文：不含密钥，也只能用原来的密码解开。" +
                    "换手机/重装前先做这一份 —— 卸载 App 会连同仓库一起删除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.warning.copy(alpha = 0.12f)
                ),
            ) {
                Text(
                    "导出的文件是明文，会写到你选择的位置（相册 / 下载 / 云盘）。" +
                        "导出完成后它就和其他普通视频一样能被任何 App 看到 —— 这是你自己要的结果，" +
                        "但如果还想藏住它，记得导出后不要留在相册里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            val currentProgress = progress
            if (currentProgress != null) {
                val (written, total) = currentProgress
                LinearProgressIndicator(
                    progress = if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else 0f,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
                Text(
                    "正在解密写出：${formatBytes(written)} / ${formatBytes(total)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }

            when {
                !loaded -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                items == null -> Text(
                    "未解锁：主密钥已从内存中清除，请重新解锁后再导出。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                items!!.isEmpty() -> Text(
                    "保险箱里还没有条目。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items!!, key = { it.uuid }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.displayName ?: "无法读取的条目 ${item.uuid.take(8)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.corrupted) {
                                    Text(
                                        "条目损坏，无法导出",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            TextButton(
                                enabled = !item.corrupted && progress == null && !backingUp,
                                onClick = {
                                    pendingUuid = item.uuid
                                    message = null
                                    // 用户在这里选择「存到哪、叫什么」。默认名就是加密 metadata 里的显示名。
                                    creator.launch(item.displayName ?: "${item.uuid}.mp4")
                                },
                            ) { Text("导出") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1024L * 1024L) "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else if (bytes >= 1024L) "%.0f KB".format(bytes / 1024.0)
    else "$bytes B"
