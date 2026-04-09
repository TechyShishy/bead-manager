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
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.seed.CatalogSeeder
import java.math.BigDecimal
import java.text.DateFormat
import kotlin.math.roundToInt

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
    val receivedGramsPerBead by viewModel.receivedGramsPerBead.collectAsState()
    val projectTargetGrams by viewModel.projectTargetGrams.collectAsState()
    val hasPendingItems = order?.items?.any { it.status == OrderItemStatus.PENDING.firestoreValue } == true
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<OrderItemEntry?>(null) }
    var selectVendorTarget by remember { mutableStateOf<OrderItemEntry?>(null) }

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
            // Group items by vendorKey; preserve creation order within each group.
            val byVendor = currentOrder.items.groupBy { it.vendorKey }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                byVendor.forEach { (vendorKey, vendorItems) ->
                    item(key = "header_$vendorKey") {
                        VendorSectionHeader(vendorKey)
                    }
                    items(vendorItems, key = { "${it.beadCode}_${it.vendorKey}_${it.packGrams}" }) { item ->
                        OrderItemRow(
                            item = item,
                            onMarkReceived = { viewModel.markItemReceived(item) },
                            onRevertReceived = { viewModel.revertItemReceived(item) },
                            onUpdateStatus = { newStatus -> viewModel.updateItemStatus(item, newStatus) },
                            onRemove = { removeTarget = item },
                            onSelectVendor = { selectVendorTarget = item },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
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

    selectVendorTarget?.let { item ->
        SelectVendorBottomSheet(
            beadCode = item.beadCode,
            targetGrams = item.targetGrams,
            alreadyReceivedGrams = receivedGramsPerBead[item.beadCode] ?: 0.0,
            projectTargetGrams = projectTargetGrams[item.beadCode],
            onAssign = { newItems ->
                viewModel.assignVendor(item.beadCode, newItems)
                selectVendorTarget = null
            },
            onDismiss = { selectVendorTarget = null },
            vendorKeysForBead = { viewModel.vendorKeysForBead(item.beadCode) },
            packsForVendor = { vendorKey -> viewModel.packsForVendor(item.beadCode, vendorKey) },
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

@Composable
private fun VendorSectionHeader(vendorKey: String) {
    val displayName = if (vendorKey.isBlank()) {
        "Needs vendor"
    } else {
        CatalogSeeder.VENDOR_DISPLAY_NAMES[vendorKey] ?: vendorKey
    }
    Text(
        text = displayName,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    HorizontalDivider()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderItemRow(
    item: OrderItemEntry,
    onMarkReceived: () -> Unit,
    onRevertReceived: () -> Unit,
    onUpdateStatus: (OrderItemStatus) -> Unit,
    onRemove: () -> Unit,
    onSelectVendor: () -> Unit,
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
            if (isVendorless) {
                SuggestionChip(
                    onClick = onSelectVendor,
                    label = { Text(stringResource(R.string.select_vendor)) },
                )
            } else {
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

/**
 * Represents one pack size and how many units of it to order.
 */
private data class PackCombination(val packGrams: Double, val quantity: Int)

/**
 * Returns the combination of packs from [availablePacks] that reaches [targetGrams] with
 * minimum waste, and among equal-waste solutions, the fewest total units.
 *
 * Uses unbounded knapsack DP scaled to integers (×10) so that half-gram pack sizes
 * (e.g. 7.5g) become clean integers (75). All known Delica pack sizes are multiples
 * of 0.5g, so the scale factor of 10 is always exact.
 *
 * Returns an empty list if [availablePacks] is empty, [targetGrams] ≤ 0, or the target
 * exceeds the practical ceiling (10 kg).
 */
private fun computeOptimalCombination(
    availablePacks: List<Double>,
    targetGrams: Double,
): List<PackCombination> {
    if (availablePacks.isEmpty() || targetGrams <= 0.0 || targetGrams > 10_000.0) return emptyList()

    val scale = 10
    val targetInt = (targetGrams * scale).roundToInt()
    val packsInt = availablePacks
        .map { (it * scale).roundToInt() }
        .filter { it > 0 }
        .distinct()
    if (packsInt.isEmpty()) return emptyList()

    // DP ceiling: worst-case overshoot is at most one more unit of the largest pack.
    val maxPack = packsInt.max()
    val ceiling = targetInt + maxPack

    val INF = Int.MAX_VALUE / 2
    // dp[i] = minimum units to reach exactly i scaled grams; INF if unreachable.
    val dp = IntArray(ceiling + 1) { INF }
    // parent[i] = scaled pack size used in the last step to reach i.
    val parent = IntArray(ceiling + 1) { -1 }
    dp[0] = 0

    for (i in 1..ceiling) {
        for (pack in packsInt) {
            if (pack <= i && dp[i - pack] < INF) {
                val candidate = dp[i - pack] + 1
                if (candidate < dp[i]) {
                    dp[i] = candidate
                    parent[i] = pack
                }
            }
        }
    }

    // Find the minimum reachable total >= targetInt.
    val reachable = (targetInt..ceiling).firstOrNull { dp[it] < INF } ?: return emptyList()

    // Backtrack through parent[] to count how many of each pack size are used.
    val counts = mutableMapOf<Int, Int>()
    var remaining = reachable
    while (remaining > 0) {
        val p = parent[remaining]
        counts[p] = (counts[p] ?: 0) + 1
        remaining -= p
    }

    // Map scaled integers back to the original Double values (verbatim from VendorPackEntity).
    return counts
        .map { (scaledGrams, qty) ->
            // !! is safe: scaledGrams came from availablePacks via the same scale factor.
            PackCombination(
                packGrams = availablePacks.first { (it * scale).roundToInt() == scaledGrams },
                quantity = qty,
            )
        }
        .sortedByDescending { it.packGrams }  // largest packs first for readability
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

/**
 * Bottom sheet for assigning a vendor to a vendor-less order item.
 *
 * The bead code and target grams are pre-filled from the item and are read-only.
 * An over-target warning is shown when the combination total plus already-received grams
 * would exceed the project target for this bead.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SelectVendorBottomSheet(
    beadCode: String,
    targetGrams: Double,
    alreadyReceivedGrams: Double,
    projectTargetGrams: Double?,
    onAssign: (items: List<OrderItemEntry>) -> Unit,
    onDismiss: () -> Unit,
    vendorKeysForBead: () -> kotlinx.coroutines.flow.Flow<List<String>>,
    packsForVendor: (vendorKey: String) -> kotlinx.coroutines.flow.Flow<List<VendorPackEntity>>,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedVendorKey by rememberSaveable { mutableStateOf<String?>(null) }

    val vendorKeys by vendorKeysForBead().collectAsState(emptyList())
    val packs by packsForVendor(selectedVendorKey ?: "").collectAsState(emptyList())

    LaunchedEffect(vendorKeys) {
        if (selectedVendorKey != null && selectedVendorKey !in vendorKeys) {
            selectedVendorKey = null
        }
    }

    val combination = remember(packs, targetGrams) {
        if (packs.isNotEmpty()) computeOptimalCombination(packs.map { it.grams }, targetGrams)
        else emptyList()
    }

    val totalGrams = combination.sumOf { it.packGrams * it.quantity }
    val overTarget = projectTargetGrams != null &&
        alreadyReceivedGrams + totalGrams > projectTargetGrams + 0.001

    val canAssign = selectedVendorKey != null && combination.isNotEmpty()

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
                text = stringResource(R.string.select_vendor),
                style = MaterialTheme.typography.titleMedium,
            )

            // Bead code — read-only display
            val targetStr = BigDecimal.valueOf(targetGrams).stripTrailingZeros().toPlainString()
            Text(
                text = stringResource(R.string.bead_vendor_target, beadCode, targetStr),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Vendor chips
            if (vendorKeys.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.vendor),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vendorKeys.forEach { key ->
                        val displayName = CatalogSeeder.VENDOR_DISPLAY_NAMES[key] ?: key
                        FilterChip(
                            selected = key == selectedVendorKey,
                            onClick = { selectedVendorKey = key },
                            label = { Text(displayName) },
                        )
                    }
                }
            }

            // Combination preview
            if (combination.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    combination.forEach { c ->
                        val gramsStr = BigDecimal.valueOf(c.packGrams).stripTrailingZeros().toPlainString()
                        Text(
                            text = stringResource(R.string.quantity_units, c.quantity, gramsStr),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val totalStr = BigDecimal.valueOf(totalGrams).stripTrailingZeros().toPlainString()
                    if (totalGrams != targetGrams) {
                        Text(
                            text = "Total: ${totalStr}g (target ${targetStr}g)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (selectedVendorKey != null && packs.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.combination_no_solution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Over-target warning
            if (overTarget && combination.isNotEmpty()) {
                val receivedStr = BigDecimal.valueOf(alreadyReceivedGrams).stripTrailingZeros().toPlainString()
                val projTargetStr = BigDecimal.valueOf(projectTargetGrams!!).stripTrailingZeros().toPlainString()
                Text(
                    text = stringResource(R.string.over_target_warning, receivedStr, projTargetStr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        val vk = selectedVendorKey ?: return@TextButton
                        if (combination.isEmpty()) return@TextButton
                        val newItems = combination.map { c ->
                            OrderItemEntry(
                                beadCode = beadCode,
                                vendorKey = vk,
                                targetGrams = targetGrams,
                                packGrams = c.packGrams,
                                quantityUnits = c.quantity,
                            )
                        }
                        onAssign(newItems)
                    },
                    enabled = canAssign,
                    colors = if (overTarget) {
                        androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        androidx.compose.material3.ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }
}
