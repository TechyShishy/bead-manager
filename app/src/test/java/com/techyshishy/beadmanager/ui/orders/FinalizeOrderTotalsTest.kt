package com.techyshishy.beadmanager.ui.orders

import com.techyshishy.beadmanager.domain.FinalizedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalizeOrderTotalsTest {

    private fun item(
        beadCode: String = "DB0001",
        vendorKey: String = "fmg",
        quantityUnits: Int = 1,
        priceCents: Int? = 299,
        fetchFailed: Boolean = false,
    ) = FinalizedItem(
        beadCode = beadCode,
        vendorKey = vendorKey,
        packGrams = 10.0,
        quantityUnits = quantityUnits,
        url = "",
        priceCents = priceCents,
        available = true,
        fetchFailed = fetchFailed,
    )

    @Test
    fun emptyListProducesZeroTotals() {
        val totals = computeOrderTotals(emptyList())
        assertEquals(0, totals.grandPackCount)
        assertEquals(0, totals.grandKnownCostCents)
        assertFalse(totals.hasUnknownPrice)
        assertTrue(totals.byVendor.isEmpty())
    }

    @Test
    fun singleItemAllPricesKnown() {
        val totals = computeOrderTotals(listOf(item(quantityUnits = 3, priceCents = 299)))
        assertEquals(3, totals.grandPackCount)
        assertEquals(897, totals.grandKnownCostCents) // 3 × 299
        assertFalse(totals.hasUnknownPrice)
    }

    @Test
    fun nullPriceSetsHasUnknownPriceAndExcludesFromCost() {
        val items = listOf(
            item(beadCode = "DB0001", quantityUnits = 2, priceCents = 500),
            item(beadCode = "DB0002", quantityUnits = 1, priceCents = null),
        )
        val totals = computeOrderTotals(items)
        assertEquals(3, totals.grandPackCount)
        assertEquals(1000, totals.grandKnownCostCents) // only 2 × 500; null excluded
        assertTrue(totals.hasUnknownPrice)
    }

    @Test
    fun fetchFailedSetsHasUnknownPriceAndExcludesFromCost() {
        val items = listOf(
            item(beadCode = "DB0001", quantityUnits = 1, priceCents = 300, fetchFailed = false),
            item(beadCode = "DB0002", quantityUnits = 2, priceCents = 200, fetchFailed = true),
        )
        val totals = computeOrderTotals(items)
        assertEquals(3, totals.grandPackCount)
        assertEquals(300, totals.grandKnownCostCents) // fetchFailed item excluded
        assertTrue(totals.hasUnknownPrice)
    }

    @Test
    fun perVendorSubtotalsAreIndependent() {
        val items = listOf(
            item(beadCode = "DB0001", vendorKey = "fmg", quantityUnits = 2, priceCents = 300),
            item(beadCode = "DB0002", vendorKey = "fmg", quantityUnits = 1, priceCents = 200),
            item(beadCode = "DB0003", vendorKey = "ac",  quantityUnits = 3, priceCents = 150),
        )
        val totals = computeOrderTotals(items)

        val fmg = totals.byVendor.getValue("fmg")
        assertEquals(3, fmg.packCount)          // 2 + 1
        assertEquals(800, fmg.knownCostCents)   // 2×300 + 1×200
        assertFalse(fmg.hasUnknownPrice)

        val ac = totals.byVendor.getValue("ac")
        assertEquals(3, ac.packCount)
        assertEquals(450, ac.knownCostCents)    // 3×150
        assertFalse(ac.hasUnknownPrice)

        assertEquals(6, totals.grandPackCount)
        assertEquals(1250, totals.grandKnownCostCents) // 800 + 450
        assertFalse(totals.hasUnknownPrice)
    }

    @Test
    fun vendorWithUnknownPriceDoesNotAffectOtherVendorSubtotal() {
        val items = listOf(
            item(beadCode = "DB0001", vendorKey = "fmg", quantityUnits = 1, priceCents = 500),
            item(beadCode = "DB0002", vendorKey = "ac",  quantityUnits = 1, priceCents = null),
        )
        val totals = computeOrderTotals(items)

        val fmg = totals.byVendor.getValue("fmg")
        assertFalse(fmg.hasUnknownPrice)
        assertEquals(500, fmg.knownCostCents)

        val ac = totals.byVendor.getValue("ac")
        assertTrue(ac.hasUnknownPrice)
        assertEquals(0, ac.knownCostCents)

        assertTrue(totals.hasUnknownPrice) // grand total is partial
    }

    @Test
    fun formatSubtotalCostAllPricesKnown() {
        assertEquals("\$8.97", formatSubtotalCost(897, false))
    }

    @Test
    fun formatSubtotalCostWithUnknownPriceAppendsSuffix() {
        assertEquals("\$8.97 + ?", formatSubtotalCost(897, true))
    }

    @Test
    fun formatSubtotalCostZeroCentsWithUnknownPrice() {
        assertEquals("\$0.00 + ?", formatSubtotalCost(0, true))
    }
}
