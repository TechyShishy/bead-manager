package com.techyshishy.beadmanager.data.firestore

/**
 * One vendor invoice on an order: the rows that share [invoiceId] were ordered together at
 * [vendorKey]. Firestore stores these as an array on the order document, beside the items.
 *
 * [estimatedCents] is the app's price for the invoice's rows when they were marked ordered;
 * null when any of them had no known price. [actualCents] is what the user reports having paid
 * (shipping and tax included); null until entered. [invoiceNumber] is the vendor's reference,
 * null or blank when none was given.
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class OrderInvoice(
    val id: String = "",
    val vendorKey: String = "",
    val invoiceNumber: String? = null,
    val estimatedCents: Int? = null,
    val actualCents: Int? = null,
)

/**
 * The invoice number for [item]: the number on its invoice record, else the legacy number
 * stored on the item itself by documents that predate invoice records. Null when neither
 * holds a non-blank number.
 */
@Suppress("DEPRECATION")
fun OrderEntry.invoiceNumberOf(item: OrderItemEntry): String? {
    val recorded = item.invoiceId?.let { id -> invoices.firstOrNull { it.id == id }?.invoiceNumber }
    return (recorded ?: item.invoiceNumber)?.takeIf { it.isNotBlank() }
}
