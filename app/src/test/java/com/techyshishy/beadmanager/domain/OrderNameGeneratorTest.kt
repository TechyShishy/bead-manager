package com.techyshishy.beadmanager.domain

import com.google.firebase.Timestamp
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class OrderNameGeneratorTest {

    private val utc = ZoneId.of("UTC")

    private fun orderWithDateUtc(year: Int, month: Int, day: Int): OrderEntry {
        val instant = java.time.LocalDate.of(year, month, day)
            .atStartOfDay(utc)
            .toInstant()
        return OrderEntry(createdAt = Timestamp(instant.epochSecond, instant.nano))
    }

    @Test
    fun `custom name takes precedence over project names`() {
        val order = OrderEntry(customName = "My Custom Order")
        val result = OrderNameGenerator.displayName(order, listOf("Alpha", "Beta"))
        assertEquals("My Custom Order", result)
    }

    @Test
    fun `custom name takes precedence over restock fallback`() {
        val order = orderWithDateUtc(2025, 6, 15).copy(customName = "Summer Restock")
        val result = OrderNameGenerator.displayName(order, emptyList(), utc)
        assertEquals("Summer Restock", result)
    }

    @Test
    fun `blank custom name falls through to project names`() {
        val order = OrderEntry(customName = "  ")
        val result = OrderNameGenerator.displayName(order, listOf("Zebra", "Apple"))
        assertEquals("Apple / Zebra", result)
    }

    @Test
    fun `null custom name falls through to project names`() {
        val order = OrderEntry(customName = null)
        val result = OrderNameGenerator.displayName(order, listOf("Beta"))
        assertEquals("Beta", result)
    }

    @Test
    fun `project names are sorted alphabetically`() {
        val order = OrderEntry()
        val result = OrderNameGenerator.displayName(order, listOf("Zebra", "Apple", "Mango"))
        assertEquals("Apple / Mango / Zebra", result)
    }

    @Test
    fun `duplicate project names are deduplicated`() {
        val order = OrderEntry()
        val result = OrderNameGenerator.displayName(order, listOf("Alpha", "Alpha", "Beta"))
        assertEquals("Alpha / Beta", result)
    }

    @Test
    fun `single project name has no separator`() {
        val order = OrderEntry()
        val result = OrderNameGenerator.displayName(order, listOf("Alpha"))
        assertEquals("Alpha", result)
    }

    @Test
    fun `restock fallback uses YYYY-MM-DD date format`() {
        val order = orderWithDateUtc(2025, 3, 7)
        val result = OrderNameGenerator.displayName(order, emptyList(), utc)
        assertEquals("Restock Order (2025-03-07)", result)
    }

    @Test
    fun `restock fallback uses dash when createdAt is null`() {
        val order = OrderEntry(createdAt = null)
        val result = OrderNameGenerator.displayName(order, emptyList())
        assertEquals("Restock Order (—)", result)
    }

    @Test
    fun `blank project names are filtered out`() {
        val order = orderWithDateUtc(2025, 1, 1)
        val result = OrderNameGenerator.displayName(order, listOf("", "  "), utc)
        assertTrue(result.startsWith("Restock Order"))
    }
}
