package com.techyshishy.beadmanager.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import com.techyshishy.beadmanager.di.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepository @Inject constructor(
    @AppDataStore private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_GLOBAL_LOW_STOCK_THRESHOLD =
            doublePreferencesKey("global_low_stock_threshold_grams")
        private val KEY_MIGRATION_THRESHOLD_V1 =
            booleanPreferencesKey("migration_threshold_v1_done")
        private val KEY_MIGRATION_ORDER_PROJECT_IDS_V1 =
            booleanPreferencesKey("migration_order_project_ids_v1_done")
        const val DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD = 5.0
    }

    val globalLowStockThreshold: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_GLOBAL_LOW_STOCK_THRESHOLD] ?: DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD
    }

    suspend fun setGlobalLowStockThreshold(grams: Double) {
        dataStore.edit { prefs ->
            prefs[KEY_GLOBAL_LOW_STOCK_THRESHOLD] = grams.coerceIn(1.0, 30.0)
        }
    }

    val migrationThresholdV1Done: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_MIGRATION_THRESHOLD_V1] ?: false
    }

    suspend fun setMigrationThresholdV1Done() {
        dataStore.edit { prefs -> prefs[KEY_MIGRATION_THRESHOLD_V1] = true }
    }

    val migrationOrderProjectIdsV1Done: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_MIGRATION_ORDER_PROJECT_IDS_V1] ?: false
    }

    suspend fun setMigrationOrderProjectIdsV1Done() {
        dataStore.edit { prefs -> prefs[KEY_MIGRATION_ORDER_PROJECT_IDS_V1] = true }
    }
}
