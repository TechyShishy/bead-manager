package com.techyshishy.beadmanager.ui.adaptive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.ui.catalog.CatalogScreen
import com.techyshishy.beadmanager.ui.catalog.CatalogViewModel
import com.techyshishy.beadmanager.ui.detail.BeadDetailPane
import com.techyshishy.beadmanager.ui.detail.BeadDetailViewModel
import com.techyshishy.beadmanager.ui.migration.MigrationViewModel
import com.techyshishy.beadmanager.ui.projects.AddToOrderScreen
import com.techyshishy.beadmanager.ui.projects.AddToOrderViewModel
import com.techyshishy.beadmanager.ui.projects.AllOrdersScreen
import com.techyshishy.beadmanager.ui.projects.AllOrdersViewModel
import com.techyshishy.beadmanager.ui.projects.FinalizeOrderScreen
import com.techyshishy.beadmanager.ui.projects.FinalizeOrderViewModel
import com.techyshishy.beadmanager.ui.projects.OrderDetailScreen
import com.techyshishy.beadmanager.ui.projects.OrderDetailViewModel
import com.techyshishy.beadmanager.ui.projects.OrdersScreen
import com.techyshishy.beadmanager.ui.projects.OrdersViewModel
import com.techyshishy.beadmanager.ui.projects.ProjectDetailScreen
import com.techyshishy.beadmanager.ui.projects.ProjectDetailViewModel
import com.techyshishy.beadmanager.ui.projects.ProjectsScreen
import com.techyshishy.beadmanager.ui.projects.ProjectsViewModel
import com.techyshishy.beadmanager.ui.settings.SettingsScreen
import com.techyshishy.beadmanager.ui.settings.SettingsViewModel

