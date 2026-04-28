package com.techyshishy.beadmanager.ui.projects

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.model.ProjectBeadEntry
import com.techyshishy.beadmanager.data.model.computeBeadRequirements
import com.techyshishy.beadmanager.data.model.ProjectSatisfaction
import com.techyshishy.beadmanager.data.model.computeProjectSatisfaction
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.domain.CreateBlankProjectUseCase
import com.techyshishy.beadmanager.domain.ImportPdfProjectUseCase
import com.techyshishy.beadmanager.domain.ImportResult
import com.techyshishy.beadmanager.domain.ImportRgpProjectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val inventoryRepository: InventoryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val createBlankProjectUseCase: CreateBlankProjectUseCase,
    private val importRgpProjectUseCase: ImportRgpProjectUseCase,
    private val importPdfProjectUseCase: ImportPdfProjectUseCase,
) : ViewModel() {

    private val _sortOrder = MutableStateFlow(ProjectSortOrder.DEFAULT)
    val sortOrder: StateFlow<ProjectSortOrder> = _sortOrder.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _tagFilter = MutableStateFlow<String?>(null)
    val tagFilter: StateFlow<String?> = _tagFilter.asStateFlow()

    /**
     * Bead requirements per project, keyed by projectId.
     *
     * Populated incrementally as grid subcollections are loaded. Projects whose grids have
     * not yet been fetched are absent from this map; the satisfaction flow treats absence
     * as `null` (no badge) until the grid arrives.
     */
    private val _projectBeads = MutableStateFlow<Map<String, List<ProjectBeadEntry>>>(emptyMap())

    /**
     * Shared backing flow for per-project satisfaction. Factored out so both [beadSatisfaction]
     * and [projects] can consume it without duplicating subscription chains.
     *
     * A 150 ms debounce absorbs inventory burst-sync events without unnecessary recomputation.
     * Kept as a [StateFlow] (with an [emptyMap] initial value) so the [projects] combine does not
     * stall waiting for inventory on the first tab visit.
     */
    private val _satisfactionFlow: StateFlow<Map<String, ProjectSatisfaction?>> =
        combine(
            _projectBeads,
            inventoryRepository.inventoryStream(),
            preferencesRepository.globalLowStockThreshold,
        ) { beadsMap, inventory, threshold ->
            beadsMap.mapValues { (_, beads) ->
                computeProjectSatisfaction(beads, inventory, threshold)
            }
        }
            .debounce(150L)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Satisfaction status per project: all-true = all beads met, any-false = deficit,
     * `null` = no Delica beads or grid not yet loaded.
     *
     * Derived from live inventory + global threshold so it updates reactively as stock changes.
     */
    val beadSatisfaction: StateFlow<Map<String, ProjectSatisfaction?>> = _satisfactionFlow

    /**
     * Sorted, deduplicated list of all tags across the user's projects. Empty when no projects
     * have tags. Used to populate the filter chip row in [ProjectFilterSheet].
     */
    val availableTags: StateFlow<List<String>> =
        projectRepository.projectsStream()
            .map { list -> list.flatMap { it.tags }.distinct().sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val projects: StateFlow<List<ProjectEntry>> =
        combine(
            projectRepository.projectsStream(),
            _sortOrder,
            _satisfactionFlow,
            _searchQuery,
            _tagFilter,
        ) { list, order, satisfaction, query, tagFilter ->
            list
                .filter { project ->
                    (query.isBlank() ||
                        project.name.contains(query, ignoreCase = true) ||
                        project.notes?.contains(query, ignoreCase = true) == true ||
                        project.tags.any { it.contains(query, ignoreCase = true) }) &&
                        (tagFilter == null || tagFilter in project.tags)
                }
                .sortedWith(order.comparator(satisfaction))
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importResult = MutableSharedFlow<ImportResult>()
    val importResult: SharedFlow<ImportResult> = _importResult.asSharedFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    init {
        // Whenever the project list changes, reload beads for all projects.
        // Grid rows come from the repository's in-memory cache (ConcurrentHashMap) after the
        // first load, making subsequent calls pure CPU. Always reloading (rather than guarding
        // on key presence) ensures colorMapping additions are reflected immediately — e.g.
        // when the user adds a bead from the catalog or imports an RGP into an existing project.
        //
        // Deliberately collects projectsStream() (the raw Firestore flow) rather than projects
        // (the sorted display flow) so that a sort-order change does not trigger spurious bead
        // reloads — sort order affects display only, not inventory data.
        viewModelScope.launch {
            projectRepository.projectsStream().collect { projectList ->
                projectList.forEach { project ->
                    launch { loadBeadsForProject(project) }
                }
            }
        }
    }

    private suspend fun loadBeadsForProject(project: ProjectEntry) {
        val rows = projectRepository.cachedProjectGrid(project.projectId)
        val beads = if (rows.isEmpty()) {
            // Non-grid project: colorMapping beads at 0 g target.
            project.colorMapping.values
                .filter { it.startsWith("DB") }
                .distinct()
                .map { code -> ProjectBeadEntry(beadCode = code, targetGrams = 0.0) }
        } else {
            val fromGrid = computeBeadRequirements(rows, project.colorMapping)
                .map { (code, grams) -> ProjectBeadEntry(beadCode = code, targetGrams = grams) }
            val fromGridCodes = fromGrid.mapTo(mutableSetOf()) { it.beadCode }
            val colorMappingOnly = project.colorMapping.values
                .filter { it.startsWith("DB") && it !in fromGridCodes }
                .distinct()
                .map { code -> ProjectBeadEntry(beadCode = code, targetGrams = 0.0) }
            fromGrid + colorMappingOnly
        }
        _projectBeads.update { it + (project.projectId to beads) }
    }

    fun setSortKey(key: ProjectSortKey) {
        _sortOrder.update { current ->
            if (current.key == key) {
                current.copy(
                    direction = if (current.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING,
                )
            } else {
                ProjectSortOrder(key, key.defaultDirection)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setTagFilter(tag: String?) {
        _tagFilter.value = tag
    }

    fun clearFilters() {
        _sortOrder.value = ProjectSortOrder.DEFAULT
        _tagFilter.value = null
    }

    fun createProject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val result = createBlankProjectUseCase.create(trimmed)
            _importResult.emit(result)
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            projectRepository.deleteProject(projectId)
            // Remove the deleted project's beads from the local map so a restored project
            // (same id) would re-fetch rather than show stale data.
            _projectBeads.update { it - projectId }
        }
    }

    fun importFromRgp(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val result = importRgpProjectUseCase.import(uri)
                _isImporting.value = false
                _importResult.emit(result)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun importFromPdf(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val result = importPdfProjectUseCase.import(uri)
                _isImporting.value = false
                _importResult.emit(result)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun addBeadToProject(beadCode: String, projectId: String) {
        viewModelScope.launch {
            val project = projects.value.find { it.projectId == projectId } ?: return@launch
            if (project.colorMapping.containsValue(beadCode)) return@launch
            projectRepository.updateProject(
                project.copy(colorMapping = project.colorMapping + (beadCode to beadCode))
            )
        }
    }
}

