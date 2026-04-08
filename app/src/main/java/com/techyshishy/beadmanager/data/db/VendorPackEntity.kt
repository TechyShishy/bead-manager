package com.techyshishy.beadmanager.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A specific purchase option (pack size) for a bead at a vendor.
 *
 * Each (beadCode, vendorKey) pair may have multiple entries — one per pack size
 * offered by that vendor. The [grams] + [url] pair uniquely identifies a purchasable SKU.
 *
 * This table is seeded from the `purchase` field in delica-beads.json and is read-only
 * from the application layer — never written to except during seeding.
 *
 * Order line items reference a pack by (beadCode, vendorKey, grams) to resolve the
 * purchase URL at render time without storing it in Firestore.
 */
@Entity(
    tableName = "vendor_packs",
    foreignKeys = [
        ForeignKey(
            entity = BeadEntity::class,
            parentColumns = ["code"],
            childColumns = ["beadCode"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("beadCode"),
        Index(value = ["beadCode", "vendorKey", "grams"], unique = true),
    ],
)
data class VendorPackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val beadCode: String,
    val vendorKey: String,
    val grams: Double,
    val url: String,
)
