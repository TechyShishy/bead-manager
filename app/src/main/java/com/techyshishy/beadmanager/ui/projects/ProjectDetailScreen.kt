package com.techyshishy.beadmanager.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.firestore.ProjectBeadEntry
import java.math.BigDecimal
import java.text.DateFormat

/**
 * Inventory quantities below this many grams are treated as zero-deficit for display and
 * checkbox-enable purposes. Keeps floating-point noise from keeping a bead in the "needs
 * ordering" state.
 */
private const val SUFFICIENT_THRESHOLD_GRAMS = 0.001

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    viewModel: ProjectDetailViewModel,
    onNavigateBack: () -> Unit,
    onViewOrders: (projectId: String, projectName: String) -> Unit,
    onAddToOrder: (selectedCodes: Set<String>) -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.initialize(projectId) }

    val project by viewModel.project.collectAsState()
    val orderCount by viewModel.orderCount.collectAsState()
    val activeOrders by viewModel.activeOrders.collectAsState()
    val inventoryGrams by viewModel.inventoryGrams.collectAsState()
    val activeOrderStatus by viewModel.activeOrderStatus.collectAsState()

    val beads = project?.beads ?: emptyList()

    var checkedCodes by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var deleteTarget by remember { mutableStateOf<ProjectBeadEntry?>(null) }
    var detachTarget by remember { mutableStateOf<OrderEntry?>(null) }

    // Drop checked codes that were removed from the bead list.
    LaunchedEffect(beads) {
        val validCodes = beads.map { it.beadCode }.toSet()
        checkedCodes = checkedCodes.intersect(validCodes)
    }

    // Drop checked codes whose inventory now covers the target — no order needed.
    LaunchedEffect(inventoryGrams, beads) {
        val sufficientCodes = beads
            .filter { bead ->
                val deficit = (bead.targetGrams - (inventoryGrams[bead.beadCode] ?: 0.0))
                    .coerceAtLeast(0.0)
                    .let { if (it < SUFFICIENT_THRESHOLD_GRAMS) 0.0 else it }
                deficit == 0.0
            }
            .map { it.beadCode }
            .toSet()
        checkedCodes = checkedCodes - sufficientCodes
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "…") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    // View orders — badge shows order count when > 0
                    IconButton(
                        onClick = {
                            val p = project ?: return@IconButton
                            onViewOrders(p.projectId, p.name)
                        },
                    ) {
                        if (orderCount > 0) {
                            BadgedBox(badge = { Badge { Text(orderCount.toString()) } }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.view_orders))
                            }
                        } else {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.view_orders))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (checkedCodes.isNotEmpty()) {
                        onAddToOrder(checkedCodes)
                    }
                },
                expanded = checkedCodes.isNotEmpty(),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_to_order)) },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        if (beads.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.no_beads),
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
                if (activeOrders.isNotEmpty()) {
                    item(key = "active_orders_header") {
                        Text(
                            text = stringResource(R.string.active_orders_header),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp,
                            ),
                        )
                    }
                    items(activeOrders, key = { "order_${it.orderId}" }) { order ->
                        ActiveOrderRow(
                            order = order,
                            onDetach = { detachTarget = order },
                        )
                        HorizontalDivider()
                    }
                    item(key = "active_orders_spacer") {
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(beads, key = { it.beadCode }) { bead ->
                    ProjectBeadRow(
                        bead = bead,
                        inventoryGrams = inventoryGrams[bead.beadCode] ?: 0.0,
                        activeOrderStatus = activeOrderStatus[bead.beadCode],
                        checked = bead.beadCode in checkedCodes,
                        onCheckedChange = { checked ->
                            checkedCodes = if (checked) {
                                checkedCodes + bead.beadCode
                            } else {
                                checkedCodes - bead.beadCode
                            }
                        },
                        onDelete = { deleteTarget = bead },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    deleteTarget?.let { bead ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.remove_bead)) },
            text = { Text(stringResource(R.string.confirm_remove_bead, bead.beadCode)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBead(bead.beadCode)
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

    detachTarget?.let { order ->
        AlertDialog(
            onDismissRequest = { detachTarget = null },
            title = { Text(stringResource(R.string.confirm_detach_project_title)) },
            text = { Text(stringResource(R.string.confirm_detach_project_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.detachProject(order.orderId)
                    detachTarget = null
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { detachTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ActiveOrderRow(
    order: OrderEntry,
    onDetach: () -> Unit,
) {
    val isFinalized = remember(order) {
        order.items.any {
            val s = OrderItemStatus.fromFirestore(it.status)
            s == OrderItemStatus.FINALIZED || s == OrderItemStatus.ORDERED || s == OrderItemStatus.RECEIVED
        }
    }
    val dateLabel = order.createdAt?.let { ts ->
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(ts.toDate())
    } ?: "…"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.order_created, dateLabel),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDetach, enabled = !isFinalized) {
            Icon(
                Icons.Filled.LinkOff,
                contentDescription = stringResource(
                    if (isFinalized) R.string.detach_project_locked
                    else R.string.confirm_detach_project_title
                ),
                // M3 IconButton does not automatically apply disabled alpha to an explicit
                // tint — the disabled appearance must be set manually here.
                tint = if (isFinalized) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                       else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ProjectBeadRow(
    bead: ProjectBeadEntry,
    inventoryGrams: Double,
    activeOrderStatus: OrderItemStatus?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    // Sufficient means inventory already covers the target — no order needed.
    val progress = if (bead.targetGrams > 0.0) {
        (inventoryGrams / bead.targetGrams).coerceIn(0.0, 1.0).toFloat()
    } else 0f

    val deficit = (bead.targetGrams - inventoryGrams).coerceAtLeast(0.0)
        .let { if (it < SUFFICIENT_THRESHOLD_GRAMS) 0.0 else it }
    val inventorySufficient = deficit == 0.0
    val invStr = BigDecimal.valueOf(inventoryGrams).stripTrailingZeros().toPlainString()
    val deficitStr = BigDecimal.valueOf(deficit).stripTrailingZeros().toPlainString()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = !inventorySufficient,
                modifier = Modifier.size(40.dp),
            )

            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = bead.beadCode,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (inventorySufficient) {
                        Text(
                            text = stringResource(R.string.bead_target_met),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    text = if (deficit > 0.0) {
                        stringResource(R.string.bead_in_stock_deficit, invStr, deficitStr)
                    } else {
                        stringResource(R.string.bead_in_stock_sufficient, invStr)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (deficit > 0.0) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.tertiary,
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.remove_bead),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Box(modifier = Modifier.padding(start = 44.dp, end = 48.dp)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (inventorySufficient) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
            )
        }
        if (activeOrderStatus != null) {
            Text(
                text = when (activeOrderStatus) {
                    OrderItemStatus.ORDERED   -> stringResource(R.string.bead_order_status_ordered)
                    OrderItemStatus.FINALIZED -> stringResource(R.string.bead_order_status_pending)
                    OrderItemStatus.PENDING   -> stringResource(R.string.bead_order_status_pending)
                    OrderItemStatus.RECEIVED,
                    OrderItemStatus.SKIPPED   -> "" // unreachable: filtered by ViewModel
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (activeOrderStatus) {
                    OrderItemStatus.ORDERED   -> MaterialTheme.colorScheme.primary
                    OrderItemStatus.FINALIZED -> MaterialTheme.colorScheme.onSurfaceVariant
                    OrderItemStatus.PENDING   -> MaterialTheme.colorScheme.onSurfaceVariant
                    OrderItemStatus.RECEIVED,
                    OrderItemStatus.SKIPPED   -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 44.dp, top = 2.dp, end = 48.dp),
            )
        }
    }
}
