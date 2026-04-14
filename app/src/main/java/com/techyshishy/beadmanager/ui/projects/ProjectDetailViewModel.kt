package com.techyshishy.beadmanager.ui.projects

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.firestore.ProjectBeadEntry
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.model.computeBeadRequirements
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.domain.ExportResult
import com.techyshishy.beadmanager.domain.ExportRgpProjectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

/** Events emitted for add-bead-from-catalog outcomes. */
sealed class AddBeadEvent {
    /** The selected bead code was already present in the project's colorMapping. */
    data object AlreadyPresent : AddBeadEvent()
}

/**
 * Drives the Project Detail screen (bead list).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectDetailViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val orderRepository: OrderRepository,
    private val inventoryRepository: InventoryRepository,
    private val catalogRepository: CatalogRepository,
    private val preferencesRepository: PreferencesRepository,
    private val exportRgpProjectUseCase: ExportRgpProjectUseCase,
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
     * Rows from the `grid/` subcollection for the current project. Loaded once per projectId
     * change via a one-shot suspend read. Rows are the pattern definition and do not change
     * after import, so a reactive Flow is not needed.
     */
    val projectRows: StateFlow<List<ProjectRgpRow>> = _projectId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(emptyList())
            else flow { emit(projectRepository.readProjectGrid(id)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Bead requirements derived from the project's RGP grid and colorMapping.
     *
     * Beads used in grid steps are returned with their gram requirements computed by
     * [computeBeadRequirements]. Beads registered only in [ProjectEntry.colorMapping]
     * (e.g. added via "Add to Project" without a corresponding grid step) are included
     * at 0 g so they are visible in the project bead list.
     */
    val beads: StateFlow<List<ProjectBeadEntry>> = combine(project, projectRows) { entry, rows ->
        if (entry == null) return@combine emptyList()
        val fromGrid = computeBeadRequirements(rows, entry.colorMapping)
            .map { (code, grams) -> ProjectBeadEntry(beadCode = code, targetGrams = grams) }
        val fromGridCodes = fromGrid.mapTo(mutableSetOf()) { it.beadCode }
        val colorMappingOnly = entry.colorMapping.values
            .filter { it.startsWith("DB") && it !in fromGridCodes }
            .map { code -> ProjectBeadEntry(beadCode = code, targetGrams = 0.0) }
        fromGrid + colorMappingOnly
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * Most significant active order status per bead code.
     *
     * A bead appears here only when at least one order contains a non-terminal item for it
     * (i.e. status is PENDING, FINALIZED, or ORDERED). RECEIVED and SKIPPED items are excluded.
     * When a bead has items at multiple active statuses, ORDERED takes precedence over FINALIZED
     * which takes precedence over PENDING — the user cares most that something is already on order.
     */
    val activeOrderStatus: StateFlow<Map<String, OrderItemStatus>> = activeOrders
        .map { orderList ->
            val result = mutableMapOf<String, OrderItemStatus>()
            for (order in orderList) {
                for (item in order.items) {
                    val status = OrderItemStatus.fromFirestore(item.status)
                    if (status == OrderItemStatus.RECEIVED || status == OrderItemStatus.SKIPPED) continue
                    val current = result[item.beadCode]
                    if (current == null ||
                        status == OrderItemStatus.ORDERED ||
                        (status == OrderItemStatus.FINALIZED && current == OrderItemStatus.PENDING)
                    ) {
                        result[item.beadCode] = status
                    }
                }
            }
            result
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Full inventory entries per bead code.
     *
     * Exposes [InventoryEntry.quantityGrams] and [InventoryEntry.lowStockThresholdGrams] so
     * the screen can compute the effective deficit including the per-bead or global minimum
     * stock threshold replenishment amount.
     */
    val inventoryEntries: StateFlow<Map<String, InventoryEntry>> = inventoryRepository
        .inventoryStream()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** App-wide low-stock threshold (grams). Fallback when a bead has no per-bead override. */
    val globalThreshold: StateFlow<Double> = preferencesRepository
        .globalLowStockThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 5.0)

    val beadLookup: StateFlow<Map<String, BeadEntity>> = catalogRepository
        .allBeadsLookup()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ── RGP export ───────────────────────────────────────────────────────────

    private val _exportEvents = MutableSharedFlow<ExportResult>(extraBufferCapacity = 1)

    /**
     * One-shot events emitted after each [exportToRgp] call. The screen collects this to
     * show a snackbar on success or an error dialog on failure.
     */
    val exportEvents: SharedFlow<ExportResult> = _exportEvents.asSharedFlow()

    /**
     * Exports this project to [uri] via [ExportRgpProjectUseCase] and emits the result on
     * [exportEvents]. The caller owns the URI; this function does not validate it.
     */
    fun exportToRgp(uri: Uri) {
        val projectId = _projectId.value.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            _exportEvents.emit(exportRgpProjectUseCase.export(projectId, uri))
        }
    }

    // ── Project mutations ────────────────────────────────────────────────────

    /**
     * Renames this project. [name] is trimmed; a blank result is a no-op. Writes via
     * [ProjectRepository.updateProject] with [SetOptions.merge] semantics via the source.
     */
    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val currentProject = project.value ?: return
        viewModelScope.launch {
            projectRepository.updateProject(currentProject.copy(name = trimmed))
        }
    }

    // ── Bead list mutations ──────────────────────────────────────────────────

    private val _addBeadEvents = MutableSharedFlow<AddBeadEvent>(extraBufferCapacity = 1)

    /**
     * One-shot events emitted after each [addBead] call when the bead is already present.
     * The screen collects this to show a snackbar.
     */
    val addBeadEvents: SharedFlow<AddBeadEvent> = _addBeadEvents.asSharedFlow()

    /**
     * Adds [beadCode] to this project's [ProjectEntry.colorMapping] using an identity key
     * (`beadCode → beadCode`). If the bead is already present in the colorMapping values,
     * emits [AddBeadEvent.AlreadyPresent] and does not write to Firestore.
     */
    fun addBead(beadCode: String) {
        val currentProject = project.value ?: return
        if (currentProject.colorMapping.values.any { it == beadCode }) {
            viewModelScope.launch { _addBeadEvents.emit(AddBeadEvent.AlreadyPresent) }
            return
        }
        viewModelScope.launch {
            projectRepository.updateProject(
                currentProject.copy(
                    colorMapping = currentProject.colorMapping + (beadCode to beadCode),
                ),
            )
        }
    }

    /**
     * Removes a bead from this project by stripping all palette keys that map to
     * [beadCode] from [ProjectEntry.colorMapping].
     */
    fun removeBead(beadCode: String) {
        val currentProject = project.value ?: return
        viewModelScope.launch {
            projectRepository.updateProject(
                currentProject.copy(
                    colorMapping = currentProject.colorMapping.filterValues { it != beadCode },
                ),
            )
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
        val selected = beads.value.filter { it.beadCode in selectedBeadCodes }
        if (selected.isEmpty()) return null
        return orderRepository.createOrderFromBeads(
            projectId,
            selected,
            inventoryEntries.value,
            globalThreshold.value,
            activeOrderStatus.value,
        )
    }

    /**
     * Detaches this project from [orderId]. Each order item's contribution from this project
     * is subtracted from [OrderItemEntry.targetGrams]; items whose target drops to zero are
     * removed entirely. Manually-added items (no contribution recorded) are preserved.
     *
     * No-ops if the order has been finalized (any item is FINALIZED, ORDERED, or RECEIVED), because
     * removing a project from a committed order would silently corrupt its history.
     */
    fun detachProject(orderId: String) {
        val projectId = _projectId.value.takeIf { it.isNotBlank() } ?: return
        val order = activeOrders.value.firstOrNull { it.orderId == orderId } ?: return
        val isFinalized = order.items.any {
            val s = OrderItemStatus.fromFirestore(it.status)
            s == OrderItemStatus.FINALIZED || s == OrderItemStatus.ORDERED || s == OrderItemStatus.RECEIVED
        }
        if (isFinalized) return
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
        val selected = beads.value.filter { it.beadCode in selectedBeadCodes }
        if (selected.isEmpty()) return
        orderRepository.importProjectItems(
            orderId = orderId,
            projectId = projectId,
            selectedBeads = selected,
            inventoryEntries = inventoryEntries.value,
            globalThresholdGrams = globalThreshold.value,
            activeOrderStatus = activeOrderStatus.value,
        )
    }
}
