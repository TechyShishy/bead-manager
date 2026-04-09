package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp

/**
 * A single line item within an [OrderEntry].
 *
 * Identity is (beadCode + vendorKey + packGrams) — an order may have multiple entries for the
 * same bead-vendor pair when different pack sizes are needed to reach the target quantity.
 *
 * The purchase URL is NOT stored here. At render time it is resolved from Room:
 *   VendorPackDao.packUrl(beadCode, vendorKey, packGrams)
 * This keeps Firestore lean and guarantees URLs stay current when the catalog is re-seeded.
 *
 * [targetGrams]   — grams still needed at order creation time (project target − inventory
 *                   at snapshot). Set to 0.0 if inventory already covers the target.
 * [packGrams]     — the pack size the user selected; must match a vendor_packs row.
 *                   INVARIANT: must be a verbatim copy of VendorPackEntity.grams — never
 *                   arithmetically derived. VendorPackDao.packUrl() uses floating-point
 *                   equality to resolve the URL, which is only safe with the original value.
 * [quantityUnits] — the number of units of [packGrams] to order for this row; set from the
 *                   DP combination result. Stored to avoid per-render recompute.
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
