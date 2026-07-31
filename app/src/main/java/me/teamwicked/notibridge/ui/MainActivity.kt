package me.teamwicked.notibridge.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.teamwicked.notibridge.ui.hookeditor.HookEditorScreen
import me.teamwicked.notibridge.ui.hooks.HookListScreen
import me.teamwicked.notibridge.ui.logs.LogsScreen
import me.teamwicked.notibridge.ui.settings.SettingsScreen
import me.teamwicked.notibridge.ui.theme.NotiBridgeTheme
import me.teamwicked.notibridge.util.PermissionUtils

object Routes {
    const val HOOKS = "hooks"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val EDITOR_NEW = "editor/new"
    const val EDITOR_PREFIX = "editor"
    fun editor(hookId: String) = "editor/$hookId"
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (PermissionUtils.needsPostNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            NotiBridgeTheme {
                NotiBridgeNavHost()
            }
        }
    }
}

@Composable
private fun NotiBridgeNavHost() {
    val navController = rememberNavController()
    val destinations = listOf(
        TopLevelDestination(Routes.HOOKS, "훅", Icons.Filled.Hub),
        TopLevelDestination(Routes.LOGS, "로그", Icons.Filled.History),
        TopLevelDestination(Routes.SETTINGS, "설정", Icons.Filled.Settings),
    )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in destinations.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOOKS,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOOKS) {
                HookListScreen(
                    onCreateHook = { navController.navigate(Routes.EDITOR_NEW) },
                    onEditHook = { id -> navController.navigate(Routes.editor(id)) },
                )
            }
            composable(Routes.LOGS) { LogsScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable("${Routes.EDITOR_PREFIX}/{hookId}") { entry ->
                val hookId = entry.arguments?.getString("hookId")
                HookEditorScreen(
                    hookId = if (hookId == "new") null else hookId,
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}
