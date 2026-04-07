package com.techyshishy.beadmanager.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BeadDetailPane(
    beadCode: String,
    viewModel: BeadDetailViewModel,
    onNavigateBack: (() -> Unit)? = null,
) {
    // LaunchedEffect ensures initialize() runs as a side effect, not during
    // composition, avoiding potential re-entrant snapshot state writes.
    LaunchedEffect(beadCode) {
        viewModel.initialize(beadCode)
    }
    val beadWithInventory by viewModel.bead.collectAsState()
    val isPhoneLayout = LocalConfiguration.current.screenWidthDp < 600
    val item = beadWithInventory ?: return

    val bead = item.catalogEntry.bead
    val inventory = item.inventory
    val vendorLinks = item.catalogEntry.vendorLinks
    val context = LocalContext.current

    val hexColor = remember(bead.hex) {
        runCatching { Color(bead.hex.toColorInt()) }.getOrDefault(Color.Gray)
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
            )
        } else {
            Spacer(Modifier.statusBarsPadding())
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Bead image / color swatch
            AsyncImage(
                model = bead.imageUrl,
                contentDescription = bead.code,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f),
                placeholder = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
                error = androidx.compose.ui.graphics.painter.ColorPainter(hexColor),
            )

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

            MetadataRow("Color group", bead.colorGroup)
            MetadataRow("Glass group", bead.glassGroup)
            MetadataRow("Dyed", bead.dyed)
            MetadataRow("Galvanized", bead.galvanized)
            MetadataRow("Plating", bead.plating)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Inventory section
            Text("Inventory", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val currentGrams = inventory?.quantityGrams ?: 0.0
            var editingQuantity by remember { mutableStateOf(false) }
            var quantityInput by remember { mutableStateOf(TextFieldValue("")) }
            val quantityFocusRequester = remember { FocusRequester() }
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
                                editingQuantity = false
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

            if (editingQuantity) {
                // Track whether focus was actually acquired this session so that
                // the initial isFocused=false event on first composition (fired
                // before LaunchedEffect has a chance to request focus) does not
                // immediately collapse the field. Declared inside the `if` so
                // it resets to false each time a new editing session begins.
                var hasFocused by remember { mutableStateOf(false) }
                BasicTextField(
                    value = quantityInput,
                    onValueChange = { quantityInput = it },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        color = LocalContentColor.current,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        // Just close the field; the resulting focus-loss fires
                        // onFocusChanged which saves exactly once.
                        editingQuantity = false
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(quantityFocusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                hasFocused = true
                                quantityInput = quantityInput.copy(
                                    selection = TextRange(0, quantityInput.text.length),
                                )
                            } else if (hasFocused) {
                                val parsed = quantityInput.text.toDoubleOrNull()
                                if (parsed != null && parsed >= 0.0) viewModel.setQuantity(parsed)
                                editingQuantity = false
                            }
                        },
                )
                LaunchedEffect(Unit) {
                    quantityFocusRequester.requestFocus()
                }
            } else {
                Text(
                    text = "${BigDecimal.valueOf(currentGrams).stripTrailingZeros().toPlainString()}g",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val text = BigDecimal.valueOf(currentGrams).stripTrailingZeros().toPlainString()
                            quantityInput = TextFieldValue(text, selection = TextRange(0, text.length))
                            editingQuantity = true
                        },
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
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Reset inventory")
            }

            Spacer(Modifier.height(8.dp))

            // Low-stock threshold slider
            var threshold by remember(inventory?.lowStockThresholdGrams) {
                mutableFloatStateOf((inventory?.lowStockThresholdGrams ?: 5.0).toFloat())
            }
            Text(
                text = "${stringResource(R.string.low_stock_threshold)}: ${"%.0f".format(threshold)}g",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { viewModel.updateThreshold(threshold.toDouble()) },
                valueRange = 1f..30f,
                steps = 28,
            )

            Spacer(Modifier.height(8.dp))

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

            // Purchase links
            if (vendorLinks.isNotEmpty()) {
                Text("Buy", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                vendorLinks.forEach { link ->
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(stringResource(R.string.buy_at, link.displayName))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

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
