package com.example.avideo.ui.screens.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.avideo.crypto.VaultGateway
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断页。
 *
 * 两条硬约束来自 spec：
 *  1. **不需要解锁**就能看/导出 —— 仓库打不开时恰恰是最需要它的时候。所以入口也在解锁页。
 *  2. 报告里**不含**密码、主密钥、data key、明文文件名、密文字节；只出现 uuid 与结构性信息。
 *
 * 它能直接支撑「密码错 vs 仓库损坏」的排查：两份边车各自的校验状态、每个文件的头部分类
 * （已提交 / 未提交 / 结构损坏）。若 10 个文件里 3 个坏、7 个正常，人工就能判断
 * 「密码很可能是对的，是文件坏了」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        report = runCatching { VaultGateway.diagnosticsReport(context) }
            .getOrElse { "诊断失败：${it.javaClass.simpleName}" }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = report
        if (uri == null) {
            message = "已取消"
            return@rememberLauncherForActivityResult
        }
        if (text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { sink ->
                    sink.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("打不开目标文件")
            }
            message = if (ok.isSuccess) "诊断信息已导出" else "导出失败：${ok.exceptionOrNull()?.javaClass?.simpleName}"
        }
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
                },
                actions = {
                    TextButton(onClick = { tick++ }) { Text("刷新") }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "这份报告不含密码、密钥或文件名，可以直接发给别人排查。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            FilledTonalButton(
                onClick = {
                    val name = "avault-diagnostics-" +
                        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
                    exporter.launch(name)
                },
                enabled = report != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出诊断信息（选择保存位置）") }

            HorizontalDivider()

            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = report ?: "正在生成…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
