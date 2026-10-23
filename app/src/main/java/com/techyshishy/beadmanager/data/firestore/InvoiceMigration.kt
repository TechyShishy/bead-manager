package com.techyshishy.beadmanager.data.firestore

import java.util.UUID

/** Items and invoices of one order after [planInvoiceMigration] has run. */
data class InvoiceMigrationPlan(
    val items: List<OrderItemEntry>,
    val invoices: List<OrderInvoice>,
)

/**
 * Moves legacy per-item invoice numbers onto [OrderInvoice] records.
 *
 * Items that carry a non-blank legacy [OrderItemEntry.invoiceNumber], have no
 * [OrderItemEntry.invoiceId] and belong to a vendor are grouped by (vendor, number). Each group
 * points at one invoice: an existing invoice with the same vendor and number when there is one,
 * otherwise a new record with no estimate and no amount, since neither was ever recorded. The
 * legacy number is cleared on every migrated item.
 *
 * Items without a legacy number get no invoice. Returns null when there is nothing to migrate,
 * so a second run over migrated data changes nothing.
 */
@Suppress("DEPRECATION")
fun planInvoiceMigration(
    items: List<OrderItemEntry>,
    invoices: List<OrderInvoice>,
): InvoiceMigrationPlan? {
    fun OrderItemEntry.legacyNumber(): String? = invoiceNumber?.trim()?.takeIf { it.isNotEmpty() }
    fun OrderItemEntry.needsMigration() =
        invoiceId == null && vendorKey.isNotBlank() && legacyNumber() != null
    if (items.none { it.needsMigration() }) return null

    val invoiceIds = mutableMapOf<Pair<String, String>, String>()
    for (invoice in invoices) {
        val number = invoice.invoiceNumber?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        invoiceIds.putIfAbsent(invoice.vendorKey to number, invoice.id)
    }
    val created = mutableListOf<OrderInvoice>()

    val migrated = items.map { item ->
        if (!item.needsMigration()) return@map item
        val number = item.legacyNumber()!!
        val id = invoiceIds.getOrPut(item.vendorKey to number) {
            UUID.randomUUID().toString().also {
                created.add(OrderInvoice(id = it, vendorKey = item.vendorKey, invoiceNumber = number))
            }
        }
        item.copy(invoiceId = id, invoiceNumber = null)
    }
    return InvoiceMigrationPlan(migrated, invoices + created)
}
