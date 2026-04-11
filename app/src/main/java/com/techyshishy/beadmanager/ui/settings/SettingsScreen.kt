package com.techyshishy.beadmanager.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val currentThreshold by viewModel.globalLowStockThreshold.collectAsState()
    val vendorPriority by viewModel.vendorPriorityOrder.collectAsState()
    val buyUpEnabled by viewModel.buyUpEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings)) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_section_vendor),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            VendorPreferenceRow(
                preferFmg = vendorPriority.firstOrNull() == "fmg",
                onPreferFmgChange = { preferFmg ->
                    viewModel.setVendorPriorityOrder(
                        if (preferFmg) listOf("fmg", "ac") else listOf("ac", "fmg")
                    )
                },
            )
            BuyUpRow(
                enabled = buyUpEnabled,
                onEnabledChange = { viewModel.setBuyUpEnabled(it) },
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_section_inventory),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            LowStockThresholdRow(
                currentGrams = currentThreshold,
                onCommit = { viewModel.setGlobalLowStockThreshold(it) },
            )
        }
    }
}

@Composable
private fun VendorPreferenceRow(
    preferFmg: Boolean,
    onPreferFmgChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_prefer_fmg),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_prefer_fmg_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = preferFmg, onCheckedChange = onPreferFmgChange)
    }
}

@Composable
private fun BuyUpRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_buy_up),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_buy_up_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun LowStockThresholdRow(
    currentGrams: Double,
    onCommit: (Double) -> Unit,
) {
    // Local text buffer. Re-initialised whenever the upstream value changes and
    // the field is not focused (i.e., an external write arrived).
    var text by remember(currentGrams) {
        mutableStateOf(BigDecimal.valueOf(currentGrams).stripTrailingZeros().toPlainString())
    }
    var isError by remember { mutableStateOf(false) }
    // Prevents a second DataStore write when the keyboard dismissal triggers focus-loss
    // immediately after the user presses the IME Done action.
    var justCommittedViaIme by remember { mutableStateOf(false) }

    fun tryCommit() {
        val parsed = text.toDoubleOrNull()
        // Must be in [1, 30] — the same range as the per-bead slider. This prevents
        // the slider thumb from being silently clamped while the label shows a higher value.
        if (parsed != null && parsed in 1.0..30.0) {
            isError = false
            onCommit(parsed)
        } else {
            isError = true
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.global_low_stock_threshold),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.global_low_stock_threshold_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                isError = false
            },
            label = { Text(stringResource(R.string.settings_threshold_label)) },
            isError = isError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                justCommittedViaIme = true
                tryCommit()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (!it.isFocused) {
                        if (!justCommittedViaIme) tryCommit()
                        justCommittedViaIme = false
                    }
                },
            supportingText = if (isError) {
                { Text(stringResource(R.string.settings_threshold_error)) }
            } else null,
        )
    }
}
