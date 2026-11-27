package com.vpsconsole.app.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vpsconsole.app.data.model.ServerProfile
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.adb.AdbScreen
import com.vpsconsole.app.ui.alerts.AlertsScreen
import com.vpsconsole.app.ui.dashboard.DashboardScreen
import com.vpsconsole.app.ui.extras.BackupsScreen
import com.vpsconsole.app.ui.extras.CronScreen
import com.vpsconsole.app.ui.extras.FirewallScreen
import com.vpsconsole.app.ui.extras.PackagesScreen
import com.vpsconsole.app.ui.extras.ScriptsScreen
import com.vpsconsole.app.ui.extras.ToolsScreen
import com.vpsconsole.app.ui.extras.TunnelsScreen
import com.vpsconsole.app.ui.extras.UsersScreen
import com.vpsconsole.app.ui.files.FilesScreen
import com.vpsconsole.app.ui.monitor.MonitorScreen
import com.vpsconsole.app.ui.servers.ServerEditScreen
import com.vpsconsole.app.ui.servers.ServerListScreen
import com.vpsconsole.app.ui.services.ServicesScreen
import com.vpsconsole.app.ui.settings.SettingsScreen
import com.vpsconsole.app.ui.terminal.TerminalScreen

private data class NavItem(val base: String, val label: String, val icon: ImageVector, val target: (Long) -> String)

private val navItems = listOf(
    NavItem("dashboard", "Home", Icons.Filled.Dashboard) { Routes.DASHBOARD },
    NavItem("servers", "Servers", Icons.Filled.Computer) { Routes.SERVERS },
    NavItem("terminal", "Terminal", Icons.Filled.Terminal) { Routes.terminal(it) },
    NavItem("tools", "Tools", Icons.Filled.Build) { Routes.tools(it) },
    NavItem("adb", "ADB", Icons.Filled.Computer) { Routes.adb(it) },
    NavItem("settings", "Settings", Icons.Filled.Settings) { Routes.SETTINGS }
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val container = LocalAppContainer.current
    val servers by container.serverRepo.servers.collectAsState(emptyList())
    val settings by container.settings.settings.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.DASHBOARD
    val selectedId = settings.selectedServerId

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 900.dp) {
            Row(Modifier.fillMaxSize()) {
                AppNavRail(nav, route, selectedId)
                Column(Modifier.weight(1f)) {
                    AppScaffold(nav, route, servers, selectedId, showBottomBar = false)
                }
            }
        } else {
            AppScaffold(nav, route, servers, selectedId, showBottomBar = true)
        }
    }
}

