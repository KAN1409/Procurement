package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.database.DocumentListRow
import com.edib.procurement.database.UnlinkedDocumentRow
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.components.SelectionActionBar
import com.edib.procurement.ui.SelectScope
import com.edib.procurement.ui.components.formatSize
import com.edib.procurement.ui.theme.ProcurementColors

/** DOCUMENTS: the indexed repository, opened externally. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DocumentsScreen(
    vm: ProcurementViewModel,
    onPickFolder: () -> Unit,
    onRescanFolder: () -> Unit,
    onOpenPack: () -> Unit = {}
) {
    val query by vm.query.collectAsStateWithLifecycle()
    // Paged against Room: scrolling appends, and a new search term cancels the
    // previous query rather than filtering what happens to be loaded.
    val documents = vm.documentPages.collectAsLazyPagingItems()
    val selection by vm.selectState.collectAsStateWithLifecycle()
    val selectionActive = selection.inMode
    val config by vm.config.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("Documents", "${counts?.documents ?: 0} indexed")
        SearchField(
            value = query,
            onValueChange = { vm.setQuery(it); vm.syncSelectionQuery() },
            placeholder = "Search file name or relative path…",
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        Spacer(Modifier.height(8.dp))
        if (selectionActive) {
            SelectionActionBar(
                selectedCount = selection.count,
                noun = "files",
                onCreatePack = {
                    vm.openPackRequest(vm.requestForSelection())
                    onOpenPack()
                },
                onSelectAll = { vm.selectAllResults { } },
                onClear = { vm.clearSelection() }
            )
        }

        if (config.treeUri == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPickFolder,
                    colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed),
                    shape = RoundedCornerShape(2.dp)
                ) { Text("Choose document folder") }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onRescanFolder) { Text("Rescan") }
                TextButton(onClick = onPickFolder) { Text("Change folder") }
                Spacer(Modifier.weight(1f))
                // Long press also starts selection, but an explicit control
                // keeps the feature reachable without a hidden gesture.
                TextButton(onClick = { vm.beginSelection(SelectScope.DOCUMENTS) }) {
                    Text(
                        "SELECT",
                        style = MaterialTheme.typography.labelMedium,
                        color = ProcurementColors.AccentRedBright
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
        ) {
            items(documents.itemCount, key = { i -> documents.peek(i)?.id ?: i.toLong() }) { i ->
                val doc = documents[i] ?: return@items
                val picked = doc.id in selection.effectiveIds
                Column(
                    Modifier
                        .then(
                            if (picked) Modifier.background(
                                ProcurementColors.AccentRed.copy(alpha = 0.16f)
                            ) else Modifier
                        )
                        .combinedClickable(
                            onClick = {
                                if (selectionActive) vm.toggleSelection(doc.id)
                                else vm.openDocument(doc.documentUri, doc.extension, doc.mimeType)
                            },
                            onLongClick = {
                                if (!selectionActive) vm.beginSelection(SelectScope.DOCUMENTS)
                                vm.toggleSelection(doc.id)
                            }
                        )
                ) {
                    DocumentRow(doc) {
                        if (selectionActive) vm.toggleSelection(doc.id)
                        else vm.openDocument(doc.documentUri, doc.extension, doc.mimeType)
                    }
                    // An unlinked file is exported as-is. The pack must never
                    // invent Follow-up context for it, so the row says so.
                    if (picked) {
                        Text(
                            if (doc.linkCount > 0) "SELECTED · LINKED"
                            else "SELECTED · UNLINKED · Selection: MANUAL",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProcurementColors.AccentRedBright
                        )
                    }
                }
            }
            if (documents.loadState.append is androidx.paging.LoadState.Loading) {
                item { PagingSpinner() }
            }
            if (documents.itemCount == 0 &&
                documents.loadState.refresh is androidx.paging.LoadState.NotLoading
            ) {
                item {
                    when {
                        config.treeUri == null ->
                            EmptyState("Choose a document folder to begin indexing.")
                        query.isNotBlank() ->
                            EmptyState("No files match your search.")
                        else ->
                            EmptyState("No supported documents were found in the selected folder.")
                    }
                }
            }
        }
    }
}

private const val PAGE = 200

@Composable
private fun DocumentRow(doc: DocumentListRow, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box2()
        Column(Modifier.weight(1f)) {
            Text(
                doc.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = ProcurementColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                doc.relativePath,
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (doc.linkCount > 0) {
                Chip(
                    if (doc.linkCount == 1) "linked" else "linked ×${doc.linkCount}",
                    ProcurementColors.StatusOk
                )
                Spacer(Modifier.height(3.dp))
            }
            Text(
                formatSize(doc.size),
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
    Spacer(Modifier.height(1.dp))
    androidx.compose.material3.HorizontalDivider(color = ProcurementColors.Graphite700, thickness = 1.dp)
}

@Composable
private fun Box2() {
    androidx.compose.foundation.layout.Box(
        Modifier
            .width(2.dp)
            .height(30.dp)
            .background(ProcurementColors.AccentRedDim)
    )
    Spacer(Modifier.width(10.dp))
}

/**
 * UNLINKED: documents with no confident follow-up match.
 *
 * Empty-state text depends on how far setup has actually progressed, so it never
 * claims files are "all linked" when nothing has been indexed yet.
 */
