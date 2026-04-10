package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

/**
 * A purchase shopping session that may span one or more projects.
 *
 * Firestore path: users/{uid}/orders/{orderId}
 * Debug path:     users_debug/{uid}/orders/{orderId}
 *
 * Items are embedded as an array — orders are always read as a unit, making a
 * subcollection an unnecessary extra round-trip.
 *
 * [createdAt] and [lastUpdated] are both @ServerTimestamp. On creation both are
 * filled in one write. On item updates, only [lastUpdated] is included in the
 * partial write (via SetOptions.merge()), so [createdAt] is never overwritten.
 *
 * [projectIds] is the canonical many-to-many association. New writes always populate
 * this field. [projectId] is the legacy 1:1 field retained as a read-only fallback so
 * the Firestore deserializer can still populate it from pre-migration documents; nothing
 * writes it after the schema change. A one-time migration backfills [projectId] into
 * [projectIds] for all existing documents (see MigrationViewModel).
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class OrderEntry(
    @DocumentId val orderId: String = "",
    @Deprecated("Backfilled into projectIds by migration; do not write. Read only as migration fallback.")
    val projectId: String = "",
    val projectIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val lastUpdated: Timestamp? = null,
    val items: List<OrderItemEntry> = emptyList(),
)
