package com.techyshishy.beadmanager.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.auth.FirebaseAuth
import com.techyshishy.beadmanager.data.firestore.FirestorePreferencesSource
import com.techyshishy.beadmanager.data.firestore.PreferencesEntry
import com.techyshishy.beadmanager.di.AppDataStore
import com.techyshishy.beadmanager.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepository @Inject constructor(
    @AppDataStore private val dataStore: DataStore<Preferences>,
    private val firestoreSource: FirestorePreferencesSource,
    private val auth: FirebaseAuth,
    @AppScope private val appScope: CoroutineScope,
) {
    companion object {
        private val KEY_GLOBAL_LOW_STOCK_THRESHOLD =
            doublePreferencesKey("global_low_stock_threshold_grams")
        private val KEY_MIGRATION_THRESHOLD_V1 =
            booleanPreferencesKey("migration_threshold_v1_done")
        private val KEY_MIGRATION_ORDER_PROJECT_IDS_V1 =
            booleanPreferencesKey("migration_order_project_ids_v1_done")
        /** Comma-delimited vendor key ordering, e.g. "fmg,ac". First entry is preferred. */
        private val KEY_VENDOR_PRIORITY_ORDER =
            stringPreferencesKey("vendor_priority_order")
        private val KEY_BUY_UP_ENABLED =
            booleanPreferencesKey("buy_up_enabled")
        private val KEY_TRAY_CARD_MAX_GRAMS =
            doublePreferencesKey("tray_card_max_grams")
        private val KEY_MIGRATION_FLAT_PROJECT_TO_GRID_V1 =
            booleanPreferencesKey("migration_flat_project_to_grid_v1_done")
        private val KEY_MIGRATION_INLINE_ROWS_V1 =
            booleanPreferencesKey("migration_inline_rows_v1_done")
        private val KEY_MIGRATION_BOB_VENDOR_V1 =
            booleanPreferencesKey("migration_bob_vendor_v1_done")
        const val DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD = 5.0
        val DEFAULT_VENDOR_PRIORITY_ORDER: List<String> = listOf("fmg", "bob", "ac")
        const val DEFAULT_BUY_UP_ENABLED = true
        const val DEFAULT_TRAY_CARD_MAX_GRAMS = 10.0
    }

    /**
     * Shared auth-state flow. Emits the current user's uid when signed in, null when signed
     * out. shareIn(Eagerly) starts collecting immediately so the Firebase auth listener is
     * registered at construction time; replay=1 ensures new downstream collectors get the
     * current auth state without waiting for the next state change.
     */
    private val authUidFlow: Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.shareIn(appScope, SharingStarted.Eagerly, replay = 1)

    /**
     * On sign-in, push the local DataStore snapshot to Firestore if no preferences document
     * exists yet. This is a one-time upload per account (first-device-wins semantics): once
     * the document exists, subsequent sign-ins from any device are no-ops here.
     */
    init {
        appScope.launch {
            authUidFlow
                .distinctUntilChanged()
                .collect { uid ->
                    if (uid != null) {
                        try {
                            val prefs = dataStore.data.first()
                            firestoreSource.bootstrapIfAbsent(
                                uid,
                                PreferencesEntry(
                                    globalLowStockThresholdGrams = (
                                        prefs[KEY_GLOBAL_LOW_STOCK_THRESHOLD]
                                            ?: DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD
                                    ).coerceIn(1.0, 100.0),
                                    vendorPriorityOrder = prefs[KEY_VENDOR_PRIORITY_ORDER]
                                        ?: DEFAULT_VENDOR_PRIORITY_ORDER.joinToString(","),
                                    buyUpEnabled = prefs[KEY_BUY_UP_ENABLED] ?: DEFAULT_BUY_UP_ENABLED,
                                    trayCardMaxGrams = (
                                        prefs[KEY_TRAY_CARD_MAX_GRAMS]
                                            ?: DEFAULT_TRAY_CARD_MAX_GRAMS
                                    ).coerceIn(1.0, 50.0),
                                ),
                            )
                        } catch (e: Exception) {
                            Log.e("PreferencesRepository", "Bootstrap failed for uid=$uid", e)
                        }
                    }
                }
        }
    }

    /**
     * Emits the signed-in user's Firestore-synced threshold, or the DataStore value when
     * signed out. Reading from DataStore when signed out keeps writes and reads consistent
     * for offline/unauthenticated use — the DataStore snapshot is also what gets uploaded
     * to Firestore as the initial preferences document on next sign-in.
     */
    val globalLowStockThreshold: Flow<Double> = authUidFlow.flatMapLatest { uid ->
        if (uid == null) {
            dataStore.data.map { prefs ->
                prefs[KEY_GLOBAL_LOW_STOCK_THRESHOLD] ?: DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD
            }
        } else {
            firestoreSource.preferencesStream(uid).map { entry ->
                entry?.globalLowStockThresholdGrams ?: DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD
            }
        }
    }

    suspend fun setGlobalLowStockThreshold(grams: Double) {
        val coerced = grams.coerceIn(1.0, 100.0)
        dataStore.edit { prefs -> prefs[KEY_GLOBAL_LOW_STOCK_THRESHOLD] = coerced }
        auth.currentUser?.uid?.let { uid ->
            firestoreSource.setPreferences(uid, mapOf("globalLowStockThresholdGrams" to coerced))
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

    val vendorPriorityOrder: Flow<List<String>> = authUidFlow.flatMapLatest { uid ->
        if (uid == null) {
            dataStore.data.map { prefs ->
                prefs[KEY_VENDOR_PRIORITY_ORDER]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_VENDOR_PRIORITY_ORDER
            }
        } else {
            firestoreSource.preferencesStream(uid).map { entry ->
                entry?.vendorPriorityOrder
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_VENDOR_PRIORITY_ORDER
            }
        }
    }

    suspend fun setVendorPriorityOrder(order: List<String>) {
        val joined = order.joinToString(",")
        dataStore.edit { prefs -> prefs[KEY_VENDOR_PRIORITY_ORDER] = joined }
        auth.currentUser?.uid?.let { uid ->
            firestoreSource.setPreferences(uid, mapOf("vendorPriorityOrder" to joined))
        }
    }

    val buyUpEnabled: Flow<Boolean> = authUidFlow.flatMapLatest { uid ->
        if (uid == null) {
            dataStore.data.map { prefs -> prefs[KEY_BUY_UP_ENABLED] ?: DEFAULT_BUY_UP_ENABLED }
        } else {
            firestoreSource.preferencesStream(uid).map { entry ->
                entry?.buyUpEnabled ?: DEFAULT_BUY_UP_ENABLED
            }
        }
    }

    suspend fun setBuyUpEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_BUY_UP_ENABLED] = enabled }
        auth.currentUser?.uid?.let { uid ->
            firestoreSource.setPreferences(uid, mapOf("buyUpEnabled" to enabled))
        }
    }

    val trayCardMaxGrams: Flow<Double> = authUidFlow.flatMapLatest { uid ->
        if (uid == null) {
            dataStore.data.map { prefs -> prefs[KEY_TRAY_CARD_MAX_GRAMS] ?: DEFAULT_TRAY_CARD_MAX_GRAMS }
        } else {
            firestoreSource.preferencesStream(uid).map { entry ->
                entry?.trayCardMaxGrams ?: DEFAULT_TRAY_CARD_MAX_GRAMS
            }
        }
    }

    suspend fun setTrayCardMaxGrams(grams: Double) {
        val coerced = grams.coerceIn(1.0, 50.0)
        dataStore.edit { prefs -> prefs[KEY_TRAY_CARD_MAX_GRAMS] = coerced }
        auth.currentUser?.uid?.let { uid ->
            firestoreSource.setPreferences(uid, mapOf("trayCardMaxGrams" to coerced))
        }
    }

    val migrationFlatProjectToGridV1Done: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_MIGRATION_FLAT_PROJECT_TO_GRID_V1] ?: false
    }

    suspend fun setMigrationFlatProjectToGridV1Done() {
        dataStore.edit { prefs -> prefs[KEY_MIGRATION_FLAT_PROJECT_TO_GRID_V1] = true }
    }

    val migrationInlineRowsV1Done: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_MIGRATION_INLINE_ROWS_V1] ?: false
    }

    suspend fun setMigrationInlineRowsV1Done() {
        dataStore.edit { prefs -> prefs[KEY_MIGRATION_INLINE_ROWS_V1] = true }
    }

    val migrationBobVendorV1Done: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_MIGRATION_BOB_VENDOR_V1] ?: false
    }

    suspend fun setMigrationBobVendorV1Done() {
        dataStore.edit { prefs -> prefs[KEY_MIGRATION_BOB_VENDOR_V1] = true }
    }
}
