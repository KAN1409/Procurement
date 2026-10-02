package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import com.edib.procurement.database.GroupListRow
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.theme.ProcurementColors

/**
 * PROJECTS list.
 *
 * Shows logical Follow-up GROUPS, never raw spreadsheet rows. A continuation
 * entry such as "MMH2-GR-AR-14-25 B" appears inside its group's entry count, not
 * as its own project row.
 */
@Composable
fun ProjectsScreen(
    vm: ProcurementViewModel,
    onOpenGroup: (Long) -> Unit,
    onOpenProject: (Long) -> Unit = {}
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()

    // Paged against the database. A new search term builds a new Pager, so the
    // previous query is cancelled rather than a loaded page being filtered.
    val projects = vm.projectPages.collectAsLazyPagingItems()

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("Projects", counts?.projects?.toString() ?: "")
        SearchField(
            value = query,
            onValueChange = vm::setQuery,
            placeholder = "Search project, code, contractor\u2026",
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        Spacer(Modifier.height(10.dp))

        if (projects.itemCount == 0 &&
            projects.loadState.refresh is androidx.paging.LoadState.NotLoading
        ) {
            EmptyState(
                when {
                    query.isNotBlank() -> "No projects match your search."
                    config.followUpUri == null -> "Import a Follow-up workbook to begin."
                    else -> "The imported workbook produced no usable project groups."
                }
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp)
        ) {
            items(projects.itemCount, key = { i -> projects.peek(i)?.id ?: i.toLong() }) { i ->
                val p = projects[i] ?: return@items
                PagedProjectRow(
                    id = p.id,
                    name = p.canonicalName,
                    groups = p.groupCount,
                    documents = p.documentCount,
                    vm = vm,
                    onOpenGroup = onOpenGroup,
                    onOpenProject = onOpenProject
                )
                Spacer(Modifier.height(6.dp))
            }
            if (projects.loadState.append is androidx.paging.LoadState.Loading) {
                item { PagingSpinner() }
            }
        }
    }
}

@Composable
private fun PagedProjectRow(
    id: Long,
    name: String,
    groups: Int,
    documents: Int,
    vm: ProcurementViewModel,
    onOpenGroup: (Long) -> Unit,
    onOpenProject: (Long) -> Unit
) {
    var expanded by rememberSaveable(name) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(ProcurementColors.Graphite850, RoundedCornerShape(3.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .weight(1f)
                    // The name opens Project Detail; the chevron on the right
                    // still expands the inline group list, so both gestures have
                    // a distinct, discoverable target.
                    .clickable { onOpenProject(id) }
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("$groups groups")
                    if (documents > 0) Chip("$documents docs", ProcurementColors.StatusInfo)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (expanded) "\u25be" else "\u25b8",
                color = ProcurementColors.TextTertiary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        if (expanded) {
            // The group's own list is paged too, so expanding a large project
            // does not pull every group into memory.
            val groupsPaged = vm.groupPages.collectAsLazyPagingItems()
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            ) {
                items(
                    count = groupsPaged.itemCount,
                    key = { i -> groupsPaged.peek(i)?.id ?: i.toLong() }
                ) { i ->
                    val g = groupsPaged[i] ?: return@items
                    GroupRow(g) { onOpenGroup(g.id) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * Footer shown while the next page loads.
 *
 * Paging 3 appends as the reader reaches the end of what is loaded, so this is
 * the only feedback needed: the list itself never blanks between pages.
 */
@Composable
fun PagingSpinner() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            Modifier.size(22.dp),
            color = ProcurementColors.AccentRedBright,
            strokeWidth = 2.dp
        )
    }
}

/** One Follow-up group inside a project. */
@Composable
fun GroupRow(group: GroupListRow, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 7.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                group.rootCodeRaw ?: group.number ?: "(no code)",
                style = MaterialTheme.typography.bodyMedium,
                color = ProcurementColors.TextPrimary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (group.isFavorite) {
                Text("★", color = ProcurementColors.AccentRedBright,
                    style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(6.dp))
            }
        }
        val meta = buildList {
            if (!group.area.isNullOrBlank()) add(group.area)
            if (!group.relatedTo.isNullOrBlank()) add(group.relatedTo)
            add("${group.entryCount} ${if (group.entryCount == 1) "entry" else "entries"}")
            if (group.documentCount > 0) add("${group.documentCount} docs")
        }.joinToString(" · ")
        Text(
            meta,
            style = MaterialTheme.typography.bodySmall,
            color = ProcurementColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ScreenHeader(title: String, meta: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ProcurementColors.Graphite900)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = ProcurementColors.TextPrimary
            )
            if (meta != null) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }
        }
    }
    SectionLabel("")
}

@Composable
fun BoxSpacer(height: Int = 8) { Spacer(Modifier.height(height.dp)) }

@Composable
fun BoldLabel(text: String, color: androidx.compose.ui.graphics.Color = ProcurementColors.TextPrimary) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold
    )
}
