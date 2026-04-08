package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

/**
 * A purchase shopping session for a project.
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
 * Default values are required for Firestore no-argument deserialization.
 */
data class OrderEntry(
    @DocumentId val orderId: String = "",
    val projectId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val lastUpdated: Timestamp? = null,
    val items: List<OrderItemEntry> = emptyList(),
)
