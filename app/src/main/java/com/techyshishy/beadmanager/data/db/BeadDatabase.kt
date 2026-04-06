package com.techyshishy.beadmanager.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local Room database for the immutable Miyuki Delica catalog.
 *
 * Version history:
 *   1 — initial schema: beads + vendor_links tables
 *
 * User inventory is intentionally NOT stored here; it lives in Firestore
 * so it syncs across devices automatically.
 */
@Database(
    entities = [BeadEntity::class, VendorLinkEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class BeadDatabase : RoomDatabase() {
    abstract fun beadDao(): BeadDao
    abstract fun vendorLinkDao(): VendorLinkDao
}
