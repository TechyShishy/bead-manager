package com.techyshishy.beadmanager.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import java.math.BigDecimal
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    orderId: String,
    viewModel: OrderDetailViewModel,
    onNavigateBack: () -> Unit,
    onFinalize: () -> Unit,
) {
    LaunchedEffect(orderId) { viewModel.initialize(orderId) }

    val order by viewModel.order.collectAsState()
    val hasPendingItems = order?.items?.any { it.status == OrderItemStatus.PENDING.firestoreValue } == true
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<OrderItemEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val dateLabel = order?.createdAt?.let { ts ->
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(ts.toDate())
                    } ?: "…"
                    Text(stringResource(R.string.order_created, dateLabel))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onFinalize,
                        enabled = hasPendingItems,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.finalize_order),
                            tint = if (hasPendingItems) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_item))
            }
        },
    ) { innerPadding ->
        val currentOrder = order
        if (currentOrder == null || currentOrder.items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.no_items),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val sortedItems = currentOrder.items.sortedBy { it.beadCode }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(sortedItems, key = { "${it.beadCode}_${it.vendorKey}_${it.packGrams}" }) { item ->
                    OrderItemRow(
                        item = item,
                        onMarkReceived = { viewModel.markItemReceived(item) },
                        onRevertReceived = { viewModel.revertItemReceived(item) },
                        onUpdateStatus = { newStatus -> viewModel.updateItemStatus(item, newStatus) },
                        onRemove = { removeTarget = item },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }

    if (showAddSheet) {
        AddItemBottomSheet(
            onAdd = { items ->
                viewModel.addItems(items)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }

    removeTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.remove_item)) },
            text = { Text(stringResource(R.string.confirm_remove_item, item.beadCode)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeItem(item)
                    removeTarget = null
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderItemRow(
    item: OrderItemEntry,
    onMarkReceived: () -> Unit,
    onRevertReceived: () -> Unit,
    onUpdateStatus: (OrderItemStatus) -> Unit,
    onRemove: () -> Unit,
) {
    val isVendorless = item.vendorKey.isBlank()
    val status = OrderItemStatus.fromFirestore(item.status)
    val packLabel = formatPackLabel(item)

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
                    text = packLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isVendorless) StatusBadge(status)
            IconButton(onClick = onRemove, enabled = !item.appliedToInventory) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.remove_item),
                    tint = if (!item.appliedToInventory)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isVendorless) {
                when (status) {
                    OrderItemStatus.PENDING -> {
                        SuggestionChip(
                            onClick = { onUpdateStatus(OrderItemStatus.ORDERED) },
                            label = { Text(stringResource(R.string.mark_ordered)) },
                        )
                        SuggestionChip(
                            onClick = { onUpdateStatus(OrderItemStatus.SKIPPED) },
                            label = { Text(stringResource(R.string.mark_skipped)) },
                        )
                    }
                    OrderItemStatus.ORDERED -> {
                        SuggestionChip(
                            onClick = onMarkReceived,
                            label = { Text(stringResource(R.string.mark_received)) },
                        )
                        SuggestionChip(
                            onClick = { onUpdateStatus(OrderItemStatus.PENDING) },
                            label = { Text(stringResource(R.string.revert_pending)) },
                        )
                        SuggestionChip(
                            onClick = { onUpdateStatus(OrderItemStatus.SKIPPED) },
                            label = { Text(stringResource(R.string.mark_skipped)) },
                        )
                    }
                    OrderItemStatus.SKIPPED -> {
                        SuggestionChip(
                            onClick = { onUpdateStatus(OrderItemStatus.PENDING) },
                            label = { Text(stringResource(R.string.revert_pending)) },
                        )
                    }
                    OrderItemStatus.RECEIVED -> {
                        SuggestionChip(
                            onClick = onRevertReceived,
                            label = { Text(stringResource(R.string.revert_pending)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: OrderItemStatus) {
    val (label, containerColor, contentColor) = when (status) {
        OrderItemStatus.PENDING -> Triple(
            stringResource(R.string.status_pending),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OrderItemStatus.ORDERED -> Triple(
            stringResource(R.string.status_ordered),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        OrderItemStatus.RECEIVED -> Triple(
            stringResource(R.string.status_received),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        OrderItemStatus.SKIPPED -> Triple(
            stringResource(R.string.status_skipped),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    FilterChip(
        selected = false,
        onClick = {},
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = false,
            borderColor = Color.Transparent,
        ),
    )
}

private fun formatPackLabel(item: OrderItemEntry): String {
    if (item.vendorKey.isBlank()) {
        val targetStr = BigDecimal.valueOf(item.targetGrams).stripTrailingZeros().toPlainString()
        return "Target: ${targetStr}g — vendor not yet selected"
    }
    val gramsStr = BigDecimal.valueOf(item.packGrams).stripTrailingZeros().toPlainString()
    val targetStr = BigDecimal.valueOf(item.targetGrams).stripTrailingZeros().toPlainString()
    return "${item.quantityUnits} × ${gramsStr}g  (target ${targetStr}g)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemBottomSheet(
    onAdd: (items: List<OrderItemEntry>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var beadCodeInput by rememberSaveable { mutableStateOf("") }
    var targetGramsInput by rememberSaveable { mutableStateOf("") }

    val normalizedCode = beadCodeInput.uppercase().trim()
    val targetGrams = targetGramsInput.toDoubleOrNull()?.takeIf { it > 0.0 }

    val canAdd = normalizedCode.isNotBlank() && targetGrams != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.add_item),
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = beadCodeInput,
                onValueChange = { beadCodeInput = it },
                label = { Text(stringResource(R.string.bead_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = targetGramsInput,
                onValueChange = { targetGramsInput = it },
                label = { Text(stringResource(R.string.target_grams)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                suffix = { Text("g") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        if (canAdd) {
                            onAdd(listOf(
                                OrderItemEntry(
                                    beadCode = normalizedCode,
                                    vendorKey = "",
                                    targetGrams = targetGrams!!,
                                    packGrams = 0.0,
                                    quantityUnits = 0,
                                )
                            ))
                        }
                    },
                    enabled = canAdd,
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }
}
