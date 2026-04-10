package com.techyshishy.beadmanager.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.model.AllOrderItem
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives [AddToOrderScreen].
 *
 * Streams the set of orders that the current project has NOT yet been associated with,
 * so the user can pick one to import their selected beads into. Orders already containing
 * the project are excluded — they are displayed on [ProjectDetailScreen]'s active-orders
 * section instead.
 *
 * Project names are resolved the same way [AllOrdersViewModel] does: [OrderEntry.projectIds]
 * matched against the live projects list, with a deprecated-field fallback for pre-migration
 * documents.
 */
@HiltViewModel
class AddToOrderViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    projectRepository: ProjectRepository,
) : ViewModel() {

    private val _projectId = MutableStateFlow("")

    fun initialize(projectId: String) {
        if (_projectId.value != projectId) _projectId.value = projectId
    }

    @Suppress("DEPRECATION") // intentional: legacy projectId read for pre-migration name resolution
    val eligibleOrders: StateFlow<List<AllOrderItem>> = combine(
        _projectId,
        orderRepository.allOrdersStream(),
        projectRepository.projectsStream(),
    ) { projectId, orders, projects ->
        if (projectId.isBlank()) return@combine emptyList()
        val nameById = projects.associate { it.projectId to it.name }
        orders
            .filter { order -> order.projectIds.none { it == projectId } }
            .map { order ->
                val names = order.projectIds.mapNotNull { nameById[it] }
                    .ifEmpty { listOfNotNull(nameById[order.projectId]) }
                AllOrderItem(order = order, projectNames = names)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
