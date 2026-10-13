package com.example.avideo.ui.screens.importflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.avideo.ui.theme.success
import com.example.avideo.ui.theme.warning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 导入流程 — 两阶段：确认 → 云端警告（可选）→ 进度 → 结果
 *
 * DESIGN.md §6:
 * - 重复导入：对话框三选一（跳过/替换/保留两份）
 * - 云端警告：默认弹出，一次 dismiss 不永久记住
 * - 进度条反映真实字节数，不转圈
 * - 结果：已藏好（绿）/ 尚未隐藏（橙+明确文案）/ 失败（红）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFlowScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stage by remember { mutableStateOf<ImportStage>(ImportStage.Confirm) }
    var progress by remember { mutableStateOf(0f) }
    var showCloudWarning by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    val coroutineScope = rememberCoroutineScope()

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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (stage) {
                ImportStage.Confirm -> {
                    ConfirmCard(
                        onSkip = { stage = ImportStage.Done; importResult = ImportResult.Success },
                        onKeepBoth = { stage = ImportStage.Done; importResult = ImportResult.Success },
                        onReplace = { showCloudWarning = true }
                    )
                }

                ImportStage.Processing -> {
                    ProcessingCard(progress = progress)
                    LaunchedEffect(progress) {
                        if (progress < 1.0f) {
                            delay(200L)
                            progress = (progress + 0.05f).coerceAtMost(1.0f)
                            if (progress >= 1.0f) {
                                // 模拟：90% 概率成功，10% 概率"尚未隐藏"
                                importResult = if (Math.random() > 0.1) ImportResult.Success
                                else ImportResult.NotHidden
                                stage = ImportStage.Done
                            }
                        }
                    }
                }

                ImportStage.Done -> {
                    importResult?.let { result ->
                        when (result) {
                            ImportResult.Success -> SuccessState()
                            ImportResult.NotHidden -> NotHiddenState(
                                onRetry = {
                                    stage = ImportStage.Processing
                                    progress = 0f
                                },
                                onGuide = {}
                            )
                            ImportResult.Failed -> FailedState(
                                onRetry = {
                                    stage = ImportStage.Processing
                                    progress = 0f
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("完成")
                    }
                }
            }
        }
    }

    if (showCloudWarning) {
        CloudWarningDialog(
            onDismiss = { showCloudWarning = false },
            onConfirm = {
                showCloudWarning = false
                stage = ImportStage.Processing
                progress = 0f
            }
        )
    }
}

// ─── Sub-composables ────────────────────────────────────────────────

@Composable
private fun ConfirmCard(
    onSkip: () -> Unit,
    onKeepBoth: () -> Unit,
    onReplace: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "检测到重复文件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "文件中存在相同的 SHA-256 哈希值，请选择处理方式：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                    Text("跳过")
                }
                FilledTonalButton(onClick = onKeepBoth, modifier = Modifier.weight(1f)) {
                    Text("保留两份")
                }
                Button(
                    onClick = onReplace,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("替换")
                }
            }
        }
    }
}

@Composable
private fun ProcessingCard(progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "正在加密写入...",
            style = MaterialTheme.typography.titleMedium
        )
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuccessState() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.success.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已藏好", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.success)
            Text(
                "视频已加密保存，源文件已删除。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun NotHiddenState(onRetry: () -> Unit, onGuide: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.warning.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("尚未隐藏", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.warning)
            Text(
                "原文件仍可能被相册或文件管理器找到。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("重试删除") }
                OutlinedButton(onClick = onGuide, modifier = Modifier.weight(1f)) { Text("手动删除指引") }
            }
        }
    }
}

@Composable
private fun FailedState(onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("写入失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(
                "加密写入过程中发生错误，视频未保存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
        }
    }
}

@Composable
private fun CloudWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.warning
            )
        },
        title = { Text("云端备份警告") },
        text = {
            Text(
                "检测到该文件可能已被云端备份（如 Google Photos / 小米云 / 华为云）。" +
                "删除本地文件不会撤销云端备份，建议检查云端设置。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("继续导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private enum class ImportStage { Confirm, Processing, Done }
private enum class ImportResult { Success, NotHidden, Failed }