@Composable
private fun AppNavRail(nav: NavHostController, route: String, selectedId: Long) {
    NavigationRail {
        navItems.forEach { item ->
            NavigationRailItem(
                selected = route.startsWith(item.base),
                onClick = {
                    nav.navigate(item.target(selectedId)) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    nav: NavHostController,
    route: String,
    servers: List<ServerProfile>,
    selectedId: Long,
    showBottomBar: Boolean
) {
    var serverMenu by remember { mutableStateOf(false) }
    val container = LocalAppContainer.current
    val selectedServer = servers.firstOrNull { it.id == selectedId }

    val fullScreenTerminal = route.startsWith("terminal")
    Scaffold(
        topBar = {
            if (fullScreenTerminal) {
                /* Terminal is full screen; its own controls include a back button */
            } else {
            TopAppBar(
                title = { Text(titleFor(route)) },
                navigationIcon = {
                    if (nav.previousBackStackEntry != null) {
                        IconButton(onClick = { nav.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { serverMenu = true }) {
                        Text(selectedServer?.name ?: "Select server")
                    }
                    DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
                        servers.forEach { server ->
                            DropdownMenuItem(
                                text = { Text(server.name) },
                                onClick = {
                                    container.settings.update { it.copy(selectedServerId = server.id) }
                                    // Re-navigate server-scoped screens so they pick up the new server
                                    val target = when {
                                        route.startsWith("terminal") -> Routes.terminal(server.id)
                                        route.startsWith("tools") -> Routes.tools(server.id)
                                        route.startsWith("monitor") -> Routes.monitor(server.id)
                                        route.startsWith("services") -> Routes.services(server.id)
                                        route.startsWith("files") -> Routes.files(server.id)
                                        route.startsWith("adb") -> Routes.adb(server.id)
                                        else -> null
                                    }
                                    if (target != null) nav.navigate(target) {
                                        launchSingleTop = true
                                        popUpTo(target) { inclusive = true }
                                    }
                                    serverMenu = false
                                }
                            )
                        }
                        if (servers.isEmpty()) {
                            DropdownMenuItem(text = { Text("No servers yet") }, onClick = { serverMenu = false })
                        }
                    }
                    IconButton(onClick = { nav.navigate(Routes.ALERTS) }) {
                        Icon(Icons.Filled.NotificationsNone, contentDescription = "Alerts")
                    }
                }
            )
            }
        },
        bottomBar = {
            if (showBottomBar && !fullScreenTerminal) {
                NavigationBar {
                    navItems.forEach { item ->
                        NavigationBarItem(
                            selected = route.startsWith(item.base),
                            onClick = {
                                nav.navigate(item.target(selectedId)) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        AppNavGraph(nav, Modifier.padding(padding).fillMaxSize())
    }
}

private fun titleFor(route: String): String = when {
    route.startsWith("dashboard") -> "Dashboard"
    route.startsWith("servers") -> "Servers"
    route.startsWith("server_edit") -> "Server details"
    route.startsWith("terminal") -> "SSH Terminal"
    route.startsWith("monitor") -> "Monitoring"
    route.startsWith("services") -> "Services & Processes"
    route.startsWith("files") -> "File Manager"
    route.startsWith("alerts") -> "Alerts"
    route.startsWith("tools") -> "Tools"
    route.startsWith("settings") -> "Settings"
    route.startsWith("firewall") -> "Firewall"
    route.startsWith("cron") -> "Cron Jobs"
    route.startsWith("users") -> "Users"
    route.startsWith("packages") -> "Packages & Updates"
    route.startsWith("backups") -> "Backups"
    route.startsWith("tunnels") -> "SSH Tunnels"
    route.startsWith("scripts") -> "Command Library"
    route.startsWith("adb") -> "ADB Console"
    else -> "VPS Console"
}

@Composable
private fun AppNavGraph(nav: NavHostController, modifier: Modifier) {
    val longArg = listOf(navArgument("serverId") { type = NavType.LongType })
    NavHost(nav, startDestination = Routes.DASHBOARD, modifier = modifier) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenServer = { nav.navigate(Routes.monitor(it)) },
                onNewServer = { nav.navigate(Routes.serverEdit()) },
                onOpenTerminal = { nav.navigate(Routes.terminal(it)) },
                onOpenFiles = { nav.navigate(Routes.files(it)) }
            )
        }
        composable(Routes.SERVERS) {
            ServerListScreen(
                onAdd = { nav.navigate(Routes.serverEdit()) },
                onEdit = { nav.navigate(Routes.serverEdit(it)) },
                onOpen = { nav.navigate(Routes.monitor(it)) },
                onTerminal = { nav.navigate(Routes.terminal(it)) },
                onFiles = { nav.navigate(Routes.files(it)) }
            )
        }
        composable(Routes.SERVER_EDIT, arguments = longArg) { entry ->
            ServerEditScreen(
                serverId = entry.arguments?.getLong("serverId") ?: 0L,
                onSaved = { nav.popBackStack() },
                onCancel = { nav.popBackStack() }
            )
        }
        composable(Routes.TERMINAL, arguments = longArg) { entry ->
            TerminalScreen(
                serverId = entry.arguments?.getLong("serverId") ?: 0L,
                onExit = { nav.navigateUp() }
            )
        }
        composable(Routes.MONITOR, arguments = longArg) { entry ->
            MonitorScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.SERVICES, arguments = longArg) { entry ->
            ServicesScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.FILES, arguments = longArg) { entry ->
            FilesScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.ALERTS) {
            AlertsScreen(onOpenServer = { nav.navigate(Routes.monitor(it)) })
        }
        composable(Routes.TOOLS, arguments = longArg) { entry ->
            ToolsScreen(entry.arguments?.getLong("serverId") ?: 0L) { nav.navigate(it) }
        }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(Routes.FIREWALL, arguments = longArg) { entry ->
            FirewallScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.CRON, arguments = longArg) { entry ->
            CronScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.USERS, arguments = longArg) { entry ->
            UsersScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.PACKAGES, arguments = longArg) { entry ->
            PackagesScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.BACKUPS, arguments = longArg) { entry ->
            BackupsScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.TUNNELS, arguments = longArg) { entry ->
            TunnelsScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.SCRIPTS, arguments = longArg) { entry ->
            ScriptsScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
        composable(Routes.ADB, arguments = longArg) { entry ->
            AdbScreen(entry.arguments?.getLong("serverId") ?: 0L)
        }
    }
}
