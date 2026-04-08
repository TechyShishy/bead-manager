package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

/**
 * A user-defined project that groups one or more orders.
 *
 * Firestore path: users/{uid}/projects/{projectId}
 * Debug path:     users_debug/{uid}/projects/{projectId}
 *
 * Projects hold no order data — orders reference their project by [projectId].
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class ProjectEntry(
    @DocumentId val projectId: String = "",
    val name: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    val notes: String? = null,
)
