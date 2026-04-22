package com.techyshishy.beadmanager.ui.lowstock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.model.AllOrderItem
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LowStockAddToOrderViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    inventoryRepository: InventoryRepository,
    preferencesRepository: PreferencesRepository,
    projectRepository: ProjectRepository,
) : ViewModel() {

    private val inventory = inventoryRepository.inventoryStream()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val globalThreshold = preferencesRepository.globalLowStockThreshold
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PreferencesRepository.DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD,
        )

    /**
     * All orders, newest-first, with project names resolved from the live projects stream.
     * No project filter — restock orders can be appended to any existing PENDING order.
     */
    @Suppress("DEPRECATION") // intentional: legacy projectId read for pre-migration fallback
    val eligibleOrders: StateFlow<List<AllOrderItem>> = combine(
        orderRepository.allOrdersStream(),
        projectRepository.projectsStream(),
    ) { orders, projects ->
        val nameById = projects.associate { it.projectId to it.name }
        orders.map { order ->
            val names = order.projectIds.mapNotNull { nameById[it] }
                .ifEmpty { listOfNotNull(nameById[order.projectId]) }
            AllOrderItem(order = order, projectNames = names)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Appends restock items for [beadCodes] to the existing order [orderId].
     *
     * @return `true` if the order was modified, `false` if all beads are already at threshold.
     */
    suspend fun appendToOrder(orderId: String, beadCodes: Set<String>): Boolean {
        return orderRepository.appendRestockItems(orderId, beadCodes, inventory.value, globalThreshold.value)
    }

    /** Creates a new project-free restock order for [beadCodes]; returns the new order ID. */
    suspend fun createRestockOrder(beadCodes: Set<String>): String {
        return orderRepository.createRestockOrder(beadCodes, inventory.value, globalThreshold.value)
    }
}
