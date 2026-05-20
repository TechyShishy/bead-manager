package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.firestore.OrderEntry

/**
 * View-ready combination of an order and the display names of its associated projects.
 *
 * Produced by joining [OrderEntry] with the live projects list in [AllOrdersViewModel].
 * Never persisted directly.
 *
 * [projectNames] is the resolved list of project names for each ID in [OrderEntry.projectIds].
 * IDs that no longer correspond to an existing project are silently dropped.
 *
 * [displayName] is the computed or custom-override name ready for direct display. Screens should
 * use [displayName] rather than constructing a label from [projectNames] directly.
 */
data class AllOrderItem(
    val order: OrderEntry,
    val projectNames: List<String>,
    val displayName: String,
)
