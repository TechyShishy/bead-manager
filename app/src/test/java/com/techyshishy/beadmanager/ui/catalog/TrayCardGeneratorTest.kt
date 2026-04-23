package com.techyshishy.beadmanager.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class TrayCardGeneratorTest {

    @Test
    fun `pageCount returns 0 for empty list`() {
        assertEquals(0, pageCount(0))
    }

    @Test
    fun `pageCount returns 1 for a single code`() {
        assertEquals(1, pageCount(1))
    }

    @Test
    fun `pageCount returns 1 for exactly 50 codes`() {
        assertEquals(1, pageCount(TRAY_SLOTS_PER_PAGE))
    }

    @Test
    fun `pageCount returns 2 for 51 codes`() {
        assertEquals(2, pageCount(TRAY_SLOTS_PER_PAGE + 1))
    }

    @Test
    fun `pageCount returns 2 for 100 codes`() {
        assertEquals(2, pageCount(100))
    }

    @Test
    fun `pageCount returns 3 for 101 codes`() {
        assertEquals(3, pageCount(101))
    }
}
