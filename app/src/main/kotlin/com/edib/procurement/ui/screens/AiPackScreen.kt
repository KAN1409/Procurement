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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.aipack.PackDetail
import com.edib.procurement.aipack.PackInclude
import com.edib.procurement.aipack.PackScope
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.components.formatSize
import com.edib.procurement.ui.theme.ProcurementColors

/**
 * AI PACK creation.
 *
 * Large scopes show a configuration sheet first rather than blindly sweeping
 * every binary into the pack: the user decides what is included and how much
 * detail survives, and sees the real counts before anything is written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPackScreen(vm: ProcurementViewModel, onClose: () -> Unit) {
    val scope by vm.packScope.collectAsStateWithLifecycle()
    val preview by vm.packPreview.collectAsStateWithLifecycle()
    val progress by vm.packProgress.collectAsStateWithLifecycle()
    val result by vm.packResult.collectAsStateWithLifecycle()

    var include by remember {
        mutableStateOf(
            PackInclude(
                followUpData = true, pdf = true, excel = true, word = true, text = true,
                unlinkedDocuments = false
            )
        )
    }
    var detail by remember { mutableStateOf(PackDetail.COMPACT) }
    var zip by remember { mutableStateOf(true) }
    var showConfig by remember { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }
    var docQuery by remember { mutableStateOf("") }
    val selectedDocs = scope?.documentIds?.toSet() ?: emptySet()

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("AI Pack", scope?.name ?: "")

        when {
            result != null -> PackResultView(
                result = result!!,
                onOpen = { vm.openPack(result!!) },
                onShare = { vm.sharePack(result!!) },
                onNew = { vm.clearPack(); showConfig = true }
            )

            progress != null -> PackProgressView(
                progress = progress!!,
                onCancel = { vm.cancelPack() }
            )

            else -> if (picking) {
                // Choosing extra files by hand, whether or not they are linked.
                DocumentChooserSheet(
                    vm = vm,
                    query = docQuery,
                    onQuery = { docQuery = it },
                    selected = selectedDocs,
                    onToggle = { vm.togglePackDocument(it) },
                    onDone = { picking = false }
                )
            } else {
                // The configuration sheet is mandatory for a wide scope: it is
                // the step that stops a 48-group project silently pulling in
                // every binary in the repository.
                if (showConfig) {
                    ConfigurationSheet(
                        scopeName = scope?.name ?: "",
                        previewGroups = preview?.groups ?: 0,
                        previewEntries = preview?.entries ?: 0,
                        previewDocuments = preview?.documents ?: 0,
                        originalBytes = preview?.originalBytes ?: 0,
                        include = include,
                        onInclude = { include = it },
                        detail = detail,
                        onDetail = { detail = it },
                        zip = zip,
                        onZip = { zip = it },
                        onAddDocuments = { picking = true },
                        onCreate = {
                            showConfig = false
                            vm.buildPack(include, detail, zip)
                        },
                        onCancel = onClose
                    )
                } else {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        Panel {
                            SectionLabel("Documents")
                            Text(
                                "The pack will include every document linked to the " +
                                    "selected entries. Add any other file from the Files " +
                                    "screen using its context menu.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ProcurementColors.TextSecondary
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showConfig = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProcurementColors.AccentRed
                            ),
                            shape = RoundedCornerShape(2.dp)
                        ) { Text("Back to configuration") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationSheet(
    scopeName: String,
    previewGroups: Int,
    previewEntries: Int,
    previewDocuments: Int,
    originalBytes: Long,
    include: PackInclude,
    onInclude: (PackInclude) -> Unit,
    detail: PackDetail,
    onDetail: (PackDetail) -> Unit,
    zip: Boolean,
    onZip: (Boolean) -> Unit,
    onAddDocuments: () -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit
) {
    // Scrollable: the configuration sheet is taller than a phone screen, and
    // without this the CREATE button sits below the fold with no way to reach it.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        Panel {
            SectionLabel("Create AI Pack")
            Text(
                "Scope: $scopeName",
                style = MaterialTheme.typography.titleSmall,
                color = ProcurementColors.TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Follow-up groups     $previewGroups\n" +
                    "Entries              $previewEntries\n" +
                    "Documents selected   $previewDocuments\n" +
                    "Estimated original   ${formatSize(originalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = ProcurementColors.TextSecondary
            )
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            SectionLabel("Include")
            CheckRow("Follow-up structured data", include.followUpData) {
                onInclude(include.copy(followUpData = it))
            }
            CheckRow("PDF text", include.pdf) { onInclude(include.copy(pdf = it)) }
            CheckRow("Excel data", include.excel) { onInclude(include.copy(excel = it)) }
            CheckRow("Word text", include.word) { onInclude(include.copy(word = it)) }
            CheckRow("Plain text / CSV", include.text) { onInclude(include.copy(text = it)) }
            CheckRow("Unlinked documents", include.unlinkedDocuments) {
                onInclude(include.copy(unlinkedDocuments = it))
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            SectionLabel("Mode")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = detail == PackDetail.COMPACT,
                    onClick = { onDetail(PackDetail.COMPACT) }
                )
                Text("Compact", style = MaterialTheme.typography.bodyMedium,
                    color = ProcurementColors.TextPrimary)
                Spacer(Modifier.width(16.dp))
                RadioButton(
                    selected = detail == PackDetail.DETAILED,
                    onClick = { onDetail(PackDetail.DETAILED) }
                )
                Text("Detailed", style = MaterialTheme.typography.bodyMedium,
                    color = ProcurementColors.TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = zip, onClick = { onZip(true) })
                Text("ZIP with manifest", style = MaterialTheme.typography.bodyMedium,
                    color = ProcurementColors.TextPrimary)
                Spacer(Modifier.width(16.dp))
                RadioButton(selected = !zip, onClick = { onZip(false) })
                Text("Single .md", style = MaterialTheme.typography.bodyMedium,
                    color = ProcurementColors.TextPrimary)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddDocuments, shape = RoundedCornerShape(2.dp)) {
                Text("ADD DOCUMENTS")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCreate,
                colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed),
                shape = RoundedCornerShape(2.dp),
                enabled = previewGroups > 0 || previewDocuments > 0
            ) { Text("CREATE AI PACK") }
            TextButton(onClick = onCancel) { Text("Cancel", color = ProcurementColors.TextSecondary) }
        }
    }
}

/**
 * Extra documents to fold into the pack.
 *
 * A document does not have to be linked to be included: the whole point of a
 * pack is to gather scattered paperwork for one scope, so anything in the index
 * can be added by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentChooserSheet(
    vm: ProcurementViewModel,
    query: String,
    onQuery: (String) -> Unit,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onDone: () -> Unit
) {
    val docs = vm.documentPages.collectAsLazyPagingItems()
    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Panel {
            SectionLabel("Add documents")
            Text(
                "Tick any file to include it in the pack, whether or not it is " +
                    "linked to a Follow-up entry.",
                style = MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextSecondary
            )
        }
        Spacer(Modifier.height(8.dp))
        SearchField(query, onQuery, "Search file name or path…")
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
            if (docs.itemCount == 0) {
                item { EmptyState("No documents match") }
            } else {
                items(count = docs.itemCount, key = { i -> docs[i]?.id ?: i.toLong() }) { index ->
                    val d = docs[index] ?: return@items
                    val on = d.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(d.id) }
                            .padding(vertical = 8.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = on, onCheckedChange = { onToggle(d.id) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ProcurementColors.TextPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                d.relativePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = ProcurementColors.TextTertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(
                        color = ProcurementColors.Graphite700, thickness = 1.dp
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed),
                shape = RoundedCornerShape(2.dp)
            ) { Text("Done") }
        }
    }
}

/** Documents offered by the chooser, paged from the database. */

