package com.example.avideo.ui.screens.unlock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.avideo.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 解锁页 — 密码输入入口
 *
 * DESIGN.md §2: 键盘弹出时输入框与提交按钮随 IME inset 上移（adjustResize）
 * DESIGN.md §7: 区分「密码不对」与「仓库损坏」两条路径
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    onLocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorType by remember { mutableStateOf<ErrorType?>(null) } // null = no error
    var isLoading by remember { mutableStateOf(false) }
    var kdfProgress by remember { mutableStateOf(0f) } // 0~1 KDF进度
    val scope = rememberCoroutineScope()

    val colorScheme = MaterialTheme.colorScheme

    val keyboard = LocalSoftwareKeyboardController.current

    // 注意：Kotlin 的**局部函数必须先声明后使用**（不像成员函数那样能提前引用）。
    // 这个函数在下面的 Column 里被 onDone / onClick 调用，所以必须放在 Column 之前。
    // 原先它被写在文件末尾，编译器报 "Unresolved reference: attemptUnlock"。
    fun attemptUnlock() {
        if (password.length < 4) {
            errorMessage = "密码长度不足"
            return
        }
        // TODO: 接入真实 KDF 验证逻辑
        // 模拟：开始KDF等待 → 完成后解锁或报错
        isLoading = true
        kdfProgress = 0f
        scope.launch {
            // 模拟KDF耗时（真实场景是argon2id派生密钥）
            while (kdfProgress < 1.0f) {
                delay(80L)
                kdfProgress = (kdfProgress + 0.05f).coerceAtMost(1.0f)
            }
            if (password == "corrupt") {
                errorType = ErrorType.VAULT_CORRUPTED
                errorMessage = null
            } else {
                onLocked()
            }
            isLoading = false
            kdfProgress = 0f
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .imePadding(), // DESIGN.md §2: 键盘弹出时输入框随IME上移
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 标题：无装饰，直接陈述
        Text(
            text = "输入密码",
            style = MaterialTheme.typography.titleLarge,
            color = colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 密码输入框
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null
                errorType = null
            },
            label = { Text("密码") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { attemptUnlock() }
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(2.dp),
            supportingText = {
                errorMessage?.let {
                    Text(it, color = colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword)
                            androidx.compose.material.icons.Icons.Filled.Visibility
                        else
                            androidx.compose.material.icons.Icons.Filled.VisibilityOff,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 提交按钮 — 主操作，用红色（DESIGN.md §2：error 色承载唯一主操作）
        Button(
            onClick = { attemptUnlock() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(2.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error),
            enabled = password.isNotEmpty() && !isLoading
        ) {
            if (isLoading) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("验证中 ${"%.0f".format(kdfProgress * 100)}%", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Text("解锁", style = MaterialTheme.typography.labelLarge)
            }
        }

        // KDF等待进度条（ARGON2ID耗时1-3秒，必须给用户反馈）
        if (isLoading) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = kdfProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
                color = colorScheme.primary,
                trackColor = colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "正在派生密钥，请稍候...",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )
        }

        // 密码错误提示（两种错误类型视觉区分）
        if (errorType == ErrorType.WRONG_PASSWORD) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.error.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "密码不对",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "请检查密码是否正确后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onError.copy(alpha = 0.7f)
                    )
                }
            }
        } else if (errorType == ErrorType.VAULT_CORRUPTED) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.error.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "仓库可能损坏",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "密钥材料校验失败，数据可能已损坏。请进入诊断页查看更多信息。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onError.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

}

private enum class ErrorType { WRONG_PASSWORD, VAULT_CORRUPTED }