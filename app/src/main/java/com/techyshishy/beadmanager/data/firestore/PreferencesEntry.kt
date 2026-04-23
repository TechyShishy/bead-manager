package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.ServerTimestamp

/**
 * Firestore document model for a user's synced preferences.
 *
 * Document path: users/{uid}/preferences/main  (release)
 *                users_debug/{uid}/preferences/main  (debug)
 *
 * All fields carry defaults so Firestore deserialization succeeds when
 * individual fields are absent — this keeps the schema forward-compatible
 * as new preference fields are added over time.
 *
 * Migration flags (migration_*) are intentionally absent: they are
 * one-time device-local guards and must not sync across devices.
 */
data class PreferencesEntry(
    val globalLowStockThresholdGrams: Double = 5.0,
    /** Comma-delimited vendor key ordering, e.g. "fmg,ac". First entry is preferred. */
    val vendorPriorityOrder: String = "fmg,ac",
    val buyUpEnabled: Boolean = true,
    @ServerTimestamp val lastUpdated: Timestamp? = null,
)
