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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.edib.procurement.aipack.formatBytes
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.aipack.ExtractionStatus
import com.edib.procurement.database.AiPackEntity
import com.edib.procurement.database.AiPackSourceEntity
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.components.formatSize
import com.edib.procurement.ui.theme.ProcurementColors
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * AI PACK HISTORY.
 *
 * Every pack the app generated, with what it cost and what it managed to
 * extract. Deleting removes the generated file and its history rows only; no
 * source document is reachable from here.
 */
@Composable
fun AiPackHistoryScreen(vm: ProcurementViewModel) {
    val packs by vm.packHistory.collectAsStateWithLifecycle()
    val totalBytes by vm.packStorageBytes.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AiPackEntity?>(null) }
    var detailsOf by remember { mutableStateOf<AiPackEntity?>(null) }
    var partsOf by remember { mutableStateOf<AiPackEntity?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("AI Packs", "${packs.size} pack(s) · ${formatSize(totalBytes)} used")

        if (packs.isEmpty()) {
            EmptyState("No AI packs generated yet.\nOpen a Follow-up group and tap AI PACK.")
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 10.dp)
            ) {
                item {
                    Panel {
                        SectionLabel("Generated storage")
                        Text(
                            "${packs.size} packs\n${formatSize(totalBytes)} used",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = ProcurementColors.TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { confirmAll = true },
                            shape = RoundedCornerShape(2.dp)
                        ) { Text("DELETE ALL PACKS", style = MaterialTheme.typography.labelMedium) }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                items(packs, key = { it.id }) { pack ->
                    PackHistoryCard(
                        pack = pack,
                        onOpen = { vm.openPackFile(pack) },
                        onShare = { vm.sharePackFile(pack) },
                        onParts = if (pack.scopeType == "MULTIPART") {
                            { partsOf = pack }
                        } else null,
                        onDetails = { detailsOf = pack },
                        onDelete = { pendingDelete = pack }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    pendingDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = ProcurementColors.Graphite850,
            titleContentColor = ProcurementColors.TextPrimary,
            textContentColor = ProcurementColors.TextSecondary,
            title = { Text("Delete this AI Pack?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Only the generated pack file is removed. No original document " +
                        "is touched: the pack is a separate file the app produced.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePack(pack.id)
                    pendingDelete = null
                }) { Text("Delete", color = ProcurementColors.AccentRedBright) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            containerColor = ProcurementColors.Graphite850,
            titleContentColor = ProcurementColors.TextPrimary,
            textContentColor = ProcurementColors.TextSecondary,
            title = { Text("Delete all AI Packs?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Every generated pack (${formatSize(totalBytes)}) is removed. " +
                        "Original documents in the repository are not affected.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAllPacks()
                    confirmAll = false
                }) { Text("Delete all", color = ProcurementColors.AccentRedBright) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) { Text("Cancel") }
            }
        )
    }

    partsOf?.let { pack ->
        ExportPartsSheet(vm = vm, pack = pack, onDismiss = { partsOf = null })
    }

    detailsOf?.let { pack ->
        PackDetailsSheet(vm = vm, pack = pack, onDismiss = { detailsOf = null })
    }
}

@Composable
private fun PackHistoryCard(
    pack: AiPackEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    /** Multipart exports offer their index and every part individually. */
    onParts: (() -> Unit)? = null,
    onDetails: () -> Unit,
    onDelete: () -> Unit
) {
    val exists = remember(pack.absolutePath) { File(pack.absolutePath).exists() }
    Panel(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    pack.scopeName,
                    style = MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${pack.scopeType.ifBlank { "GROUP" }} · " +
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(pack.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }
            Spacer(Modifier.width(8.dp))
            Chip(
                "${"%.1f".format(pack.reduction * 100)}%",
                ProcurementColors.StatusOk
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "${pack.groupCount} groups · ${pack.entryCount} entries · " +
                "${pack.documentCount} documents",
            style = MaterialTheme.typography.bodySmall,
            color = ProcurementColors.TextSecondary
        )
        Text(
            "${formatSize(pack.originalBytes)} → ${formatSize(pack.packBytes)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = ProcurementColors.TextSecondary
        )

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("${pack.succeeded} ok", ProcurementColors.StatusOk)
            if (pack.ocrRequired > 0) {
                Chip("${pack.ocrRequired} OCR", ProcurementColors.StatusWarn)
            }
            if (pack.unsupported > 0) {
                Chip("${pack.unsupported} unsupported", ProcurementColors.TextTertiary)
            }
            if (pack.errors > 0) {
                Chip("${pack.errors} error", ProcurementColors.AccentRedBright)
            }
            if (!exists) Chip("FILE MISSING", ProcurementColors.AccentRedBright)
        }

        Spacer(Modifier.height(10.dp))
        // Five actions will not fit one line on a narrow phone, and a squeezed
        // label is an unusable control. Two rows keeps every action readable.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onOpen,
                enabled = exists,
                colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.weight(1f)
            ) { Text("OPEN", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onShare, enabled = exists, modifier = Modifier.weight(1f)) {
                Text("SHARE", style = MaterialTheme.typography.labelMedium,
                    color = ProcurementColors.TextPrimary)
            }
            if (onParts != null) {
                TextButton(onClick = onParts, modifier = Modifier.weight(1f)) {
                    Text("PARTS", style = MaterialTheme.typography.labelMedium,
                        color = ProcurementColors.StatusInfo)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onDetails, modifier = Modifier.weight(1f)) {
                Text("DETAILS", style = MaterialTheme.typography.labelMedium,
                    color = ProcurementColors.TextSecondary)
            }
            TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                Text("DELETE EXPORT", style = MaterialTheme.typography.labelMedium,
                    color = ProcurementColors.TextTertiary)
            }
        }
    }
}

