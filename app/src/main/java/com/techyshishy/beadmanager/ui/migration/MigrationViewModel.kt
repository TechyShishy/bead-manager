package com.techyshishy.beadmanager.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs one-time data migrations when first composed inside the authenticated shell.
 *
 * The [init] block fires a coroutine that checks the migration flag in DataStore and,
 * if the migration has not yet run, performs it and records completion. Because this
 * ViewModel is scoped to the Activity and [hiltViewModel] returns the same instance
 * on every recomposition, the migration runs exactly once per app process.
 */
@HiltViewModel
class MigrationViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val inventoryRepository: InventoryRepository,
) : ViewModel() {

    init {
        viewModelScope.launch {
            try {
                val done = preferencesRepository.migrationThresholdV1Done.first()
                if (!done) {
                    // Migrate legacy per-bead thresholds that were stored at the factory
                    // default (5.0 g) — indistinguishable from a user-set value — to the
                    // sentinel (0.0 g), which means "use the global threshold from Settings".
                    inventoryRepository.migrateLegacyThresholds()
                    preferencesRepository.setMigrationThresholdV1Done()
                }
            } catch (e: Exception) {
                // Migration failed (e.g. no network, Firestore error). The completion flag
                // is not set, so the migration retries on next launch. This is intentional.
                android.util.Log.e(TAG, "Threshold migration failed; will retry on next launch", e)
            }
        }
    }

    private companion object {
        private const val TAG = "MigrationViewModel"
    }
}
