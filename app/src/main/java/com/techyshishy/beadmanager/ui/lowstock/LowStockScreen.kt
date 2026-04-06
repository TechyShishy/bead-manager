package com.techyshishy.beadmanager.ui.lowstock

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.model.BeadWithInventory

@Composable
fun LowStockScreen(
    viewModel: LowStockViewModel,
    onBeadSelected: (String) -> Unit,
) {
    val beads by viewModel.lowStockBeads.collectAsState()
    val context = LocalContext.current

    Scaffold(
        floatingActionButton = {
            if (beads.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        val text = viewModel.buildShareText()
                        context.startActivity(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                        )
                    },
                ) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_reorder_list))
                }
            }
        },
    ) { innerPadding ->
        if (beads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.no_low_stock),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            items(beads, key = { it.code }) { item ->
                LowStockRow(item = item, onBeadSelected = onBeadSelected)
            }
        }
    }
}

@Composable
private fun LowStockRow(
    item: BeadWithInventory,
    onBeadSelected: (String) -> Unit,
) {
    val bead = item.catalogEntry.bead
    val grams = item.inventory?.quantityGrams ?: 0.0
    val hexColor = remember(bead.hex) {
        runCatching { Color(bead.hex.toColorInt()) }.getOrDefault(Color.Gray)
    }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onBeadSelected(bead.code) }
            .padding(16.dp),
    ) {
        Row(
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
                    "%.1fg remaining".format(grams),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (item.catalogEntry.vendorLinks.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.catalogEntry.vendorLinks.forEach { link ->
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                            )
                        },
                    ) {
                        Text(link.displayName)
                    }
                }
            }
        }
    }
}
