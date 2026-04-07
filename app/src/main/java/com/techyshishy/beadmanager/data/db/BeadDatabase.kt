package com.techyshishy.beadmanager.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local Room database for the immutable Miyuki Delica catalog.
 *
 * Version history:
 *   1 — initial schema: beads + vendor_links tables
 *   2 — vendor_links.beadName added (vendor-specific bead name from `names` field);
 *       beads.colorGroup data migrated from plain string to JSON array
 *
 * User inventory is intentionally NOT stored here; it lives in Firestore
 * so it syncs across devices automatically.
 */
@Database(
    entities = [BeadEntity::class, VendorLinkEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class BeadDatabase : RoomDatabase() {
    abstract fun beadDao(): BeadDao
    abstract fun vendorLinkDao(): VendorLinkDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vendor_links ADD COLUMN beadName TEXT")
                // colorGroup was a plain string in v1; wrap each existing value in a JSON
                // array so the column is consistent with the new List<String> encoding used
                // by the seeder (e.g. "Gray" → ["Gray"]). We cannot use json_array() because
                // the JSON1 extension is a compile-time OEM option and is not guaranteed on
                // all Android builds. String concatenation is safe here: color_group was a
                // plain string (not an array) in the pre-v2 JSON, so every v1 DB row holds
                // a single ASCII word with no embedded quotes or backslashes.
                db.execSQL("UPDATE beads SET colorGroup = '[\"' || colorGroup || '\"]'")
            }
        }
    }
}
