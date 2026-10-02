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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.database.EntryRow
import com.edib.procurement.database.LinkedDocumentRow
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.components.formatSize
import com.edib.procurement.ui.theme.ProcurementColors

/**
 * GROUP DETAIL.
 *
 * Header carries the group context that the continuation rows inherited, then
 * the entries, then the linked documents with how each one was matched.
 */
@Composable
fun GroupDetailScreen(
    vm: ProcurementViewModel,
    groupId: Long,
    onBack: () -> Unit,
    onOpenPack: () -> Unit = {}
) {
    val group by vm.groupDetail(groupId).collectAsStateWithLifecycle(initialValue = null)
    val entries by vm.entryList(groupId).collectAsStateWithLifecycle(initialValue = emptyList())
    val documents by vm.documentList(groupId).collectAsStateWithLifecycle(initialValue = emptyList())
    var contractorTarget by remember { mutableStateOf<EntryRow?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        // Visible back affordance, not just the system gesture.
        Row(
            Modifier
                .fillMaxWidth()
                .background(ProcurementColors.Graphite850)
                .clickable { onBack() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                style = MaterialTheme.typography.headlineSmall,
                color = ProcurementColors.AccentRedBright
            )
            Spacer(Modifier.width(8.dp))
            Text(
                group?.rootCodeRaw ?: "Follow-up group",
                style = MaterialTheme.typography.titleMedium,
                color = ProcurementColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
        ) {
            // -------------------------------------------------------- header
            item {
                Panel {
                    SectionLabel("Follow-up group")
                    HeaderLine("Project", group?.manualProjectOverride ?: group?.projectRaw)
                    HeaderLine("Root code", group?.rootCodeRaw)
                    HeaderLine("Area", group?.area)
                    HeaderLine("Responsible", group?.relatedTo)
                    HeaderLine("PR date", group?.prDate)
                    HeaderLine(
                        "Workbook row",
                        group?.originalExcelRow?.takeIf { it > 0 }?.let { "row $it" }
                    )
                    if (!group?.notes.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        SectionLabel("Notes")
                        Text(
                            group!!.notes!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = ProcurementColors.TextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // -------------------------------------------------------- entries
            item {
                SectionLabel("Follow-up entries (${entries.size})")
            }
            if (entries.isEmpty()) {
                item { EmptyState("No entries in this group") }
            } else {
                items(entries, key = { it.id }) { entry ->
                    EntryRowView(
                        entry = entry,
                        onContractorClick = { contractorTarget = entry }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ------------------------------------------------------ documents
            item {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Documents (${documents.size})")
                    Spacer(Modifier.width(10.dp))
                    TextButton(
                        onClick = {
                            vm.buildGroupPackScope(groupId)
                            onOpenPack()
                        }
                    ) {
                        Text(
                            "AI PACK",
                            style = MaterialTheme.typography.labelMedium,
                            color = ProcurementColors.AccentRedBright
                        )
                    }
                }
            }

            if (documents.isEmpty()) {
                item {
                    EmptyState(
                        "No documents linked yet.\n" +
                            "Matching runs after the document folder is indexed."
                    )
                }
            } else {
                items(documents, key = { it.documentId }) { doc ->
                    LinkedDocumentView(doc) { vm.openDocument(doc.documentUri, doc.extension, doc.mimeType) }
                    Spacer(Modifier.height(6.dp))
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }

    contractorTarget?.let { entry ->
        ContractorMappingDialog(
            vm = vm,
            entry = entry,
            onDismiss = { contractorTarget = null }
        )
    }
}

@Composable
private fun HeaderLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = ProcurementColors.TextTertiary,
            modifier = Modifier.width(110.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = ProcurementColors.TextPrimary
        )
    }
}

/**
 * One entry. The variant badge (A, B, "L adj", …) comes from the group root, so
 * a continuation row is always shown as an entry of its group.
 */
@Composable
private fun EntryRowView(entry: EntryRow, onContractorClick: () -> Unit) {
    Panel(padding = PaddingValues(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!entry.entryVariant.isNullOrBlank()) {
                Chip(
                    entry.entryVariant,
                    ProcurementColors.AccentRedBright,
                    ProcurementColors.AccentRedDim
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                entry.codeRaw ?: "(no code)",
                style = MaterialTheme.typography.bodyMedium,
                color = ProcurementColors.TextPrimary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (!entry.description.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = ProcurementColors.TextSecondary
            )
        }

        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val resolved = entry.manualContractorName
                    ?: entry.contractorName
                    ?: entry.companyRaw
                if (!resolved.isNullOrBlank()) {
                    Text(
                        resolved,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.contractorName == null && entry.manualContractorName == null)
                            ProcurementColors.StatusWarn else ProcurementColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onContractorClick() }
                    )
                    // The untouched workbook text is always kept in view when it
                    // differs from the resolved contractor.
                    if (entry.companyRaw != null && entry.companyRaw != resolved) {
                        Text(
                            "workbook: ${entry.companyRaw}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProcurementColors.TextTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (!entry.orderDate.isNullOrBlank()) {
                Text(
                    entry.orderDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        val facts = buildList {
            if (!entry.prDate.isNullOrBlank()) add("PR ${entry.prDate}")
            if (!entry.prWithEngineer.isNullOrBlank()) add("eng. ${entry.prWithEngineer}")
            if (!entry.financialReceivedDate.isNullOrBlank()) add("fin ${entry.financialReceivedDate}")
            if (!entry.finalApprovalDate.isNullOrBlank()) add("appr ${entry.finalApprovalDate}")
            if (!entry.checkValue.isNullOrBlank()) add(entry.checkValue)
        }
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                facts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary
            )
        }

        if (!entry.notes.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                entry.notes,
                style = MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextSecondary
            )
        }

        // Historical references: shown as provenance, never opened directly
        // because they are Windows paths that do not exist on Android.
        val refs = listOfNotNull(
            entry.prPathRaw?.takeIf { it.isNotBlank() },
            entry.orderPathRaw?.takeIf { it.isNotBlank() }
        )
        if (refs.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            SectionLabel("Workbook reference")
            refs.forEach { r ->
                Text(
                    r,
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A linked document plus how it was matched. */
@Composable
private fun LinkedDocumentView(doc: LinkedDocumentRow, onOpen: () -> Unit) {
    val (tint, label) = when {
        doc.isManual -> ProcurementColors.StatusOk to "MANUAL"
        doc.confidence >= 95 -> ProcurementColors.StatusOk to "AUTO"
        doc.confidence >= 80 -> ProcurementColors.StatusWarn to "SUGGESTED"
        else -> ProcurementColors.TextTertiary to "UNLINKED"
    }
    Panel(padding = PaddingValues(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                doc.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = ProcurementColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Chip(label, tint)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            doc.relativePath,
            style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!doc.entryCodeRaw.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "linked to entry ${doc.entryCodeRaw}",
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextSecondary
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${doc.matchMethod.replace('_', ' ').lowercase()} · ${doc.confidence}% · ${formatSize(doc.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpen) {
                Text("OPEN", style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.AccentRedBright)
            }
        }
        if (!doc.evidence.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                "Matched because: ${doc.evidence}",
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary
            )
        }
    }
}

/** Lets the user fix an unresolved or wrong contractor and remember it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContractorMappingDialog(
    vm: ProcurementViewModel,
    entry: EntryRow,
    onDismiss: () -> Unit
) {
    val contractors by vm.contractors.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val filtered = remember(search, contractors) {
        if (search.isBlank()) contractors
        else contractors.filter {
            it.canonicalName.contains(search, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProcurementColors.Graphite850,
        titleContentColor = ProcurementColors.TextPrimary,
        textContentColor = ProcurementColors.TextSecondary,
        title = {
            Text("Map contractor", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            LazyColumn(Modifier.height(380.dp)) {
                item {
                    Column {
                        SectionLabel("Workbook text (kept as-is)")
                        Text(
                            entry.companyRaw ?: "(blank)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ProcurementColors.TextPrimary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                item {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        SearchField(search, { search = it }, "Search contractor…")
                    }
                }
                items(filtered.take(120), key = { it.id }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.mapContractor(entry.id, entry.companyRaw, c.id)
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
                        if (entry.contractorName == c.canonicalName ||
                            entry.manualContractorName == c.canonicalName
                        ) {
                            Chip("current", ProcurementColors.StatusOk)
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item { EmptyState("No contractor matches that name") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
