package com.techyshishy.beadmanager.domain

import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class CreateBlankProjectUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
) {
    suspend fun create(name: String): ImportResult {
        return try {
            val projectId = projectRepository.createProject(ProjectEntry(name = name))
            ImportResult.Success(projectId = projectId, name = name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failure.WriteError
        }
    }
}
