package com.techyshishy.beadmanager.ui.orders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvoiceDialogTest {

    @Test
    fun `parses whole dollars and cents`() {
        assertEquals(4650, parseDollarsToCents("46.50"))
        assertEquals(4650, parseDollarsToCents("46.5"))
        assertEquals(4600, parseDollarsToCents("46"))
        assertEquals(5, parseDollarsToCents("0.05"))
    }

    @Test
    fun `accepts a leading dollar sign and surrounding spaces`() {
        assertEquals(1234, parseDollarsToCents("  $12.34 "))
    }

    @Test
    fun `rejects malformed or blank text`() {
        assertNull(parseDollarsToCents(""))
        assertNull(parseDollarsToCents("abc"))
        assertNull(parseDollarsToCents("1.234"))
        assertNull(parseDollarsToCents("-5"))
        assertNull(parseDollarsToCents("1,000"))
    }

    @Test
    fun `rejects an amount too large for an Int of cents`() {
        assertNull(parseDollarsToCents("99999999999"))
    }

    @Test
    fun `formats cents for a text field`() {
        assertEquals("46.50", formatCentsForInput(4650))
        assertEquals("0.05", formatCentsForInput(5))
        assertEquals("0.00", formatCentsForInput(0))
    }

    @Test
    fun `format and parse round-trip`() {
        listOf(0, 5, 99, 100, 123456).forEach {
            assertEquals(it, parseDollarsToCents(formatCentsForInput(it)))
        }
    }
}
