package com.techyshishy.beadmanager.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Project Detail screen (bead list).
 *
 * Combines a live stream of the project document (for the bead list) with a live stream of
 * all orders for that project (for received-grams progress). The resulting [receivedGrams]
 * map is computed reactively — no denormalized write is needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectDetailViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val orderRepository: OrderRepository,
    private val inventoryRepository: InventoryRepository,
) : ViewModel() {

    private val _projectId = MutableStateFlow("")

    fun initialize(projectId: String) {
        if (_projectId.value != projectId) _projectId.value = projectId
    }

    val project: StateFlow<ProjectEntry?> = _projectId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(null)
            else projectRepository.projectStream(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val orders: StateFlow<List<OrderEntry>> = _projectId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(emptyList())
            else orderRepository.ordersStream(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val orderCount: StateFlow<Int> = orders
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Grams received per bead code, summed across all orders.
     *
     * Only counts items where [appliedToInventory] is true, so reverted items
     * are correctly excluded.
     */
    val receivedGrams: StateFlow<Map<String, Double>> = orders
        .map { orderList ->
            val map = mutableMapOf<String, Double>()
            for (order in orderList) {
                for (item in order.items) {
                    if (item.appliedToInventory) {
                        map[item.beadCode] = (map[item.beadCode] ?: 0.0) + item.packGrams * item.quantityUnits
                    }
                }
            }
            map
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Current inventory quantity per bead code, in grams.
     *
     * Used alongside [ProjectBeadEntry.targetGrams] to compute how much still needs to be
     * acquired. Deficit = max(0, targetGrams - inventoryGrams[beadCode]).
     */
    val inventoryGrams: StateFlow<Map<String, Double>> = inventoryRepository
        .inventoryStream()
        .map { inv -> inv.mapValues { (_, entry) -> entry.quantityGrams } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ── Bead list mutations ──────────────────────────────────────────────────

    /**
     * Removes a bead from the project bead list.
     */
    fun removeBead(beadCode: String) {
        val projectId = _projectId.value.takeIf { it.isNotBlank() } ?: return
        val currentBeads = project.value?.beads ?: return
        viewModelScope.launch {
            projectRepository.removeBead(projectId, beadCode, currentBeads)
        }
    }

    // ── Order creation ───────────────────────────────────────────────────────

    /**
     * Creates a new order pre-populated with vendor-less items for [selectedBeadCodes].
     * Returns the new orderId, or null if no codes are selected or no matching beads found.
     *
     * [inventoryGrams] snapshot is taken at call time. Firestore's local cache means the
     * value is live before any user interaction is possible; the window where it could be
     * [emptyMap] is sub-second at first screen open only.
     */
    suspend fun createOrderFromSelection(selectedBeadCodes: Set<String>): String? {
        val projectId = _projectId.value.takeIf { it.isNotBlank() } ?: return null
        val beads = project.value?.beads ?: return null
        val selected = beads.filter { it.beadCode in selectedBeadCodes }
        if (selected.isEmpty()) return null
        return orderRepository.createOrderFromBeads(projectId, selected, inventoryGrams.value)
    }
}
