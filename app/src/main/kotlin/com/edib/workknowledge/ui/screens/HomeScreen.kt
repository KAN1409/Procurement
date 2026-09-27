package com.edib.workknowledge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.data.ProjectEntity
import com.edib.workknowledge.ui.components.EmptyState
import com.edib.workknowledge.ui.components.SectionHeader
import com.edib.workknowledge.ui.components.WorkDivider
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

/**
 * Workspace home. The user arrives here to pick a project; the project list is
 * the primary object, not a chat box.
 */
@Composable
fun HomeScreen(
    projects: List<ProjectEntity>,
    onProjectClick: (ProjectEntity) -> Unit,
    onNewProject: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WORK KNOWLEDGE",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Your project documents, indexed and searchable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenDiagnostics) {
                    Icon(Icons.Filled.List, contentDescription = "Diagnostics")
                }
                TextButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onNewProject) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("New")
                }
            }
        }

        WorkDivider()

        if (projects.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Add,
                title = "No projects yet",
                subtitle = "Create a project, then import the PDFs and workbooks that " +
                    "belong to it. Everything is indexed on your device.",
                actionText = "Create a project",
                onAction = onNewProject
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { SectionHeader("Projects", subtitle = "${projects.size} total") }
                items(projects, key = { it.id }) { project ->
                    ProjectRowItem(project = project, onClick = { onProjectClick(project) })
                    WorkDivider()
                }
            }
        }
    }
}

@Composable
fun ProjectRowItem(project: ProjectEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = project.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (project.description.isNotBlank()) {
            Text(
                text = project.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "Updated ${RelativeTime.format(project.updatedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
