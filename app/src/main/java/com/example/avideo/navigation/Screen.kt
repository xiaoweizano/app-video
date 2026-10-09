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
}