/** PARTS: the index and every part of a stored multipart export, each shareable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportPartsSheet(
    vm: ProcurementViewModel,
    pack: AiPackEntity,
    onDismiss: () -> Unit
) {
    val parts = remember(pack.id) { vm.exportParts(pack) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProcurementColors.Graphite850,
        titleContentColor = ProcurementColors.TextPrimary,
        textContentColor = ProcurementColors.TextSecondary,
        title = { Text("Parts (${parts.size})", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                parts.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = ProcurementColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                (if (p.isIndex) "INDEX · " else "") +
                                    formatBytes(p.bytes) +
                                    if (!java.io.File(p.path).exists()) " · FILE MISSING" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = ProcurementColors.TextTertiary
                            )
                        }
                        TextButton(
                            onClick = { vm.sharePartFile(p.path) },
                            enabled = java.io.File(p.path).exists()
                        ) {
                            Text("SHARE", color = ProcurementColors.StatusInfo)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.shareAllParts(pack) },
                    colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed),
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("SHARE ALL (INDEX + ${parts.size - 1} PARTS)") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** PACK DETAILS: what went in, what came out, and how each source fared. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackDetailsSheet(vm: ProcurementViewModel, pack: AiPackEntity, onDismiss: () -> Unit) {
    val sources by vm.packSources(pack.id).collectAsStateWithLifecycle(emptyList())
    val exists = remember(pack.absolutePath) { File(pack.absolutePath).exists() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProcurementColors.Graphite850,
        titleContentColor = ProcurementColors.TextPrimary,
        textContentColor = ProcurementColors.TextSecondary,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Pack details", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!exists) {
                    Text(
                        "FILE MISSING",
                        style = MaterialTheme.typography.titleSmall,
                        color = ProcurementColors.AccentRedBright
                    )
                    Text(
                        "The generated file is no longer on disk. The history record is " +
                            "kept so the pack is not silently forgotten.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                }
                KeyValue("Pack filename", pack.fileName)
                KeyValue("Location", File(pack.absolutePath).parent ?: pack.absolutePath)
                KeyValue(
                    "Generated",
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(pack.createdAt))
                )
                KeyValue("Scope", "${pack.scopeType.ifBlank { "GROUP" }} · ${pack.scopeName}")
                KeyValue("Groups / Entries", "${pack.groupCount} / ${pack.entryCount}")
                KeyValue("Documents", pack.documentCount.toString())
                KeyValue("Original size", formatSize(pack.originalBytes))
                KeyValue("Normalized size", formatSize(pack.packBytes))
                KeyValue("Reduction", "${"%.1f".format(pack.reduction * 100)}%")
                KeyValue("Format version", pack.formatVersion.toString())

                Spacer(Modifier.height(10.dp))
                SectionLabel("Extraction")
                ExtractionCounts(pack)

                if (sources.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("Sources (${sources.size})")
                    sources.forEach { PackSourceRow(it) }
                }
            }
        }
    )
}

@Composable
private fun ExtractionCounts(pack: AiPackEntity) {
    val total = pack.succeeded + pack.ocrRequired + pack.unsupported + pack.errors + pack.skipped
    Text(
        buildString {
            append("SUCCESS      ${pack.succeeded}\n")
            append("OCR_REQUIRED ${pack.ocrRequired}\n")
            append("UNSUPPORTED  ${pack.unsupported}\n")
            append("ERROR        ${pack.errors}\n")
            append("SKIPPED      ${pack.skipped}\n")
            append("TOTAL        $total")
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = ProcurementColors.TextSecondary
    )
}

@Composable
private fun PackSourceRow(source: AiPackSourceEntity) {
    val status = runCatching { ExtractionStatus.valueOf(source.extractionStatus) }
        .getOrDefault(ExtractionStatus.ERROR)
    val tint = when (status) {
        ExtractionStatus.SUCCESS -> ProcurementColors.StatusOk
        ExtractionStatus.PARTIAL -> ProcurementColors.StatusInfo
        ExtractionStatus.OCR_REQUIRED -> ProcurementColors.StatusWarn
        ExtractionStatus.UNSUPPORTED, ExtractionStatus.SKIPPED -> ProcurementColors.TextTertiary
        ExtractionStatus.ERROR -> ProcurementColors.AccentRedBright
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                source.sourceId,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = ProcurementColors.TextTertiary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                source.filename,
                style = MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                status.name,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
        }
        if (source.linkedManually) {
            Text(
                "selected manually (unlinked)",
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary
            )
        }
        if (!source.detail.isNullOrBlank()) {
            Text(
                source.detail,
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider(color = ProcurementColors.Graphite700, thickness = 1.dp)
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = ProcurementColors.TextTertiary,
            modifier = Modifier.width(130.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = ProcurementColors.TextPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}
