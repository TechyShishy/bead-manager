package com.techyshishy.beadmanager.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.seed.CatalogSeeder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    viewModel: CatalogViewModel,
    onDismiss: () -> Unit,
) {
    val filter by viewModel.filterState.collectAsState()
    val colorGroups by viewModel.colorGroups.collectAsState()
    val glassGroups by viewModel.glassGroups.collectAsState()
    val enoughOnHandTargetGrams by viewModel.enoughOnHandTargetGrams.collectAsState()
    val enoughOnHandEnabled by viewModel.enoughOnHandEnabled.collectAsState()
    val finishes = CatalogSeeder.ALL_FINISHES
    val dyedValues = CatalogSeeder.ALL_DYED_VALUES
    val galvanizedValues = CatalogSeeder.ALL_GALVANIZED_VALUES
    val platingValues = CatalogSeeder.ALL_PLATING_VALUES

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Sort section
            Text(
                text = stringResource(R.string.sort_by),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortBy.entries.forEach { option ->
                    FilterChip(
                        selected = filter.sortBy == option,
                        onClick = { viewModel.setSortBy(option) },
                        label = { Text(option.label()) },
                        trailingIcon = if (filter.sortBy == option) {
                            {
                                Icon(
                                    imageVector = if (filter.sortDirection == SortDirection.ASCENDING)
                                        Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    contentDescription = if (filter.sortDirection == SortDirection.ASCENDING)
                                        stringResource(R.string.sort_direction_ascending)
                                    else
                                        stringResource(R.string.sort_direction_descending),
                                )
                            }
                        } else null,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Owned only filter chip
            FilterChip(
                selected = filter.ownedOnly,
                onClick = { viewModel.toggleOwnedOnly() },
                label = { Text(stringResource(R.string.owned_only)) },
            )
            if (enoughOnHandTargetGrams != null) {
                FilterChip(
                    selected = enoughOnHandEnabled,
                    onClick = { viewModel.toggleEnoughOnHand() },
                    label = { Text(stringResource(R.string.catalog_enough_in_stock)) },
                )
            }

            FilterSection(
                title = stringResource(R.string.color_group),
                items = colorGroups,
                selected = filter.colorGroups,
                onToggle = viewModel::toggleColorGroup,
            )

            FilterSection(
                title = stringResource(R.string.glass_group),
                items = glassGroups,
                selected = filter.glassGroups,
                onToggle = viewModel::toggleGlassGroup,
            )

            FilterSection(
                title = stringResource(R.string.finish),
                items = finishes,
                selected = filter.finishes,
                onToggle = viewModel::toggleFinish,
            )

            FilterSection(
                title = stringResource(R.string.dyed),
                items = dyedValues,
                selected = filter.dyed,
                onToggle = viewModel::toggleDyed,
            )

            FilterSection(
                title = stringResource(R.string.galvanized),
                items = galvanizedValues,
                selected = filter.galvanized,
                onToggle = viewModel::toggleGalvanized,
            )

            FilterSection(
                title = stringResource(R.string.plating),
                items = platingValues,
                selected = filter.plating,
                onToggle = viewModel::togglePlating,
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.clearFilters()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.clear_all_filters))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SortBy.label(): String = when (this) {
    SortBy.DB_NUMBER -> stringResource(R.string.sort_db_number)
    SortBy.COLOR_GROUP -> stringResource(R.string.color_group)
    SortBy.GLASS_GROUP -> stringResource(R.string.glass_group)
    SortBy.DYED -> stringResource(R.string.dyed)
    SortBy.GALVANIZED -> stringResource(R.string.galvanized)
    SortBy.PLATING -> stringResource(R.string.plating)
    SortBy.COUNT_GRAMS -> stringResource(R.string.sort_quantity_grams)
    SortBy.COUNT_BEADS -> stringResource(R.string.sort_quantity_beads)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    items: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Text(
        text = title,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item in selected,
                onClick = { onToggle(item) },
                label = { Text(item) },
            )
        }
    }
}
