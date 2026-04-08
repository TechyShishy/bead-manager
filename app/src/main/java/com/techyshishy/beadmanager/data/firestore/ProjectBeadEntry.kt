package com.techyshishy.beadmanager.data.firestore

/**
 * A single bead in a project's bead list.
 *
 * Stored as an array element inside the parent [ProjectEntry] document — not a subcollection,
 * so reads and writes are always a single Firestore round-trip.
 *
 * [beadCode]    — catalog bead code (e.g. "DB0001"). Unique within a project bead list;
 *                 duplicate codes are rejected at the repository layer.
 * [targetGrams] — how much of this color the project requires in total across all orders.
 *
 * Vendor information is intentionally absent. Vendor selection happens at order time;
 * the project bead list expresses intent, not sourcing.
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class ProjectBeadEntry(
    val beadCode: String = "",
    val targetGrams: Double = 0.0,
)
