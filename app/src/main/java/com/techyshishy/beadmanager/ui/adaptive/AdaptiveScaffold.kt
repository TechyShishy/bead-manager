package com.techyshishy.beadmanager.ui.adaptive

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.ui.catalog.CatalogScreen
import com.techyshishy.beadmanager.ui.catalog.CatalogViewModel
import com.techyshishy.beadmanager.ui.detail.BeadDetailPane
import com.techyshishy.beadmanager.ui.detail.BeadDetailViewModel
import com.techyshishy.beadmanager.ui.inventory.InventoryScreen
import com.techyshishy.beadmanager.ui.inventory.InventoryViewModel
import com.techyshishy.beadmanager.ui.lowstock.LowStockScreen
import com.techyshishy.beadmanager.ui.lowstock.LowStockViewModel
import com.techyshishy.beadmanager.ui.migration.MigrationViewModel
import com.techyshishy.beadmanager.ui.settings.SettingsScreen
import com.techyshishy.beadmanager.ui.settings.SettingsViewModel

enum class AppTab { CATALOG, INVENTORY, LOW_STOCK, SETTINGS }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdaptiveScaffold() {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.CATALOG) }
    val scope = rememberCoroutineScope()

    val catalogViewModel: CatalogViewModel = hiltViewModel()
    val inventoryViewModel: InventoryViewModel = hiltViewModel()
    val lowStockViewModel: LowStockViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    // Triggers one-time data migrations immediately on first composition post-auth.
    hiltViewModel<MigrationViewModel>()

    val lowStockCount by lowStockViewModel.lowStockBeads.collectAsState()

    // Separate list-detail navigators keep scaffold state independent per tab.
    val catalogNavigator = rememberListDetailPaneScaffoldNavigator<String>()
    val inventoryNavigator = rememberListDetailPaneScaffoldNavigator<String>()

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
                selected = currentTab == AppTab.INVENTORY,
                onClick = { currentTab = AppTab.INVENTORY },
                icon = {
                    Icon(
                        if (currentTab == AppTab.INVENTORY) Icons.Filled.Inventory else Icons.Outlined.Inventory,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.my_beads)) },
            )
            item(
                selected = currentTab == AppTab.LOW_STOCK,
                onClick = { currentTab = AppTab.LOW_STOCK },
                icon = {
                    BadgedBox(
                        badge = {
                            if (lowStockCount.isNotEmpty()) {
                                Badge { Text("${lowStockCount.size}") }
                            }
                        },
                    ) {
                        Icon(
                            if (currentTab == AppTab.LOW_STOCK) Icons.Filled.ShoppingCart else Icons.Outlined.ShoppingCart,
                            contentDescription = null,
                        )
                    }
                },
                label = { Text(stringResource(R.string.reorder)) },
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
                // Provide a BeadDetailViewModel scoped to the selected code via the
                // scaffold navigator's content key so Hilt reuses the VM per bead.
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
                                )
                            }
                        }
                    },
                )
            }

            AppTab.INVENTORY -> {
                BackHandler(inventoryNavigator.canNavigateBack()) {
                    scope.launch { inventoryNavigator.navigateBack() }
                }
                ListDetailPaneScaffold(
                    directive = inventoryNavigator.scaffoldDirective.copy(maxHorizontalPartitions = 1),
                    value = inventoryNavigator.scaffoldValue,
                    listPane = {
                        AnimatedPane {
                            InventoryScreen(
                                viewModel = inventoryViewModel,
                                onBeadSelected = { code ->
                                    inventoryNavigator.navigateTo(
                                        ListDetailPaneScaffoldRole.Detail,
                                        code,
                                    )
                                },
                            )
                        }
                    },
                    detailPane = {
                        AnimatedPane {
                            inventoryNavigator.currentDestination?.content?.let { code ->
                                val detailVm: BeadDetailViewModel = hiltViewModel(
                                    key = "inventory_detail_$code",
                                )
                                BeadDetailPane(
                                    beadCode = code,
                                    viewModel = detailVm,
                                    onNavigateBack = if (inventoryNavigator.canNavigateBack()) {
                                        { scope.launch { inventoryNavigator.navigateBack() } }
                                    } else null,
                                )
                            }
                        }
                    },
                )
            }

            AppTab.LOW_STOCK -> {
                LowStockScreen(
                    viewModel = lowStockViewModel,
                    onBeadSelected = { code ->
                        // Navigate into the catalog tab and open the detail pane.
                        currentTab = AppTab.CATALOG
                        catalogNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, code)
                    },
                )
            }

            AppTab.SETTINGS -> {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}
