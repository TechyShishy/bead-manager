package com.techyshishy.beadmanager.ui.lowstock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.model.AllOrderItem
import kotlinx.coroutines.launch
import java.text.DateFormat

/**
 * Order picker launched from [LowStockScreen] when the user has selected low-stock beads and
 * wants to add them to an order.
 *
 * Two paths:
 *  - Tap a row  → confirm dialog → [onImportIntoOrder] → navigate to that order's detail.
 *  - Tap the FAB → [onNewOrder] → navigate to the newly created restock order's detail.
 *
 * Both operations are performed outside this screen (in callbacks provided by
 * [com.techyshishy.beadmanager.ui.adaptive.AdaptiveScaffold]) so that order-creation logic
 * stays in [LowStockAddToOrderViewModel] rather than being duplicated here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LowStockAddToOrderScreen(
    viewModel: LowStockAddToOrderViewModel,
    onNavigateBack: () -> Unit,
    /** Creates a new restock order; returns the new orderId or null on failure. */
    onNewOrder: suspend () -> String?,
    /** Appends the selected beads into an existing order [orderId].
     * Returns `true` if the order was modified; `false` if all beads were already at threshold.
     */
    onImportIntoOrder: suspend (orderId: String) -> Boolean,
    /** Navigate to the order detail screen for [orderId]. */
    onNavigateToOrder: (orderId: String) -> Unit,
) {
    val eligibleOrders by viewModel.eligibleOrders.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val nothingAddedMessage = stringResource(R.string.low_stock_append_nothing_added)

    var confirmTarget by remember { mutableStateOf<AllOrderItem?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_to_order_screen_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!busy) {
                        scope.launch {
                            busy = true
                            try {
                                val orderId = onNewOrder()
                                if (orderId != null) onNavigateToOrder(orderId)
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                expanded = true,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_restock_order)) },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        if (eligibleOrders.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.no_restock_eligible_orders),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(eligibleOrders, key = { it.order.orderId }) { item ->
                    RestockEligibleOrderRow(
                        item = item,
                        onClick = { confirmTarget = item },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    confirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text(stringResource(R.string.add_to_existing_order_confirm_title)) },
            text = { Text(stringResource(R.string.add_restock_to_existing_order_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTarget = null
                        if (!busy) {
                            scope.launch {
                                busy = true
                                try {
                                    val modified = onImportIntoOrder(target.order.orderId)
                                    if (modified) {
                                        onNavigateToOrder(target.order.orderId)
                                    } else {
                                        snackbarHostState.showSnackbar(nothingAddedMessage)
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RestockEligibleOrderRow(
    item: AllOrderItem,
    onClick: () -> Unit,
) {
    val order = item.order
    val itemCount = order.items.size
    val receivedCount = order.items.count {
        OrderItemStatus.fromFirestore(it.status) == OrderItemStatus.RECEIVED
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val dateLabel = order.createdAt?.let { ts ->
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(ts.toDate())
            } ?: "…"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.order_created, dateLabel),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(8.dp))
                if (itemCount > 0) {
                    Text(
                        text = stringResource(R.string.order_progress, receivedCount, itemCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.projectNames.isNotEmpty()) {
                Text(
                    text = item.projectNames.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.all_orders_no_project),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
