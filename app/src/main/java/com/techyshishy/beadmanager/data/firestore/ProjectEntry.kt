package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

/**
 * A user-defined project that groups a bead list and one or more orders.
 *
 * Firestore path: users/{uid}/projects/{projectId}
 * Debug path:     users_debug/{uid}/projects/{projectId}
 *
 * The bead list is embedded as an array of [ProjectBeadEntry] — projects are always read as a
 * unit, keeping the bead list update a single Firestore write. Orders are a separate collection
 * and reference their project by [projectId].
 *
 * Existing project documents without a [beads] field deserialize safely to an empty list.
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class ProjectEntry(
    @DocumentId val projectId: String = "",
    val name: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    val notes: String? = null,
    val beads: List<ProjectBeadEntry> = emptyList(),
)
