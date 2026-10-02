package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.database.SuggestionRow
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.components.formatSize
import com.edib.procurement.ui.theme.ProcurementColors

/**
 * NEEDS REVIEW.
 *
 * Three queues behind one destination, because they are three different kinds
 * of outstanding decision:
 *
 *  - Suggestions: a match the matcher found but would not apply alone.
 *  - Unlinked files: documents with no candidate at all.
 *  - Unresolved contractors: raw supplier text that still needs a decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsReviewScreen(vm: ProcurementViewModel) {
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val unlinked by vm.unlinkedOnly.collectAsStateWithLifecycle()
    val unresolved by vm.unresolvedByUsage.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val indexed by vm.documentCount.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var linking by remember { mutableStateOf<SuggestionRow?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader(
            "Needs Review",
            "${suggestions.size} suggestions · ${unlinked.size} unlinked · ${unresolved.size} contractors"
        )

        TabRow(
            selectedTabIndex = tab,
            containerColor = ProcurementColors.Graphite900,
            contentColor = ProcurementColors.AccentRedBright
        ) {
            listOf("Suggestions", "Unlinked Files", "Contractors").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                )
            }
        }

        when (tab) {
            0 -> SuggestionList(suggestions) { linking = it }
            1 -> UnlinkedList(unlinked, config.treeUri != null, indexed) { linking = it }
            else -> UnresolvedContractorList(vm, unresolved)
        }
    }

    linking?.let { doc ->
        LinkPickerDialog(
            vm = vm,
            documentId = doc.id,
            documentName = doc.displayName,
            onDismiss = { linking = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionList(items: List<SuggestionRow>, onLink: (SuggestionRow) -> Unit) {
    if (items.isEmpty()) {
        EmptyState("No suggestions. Run matching from SETTINGS.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        items(items, key = { it.id }) { row ->
            SuggestionCard(row, onLink)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SuggestionCard(row: SuggestionRow, onLink: (SuggestionRow) -> Unit) {
    Panel(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    row.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Chip("SUGGESTED", ProcurementColors.StatusWarn)
        }

        Spacer(Modifier.height(8.dp))
        if (!row.projectRaw.isNullOrBlank() || !row.codeRaw.isNullOrBlank()) {
            Text(
                "suggested project : ${row.projectRaw ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextSecondary
            )
            Text(
                "suggested code    : ${row.codeRaw ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }

        if (!row.evidence.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            SectionLabel("Evidence")
            row.evidence.lines().forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onLink(row) }) {
                Text("Link to…", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { onLink(row) }) {
                Text("Accept", style = MaterialTheme.typography.labelMedium,
                    color = ProcurementColors.AccentRedBright)
            }
            TextButton(onClick = { /* leaving unlinked is the default */ }) {
                Text("Leave unlinked", style = MaterialTheme.typography.labelMedium,
                    color = ProcurementColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun UnlinkedList(
    items: List<SuggestionRow>,
    folderSelected: Boolean,
    indexed: Int,
    onLink: (SuggestionRow) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        when {
            !folderSelected -> EmptyState("Choose a document folder to begin indexing.")
            indexed == 0 -> EmptyState("No supported documents were found in the selected folder.")
            items.isEmpty() -> EmptyState("All indexed documents are linked. Nothing to review.")
            else -> {
                SectionLabel(
                    "Files with no link at all. Manual links always win.",
                    Modifier.padding(horizontal = 14.dp)
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    items(items, key = { it.id }) { row ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                row.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ProcurementColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                row.relativePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = ProcurementColors.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Chip(formatSize(row.size))
                                TextButton(onClick = { onLink(row) }) {
                                    Text("Link to…", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(
                            color = ProcurementColors.Graphite700, thickness = 1.dp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Unresolved contractor review, ordered by usage so the values that clear the
 * most entries get decided first.
 */
@Composable
private fun UnresolvedContractorList(
    vm: ProcurementViewModel,
    items: List<com.edib.procurement.database.UnresolvedContractorRow>
) {
    var mapping by remember { mutableStateOf<com.edib.procurement.database.UnresolvedContractorRow?>(null) }

    if (items.isEmpty()) {
        EmptyState("Every contractor value resolved.")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        item {
            SectionLabel("Highest usage first")
        }
        items(items, key = { it.companyRaw }) { row ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    row.companyRaw,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ProcurementColors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("${row.usageCount} entries", ProcurementColors.StatusWarn)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { mapping = row }) {
                        Text("Map", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { vm.ignoreContractorValue(row.companyRaw) }) {
                        Text("Not a contractor", style = MaterialTheme.typography.labelMedium,
                            color = ProcurementColors.TextTertiary)
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(
                color = ProcurementColors.Graphite700, thickness = 1.dp
            )
        }
    }

    mapping?.let { row ->
        ContractorDecisionDialog(vm, row.companyRaw) { mapping = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContractorDecisionDialog(
    vm: ProcurementViewModel,
    rawCompany: String,
    onDismiss: () -> Unit
) {
    val contractors by vm.contractors.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    val filtered = remember(search, contractors) {
        if (search.isBlank()) contractors
        else contractors.filter { it.canonicalName.contains(search, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProcurementColors.Graphite850,
        titleContentColor = ProcurementColors.TextPrimary,
        textContentColor = ProcurementColors.TextSecondary,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Map contractor", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                SectionLabel("Workbook text (kept exactly as written)")
                Text(
                    rawCompany,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ProcurementColors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                SearchField(search, { search = it }, "Search existing contractor…")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(200.dp)) {
                    items(filtered.take(60), key = { it.id }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Mapped through the group-detail path so the
                                    // entry ids are known; the repository writes
                                    // the alias for future imports.
                                    vm.mapContractorRaw(rawCompany, c.canonicalName)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                c.canonicalName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ProcurementColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        item { EmptyState("No existing contractor matches") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SectionLabel("Or create a new canonical contractor")
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = {
                        Text("New contractor name", style = MaterialTheme.typography.bodySmall)
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ProcurementColors.AccentRedBright,
                        unfocusedBorderColor = ProcurementColors.Graphite600,
                        focusedContainerColor = ProcurementColors.Graphite900,
                        unfocusedContainerColor = ProcurementColors.Graphite900,
                        focusedTextColor = ProcurementColors.TextPrimary,
                        unfocusedTextColor = ProcurementColors.TextPrimary
                    ),
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            vm.mapContractorRaw(rawCompany, newName.trim())
                            onDismiss()
                        }
                    },
                    enabled = newName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed),
                    shape = RoundedCornerShape(2.dp)
                ) { Text("Create and map", style = MaterialTheme.typography.labelMedium) }
            }
        }
    )
}

/**
 * "Link to…" picker.
 *
 * Searches groups and entries, then lets the user commit to either level. The
 * resulting link is manual and locked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkPickerDialog(
    vm: ProcurementViewModel,
    documentId: Long,
    documentName: String,
    onDismiss: () -> Unit
) {
    // The picker filters on its OWN query state. It used to hold `query`
    // locally while reading vm.linkEntryPages, which is driven by the
    // screen-level search box, so typing here changed nothing in the list.
    val query by vm.linkQuery.collectAsState()
    var level by remember { mutableStateOf("entry") }
    val groups = vm.linkGroupPages.collectAsLazyPagingItems()
    val entries = vm.linkEntryPages.collectAsLazyPagingItems()

    // Start from a blank filter every time the picker opens; a term left over
    // from a previous picker would silently hide most targets.
    LaunchedEffect(Unit) { vm.setLinkQuery("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProcurementColors.Graphite850,
        titleContentColor = ProcurementColors.TextPrimary,
        textContentColor = ProcurementColors.TextSecondary,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Link to…", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                SectionLabel(documentName)
                SearchField(query, vm::setLinkQuery, "Code, project, contractor, description")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { level = "entry" },
                        colors = if (level == "entry") ButtonDefaults.buttonColors(
                            containerColor = ProcurementColors.AccentRed
                        ) else ButtonDefaults.outlinedButtonColors(),
                        shape = RoundedCornerShape(2.dp)
                    ) { Text("Entry", style = MaterialTheme.typography.labelMedium) }
                    OutlinedButton(
                        onClick = { level = "group" },
                        colors = if (level == "group") ButtonDefaults.buttonColors(
                            containerColor = ProcurementColors.AccentRed
                        ) else ButtonDefaults.outlinedButtonColors(),
                        shape = RoundedCornerShape(2.dp)
                    ) { Text("Group", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.height(300.dp)) {
                    if (level == "entry") {
                        items(entries.itemCount, key = { i -> entries.peek(i)?.entryId ?: i.toLong() }) { i ->
                            val e = entries[i] ?: return@items
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.linkManually(documentId, e.groupId, e.entryId, null)
                                        onDismiss()
                                    }
                                    .padding(vertical = 7.dp, horizontal = 2.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.projectRaw ?: "(no project)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ProcurementColors.AccentRedBright
                                    )
                                    Text(
                                        e.codeRaw ?: "(no code)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ProcurementColors.TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        e.companyRaw ?: e.description ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ProcurementColors.TextTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            androidx.compose.material3.HorizontalDivider(
                                color = ProcurementColors.Graphite700, thickness = 1.dp
                            )
                        }
                        if (entries.itemCount == 0) {
                            item { EmptyState("No entries match") }
                        }
                    } else {
                        items(groups.itemCount, key = { i -> groups.peek(i)?.groupId ?: i.toLong() }) { i ->
                            val g = groups[i] ?: return@items
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.linkManually(documentId, g.groupId, null, null)
                                        onDismiss()
                                    }
                                    .padding(vertical = 7.dp, horizontal = 2.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        g.projectRaw ?: "(no project)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ProcurementColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${g.rootCodeRaw ?: "—"} · ${g.entryCount} entries",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ProcurementColors.TextTertiary,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            androidx.compose.material3.HorizontalDivider(
                                color = ProcurementColors.Graphite700, thickness = 1.dp
                            )
                        }
                        if (groups.itemCount == 0) {
                            item { EmptyState("No groups match") }
                        }
                    }
                }
            }
        }
    )
}
