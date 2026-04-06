package com.techyshishy.beadmanager.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Immutable catalog record for a single Delica bead variant.
 * Seeded once from delica-beads.json and never mutated by the user.
 *
 * [finishes] is stored as a JSON-encoded string and converted by
 * [BeadDatabase.Converters].
 */
@Entity(tableName = "beads")
data class BeadEntity(
    @PrimaryKey val code: String,
    val hex: String,
    val imageUrl: String,
    val officialUrl: String,
    val colorGroup: String,
    val glassGroup: String,
    val finishes: String,   // JSON array, e.g. ["Metallic","Rainbow"]
    val dyed: String,
    val galvanized: String,
    val plating: String,
)
