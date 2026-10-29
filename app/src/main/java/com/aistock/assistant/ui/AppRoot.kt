package com.aistock.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aistock.assistant.R
import com.aistock.assistant.features.ai.AssistantScreen
import com.aistock.assistant.features.ai.ConversationsScreen
import com.aistock.assistant.features.data.BackupScreen
import com.aistock.assistant.features.data.ImportExportScreen
import com.aistock.assistant.features.data.WorkbookScreen
import com.aistock.assistant.features.home.DashboardScreen
import com.aistock.assistant.features.inventory.InventoryScreen
import com.aistock.assistant.features.inventory.ProductFormScreen
import com.aistock.assistant.features.inventory.StockMoveScreen
import com.aistock.assistant.features.onboarding.LockScreen
import com.aistock.assistant.features.onboarding.OnboardingScreen
import com.aistock.assistant.features.parties.PartyFormScreen
import com.aistock.assistant.features.parties.PartyListScreen
import com.aistock.assistant.features.reports.ReportsScreen
import com.aistock.assistant.features.settings.MainViewModel
import com.aistock.assistant.features.settings.MoreScreen
import com.aistock.assistant.features.settings.SettingsScreen
import com.aistock.assistant.features.transactions.ExpenseFormScreen
import com.aistock.assistant.features.transactions.PaymentFormScreen
import com.aistock.assistant.features.transactions.TradeFormScreen
import com.aistock.assistant.features.transactions.TransactionsScreen
import com.aistock.assistant.ui.components.LocalCurrency
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.theme.AIStockTheme
import kotlinx.coroutines.launch
import com.aistock.assistant.business.BusinessConfig
import com.aistock.assistant.business.Module
import com.aistock.assistant.features.work.*

private data class TopLevel(val route: String, val base: String, val label: Int, val icon: Int, val module: Module? = null)

/**
 * The bottom bar follows the business: Home and Chat always, then the two things this business uses most
 * (a garment unit sees Production, a repair shop Jobs, a shop Inventory and Transactions), then More.
 */
private fun topLevel(cfg: BusinessConfig): List<TopLevel> {
    val candidates = ArrayList<TopLevel>()
    for (m in cfg.workModules.filter { it != Module.TASKS }) candidates.add(TopLevel("w_${m.key}", "w_${m.key}", R.string.m_tasks, R.drawable.ic_work, m))
    if (cfg.has(Module.INVENTORY)) candidates.add(TopLevel("inventory", "inventory", R.string.nav_inventory, R.drawable.ic_inventory))
    if (cfg.has(Module.SALES) || cfg.has(Module.PURCHASES) || cfg.has(Module.EXPENSES)) candidates.add(TopLevel("transactions", "transactions", R.string.nav_transactions, R.drawable.ic_receipt))
    if (cfg.has(Module.TASKS)) candidates.add(TopLevel("w_tasks", "w_tasks", R.string.m_tasks, R.drawable.ic_work, Module.TASKS))
    return listOf(TopLevel("home", "home", R.string.nav_home, R.drawable.ic_home), TopLevel("ai", "ai", R.string.nav_ai, R.drawable.ic_ai)) +
        candidates.take(2) + TopLevel("more", "more", R.string.nav_more, R.drawable.ic_more)
}

@Composable
fun AppRoot(startTalking: Boolean = false, openChat: Boolean = false, vm: MainViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    AIStockTheme(prefs.theme) {
        CompositionLocalProvider(LocalCurrency provides prefs.currencySymbol, LocalTamil provides prefs.tamil) {
            when {
                !prefs.loaded || (prefs.onboardingDone && hasPin == null) -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                !prefs.onboardingDone -> OnboardingScreen(vm)
                hasPin == true && !unlocked -> LockScreen(vm)
                // A Follower account is needed: it starts the free trial and carries the plan afterwards.
                prefs.serverBusinessId.isBlank() || prefs.serverBusinessId == "pending" -> {
                    val profile by vm.businessProfile.collectAsStateWithLifecycle()
                    com.aistock.assistant.features.account.SignInGate(profile?.name.orEmpty())
                }
                else -> OwnerCheckHost(prefs.ownerCheck, hasPin == true, { vm.unlock(it) }) { MainScaffold(vm, startTalking, openChat) }
            }
        }
    }
}

