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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edib.procurement.aipack.MultipartResult
import com.edib.procurement.aipack.PackSplitter
import com.edib.procurement.aipack.formatBytes
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.theme.ProcurementColors

/**
 * The result screen for a multipart export.
 *
 * It leads with the index, because the index is the thing to upload first, then
 * lists every part with its own size and its own share action. Size accounting
 * is shown per part rather than only in total, since "why is part 3 small" is a
 * question the reader will ask.
 */
@Composable
fun MultipartResultView(
    result: MultipartResult,
    onShareIndex: () -> Unit,
    onSharePart: (String) -> Unit,
    onShareAll: () -> Unit,
    onOpen: (String) -> Unit,
    onDone: () -> Unit
) {
    val width = PackSplitter.numberWidth(result.partFileNames.size)

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
            .padding(14.dp)
    ) {
        Panel {
            SectionLabel("AI PACK CREATED (MULTIPART)")
            Text(
                "${result.partFileNames.size} part(s) · ${formatBytes(result.totalPackBytes)}",
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = ProcurementColors.TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Export ID: ${result.exportId}",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary
            )
            Spacer(Modifier.height(8.dp))
            if (!result.integrity.ok) {
                Text(
                    "INTEGRITY CHECK FAILED — this export is not valid.",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = ProcurementColors.AccentRedBright
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Verified: ${result.integrity.actualSources} of " +
                            "${result.integrity.expectedSources} sources present",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = ProcurementColors.StatusInfo
                    )
                }
            }
            if (result.oversizedParts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "WARNING: ${result.oversizedParts.size} part(s) exceed the target because a " +
                        "single document is larger than the target part size. Those documents " +
                        "were placed whole, not truncated.",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.StatusWarn
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- size accounting ----
        Panel {
            SectionLabel("Size accounting")
            Text(
                "Original source size    ${formatBytes(result.originalTotalBytes)}\n" +
                    "Normalized (raw text)   ${formatBytes(result.normalizedTotalBytes)}\n" +
                    "Final package           ${formatBytes(result.totalPackBytes)}\n" +
                    "Parts                   ${result.partFileNames.size}\n" +
                    "Target per part         ${formatBytes(result.targetPartBytes)}\n" +
                    "Reduction vs original   ${"%.1f".format(result.reduction * 100)}%",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            result.partFileNames.forEachIndexed { i, _ ->
                Text(
                    "${PackSplitter.partLabel(i + 1, width)}   " +
                        formatBytes(result.partBytes[i]),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = ProcurementColors.TextSecondary
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- index ----
        Panel {
            SectionLabel("Index")
            Text(
                result.indexFileName,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = ProcurementColors.TextPrimary
            )
            Text(
                "${formatBytes(result.indexBytes)} · lists every part and maps every SOURCE id",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onOpen(result.indexPath) },
                    shape = RoundedCornerShape(2.dp)
                ) { Text("OPEN INDEX") }
                OutlinedButton(
                    onClick = onShareIndex,
                    shape = RoundedCornerShape(2.dp)
                ) { Text("SHARE INDEX") }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onShareAll,
                colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("SHARE ALL (INDEX + ${result.partFileNames.size} PARTS)") }
        }

        Spacer(Modifier.height(10.dp))
        SectionLabel("Parts")

        LazyColumn(Modifier.weight(1f, fill = false)) {
            itemsIndexed(result.partFileNames) { i, name ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(ProcurementColors.Graphite850, RoundedCornerShape(3.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        "${PackSplitter.partLabel(i + 1, width)} · $name",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = ProcurementColors.TextPrimary
                    )
                    Text(
                        formatBytes(result.partBytes[i]),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = ProcurementColors.TextTertiary
                    )
                    result.oversizedParts[i + 1]?.let {
                        Text(
                            "OVERSIZED: $it",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = ProcurementColors.StatusWarn
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onOpen(result.partPaths[i]) }) { Text("OPEN") }
                        TextButton(onClick = { onSharePart(result.partPaths[i]) }) {
                            Text("SHARE", color = ProcurementColors.StatusInfo)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("DONE", color = ProcurementColors.TextSecondary)
            }
        }
    }
}
