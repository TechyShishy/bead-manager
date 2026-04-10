package com.techyshishy.beadmanager.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs one-time data migrations when first composed inside the authenticated shell.
 *
 * The [init] block fires a coroutine that checks migration flags in DataStore and,
 * if a migration has not yet run, performs it and records completion. Because this
 * ViewModel is scoped to the Activity and [hiltViewModel] returns the same instance
 * on every recomposition, each migration runs exactly once per app process.
 */
@HiltViewModel
class MigrationViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val inventoryRepository: InventoryRepository,
    private val orderRepository: OrderRepository,
) : ViewModel() {

    init {
        viewModelScope.launch {
            runThresholdV1Migration()
            runOrderProjectIdsV1Migration()
        }
    }

    private suspend fun runThresholdV1Migration() {
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

    /**
     * Backfills [com.techyshishy.beadmanager.data.firestore.OrderEntry.projectIds] from the
     * deprecated [com.techyshishy.beadmanager.data.firestore.OrderEntry.projectId] field.
     *
     * For each order document where [projectIds] is empty and [projectId] is non-blank,
     * writes `{ projectIds: arrayUnion(projectId) }` via merge. The legacy [projectId] field
     * is left in place — it does no harm and avoids the cost of a full document rewrite.
     *
     * Batches are not needed here because each [addProjectIdToOrder] call is a targeted
     * field update (not a batch write), and the number of affected documents per user is
     * expected to be small (typically < 50). If this assumption changes, batch writes
     * can be introduced in a follow-up.
     *
     * The migration runs before any query-backed UI loads, so the window where
     * [ordersStream] returns empty results for old-format docs is negligible.
     */
    private suspend fun runOrderProjectIdsV1Migration() {
        try {
            val done = preferencesRepository.migrationOrderProjectIdsV1Done.first()
            if (!done) {
                val orders = orderRepository.getAllOrdersSnapshot()
                orders.filter { it.projectIds.isEmpty() && it.projectId.isNotBlank() && it.orderId.isNotBlank() }
                    .forEach { order ->
                        orderRepository.addProjectIdToOrder(order.orderId, order.projectId)
                    }
                preferencesRepository.setMigrationOrderProjectIdsV1Done()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Order projectIds migration failed; will retry on next launch", e)
        }
    }

    private companion object {
        private const val TAG = "MigrationViewModel"
    }
}
