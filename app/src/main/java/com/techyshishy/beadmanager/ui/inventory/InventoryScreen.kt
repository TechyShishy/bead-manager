package com.techyshishy.beadmanager.ui.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.model.BeadWithInventory

@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel,
    onBeadSelected: (String) -> Unit,
) {
    val beads by viewModel.ownedBeads.collectAsState()
    val isPhoneLayout = LocalConfiguration.current.screenWidthDp < 600

    if (beads.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.no_beads_owned),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = if (isPhoneLayout)
            WindowInsets.safeDrawing
                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                .asPaddingValues()
        else
            WindowInsets.safeDrawing.asPaddingValues(),
    ) {
        items(beads, key = { it.code }) { item ->
            InventoryRow(
                item = item,
                onAdjust = { delta -> viewModel.adjustQuantity(item.code, delta) },
                onClick = { onBeadSelected(item.code) },
            )
        }
    }
}

@Composable
private fun InventoryRow(
    item: BeadWithInventory,
    onAdjust: (Double) -> Unit,
    onClick: () -> Unit,
) {
    val bead = item.catalogEntry.bead
    val grams = item.inventory?.quantityGrams ?: 0.0
    val hexColor = remember(bead.hex) {
        runCatching { Color(bead.hex.toColorInt()) }.getOrDefault(Color.Gray)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = bead.imageUrl,
            contentDescription = bead.code,
            modifier = Modifier.size(48.dp),
            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
            error = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(bead.code, style = MaterialTheme.typography.bodyLarge)
            Text(
                bead.colorGroup,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.isLowStock) {
                Text(
                    stringResource(R.string.low_stock),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Quick +/- controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onAdjust(-0.5) }) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.subtract_five_grams))
            }
            Text(
                text = "${java.math.BigDecimal.valueOf(grams).stripTrailingZeros().toPlainString()}g",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = { onAdjust(+0.5) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_five_grams))
            }
        }
    }
}
