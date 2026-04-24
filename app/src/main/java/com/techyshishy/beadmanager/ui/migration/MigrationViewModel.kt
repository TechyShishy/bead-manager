package com.techyshishy.beadmanager.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.model.synthesizeFlatListGrid
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
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
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    init {
        viewModelScope.launch {
            runThresholdV1Migration()
            runOrderProjectIdsV1Migration()
            runFlatProjectToGridV1Migration()
            runInlineRowsToSubcollectionV1Migration()
            runBobVendorMigration()
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

    /**
     * Converts legacy flat-list [ProjectEntry] documents to the RGP grid format.
     *
     * Each project document that has a non-empty [beads] array but an empty [rows] list is
     * upgraded: a single-row synthetic grid is written to [rows] and [colorMapping], and the
     * [beads] field is atomically deleted from the document. Projects that already have a grid
     * (rows non-empty) are skipped without modification.
     *
     * The synthesized grid round-trips through [computeBeadRequirements] with a maximum error
     * of < 0.003g per bead — well within the tolerance of the display and ordering logic.
     *
     * After all documents are migrated the completion flag is set so the migration never runs
     * again. If a failure occurs the flag is NOT set, causing a retry on the next app launch.
     */
    private suspend fun runFlatProjectToGridV1Migration() {
        try {
            val done = preferencesRepository.migrationFlatProjectToGridV1Done.first()
            if (!done) {
                val flatProjects = projectRepository.getFlatProjectsForMigration()
                for ((projectId, beads) in flatProjects) {
                    val (rows, colorMapping) = synthesizeFlatListGrid(beads)
                    projectRepository.migrateProjectToGrid(projectId, rows, colorMapping)
                }
                preferencesRepository.setMigrationFlatProjectToGridV1Done()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Flat-project-to-grid migration failed; will retry on next launch", e)
        }
    }

    /**
     * Moves inline `rows` arrays from legacy project documents into the new `grid/`
     * subcollection format.
     *
     * Old code stored the RGP grid as a `rows` array directly in the project document. For large
     * patterns this creates a Firestore mutation overlay in the local SQLite cache that exceeds
     * the 2 MB CursorWindow limit, causing a fatal [android.database.sqlite.SQLiteBlobTooBigException]
     * on startup. This migration reads all project documents from the server (bypassing the broken
     * local cache), moves any inline rows to the subcollection managed by [ProjectRepository], and
     * removes the `rows` field from the main document.
     *
     * After this migration runs, [ProjectEntry] documents hold a `rowCount` field and have no
     * `rows` array, so the local overlay for each main document is always small.
     */
    private suspend fun runInlineRowsToSubcollectionV1Migration() {
        try {
            val done = preferencesRepository.migrationInlineRowsV1Done.first()
            if (!done) {
                val projectsWithInlineRows = projectRepository.getProjectsWithInlineRowsFromServer()
                for ((projectId, rows, colorMapping) in projectsWithInlineRows) {
                    // migrateProjectToGrid writes the grid to the subcollection, sets rowCount in
                    // the main doc, updates colorMapping, and removes the inline `rows` field,
                    // unblocking the SQLiteBlobTooBigException crash for this project.
                    projectRepository.migrateProjectToGrid(projectId, rows, colorMapping)
                }
                preferencesRepository.setMigrationInlineRowsV1Done()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Inline-rows migration failed; will retry on next launch", e)
        }
    }

    /**
     * Inserts "bob" (Barrel of Beads) into the stored vendor priority order for existing
     * users whose saved order predates this vendor being registered. New installs use
     * DEFAULT_VENDOR_PRIORITY_ORDER which already includes "bob", so this migration is a
     * no-op for them once that default is written.
     *
     * Position: immediately after "fmg" when present, otherwise appended at the end.
     */
    private suspend fun runBobVendorMigration() {
        try {
            val done = preferencesRepository.migrationBobVendorV1Done.first()
            if (!done) {
                val current = preferencesRepository.vendorPriorityOrder.first()
                if ("bob" !in current) {
                    val fmgIndex = current.indexOf("fmg")
                    val insertAt = if (fmgIndex >= 0) fmgIndex + 1 else current.size
                    val updated = current.toMutableList().also { it.add(insertAt, "bob") }
                    preferencesRepository.setVendorPriorityOrder(updated)
                }
                preferencesRepository.setMigrationBobVendorV1Done()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Bob vendor migration failed; will retry on next launch", e)
        }
    }

    private companion object {
        private const val TAG = "MigrationViewModel"
    }
}