enum class AppTab { CATALOG, PROJECTS, ORDERS, SETTINGS }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdaptiveScaffold() {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.CATALOG) }
    val scope = rememberCoroutineScope()

    val catalogViewModel: CatalogViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val projectsViewModel: ProjectsViewModel = hiltViewModel()
    val allOrdersViewModel: AllOrdersViewModel = hiltViewModel()
    // Triggers one-time data migrations immediately on first composition post-auth.
    hiltViewModel<MigrationViewModel>()

    // Separate list-detail navigators keep scaffold state independent per tab.
    val catalogNavigator = rememberListDetailPaneScaffoldNavigator<String>()
    val catalogSnackbarHostState = remember { SnackbarHostState() }

    // Projects tab: five-level nav state (projects → project detail → orders → order detail → finalize).
    var ordersProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var ordersProjectName by rememberSaveable { mutableStateOf("") }
    var ordersShowOrdersList by rememberSaveable { mutableStateOf(false) }
    var ordersOrderId by rememberSaveable { mutableStateOf<String?>(null) }
    var ordersShowFinalizing by rememberSaveable { mutableStateOf(false) }
    // Non-null while the AddToOrderScreen picker is open; holds the checked bead codes.
    var ordersAddToOrderCodes by rememberSaveable { mutableStateOf<Set<String>?>(null) }

    // All-Orders tab: two-level nav state (list → order detail → finalize).
    var allOrdersOrderId by rememberSaveable { mutableStateOf<String?>(null) }
    var allOrdersShowFinalizing by rememberSaveable { mutableStateOf(false) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = currentTab == AppTab.CATALOG,
                onClick = { currentTab = AppTab.CATALOG },
                icon = {
                    Icon(
                        if (currentTab == AppTab.CATALOG) Icons.Filled.Search else Icons.Outlined.Search,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.catalog)) },
            )
            item(
                selected = currentTab == AppTab.PROJECTS,
                onClick = { currentTab = AppTab.PROJECTS },
                icon = {
                    Icon(
                        if (currentTab == AppTab.PROJECTS) Icons.Filled.Folder
                        else Icons.Outlined.Folder,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.projects)) },
            )
            item(
                selected = currentTab == AppTab.ORDERS,
                onClick = { currentTab = AppTab.ORDERS },
                icon = {
                    Icon(
                        if (currentTab == AppTab.ORDERS) Icons.Filled.ShoppingCart
                        else Icons.Outlined.ShoppingCart,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.orders)) },
            )
            item(
                selected = currentTab == AppTab.SETTINGS,
                onClick = { currentTab = AppTab.SETTINGS },
                icon = {
                    Icon(
                        if (currentTab == AppTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.settings)) },
            )
        },
    ) {
        when (currentTab) {
            AppTab.CATALOG -> {
                BackHandler(catalogNavigator.canNavigateBack()) {
                    scope.launch { catalogNavigator.navigateBack() }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    ListDetailPaneScaffold(
                        directive = catalogNavigator.scaffoldDirective.copy(maxHorizontalPartitions = 1),
                        value = catalogNavigator.scaffoldValue,
                        listPane = {
                            AnimatedPane {
                                CatalogScreen(
                                    viewModel = catalogViewModel,
                                    onBeadSelected = { code ->
                                        catalogNavigator.navigateTo(
                                            ListDetailPaneScaffoldRole.Detail,
                                            code,
                                        )
                                    },
                                )
                            }
                        },
                        detailPane = {
                            AnimatedPane {
                                catalogNavigator.currentDestination?.content?.let { code ->
                                    val detailVm: BeadDetailViewModel = hiltViewModel(
                                        key = "catalog_detail_$code",
                                    )
                                    BeadDetailPane(
                                        beadCode = code,
                                        viewModel = detailVm,
                                        onNavigateBack = if (catalogNavigator.canNavigateBack()) {
                                            { scope.launch { catalogNavigator.navigateBack() } }
                                        } else null,
                                        onShowSnackbar = { message ->
                                            scope.launch {
                                                catalogSnackbarHostState.showSnackbar(message)
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                    SnackbarHost(
                        hostState = catalogSnackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            AppTab.PROJECTS -> {
                BackHandler(ordersProjectId != null) {
                    when {
                        ordersShowFinalizing -> ordersShowFinalizing = false
                        ordersOrderId != null -> ordersOrderId = null
                        ordersAddToOrderCodes != null -> ordersAddToOrderCodes = null
                        ordersShowOrdersList -> ordersShowOrdersList = false
                        else -> {
                            ordersProjectId = null
                            ordersProjectName = ""
                        }
                    }
                }
                when {
                    ordersShowFinalizing && ordersOrderId != null -> {
                        val finalizeVm: FinalizeOrderViewModel =
                            hiltViewModel(key = "finalize_$ordersOrderId")
                        FinalizeOrderScreen(
                            orderId = ordersOrderId!!,
                            viewModel = finalizeVm,
                            onNavigateBack = { ordersShowFinalizing = false },
                        )
                    }
                    ordersOrderId != null -> {
                        val orderDetailVm: OrderDetailViewModel =
                            hiltViewModel(key = "order_detail_$ordersOrderId")
                        OrderDetailScreen(
                            orderId = ordersOrderId!!,
                            viewModel = orderDetailVm,
                            onNavigateBack = { ordersOrderId = null },
                            onFinalize = { ordersShowFinalizing = true },
                        )
                    }
                    ordersShowOrdersList && ordersProjectId != null -> {
                        val ordersVm: OrdersViewModel =
                            hiltViewModel(key = "orders_$ordersProjectId")
                        OrdersScreen(
                            projectId = ordersProjectId!!,
                            projectName = ordersProjectName,
                            viewModel = ordersVm,
                            onOrderSelected = { orderId -> ordersOrderId = orderId },
                            onNavigateBack = { ordersShowOrdersList = false },
                        )
                    }
                    ordersAddToOrderCodes != null && ordersProjectId != null -> {
                        // Same key as the ProjectDetailScreen branch so that both branches
                        // share the same ViewModel instance (Hilt returns the cached VM).
                        val projectDetailVm: ProjectDetailViewModel =
                            hiltViewModel(key = "project_detail_$ordersProjectId")
                        val addToOrderVm: AddToOrderViewModel =
                            hiltViewModel(key = "add_to_order_$ordersProjectId")
                        val selectedCodes = ordersAddToOrderCodes!!
                        AddToOrderScreen(
                            projectId = ordersProjectId!!,
                            viewModel = addToOrderVm,
                            onNavigateBack = { ordersAddToOrderCodes = null },
                            onNewOrder = {
                                projectDetailVm.createOrderFromSelection(selectedCodes)
                            },
                            onImportIntoOrder = { orderId ->
                                projectDetailVm.importProjectItems(orderId, selectedCodes)
                            },
                            onNavigateToOrder = { orderId ->
                                ordersAddToOrderCodes = null
                                ordersOrderId = orderId
                            },
                        )
                    }
                    ordersProjectId != null -> {
                        val projectDetailVm: ProjectDetailViewModel =
                            hiltViewModel(key = "project_detail_$ordersProjectId")
                        ProjectDetailScreen(
                            projectId = ordersProjectId!!,
                            viewModel = projectDetailVm,
                            onNavigateBack = {
                                ordersProjectId = null
                                ordersProjectName = ""
                            },
                            onViewOrders = { _, _ -> ordersShowOrdersList = true },
                            onAddToOrder = { codes -> ordersAddToOrderCodes = codes },
                        )
                    }
                    else -> {
                        ProjectsScreen(
                            viewModel = projectsViewModel,
                            onProjectSelected = { projectId, projectName ->
                                ordersProjectId = projectId
                                ordersProjectName = projectName
                                ordersShowOrdersList = false
                            },
                        )
                    }
                }
            }

            AppTab.ORDERS -> {
                BackHandler(allOrdersOrderId != null) {
                    when {
                        allOrdersShowFinalizing -> allOrdersShowFinalizing = false
                        else -> allOrdersOrderId = null
                    }
                }
                when {
                    allOrdersShowFinalizing && allOrdersOrderId != null -> {
                        // Keys are prefixed "all_" to keep these VM instances independent
                        // from the identically-keyed VMs in AppTab.PROJECTS. Both tabs may
                        // observe the same Firestore document simultaneously; sharing a key
                        // would cause back-navigation in one tab to clear state the other
                        // tab still needs.
                        val finalizeVm: FinalizeOrderViewModel =
                            hiltViewModel(key = "all_finalize_$allOrdersOrderId")
                        FinalizeOrderScreen(
                            orderId = allOrdersOrderId!!,
                            viewModel = finalizeVm,
                            onNavigateBack = { allOrdersShowFinalizing = false },
                        )
                    }
                    allOrdersOrderId != null -> {
                        val orderDetailVm: OrderDetailViewModel =
                            hiltViewModel(key = "all_order_detail_$allOrdersOrderId")
                        OrderDetailScreen(
                            orderId = allOrdersOrderId!!,
                            viewModel = orderDetailVm,
                            onNavigateBack = { allOrdersOrderId = null },
                            onFinalize = { allOrdersShowFinalizing = true },
                        )
                    }
                    else -> {
                        // Order-level deletion is only available from the Projects tab
                        // (OrdersScreen). The Orders tab is a purchasing-review view;
                        // destructive management actions are intentionally absent here.
                        AllOrdersScreen(
                            viewModel = allOrdersViewModel,
                            onOrderSelected = { orderId -> allOrdersOrderId = orderId },
                        )
                    }
                }
            }

            AppTab.SETTINGS -> {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}
