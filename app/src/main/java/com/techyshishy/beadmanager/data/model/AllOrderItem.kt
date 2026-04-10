package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.firestore.OrderEntry

/**
 * View-ready combination of an order and the display names of its associated projects.
 *
 * Produced by joining [OrderEntry] with the live projects list in [AllOrdersViewModel].
 * Never persisted directly.
 *
 * [projectNames] is the resolved list of project names for each ID in [OrderEntry.projectIds].
 * IDs that no longer correspond to an existing project are silently dropped. If the list is
 * empty (all projects deleted, or a pre-migration document with an unresolvable legacy
 * [OrderEntry.projectId]), the screen should show an appropriate fallback label.
 */
data class AllOrderItem(
    val order: OrderEntry,
    val projectNames: List<String>,
)
