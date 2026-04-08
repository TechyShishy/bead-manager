package com.techyshishy.beadmanager.data.seed

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.techyshishy.beadmanager.data.db.BeadDao
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.VendorLinkDao
import com.techyshishy.beadmanager.data.db.VendorLinkEntity
import com.techyshishy.beadmanager.di.AppDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the local Room catalog from [CATALOG_ASSET] on first install and
 * whenever [CATALOG_VERSION] is bumped (e.g. after a new Miyuki release).
 *
 * The seeder is idempotent: INSERT OR IGNORE ensures re-runs on an
 * existing DB are no-ops. The DataStore version guard prevents unnecessary
 * re-parsing on every app start.
 *
 * To add a new vendor: add its key and display name to [VENDOR_DISPLAY_NAMES].
 * The JSON values for that key will be picked up automatically on the next
 * catalog version bump.
 */
@Singleton
class CatalogSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppDataStore private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val beadDao: BeadDao,
    private val vendorLinkDao: VendorLinkDao,
) {

    companion object {
        private const val CATALOG_VERSION = 2
        private const val CATALOG_ASSET = "delica-beads.json"

        private val KEY_CATALOG_VERSION = intPreferencesKey("catalog_version")

        val VENDOR_DISPLAY_NAMES: Map<String, String> = mapOf(
            "ac"  to "Aura Crystals",
            "fmg" to "Fire Mountain Gems",
            // Third vendor will be added here — key must match JSON field name.
        )

        // Canonical values for each finish-related property; used to populate filter chips.
        // Note: ALL_FINISHES filters the bead.finishes JSON array. ALL_GALVANIZED_VALUES and
        // ALL_PLATING_VALUES filter the separate scalar bead.galvanized / bead.plating columns;
        // the overlapping labels ("Galvanized", "Plating") select on different DB columns.
        val ALL_FINISHES: List<String> = listOf(
            "Color-Lined", "Copperline", "Duracoat", "Frosted (Matte)",
            "Galvanized", "Glass enamel", "Glazed", "Glazed Luster",
            "Gold Luster", "Goldline", "Luster", "Metallic",
            "Non-Extra Finish", "Outside-Dyed", "Plating", "Rainbow",
            "Silverline", "Special Coating",
        )
        val ALL_DYED_VALUES: List<String> = listOf(
            "Color-Lined", "Duracoat Outside-Dyed", "Non Dyed", "Outside Dyed",
        )
        val ALL_GALVANIZED_VALUES: List<String> = listOf(
            "Duracoat Galvanized", "Galvanized", "Non-galvanized",
        )
        val ALL_PLATING_VALUES: List<String> = listOf(
            "Copper", "Gold", "Nickel", "Non-Plating", "Palladium", "Silver",
        )
    }

    suspend fun seedIfNeeded() {
        val storedVersion = dataStore.data
            .map { prefs -> prefs[KEY_CATALOG_VERSION] ?: 0 }
            .first()

        if (storedVersion >= CATALOG_VERSION) return

        val json = Json { ignoreUnknownKeys = true }
        val raw = context.assets.open(CATALOG_ASSET).bufferedReader().readText()
        val catalog: Map<String, BeadJson> = json.decodeFromString(raw)

        val beadEntities = catalog.values.map { bead ->
            BeadEntity(
                code = bead.code,
                hex = bead.hex,
                imageUrl = bead.image,
                officialUrl = bead.url,
                colorGroup = json.encodeToString(ListSerializer(String.serializer()), bead.colorGroup),
                glassGroup = bead.glassGroup,
                finishes = json.encodeToString(ListSerializer(String.serializer()), bead.finish),
                dyed = bead.dyed,
                galvanized = bead.galvanized,
                plating = bead.plating,
            )
        }

        val vendorEntities = catalog.values.flatMap { bead ->
            bead.purchase.entries.map { (key, url) ->
                VendorLinkEntity(
                    beadCode = bead.code,
                    vendorKey = key,
                    displayName = VENDOR_DISPLAY_NAMES[key] ?: key,
                    url = url,
                    beadName = bead.names[key],
                )
            }
        }

        beadDao.insertAll(beadEntities)
        vendorLinkDao.insertAll(vendorEntities)

        dataStore.edit { prefs -> prefs[KEY_CATALOG_VERSION] = CATALOG_VERSION }
    }

    // ---------------------------------------------------------------------------
    // JSON shape — mirrors delica-beads.json exactly.
    // ---------------------------------------------------------------------------

    @Serializable
    private data class BeadJson(
        val code: String,
        val hex: String,
        val image: String,
        val url: String,
        val purchase: Map<String, String> = emptyMap(),
        val names: Map<String, String> = emptyMap(),
        @SerialName("color_group") val colorGroup: List<String> = emptyList(),
        @SerialName("glass_group") val glassGroup: String,
        val finish: List<String> = emptyList(),
        val dyed: String,
        val galvanized: String,
        val plating: String,
    )
}
