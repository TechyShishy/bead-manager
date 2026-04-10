package com.techyshishy.beadmanager.ui.orders

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.seed.CatalogSeeder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalizeOrderScreen(
    orderId: String,
    viewModel: FinalizeOrderViewModel,
    onNavigateBack: () -> Unit,
) {
    LaunchedEffect(orderId) { viewModel.initiate(orderId) }

    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.finalize_order)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            is FinalizeOrderViewModel.UiState.Checking -> CheckingContent(
                modifier = Modifier.padding(innerPadding),
            )

            is FinalizeOrderViewModel.UiState.Success -> SuccessContent(
                items = state.items,
                modifier = Modifier.padding(innerPadding),
            )

            is FinalizeOrderViewModel.UiState.UnavailableError -> UnavailableContent(
                beadCodes = state.beadCodes,
                onGoBack = onNavigateBack,
                modifier = Modifier.padding(innerPadding),
            )

            is FinalizeOrderViewModel.UiState.ConnectivityError -> ErrorContent(
                message = stringResource(R.string.finalize_connectivity_error),
                onRetry = { viewModel.initiate(orderId) },
                modifier = Modifier.padding(innerPadding),
            )

            is FinalizeOrderViewModel.UiState.ScrapingError -> ErrorContent(
                message = stringResource(R.string.finalize_scraping_error),
                onRetry = { viewModel.initiate(orderId) },
                modifier = Modifier.padding(innerPadding),
            )

            is FinalizeOrderViewModel.UiState.UnknownError -> ErrorContent(
                message = stringResource(R.string.finalize_unknown_error),
                onRetry = { viewModel.initiate(orderId) },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun CheckingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.finalize_checking_prices),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuccessContent(
    items: List<FinalizedItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.finalize_no_pending_items),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val byVendor = items.groupBy { it.vendorKey }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        byVendor.forEach { (vendorKey, vendorItems) ->
            item(key = "header_$vendorKey") {
                VendorFinalizeHeader(vendorKey)
            }
            items(vendorItems, key = { "${it.beadCode}_${it.vendorKey}_${it.packGrams}" }) { item ->
                FinalizedItemRow(item)
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun UnavailableContent(
    beadCodes: List<String>,
    onGoBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.finalize_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.finalize_unavailable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        beadCodes.forEach { code ->
            Text(
                text = "\u2022 $code",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onGoBack) {
            Text(stringResource(R.string.go_back))
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
    }
}

@Composable
private fun VendorFinalizeHeader(vendorKey: String) {
    val displayName = CatalogSeeder.VENDOR_DISPLAY_NAMES[vendorKey] ?: vendorKey
    Text(
        text = displayName,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    HorizontalDivider()
}

@Composable
private fun FinalizedItemRow(item: FinalizedItem) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.beadCode,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(
                        R.string.finalize_pack_label,
                        item.quantityUnits,
                        item.packGramsLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.priceCents != null) {
                val dollars = item.priceCents / 100
                val cents = item.priceCents % 100
                Text(
                    text = "$${dollars}.${cents.toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.fetchFailed) {
                SuggestionChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.finalize_price_unavailable)) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
            if (item.url.isNotBlank()) {
                SuggestionChip(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                        )
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.finalize_open_vendor))
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.OpenInBrowser,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        }
    }
}
