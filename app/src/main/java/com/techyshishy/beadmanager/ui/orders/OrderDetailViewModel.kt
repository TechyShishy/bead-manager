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
import com.techyshishy.beadmanager.data.seed.CatalogSeeder
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
     * Summary of a single vendor's items within a fully-ordered order.
     *
     * [vendorKey]      — the raw vendor key (e.g. "fmg").
     * [displayName]    — human-readable vendor name, resolved from [CatalogSeeder.VENDOR_DISPLAY_NAMES];
     *                    falls back to [vendorKey] when not found.
     * [itemCount]      — number of non-SKIPPED items in this vendor group.
     * [invoiceNumber]  — the shared invoice number, or null when: no items have an invoice
     *                    number, or the items in this group disagree on the invoice number
     *                    (data inconsistency — should not occur through the normal flow).
     */
    data class VendorSetSummary(
        val vendorKey: String,
        val displayName: String,
        val itemCount: Int,
        val invoiceNumber: String?,
    )

    /**
     * True when the order is non-null and contains at least one ORDERED or RECEIVED item but
     * no PENDING or FINALIZED items. The vendor-set summary view is shown in this state.
     *
     * All-SKIPPED orders (no ORDERED/RECEIVED items) remain in the flat-list view; there is
     * no vendor transaction to summarize.
     */
    val isFullyOrdered: StateFlow<Boolean> = order
        .map { entry ->
            if (entry == null) return@map false
            val items = entry.items
            val hasActivePlacement = items.any {
                it.status == OrderItemStatus.ORDERED.firestoreValue ||
                    it.status == OrderItemStatus.RECEIVED.firestoreValue
            }
            val hasPendingOrFinalized = items.any {
                it.status == OrderItemStatus.PENDING.firestoreValue ||
                    it.status == OrderItemStatus.FINALIZED.firestoreValue
            }
            hasActivePlacement && !hasPendingOrFinalized
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Vendor-set summaries for a fully-ordered order, sorted by vendor display name.
     *
     * SKIPPED items are excluded from grouping. Vendorless items (blank [OrderItemEntry.vendorKey])
     * are excluded. Empty when [isFullyOrdered] is false or when the order is null.
     *
     * Invoice number consensus rule: collect all non-null, non-blank invoice numbers in the
     * group. Show the value only when all are equal; suppress (null) when they disagree or
     * when none are present.
     */
    val vendorSets: StateFlow<List<VendorSetSummary>> = order
        .map { entry ->
            if (entry == null) return@map emptyList()
            entry.items
                .filter { item ->
                    item.vendorKey.isNotBlank() &&
                        item.status != OrderItemStatus.SKIPPED.firestoreValue
                }
                .groupBy { it.vendorKey }
                .map { (vendorKey, items) ->
                    val invoiceNumbers = items
                        .mapNotNull { it.invoiceNumber?.takeIf { n -> n.isNotBlank() } }
                        .toSet()
                    val invoiceNumber = when {
                        invoiceNumbers.isEmpty() -> null
                        invoiceNumbers.size == 1 -> invoiceNumbers.first()
                        else -> null // disagreement — suppress
                    }
                    VendorSetSummary(
                        vendorKey = vendorKey,
                        displayName = CatalogSeeder.VENDOR_DISPLAY_NAMES[vendorKey] ?: vendorKey,
                        itemCount = items.size,
                        invoiceNumber = invoiceNumber,
                    )
                }
                .sortedBy { it.displayName }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

