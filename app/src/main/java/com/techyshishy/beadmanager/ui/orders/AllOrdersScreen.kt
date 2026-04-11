package com.techyshishy.beadmanager.ui.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.model.AllOrderItem
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllOrdersScreen(
    viewModel: AllOrdersViewModel,
    onOrderSelected: (orderId: String) -> Unit,
) {
    val orders by viewModel.orders.collectAsState()
    var deleteTarget by remember { mutableStateOf<AllOrderItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.all_orders_title)) },
            )
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
                    text = stringResource(R.string.all_orders_empty),
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
                items(orders, key = { it.order.orderId }) { item ->
                    AllOrderRow(
                        item = item,
                        onClick = { onOrderSelected(item.order.orderId) },
                        onDelete = { deleteTarget = item },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    deleteTarget?.let { item ->
        val hasActiveItems = remember(item) {
            item.order.items.any {
                val status = OrderItemStatus.fromFirestore(it.status)
                status == OrderItemStatus.PENDING ||
                    status == OrderItemStatus.FINALIZED ||
                    status == OrderItemStatus.ORDERED
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
                    viewModel.deleteOrder(item.order.orderId)
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
private fun AllOrderRow(
    item: AllOrderItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
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
        Column(modifier = Modifier.weight(1f)) {
            val dateLabel = order.createdAt?.let { ts ->
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(ts.toDate())
            } ?: "…"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.order_created, dateLabel),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (itemCount > 0) {
                    Text(
                        text = stringResource(R.string.order_progress, receivedCount, itemCount),
                        style = MaterialTheme.typography.labelMedium,
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
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete_order),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
