package com.techyshishy.beadmanager.ui.projects

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val importRgpProjectUseCase: ImportRgpProjectUseCase,
) : ViewModel() {

    val projects: StateFlow<List<ProjectEntry>> =
        projectRepository.projectsStream()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importResult = MutableSharedFlow<ImportResult>()
    val importResult: SharedFlow<ImportResult> = _importResult.asSharedFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

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
            _selectedIds.update { it - projectId }
        }
    }

    fun toggleSelection(projectId: String) {
        _selectedIds.update { current ->
            if (projectId in current) current - projectId else current + projectId
        }
    }

    fun selectAll(projectIds: List<String>) {
        _selectedIds.value = projectIds.toSet()
    }

    fun deselectAll() {
        _selectedIds.value = emptySet()
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value.toList()
            ids.forEach { projectRepository.deleteProject(it) }
            _selectedIds.value = emptySet()
        }
    }

    fun importFromRgp(uri: Uri) {
        viewModelScope.launch {
            val result = importRgpProjectUseCase.import(uri)
            _importResult.emit(result)
        }
    }
}
