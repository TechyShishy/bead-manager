package com.techyshishy.beadmanager.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.model.BEADS_PER_GRAM
import java.math.BigDecimal
import java.text.NumberFormat
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BeadDetailPane(
    beadCode: String,
    viewModel: BeadDetailViewModel,
    onNavigateBack: (() -> Unit)? = null,
    onViewProjects: () -> Unit = {},
    isPinned: Boolean = false,
    onPinToggle: () -> Unit = {},
    isFavorited: Boolean = false,
    onFavoriteToggle: () -> Unit = {},
    onNavigateNext: (() -> Unit)? = null,
    onNavigatePrev: (() -> Unit)? = null,
) {
    // LaunchedEffect ensures initialize() runs as a side effect, not during
    // composition, avoiding potential re-entrant snapshot state writes.
    LaunchedEffect(beadCode) {
        viewModel.initialize(beadCode)
    }
    val beadWithInventory by viewModel.bead.collectAsState()
    val globalThreshold by viewModel.globalThresholdGrams.collectAsState()
    val beadName by viewModel.beadName.collectAsState()
    val isPhoneLayout = LocalConfiguration.current.screenWidthDp < 600
    val item = beadWithInventory ?: return

    val bead = item.catalogEntry.bead
    val inventory = item.inventory
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val hexColor = remember(bead.hex) {
        runCatching { Color(bead.hex.toColorInt()) }.getOrDefault(Color.Gray)
    }
    val colorGroupList = bead.colorGroup

    val latestOnNavigateNext by rememberUpdatedState(onNavigateNext)
    val latestOnNavigatePrev by rememberUpdatedState(onNavigatePrev)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(focusManager) { detectTapGestures { focusManager.clearFocus() } }
            .pointerInput(Unit) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        val threshold = 50.dp.toPx()
                        when {
                            totalDrag < -threshold -> latestOnNavigateNext?.invoke()
                            totalDrag > threshold -> latestOnNavigatePrev?.invoke()
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                    },
                )
            },
    ) {
        if (onNavigateBack != null) {
            TopAppBar(
                title = { Text(bead.code) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onFavoriteToggle) {
                        Icon(
                            if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(
                                if (isFavorited) R.string.remove_from_favorites
                                else R.string.add_to_favorites
                            ),
                        )
                    }
                    IconButton(onClick = onPinToggle) {
                        Icon(
                            if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource(
                                if (isPinned) R.string.unpin_from_comparison
                                else R.string.pin_for_comparison
                            ),
                        )
                    }
                },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onFavoriteToggle) {
                    Icon(
                        if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(
                            if (isFavorited) R.string.remove_from_favorites
                            else R.string.add_to_favorites
                        ),
                    )
                }
                IconButton(onClick = onPinToggle) {
                    Icon(
                        if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = stringResource(
                            if (isPinned) R.string.unpin_from_comparison
                            else R.string.pin_for_comparison
                        ),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Bead image / color swatch — flanked by prev/next arrows when navigating catalog
            if (onNavigatePrev != null || onNavigateNext != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onNavigatePrev?.invoke() },
                        enabled = onNavigatePrev != null,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.NavigateBefore,
                            contentDescription = stringResource(R.string.navigate_prev),
                        )
                    }
                    AsyncImage(
                        model = bead.imageUrl,
                        contentDescription = bead.code,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(2f),
                        placeholder = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
                        error = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
                    )
                    IconButton(
                        onClick = { onNavigateNext?.invoke() },
                        enabled = onNavigateNext != null,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = stringResource(R.string.navigate_next),
                        )
                    }
                }
            } else {
                AsyncImage(
                    model = bead.imageUrl,
                    contentDescription = bead.code,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f),
                    placeholder = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
                    error = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Code + color swatch chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(0.dp)
                        .background(hexColor, MaterialTheme.shapes.small)
                        .height(24.dp)
                        .padding(horizontal = 12.dp),
                )
                Text(bead.code, style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(8.dp))

            beadName?.let { MetadataRow("Name", it) }
            MetadataRow("Color group", colorGroupList.joinToString(", "))
            MetadataRow("Glass group", bead.glassGroup)
            MetadataRow("Dyed", bead.dyed)
            MetadataRow("Galvanized", bead.galvanized)
            MetadataRow("Plating", bead.plating)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Inventory section
            Text("Inventory", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val currentGrams = inventory?.quantityGrams ?: 0.0
            val beadCount = (currentGrams * BEADS_PER_GRAM).toLong()
            var customAmount by remember(beadCode) { mutableStateOf(TextFieldValue("")) }
            var showResetConfirmation by remember(beadCode) { mutableStateOf(false) }

            if (showResetConfirmation) {
                AlertDialog(
                    onDismissRequest = { showResetConfirmation = false },
                    title = { Text("Reset inventory?") },
                    text = { Text("This will set the quantity to 0g.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.setQuantity(0.0)
                                showResetConfirmation = false
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Reset") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirmation = false }) { Text("Cancel") }
                    },
                )
            }

            Text(
                    text = "${BigDecimal.valueOf(currentGrams).stripTrailingZeros().toPlainString()}g",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            if (currentGrams > 0.0) {
                Text(
                    text = "≈ ${NumberFormat.getIntegerInstance().format(beadCount)} beads",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(7.5, 25.0, 50.0, 100.0).forEach { grams ->
                    SuggestionChip(
                        onClick = { viewModel.adjustQuantity(grams) },
                        label = {
                            Text("+${BigDecimal.valueOf(grams).stripTrailingZeros().toPlainString()}g")
                        },
                    )
                }
                BasicTextField(
                    value = customAmount,
                    onValueChange = { customAmount = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = LocalContentColor.current,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        val parsed = customAmount.text.toDoubleOrNull()
                        if (parsed != null && parsed > 0.0) viewModel.adjustQuantity(parsed)
                        customAmount = TextFieldValue("")
                    }),
                    modifier = Modifier.widthIn(min = 96.dp, max = 120.dp),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (customAmount.text.isEmpty()) {
                                Text(
                                    "0.0g",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            TextButton(
                onClick = { showResetConfirmation = true },
                enabled = currentGrams != 0.0,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Reset inventory")
            }

            Spacer(Modifier.height(8.dp))

            // Low-stock threshold — per-bead override or global fallback.
            // 0.0 is the sentinel meaning "use global"; any positive value is a per-bead
            // override set explicitly by the user.
            val storedThreshold = inventory?.lowStockThresholdGrams ?: 0.0
            val isUsingGlobal = storedThreshold == 0.0
            val effectiveThreshold = if (isUsingGlobal) globalThreshold.toFloat() else storedThreshold.toFloat()
            var threshold by remember(storedThreshold) { mutableFloatStateOf(effectiveThreshold) }
            // Keep slider in sync when the global threshold changes while this bead's
            // threshold is set to the global sentinel.
            //
            // Safety note: the LaunchedEffect re-runs when either key changes. The guard
            // `if (isUsingGlobal)` is what protects against an unwanted reset — not any
            // recomposition ordering guarantee. A narrow race exists (global changes between
            // onValueChangeFinished and the Firestore write reflecting back), but it requires
            // simultaneous multi-screen interaction and is acceptable.
            LaunchedEffect(isUsingGlobal, globalThreshold) {
                if (isUsingGlobal) threshold = globalThreshold.toFloat()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.low_stock_threshold_label,
                        threshold.toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!isUsingGlobal) {
                    TextButton(onClick = { viewModel.resetThresholdToGlobal() }) {
                        Text(stringResource(R.string.reset_to_global_default))
                    }
                }
            }
            if (isUsingGlobal) {
                Text(
                    text = stringResource(R.string.using_global_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { viewModel.updateThreshold(threshold.toDouble()) },
                valueRange = 1f..100f,
                steps = 98,
            )

            // Notes field — saved on IME Done and also on focus loss so edits
            // are never silently discarded by back-gesture or field switching.
            var notes by remember(inventory?.notes) { mutableStateOf(inventory?.notes ?: "") }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.notes)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) viewModel.updateNotes(notes)
                    },
                minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                keyboardActions = KeyboardActions(onDone = { viewModel.updateNotes(notes) }),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Button(
                onClick = onViewProjects,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.view_projects))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(bead.officialUrl))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.view_on_miyuki))
            }
            if (!isPhoneLayout) {
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
