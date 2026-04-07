package com.techyshishy.beadmanager.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A purchase link for a specific bead at a specific vendor.
 *
 * [vendorKey] is the short key used in the JSON ("ac", "fmg", etc.).
 * [displayName] is the human-readable name shown in the UI.
 * Adding a new vendor requires only new rows here — no schema change.
 */
@Entity(
    tableName = "vendor_links",
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
        // Compound unique index ensures INSERT OR IGNORE actually deduplicates when
        // the seeder is re-run after an interrupted first run.
        Index(value = ["beadCode", "vendorKey"], unique = true),
    ],
)
data class VendorLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val beadCode: String,
    val vendorKey: String,
    val displayName: String,
    val url: String,
    val beadName: String? = null,
)
