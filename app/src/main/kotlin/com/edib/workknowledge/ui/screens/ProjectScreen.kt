package com.edib.workknowledge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.data.DocumentEntity
import com.edib.workknowledge.data.ProjectEntity
import com.edib.workknowledge.data.WorkRepository
import com.edib.workknowledge.ui.DocumentRow
import com.edib.workknowledge.ui.components.EmptyState
import com.edib.workknowledge.ui.components.SectionHeader
import com.edib.workknowledge.ui.components.StatusChip
import com.edib.workknowledge.ui.components.WorkDivider
import com.edib.workknowledge.ui.components.WorkToolbar

/**
 * Project screen: the file list for one project, plus the import action.
 *
 * Scope is stated in the toolbar so it is never ambiguous which project's
 * documents a chat or a citation belongs to.
 */
@Composable
fun ProjectScreen(
    project: ProjectEntity,
    documents: List<DocumentRow>,
    onBack: () -> Unit,
    onImport: (List<android.net.Uri>) -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit,
    onDeleteDocument: (DocumentEntity) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: () -> Unit,
    onArchiveProject: () -> Unit,
    onCompare: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // Persist read access so the file is still readable after a restart.
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            onImport(uris)
        }
    }

    var showRename by remember { mutableStateOf(false) }
    var confirmDeleteDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var confirmDeleteProject by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        WorkToolbar(
            title = project.name,
            subtitle = "Scope: this project only",
            showBack = true,
            onBack = onBack,
            actions = {
                TextButton(onClick = onCompare) {
                    Text("Compare")
                }
                IconButton(onClick = { showRename = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename project")
                }
                IconButton(onClick = onArchiveProject) {
                    Icon(Icons.Filled.Warning, contentDescription = "Archive project")
                }
                IconButton(onClick = { confirmDeleteProject = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete project")
                }
            }
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionHeader(
                    title = "Files",
                    subtitle = "${documents.size} file${if (documents.size == 1) "" else "s"}"
                )
            }

            if (documents.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Add,
                        title = "No files imported",
                        subtitle = "Import the PDFs and workbooks for this project. " +
                            "Supported: PDF, XLSX, XLSM, CSV, TXT.",
                        actionText = "Import files",
                        onAction = {
                            picker.launch(SUPPORTED_MIME_TYPES)
                        }
                    )
                }
            } else {
                items(documents, key = { it.document.id }) { row ->
                    DocumentRowItem(
                        row = row,
                        onClick = { onOpenDocument(row.document) },
                        onDelete = { confirmDeleteDocument = row.document }
                    )
                    WorkDivider()
                }
                item {
                    TextButton(
                        onClick = { picker.launch(SUPPORTED_MIME_TYPES) },
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import more files")
                    }
                }
            }
        }
    }

    if (showRename) {
        RenameProjectDialog(
            project = project,
            onDismiss = { showRename = false },
            onConfirm = { name, description ->
                showRename = false
                onRenameProject(name, description)
            }
        )
    }

    confirmDeleteDocument?.let { document ->
        AlertDialog(
            onDismissRequest = { confirmDeleteDocument = null },
            title = { Text("Delete ${document.filename}?") },
            text = {
                Text(
                    "This permanently removes the document together with its " +
                        "indexed content, summary and citations."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteDocument = null
                    onDeleteDocument(document)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDocument = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmDeleteProject) {
        AlertDialog(
            onDismissRequest = { confirmDeleteProject = false },
            title = { Text("Delete ${project.name}?") },
            text = {
                Text(
                    "This deletes the project and every document, index entry and " +
                        "summary inside it. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteProject = false
                    onDeleteProject()
                }) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteProject = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DocumentRowItem(
    row: DocumentRow,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val document = row.document
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = document.filename,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(document.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildString {
                        append(RelativeTime.fileSize(document.fileSize))
                        if (document.pageCount > 0) append(" · ${document.pageCount} pages")
                        if (document.sheetCount > 0) append(" · ${document.sheetCount} sheets")
                        if (row.chunkCount > 0) append(" · ${row.chunkCount} chunks")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val error = document.errorMessage
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete ${document.filename}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RenameProjectDialog(
    project: ProjectEntity,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(project.name) }
    var description by remember { mutableStateOf(project.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, description) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val SUPPORTED_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel.sheet.macroEnabled.12",
    "text/csv",
    "text/plain"
)
