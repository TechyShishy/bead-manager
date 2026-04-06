package com.techyshishy.beadmanager.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
    val finishes = CatalogSeeder.ALL_FINISHES

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

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.clearFilters()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear all filters")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
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
