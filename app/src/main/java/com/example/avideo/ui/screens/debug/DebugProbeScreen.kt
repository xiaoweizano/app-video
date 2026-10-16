package com.example.avideo.ui.screens.debug

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avideo.core.crypto.KdfParams
import com.example.avideo.crypto.VaultGateway
import kotlinx.coroutines.launch
import java.io.File

/**
 * debug 专用诊断页。四件事：B/A 回答「设计前提成不成立」，C/D 回答「真机上的实现对不对」。
 *
 * **B · 私有目录可见性（T2，威胁模型门禁）**
 * 把一个**真实的视频**复制进 `getFilesDir()` 与 `getExternalFilesDir()`，
 * 然后由人去看系统图库 / 文件管理器 / 第三方文件管理器能不能找到它。
 * 找不到 —— 「真的藏得住」这一层成立；找得到 —— 整个 B 层威胁模型要重写。
 * 注意必须用**真实视频**：随便造一个 `.mp4` 后缀的文件，图库不索引它可能是因为
 * 「不是合法视频」而不是「目录私有」，那样结论是不成立的。
 *
 * **A · 视频格式普查（T0 尽调的事实部分）**
 * 统计媒体库里按扩展名的分布与占用。它决定 libVLC 那 41 MB 值不值得：
 * 冷门格式（rmvb/avi 等）占比很低的话，换 Media3/ExoPlayer 能把 APK 从 ~36 MB 降到 ~8 MB。
 *
 * **C · 跨实现自检（F6.1，唯一能在真机上验证的格式承诺）**
 * 用真机的 argon2kt 按 `core/src/test/resources/fixtures` 里那份 vector（生产参数、
 * 非 ASCII + emoji 密码）派生一次，与 fixture 里由 BouncyCastle 算出的主密钥逐字节比对。
 * 一致 ⇒「换平台也能解开」这条承诺在 Android 上真的成立；不一致 ⇒ 真机上根本解不开
 * JVM 侧写出来的仓库 —— 那必须在导入任何文件之前发现，否则用户的视频会永远打不开。
 *
 * **D · 仓库读写自检（需要先解锁）**
 * 往真实仓库里写一个跨 2 块 + 短末块的合成条目，再逐块读回来核对 SHA-256。
 * 起因：stage-1 的导入还没接通，而**空仓库对任何密码都放行** —— 没有条目就验证不了
 * 列表、密码校验、锁定/解锁这条完整路径（真机上唯一能验的方式就是自己写一条进去）。
 * 走的是生产同一套 `EncryptedFileWriter` / `EncryptedFileReader`，没有另开路径。
 *
 * **R · 重置仓库（debug）**
 * 删掉整个仓库并清密钥，用来走出「密码被锚错所以再也进不去」这个死局
 * （空仓库对任何密码都放行，第一次写入锚定当时那把密码，且没有恢复路径 —— 这是设计）。
 *
 * 本页只在 debug 构建可达（入口在解锁页底部），且媒体读取权限只声明在
 * `app/src/debug/AndroidManifest.xml` —— release 不声明，因为正式版走 SAF，不需要读媒体库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugProbeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var output by remember { mutableStateOf("") }

    fun append(line: String) {
        output = output + line + "\n"
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) append("[B] 未选择文件（取消了）") else append(copyProbe(context, uri))
    }

    val mediaPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) append(surveyVideos(context)) else append("[A] 未授予媒体读取权限，无法普查")
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("诊断探针") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("B · 私有目录可见性（威胁模型门禁）", style = MaterialTheme.typography.titleSmall)
            Text(
                "选一个相册里的真实视频，App 会把它复制进两处内部目录。复制完请去检查三处：" +
                    "① 系统图库有没有多出新条目 ② 文件管理（含「显示隐藏文件」）能否看到下面的路径 " +
                    "③ 第三方文件管理器。三处都找不到 = 真的藏得住；任一处找得到 = 设计前提要重写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { pickVideo.launch(arrayOf("video/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("选择视频并复制进私有目录") }

            HorizontalDivider()

            Text("A · 视频格式普查（决定要不要 41 MB 的 libVLC）", style = MaterialTheme.typography.titleSmall)
            Text(
                "统计媒体库里按扩展名的分布。冷门格式（rmvb / avi 等）占比很低的话，" +
                    "换 Media3 能把 APK 从约 36 MB 降到约 8 MB。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { askPermission.launch(mediaPermission) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("统计媒体库里的格式分布") }

            HorizontalDivider()

            Text("C · 跨实现自检（真机 argon2kt 对 fixture）", style = MaterialTheme.typography.titleSmall)
            Text(
                "用生产参数在同一台手机上派生一次 fixture 里的密码，与 BouncyCastle 算出的主密钥比对。" +
                    "一致 = Android 与 JVM 产出同一份字节，「换机/换平台还能解开」成立；" +
                    "不一致 = 真机解不开 JVM 写的仓库，必须在导入第一个视频之前修掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var isChecking by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            Button(
                onClick = {
                    isChecking = true
                    scope.launch {
                        append(crossImplementationSelfTest())
                        isChecking = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isChecking
            ) { Text(if (isChecking) "正在派生（约 1–3 秒）…" else "跑跨实现自检") }

            HorizontalDivider()

            Text("D · 仓库读写自检（需要先解锁）", style = MaterialTheme.typography.titleSmall)
            Text(
                "往仓库里写一个约 2 MB 的合成条目（跨 2 块 + 一个短末块），再读回来核对 SHA-256。" +
                    "导入功能还没接通，而没有条目就验证不了列表、密码校验、锁定这条完整路径" +
                    "（空仓库对任何密码都放行）。走的是生产同一套读写代码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var isWriting by remember { mutableStateOf(false) }
            var showAnchorConfirm by remember { mutableStateOf(false) }

            fun startWrite() {
                isWriting = true
                scope.launch {
                    append(VaultGateway.writeAndVerifyProbeEntry(context))
                    isWriting = false
                }
            }

            Button(
                onClick = {
                    // 密码还没被任何文件证明过 ⇒ 这次写入会**锚定**当前密码（而且是不可逆的），
                    // 所以必须在写之前问一次，而不是写完再在输出里解释。
                    if (VaultGateway.isPasswordProven()) startWrite() else showAnchorConfirm = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isWriting
            ) { Text(if (isWriting) "正在写入并读回…" else "写入合成条目并读回核对") }

            if (showAnchorConfirm) {
                AlertDialog(
                    onDismissRequest = { showAnchorConfirm = false },
                    title = { Text("这次写入会永久锚定当前密码") },
                    text = {
                        Text(
                            "这个仓库现在是空的，所以刚才那次输入的密码没有被任何文件校验过 —— " +
                                "任何密码都能进来，而不同密码派生出的密钥互不相同。" +
                                "写入之后，只有当前这一次输入的密码能解开这个文件（以及之后写入的文件），" +
                                "而且没有恢复路径。\n\n" +
                                "如果你不确定刚输的是哪一把：先取消，回上一页用下面的「重置仓库」清空，" +
                                "再用你确定要用的那个密码进来。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAnchorConfirm = false
                            startWrite()
                        }) { Text("我确定，写入") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAnchorConfirm = false }) { Text("取消") }
                    }
                )
            }

            HorizontalDivider()

            Text("R · 重置仓库（debug）", style = MaterialTheme.typography.titleSmall)
            Text(
                "删掉整个仓库目录（vault.meta / vault.meta.bak / data 下的密文条目）并清掉内存里的密钥，" +
                    "然后返回上一页重新设置密码。\n" +
                    "为什么需要这条路：空仓库对任何密码都放行，而「第一次写入会把当时那把密码永久锚定」 —— " +
                    "密码一旦锚定就没有恢复路径（这是设计）。开发阶段很容易锚错，没有这条路真机会卡在" +
                    "「再也进不去」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var showResetConfirm by remember { mutableStateOf(false) }
            var isResetting by remember { mutableStateOf(false) }
            Button(
                onClick = { showResetConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isResetting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(if (isResetting) "正在重置…" else "重置仓库（删掉全部条目）") }

            if (showResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = { Text("删除整个仓库？") },
                    text = {
                        Text(
                            "vault.meta、vault.meta.bak 与 data/ 下的全部密文条目都会被删除，不可恢复。" +
                                "（合成自检条目也一起删。）"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showResetConfirm = false
                            isResetting = true
                            scope.launch {
                                append(VaultGateway.resetVault(context))
                                isResetting = false
                            }
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
                    }
                )
            }

            HorizontalDivider()

            Text("输出", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    text = output.ifEmpty { "（还没有输出）" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * 与 `core/src/test/resources/fixtures/vector.properties` 对齐的三个常量。
 *
 * 刻意**复制**而不是读取：fixture 在 `:core` 的 **test** 资源里，不会被打进 APK。
 * 代价是它可能过期 —— fixture 重新生成（`./gradlew :core:generateFixtures`）后这三个值要同步。
 * 过期时的表现是自检报「不一致」，也就是**响亮地失败**，而不是静默通过。
 */
