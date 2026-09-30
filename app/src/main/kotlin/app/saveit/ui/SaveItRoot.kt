package app.saveit.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.saveit.container
import app.saveit.core.model.Platform
import app.saveit.ui.about.AboutScreen
import app.saveit.ui.history.HistoryScreen
import app.saveit.ui.history.HistoryViewModel
import app.saveit.ui.home.HomeScreen
import app.saveit.ui.home.HomeViewModel
import app.saveit.ui.login.LoginScreen
import app.saveit.ui.settings.SettingsScreen
import app.saveit.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.StateFlow

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("history", "History", Icons.Outlined.History, Icons.Filled.History),
    Tab("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun SaveItRoot(sharedText: StateFlow<String?>, onSharedConsumed: () -> Unit) {
    val container = LocalContext.current.container
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val factory = viewModelFactory {
        initializer { HomeViewModel(container) }
        initializer { HistoryViewModel(container) }
        initializer { SettingsViewModel(container) }
    }
    val homeVm: HomeViewModel = viewModel(factory = factory)

    val shared by sharedText.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        shared?.let {
            nav.navigate("home") { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true }
            homeVm.onSharedText(it)
            onSharedConsumed()
        }
    }

    val goLogin: (Platform) -> Unit = { p -> nav.navigate("login/${p.name}") }

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable("home") {
                HomeScreen(
                    vm = homeVm,
                    onOpenHistory = { nav.navigate("history") { launchSingleTop = true } },
                    onLogin = goLogin,
                    onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
                )
            }
            composable("history") { HistoryScreen(vm = viewModel(factory = factory)) }
            composable("settings") {
                SettingsScreen(
                    vm = viewModel(factory = factory),
                    onLogin = goLogin,
                    onAbout = { nav.navigate("about") },
                )
            }
            composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
            composable("login/{platform}", arguments = listOf(navArgument("platform") { type = NavType.StringType })) { entry ->
                val platform = runCatching { Platform.valueOf(entry.arguments?.getString("platform").orEmpty()) }.getOrNull()
                if (platform == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    LoginScreen(platform = platform, onDone = { nav.popBackStack() })
                }
            }
        }
    }
}
