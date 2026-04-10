package com.techyshishy.beadmanager.ui.projects

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    projectId: String,
    projectName: String,
    viewModel: OrdersViewModel,
    onOrderSelected: (orderId: String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.initialize(projectId) }

    val orders by viewModel.orders.collectAsState()
    var deleteTarget by remember { mutableStateOf<OrderEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectName) },
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.createOrder() },
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_order))
            }
        },
    ) { innerPadding ->
        if (orders.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.no_orders),
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
                items(orders, key = { it.orderId }) { order ->
                    OrderRow(
                        order = order,
                        onClick = { onOrderSelected(order.orderId) },
                        onDelete = { deleteTarget = order },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    deleteTarget?.let { order ->
        val hasActiveItems = remember(order) {
            order.items.any {
                val status = OrderItemStatus.fromFirestore(it.status)
                status == OrderItemStatus.PENDING || status == OrderItemStatus.FINALIZED || status == OrderItemStatus.ORDERED
            }
        }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_order)) },
            text = {
                Text(
                    stringResource(
                        if (hasActiveItems) R.string.confirm_delete_order_with_active_items
                        else R.string.confirm_delete_order
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteOrder(order.orderId)
                    deleteTarget = null
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun OrderRow(
    order: OrderEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val itemCount = order.items.size
    val receivedCount = order.items.count {
        OrderItemStatus.fromFirestore(it.status) == OrderItemStatus.RECEIVED
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val dateLabel = order.createdAt?.let { ts ->
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(ts.toDate())
            } ?: "…"
            Text(
                text = stringResource(R.string.order_created, dateLabel),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (itemCount > 0) {
                Text(
                    text = stringResource(R.string.order_progress, receivedCount, itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.no_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete_order),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
