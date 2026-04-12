package com.techyshishy.beadmanager.data.db

import android.util.Log
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local Room database for the immutable Miyuki Delica catalog.
 *
 * Version history:
 *   1 — initial schema: beads + vendor_links tables
 *   2 — vendor_links.beadName added (vendor-specific bead name from `names` field);
 *       beads.colorGroup data migrated from plain string to JSON array
 *   3 — vendor_packs table added; stores per-pack-size purchase URLs for each
 *       (beadCode, vendorKey) pair, replacing the single URL in vendor_links
 *   4 — vendor_packs gains priceCents (INT nullable), available (INT nullable 0/1),
 *       and lastCheckedEpochSeconds (INT nullable) for live price-check results
 *   5 — vendor_packs gains tier2PriceCents, tier3PriceCents, tier4PriceCents (INT nullable)
 *       for FMG quantity-break discount tiers (qty 15–49, 50–99, 100+)
 *
 * User inventory is intentionally NOT stored here; it lives in Firestore
 * so it syncs across devices automatically.
 */
@Database(
    entities = [BeadEntity::class, VendorLinkEntity::class, VendorPackEntity::class],
    version = 5,
    exportSchema = true,
)
@TypeConverters(BeadDatabase.Converters::class)
abstract class BeadDatabase : RoomDatabase() {
    abstract fun beadDao(): BeadDao
    abstract fun vendorLinkDao(): VendorLinkDao
    abstract fun vendorPackDao(): VendorPackDao

    class Converters {
        private val json = Json { ignoreUnknownKeys = true }

        @TypeConverter
        fun fromStringList(value: List<String>): String = json.encodeToString(value)

        @TypeConverter
        fun toStringList(value: String): List<String> =
            runCatching { json.decodeFromString<List<String>>(value) }.getOrElse { e ->
                Log.e("BeadDatabase", "Failed to decode string list: $value", e)
                emptyList()
            }
    }

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS vendor_packs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        beadCode TEXT NOT NULL,
                        vendorKey TEXT NOT NULL,
                        grams REAL NOT NULL,
                        url TEXT NOT NULL,
                        FOREIGN KEY (beadCode) REFERENCES beads(code) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_vendor_packs_beadCode ON vendor_packs (beadCode)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_vendor_packs_beadCode_vendorKey_grams ON vendor_packs (beadCode, vendorKey, grams)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vendor_packs ADD COLUMN priceCents INTEGER")
                db.execSQL("ALTER TABLE vendor_packs ADD COLUMN available INTEGER")
                db.execSQL("ALTER TABLE vendor_packs ADD COLUMN lastCheckedEpochSeconds INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vendor_packs ADD COLUMN tier2PriceCents INTEGER")
                db.execSQL("ALTER TABLE vendor_packs ADD COLUMN tier3PriceCents INTEGER")
                db.execSQL("ALTER TABLE vendor_packs ADD COLUMN tier4PriceCents INTEGER")
            }
        }
    }
}
