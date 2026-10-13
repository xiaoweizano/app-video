package com.example.avideo.navigation

sealed class Screen(val route: String) {
    object Unlock : Screen("unlock")
    object VaultList : Screen("vault_list")
    object Player : Screen("player/{fileId}") {
        fun createRoute(fileId: String) = "player/$fileId"
    }
    object ImportFlow : Screen("import_flow")
    object ExportFlow : Screen("export_flow")
    object Diagnostics : Screen("diagnostics")
    /** debug 专用：诊断探针（私有目录可见性 / 视频格式普查）。release 里不加这个目的地。 */
    object Debug : Screen("debug")
}
