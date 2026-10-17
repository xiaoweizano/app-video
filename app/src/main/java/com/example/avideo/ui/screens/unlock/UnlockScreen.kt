package com.example.avideo.ui.screens.unlock

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.avideo.core.backup.VaultArchive
import com.example.avideo.BuildConfig
import com.example.avideo.crypto.UnlockOutcome
import com.example.avideo.crypto.VaultGateway
import com.example.avideo.crypto.VaultState
import com.example.avideo.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 解锁页 / 首次设置页 —— 同一屏的两个模式，按仓库是否存在切换。
 *
 * 这一屏现在**是真的**了（stage-0 的骨架版是假的）：
 *  - KDF = argon2kt 的 Argon2id（m=64MiB, t=3, p=1），在后台线程跑；
 *  - 密码对不对由 `VaultGateway.unlock` 抽样真实密文文件判定；
 *  - 仓库不存在时走首次设置：两次输入一致才创建（spec: vault-unlock「首次设置创建仓库」）。
 *
 * 两处刻意不做的事：
 *  1. **不显示 KDF 进度百分比**。骨架版画了一个 0→100% 的假进度条和「验证中 n%」，
 *     Argon2id 是一次不可中断的原生调用，给不出进度。改成不确定态的转圈 —— 宁可不知道，
 *     也不编一个百分比出来。
 *  2. **空仓库不声称「密码正确」**。没有任何文件能验证主密钥，此时任何密码都会被放行，
 *     界面只说「密码尚未被校验」（spec 明确要求 MUST NOT 显示已验证的表述）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    /** 参数 = 密码是否被真实文件**证明**过（空仓库为 false，见文件头注释）。 */
    onUnlocked: (passwordVerified: Boolean) -> Unit,
    onDebug: () -> Unit,
    /** 诊断页。**不需要解锁**就能进 —— 仓库打不开时恰恰最需要它。 */
    onDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 读两次 37 字节的边车，不跑 KDF。恢复备份后 stateTick++ 让它重新判定
    // 「首次设置」还是「解锁」。
    var stateTick by remember { mutableStateOf(0) }
    val vaultState = remember(stateTick) { VaultGateway.state(context) }
    val isFirstRun = vaultState == VaultState.NotCreated

    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var fieldError by remember { mutableStateOf<String?>(null) }
    var outcomeError by remember { mutableStateOf<OutcomeError?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingRestore = uri }

    fun startRestore(uri: Uri) {
        restoring = true
        restoreMessage = null
        scope.launch {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    VaultGateway.restoreVaultArchive(context, input, allowOverwrite = true)
                } ?: error("打不开备份文件")
            }
            restoring = false
            restoreMessage = when (val outcome = result.getOrNull()) {
                is VaultArchive.RestoreResult.Success -> {
                    stateTick++
                    "恢复完成：${outcome.entries} 个条目" +
                        (if (outcome.usedBackupMeta) "（用了 vault.meta.bak）" else "") +
                        "。请用做这份备份时的密码解锁。"
                }
                is VaultArchive.RestoreResult.Failed -> "恢复失败：${outcome.reason}"
                null -> "恢复失败：${result.exceptionOrNull()?.javaClass?.simpleName}"
            }
        }
    }
    val colorScheme = MaterialTheme.colorScheme

    // 进入时两份边车就已经都过不了 CRC32：先把话说明白，别让用户以为是密码问题。
    LaunchedEffect(vaultState) {
        if (vaultState == VaultState.Corrupted) outcomeError = OutcomeError.Corrupted
    }

    // Kotlin 的局部函数必须先声明后使用（它不是成员函数，不能提前引用）。
    fun submit() {
        if (isWorking) return
        if (password.length < VaultGateway.MIN_PASSWORD_LENGTH) {
            fieldError = "密码至少 ${VaultGateway.MIN_PASSWORD_LENGTH} 位"
            return
        }
        if (isFirstRun && password != confirmPassword) {
            fieldError = "两次输入不一致"
            return
        }
        fieldError = null
        outcomeError = null
        isWorking = true
        val entered = password
        scope.launch {
            val outcome = if (isFirstRun) {
                VaultGateway.create(context, entered.toCharArray())
            } else {
                VaultGateway.unlock(context, entered.toCharArray())
            }
            isWorking = false
            when (outcome) {
                is UnlockOutcome.Unlocked -> onUnlocked(outcome.probe.keyProven)
                is UnlockOutcome.WrongPassword ->
                    outcomeError = OutcomeError.WrongPassword(outcome.candidateFingerprint)
                UnlockOutcome.VaultCorrupted -> outcomeError = OutcomeError.Corrupted
                is UnlockOutcome.Failed -> outcomeError = OutcomeError.Other(outcome.detail)
            }
        }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("从备份恢复？") },
            text = {
                Text(
                    "会用备份里的仓库替换当前的" +
                        (if (vaultState == VaultState.NotCreated) "空仓库" else "仓库（现有条目会全部消失）") +
                        "，不可撤销。恢复后请用做这份备份时的密码解锁。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    startRestore(uri)
                }) { Text("恢复", color = colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            }
        )
    }

    // 外层 Box 负责居中，内层 Column 负责滚动。
    // 为什么不是「Column + verticalScroll + Arrangement.Center」：滚动容器的测量高度是无限，
    // 于是 Center 永远不会生效（内容会贴顶）。分开两层才同时拿到居中和可滚动 ——
    // 键盘弹起、窗口被 adjustResize 压矮时，按钮必须仍然够得着。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .imePadding(), // DESIGN.md §2: 键盘弹出时输入框随 IME 上移
        contentAlignment = Alignment.Center
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (isFirstRun) "设置密码" else "输入密码",
            style = MaterialTheme.typography.titleLarge,
            color = colorScheme.onSurface
        )

        if (isFirstRun) {
            Spacer(modifier = Modifier.height(12.dp))
            // spec: vault-unlock 明确要求「系统 MUST 告知」这两件事，所以放在按钮之前，
            // 而不是创建完成之后的某个提示里 —— 用户必须在输入密码之前就知道。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.warning.copy(alpha = 0.12f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "没有恢复码",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.warning
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "忘记密码 = 里面的视频永久取不回。v1 不提供恢复码，也没有后门。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "空仓库无法校验密码：刚创建的保险箱里没有任何文件，" +
                            "所以在你导入第一个视频之前，系统无法判断密码是否输对。请务必记住它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        // 真机踩过的坑：空仓库对任何密码都放行，于是「顺手输个别密码进去看看」
                        // 会把那把密码锚定到第一个写入的文件上，创建时的密码反而再也进不来。
                        text = "也别在导入第一个视频之前用别的密码进来「试试」：" +
                            "第一次写入会把当时那把密码永久锚定，之后只有它能解开仓库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        PasswordField(
            value = password,
            onValueChange = { password = it; fieldError = null; outcomeError = null },
            label = if (isFirstRun) "密码（至少 ${VaultGateway.MIN_PASSWORD_LENGTH} 位）" else "密码",
            showPassword = showPassword,
            onToggleVisibility = { showPassword = !showPassword },
            imeAction = if (isFirstRun) ImeAction.Next else ImeAction.Done,
            // 首次设置时这一栏是「下一项」（回车由 IME 移到确认框），所以只有解锁模式才提交
            onDone = { if (!isFirstRun) submit() },
            errorText = fieldError,
        )

        if (isFirstRun) {
            Spacer(modifier = Modifier.height(12.dp))
            PasswordField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; fieldError = null; outcomeError = null },
                label = "再输一次",
                showPassword = showPassword,
                onToggleVisibility = { showPassword = !showPassword },
                imeAction = ImeAction.Done,
                onDone = { submit() },
                errorText = null,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { submit() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(2.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error),
            enabled = password.isNotEmpty() && !isWorking
        ) {
            if (isWorking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isFirstRun) "正在创建…" else "正在校验…",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                Text(if (isFirstRun) "创建保险箱" else "解锁", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (isWorking) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "正在用 Argon2id 派生密钥（约 1–3 秒）…",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )
        }

        outcomeError?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            // 损坏卡片上的「打开诊断页」现在指向真正的诊断页（原来指向 debug 探针）
            ErrorCard(error, onDiagnostics = onDiagnostics)
        }

        // 诊断与恢复都必须在**解锁之前**可达：
        // 「仓库打不开」正是最需要看诊断、或干脆从备份恢复的时候。
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDiagnostics) {
                Text("诊断", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
                onClick = { restorePicker.launch(arrayOf("*/*")) },
                enabled = !restoring,
            ) {
                Text(
                    if (restoring) "正在恢复…" else "从备份恢复",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        restoreMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        // debug 专用入口：那两个探针（私有目录可见性 / 格式普查）跟密码无关，
        // 所以在解锁之前就要能进去。
        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onDebug) {
                Text("诊断探针（debug 专用）", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    showPassword: Boolean,
    onToggleVisibility: () -> Unit,
    imeAction: ImeAction,
    onDone: () -> Unit,
    errorText: String?,
) {
    val colorScheme = MaterialTheme.colorScheme
    val errorColor = colorScheme.error
    val errorStyle = MaterialTheme.typography.labelSmall
    // 显式声明类型（而不是把 lambda 直接写进实参）：嵌套的 composable lambda 需要靠
    // 期望类型来推断，写成带类型标注的 val 最稳。
    val supporting: (@Composable () -> Unit)? = if (errorText != null) {
        { Text(errorText, color = errorColor, style = errorStyle) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        // 只覆盖 onDone。不覆盖 onNext，是为了保留 Compose 的默认行为：
        // 「下一项」自动把焦点移到下面的「再输一次」，比手写 FocusRequester 简单且不会失焦。
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(2.dp),
        isError = errorText != null,
        supportingText = supporting,
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    // 必须**import**这两个图标：它们是 Icons.Filled 上的扩展属性，
                    // 全限定写法（androidx.compose.material.icons.Icons.Filled.Visibility）
                    // 不会把扩展带进作用域，编译期会报 Unresolved reference。
                    imageVector = if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                    tint = colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/** 三种失败各有各的文案与建议，MUST NOT 合并成一句「密码错误」。 */
private sealed interface OutcomeError {
    data class WrongPassword(val candidateFingerprint: String) : OutcomeError
    object Corrupted : OutcomeError
    data class Other(val detail: String) : OutcomeError
}

@Composable
private fun ErrorCard(error: OutcomeError, onDiagnostics: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val (title, advice) = when (error) {
        is OutcomeError.WrongPassword -> "密码不对" to
            "检查一下大小写、输入法状态与首尾空格。密码经过 Unicode 规范化，全角/半角字符不是同一个字。"
        OutcomeError.Corrupted -> "仓库元数据损坏" to
            "vault.meta 与 vault.meta.bak 两份都没通过校验，跟密码无关。先别卸载或重装 App" +
            "（那会删掉整个私有目录），进诊断页看一下，再决定下一步。"
        is OutcomeError.Other -> "解锁没能完成" to
            "${error.detail}。可以进诊断页查看更多信息。"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.error.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = colorScheme.error)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = advice,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
            if (error is OutcomeError.WrongPassword) {
                // 指纹 + 锚定说明。这不是给终端用户的文案，是给「我确定密码没记错」这种时刻的：
                // 空仓库对任何密码都放行，而第一次写入会把当时那把密码永久锚定 ——
                // 开发阶段踩过一次，表现就是「密码明明没记错却再也进不去」。
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "这次密码派生的密钥指纹：${error.candidateFingerprint}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "如果这个仓库是刚建的、还没导入过自己的视频，而中途解锁时随便输过一个密码 ——" +
                        "那一次的密码已经被第一个写入的文件永久锚定了（空仓库无法校验密码，" +
                        "所以任何密码都能进，而写入时用的是当次那把）。" +
                        "要换密码重来，请在诊断探针页用「重置仓库」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            if (error == OutcomeError.Corrupted) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDiagnostics) {
                    Text("打开诊断页", color = colorScheme.error)
                }
            }
        }
    }
}
