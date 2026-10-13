package com.example.avideo.ui.screens.vaultlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.avideo.ui.SkeletonBadge
import com.example.avideo.ui.theme.*
import kotlinx.coroutines.delay

/**
 * 列表页 — 保险箱视频列表
 *
 * DESIGN.md §2: 单一 FAB（导入），Top bar 承载导出/诊断
 * DESIGN.md §4: 状态徽章用 1dp 细线，不用色块填充
 * DESIGN.md §6: 无缩略图，用显示名+时长+状态承载信息
 */
data class VaultEntry(
    val id: String,
    val displayName: String,
    val durationMs: Long,
    val status: EntryStatus
)

enum class EntryStatus {
    HIDDEN,    // 已藏好
    NOT_HIDDEN, // 尚未隐藏
    FAILED     // 写入失败
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDiagnostics: () -> Unit,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        isLoading = true
        // TODO: 从真实vault数据源加载
        delay(300L)
        entries = listOf(
            VaultEntry("1", " vacation_2024.mp4", 3600000L, EntryStatus.HIDDEN),
            VaultEntry("2", " family_recording.mkv", 120000L, EntryStatus.NOT_HIDDEN),
            VaultEntry("3", " private_clip.avi", 90000L, EntryStatus.HIDDEN),
        )
        isLoading = false
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("保险箱") },
                actions = {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "导出")
                    }
                    IconButton(onClick = onDiagnostics) {
                        Icon(Icons.Default.Info, contentDescription = "诊断")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onImport,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "导入视频")
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (entries.isEmpty()) {
            EmptyVaultState(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 标注：下面这三条是硬编码的示例数据。实测装上真机后确实会问「这几个视频是哪来的」，
                // 所以把话说在界面上，而不是只写在代码注释里。
                item {
                    SkeletonBadge(text = "骨架 · 下面是硬编码的示例数据，不是你的视频（真实数据源未接）")
                }
                items(entries, key = { it.id }) { entry ->
                    VaultEntryRow(
                        entry = entry,
                        onPlay = { onPlay(entry.id) },
                        onDelete = { showDeleteDialog = entry.id }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    showDeleteDialog?.let { deleteId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除视频") },
            text = { Text("确定要删除此视频吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = null
                    // TODO: 删除逻辑
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun VaultEntryRow(
    entry: VaultEntry,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val statusColor = when (entry.status) {
        EntryStatus.HIDDEN -> colorScheme.success
        EntryStatus.NOT_HIDDEN -> colorScheme.warning
        EntryStatus.FAILED -> colorScheme.error
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态指示线：1dp 垂直线，不用色块（DESIGN.md §1：禁用卡片）
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(48.dp)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName.trimStart(),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatDuration(entry.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.W400
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 状态标签：1dp 细线下划线样式
                Text(
                    text = entry.status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 删除按钮（独立触控目标 ≥ 48dp）
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) {
        String.format("%d:%02d", hours, minutes)
    } else {
        String.format("%d:%02d", minutes, totalSeconds % 60)
    }
}

private val EntryStatus.label: String
    get() = when (this) {
        EntryStatus.HIDDEN -> "已藏好"
        EntryStatus.NOT_HIDDEN -> "尚未隐藏"
        EntryStatus.FAILED -> "写入失败"
    }

@Composable
private fun EmptyVaultState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 大字号 thesis 陈述
        Text(
            text = "你的视频，安全地藏着。",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))
        // 三句话解释
        val points = listOf(
            "视频加密后存于 App 私有目录，其他应用无法读取。",
            "密钥由你的密码派生，不绑定设备，换机仍可打开。",
            "点击下方「+」导入第一个视频开始使用。"
        )
        points.forEach { point ->
            Text(
                text = point,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
