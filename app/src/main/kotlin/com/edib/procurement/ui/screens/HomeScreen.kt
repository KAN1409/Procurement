package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.data.LibraryConfig
import com.edib.procurement.followup.ImportReport
import com.edib.procurement.ui.BusyState
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.components.StatRow
import com.edib.procurement.ui.theme.ProcurementColors
import java.text.DateFormat
import java.text.NumberFormat

private val NUMBER: NumberFormat = NumberFormat.getIntegerInstance()

private fun formatDate(millis: Long): String =
    if (millis <= 0) "—"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(millis)

/**
 * HOME.
 *
 * Two distinct shapes: a compact two-step setup panel before the library is
 * configured, and a dashboard afterwards. The setup card disappears once both
 * sources are in place so it does not permanently occupy the top of Home.
 */
@Composable
fun HomeScreen(
    vm: ProcurementViewModel,
    onPickWorkbook: () -> Unit,
    onPickFolder: () -> Unit,
    onRescanFolder: () -> Unit,
    onRunMatching: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenFollowUp: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenContractors: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val config by vm.config.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val unresolved by vm.unresolvedContractors.collectAsStateWithLifecycle()
    val unlinkedCount by vm.unlinkedCount.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    var showReport by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }

    val workbookReady = config.followUpUri != null
    val folderReady = config.treeUri != null
    val fullySetUp = workbookReady && folderReady

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // ------------------------------------------------------- header
        Text(
            "PROCUREMENT LIBRARY",
            style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.AccentRedBright,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Follow-up",
            style = MaterialTheme.typography.displaySmall,
            color = ProcurementColors.TextPrimary
        )
        Spacer(Modifier.height(14.dp))

        if (busy.running) {
            BusyPanel(busy)
            Spacer(Modifier.height(14.dp))
        }

        // ------------------------------------------------- search (primary)
        if (fullySetUp) {
            SearchField(
                value = query,
                onValueChange = vm::setQuery,
                placeholder = "Search project, code, contractor…",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
        }

        // ------------------------------------------------------- setup card
        if (!fullySetUp) {
            Panel {
                SectionLabel("Set up your library")
                SetupStep(
                    number = 1,
                    title = "Follow-up workbook",
                    state = if (workbookReady) config.followUpDisplayName ?: "selected" else null,
                    missing = "Not selected",
                    action = if (workbookReady) "Change" else "Select workbook",
                    onClick = onPickWorkbook
                )
                Spacer(Modifier.height(12.dp))
                SetupStep(
                    number = 2,
                    title = "Document root folder",
                    state = if (folderReady) config.treeDisplayName else null,
                    missing = "Not selected",
                    action = if (folderReady) "Change" else "Select folder",
                    onClick = onPickFolder
                )
                if (workbookReady && !folderReady) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Step 2 can wait. You can browse projects and groups first.",
                        style = MaterialTheme.typography.labelSmall,
                        color = ProcurementColors.TextTertiary
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // --------------------------------------------- dashboard (post-setup)
        if (fullySetUp) {
            // Headline counts only: projects, groups, files, needs review.
            Panel {
                SectionLabel("Library")
                StatRow(
                    listOf(
                        "Projects" to (counts?.projects ?: 0).let { NUMBER.format(it) },
                        "Follow-up Groups" to (counts?.groups ?: 0).let { NUMBER.format(it) },
                        "Files" to (counts?.documents ?: 0).let { NUMBER.format(it) },
                        "Needs Review" to needsReview(counts?.unlinkedDocuments ?: 0,
                            counts?.suggestedLinks ?: 0, unresolved.size)
                            .let { NUMBER.format(it) }
                    )
                )
            }
            Spacer(Modifier.height(14.dp))

            // ------------------------------------------ recent / projects
            SectionLabel("Projects")
            Panel {
                if (projects.isEmpty()) {
                    Text(
                        "The imported workbook contains no usable project groups.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ProcurementColors.StatusWarn
                    )
                } else {
                    projects.take(6).forEachIndexed { index, p ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenProjects() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                p.canonicalName,
                                style = MaterialTheme.typography.titleSmall,
                                color = ProcurementColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${p.groupCount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ProcurementColors.TextTertiary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (projects.size > 6) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "+ ${projects.size - 6} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = ProcurementColors.AccentRedBright,
                            modifier = Modifier.clickable { onOpenProjects() }
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // ------------------------------------------------ needs review
            SectionLabel("Needs Review")
            Panel {
                if (needsReview(
                        counts?.unlinkedDocuments ?: 0,
                        counts?.suggestedLinks ?: 0,
                        unresolved.size
                    ) == 0
                ) {
                    Text(
                        "Nothing waiting for review.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ProcurementColors.TextSecondary
                    )
                } else {
                    if ((counts?.unlinkedDocuments ?: 0) > 0) {
                        ReviewLine(
                            "Unlinked files", counts!!.unlinkedDocuments.toString(),
                            onOpenDocuments
                        )
                    }
                    if ((counts?.suggestedLinks ?: 0) > 0) {
                        ReviewLine(
                            "Suggested matches", counts!!.suggestedLinks.toString(),
                            onOpenDocuments
                        )
                    }
                    if (unresolved.isNotEmpty()) {
                        ReviewLine(
                            "Unresolved contractors", unresolved.size.toString(),
                            onOpenContractors
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ------------------------------------------------ compact status
        SectionLabel("Status")
        Panel {
            StatRow(
                listOf(
                    "Follow-up" to (config.followUpDisplayName ?: "Not selected"),
                    "Imported" to formatDate(config.followUpImportedAt),
                    "Documents" to (config.treeDisplayName ?: "Not selected"),
                    "Last scanned" to formatDate(config.treeIndexedAt),
                    "Files indexed" to (counts?.documents ?: 0).let { NUMBER.format(it) }
                )
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRunMatching) { Text("Run matching") }
                TextButton(onClick = onOpenSettings) { Text("All settings") }
            }
            Spacer(Modifier.height(14.dp))
        }
        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------ import report
        if (report != null) {
            Panel {
                SectionLabel("Last import")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${report!!.groups} groups · ${report!!.entries} entries",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ProcurementColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showReport = true }) { Text("Details") }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ------------------------------------------------ library links
        if (fullySetUp) {
            SectionLabel("Browse")
            NavRow("Projects", (counts?.projects ?: 0).let { "$it" }, onOpenProjects)
            NavRow("Follow-up", (counts?.groups ?: 0).let { "$it groups" }, onOpenFollowUp)
            NavRow("Files", (counts?.documents ?: 0).let { "$it" }, onOpenDocuments)
            NavRow("Contractors", (counts?.contractors ?: 0).let { "$it" }, onOpenContractors)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { showStats = true }) {
                Text("Statistics", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    if (showReport && report != null) {
        ImportReportDialog(report!!) { showReport = false }
    }
    if (showStats) {
        StatisticsDialog(
            counts = counts,
            unlinkedCount = unlinkedCount,
            unresolvedCount = unresolved.size,
            onDismiss = { showStats = false }
        )
    }
}

private fun needsReview(unlinked: Int, suggested: Int, unresolved: Int): Int =
    unlinked + suggested + unresolved

@Composable
private fun BusyPanel(busy: BusyState) {
    Panel {
        Text(
            busy.label,
            style = MaterialTheme.typography.titleSmall,
            color = ProcurementColors.TextPrimary
        )
        if (busy.detail.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                busy.detail,
                style = MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextSecondary
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            Modifier.fillMaxWidth(),
            color = ProcurementColors.AccentRedBright,
            trackColor = ProcurementColors.Graphite700
        )
    }
}

/**
 * One setup step.
 *
 * [state] is the selected name, or null when nothing is selected yet, in which
 * case [missing] is shown. The label never claims a success that has not
 * happened.
 */
@Composable
private fun SetupStep(
    number: Int,
    title: String,
    state: String?,
    missing: String,
    action: String,
    onClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .width(22.dp)
                .height(22.dp)
                .background(
                    if (state != null) ProcurementColors.StatusOk else ProcurementColors.Graphite700,
                    RoundedCornerShape(2.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (state != null) "✓" else number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (state != null) Color.Black else ProcurementColors.TextSecondary
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = ProcurementColors.TextPrimary
            )
            Text(
                state ?: missing,
                style = MaterialTheme.typography.bodySmall,
                color = if (state != null) ProcurementColors.TextSecondary
                else ProcurementColors.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = ProcurementColors.AccentRed,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(2.dp)
        ) { Text(action, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun NavRow(title: String, meta: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ProcurementColors.Graphite850, RoundedCornerShape(3.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = ProcurementColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            meta,
            style = MaterialTheme.typography.labelMedium,
            color = ProcurementColors.TextTertiary,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(6.dp))
        Text("›", color = ProcurementColors.TextTertiary)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ReviewLine(label: String, count: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = ProcurementColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Chip(count, ProcurementColors.StatusWarn)
    }
}

@Composable
private fun ImportReportDialog(report: ImportReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProcurementColors.Graphite850,
        titleContentColor = ProcurementColors.TextPrimary,
        textContentColor = ProcurementColors.TextSecondary,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("FOLLOW-UP IMPORTED", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Groups: ${report.groups}\nEntries: ${report.entries}\n" +
                        "Projects: ${report.projects}\nContractors: ${report.contractors}\n" +
                        "Unresolved contractors: ${report.unresolvedContractors}\n" +
                        "Contractor aliases: ${report.supplierAliases}\n" +
                        "Continuation rows: ${report.continuationRows}\n" +
                        "Hyperlink paths resolved: ${report.hyperlinkResolvedColumns}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))
                SectionLabel("Sheets found")
                Text(report.sheetsFound.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                SectionLabel("Recognised columns")
                Text(report.recognizedFields.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                if (report.unknownColumnHeaders.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Unrecognised columns (kept, not mapped)")
                    Text(
                        report.unknownColumnHeaders.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (report.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Warnings")
                    report.warnings.forEach {
                        Text(
                            "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = ProcurementColors.StatusWarn
                        )
                    }
                }
            }
        }
    )
}

/**
 * Secondary counters, kept out of the main Home body so the dashboard stays
 * short. Nothing here is a speculative procurement metric: every value is a
 * direct count of something the library actually holds.
 */
@Composable
private fun StatisticsDialog(
    counts: com.edib.procurement.database.HomeCounts?,
    unlinkedCount: Int,
    unresolvedCount: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProcurementColors.Graphite850,
        titleContentColor = ProcurementColors.TextPrimary,
        textContentColor = ProcurementColors.TextSecondary,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Statistics", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                val c = counts
                StatRow(
                    listOf(
                        "Projects" to "${c?.projects ?: 0}",
                        "Follow-up groups" to "${c?.groups ?: 0}",
                        "Entries" to "${c?.entries ?: 0}",
                        "Contractors" to "${c?.contractors ?: 0}",
                        "Unresolved contractors" to "$unresolvedCount",
                        "Indexed files" to "${c?.documents ?: 0}",
                        "Linked files" to "${c?.linkedDocuments ?: 0}",
                        "Suggested links" to "${c?.suggestedLinks ?: 0}",
                        "Unlinked files" to "$unlinkedCount"
                    )
                )
            }
        }
    )
}

/** Shared search field styling. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodySmall)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ProcurementColors.AccentRedBright,
            unfocusedBorderColor = ProcurementColors.Graphite600,
            focusedContainerColor = ProcurementColors.Graphite850,
            unfocusedContainerColor = ProcurementColors.Graphite850,
            focusedTextColor = ProcurementColors.TextPrimary,
            unfocusedTextColor = ProcurementColors.TextPrimary,
            cursorColor = ProcurementColors.AccentRedBright
        ),
        shape = RoundedCornerShape(2.dp),
        modifier = modifier.fillMaxWidth()
    )
}
