package com.example.avideo.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Unlock.route) {
            UnlockScreen(
                onLocked = { navController.navigate(Screen.VaultList.route) }
            )
        }

        composable(Screen.VaultList.route) {
            VaultListScreen(
                onImport = { navController.navigate(Screen.ImportFlow.route) },
                onExport = { navController.navigate(Screen.ExportFlow.route) },
                onDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                onPlay = { fileId ->
                    navController.navigate(Screen.Player.createRoute(fileId))
                }
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
    }
}
