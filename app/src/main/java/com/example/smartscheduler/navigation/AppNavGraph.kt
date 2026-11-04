package com.example.smartscheduler.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.update.UpdateHost
import com.example.smartscheduler.importer.ImportScreen
import com.example.smartscheduler.update.rememberAppUpdateViewModel
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.smartscheduler.ui.screens.auth.LoginRegisterScreen
import com.example.smartscheduler.ui.screens.dashboard.DashboardScreen
import com.example.smartscheduler.ui.screens.schedule.BatchAssignScreen
import com.example.smartscheduler.ui.screens.schedule.RoutineScreen
import com.example.smartscheduler.ui.screens.stocks.StockAnalysisScreen
import com.example.smartscheduler.ui.screens.stocks.StockMarketHubScreen

object Routes {
    const val AUTH = "auth"
    const val DASHBOARD = "dashboard"
    const val ROUTINE = "routine"
    const val BATCH_ASSIGN = "batch_assign"
    const val IMPORT = "import"
    const val STOCK_HUB = "stock_hub"
    const val STOCK_ANALYSIS = "stock_analysis/{symbol}/{market}"

    fun stockAnalysis(symbol: String, market: String) = "stock_analysis/$symbol/$market"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.DASHBOARD, "วันนี้", Icons.Filled.Today),
    TabItem(Routes.ROUTINE, "กิจวัตร", Icons.Filled.Repeat),
    TabItem(Routes.STOCK_HUB, "หุ้น", Icons.Filled.ShowChart)
)

@Composable
fun AppNavGraph(startDestination: String = Routes.AUTH, openUpdateOnStart: Boolean = false) {
    val navController: NavHostController = rememberNavController()
    val updateViewModel = rememberAppUpdateViewModel()
    UpdateHost(updateViewModel, openedFromNotification = openUpdateOnStart)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == currentRoute }) {
                NavigationBar(containerColor = AppTheme.colors.surface, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = AppTheme.colors.ink,
                                selectedIconColor = AppTheme.colors.ground,
                                selectedTextColor = AppTheme.colors.ink,
                                unselectedIconColor = AppTheme.colors.muted,
                                unselectedTextColor = AppTheme.colors.muted
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.AUTH) {
                LoginRegisterScreen(
                    onAuthenticated = {
                        navController.navigate(Routes.DASHBOARD) { popUpTo(Routes.AUTH) { inclusive = true } }
                    }
                )
            }
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenBatchAssign = { navController.navigate(Routes.BATCH_ASSIGN) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) }
                )
            }
            composable(Routes.ROUTINE) {
                RoutineScreen(
                    onOpenBatchAssign = { navController.navigate(Routes.BATCH_ASSIGN) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) }
                )
            }
            composable(Routes.IMPORT) {
                ImportScreen(
                    onBack = { navController.popBackStack() },
                    onDone = {
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.BATCH_ASSIGN) {
                BatchAssignScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.STOCK_HUB) {
                StockMarketHubScreen(
                    onOpenStockDetail = { symbol, market ->
                        navController.navigate(Routes.stockAnalysis(symbol, market))
                    }
                )
            }
            composable(
                route = Routes.STOCK_ANALYSIS,
                arguments = listOf(
                    navArgument("symbol") { type = NavType.StringType },
                    navArgument("market") { type = NavType.StringType }
                )
            ) {
                StockAnalysisScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
