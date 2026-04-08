package com.techyshishy.beadmanager.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    private val projectRepository: ProjectRepository,
    private val catalogRepository: CatalogRepository,
) : ViewModel() {

    private val _orderId = MutableStateFlow("")

    fun initialize(orderId: String) {
        if (_orderId.value != orderId) _orderId.value = orderId
    }

    val order: StateFlow<OrderEntry?> = _orderId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(null)
            else orderRepository.orderStream(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Stable project-ID projection. `projectId` never changes for a given order, but
     * `order` re-emits on every item-level update. Deriving the inner flows from this
     * rather than directly from `order` prevents Firestore listener churn on each update.
     */
    private val _projectId: StateFlow<String?> = order
        .map { it?.projectId?.takeIf { id -> id.isNotBlank() } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Grams already received across all orders for this project, keyed by bead code.
     * Used to warn when a vendor assignment would push a bead over its project target.
     * Emits an empty map if the project ID is not yet available.
     */
    val receivedGramsPerBead: StateFlow<Map<String, Double>> = _projectId
        .flatMapLatest { projectId ->
            if (projectId == null) return@flatMapLatest flowOf(emptyMap())
            orderRepository.ordersStream(projectId).map { orders ->
                val map = mutableMapOf<String, Double>()
                for (o in orders) {
                    for (item in o.items) {
                        if (item.appliedToInventory) {
                            map[item.beadCode] = (map[item.beadCode] ?: 0.0) + item.packGrams * item.quantityUnits
                        }
                    }
                }
                map
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Target grams per bead code from the project bead list.
     * Needed to compute over-target warnings in the Select Vendor sheet.
     */
    val projectTargetGrams: StateFlow<Map<String, Double>> = _projectId
        .flatMapLatest { projectId ->
            if (projectId == null) return@flatMapLatest flowOf(emptyMap())
            projectRepository.projectStream(projectId).map { project ->
                project?.beads?.associate { it.beadCode to it.targetGrams } ?: emptyMap()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Vendor keys that carry the given bead, for the add-item picker. */
    fun vendorKeysForBead(beadCode: String): Flow<List<String>> =
        catalogRepository.vendorKeysForBead(beadCode)

    /** Pack sizes for a bead at a specific vendor, for the add-item picker. */
    fun packsForVendor(beadCode: String, vendorKey: String): Flow<List<VendorPackEntity>> =
        catalogRepository.packsForVendor(beadCode, vendorKey)

    /**
     * Adds pre-computed line items to the current order.
     * Items whose (beadCode, vendorKey, packGrams) triple already exists are silently skipped
     * by [OrderRepository.addItems].
     */
    fun addItems(items: List<OrderItemEntry>) {
        val currentOrder = order.value ?: return
        viewModelScope.launch {
            orderRepository.addItems(currentOrder.orderId, items, currentOrder.items)
        }
    }

    /**
     * Assigns a vendor to the vendor-less item for [beadCode].
     * Replaces the vendor-less item with [newItems] (one per pack size in the combination).
     */
    fun assignVendor(beadCode: String, newItems: List<OrderItemEntry>) {
        val currentOrder = order.value ?: return
        viewModelScope.launch {
            orderRepository.assignVendor(
                orderId = currentOrder.orderId,
                beadCode = beadCode,
                newItems = newItems,
                allItems = currentOrder.items,
            )
        }
    }

    fun removeItem(item: OrderItemEntry) {
        val currentOrder = order.value ?: return
        viewModelScope.launch {
            orderRepository.removeItem(currentOrder.orderId, item, currentOrder.items)
        }
    }

    fun markItemReceived(item: OrderItemEntry) {
        val currentOrder = order.value ?: return
        viewModelScope.launch {
            orderRepository.markItemReceived(currentOrder.orderId, item, currentOrder.items)
        }
    }

    fun updateItemStatus(item: OrderItemEntry, newStatus: OrderItemStatus) {
        val currentOrder = order.value ?: return
        viewModelScope.launch {
            orderRepository.updateItemStatus(currentOrder.orderId, item, currentOrder.items, newStatus)
        }
    }

    fun revertItemReceived(item: OrderItemEntry) {
        val currentOrder = order.value ?: return
        viewModelScope.launch {
            orderRepository.revertItemReceived(currentOrder.orderId, item, currentOrder.items)
        }
    }
}