@Composable
fun UnlinkedScreen(vm: ProcurementViewModel) {
    val unlinked by vm.unlinkedDocuments.collectAsStateWithLifecycle()
    val unlinkedCount by vm.unlinkedCount.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val indexed by vm.documentCount.collectAsStateWithLifecycle()

    val folderSelected = config.treeUri != null

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("Unlinked", "$unlinkedCount without a confident match")

        when {
            !folderSelected -> EmptyState("Choose a document folder to begin indexing.")
            indexed == 0 -> EmptyState("No supported documents were found in the selected folder.")
            unlinkedCount == 0 -> EmptyState("All indexed documents are linked. Nothing to review.")
            else -> SectionLabel(
                "Files with no auto-link at 80% or above. Manual links always win.",
                Modifier.padding(horizontal = 14.dp)
            )
        }

        if (folderSelected && indexed > 0 && unlinkedCount > 0) {
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                items(unlinked, key = { it.id }) { doc ->
                    UnlinkedRow(doc)
                }
            }
        }
    }
}

@Composable
private fun UnlinkedRow(doc: UnlinkedDocumentRow) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            doc.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = ProcurementColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            doc.relativePath,
            style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("no match", ProcurementColors.StatusWarn)
            Chip(formatSize(doc.size))
        }
    }
    androidx.compose.material3.HorizontalDivider(color = ProcurementColors.Graphite700, thickness = 1.dp)
}

