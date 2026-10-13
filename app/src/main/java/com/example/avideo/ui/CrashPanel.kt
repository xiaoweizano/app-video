package com.example.avideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 上次崩溃的堆栈（**仅 debug**）。由 MainActivity 在存在 crash.log 时替代正常界面显示。
 *
 * 存在的理由：本机没有 SDK / adb，屏幕是唯一能读到堆栈的地方。截图即可。
 */
@Composable
fun CrashPanel(
    text: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A0000))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "上次崩溃（debug 显示）",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFFF6B6B)
        )
        Text(
            text = "这一份同时已导出到「文件管理 → 下载」目录，文件名 avideo-crash-<时间戳>.txt，" +
                "可以直接把那个文件发出来（比截图可靠）。下面的文字也可以长按选中复制。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBBBBBB)
        )
        SelectionContainer {
            Text(
                // 截断显示：堆栈可能很长，屏幕上只保留前 6000 字符（足够看到首因与顶层帧）
                text = text.take(6000),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFFE0E0E0)
            )
        }
        Button(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
            Text("清除并继续")
        }
    }
}
