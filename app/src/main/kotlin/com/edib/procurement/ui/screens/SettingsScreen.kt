package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.components.StatRow
import com.edib.procurement.ui.theme.ProcurementColors
import java.text.DateFormat
import java.text.NumberFormat

/**
 * SETTINGS: source selection, re-import history and hard guarantees.
 *
 * The import history exists so a replaced workbook is visibly additive rather
 * than destructive.
 */
@Composable
fun SettingsScreen(
    vm: ProcurementViewModel,
    onPickWorkbook: () -> Unit,
    onPickFolder: () -> Unit,
    onRescanFolder: () -> Unit,
    onRunMatching: () -> Unit
) {
    val config by vm.config.collectAsStateWithLifecycle()
    val imports by vm.imports.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("Settings")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
        ) {
            // ---------------------------------------------------------- source
            item {
                Panel {
                    SectionLabel("Follow-up workbook")
                    StatRow(
                        listOf(
                            "File" to (config.followUpDisplayName ?: "not selected"),
                            "Imported" to formatDate(config.followUpImportedAt)
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onPickWorkbook,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProcurementColors.AccentRed
                            ),
                            shape = RoundedCornerShape(2.dp)
                        ) { Text(if (config.followUpUri == null) "Select workbook" else "Replace workbook") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            item {
                Panel {
                    SectionLabel("Document folder")
                    StatRow(
                        listOf(
                            "Folder" to (config.treeDisplayName ?: "not selected"),
                            "Indexed at" to formatDate(config.treeIndexedAt),
                            "Files indexed" to config.documentCountAtScan.toString()
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRunMatching,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProcurementColors.AccentRed
                            ),
                            shape = RoundedCornerShape(2.dp)
                        ) { Text("Run matching") }
                        OutlinedButton(onClick = onRescanFolder) { Text("Rescan") }
                        TextButton(onClick = onPickFolder) { Text("Change folder") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // --------------------------------------------------------- counts
            item {
                Panel {
                    SectionLabel("Library")
                    val c = counts
                    StatRow(
                        listOf(
                            "Projects" to (c?.projects ?: 0).toString(),
                            "Follow-up groups" to (c?.groups ?: 0).toString(),
                            "Entries" to (c?.entries ?: 0).toString(),
                            "Contractors" to (c?.contractors ?: 0).toString(),
                            "Unresolved contractors" to (c?.unresolvedContractors ?: 0).toString(),
                            "Documents" to (c?.documents ?: 0).toString(),
                            "Linked documents" to (c?.linkedDocuments ?: 0).toString(),
                            "Suggested matches" to (c?.suggestedLinks ?: 0).toString(),
                            "Unlinked documents" to (c?.unlinkedDocuments ?: 0).toString()
                        )
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // --------------------------------------------------------- history
            item {
                SectionLabel("Import history")
            }
            if (imports.isEmpty()) {
                item { EmptyState("No workbook imported yet.") }
            } else {
                items(imports, key = { it.id }) { imp ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                imp.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ProcurementColors.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (imp.isCurrent) {
                                Chip("current", ProcurementColors.StatusOk)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${formatDate(imp.importDate)} · ${NumberFormat.getIntegerInstance().format(imp.groupCount)} groups · " +
                                "${NumberFormat.getIntegerInstance().format(imp.entryCount)} entries",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProcurementColors.TextTertiary
                        )
                        Text(
                            "fingerprint ${imp.workbookFingerprint}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProcurementColors.TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(
                        color = ProcurementColors.Graphite700, thickness = 1.dp
                    )
                }
            }

            // ------------------------------------------------------ guarantees
            item {
                Spacer(Modifier.height(12.dp))
                Panel {
                    SectionLabel("Guarantees")
                    Guarantee("Originals are never modified")
                    Guarantee("Workbook opened read-only")
                    Guarantee("Documents opened in the installed app")
                    Guarantee("Manual links are locked")
                    Guarantee("Tags and corrections survive re-import")
                    Guarantee("No cloud, no account, no network")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Guarantee(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "✓",
            style = MaterialTheme.typography.bodySmall,
            color = ProcurementColors.StatusOk,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ProcurementColors.TextSecondary
        )
    }
}

private fun formatDate(millis: Long): String =
    if (millis <= 0) "—"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(millis)
