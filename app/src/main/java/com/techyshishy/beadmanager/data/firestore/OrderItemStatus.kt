package com.techyshishy.beadmanager.data.firestore

import android.util.Log

/**
 * Status of a single line item within an order.
 *
 * Serialized to/from Firestore as lowercase strings (e.g. "pending", "received").
 * The [firestoreValue] property is the Firestore representation.
 */
enum class OrderItemStatus {
    PENDING,
    ORDERED,
    RECEIVED,
    SKIPPED;

    val firestoreValue: String get() = name.lowercase()

    companion object {
        fun fromFirestore(value: String): OrderItemStatus =
            entries.firstOrNull { it.firestoreValue == value } ?: run {
                Log.w("OrderItemStatus", "Unknown status value '$value', defaulting to PENDING")
                PENDING
            }
    }
}
