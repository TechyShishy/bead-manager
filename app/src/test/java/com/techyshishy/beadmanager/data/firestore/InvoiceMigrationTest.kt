package com.techyshishy.beadmanager.data.firestore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class InvoiceMigrationTest {

    private fun item(
        beadCode: String,
        vendorKey: String,
        number: String?,
        invoiceId: String? = null,
    ) = OrderItemEntry(
        beadCode = beadCode,
        vendorKey = vendorKey,
        status = OrderItemStatus.ORDERED.firestoreValue,
        invoiceNumber = number,
        invoiceId = invoiceId,
    )

    @Test
    fun `rows sharing a vendor and number get one invoice`() {
        val plan = planInvoiceMigration(
            listOf(item("DB0001", "fmg", "INV-1"), item("DB0002", "fmg", "INV-1")),
            emptyList(),
        )!!

        val invoice = plan.invoices.single()
        assertEquals("fmg", invoice.vendorKey)
        assertEquals("INV-1", invoice.invoiceNumber)
        assertNull(invoice.estimatedCents)
        assertNull(invoice.actualCents)
        assertEquals(listOf(invoice.id, invoice.id), plan.items.map { it.invoiceId })
        assertEquals(listOf(null, null), plan.items.map { it.invoiceNumber })
    }

    @Test
    fun `different numbers or vendors get different invoices`() {
        val plan = planInvoiceMigration(
            listOf(
                item("DB0001", "fmg", "INV-1"),
                item("DB0002", "fmg", "INV-2"),
                item("DB0003", "ac", "INV-1"),
            ),
            emptyList(),
        )!!

        assertEquals(3, plan.invoices.size)
        assertEquals(3, plan.items.map { it.invoiceId }.toSet().size)
    }

    @Test
    fun `rows without a legacy number get no invoice`() {
        val plan = planInvoiceMigration(
            listOf(item("DB0001", "fmg", "INV-1"), item("DB0002", "fmg", null), item("DB0003", "fmg", "  ")),
            emptyList(),
        )!!

        assertNotNull(plan.items[0].invoiceId)
        assertNull(plan.items[1].invoiceId)
        assertNull(plan.items[2].invoiceId)
        assertEquals(1, plan.invoices.size)
    }

    @Test
    fun `an existing invoice with the same vendor and number is reused`() {
        val existing = OrderInvoice(id = "inv-x", vendorKey = "fmg", invoiceNumber = "INV-1", actualCents = 500)
        val plan = planInvoiceMigration(listOf(item("DB0001", "fmg", "INV-1")), listOf(existing))!!

        assertEquals(listOf(existing), plan.invoices)
        assertEquals("inv-x", plan.items.single().invoiceId)
    }

    @Test
    fun `items that already have an invoice id are left alone`() {
        assertNull(
            planInvoiceMigration(
                listOf(item("DB0001", "fmg", "INV-1", invoiceId = "inv-x")),
                listOf(OrderInvoice(id = "inv-x", vendorKey = "fmg")),
            )
        )
    }

    @Test
    fun `vendorless rows are not migrated`() {
        assertNull(planInvoiceMigration(listOf(item("DB0001", "", "INV-1")), emptyList()))
    }

    @Test
    fun `running the plan twice changes nothing the second time`() {
        val first = planInvoiceMigration(listOf(item("DB0001", "fmg", "INV-1")), emptyList())!!

        assertNull(planInvoiceMigration(first.items, first.invoices))
        assertTrue(first.items.single().invoiceNumber == null)
    }
}
