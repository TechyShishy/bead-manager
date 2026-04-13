package com.techyshishy.beadmanager.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.model.nextBijectiveKey
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository.Companion.DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the bead detail pane.
 *
 * Because this VM is used without Compose Navigation (the adaptive scaffold
 * places list and detail in the same composition), the bead code is delivered
 * via [initialize] rather than SavedStateHandle. The VM is keyed by code in
 * AdaptiveScaffold, so each unique code gets its own instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BeadDetailViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val inventoryRepository: InventoryRepository,
    preferencesRepository: PreferencesRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val beadCode = MutableStateFlow("")

    val globalThresholdGrams: StateFlow<Double> =
        preferencesRepository.globalLowStockThreshold
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD)

    fun initialize(code: String) {
        if (beadCode.value != code) beadCode.value = code
    }

    val bead: StateFlow<BeadWithInventory?> = beadCode
        .flatMapLatest { code ->
            if (code.isBlank()) return@flatMapLatest flowOf(null)
            combine(
                catalogRepository.getBeadWithVendors(code),
                inventoryRepository.inventoryStream(),
                globalThresholdGrams,
            ) { catalogEntry, inventoryMap, threshold ->
                catalogEntry?.let {
                    BeadWithInventory(
                        catalogEntry = it,
                        inventory = inventoryMap[code],
                        globalThresholdGrams = threshold,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun adjustQuantity(deltaGrams: Double) {
        viewModelScope.launch {
            inventoryRepository.adjustQuantity(
                beadCode = beadCode.value,
                deltaGrams = deltaGrams,
            )
        }
    }

    fun setQuantity(grams: Double) {
        viewModelScope.launch {
            val code = beadCode.value
            val current = bead.value?.inventory ?: InventoryEntry(beadCode = code)
            inventoryRepository.upsert(current.copy(quantityGrams = grams, lastUpdated = null))
        }
    }

    fun updateNotes(notes: String) {
        viewModelScope.launch {
            val code = beadCode.value
            val current = bead.value?.inventory ?: InventoryEntry(beadCode = code)
            inventoryRepository.upsert(current.copy(notes = notes, lastUpdated = null))
        }
    }

    fun updateThreshold(thresholdGrams: Double) {
        viewModelScope.launch {
            val code = beadCode.value
            val current = bead.value?.inventory ?: InventoryEntry(beadCode = code)
            inventoryRepository.upsert(
                current.copy(lowStockThresholdGrams = thresholdGrams, lastUpdated = null)
            )
        }
    }

    fun resetThresholdToGlobal() = updateThreshold(0.0)

    // ── Project bead list ──────────────────────────────────────────────────────

    /**
     * All projects belonging to the current user. Drives the "Add to project"
     * selector in the catalog detail pane.
     */
    val projects: StateFlow<List<ProjectEntry>> =
        projectRepository.projectsStream()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Registers the current bead in [projectId]'s [colorMapping] by assigning it a new
     * bijective palette key. This makes the bead visible in the project bead list at 0g
     * target until the project's RGP grid is updated to include steps for this palette key.
     *
     * If the bead is already registered (its DB code appears in [colorMapping] values) this
     * is a no-op. No grid row or step is created; grams come only from grid step counts.
     *
     * No-ops silently if the bead code is blank (i.e. [initialize] has not been called).
     */
    suspend fun addToProject(projectId: String) {
        val code = beadCode.value.takeIf { it.isNotBlank() } ?: return
        val project = projects.value.find { it.projectId == projectId } ?: return
        if (project.colorMapping.containsValue(code)) return
        val newKey = nextBijectiveKey(project.colorMapping)
        projectRepository.updateProject(project.copy(colorMapping = project.colorMapping + (newKey to code)))
    }
}

