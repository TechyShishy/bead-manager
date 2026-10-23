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
        stockQuantity: Int? = null,
    ) = VendorPackEntity(
        beadCode = beadCode,
        vendorKey = vendorKey,
        grams = grams,
        url = "https://example.test/$vendorKey/$grams",
        priceCents = priceCents,
        available = available,
        stockQuantity = stockQuantity,
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

    // ---- stock caps ----

    @Test
    fun `a stock cap below the needed units makes the vendor unable to cover the target`() {
        // Regression: 3 x 50g needed but only 1 in stock and no other pack size.
        val packs = listOf(pack(50.0, 2325, stockQuantity = 1))
        assertEquals(emptyList<PackCombination>(), computeCheapestCombination(packs, 150.0))
    }

    @Test
    fun `a capped pack is used up to its stock and uncapped packs cover the rest`() {
        val packs = listOf(pack(7.5, 420), pack(50.0, 2325, stockQuantity = 1))
        // 100g: one 50g (cap) plus 7 x 7.5g = 102.5g ($23.25 + $29.40) vs 14 x 7.5g = $58.80.
        assertEquals(combo(50.0 to 1, 7.5 to 7), computeCheapestCombination(packs, 100.0))
    }

    @Test
    fun `a pack with zero stock is ignored`() {
        val packs = listOf(pack(7.5, 420), pack(50.0, 2325, stockQuantity = 0))
        assertEquals(combo(7.5 to 7), computeCheapestCombination(packs, 50.0))
    }

    @Test
    fun `a cap that exactly covers the target is accepted`() {
        val packs = listOf(pack(50.0, 2325, stockQuantity = 3))
        assertEquals(combo(50.0 to 3), computeCheapestCombination(packs, 150.0))
    }

    @Test
    fun `selectPreferredVendor skips a vendor whose stock cannot cover the target`() {
        val packs = listOf(
            pack(50.0, 3000, vendorKey = "ac", stockQuantity = 1),
            pack(50.0, 3500, vendorKey = "fmg"),
        )
        val result = selectPreferredVendor("DB0010", 150.0, packs, listOf("ac", "fmg"))
        assertEquals("fmg", result?.vendorKey)
        assertEquals(combo(50.0 to 3), result?.combination)
    }

    // ---- cross-vendor allocation ----

    @Test
    fun `selectAllocation uses a single vendor when it can cover the target`() {
        val packs = listOf(pack(50.0, 2325, vendorKey = "fmg"))
        val result = selectAllocation("DB0010", 50.0, packs, listOf("ac", "fmg"))!!
        assertEquals(listOf("fmg"), result.map { it.vendorKey })
    }

    @Test
    fun `selectAllocation splits a global shortage across vendors in priority order`() {
        // Need 3 x 50g and every vendor has exactly 1.
        val packs = listOf(
            pack(50.0, 3000, vendorKey = "ac", stockQuantity = 1),
            pack(50.0, 3200, vendorKey = "bob", stockQuantity = 1),
            pack(50.0, 3500, vendorKey = "fmg", stockQuantity = 1),
        )
        val result = selectAllocation("DB0010", 150.0, packs, listOf("ac", "bob", "fmg"))!!
        assertEquals(listOf("ac", "bob", "fmg"), result.map { it.vendorKey })
        assertEquals(
            listOf(combo(50.0 to 1), combo(50.0 to 1), combo(50.0 to 1)),
            result.map { it.combination },
        )
        assertEquals(listOf(3000, 3200, 3500), result.map { it.totalCents })
    }

    @Test
    fun `selectAllocation splits 3 needed as 1 here and 2 there`() {
        val packs = listOf(
            pack(50.0, 3000, vendorKey = "ac", stockQuantity = 1),
            pack(50.0, 3500, vendorKey = "fmg"),
        )
        // AC alone cannot cover 150g, but FMG alone can, so no split is needed.
        val single = selectAllocation("DB0010", 150.0, packs, listOf("ac", "fmg"))!!
        assertEquals(listOf("fmg"), single.map { it.vendorKey })

        val capped = listOf(
            pack(50.0, 3000, vendorKey = "ac", stockQuantity = 1),
            pack(50.0, 3500, vendorKey = "fmg", stockQuantity = 2),
        )
        val split = selectAllocation("DB0010", 150.0, capped, listOf("ac", "fmg"))!!
        assertEquals(listOf("ac", "fmg"), split.map { it.vendorKey })
        assertEquals(listOf(combo(50.0 to 1), combo(50.0 to 2)), split.map { it.combination })
    }

    @Test
    fun `selectAllocation returns null when all vendors together fall short`() {
        val packs = listOf(
            pack(50.0, 3000, vendorKey = "ac", stockQuantity = 1),
            pack(50.0, 3500, vendorKey = "fmg", stockQuantity = 1),
        )
        assertNull(selectAllocation("DB0010", 150.0, packs, listOf("ac", "fmg")))
    }

    @Test
    fun `selectAllocation skips vendors outside the priority order`() {
        val packs = listOf(
            pack(50.0, 3000, vendorKey = "ac", stockQuantity = 1),
            pack(50.0, 3500, vendorKey = "fmg", stockQuantity = 5),
        )
        assertNull(selectAllocation("DB0010", 100.0, packs, listOf("ac")))
    }
}
