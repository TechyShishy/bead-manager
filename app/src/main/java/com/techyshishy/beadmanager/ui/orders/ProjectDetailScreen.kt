package com.techyshishy.beadmanager.ui.orders

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.ProjectBeadEntry
import kotlinx.coroutines.launch
import java.math.BigDecimal

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
    onOrderCreated: (orderId: String) -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.initialize(projectId) }

    val project by viewModel.project.collectAsState()
    val receivedGrams by viewModel.receivedGrams.collectAsState()
    val orderCount by viewModel.orderCount.collectAsState()
    val inventoryGrams by viewModel.inventoryGrams.collectAsState()
    val scope = rememberCoroutineScope()

    val beads = project?.beads ?: emptyList()

    var checkedCodes by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var deleteTarget by remember { mutableStateOf<ProjectBeadEntry?>(null) }

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
                        scope.launch {
                            val orderId = viewModel.createOrderFromSelection(checkedCodes)
                            if (orderId != null) {
                                checkedCodes = emptySet()
                                onOrderCreated(orderId)
                            }
                        }
                    }
                },
                expanded = checkedCodes.isNotEmpty(),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.create_order)) },
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
                items(beads, key = { it.beadCode }) { bead ->
                    val received = receivedGrams[bead.beadCode] ?: 0.0
                    val atOrOverTarget = received >= bead.targetGrams
                    ProjectBeadRow(
                        bead = bead,
                        receivedGrams = received,
                        inventoryGrams = inventoryGrams[bead.beadCode] ?: 0.0,
                        checked = bead.beadCode in checkedCodes,
                        atOrOverTarget = atOrOverTarget,
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
}

@Composable
private fun ProjectBeadRow(
    bead: ProjectBeadEntry,
    receivedGrams: Double,
    inventoryGrams: Double,
    checked: Boolean,
    atOrOverTarget: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    // Sufficient means inventory already covers the target — no order needed.
    val progress = if (bead.targetGrams > 0.0) {
        (receivedGrams / bead.targetGrams).coerceIn(0.0, 1.0).toFloat()
    } else 0f

    val targetStr = BigDecimal.valueOf(bead.targetGrams).stripTrailingZeros().toPlainString()
    val receivedStr = BigDecimal.valueOf(receivedGrams).stripTrailingZeros().toPlainString()
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
                    if (atOrOverTarget) {
                        Text(
                            text = stringResource(R.string.bead_target_met),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.bead_progress, receivedStr, targetStr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = if (atOrOverTarget) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = if (deficit > 0.0) {
                stringResource(R.string.bead_in_stock_deficit, invStr, deficitStr)
            } else {
                stringResource(R.string.bead_in_stock_sufficient, invStr)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (deficit > 0.0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(start = 44.dp, top = 2.dp, end = 48.dp),
        )
    }
}
