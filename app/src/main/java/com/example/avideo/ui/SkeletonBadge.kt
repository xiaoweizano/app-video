package com.example.avideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.avideo.ui.theme.warning

/**
 * 「这是骨架，不是真数据」的标注。
 *
 * 为什么要有它：六个屏幕都是更早的会话写的 UI 骨架 —— 列表里是硬编码的假条目、
 * 解锁是模拟的 KDF、播放器没有数据源。不标出来的话，自己回来看会以为功能已经能用
 * （实测就发生过：拿到真机安装后问「这三个视频是哪来的」）。
 *
 * 形态沿用 DESIGN.md 的状态语言：1dp 琥珀细线 + 小字，不用色块、不加装饰。
 */
@Composable
fun SkeletonBadge(
    text: String = "骨架 · 未接真实数据",
    modifier: Modifier = Modifier
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(12.dp)
                .background(MaterialTheme.colorScheme.warning)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
