package com.techyshishy.beadmanager.ui.adaptive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.ui.catalog.CatalogScreen
import com.techyshishy.beadmanager.ui.catalog.CatalogViewModel
import com.techyshishy.beadmanager.ui.detail.BeadDetailPane
import com.techyshishy.beadmanager.ui.detail.BeadDetailViewModel
import com.techyshishy.beadmanager.ui.migration.MigrationViewModel
import com.techyshishy.beadmanager.ui.orders.AddToOrderScreen
import com.techyshishy.beadmanager.ui.orders.AddToOrderViewModel
import com.techyshishy.beadmanager.ui.orders.AllOrdersScreen
import com.techyshishy.beadmanager.ui.orders.AllOrdersViewModel
import com.techyshishy.beadmanager.ui.orders.FinalizeOrderScreen
import com.techyshishy.beadmanager.ui.orders.FinalizeOrderViewModel
import com.techyshishy.beadmanager.ui.orders.OrderDetailScreen
import com.techyshishy.beadmanager.ui.orders.OrderDetailViewModel
import com.techyshishy.beadmanager.ui.projects.ProjectDetailScreen
import com.techyshishy.beadmanager.ui.projects.ProjectDetailViewModel
import com.techyshishy.beadmanager.ui.projects.ProjectInfoScreen
import com.techyshishy.beadmanager.ui.projects.ProjectsScreen
import com.techyshishy.beadmanager.ui.projects.ProjectsViewModel
import com.techyshishy.beadmanager.ui.lowstock.LowStockAddToOrderScreen
import com.techyshishy.beadmanager.ui.lowstock.LowStockAddToOrderViewModel
import com.techyshishy.beadmanager.ui.lowstock.LowStockScreen
import com.techyshishy.beadmanager.ui.lowstock.LowStockViewModel
import com.techyshishy.beadmanager.ui.settings.SettingsScreen
import com.techyshishy.beadmanager.ui.settings.SettingsViewModel

enum class AppTab { CATALOG, PROJECTS, ORDERS, LOW_STOCK, SETTINGS }

