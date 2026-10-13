package com.example.avideo.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.avideo.ui.theme.success
import com.example.avideo.ui.theme.warning

/**
 * 诊断页 — 不泄露敏感信息
 *
 * DESIGN.md §7:
 * - 只读展示，不含密码/密钥/明文文件名
 * - 显示：仓库版本、文件数、头校验结果、KDF 参数、vault.meta 与 .bak 一致性
 */
data class DiagnosticItem(
    val label: String,
    val value: String,
    val status: DiagnosticStatus = DiagnosticStatus.Ok
)

enum class DiagnosticStatus { Ok, Warning, Error }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // TODO: 从真实诊断服务读取
    val items = remember {
        listOf(
            DiagnosticItem("仓库版本", "v3", DiagnosticStatus.Ok),
            DiagnosticItem("文件数量", "3", DiagnosticStatus.Ok),
            DiagnosticItem("vault.meta 校验", "通过", DiagnosticStatus.Ok),
            DiagnosticItem("vault.meta.bak", "存在且一致", DiagnosticStatus.Ok),
            DiagnosticItem("KDF 算法", "Argon2id", DiagnosticStatus.Ok),
            DiagnosticItem("KDF 参数", "m=64MiB t=3 p=1", DiagnosticStatus.Ok),
            DiagnosticItem("文件 #1 头校验", "通过", DiagnosticStatus.Ok),
            DiagnosticItem("文件 #2 头校验", "通过", DiagnosticStatus.Ok),
            DiagnosticItem("文件 #3 头校验", "失败", DiagnosticStatus.Error),
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                DiagnosticRow(item = item)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    item: DiagnosticItem,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val statusColor = when (item.status) {
        DiagnosticStatus.Ok -> colorScheme.success
        DiagnosticStatus.Warning -> colorScheme.warning
        DiagnosticStatus.Error -> colorScheme.error
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 状态指示线
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(24.dp)
                .background(statusColor)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
        }
    }
}
