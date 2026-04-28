package com.techyshishy.beadmanager.ui.projects

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectFilterSheet(
    viewModel: ProjectsViewModel,
    onDismiss: () -> Unit,
) {
    val sortOrder by viewModel.sortOrder.collectAsState()
    val availableTags by viewModel.availableTags.collectAsState()
    val tagFilter by viewModel.tagFilter.collectAsState()

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
                ProjectSortKey.entries.forEach { key ->
                    val isActive = sortOrder.key == key
                    FilterChip(
                        selected = isActive,
                        onClick = { viewModel.setSortKey(key) },
                        label = { Text(key.label()) },
                        trailingIcon = if (isActive) {
                            {
                                Icon(
                                    imageVector = if (sortOrder.direction == SortDirection.ASCENDING)
                                        Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    contentDescription = if (sortOrder.direction == SortDirection.ASCENDING)
                                        stringResource(R.string.sort_direction_ascending)
                                    else
                                        stringResource(R.string.sort_direction_descending),
                                )
                            }
                        } else null,
                    )
                }
            }

            if (availableTags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.filter_by_tag),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableTags.forEach { tag ->
                        FilterChip(
                            selected = tagFilter == tag,
                            onClick = {
                                viewModel.setTagFilter(if (tagFilter == tag) null else tag)
                            },
                            label = { Text(tag) },
                        )
                    }
                }
            }

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
private fun ProjectSortKey.label(): String = when (this) {
    ProjectSortKey.CREATED_AT -> stringResource(R.string.sort_date_created)
    ProjectSortKey.LAST_UPDATED -> stringResource(R.string.sort_last_updated)
    ProjectSortKey.NAME -> stringResource(R.string.sort_name)
    ProjectSortKey.BEAD_TYPES -> stringResource(R.string.sort_bead_types)
    ProjectSortKey.GRID_SIZE -> stringResource(R.string.sort_grid_size)
    ProjectSortKey.SATISFACTION -> stringResource(R.string.sort_satisfaction)
}
