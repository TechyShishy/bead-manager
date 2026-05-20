package com.techyshishy.beadmanager.domain

import com.techyshishy.beadmanager.data.firestore.OrderEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object OrderNameGenerator {

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Returns the display name for [order] given the resolved [projectNames].
     *
     * Priority:
     * 1. [OrderEntry.customName] if non-null and non-blank.
     * 2. Project names joined alphabetically with " / " separator.
     * 3. "Restock Order (YYYY-MM-DD)" when no project names are available.
     *
     * [zone] controls which local date the timestamp is rendered in. Callers should pass
     * [ZoneId.systemDefault] explicitly so tests can inject a fixed zone without mutating
     * JVM global state. The default is provided as a convenience for production call sites.
     */
    fun displayName(
        order: OrderEntry,
        projectNames: List<String>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val custom = order.customName?.takeIf { it.isNotBlank() }
        if (custom != null) return custom

        val sorted = projectNames.filter { it.isNotBlank() }.distinct().sorted()
        if (sorted.isNotEmpty()) return sorted.joinToString(" / ")

        val dateStr = order.createdAt?.let { ts ->
            val instant = Instant.ofEpochSecond(ts.seconds, ts.nanoseconds.toLong())
            DATE_FORMATTER.format(instant.atZone(zone))
        } ?: "—"
        return "Restock Order ($dateStr)"
    }
}
