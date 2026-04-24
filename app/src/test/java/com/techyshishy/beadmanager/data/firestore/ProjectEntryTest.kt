package com.techyshishy.beadmanager.data.firestore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEntryTest {

    @Test
    fun `isAllOriginalColors is true when no swaps have been made`() {
        val entry = ProjectEntry(
            projectId = "p1",
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0010"),
            originalColorMapping = emptyMap(),
        )
        assertTrue(entry.isAllOriginalColors)
    }

    @Test
    fun `isAllOriginalColors is true when all palette keys match their originals`() {
        val entry = ProjectEntry(
            projectId = "p2",
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0010"),
            originalColorMapping = mapOf("A" to "DB0001", "B" to "DB0010"),
        )
        assertTrue(entry.isAllOriginalColors)
    }

    @Test
    fun `isAllOriginalColors is false when one key differs from its original`() {
        val entry = ProjectEntry(
            projectId = "p3",
            colorMapping = mapOf("A" to "DB0999", "B" to "DB0010"),
            originalColorMapping = mapOf("A" to "DB0001", "B" to "DB0010"),
        )
        assertFalse(entry.isAllOriginalColors)
    }

    @Test
    fun `isAllOriginalColors is false when all keys differ from their originals`() {
        val entry = ProjectEntry(
            projectId = "p4",
            colorMapping = mapOf("A" to "DB0999", "B" to "DB0888"),
            originalColorMapping = mapOf("A" to "DB0001", "B" to "DB0010"),
        )
        assertFalse(entry.isAllOriginalColors)
    }

    @Test
    fun `isAllOriginalColors checks only originalColorMapping keys not all colorMapping keys`() {
        // "B" is in colorMapping but not in originalColorMapping — no swap was recorded for it
        val entry = ProjectEntry(
            projectId = "p5",
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0010"),
            originalColorMapping = mapOf("A" to "DB0001"),
        )
        assertTrue(entry.isAllOriginalColors)
    }

    @Test
    fun `isAllOriginalColors is false when a key in originalColorMapping is absent from colorMapping`() {
        // "A" was once in colorMapping but has since been removed entirely; the original is still
        // recorded — treated as a change, so the badge should not show.
        val entry = ProjectEntry(
            projectId = "p6",
            colorMapping = mapOf("B" to "DB0010"),
            originalColorMapping = mapOf("A" to "DB0001"),
        )
        assertFalse(entry.isAllOriginalColors)
    }
}
