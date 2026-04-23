package com.techyshishy.beadmanager.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    private val catalogRepository: CatalogRepository,
    private val preferencesRepository: PreferencesRepository,
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

    val sortedItems: StateFlow<List<OrderItemEntry>> = order
        .map { it?.items?.sortedBy { item -> item.beadCode } ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allBeadsWithVendors = catalogRepository
        .getAllBeadsWithVendors()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val beadLookup: StateFlow<Map<String, BeadEntity>> = allBeadsWithVendors
        .map { list -> list.associate { it.bead.code to it.bead } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Maps beadCode → canonical color name resolved by walking the saved vendor priority order
     * and returning the first non-blank name found across the bead's vendor links.
     * Only codes present in the catalog will appear; unknown or nameless codes are absent.
     */
    val beadColorNames: StateFlow<Map<String, String>> =
        combine(
            allBeadsWithVendors,
            preferencesRepository.vendorPriorityOrder,
        ) { beads, priorityOrder ->
            buildMap {
                for (beadWithVendors in beads) {
                    val name = priorityOrder
                        .firstNotNullOfOrNull { vendorKey ->
                            beadWithVendors.vendorLinks
                                .firstOrNull { it.vendorKey == vendorKey }
                                ?.beadName
                                ?.takeIf { it.isNotBlank() }
                        }
                    if (name != null) put(beadWithVendors.bead.code, name)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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

