package com.techyshishy.beadmanager.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.model.ProjectSatisfaction
import com.techyshishy.beadmanager.domain.ImportResult
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel,
    onProjectSelected: (projectId: String, projectName: String) -> Unit,
    beadCodeFilter: String? = null,
) {
    val context = LocalContext.current
    val projects by viewModel.projects.collectAsState()
    val beadSatisfaction by viewModel.beadSatisfaction.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    val displayedProjects = remember(projects, beadCodeFilter) {
        if (beadCodeFilter == null) projects
        else projects.filter { beadCodeFilter in it.colorMapping.values }
    }

    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProjectEntry?>(null) }
    var importError by remember { mutableStateOf<ImportResult.Failure?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (context.contentResolver.getType(uri) == "application/pdf") {
            viewModel.importFromPdf(uri)
        } else {
            viewModel.importFromRgp(uri)
        }
    }

    LaunchedEffect(viewModel.importResult) {
        viewModel.importResult.collect { result ->
            when (result) {
                is ImportResult.Success -> onProjectSelected(result.projectId, result.name)
                is ImportResult.Failure -> importError = result
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (beadCodeFilter != null) {
                        Text(stringResource(R.string.projects_filtered_by_bead, beadCodeFilter))
                    } else {
                        Text(stringResource(R.string.projects))
                    }
                },
                actions = {
                    if (beadCodeFilter == null) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                Icons.Outlined.FileOpen,
                                contentDescription = stringResource(R.string.import_from_file),
                            )
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    Icons.Filled.SwapVert,
                                    contentDescription = stringResource(R.string.sort_projects),
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                ProjectSortKey.entries.forEach { key ->
                                    val isActive = sortOrder.key == key
                                    DropdownMenuItem(
                                        text = { Text(key.label()) },
                                        onClick = {
                                            viewModel.toggleSortKey(key)
                                            showSortMenu = false
                                        },
                                        trailingIcon = if (isActive) {
                                            {
                                                Icon(
                                                    if (sortOrder.direction == SortDirection.ASCENDING) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                                    contentDescription = stringResource(
                                                        if (sortOrder.direction == SortDirection.ASCENDING) R.string.sort_direction_ascending else R.string.sort_direction_descending,
                                                    ),
                                                )
                                            }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (beadCodeFilter == null) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_project))
                }
            }
        },
    ) { innerPadding ->
        if (displayedProjects.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 32.dp),
            ) {
                Text(
                    text = if (beadCodeFilter != null) {
                        stringResource(R.string.no_projects_for_bead)
                    } else {
                        stringResource(R.string.no_projects)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(displayedProjects, key = { it.projectId }) { project ->
                    ProjectRow(
                        project = project,
                        satisfaction = beadSatisfaction[project.projectId],
                        onClick = { onProjectSelected(project.projectId, project.name) },
                        onDelete = if (beadCodeFilter == null) (
                            { deleteTarget = project }
                        ) else null,
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onConfirm = { name ->
                viewModel.createProject(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_project)) },
            text = { Text(stringResource(R.string.confirm_delete_project, project.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(project.projectId)
                    deleteTarget = null
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    importError?.let { error ->
        ImportErrorDialog(
            error = error,
            onDismiss = { importError = null },
        )
    }
}

private const val SEGMENTED_BAR_MAX_BEADS = 20

@Composable
private fun SatisfactionBar(
    satisfaction: ProjectSatisfaction,
    modifier: Modifier = Modifier,
) {
    val desc = if (satisfaction.deficitCount == 0) {
        pluralStringResource(
            R.plurals.sat_bar_all_satisfied,
            satisfaction.totalCount,
            satisfaction.totalCount,
        )
    } else {
        stringResource(
            R.string.sat_bar_partial,
            satisfaction.totalCount - satisfaction.deficitCount,
            satisfaction.totalCount,
        )
    }
    val semanticsModifier = modifier.clearAndSetSemantics { contentDescription = desc }
    if (satisfaction.totalCount <= SEGMENTED_BAR_MAX_BEADS) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = semanticsModifier
                .fillMaxWidth()
                .height(10.dp),
        ) {
            satisfaction.beadStatuses.forEach { isSatisfied ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (isSatisfied) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        ),
                )
            }
        }
    } else {
        LinearProgressIndicator(
            progress = {
                (satisfaction.totalCount - satisfaction.deficitCount).toFloat() /
                    satisfaction.totalCount
            },
            modifier = semanticsModifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.errorContainer,
        )
    }
}

@Composable
private fun ImportErrorDialog(
    error: ImportResult.Failure,
    onDismiss: () -> Unit,
) {
    val message = when (error) {
        is ImportResult.Failure.NotGzip ->
            stringResource(R.string.import_failed_not_gzip)
        is ImportResult.Failure.InvalidJson ->
            stringResource(R.string.import_failed_invalid_json)
        is ImportResult.Failure.NoDelicaCodes ->
            stringResource(R.string.import_failed_no_delica_codes)
        is ImportResult.Failure.UnrecognizedCodes -> {
            val displayed = error.codes.take(5)
            val overflow = error.codes.size - displayed.size
            val codeList = if (overflow > 0) {
                displayed.joinToString(", ") + " (+$overflow more)"
            } else {
                displayed.joinToString(", ")
            }
            stringResource(R.string.import_failed_unrecognized_codes, codeList)
        }
        is ImportResult.Failure.WriteError ->
            stringResource(R.string.import_failed_write_error)
        is ImportResult.Failure.NotPdf ->
            stringResource(R.string.import_failed_not_pdf)
        is ImportResult.Failure.NoPatternFound ->
            stringResource(R.string.import_failed_no_pattern_found)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_failed_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun ProjectRow(
    project: ProjectEntry,
    satisfaction: ProjectSatisfaction?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            project.createdAt?.let { ts ->
                Text(
                    text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(ts.toDate()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (satisfaction != null) {
                SatisfactionBar(
                    satisfaction = satisfaction,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete_project),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_project)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.project_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ProjectSortKey.label(): String = when (this) {
    ProjectSortKey.CREATED_AT -> stringResource(R.string.sort_date_created)
    ProjectSortKey.NAME -> stringResource(R.string.sort_name)
    ProjectSortKey.BEAD_TYPES -> stringResource(R.string.sort_bead_types)
    ProjectSortKey.GRID_SIZE -> stringResource(R.string.sort_grid_size)
}