/** CONTRACTORS: resolved suppliers plus the unresolved raw names to map. */
@Composable
fun ContractorsScreen(
    vm: ProcurementViewModel,
    onOpenContractor: (Long) -> Unit = {}
) {
    val contractors by vm.contractors.collectAsStateWithLifecycle()
    val unresolved by vm.unresolvedContractors.collectAsStateWithLifecycle()
    val aliasCount by vm.contractorAliasCount.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()

    val workbookImported = config.followUpUri != null

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader(
            "Contractors",
            if (workbookImported)
                "${contractors.size} resolved · ${unresolved.size} unresolved"
            else "no workbook imported"
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
        ) {
            when {
                !workbookImported -> {
                    item { EmptyState("Import a Follow-up workbook to begin.") }
                }

                aliasCount == 0 && contractors.isEmpty() -> {
                    item {
                        EmptyState(
                            "This workbook's SUPPLIER LIST sheet contained no mappings, so no " +
                                "contractor was normalised. Contractor cells are shown as the raw " +
                                "workbook text."
                        )
                    }
                }

                else -> {
                    if (unresolved.isNotEmpty()) {
                        item {
                            SectionLabel("Unresolved (open a group to map these)")
                            Spacer(Modifier.height(4.dp))
                        }
                        items(unresolved.take(200)) { raw ->
                            Text(
                                raw,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ProcurementColors.StatusWarn,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                            androidx.compose.material3.HorizontalDivider(
                                color = ProcurementColors.Graphite700, thickness = 1.dp
                            )
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }

                    item { SectionLabel("Resolved from SUPPLIER LIST") }
                    items(contractors, key = { it.id }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenContractor(c.id) }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                c.canonicalName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ProcurementColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${c.entryCount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ProcurementColors.TextTertiary,
                                fontFamily = FontFamily.Monospace
                            )
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

/** FOLLOW-UP: every group across every project, filtered by the search box. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FollowUpScreen(
    vm: ProcurementViewModel,
    onOpenGroup: (Long) -> Unit,
    onOpenPack: () -> Unit = {}
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val groups = vm.groupPages.collectAsLazyPagingItems()
    val selection by vm.selectState.collectAsStateWithLifecycle()
    val selectionActive = selection.inMode
    val config by vm.config.collectAsStateWithLifecycle()
    val groupTotal by vm.groupCount.collectAsStateWithLifecycle()

    val workbookImported = config.followUpUri != null
    val searching = query.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("Follow-up", if (workbookImported) "${groupTotal} groups" else "no workbook")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.beginSelection(SelectScope.GROUPS) }) {
                Text(
                    "SELECT GROUPS",
                    style = MaterialTheme.typography.labelMedium,
                    color = ProcurementColors.AccentRedBright
                )
            }
        }
        if (selectionActive) {
            SelectionActionBar(
                selectedCount = selection.count,
                noun = "groups",
                onCreatePack = {
                    vm.openPackRequest(vm.requestForSelection())
                    onOpenPack()
                },
                onSelectAll = { vm.selectAllResults { } },
                onClear = { vm.clearSelection() }
            )
        }

        when {
            !workbookImported -> {
                EmptyState("Import a Follow-up workbook to begin.")
            }
            groupTotal == 0 -> {
                EmptyState(
                    "The imported workbook produced no usable groups. Check the import " +
                        "report in HOME for a header or schema warning."
                )
            }
            // Only claim "no match" once the query has actually finished:
            // itemCount is 0 while the first page is still loading, and saying
            // "no results" then would be a lie.
            groups.itemCount == 0 &&
                groups.loadState.refresh is androidx.paging.LoadState.NotLoading -> {
                EmptyState(
                    if (searching) "No follow-up groups match your search."
                    else "No follow-up groups in the library."
                )
            }
            else -> {
                SearchField(
                    value = query,
                    onValueChange = { vm.setQuery(it); vm.syncSelectionQuery() },
                    placeholder = "Search code, project, area, contractor, notes…",
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    items(
                        count = groups.itemCount,
                        key = { i -> groups.peek(i)?.id ?: i.toLong() }
                    ) { i ->
                        val g = groups[i] ?: return@items
                        val picked = g.id in selection.effectiveIds
                        Column(
                            Modifier
                                .then(
                                    if (picked) Modifier.background(
                                        ProcurementColors.AccentRed.copy(alpha = 0.16f)
                                    ) else Modifier
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (selectionActive) vm.toggleSelection(g.id)
                                        else onOpenGroup(g.id)
                                    },
                                    onLongClick = {
                                        if (!selectionActive) vm.beginSelection(SelectScope.GROUPS)
                                        vm.toggleSelection(g.id)
                                    }
                                )
                        ) {
                            Text(
                                g.projectRaw ?: "(no project)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ProcurementColors.AccentRedBright
                            )
                            Spacer(Modifier.height(2.dp))
                            GroupRow(g) { onOpenGroup(g.id) }
                            if (picked) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "SELECTED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ProcurementColors.AccentRedBright
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (groups.loadState.append is androidx.paging.LoadState.Loading) {
                        item { PagingSpinner() }
                    }
                }
            }
        }
    }
}
