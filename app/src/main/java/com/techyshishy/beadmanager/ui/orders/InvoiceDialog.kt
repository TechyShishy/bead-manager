package com.techyshishy.beadmanager.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import java.math.BigDecimal

private val DOLLAR_AMOUNT = Regex("""\$?\s*(\d+)(\.\d{1,2})?""")

/**
 * Parses a dollar amount typed by the user ("46.5", "$46.50") into cents. Returns null for
 * blank or malformed text; callers tell the two apart with [String.isBlank].
 */
internal fun parseDollarsToCents(text: String): Int? {
    val match = DOLLAR_AMOUNT.matchEntire(text.trim()) ?: return null
    val cents = BigDecimal(match.groupValues[1] + match.groupValues[2])
        .movePointRight(2)
        .toBigIntegerExact()
    return if (cents.bitLength() < Int.SIZE_BITS) cents.toInt() else null
}

/** [cents] as plain dollars with two decimals, e.g. 4650 -> "46.50", for a text field. */
internal fun formatCentsForInput(cents: Int): String =
    "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

/**
 * Dialog for an invoice's number and the amount paid. Both are optional; a blank amount
 * leaves it unrecorded. [initialAmount] is pre-filled text (the estimate when marking a vendor
 * ordered), and [estimateIsPartial] adds a note that the estimate left out rows with no known
 * price.
 */
@Composable
internal fun InvoiceDialog(
    title: String,
    confirmLabel: String,
    initialInvoiceNumber: String,
    initialAmount: String,
    estimateIsPartial: Boolean,
    onConfirm: (invoiceNumber: String?, amountCents: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var invoiceText by remember { mutableStateOf(initialInvoiceNumber) }
    var amountText by remember { mutableStateOf(initialAmount) }
    val amountCents = parseDollarsToCents(amountText)
    val amountValid = amountText.isBlank() || amountCents != null

    fun confirm() {
        if (amountValid) onConfirm(invoiceText.takeIf { it.isNotBlank() }, amountCents)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = invoiceText,
                    onValueChange = { invoiceText = it },
                    label = { Text(stringResource(R.string.finalize_invoice_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.invoice_amount_hint)) },
                    prefix = { Text("$") },
                    singleLine = true,
                    isError = !amountValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
                if (estimateIsPartial) {
                    Text(
                        text = stringResource(R.string.invoice_estimate_partial),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = amountValid) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