@Composable
private fun MainScaffold(vm: MainViewModel, startTalking: Boolean, openChat: Boolean = false) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route.orEmpty()
    val cfg by vm.businessConfig.collectAsStateWithLifecycle()
    val tabs = remember(cfg) { topLevel(cfg) }
    val showBar = tabs.any { it.base == currentRoute.substringBefore('?') }

    val nav = remember(navController) {
        AppNav(
            go = { route -> navController.navigate(route) { launchSingleTop = true } },
            back = { if (!navController.popBackStack()) navController.navigate("home") },
        )
    }
    val toast: (String) -> Unit = remember(snackbar) {
        { message ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(message)
            }
        }
    }

    var talked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startTalking) {
        if (startTalking && !talked) { talked = true; navController.navigate("ai?listen=true") { launchSingleTop = true } }
        else if (openChat && !talked) { talked = true; navController.navigate("ai") { launchSingleTop = true } }
    }

    if (prefs.loaded && !prefs.importOffered && !startTalking) {
        AlertDialog(
            onDismissRequest = { scope.launch { vm.setImportOffered() } },
            title = { Text(stringResource(R.string.import_offer_title)) },
            text = { Text(stringResource(R.string.import_offer_body)) },
            confirmButton = {
                Button(onClick = { scope.launch { vm.setImportOffered() }; nav.go("workbook") }) { Text(stringResource(R.string.import_offer_yes)) }
            },
            dismissButton = { TextButton(onClick = { scope.launch { vm.setImportOffered() } }) { Text(stringResource(R.string.import_offer_later)) } },
        )
    }

    CompositionLocalProvider(LocalToast provides toast) {
        com.aistock.assistant.features.calls.CallNoteHost()
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBar) NavigationBar {
                    for (item in tabs) {
                        NavigationBarItem(
                            selected = currentRoute.substringBefore('?') == item.base,
                            onClick = {
                                val onThisTab = currentRoute.substringBefore('?') == item.base
                                when {
                                    onThisTab -> {}
                                    // Home is always at the bottom of the stack: go back to it instead of opening a second copy.
                                    item.base == "home" -> navController.popBackStack("home", inclusive = false)
                                    else -> navController.navigate(item.route) {
                                        popUpTo("home") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(item.module?.let { moduleTitle(cfg, it) } ?: stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                composable("home") { DashboardScreen(nav) }
                composable("inventory?tab={tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) {
                    InventoryScreen(nav, it.arguments?.getInt("tab") ?: 0)
                }
                composable("transactions?tab={tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) {
                    TransactionsScreen(nav, it.arguments?.getInt("tab") ?: 0)
                }
                composable(
                    "ai?conv={conv}&listen={listen}",
                    arguments = listOf(
                        navArgument("conv") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("listen") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) { AssistantScreen(nav, prefs, it.arguments?.getString("conv"), it.arguments?.getBoolean("listen") ?: false) }
                composable("more") { MoreScreen(nav, vm, cfg, tabs.mapNotNull { it.module }.toSet(), tabs.map { it.base }.toSet()) }
                for (m in Module.WORK) composable("w_${m.key}") { WorkListScreen(nav, m.key, showBack = false) }
                composable("worklist/{module}", arguments = listOf(navArgument("module") { type = NavType.StringType })) { WorkListScreen(nav, it.arguments?.getString("module") ?: "tasks", showBack = true) }
                composable("work/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { WorkDetailScreen(nav, it.arguments?.getString("id").orEmpty()) }
                composable("workform/{module}", arguments = listOf(navArgument("module") { type = NavType.StringType })) { WorkFormScreen(nav, it.arguments?.getString("module") ?: "tasks") }
                composable("followups") { FollowupsScreen(nav) }
                composable("staff") { StaffScreen(nav) }
                composable("reminders") { RemindersScreen(nav) }
                composable("setup") { BusinessSetupScreen(nav) }
                composable("account") { com.aistock.assistant.features.account.AccountScreen(nav) }
                composable("plan") { com.aistock.assistant.features.account.PlanScreen(nav) }
                composable("analysis") { com.aistock.assistant.features.reports.AnalysisScreen(nav) }
                composable("call") { com.aistock.assistant.features.ai.CallStockyScreen(nav) }

                composable("product?id={id}", arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                    ProductFormScreen(nav, it.arguments?.getString("id"))
                }
                composable("stockmove/{mode}?productId={productId}", arguments = listOf(
                    navArgument("mode") { type = NavType.StringType },
                    navArgument("productId") { type = NavType.StringType; nullable = true; defaultValue = null },
                )) { StockMoveScreen(nav, it.arguments?.getString("mode") ?: "in", it.arguments?.getString("productId")) }
                composable("trade/{kind}", arguments = listOf(navArgument("kind") { type = NavType.StringType })) {
                    TradeFormScreen(nav, it.arguments?.getString("kind") ?: "sale")
                }
                composable("expense") { ExpenseFormScreen(nav) }
                composable("payment?kind={kind}&partyId={partyId}", arguments = listOf(
                    navArgument("kind") { type = NavType.StringType; defaultValue = "customer" },
                    navArgument("partyId") { type = NavType.StringType; nullable = true; defaultValue = null },
                )) { PaymentFormScreen(nav, it.arguments?.getString("kind") ?: "customer", it.arguments?.getString("partyId")) }
                composable("parties/{kind}", arguments = listOf(navArgument("kind") { type = NavType.StringType })) {
                    PartyListScreen(nav, it.arguments?.getString("kind") ?: "customer")
                }
                composable("party/{kind}?id={id}", arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                )) { PartyFormScreen(nav, it.arguments?.getString("kind") ?: "customer", it.arguments?.getString("id")) }
                composable("reports") { ReportsScreen(nav) }
                composable("data") { ImportExportScreen(nav) }
                composable("backup") { BackupScreen(nav) }
                composable("workbook") { WorkbookScreen(nav) }
                composable("excel_links") { com.aistock.assistant.features.data.ExcelLinksScreen(nav) }
                composable("settings") { SettingsScreen(nav, vm) }
                composable("conversations") { ConversationsScreen(nav) }
            }
        }
    }
}
