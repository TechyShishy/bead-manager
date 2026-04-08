package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp

/**
 * A single line item within an [OrderEntry].
 *
 * Identity is (beadCode + vendorKey) — exactly one entry per bead-vendor pair per order.
 *
 * The purchase URL is NOT stored here. At render time it is resolved from Room:
 *   VendorPackDao.packUrl(beadCode, vendorKey, packGrams)
 * This keeps Firestore lean and guarantees URLs stay current when the catalog is re-seeded.
 *
 * [targetGrams]   — how much of this color the project requires.
 * [packGrams]     — the pack size the user selected; must match a vendor_packs row.
 *                   INVARIANT: must be a verbatim copy of VendorPackEntity.grams — never
 *                   arithmetically derived. VendorPackDao.packUrl() uses floating-point
 *                   equality to resolve the URL, which is only safe with the original value.
 * [quantityUnits] — ceil(targetGrams / packGrams); stored to avoid per-render recompute.
 * [status]        — lowercase string from [OrderItemStatus.firestoreValue].
 * [receivedAt]    — null until status transitions to "received". Set to client Timestamp.now()
 *                   on first receive; preserved on retries. Cannot use @ServerTimestamp here
 *                   because FieldValue.serverTimestamp() is not supported inside array elements.
 * [appliedToInventory] — idempotency guard; true after the inventory delta has been posted.
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class OrderItemEntry(
    val beadCode: String = "",
    val vendorKey: String = "",
    val targetGrams: Double = 0.0,
    val packGrams: Double = 0.0,
    val quantityUnits: Int = 0,
    val status: String = OrderItemStatus.PENDING.firestoreValue,
    val receivedAt: Timestamp? = null,
    val appliedToInventory: Boolean = false,
)
