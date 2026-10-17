package com.example.avideo.ui.screens.vaultlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.avideo.BuildConfig
import com.example.avideo.crypto.VaultGateway
import com.example.avideo.importing.NotHiddenStore
import com.example.avideo.ui.SkeletonBadge
import com.example.avideo.ui.theme.*

/**
 * 列表页 — 保险箱视频列表
 *
 * 列表数据是**真实的**：`VaultGateway.entries()` 枚举 `data/<uuid>.bin`、逐个解 metadata 区
 * （不解任何数据块）。主密钥从 `KeyRepository` 取，取不到就拒绝显示并要求重新解锁。
 *
 * 仍未接的部分：导入（stage-1）、播放（stage-2）、删除（stage-1）。它们都在界面上如实标注，
 * 不做「点了没反应」的假动作。
 *
 * DESIGN.md §2: 单一 FAB（导入），Top bar 承载导出/诊断
 * DESIGN.md §4: 状态徽章用 1dp 细线，不用色块填充
 * DESIGN.md §6: 无缩略图，用显示名+时长+状态承载信息
 */
data class VaultRow(
    val id: String,
    val displayName: String,
    val durationMs: Long?,
    val status: EntryStatus
)

enum class EntryStatus {
    HIDDEN,     // 已藏好
    NOT_HIDDEN, // 尚未隐藏（源文件还在）—— stage-1 之后才会出现
    FAILED      // 读不出来
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    /** 解锁时密码是否被真实文件**证明**过（空仓库无法校验，见 vault-unlock spec）。 */
    passwordVerified: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDiagnostics: () -> Unit,
    onPlay: (String) -> Unit,
    onRequireUnlock: () -> Unit,
    /** debug 专用：诊断探针。release 构建里这个按钮不渲染（也就不需要能到那个目的地）。 */
    onDebugProbe: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteNotice by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    // null = 未解锁（KeyRepository 里没有主密钥），空列表 = 仓库为空，两者必须分开呈现
    var rows by remember { mutableStateOf<List<VaultRow>?>(emptyList()) }

    LaunchedEffect(Unit) {
        isLoading = true
        // 「尚未隐藏」是持久化的状态（spec: source-cleanup），与仓库里的密文无关：
        // 密文写成功了，但源文件没删掉/没验证通过。
        val notHidden = NotHiddenStore.uuids(context)
        rows = VaultGateway.entries(context)?.map { item ->
            VaultRow(
                id = item.uuid,
                // 条目损坏时显示名解不出来。uuid 是结构性信息（不含原文件名），可以显示。
                displayName = item.displayName ?: "无法读取的条目 ${item.uuid.take(8)}",
                durationMs = item.durationMs,
                status = when {
                    item.corrupted -> EntryStatus.FAILED
                    item.uuid in notHidden -> EntryStatus.NOT_HIDDEN
                    else -> EntryStatus.HIDDEN
                },
            )
        }
        isLoading = false
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("保险箱") },
                actions = {
                    // 主动锁定：清掉主密钥并回解锁页。同时也是测试「超时锁定」这条路径的入口
                    // （否则要真的等 5 分钟后台）。
                    IconButton(onClick = {
                        VaultGateway.lock()
                        onRequireUnlock()
                    }) {
                        Icon(Icons.Default.Lock, contentDescription = "锁定")
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "导出")
                    }
                    IconButton(onClick = onDiagnostics) {
                        Icon(Icons.Default.Info, contentDescription = "诊断")
                    }
                    // 探针入口也要有：解锁后用户就在这一页，回不到解锁页底部的那个入口。
                    // D 自检（写入合成条目）必须先解锁，所以它只能挂在这里。
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = onDebugProbe) {
                            Icon(Icons.Default.BugReport, contentDescription = "诊断探针（debug）")
                        }
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
        val loaded = rows
        when {
            isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            loaded == null -> LockedState(
                modifier = Modifier.padding(padding),
                onUnlock = onRequireUnlock,
            )

            loaded.isEmpty() -> EmptyVaultState(
                modifier = Modifier.padding(padding),
                passwordVerified = passwordVerified,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SkeletonBadge(
                        text = "下面是真实的仓库内容（枚举 data/ 得来），导入已接通。" +
                            "播放（stage-2）、删除、导出尚未接通。"
                    )
                }
                items(loaded, key = { it.id }) { row ->
                    VaultRowItem(
                        row = row,
                        onPlay = { onPlay(row.id) },
                        onDelete = { showDeleteNotice = row.id }
                    )
                }
            }
        }
    }

    // 删除尚未实现。这里刻意不显示「确定删除」的按钮 —— 一个点了什么都不会发生的确认框，
    // 比没有删除功能更糟。但对「尚未隐藏」的条目，这里要给的是**真正的**指引。
    showDeleteNotice?.let { id ->
        val notHidden = rows?.firstOrNull { it.id == id }?.status == EntryStatus.NOT_HIDDEN
        AlertDialog(
            onDismissRequest = { showDeleteNotice = null },
            title = { Text(if (notHidden) "原文件还没确认删掉" else "删除还没接通") },
            text = {
                if (notHidden) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "这个条目的密文已经写进保险箱了，但原文件没能删除或没能验证，" +
                                "所以它可能仍然出现在相册或文件管理器里。"
                        )
                        Text(
                            "请在相册 / 文件管理里手动删除它。删掉之后这里的状态不会自动更新 —— " +
                                "v1 不记录原文件的位置（记下来就等于把文件名写进明文）。"
                        )
                    }
                } else {
                    Text(
                        "删除会在导入/播放之后再做（要先确定「删除后不可恢复」在密文文件上意味着什么）。" +
                            "现在点它不会有任何效果，你的文件没有被动过。"
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeleteNotice = null }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun VaultRowItem(
    row: VaultRow,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val statusColor = when (row.status) {
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
                text = row.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                // 时长未知时不编一个 0:00 出来（duration_ms 的哨兵值就是「未知」）
                Text(
                    text = row.durationMs?.let(::formatDuration) ?: "时长未知",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.W400
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = row.status.label,
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
        EntryStatus.FAILED -> "读不出来"
    }

/**
 * 未解锁：`KeyRepository.getMasterKey()` 返回 null。spec: key-repository
 * 「getMasterKey() 返回 null 时，调用方 MUST 拒绝继续操作并提示未解锁」——
 * 所以这里既不显示任何条目，也不尝试解密。
 */
@Composable
private fun LockedState(modifier: Modifier = Modifier, onUnlock: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "未解锁",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "主密钥已从内存中清除（可能是切到后台超过 5 分钟，或你刚点了锁定）。" +
                "重新输入密码即可继续。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onUnlock,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(2.dp),
        ) {
            Text("去解锁")
        }
    }
}

@Composable
private fun EmptyVaultState(modifier: Modifier = Modifier, passwordVerified: Boolean) {
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
        val points = buildList {
            add("视频加密后存于 App 私有目录，其他应用无法读取。")
            add("密钥由你的密码派生，不绑定设备，换机仍可打开。")
            add("点击下方「+」导入第一个视频开始使用。")
            // spec: vault-unlock「空仓库的解锁不校验密码」——MUST NOT 声称密码已验证。
            if (!passwordVerified) {
                add("注意：仓库里还没有文件，所以刚才输入的密码没有被任何文件校验过。请确保你记住了它。")
            }
        }
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
