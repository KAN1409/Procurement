package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.database.RateCandidateRow
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.theme.ProcurementColors
import java.text.DecimalFormat

@Composable
fun RateCandidatesScreen(
    vm: ProcurementViewModel,
    onBack: () -> Unit,
    onReviewCandidate: () -> Unit
) {
    val query by vm.rateCandidateQuery.collectAsStateWithLifecycle()
    val candidates by vm.rateCandidates.collectAsStateWithLifecycle()
    val pendingCount by vm.rateCandidateCount.collectAsStateWithLifecycle()
    val scan by vm.rateCandidateScan.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("Rate Candidates", "$pendingCount pending review")
        Column(Modifier.padding(horizontal = 14.dp)) {
            Panel {
                Text(
                    "Candidates are extracted hints, not trusted rates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ProcurementColors.TextPrimary
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Nothing enters commercial memory until you review it and save it as an observation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }
            Spacer(Modifier.height(8.dp))
            SearchField(
                value = query,
                onValueChange = vm::setRateCandidateQuery,
                placeholder = "Search candidate item, evidence or source…",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(0.8f)
                ) {
                    Text("BACK")
                }
                Button(
                    onClick = vm::scanRateCandidates,
                    enabled = !scan.running,
                    modifier = Modifier.weight(1.6f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProcurementColors.AccentRed
                    )
                ) {
                    Text(if (scan.running) "SCANNING…" else "SCAN INDEXED FILES")
                }
            }
            if (scan.running || scan.documentsScanned > 0 || scan.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        scan.error != null -> scan.error!!
                        scan.running ->
                            "${scan.documentsScanned} files · ${scan.candidatesInserted} new · ${scan.currentFile.orEmpty()}"
                        else ->
                            "${scan.documentsScanned} files scanned · ${scan.candidatesInserted} new candidates"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (scan.error != null) {
                        ProcurementColors.AccentRedBright
                    } else {
                        ProcurementColors.TextTertiary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (candidates.isEmpty()) {
            EmptyState(
                if (scan.documentsScanned == 0) {
                    "Scan indexed files to build a review queue from existing work documents."
                } else {
                    "No pending candidates match this search."
                }
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 14.dp,
                    vertical = 4.dp
                )
            ) {
                items(candidates, key = { it.id }) { candidate ->
                    CandidateRow(
                        vm = vm,
                        candidate = candidate,
                        onReview = {
                            vm.selectRateCandidate(candidate)
                            onReviewCandidate()
                        },
                        onDismiss = { vm.dismissRateCandidate(candidate.id) }
                    )
                    HorizontalDivider(
                        color = ProcurementColors.Graphite700,
                        thickness = 1.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    vm: ProcurementViewModel,
    candidate: RateCandidateRow,
    onReview: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    candidate.itemRaw,
                    style = MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("${candidate.confidence}% extraction")
                    candidate.inferredScope?.let { Chip(it) }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    buildString {
                        append(MONEY.format(candidate.rate))
                        candidate.currency?.let { append(" ").append(it) }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.AccentRedBright,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    candidate.unitRaw?.let { "/ $it" } ?: "unit needs review",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }
        }

        Spacer(Modifier.height(7.dp))
        Text(
            candidate.evidenceText,
            style = MaterialTheme.typography.bodySmall,
            color = ProcurementColors.TextSecondary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${candidate.sourceFileName} · ${candidate.evidenceLocator}",
            style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (candidate.sourceUri.isNotBlank()) {
                TextButton(
                    onClick = {
                        vm.openDocument(
                            candidate.sourceUri,
                            candidate.sourceExtension,
                            candidate.sourceMimeType
                        )
                    }
                ) {
                    Text("OPEN SOURCE", color = ProcurementColors.TextSecondary)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("DISMISS", color = ProcurementColors.TextTertiary)
            }
            Button(
                onClick = onReview,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ProcurementColors.AccentRed
                )
            ) {
                Text("REVIEW")
            }
        }
    }
}

private val MONEY = DecimalFormat("#,##0.##")
