package com.techyshishy.beadmanager.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Left-anchored vertical sidebar that shows the buckets of the current sort, highlights the
 * bucket containing the center-visible grid item, and lets the user tap any bucket to jump there.
 *
 * The caller is responsible for only rendering this composable when there are ≥ 2 buckets and
 * the window is wide enough to accommodate it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortNavBar(
    buckets: List<SortBucket>,
    currentBucketIndex: Int,
    onBucketClick: (SortBucket) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The Row wrapping LazyColumn + VerticalDivider fills the sidebar slot.
    Row(modifier = modifier.fillMaxHeight()) {
        LazyColumn(modifier = Modifier.width(72.dp).fillMaxHeight()) {
            itemsIndexed(
                items = buckets,
                key = { _, bucket -> bucket.startIndex },
            ) { index, bucket ->
                val isCurrent = index == currentBucketIndex
                val tooltipState = rememberTooltipState()

                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(bucket.label) } },
                    state = tooltipState,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBucketClick(bucket) }
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = bucket.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        VerticalDivider()
    }
}