@Composable
fun AdaptiveScaffold() {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.CATALOG) }

    val catalogViewModel: CatalogViewModel = hiltViewModel()
    val pinnedCodes by catalogViewModel.pinnedCodes.collectAsState()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val projectsViewModel: ProjectsViewModel = hiltViewModel()
    val allOrdersViewModel: AllOrdersViewModel = hiltViewModel()
    // Triggers one-time data migrations immediately on first composition post-auth.
    hiltViewModel<MigrationViewModel>()

    // Catalog tab: nav state (grid → bead detail).
    var catalogDetailCode by rememberSaveable { mutableStateOf<String?>(null) }
    // Scroll position of the catalog grid — lives here so it survives CatalogScreen leaving
    // the composition when a bead detail pane is shown.
    val catalogGridState = rememberLazyGridState()
    // Scroll position of the Project Info screen — lives here so it survives the composable
    // leaving composition during cross-tab catalog-detail navigation.
    val projectInfoListState = rememberLazyListState()
    // Non-null while catalog-initiated project picker is active; holds the staged bead code.
    var catalogProjectPickerBeadCode by rememberSaveable { mutableStateOf<String?>(null) }
    // Non-null when catalog detail was opened via a tap in ProjectDetailScreen;
    // holds the originating project ID so back navigation can return to that project.
    // Intentionally not cleared on nav-tab tap — mirrors the allOrdersCameFromProjects
    // pattern so that manually switching tabs and then pressing back still routes correctly.
    var catalogDetailReturnProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    // Non-null when catalog detail was opened from ORDERS or LOW_STOCK; holds the tab
    // to return to on back. The projectId path takes precedence when both are set.
    var catalogDetailReturnTab by rememberSaveable { mutableStateOf<AppTab?>(null) }

    // Projects tab: nav state (projects → project detail → add-to-order).
    var ordersProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    // Non-null while the AddToOrderScreen picker is open; holds the checked bead codes.
    var ordersAddToOrderCodes by rememberSaveable { mutableStateOf<Set<String>?>(null) }
    // True while the catalog picker is open for adding a bead to the current project.
    var projectsCatalogPickerMode by rememberSaveable { mutableStateOf(false) }
    // Non-null while the catalog picker is open for replacing a bead; holds the old bead code.
    var projectsCatalogSwapTargetCode by rememberSaveable { mutableStateOf<String?>(null) }
    // True while the Project Info screen is open over the project detail.
    var projectInfoMode by rememberSaveable { mutableStateOf(false) }

    // All-Orders tab: nav state (list → order detail → finalize).
    var allOrdersOrderId by rememberSaveable { mutableStateOf<String?>(null) }
    // Low Stock tab: non-null when the order picker is open; holds selected bead codes.
    var lowStockAddToOrderCodes by rememberSaveable { mutableStateOf<Set<String>?>(null) }
    var allOrdersShowFinalizing by rememberSaveable { mutableStateOf(false) }
    // True when the current order detail was opened via redirect from the Projects tab;
    // back navigation should return to Projects rather than staying in Orders.
    var allOrdersCameFromProjects by rememberSaveable { mutableStateOf(false) }

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
                // allOrdersCameFromProjects is intentionally not cleared here; the flag tracks
                // order provenance, not the current tab. Clearing on tab tap would mean a user
                // who manually taps PROJECTS and returns loses the back-to-Projects behaviour.
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
                selected = currentTab == AppTab.LOW_STOCK,
                onClick = { currentTab = AppTab.LOW_STOCK },
                icon = {
                    Icon(
                        if (currentTab == AppTab.LOW_STOCK) Icons.Filled.Inventory
                        else Icons.Outlined.Inventory,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.low_stock)) },
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
                BackHandler(catalogDetailCode != null) {
                    val returnProjectId = catalogDetailReturnProjectId
                    val returnTab = catalogDetailReturnTab
                    catalogDetailCode = null
                    catalogDetailReturnProjectId = null
                    catalogDetailReturnTab = null
                    when {
                        returnProjectId != null -> {
                            ordersProjectId = returnProjectId
                            currentTab = AppTab.PROJECTS
                        }
                        returnTab != null -> currentTab = returnTab
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    val code = catalogDetailCode
                    if (code != null) {
                        val detailVm: BeadDetailViewModel = hiltViewModel(
                            key = "catalog_detail_$code",
                        )
                        BeadDetailPane(
                            beadCode = code,
                            viewModel = detailVm,
                            onNavigateBack = {
                                val returnProjectId = catalogDetailReturnProjectId
                                val returnTab = catalogDetailReturnTab
                                catalogDetailCode = null
                                catalogDetailReturnProjectId = null
                                catalogDetailReturnTab = null
                                when {
                                    returnProjectId != null -> {
                                        ordersProjectId = returnProjectId
                                        currentTab = AppTab.PROJECTS
                                    }
                                    returnTab != null -> currentTab = returnTab
                                }
                            },
                            onAddToProject = {
                                catalogProjectPickerBeadCode = code
                                currentTab = AppTab.PROJECTS
                            },
                            isPinned = code in pinnedCodes,
                            onPinToggle = { catalogViewModel.togglePin(code) },
                        )
                    } else {
                        CatalogScreen(
                            viewModel = catalogViewModel,
                            onBeadSelected = { selected -> catalogDetailCode = selected },
                            gridState = catalogGridState,
                        )
                    }
                }
            }

            AppTab.PROJECTS -> {
                BackHandler(ordersProjectId != null) {
                    when {
                        projectsCatalogPickerMode -> {
                            projectsCatalogPickerMode = false
                            projectsCatalogSwapTargetCode = null
                        }
                        ordersAddToOrderCodes != null -> ordersAddToOrderCodes = null
                        projectInfoMode -> projectInfoMode = false
                        else -> ordersProjectId = null
                    }
                }
                when {
                    catalogProjectPickerBeadCode != null -> {
                        BackHandler {
                            catalogProjectPickerBeadCode = null
                            currentTab = AppTab.CATALOG
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .statusBarsPadding()
                                        .padding(horizontal = 4.dp),
                                ) {
                                    IconButton(
                                        onClick = {
                                            catalogProjectPickerBeadCode = null
                                            currentTab = AppTab.CATALOG
                                        },
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.picker_cancel),
                                        )
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.picker_add_bead_to_project_hint,
                                            catalogProjectPickerBeadCode!!,
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 4.dp),
                                    )
                                }
                            }
                            Box(modifier = Modifier.consumeWindowInsets(WindowInsets.statusBars)) {
                                ProjectsScreen(
                                    viewModel = projectsViewModel,
                                    onProjectSelected = { projectId, _ ->
                                        projectsViewModel.addBeadToProject(
                                            catalogProjectPickerBeadCode!!,
                                            projectId,
                                        )
                                        catalogProjectPickerBeadCode = null
                                        currentTab = AppTab.CATALOG
                                    },
                                )
                            }
                        }
                    }
                    projectsCatalogPickerMode && ordersProjectId != null -> {
                        // Reuse the same VM instance as the detail screen so addBead() writes
                        // to the correct project.
                        val projectDetailVm: ProjectDetailViewModel =
                            hiltViewModel(key = "project_detail_$ordersProjectId")
                        val pickerProject by projectDetailVm.project.collectAsState()
                        val projectName = pickerProject?.name.orEmpty()
                        val isSwap = projectsCatalogSwapTargetCode != null
                        Column(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .statusBarsPadding()
                                        .padding(horizontal = 4.dp),
                                ) {
                                    IconButton(
                                        onClick = {
                                            projectsCatalogPickerMode = false
                                            projectsCatalogSwapTargetCode = null
                                        },
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.picker_cancel),
                                        )
                                    }
                                    Text(
                                        text = if (isSwap) {
                                            stringResource(R.string.picker_swap_bead_hint, projectName)
                                        } else {
                                            stringResource(R.string.picker_add_bead_hint, projectName)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 4.dp),
                                    )
                                }
                            }
                            Box(modifier = Modifier.consumeWindowInsets(WindowInsets.statusBars)) {
                                CatalogScreen(
                                    viewModel = catalogViewModel,
                                    onBeadSelected = { code ->
                                        val swapTarget = projectsCatalogSwapTargetCode
                                        if (swapTarget != null) {
                                            projectDetailVm.swapBead(swapTarget, code)
                                        } else {
                                            projectDetailVm.addBead(code)
                                        }
                                        projectsCatalogPickerMode = false
                                        projectsCatalogSwapTargetCode = null
                                    },
                                )
                            }
                        }
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
                                allOrdersOrderId = orderId
                                allOrdersCameFromProjects = true
                                currentTab = AppTab.ORDERS
                            },
                        )
                    }
                    projectInfoMode && ordersProjectId != null -> {
                        val projectDetailVm: ProjectDetailViewModel =
                            hiltViewModel(key = "project_detail_$ordersProjectId")
                        ProjectInfoScreen(
                            projectId = ordersProjectId!!,
                            viewModel = projectDetailVm,
                            onNavigateBack = { projectInfoMode = false },
                            listState = projectInfoListState,
                            onViewInCatalog = { beadCode ->
                                catalogDetailCode = beadCode
                                catalogDetailReturnProjectId = ordersProjectId
                                currentTab = AppTab.CATALOG
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
                            },
                            onAddToOrder = { codes -> ordersAddToOrderCodes = codes },
                            onAddBeadFromCatalog = { projectsCatalogPickerMode = true },
                            onReplaceBeadFromCatalog = { oldCode ->
                                projectsCatalogSwapTargetCode = oldCode
                                projectsCatalogPickerMode = true
                            },
                            onPinAllToComparison = { codes ->
                                catalogViewModel.pinAll(codes)
                                catalogDetailCode = null
                                currentTab = AppTab.CATALOG
                            },
                            onViewInCatalog = { beadCode ->
                                catalogDetailCode = beadCode
                                catalogDetailReturnProjectId = ordersProjectId
                                currentTab = AppTab.CATALOG
                            },
                            onViewProjectInfo = { projectInfoMode = true },
                        )
                    }
                    else -> {
                        ProjectsScreen(
                            viewModel = projectsViewModel,
                            onProjectSelected = { projectId, _ ->
                                ordersProjectId = projectId
                            },
                        )
                    }
                }
            }

            AppTab.ORDERS -> {
                // Shared exit logic for both the system back gesture and the TopAppBar ← button.
                // Clearing allOrdersCameFromProjects must happen in both paths — using a single
                // lambda here ensures they stay in sync.
                val onExitOrderDetail: () -> Unit = {
                    allOrdersOrderId = null
                    if (allOrdersCameFromProjects) {
                        allOrdersCameFromProjects = false
                        currentTab = AppTab.PROJECTS
                    }
                }
                BackHandler(allOrdersOrderId != null) {
                    when {
                        allOrdersShowFinalizing -> allOrdersShowFinalizing = false
                        else -> onExitOrderDetail()
                    }
                }
                when {
                    allOrdersShowFinalizing && allOrdersOrderId != null -> {
                        // Keys are prefixed "all_" to keep these VM instances distinct
                        // from any order opened in the same session via a different path.
                        val finalizeVm: FinalizeOrderViewModel =
                            hiltViewModel(key = "all_finalize_$allOrdersOrderId")
                        FinalizeOrderScreen(
                            orderId = allOrdersOrderId!!,
                            viewModel = finalizeVm,
                            onNavigateBack = { allOrdersShowFinalizing = false },
                            onViewInCatalog = { beadCode ->
                                catalogDetailCode = beadCode
                                catalogDetailReturnProjectId = null
                                catalogDetailReturnTab = AppTab.ORDERS
                                currentTab = AppTab.CATALOG
                            },
                        )
                    }
                    allOrdersOrderId != null -> {
                        val orderDetailVm: OrderDetailViewModel =
                            hiltViewModel(key = "all_order_detail_$allOrdersOrderId")
                        OrderDetailScreen(
                            orderId = allOrdersOrderId!!,
                            viewModel = orderDetailVm,
                            onNavigateBack = onExitOrderDetail,
                            onFinalize = { allOrdersShowFinalizing = true },
                            onViewInCatalog = { beadCode ->
                                catalogDetailCode = beadCode
                                catalogDetailReturnProjectId = null
                                catalogDetailReturnTab = AppTab.ORDERS
                                currentTab = AppTab.CATALOG
                            },
                        )
                    }
                    else -> {
                        // The Orders tab is a purchasing-review view; order deletion is
                        // intentionally absent here.
                        AllOrdersScreen(
                            viewModel = allOrdersViewModel,
                            onOrderSelected = { orderId -> allOrdersOrderId = orderId },
                        )
                    }
                }
            }

            AppTab.LOW_STOCK -> {
                val lowStockViewModel: LowStockViewModel = hiltViewModel()
                BackHandler(lowStockAddToOrderCodes != null) { lowStockAddToOrderCodes = null }
                when {
                    lowStockAddToOrderCodes != null -> {
                        val addToOrderVm: LowStockAddToOrderViewModel = hiltViewModel()
                        LowStockAddToOrderScreen(
                            viewModel = addToOrderVm,
                            onNavigateBack = { lowStockAddToOrderCodes = null },
                            onNewOrder = {
                                val codes = lowStockAddToOrderCodes ?: return@LowStockAddToOrderScreen null
                                addToOrderVm.createRestockOrder(codes)
                            },
                            onImportIntoOrder = { orderId ->
                                val codes = lowStockAddToOrderCodes ?: return@LowStockAddToOrderScreen false
                                addToOrderVm.appendToOrder(orderId, codes)
                            },
                            onNavigateToOrder = { orderId ->
                                lowStockAddToOrderCodes = null
                                allOrdersOrderId = orderId
                                currentTab = AppTab.ORDERS
                            },
                        )
                    }
                    else -> {
                        LowStockScreen(
                            viewModel = lowStockViewModel,
                            onAddToOrder = {
                                lowStockAddToOrderCodes = lowStockViewModel.effectiveSelectedCodes.value
                            },
                            onViewInCatalog = { beadCode ->
                                catalogDetailCode = beadCode
                                catalogDetailReturnProjectId = null
                                catalogDetailReturnTab = AppTab.LOW_STOCK
                                currentTab = AppTab.CATALOG
                            },
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