private const val FIXTURE_PASSWORD = "café-密码-🔒"
private const val FIXTURE_SALT_HEX = "0358d39219b51e489e7d24b05bdca84d"
private const val FIXTURE_MASTER_KEY_HEX = "5b3af832d590f593201857ad3e50e31c36d2d04a852bf557a50b43a844092c66"

/**
 * 在真机上跑一次跨实现自检。走的是生产同一条路径（`VaultGateway` 里的 NFC + argon2kt），
 * 所以这个结论能覆盖生产。
 */
private suspend fun crossImplementationSelfTest(): String = try {
    val salt = hexToBytes(FIXTURE_SALT_HEX)
    val started = System.currentTimeMillis()
    val derived = VaultGateway.deriveForSelfTest(
        FIXTURE_PASSWORD.toCharArray(),
        salt,
        KdfParams.PRODUCTION,
    )
    val elapsed = System.currentTimeMillis() - started
    val actual = derived.joinToString("") { "%02x".format(it) }

    val sb = StringBuilder("[C] 跨实现自检（Argon2id m=64MiB t=3 p=1，耗时 ${elapsed}ms）\n")
    sb.append("    期望 $FIXTURE_MASTER_KEY_HEX\n")
    sb.append("    实际 $actual\n")
    if (actual == FIXTURE_MASTER_KEY_HEX) {
        sb.append("    ✓ 一致：真机的 argon2kt 与 BouncyCastle/JVM 产出同一份字节，换平台能解开。\n")
    } else {
        sb.append(
            "    ✗ 不一致：真机解不开 JVM 写的仓库，先别导入任何视频！\n" +
                "      排查顺序：① 参数是否传全（argon2kt 默认 t=1/p=2，与生产不同）" +
                "② 版本必须是 V13 ③ 密码要按 UTF-8 编码、且经过 NFC。\n"
        )
    }
    sb.toString()
} catch (e: Throwable) {
    // 自检本身崩了也要有输出：这条路径最可能的崩法是 .so 没打进 APK（UnsatisfiedLinkError）
    "[C] 自检崩了：${e::class.java.name}: ${e.message}\n"
}

