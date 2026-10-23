package com.techyshishy.beadmanager.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.seed.CatalogSeeder
import com.techyshishy.beadmanager.domain.PinEditorData
import com.techyshishy.beadmanager.domain.PinRow
import java.math.BigDecimal

/**
 * Bottom sheet for choosing a bead's vendors, pack sizes and quantities by hand. Saving pins
 * the rows so automatic planning leaves them alone; removing the override hands the bead back
 * to the optimizer.
 *
 * The total against the target is shown as information only: a shortfall is accepted, as it is
 * when editing a target elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinEditorSheet(
    data: PinEditorData,
    onSave: (List<PinRow>) -> Unit,
    onRemovePin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rows by remember(data) { mutableStateOf(data.rows) }

    val duplicate = rows.groupBy { it.vendorKey to it.packGrams }.any { it.value.size > 1 }
    val collidesWithLocked = rows.any { (it.vendorKey to it.packGrams) in data.lockedPacks }
    val canSave = rows.isNotEmpty() && rows.all { it.quantityUnits > 0 } &&
        !duplicate && !collidesWithLocked
    val totalGrams = rows.sumOf { it.packGrams * it.quantityUnits }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.pin_editor_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = data.beadCode,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            rows.forEachIndexed { index, row ->
                PinRowEditor(
                    row = row,
                    packs = data.packs,
                    onChange = { changed -> rows = rows.toMutableList().also { it[index] = changed } },
                    onDelete = { rows = rows.toMutableList().also { it.removeAt(index) } },
                )
                HorizontalDivider()
            }

            OutlinedButton(
                onClick = {
                    val pack = data.packs.firstOrNull {
                        (it.vendorKey to it.grams) !in data.lockedPacks &&
                            rows.none { r -> r.vendorKey == it.vendorKey && r.packGrams == it.grams }
                    } ?: data.packs.first()
                    rows = rows + PinRow(pack.vendorKey, pack.grams, 1)
                },
                enabled = data.packs.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.pin_editor_add_row))
            }

            Text(
                text = stringResource(
                    R.string.pin_editor_total,
                    formatGrams(totalGrams),
                    formatGrams(data.targetGrams),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (totalGrams < data.targetGrams) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (duplicate) {
                Text(
                    text = stringResource(R.string.pin_editor_duplicate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (collidesWithLocked) {
                Text(
                    text = stringResource(R.string.pin_editor_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (data.hasPin) {
                    TextButton(onClick = onRemovePin) {
                        Text(stringResource(R.string.pin_editor_remove))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = { onSave(rows) }, enabled = canSave) {
                    Text(stringResource(R.string.edit_item_save))
                }
            }
        }
    }
}

@Composable
private fun PinRowEditor(
    row: PinRow,
    packs: List<VendorPackEntity>,
    onChange: (PinRow) -> Unit,
    onDelete: () -> Unit,
) {
    val vendorKeys = remember(packs) { packs.map { it.vendorKey }.distinct() }
    val vendorPacks = remember(packs, row.vendorKey) { packs.filter { it.vendorKey == row.vendorKey } }
    val selectedPack = vendorPacks.firstOrNull { it.grams == row.packGrams }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DropdownField(
                label = CatalogSeeder.VENDOR_DISPLAY_NAMES[row.vendorKey] ?: row.vendorKey,
                options = vendorKeys,
                optionLabel = { CatalogSeeder.VENDOR_DISPLAY_NAMES[it] ?: it },
                onSelect = { vendorKey ->
                    if (vendorKey != row.vendorKey) {
                        val first = packs.first { it.vendorKey == vendorKey }
                        onChange(row.copy(vendorKey = vendorKey, packGrams = first.grams))
                    }
                },
                modifier = Modifier.weight(1f),
            )
            DropdownField(
                label = packLabel(selectedPack, row.packGrams),
                options = vendorPacks,
                optionLabel = { packLabel(it, it.grams) },
                onSelect = { onChange(row.copy(packGrams = it.grams)) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.remove_item),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange(row.copy(quantityUnits = (row.quantityUnits - 1).coerceAtLeast(1))) },
                enabled = row.quantityUnits > 1,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = null)
            }
            Text(
                text = row.quantityUnits.toString(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = { onChange(row.copy(quantityUnits = row.quantityUnits + 1)) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
        val stock = selectedPack?.stockQuantity
        if (selectedPack?.available == false || (stock != null && row.quantityUnits > stock)) {
            Text(
                text = stringResource(R.string.pin_editor_stock_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun <T> DropdownField(
    label: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun packLabel(pack: VendorPackEntity?, grams: Double): String {
    val size = stringResource(R.string.pin_editor_pack_size, formatGrams(grams))
    return when {
        pack == null -> size
        pack.available == false || pack.stockQuantity == 0 ->
            "$size · ${stringResource(R.string.pin_editor_out_of_stock)}"
        pack.stockQuantity != null ->
            "$size · ${stringResource(R.string.pin_editor_in_stock, pack.stockQuantity)}"
        else -> size
    }
}

private fun formatGrams(grams: Double): String =
    BigDecimal.valueOf(grams).stripTrailingZeros().toPlainString()
