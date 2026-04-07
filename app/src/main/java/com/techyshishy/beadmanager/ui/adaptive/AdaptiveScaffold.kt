package com.techyshishy.beadmanager.ui.adaptive

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
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
import com.techyshishy.beadmanager.ui.migration.MigrationViewModel
import com.techyshishy.beadmanager.ui.settings.SettingsScreen
import com.techyshishy.beadmanager.ui.settings.SettingsViewModel

enum class AppTab { CATALOG, SETTINGS }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdaptiveScaffold() {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.CATALOG) }
    val scope = rememberCoroutineScope()

    val catalogViewModel: CatalogViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    // Triggers one-time data migrations immediately on first composition post-auth.
    hiltViewModel<MigrationViewModel>()

    // Separate list-detail navigators keep scaffold state independent per tab.
    val catalogNavigator = rememberListDetailPaneScaffoldNavigator<String>()

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

            AppTab.SETTINGS -> {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}
