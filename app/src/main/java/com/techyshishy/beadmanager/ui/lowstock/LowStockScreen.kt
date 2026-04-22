package com.techyshishy.beadmanager.ui.lowstock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.model.effectiveThresholdFor
import java.math.BigDecimal

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LowStockScreen(
    viewModel: LowStockViewModel,
    onAddToOrder: () -> Unit = {},
) {
    val beads by viewModel.lowStockBeads.collectAsState()
    val selectedCodes by viewModel.effectiveSelectedCodes.collectAsState()

    val allCodes = beads.map { it.code }
    val allSelected = allCodes.isNotEmpty() && allCodes.all { it in selectedCodes }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (beads.isNotEmpty()) {
                stickyHeader(key = "select_all_header") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(
                                onClickLabel = stringResource(R.string.select_all),
                                onClick = {
                                    if (allSelected) viewModel.clearSelection()
                                    else viewModel.selectAll(allCodes)
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                if (checked) viewModel.selectAll(allCodes)
                                else viewModel.clearSelection()
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.select_all),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            }

            if (beads.isEmpty()) {
                item(key = "empty_state") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 64.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.low_stock_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(beads, key = { it.code }) { item ->
                LowStockBeadRow(
                    item = item,
                    isSelected = item.code in selectedCodes,
                    onToggle = { viewModel.toggleSelection(item.code) },
                )
            }
        }

        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.low_stock_selected_count, selectedCodes.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onAddToOrder,
                    enabled = false,
                ) {
                    Text(stringResource(R.string.low_stock_add_to_order))
                }
            }
        }
    }
}

@Composable
private fun LowStockBeadRow(
    item: BeadWithInventory,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val bead = item.catalogEntry.bead
    val hexColor = runCatching { Color(bead.hex.toColorInt()) }.getOrDefault(Color.Gray)
    val quantityGrams = item.inventory?.quantityGrams ?: 0.0
    val threshold = effectiveThresholdFor(item.inventory, item.globalThresholdGrams)
    val deficit = (threshold - quantityGrams).coerceAtLeast(0.0)

    val qtyStr = BigDecimal.valueOf(quantityGrams).stripTrailingZeros().toPlainString()
    val deficitStr = BigDecimal.valueOf(deficit).stripTrailingZeros().toPlainString()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
        )
        Spacer(Modifier.width(8.dp))
        AsyncImage(
            model = bead.imageUrl,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(MaterialTheme.shapes.small),
            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
            error = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bead.code,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (bead.glassGroup.isNotBlank()) {
                Text(
                    text = bead.glassGroup,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${qtyStr}g",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "−${deficitStr}g",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
