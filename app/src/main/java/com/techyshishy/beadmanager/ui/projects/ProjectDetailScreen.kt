package com.techyshishy.beadmanager.ui.projects

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.model.ProjectBeadEntry
import com.techyshishy.beadmanager.data.model.effectiveDeficitFor
import com.techyshishy.beadmanager.domain.ExportResult
import java.math.BigDecimal
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    viewModel: ProjectDetailViewModel,
    onNavigateBack: () -> Unit,
    onAddToOrder: (selectedCodes: Set<String>) -> Unit,
    onAddBeadFromCatalog: () -> Unit,
    onReplaceBeadFromCatalog: (oldCode: String) -> Unit,
    onPinAllToComparison: (List<String>) -> Unit,
    onViewInCatalog: (String) -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.initialize(projectId) }

    val project by viewModel.project.collectAsState()
    val beads by viewModel.beads.collectAsState()
    val activeOrders by viewModel.activeOrders.collectAsState()
    val inventoryEntries by viewModel.inventoryEntries.collectAsState()
    val globalThreshold by viewModel.globalThreshold.collectAsState()
    val activeOrderStatus by viewModel.activeOrderStatus.collectAsState()
    val beadLookup by viewModel.beadLookup.collectAsState()

    val isGridBacked = project?.rowCount?.let { it > 0 } == true
    val hasBeads = project?.colorMapping?.isNotEmpty() == true

    val deficitCodes = remember(beads, inventoryEntries, globalThreshold) {
        beads
            .filter { effectiveDeficitFor(it, inventoryEntries[it.beadCode], globalThreshold) > 0.0 }
            .mapTo(mutableSetOf()) { it.beadCode }
    }

    var deleteTarget by remember { mutableStateOf<ProjectBeadEntry?>(null) }
    var detachTarget by remember { mutableStateOf<OrderEntry?>(null) }
    var exportErrorMessage by remember { mutableStateOf<String?>(null) }

    var renameMode by rememberSaveable { mutableStateOf(false) }
    var renameInput by rememberSaveable { mutableStateOf("") }
    var renameError by rememberSaveable { mutableStateOf(false) }
    val renameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(renameMode) {
        if (renameMode) renameFocusRequester.requestFocus()
    }

    BackHandler(enabled = renameMode) {
        renameMode = false
        renameError = false
    }
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

    val beadAlreadyInProjectMessage = stringResource(R.string.bead_already_in_project)
    LaunchedEffect(Unit) {
        viewModel.addBeadEvents.collect { event ->
            when (event) {
                is AddBeadEvent.AlreadyPresent ->
                    snackbarHostState.showSnackbar(beadAlreadyInProjectMessage)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (renameMode) {
                        OutlinedTextField(
                            value = renameInput,
                            onValueChange = { renameInput = it; renameError = false },
                            singleLine = true,
                            isError = renameError,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (renameInput.isBlank()) {
                                    renameError = true
                                } else {
                                    viewModel.rename(renameInput)
                                    renameMode = false
                                    renameError = false
                                }
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(renameFocusRequester),
                        )
                    } else {
                        Text(
                            text = project?.name ?: "…",
                            modifier = Modifier.clickable {
                                renameInput = project?.name ?: ""
                                renameError = false
                                renameMode = true
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (renameMode) {
                            renameMode = false
                            renameError = false
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    if (renameMode) {
                        IconButton(onClick = {
                            if (renameInput.isBlank()) {
                                renameError = true
                            } else {
                                viewModel.rename(renameInput)
                                renameMode = false
                                renameError = false
                            }
                        }) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.rename_confirm),
                            )
                        }
                    } else {
                        if (deficitCodes.isNotEmpty()) {
                            IconButton(onClick = { onAddToOrder(deficitCodes) }) {
                                Icon(
                                    Icons.Filled.ShoppingCart,
                                    contentDescription = stringResource(R.string.add_to_order),
                                )
                            }
                        }
                        IconButton(onClick = onAddBeadFromCatalog) {
                            Icon(
                                Icons.Filled.LibraryAdd,
                                contentDescription = stringResource(R.string.add_bead_from_catalog),
                            )
                        }
                        if (hasBeads) {
                            IconButton(onClick = {
                                onPinAllToComparison(beads.map { it.beadCode })
                            }) {
                                Icon(
                                    Icons.Outlined.PushPin,
                                    contentDescription = stringResource(R.string.pin_all_to_comparison),
                                )
                            }
                            IconButton(onClick = {
                                exportLauncher.launch("${project?.name ?: "project"}.rgp")
                            }) {
                                Icon(
                                    Icons.Filled.FileDownload,
                                    contentDescription = stringResource(R.string.export_rgp),
                                )
                            }
                        }
                    }
                },
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
                        onReplace = { onReplaceBeadFromCatalog(bead.beadCode) },
                        onDelete = ({ deleteTarget = bead }).takeUnless { isGridBacked && bead.targetGrams > 0.0 },
                        onViewInCatalog = { onViewInCatalog(bead.beadCode) },
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
    onReplace: () -> Unit,
    onDelete: (() -> Unit)?,
    onViewInCatalog: () -> Unit,
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
            .clickable(
                onClickLabel = stringResource(R.string.view_in_catalog),
                onClick = onViewInCatalog,
            )
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
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

            IconButton(onClick = onReplace) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = stringResource(R.string.replace_bead_from_catalog),
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

        Box(modifier = Modifier.padding(start = 60.dp, end = 48.dp)) {
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
                modifier = Modifier.padding(start = 60.dp, top = 2.dp, end = 48.dp),
            )
        }
    }
}
