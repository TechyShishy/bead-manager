package com.techyshishy.beadmanager.ui.projects

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.techyshishy.beadmanager.R
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectInfoScreen(
    projectId: String,
    viewModel: ProjectDetailViewModel,
    onNavigateBack: () -> Unit,
    listState: LazyListState,
    onViewInCatalog: (String) -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.initialize(projectId) }

    val project by viewModel.project.collectAsState()
    val beadLookup by viewModel.beadLookup.collectAsState()
    val imageUploadState by viewModel.imageUploadState.collectAsState()
    val gridSummary by viewModel.gridSummary.collectAsState()

    val rowCount = project?.rowCount ?: 0

    val colorMapping = project?.colorMapping.orEmpty()
    val sortedBeadCounts = remember(gridSummary, colorMapping) {
        gridSummary?.beadCountsByKey
            ?.entries
            // Defensive: guards against transient staleness between project and gridSummary flows.
            // Both derive from separate StateFlows and can briefly disagree after a project update.
            ?.filter { it.key in colorMapping }
            ?.sortedBy { it.key }
            ?: emptyList()
    }

    var notesEditMode by rememberSaveable { mutableStateOf(false) }
    var notesInput by rememberSaveable { mutableStateOf("") }
    val notesFocusRequester = remember { FocusRequester() }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.uploadProjectImage(uri)
    }

    LaunchedEffect(imageUploadState) {
        if (imageUploadState is ImageUploadState.Error) {
            snackbarHostState.showSnackbar(
                context.getString((imageUploadState as ImageUploadState.Error).messageRes)
            )
            viewModel.resetImageUploadState()
        }
    }

    LaunchedEffect(notesEditMode) {
        if (notesEditMode) notesFocusRequester.requestFocus()
    }

    BackHandler(enabled = notesEditMode) {
        notesEditMode = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.project_info_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (notesEditMode) notesEditMode = false
                        else onNavigateBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    if (notesEditMode) {
                        IconButton(onClick = {
                            viewModel.updateNotes(notesInput)
                            notesEditMode = false
                        }) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.project_info_notes_save),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Cover image ─────────────────────────────────────────────────
            item {
                InfoSectionHeader(stringResource(R.string.project_info_image_header))
            }
            item {
                val isUploading = imageUploadState is ImageUploadState.Uploading
                val imageUrl = project?.imageUrl
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (imageUrl != null) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = stringResource(R.string.project_info_image_header),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.project_info_image_uploading))
                        }
                        if (!isUploading && imageUrl != null) {
                            IconButton(onClick = { viewModel.removeProjectImage() }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.project_info_image_remove),
                                )
                            }
                        }
                        if (!isUploading && rowCount > 0) {
                            IconButton(onClick = { viewModel.generatePreviewFromGrid() }) {
                                Icon(
                                    Icons.Filled.GridOn,
                                    contentDescription = stringResource(R.string.project_info_image_generate),
                                )
                            }
                        }
                        if (!isUploading) {
                            IconButton(
                                onClick = {
                                    imagePicker.launch(
                                        PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                                    )
                                },
                            ) {
                                val cd = if (imageUrl != null)
                                    stringResource(R.string.project_info_image_change)
                                else
                                    stringResource(R.string.project_info_image_add)
                                Icon(Icons.Filled.Edit, contentDescription = cd)
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            val summary = gridSummary
            if (rowCount > 0 && summary != null) {
                item {
                    InfoSectionHeader(stringResource(R.string.project_info_summary_header))
                }
                item {
                    InfoDetailRow(
                        label = stringResource(R.string.project_info_total_beads_label),
                        value = summary.totalBeads.toString(),
                    )
                    HorizontalDivider()
                }
                item {
                    InfoDetailRow(
                        label = stringResource(R.string.project_info_total_colors_label),
                        value = summary.totalColors.toString(),
                    )
                    HorizontalDivider()
                }
                item {
                    InfoDetailRow(
                        label = stringResource(R.string.project_info_dimensions_label),
                        value = stringResource(
                            R.string.project_info_summary_dimensions,
                            summary.rowCount,
                            summary.maxBeadsWide,
                        ),
                    )
                    HorizontalDivider()
                }
                item {
                    InfoDetailRow(
                        label = stringResource(R.string.project_info_approx_size_label),
                        value = stringResource(
                            R.string.project_info_approx_size_format,
                            (summary.widthMm / 10.0).toFloat(),
                            (summary.heightMm / 10.0).toFloat(),
                        ),
                    )
                    HorizontalDivider()
                }
            }

            if (sortedBeadCounts.isNotEmpty()) {
                item {
                    InfoSectionHeader(stringResource(R.string.project_info_bead_counts_header))
                }
                items(sortedBeadCounts, key = { it.key }) { (key, count) ->
                    val code = colorMapping.getValue(key)
                    val hex = beadLookup[code]?.hex
                    val swatchColor = remember(hex) {
                        hex?.let { runCatching { Color(it.toColorInt()) }.getOrNull() }
                    }
                    BeadCountRow(
                        paletteKey = key,
                        beadCode = code,
                        count = count,
                        swatchColor = swatchColor,
                        onClick = { onViewInCatalog(code) },
                    )
                    HorizontalDivider()
                }
            }

            item {
                InfoSectionHeader(stringResource(R.string.project_info_details_header))
            }
            item {
                val createdText = project?.createdAt?.toDate()?.let {
                    DateFormat.getDateInstance(DateFormat.LONG).format(it)
                } ?: "…"
                InfoDetailRow(
                    label = stringResource(R.string.project_info_created_label),
                    value = createdText,
                )
                HorizontalDivider()
            }

            item {
                InfoSectionHeader(stringResource(R.string.project_info_notes_header))
            }
            item {
                if (notesEditMode) {
                    OutlinedTextField(
                        value = notesInput,
                        onValueChange = { notesInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .focusRequester(notesFocusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        minLines = 3,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        Text(
                            text = project?.notes?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.project_info_notes_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (project?.notes.isNullOrBlank())
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            notesInput = project?.notes.orEmpty()
                            notesEditMode = true
                        }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.project_info_notes_edit),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 4.dp,
        ),
    )
}

@Composable
private fun InfoDetailRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BeadCountRow(
    paletteKey: String,
    beadCode: String,
    count: Int,
    swatchColor: Color?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.view_in_catalog),
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = paletteKey,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(28.dp),
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(
                    swatchColor ?: MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.extraSmall,
                ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = beadCode,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
