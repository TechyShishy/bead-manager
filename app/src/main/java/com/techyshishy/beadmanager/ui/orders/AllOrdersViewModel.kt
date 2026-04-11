package com.techyshishy.beadmanager.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.model.AllOrderItem
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AllOrdersViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    projectRepository: ProjectRepository,
) : ViewModel() {

    /**
     * Live list of all orders, newest-first, with project names resolved from the live
     * projects stream. No extra Firestore reads — both underlying streams are already
     * snapshot listeners with offline caching.
     *
     * For each order:
     * - [AllOrderItem.projectNames] is built from [com.techyshishy.beadmanager.data.firestore.OrderEntry.projectIds]
     *   matched against the current project list.
     * - If [projectIds] is empty (pre-migration document), the deprecated
     *   [com.techyshishy.beadmanager.data.firestore.OrderEntry.projectId] field is used as a
     *   single-element fallback. If that is also unresolvable (project deleted), [projectNames]
     *   is empty and the screen displays a generic fallback label.
     */
    @Suppress("DEPRECATION") // intentional: legacy projectId read for pre-migration fallback
    val orders: StateFlow<List<AllOrderItem>> = combine(
        orderRepository.allOrdersStream(),
        projectRepository.projectsStream(),
    ) { orders, projects ->
        val nameById = projects.associate { it.projectId to it.name }
        orders.map { order ->
            val names = order.projectIds.mapNotNull { nameById[it] }
                .ifEmpty {
                    // Pre-migration docs have empty projectIds; fall back to the legacy field.
                    listOfNotNull(nameById[order.projectId])
                }
            AllOrderItem(order = order, projectNames = names)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteOrder(orderId: String) {
        viewModelScope.launch {
            orderRepository.deleteOrder(orderId)
        }
    }
}
