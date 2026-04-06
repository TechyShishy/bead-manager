package com.techyshishy.beadmanager.data.db

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Result type for queries that need a bead together with all its vendor links.
 * Use with @Transaction queries in [BeadDao].
 */
data class BeadWithVendors(
    @Embedded val bead: BeadEntity,
    @Relation(
        parentColumn = "code",
        entityColumn = "beadCode",
    )
    val vendorLinks: List<VendorLinkEntity>,
)
