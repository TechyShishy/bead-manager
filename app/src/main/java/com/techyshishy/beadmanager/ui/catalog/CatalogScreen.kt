package com.techyshishy.beadmanager.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import kotlinx.coroutines.launch

@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onBeadSelected: (String) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val beads by viewModel.beads.collectAsState()
    val sortBuckets by viewModel.sortBuckets.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filterState.collectAsState()
    val pinnedBeads by viewModel.pinnedBeads.collectAsState()
    val stockOnlyFilter by viewModel.stockOnlyFilter.collectAsState()
    val enoughOnHandTargetGrams by viewModel.enoughOnHandTargetGrams.collectAsState()
    val enoughOnHandEnabled by viewModel.enoughOnHandEnabled.collectAsState()
    var showFilter by remember { mutableStateOf(false) }
    // No remember key — LaunchedEffect handles external resets; a query key would reset
    // cursor position on every keystroke during normal typing.
    var searchFieldValue by remember { mutableStateOf(TextFieldValue(query)) }
    LaunchedEffect(query) {
        if (query != searchFieldValue.text) {
            searchFieldValue = TextFieldValue(query)
        }
    }
    val activeFilterCount = filter.colorGroups.size +
        filter.glassGroups.size +
        filter.finishes.size +
        (if (filter.ownedOnly) 1 else 0) +
        (if (filter.sortBy != SortBy.DB_NUMBER) 1 else 0) +
        (if (enoughOnHandTargetGrams != null && enoughOnHandEnabled) 1 else 0)
    // On phones NavigationSuiteScaffold places the nav bar below content, so the content area
    // is already above the system nav bar and we must not add redundant bottom insets.
    val isPhoneLayout = LocalConfiguration.current.screenWidthDp < 600
    val showNavBar = !isPhoneLayout && sortBuckets.size > 1

    val coroutineScope = rememberCoroutineScope()

    // Tracks which beads list item is closest to the vertical center of the visible grid area.
    // Uses derivedStateOf so scroll events only trigger recomposition when the value changes.
    val centerVisibleIndex by remember(gridState) {
        derivedStateOf {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) -1 else visible[visible.size / 2].index
        }
    }
    // Finds the nav bar bucket that contains the center-visible item.
    val currentBucketIndex by remember(sortBuckets) {
        val buckets = sortBuckets
        derivedStateOf {
            if (buckets.isEmpty() || centerVisibleIndex < 0) -1
            else buckets.indexOfLast { it.startIndex <= centerVisibleIndex }
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        PinComparisonStrip(
            pinnedBeads = pinnedBeads,
            stockOnlyFilter = stockOnlyFilter,
            onBeadSelected = onBeadSelected,
            onUnpin = { code -> viewModel.unpinBead(code) },
            onClearAll = { viewModel.clearAllPins() },
            onToggleStockOnly = { viewModel.toggleStockOnly() },
        )
        TextField(
            value = searchFieldValue,
            onValueChange = { newValue ->
                val digits = newValue.text.filter { it.isDigit() }
                val clampedSelection = TextRange(
                    newValue.selection.start.coerceAtMost(digits.length),
                    newValue.selection.end.coerceAtMost(digits.length),
                )
                searchFieldValue = newValue.copy(text = digits, selection = clampedSelection)
                viewModel.updateSearch(digits)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        val text = searchFieldValue.text
                        searchFieldValue = searchFieldValue.copy(
                            selection = TextRange(0, text.length)
                        )
                    }
                },
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

        Row(modifier = Modifier.fillMaxSize()) {
            if (showNavBar) {
                SortNavBar(
                    buckets = sortBuckets,
                    currentBucketIndex = currentBucketIndex,
                    onBucketClick = { bucket ->
                        coroutineScope.launch { gridState.animateScrollToItem(bucket.startIndex) }
                    },
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxHeight(),
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

/**
 * Horizontal strip shown above the catalog grid when at least one bead is pinned.
 *
 * Each pinned bead is shown as a large circular image; tapping it navigates to the bead's
 * detail pane. A small close button overlaid in the top-right corner unpins that bead.
 * "In stock only" filters the catalog grid to beads with non-zero inventory; "Clear all"
 * dismisses all pins and hides the strip.
 */
@Composable
private fun PinComparisonStrip(
    pinnedBeads: List<BeadWithInventory>,
    stockOnlyFilter: Boolean,
    onBeadSelected: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onClearAll: () -> Unit,
    onToggleStockOnly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pinnedBeads.isEmpty()) return

    Column(modifier = modifier) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(pinnedBeads, key = { it.code }) { bead ->
                val hexColor = remember(bead.catalogEntry.bead.hex) {
                    runCatching {
                        Color(bead.catalogEntry.bead.hex.toColorInt())
                    }.getOrDefault(Color.Gray)
                }
                Box(modifier = Modifier.size(80.dp)) {
                    AsyncImage(
                        model = bead.catalogEntry.bead.imageUrl,
                        contentDescription = bead.code,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .align(Alignment.Center)
                            .clickable { onBeadSelected(bead.code) },
                        placeholder = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
                        error = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
                    )
                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides 0.dp,
                    ) {
                        IconButton(
                            onClick = { onUnpin(bead.code) },
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.TopEnd),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(
                                    R.string.unpin_bead_from_comparison,
                                    bead.code,
                                ),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            item(key = "stock_only_toggle") {
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = stockOnlyFilter,
                    onClick = onToggleStockOnly,
                    label = { Text(stringResource(R.string.comparison_strip_in_stock_only)) },
                )
            }
            item(key = "clear_all") {
                TextButton(onClick = onClearAll) {
                    Text(stringResource(R.string.comparison_strip_clear_all))
                }
            }
        }
        HorizontalDivider()
    }
}
