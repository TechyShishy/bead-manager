package com.techyshishy.beadmanager.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
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

    /**
     * All orders that currently list this project in their [projectIds] array.
     * Sorted oldest-first (by createdAt) since [FirestoreOrderSource.ordersStream] uses
     * ASCENDING order for the per-project query.
     */
    val activeOrders: StateFlow<List<OrderEntry>> = _projectId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(emptyList())
            else orderRepository.ordersStream(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val orderCount: StateFlow<Int> = activeOrders
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Most significant active order status per bead code.
     *
     * A bead appears here only when at least one order contains a non-terminal item for it
     * (i.e. status is PENDING or ORDERED). RECEIVED and SKIPPED items are excluded.
     * When a bead has items at multiple active statuses, ORDERED takes precedence over PENDING
     * — the user cares most that something is already on order.
     */
    val activeOrderStatus: StateFlow<Map<String, OrderItemStatus>> = activeOrders
        .map { orderList ->
            val result = mutableMapOf<String, OrderItemStatus>()
            for (order in orderList) {
                for (item in order.items) {
                    val status = OrderItemStatus.fromFirestore(item.status)
                    if (status == OrderItemStatus.RECEIVED || status == OrderItemStatus.SKIPPED) continue
                    val current = result[item.beadCode]
                    if (current == null || status == OrderItemStatus.ORDERED) {
                        result[item.beadCode] = status
                    }
                }
            }
            result
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
        return orderRepository.createOrderFromBeads(projectId, selected, inventoryGrams.value, activeOrderStatus.value)
    }

    /**
     * Detaches this project from [orderId]. Each order item's contribution from this project
     * is subtracted from [OrderItemEntry.targetGrams]; items whose target drops to zero are
     * removed entirely. Manually-added items (no contribution recorded) are preserved.
     */
    fun detachProject(orderId: String) {
        val projectId = _projectId.value.takeIf { it.isNotBlank() } ?: return
        val order = activeOrders.value.firstOrNull { it.orderId == orderId } ?: return
        viewModelScope.launch {
            orderRepository.detachProject(orderId, projectId, order.items)
        }
    }

    /**
     * Adds items from this project's selected beads to an existing order.
     * The existing order's items are fetched internally for deduplication.
     */
    suspend fun importProjectItems(orderId: String, selectedBeadCodes: Set<String>) {
        val projectId = _projectId.value.takeIf { it.isNotBlank() } ?: return
        val beads = project.value?.beads ?: return
        val selected = beads.filter { it.beadCode in selectedBeadCodes }
        if (selected.isEmpty()) return
        orderRepository.importProjectItems(
            orderId = orderId,
            projectId = projectId,
            selectedBeads = selected,
            inventoryGrams = inventoryGrams.value,
            activeOrderStatus = activeOrderStatus.value,
        )
    }
}
