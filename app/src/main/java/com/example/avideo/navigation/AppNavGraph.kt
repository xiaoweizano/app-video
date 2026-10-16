package com.example.avideo.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.avideo.ui.screens.debug.DebugProbeScreen
import com.example.avideo.ui.screens.diagnostics.DiagnosticsScreen
import com.example.avideo.ui.screens.export.ExportScreen
import com.example.avideo.ui.screens.importflow.ImportFlowScreen
import com.example.avideo.ui.screens.player.PlayerScreen
import com.example.avideo.ui.screens.unlock.UnlockScreen
import com.example.avideo.ui.screens.vaultlist.VaultListScreen

@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Unlock.route
) {
    val navController = rememberNavController()

    // 解锁时密码是否被真实文件证明过。列表页用它决定要不要提示「空仓库无法校验密码」。
    // 进程存活期间保留在这里；过期锁定会重建整个导航图（见 MainActivity），于是它自然复位。
    var passwordVerified by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Unlock.route) {
            UnlockScreen(
                onUnlocked = { verified ->
                    passwordVerified = verified
                    navController.navigate(Screen.VaultList.route) {
                        // 解锁后把解锁页从返回栈里去掉：否则按返回键能回到解锁页，
                        // 而那时主密钥还在内存里 —— 那个页面就没有存在意义了。
                        popUpTo(Screen.Unlock.route) { inclusive = true }
                    }
                },
                onDebug = { navController.navigate(Screen.Debug.route) }
            )
        }

        composable(Screen.VaultList.route) {
            VaultListScreen(
                passwordVerified = passwordVerified,
                onImport = { navController.navigate(Screen.ImportFlow.route) },
                onExport = { navController.navigate(Screen.ExportFlow.route) },
                onDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                onPlay = { fileId ->
                    navController.navigate(Screen.Player.createRoute(fileId))
                },
                // 未解锁（主密钥已被清掉）时回到解锁页，并把列表从返回栈里去掉 ——
                // 否则按返回键会回到一个永远显示「未解锁」的页面。
                onRequireUnlock = {
                    navController.navigate(Screen.Unlock.route) {
                        popUpTo(Screen.VaultList.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(navArgument("fileId") { type = NavType.StringType })
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            // 注意要把 onBack 写成具名实参：PlayerScreen 的最后一个参数是 modifier，
            // 用尾随 lambda 会把回调绑到 modifier 上（并让 onBack 缺失）。
            PlayerScreen(fileId = fileId, onBack = { navController.popBackStack() })
        }

        composable(Screen.ImportFlow.route) {
            ImportFlowScreen(
                onBack = { navController.popBackStack(Screen.VaultList.route, inclusive = false) },
                onComplete = { navController.popBackStack(Screen.VaultList.route, inclusive = false) }
            )
        }

        composable(Screen.ExportFlow.route) {
            ExportScreen(
                onBack = { navController.popBackStack(Screen.VaultList.route, inclusive = false) },
                onComplete = { navController.popBackStack(Screen.VaultList.route, inclusive = false) }
            )
        }

        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }

        // debug 专用目的地：诊断探针。入口在解锁页底部，且只在 BuildConfig.DEBUG 时显示，
        // 所以 release 构建里用户到不了这里。
        composable(Screen.Debug.route) {
            DebugProbeScreen(onBack = { navController.popBackStack() })
        }
    }
}