@Composable
private fun PackProgressView(
    progress: com.edib.procurement.aipack.PackProgress,
    onCancel: () -> Unit
) {
    Panel {
        Text(
            "${progress.phase}...",
            style = MaterialTheme.typography.titleSmall,
            color = ProcurementColors.TextPrimary
        )
        if (progress.total > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${progress.current} / ${progress.total}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = ProcurementColors.TextSecondary
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            Modifier.fillMaxWidth(),
            color = ProcurementColors.AccentRedBright,
            trackColor = ProcurementColors.Graphite700
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onCancel) {
            Text("Cancel", color = ProcurementColors.StatusWarn)
        }
    }
}

@Composable
private fun PackResultView(
    result: com.edib.procurement.aipack.PackResult,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onNew: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        Panel {
            SectionLabel("AI Pack created")
            Text(
                "Scope: ${result.scopeName}",
                style = MaterialTheme.typography.bodyMedium,
                color = ProcurementColors.TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${result.groups} groups\n" +
                    "${result.entries} entries\n" +
                    "${result.documents} documents",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = ProcurementColors.TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Original size   ${formatSize(result.originalTotalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextSecondary
            )
            Text(
                "AI Pack         ${formatSize(result.packBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextPrimary
            )
            Text(
                "Reduction       ${"%.1f".format(result.reduction * 100)}%",
                style = MaterialTheme.typography.bodySmall,
                color = ProcurementColors.StatusOk
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("Extraction")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("${result.succeeded} success", ProcurementColors.StatusOk)
                if (result.ocrRequired > 0) {
                    Chip("${result.ocrRequired} OCR", ProcurementColors.StatusWarn)
                }
                if (result.unsupported > 0) {
                    Chip("${result.unsupported} unsupported", ProcurementColors.TextTertiary)
                }
                if (result.errors > 0) {
                    Chip("${result.errors} error", ProcurementColors.AccentRedBright)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onShare,
                colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed),
                shape = RoundedCornerShape(2.dp)
            ) { Text("SHARE") }
            TextButton(onClick = onOpen) { Text("OPEN", color = ProcurementColors.TextPrimary) }
            TextButton(onClick = onNew) { Text("DETAILS", color = ProcurementColors.TextSecondary) }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = ProcurementColors.TextPrimary
        )
    }
}
