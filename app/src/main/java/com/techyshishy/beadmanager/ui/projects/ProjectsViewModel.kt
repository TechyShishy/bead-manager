package com.techyshishy.beadmanager.ui.projects

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.model.ProjectBeadEntry
import com.techyshishy.beadmanager.data.model.computeBeadRequirements
import com.techyshishy.beadmanager.data.model.computeProjectSatisfaction
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.domain.ImportResult
import com.techyshishy.beadmanager.domain.ImportRgpProjectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val inventoryRepository: InventoryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val importRgpProjectUseCase: ImportRgpProjectUseCase,
) : ViewModel() {

    val projects: StateFlow<List<ProjectEntry>> =
        projectRepository.projectsStream()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Bead requirements per project, keyed by projectId.
     *
     * Populated incrementally as grid subcollections are loaded. Projects whose grids have
     * not yet been fetched are absent from this map; the satisfaction flow treats absence
     * as `null` (no badge) until the grid arrives.
     */
    private val _projectBeads = MutableStateFlow<Map<String, List<ProjectBeadEntry>>>(emptyMap())

    /**
     * Satisfaction status per project: `0` = all beads met, `N > 0` = N beads with deficit,
     * `null` = no DB beads or grid not yet loaded.
     *
     * Derived from live inventory + global threshold so it updates reactively as stock changes.
     * A 150 ms debounce absorbs inventory burst-sync events without unnecessary recomputation.
     */
    val beadSatisfaction: StateFlow<Map<String, Int?>> =
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

    private val _importResult = MutableSharedFlow<ImportResult>()
    val importResult: SharedFlow<ImportResult> = _importResult.asSharedFlow()

    init {
        // Whenever the project list changes, reload beads for all projects.
        // Grid rows come from the repository's in-memory cache (ConcurrentHashMap) after the
        // first load, making subsequent calls pure CPU. Always reloading (rather than guarding
        // on key presence) ensures colorMapping additions are reflected immediately — e.g.
        // when the user adds a bead from the catalog or imports an RGP into an existing project.
        viewModelScope.launch {
            projects.collect { projectList ->
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

    fun createProject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            projectRepository.createProject(ProjectEntry(name = trimmed))
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
            val result = importRgpProjectUseCase.import(uri)
            _importResult.emit(result)
        }
    }
}

