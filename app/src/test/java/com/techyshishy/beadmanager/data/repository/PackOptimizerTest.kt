package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.db.VendorPackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackOptimizerTest {

    private fun pack(
        grams: Double,
        priceCents: Int?,
        vendorKey: String = "fmg",
        beadCode: String = "DB0010",
        available: Boolean? = null,
    ) = VendorPackEntity(
        beadCode = beadCode,
        vendorKey = vendorKey,
        grams = grams,
        url = "https://example.test/$vendorKey/$grams",
        priceCents = priceCents,
        available = available,
    )

    // FMG DB0010 tier-1 prices.
    private val fmgDb0010 = listOf(pack(7.5, 420), pack(50.0, 2325), pack(250.0, 9126))

    private fun combo(vararg pairs: Pair<Double, Int>) =
        pairs.map { PackCombination(it.first, it.second) }

    @Test
    fun `prefers the cheaper two 50g packs over thirteen 7_5g packs that waste less`() {
        // Regression: 96.67g target. 13 x 7.5g (97.5g, $54.60) wastes least but 2 x 50g
        // (100g, $46.50) costs less.
        val result = computeCheapestCombination(fmgDb0010, 96.66999618530272)
        assertEquals(combo(50.0 to 2), result)
    }

    @Test
    fun `uses the smallest pack for a small target`() {
        assertEquals(combo(7.5 to 1), computeCheapestCombination(fmgDb0010, 5.0))
    }

    @Test
    fun `mixes pack sizes when that is cheapest`() {
        // 57.5g: 50g + 7.5g = $27.45 beats 2 x 50g = $46.50 and 8 x 7.5g = $33.60.
        assertEquals(combo(50.0 to 1, 7.5 to 1), computeCheapestCombination(fmgDb0010, 57.5))
    }

    @Test
    fun `an exact fit is returned when it is also cheapest`() {
        assertEquals(combo(7.5 to 2), computeCheapestCombination(fmgDb0010, 15.0))
    }

    @Test
    fun `breaks an exact cost and waste tie by fewest units`() {
        // 2 x 10g and 1 x 20g both cost 200 and total 20g; the single pack wins.
        val packs = listOf(pack(10.0, 100), pack(20.0, 200))
        assertEquals(combo(20.0 to 1), computeCheapestCombination(packs, 20.0))
    }

    @Test
    fun `on equal cost the combination with less waste wins`() {
        // A 26g pack and a 30g pack both cost 300; 26g reaches the 25g target with less waste.
        val packs = listOf(pack(26.0, 300), pack(30.0, 300))
        assertEquals(combo(26.0 to 1), computeCheapestCombination(packs, 25.0))
    }

    @Test
    fun `ignores packs without a price`() {
        val packs = listOf(pack(7.5, null), pack(50.0, 2325))
        assertEquals(combo(50.0 to 1), computeCheapestCombination(packs, 5.0))
    }

    @Test
    fun `returns empty when no priced pack exists`() {
        assertTrue(computeCheapestCombination(listOf(pack(7.5, null)), 10.0).isEmpty())
        assertTrue(computeCheapestCombination(emptyList(), 10.0).isEmpty())
    }

    @Test
    fun `returns empty for a non-positive or oversized target`() {
        assertTrue(computeCheapestCombination(fmgDb0010, 0.0).isEmpty())
        assertTrue(computeCheapestCombination(fmgDb0010, -1.0).isEmpty())
        assertTrue(computeCheapestCombination(fmgDb0010, 10_001.0).isEmpty())
    }

    @Test
    fun `selectPreferredVendor skips unavailable packs when costing`() {
        val packs = listOf(
            pack(7.5, 420), pack(50.0, 2325, available = false), pack(250.0, 9126),
        )
        val selection = selectPreferredVendor("DB0010", 96.67, packs, listOf("fmg"))!!
        // Without the 50g pack the cheapest cover is 13 x 7.5g ($54.60) vs 250g ($91.26).
        assertEquals(combo(7.5 to 13), selection.combination)
        assertEquals(13 * 420, selection.totalCents)
    }

    @Test
    fun `selectPreferredVendor follows priority order not price`() {
        val packs = fmgDb0010 + listOf(pack(50.0, 1000, vendorKey = "bob"))
        val selection = selectPreferredVendor("DB0010", 96.67, packs, listOf("fmg", "bob"))!!
        assertEquals("fmg", selection.vendorKey)
        assertEquals(combo(50.0 to 2), selection.combination)
        assertEquals(2 * 2325, selection.totalCents)
    }

    @Test
    fun `selectPreferredVendor returns null when no listed vendor has the bead`() {
        assertNull(selectPreferredVendor("DB0010", 10.0, fmgDb0010, listOf("ac")))
    }

    @Test
    fun `selectCheapestVendor picks the lowest total across vendors`() {
        val packs = fmgDb0010 + listOf(pack(50.0, 1000, vendorKey = "bob"))
        val selection = selectCheapestVendor("DB0010", 96.67, packs)!!
        assertEquals("bob", selection.vendorKey)
        assertEquals(2 * 1000, selection.totalCents)
    }
}
