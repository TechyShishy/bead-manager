package com.techyshishy.beadmanager.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.techyshishy.beadmanager.R
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.domain.ImportResult
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel,
    onProjectSelected: (projectId: String, projectName: String) -> Unit,
) {
    val projects by viewModel.projects.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProjectEntry?>(null) }
    var importError by remember { mutableStateOf<ImportResult.Failure?>(null) }

    val rgpPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importFromRgp(it) }
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
                title = { Text(stringResource(R.string.projects)) },
                actions = {
                    IconButton(onClick = { rgpPicker.launch(arrayOf("*/*")) }) {
                        Icon(
                            Icons.Outlined.FileOpen,
                            contentDescription = stringResource(R.string.import_from_rgp),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_project))
            }
        },
    ) { innerPadding ->
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.no_projects),
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
                items(projects, key = { it.projectId }) { project ->
                    ProjectRow(
                        project = project,
                        onClick = { onProjectSelected(project.projectId, project.name) },
                        onDelete = { deleteTarget = project },
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
    onClick: () -> Unit,
    onDelete: () -> Unit,
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
        }
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