private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) {
    ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte()
}

private fun displayNameOf(context: Context, uri: Uri): String {    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it }
            }
        }
    return "probe-${System.currentTimeMillis()}.mp4"
}

/**
 * 复制一份真实视频到两个内部目录。两个都测，是因为它们的可见性规则不同：
 *  `getFilesDir()` → /data/data/<pkg>/files/，第三方文件管理器通常完全够不着；
 *  `getExternalFilesDir()` → /sdcard/Android/data/<pkg>/files/，Android 11+ 对文件管理器隐藏，
 *  但媒体库扫描是否覆盖它，各 ROM 行为不同 —— 这正是要测的。
 */
private fun copyProbe(context: Context, uri: Uri): String {
    val name = displayNameOf(context, uri)
    val internal = File(File(context.filesDir, "probe").apply { mkdirs() }, name)
    val externalDir = context.getExternalFilesDir(null)
    val targets = buildList {
        add("内部私有目录 getFilesDir()" to internal)
        if (externalDir != null) {
            add("外部应用目录 getExternalFilesDir()" to File(File(externalDir, "probe").apply { mkdirs() }, name))
        }
    }

    val sb = StringBuilder("[B] 探针文件：$name\n")
    targets.forEach { (label, target) ->
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: error("打不开输入流")
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        }.onSuccess {
            sb.append("  ✓ $label\n      ${target.absolutePath}\n      ${target.length()} 字节\n")
        }.onFailure {
            sb.append("  ✗ $label 复制失败：${it::class.simpleName}: ${it.message}\n")
        }
    }
    sb.append("  接着去：系统图库找「$name」；文件管理里试着走到上面两个路径；再用第三方文件管理器试一次。\n")
    return sb.toString()
}

/** 按扩展名统计媒体库里的视频。冷门格式单独提示，因为它直接决定播放器选型。 */
private fun surveyVideos(context: Context): String {
    val coldFormats = setOf("rmvb", "rm", "avi", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "vob", "mov")
    val count = mutableMapOf<String, Int>()
    val bytes = mutableMapOf<String, Long>()
    var total = 0

    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE),
        null, null, null
    )?.use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameIndex) ?: continue
            val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
            val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { "(无扩展名)" }
            count[ext] = (count[ext] ?: 0) + 1
            bytes[ext] = (bytes[ext] ?: 0L) + size
            total++
        }
    }

    if (total == 0) return "[A] 媒体库里没有查到视频（可能没有授予权限，或库里确实没有）"

    val sb = StringBuilder("[A] 媒体库视频总数：$total\n")
    sb.append(String.format("    %-12s %6s %12s  %s%n", "扩展名", "数量", "占用", ""))
    count.entries.sortedByDescending { it.value }.forEach { (ext, n) ->
        val gb = (bytes[ext] ?: 0L) / 1024.0 / 1024 / 1024
        val mark = if (ext in coldFormats) "← 冷门（libVLC 才能播）" else ""
        sb.append(String.format("    %-12s %6d %9.2f GB  %s%n", ext, n, gb, mark))
    }
    val coldCount = count.filterKeys { it in coldFormats }.values.sum()
    val coldBytes = bytes.filterKeys { it in coldFormats }.values.sum()
    val totalBytes = bytes.values.sum().coerceAtLeast(1L)
    sb.append("\n    冷门格式：$coldCount/$total = ${"%.1f".format(coldCount * 100.0 / total)}%")
    sb.append("（占用 ${"%.1f".format(coldBytes * 100.0 / totalBytes)}%）\n")
    sb.append(
        when {
            coldCount == 0 -> "    → 一个冷门格式都没有：换 Media3 完全够用，APK 可从 ~36 MB 降到 ~8 MB。"
            coldCount * 100.0 / total < 5 -> "    → 冷门占比 <5%：值得考虑 Media3 为主 + 少数用 libVLC，或直接放弃这部分。"
            else -> "    → 冷门占比不低：libVLC 这 41 MB 是必须付的。"
        }
    )
    return sb.toString()
}
