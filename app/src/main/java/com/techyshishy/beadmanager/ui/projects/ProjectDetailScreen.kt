package com.techyshishy.beadmanager.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.firestore.ProjectBeadEntry
import com.techyshishy.beadmanager.domain.ExportResult
import java.math.BigDecimal
import java.text.DateFormat
import kotlin.math.max

/**
 * Inventory quantities below this many grams are treated as zero-deficit for display and
 * checkbox-enable purposes. Keeps floating-point noise from keeping a bead in the "needs
 * ordering" state.
 */
private const val SUFFICIENT_THRESHOLD_GRAMS = 0.001

/** Resolves the effective minimum-stock threshold for a bead. */
private fun effectiveThresholdFor(entry: InventoryEntry?, globalThreshold: Double): Double =
    if ((entry?.lowStockThresholdGrams ?: 0.0) > 0.0) entry!!.lowStockThresholdGrams
    else globalThreshold

/**
 * Effective deficit including the minimum-stock replenishment amount.
 *
 * Returns max(0, targetGrams + effectiveThreshold - inventoryGrams), floored to 0 when
 * the result is below [SUFFICIENT_THRESHOLD_GRAMS] to suppress floating-point noise.
 */
private fun effectiveDeficitFor(
    bead: ProjectBeadEntry,
    entry: InventoryEntry?,
    globalThreshold: Double,
): Double {
    val inStock = entry?.quantityGrams ?: 0.0
    val threshold = effectiveThresholdFor(entry, globalThreshold)
    val raw = max(0.0, bead.targetGrams + threshold - inStock)
    return if (raw < SUFFICIENT_THRESHOLD_GRAMS) 0.0 else raw
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    viewModel: ProjectDetailViewModel,
    onNavigateBack: () -> Unit,
    onAddToOrder: (selectedCodes: Set<String>) -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.initialize(projectId) }

    val project by viewModel.project.collectAsState()
    val beads by viewModel.beads.collectAsState()
    val activeOrders by viewModel.activeOrders.collectAsState()
    val inventoryEntries by viewModel.inventoryEntries.collectAsState()
    val globalThreshold by viewModel.globalThreshold.collectAsState()
    val activeOrderStatus by viewModel.activeOrderStatus.collectAsState()
    val beadLookup by viewModel.beadLookup.collectAsState()

    val isGridBacked = project?.rows?.isNotEmpty() == true

    var checkedCodes by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var deleteTarget by remember { mutableStateOf<ProjectBeadEntry?>(null) }
    var detachTarget by remember { mutableStateOf<OrderEntry?>(null) }
    var exportErrorMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val exportSuccessMessage = stringResource(R.string.export_rgp_success)
    val exportNoGridMessage = stringResource(R.string.export_rgp_error_no_grid)
    val exportIoErrorMessage = stringResource(R.string.export_rgp_error_io)
    val exportNotFoundMessage = stringResource(R.string.export_rgp_error_not_found)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-rowguide-project")
    ) { uri ->
        if (uri != null) viewModel.exportToRgp(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.exportEvents.collect { result ->
            when (result) {
                is ExportResult.Success ->
                    snackbarHostState.showSnackbar(
                        exportSuccessMessage.format(result.suggestedFilename)
                    )
                is ExportResult.Failure.NoGrid -> exportErrorMessage = exportNoGridMessage
                is ExportResult.Failure.IoError -> exportErrorMessage = exportIoErrorMessage
                is ExportResult.Failure.NotFound -> exportErrorMessage = exportNotFoundMessage
            }
        }
    }

    // Drop checked codes that were removed from the bead list.
    LaunchedEffect(beads) {
        val validCodes = beads.map { it.beadCode }.toSet()
        checkedCodes = checkedCodes.intersect(validCodes)
    }

    // Drop checked codes whose inventory now covers the target including the stock threshold.
    LaunchedEffect(inventoryEntries, globalThreshold, beads) {
        val sufficientCodes = beads
            .filter { bead ->
                effectiveDeficitFor(bead, inventoryEntries[bead.beadCode], globalThreshold) == 0.0
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
                    if (isGridBacked) {
                        IconButton(onClick = {
                            exportLauncher.launch("${project?.name ?: "project"}.rgp")
                        }) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = stringResource(R.string.export_rgp),
                            )
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                item(key = "select_all_beads") {
                    val eligibleCodes = beads
                        .filter { bead ->
                            effectiveDeficitFor(
                                bead,
                                inventoryEntries[bead.beadCode],
                                globalThreshold,
                            ) > 0.0
                        }
                        .map { it.beadCode }
                        .toSet()
                    val selectedEligible = checkedCodes.intersect(eligibleCodes)
                    val triState = when (selectedEligible.size) {
                        0 -> ToggleableState.Off
                        eligibleCodes.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    val toggle = {
                        checkedCodes = if (triState == ToggleableState.On) emptySet()
                        else eligibleCodes
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {}
                            .clickable(onClick = toggle)
                            .padding(start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(
                            state = triState,
                            onClick = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.select_all),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HorizontalDivider()
                }
                items(beads, key = { it.beadCode }) { bead ->
                    val catalogBead = beadLookup[bead.beadCode]
                    val entry = inventoryEntries[bead.beadCode]
                    val effectiveDeficit = effectiveDeficitFor(bead, entry, globalThreshold)
                    val isThresholdOnly = activeOrderStatus[bead.beadCode] == null &&
                        effectiveDeficit > 0.0 &&
                        (entry?.quantityGrams ?: 0.0) >= bead.targetGrams
                    ProjectBeadRow(
                        bead = bead,
                        imageUrl = catalogBead?.imageUrl ?: "",
                        hex = catalogBead?.hex ?: "",
                        inventoryGrams = entry?.quantityGrams ?: 0.0,
                        effectiveDeficit = effectiveDeficit,
                        isThresholdOnly = isThresholdOnly,
                        activeOrderStatus = activeOrderStatus[bead.beadCode],
                        checked = bead.beadCode in checkedCodes,
                        onCheckedChange = { checked ->
                            checkedCodes = if (checked) {
                                checkedCodes + bead.beadCode
                            } else {
                                checkedCodes - bead.beadCode
                            }
                        },
                        onDelete = ({ deleteTarget = bead }).takeUnless { isGridBacked && bead.targetGrams > 0.0 },
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

    exportErrorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { exportErrorMessage = null },
            title = { Text(stringResource(R.string.export_rgp_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { exportErrorMessage = null }) {
                    Text(stringResource(android.R.string.ok))
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
    imageUrl: String,
    hex: String,
    inventoryGrams: Double,
    effectiveDeficit: Double,
    isThresholdOnly: Boolean,
    activeOrderStatus: OrderItemStatus?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val progress = if (bead.targetGrams > 0.0) {
        (inventoryGrams / bead.targetGrams).coerceIn(0.0, 1.0).toFloat()
    } else 0f

    val inventorySufficient = effectiveDeficit == 0.0
    val invStr = BigDecimal.valueOf(inventoryGrams).stripTrailingZeros().toPlainString()
    val deficitStr = BigDecimal.valueOf(effectiveDeficit).stripTrailingZeros().toPlainString()
    val hexColor = remember(hex) {
        runCatching { Color(hex.toColorInt()) }.getOrDefault(Color.Gray)
    }

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
            Spacer(Modifier.width(4.dp))

            AsyncImage(
                model = imageUrl.takeIf { it.isNotBlank() },
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small),
                placeholder = ColorPainter(hexColor),
                error = ColorPainter(hexColor),
            )
            Spacer(Modifier.width(8.dp))

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
                    } else if (isThresholdOnly) {
                        Text(
                            text = stringResource(R.string.bead_restocking_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Text(
                    text = if (effectiveDeficit > 0.0) {
                        stringResource(R.string.bead_in_stock_deficit, invStr, deficitStr)
                    } else {
                        stringResource(R.string.bead_in_stock_sufficient, invStr)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (effectiveDeficit > 0.0) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.tertiary,
                )
            }

            onDelete?.let { onDeleteAction ->
                IconButton(onClick = onDeleteAction) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.remove_bead),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Box(modifier = Modifier.padding(start = 104.dp, end = 48.dp)) {
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
                modifier = Modifier.padding(start = 104.dp, top = 2.dp, end = 48.dp),
            )
        }
    }
}
