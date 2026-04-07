package com.techyshishy.beadmanager.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.model.BeadWithInventory

@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onBeadSelected: (String) -> Unit,
) {
    val beads by viewModel.beads.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filterState.collectAsState()
    var showFilter by remember { mutableStateOf(false) }
    // No remember key — LaunchedEffect handles external resets; a query key would reset
    // cursor position on every keystroke during normal typing.
    var searchFieldValue by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != searchFieldValue) {
            searchFieldValue = query
        }
    }
    val activeFilterCount = filter.colorGroups.size +
        filter.glassGroups.size +
        filter.finishes.size +
        (if (filter.ownedOnly) 1 else 0) +
        (if (filter.sortBy != SortBy.DB_NUMBER) 1 else 0)
    // On phones NavigationSuiteScaffold places the nav bar below content, so the content area
    // is already above the system nav bar and we must not add redundant bottom insets.
    val isPhoneLayout = LocalConfiguration.current.screenWidthDp < 600

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TextField(
            value = searchFieldValue,
            onValueChange = { newValue ->
                val digits = newValue.filter { it.isDigit() }
                searchFieldValue = digits
                viewModel.updateSearch(digits)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_beads)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                BadgedBox(
                    badge = {
                        if (activeFilterCount > 0) Badge { Text("$activeFilterCount") }
                    },
                ) {
                    IconButton(onClick = { showFilter = true }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = stringResource(R.string.filter),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
            ),
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = if (!isPhoneLayout) WindowInsets.navigationBars.asPaddingValues()
                             else WindowInsets(0, 0, 0, 0).asPaddingValues(),
        ) {
            items(beads, key = { it.code }) { item ->
                BeadGridItem(
                    item = item,
                    onClick = { onBeadSelected(item.code) },
                )
            }
        }
    }

    if (showFilter) {
        FilterSheet(viewModel = viewModel, onDismiss = { showFilter = false })
    }
}

@Composable
private fun BeadGridItem(
    item: BeadWithInventory,
    onClick: () -> Unit,
) {
    val bead = item.catalogEntry.bead
    val hexColor = remember(bead.hex) {
        runCatching { Color(bead.hex.toColorInt()) }.getOrDefault(Color.Gray)
    }

    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            AsyncImage(
                model = bead.imageUrl,
                contentDescription = bead.code,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.small),
                // Show the hex color swatch while the CDN image loads.
                placeholder = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
                error = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
            )

            if (item.isOwned) {
                // Small dot overlay: green = adequate stock, amber = low stock
                val dotColor = if (item.isLowStock) Color(0xFFFFA000) else Color(0xFF4CAF50)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .border(1.dp, Color.White, CircleShape),
                )
            }
        }

        Text(
            text = bead.code,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
