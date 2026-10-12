package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.database.RateCandidateRow
import com.edib.procurement.rates.CandidateClustering
import com.edib.procurement.rates.RateCandidateParser
import com.edib.procurement.rates.PoMetadata
import java.time.format.DateTimeFormatter
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.theme.ProcurementColors
import kotlinx.coroutines.launch
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
    val scope by vm.candidateScope.collectAsStateWithLifecycle()
    val confidence by vm.candidateMinConfidence.collectAsStateWithLifecycle()
    val sort by vm.candidateSort.collectAsStateWithLifecycle()
    val poMetadata by vm.candidatePoMetadata.collectAsStateWithLifecycle()
    val datedCandidates = remember(candidates, sort, poMetadata) {
        if (sort == "PO_DATE_DESC" || sort == "PO_DATE_ASC") {
            val order = compareBy<RateCandidateRow> { row ->
                row.sourceDocumentId?.let(poMetadata::get)?.date == null
            }.thenBy { row ->
                val day = row.sourceDocumentId?.let(poMetadata::get)?.date?.toEpochDay() ?: 0L
                if (sort == "PO_DATE_DESC") -day else day
            }.thenBy { it.id }
            candidates.sortedWith(order)
        } else candidates
    }
    val clusters = remember(datedCandidates, sort) { CandidateClustering.group(datedCandidates, sort) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val candidateListState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()

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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { vm.setCandidateScope(if (scope == "SUPPLY") "" else "SUPPLY") }) {
                    Text(if (scope == "SUPPLY") "✓ SUPPLY" else "SUPPLY")
                }
                TextButton(onClick = { vm.setCandidateScope(if (scope == "INSTALL") "" else "INSTALL") }) {
                    Text(if (scope == "INSTALL") "✓ INSTALL" else "INSTALL")
                }
                TextButton(onClick = { vm.setCandidateScope(if (scope == "SUPPLY_INSTALL") "" else "SUPPLY_INSTALL") }) {
                    Text(if (scope == "SUPPLY_INSTALL") "✓ BOTH" else "BOTH")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { vm.setCandidateMinConfidence(if (confidence == 0) 80 else 0) }) {
                    Text(if (confidence == 0) "ALL CONFIDENCE" else "≥80% ONLY")
                }
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Text("SORT: " + when (sort) {
                            "RATE_ASC" -> "RATE ↑"
                            "RATE_DESC" -> "RATE ↓"
                            "SOURCE" -> "SOURCE"
                            "PO_DATE_DESC" -> "NEWEST PO"
                            "PO_DATE_ASC" -> "OLDEST PO"
                            else -> "CONFIDENCE"
                        })
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        listOf(
                            "CONFIDENCE" to "Confidence",
                            "RATE_ASC" to "Rate: low to high",
                            "RATE_DESC" to "Rate: high to low",
                            "SOURCE" to "Source document",
                            "PO_DATE_DESC" to "PO date: newest first",
                            "PO_DATE_ASC" to "PO date: oldest first"
                        ).forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                vm.setCandidateSort(value)
                                sortMenuOpen = false
                            })
                        }
                    }
                }
            }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${candidates.size} matching candidates · ${clusters.size} groups",
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextSecondary)
            TextButton(onClick = {
                vm.setRateCandidateQuery("")
                vm.setCandidateScope("")
                vm.setCandidateMinConfidence(0)
                vm.setCandidateSort("CONFIDENCE")
                expanded.clear()
            }) { Text("CLEAR ALL") }
        }

        if (candidates.isEmpty()) {
            EmptyState(
                if (pendingCount == 0 && query.isBlank() && scope.isBlank() && confidence == 0) {
                    "No pending candidates. Scan indexed files to build a review queue."
                } else {
                    "No pending candidates match the current search or filters."
                }
            )
        } else {
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = candidateListState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 4.dp,
                    bottom = 104.dp
                )
            ) {
                clusters.forEach { cluster ->
                    val groupId = cluster.key.toString() + if (sort.startsWith("PO_DATE_")) ":source:" + cluster.candidates.first().sourceKey else ""
                    item(key = "cluster:" + groupId) {
                        val rows = cluster.candidates
                        val distinctFiles = rows.map {
                            it.sourceDocumentId?.toString()
                                ?: it.sourceUri.ifBlank { it.sourceRelativePath }
                        }.distinct().size
                        val evidence = remember(rows) { CandidateClustering.evidenceSummary(rows) }
                        val low = rows.minOf { it.rate }
                        val high = rows.maxOf { it.rate }
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(
                                rows.first().itemRaw,
                                style = MaterialTheme.typography.titleSmall,
                                color = ProcurementColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            if (sort == "PO_DATE_DESC" || sort == "PO_DATE_ASC") {
                                val documentIds = rows.mapNotNull { it.sourceDocumentId }.distinct()
                                val datedSources = documentIds.mapNotNull { poMetadata[it]?.date }.distinct()
                                Text(
                                    "PO date: " + if (datedSources.size == 1) datedSources.single().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                    else if (datedSources.isEmpty()) "Unknown" else "Multiple sources — see individual records",
                                    color = ProcurementColors.TextPrimary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                "${rows.size} extractions · ${distinctFiles} files · ${CandidateClustering.rateVariantCount(rows)} rate values · ${cluster.key.unit} · ${cluster.key.scope}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ProcurementColors.TextSecondary
                            )
                            Text(
                                (if (cluster.candidates.any { RateCandidateParser.suspiciousUnit(it.unitRaw) || it.unitRaw.isNullOrBlank() })
                                    "Unverified extracted values (unit needs review): ${MONEY.format(low)}–${MONEY.format(high)}"
                                 else "Unverified range: ${MONEY.format(low)}–${MONEY.format(high)} ${cluster.key.currency} / ${cluster.key.unit}"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ProcurementColors.AccentRedBright
                            )
                            Text(
                                "Repeated values may be copied across files; not independent bids, approved prices, or necessarily comparable.",
                                style = MaterialTheme.typography.labelSmall,
                                color = ProcurementColors.TextTertiary
                            )
                            Text(
                                "${evidence.repeatedEvidenceGroups} repeated evidence groups · " +
                                    "${evidence.matchingValueGroups} matching value groups" +
                                    (if (evidence.unknownSources > 0) " · ${evidence.unknownSources} unknown sources" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = ProcurementColors.TextSecondary
                            )
                            Text(
                                "Evidence relationships only; no candidates merged or independent offers inferred.",
                                style = MaterialTheme.typography.labelSmall,
                                color = ProcurementColors.TextTertiary
                            )
                            if (query.isBlank() && sort == "CONFIDENCE") TextButton(onClick = { expanded[groupId] = expanded[groupId] != true }) {
                                Text(if (expanded[groupId] == true) "HIDE SOURCES" else "SHOW SOURCES")
                            }
                        }
                        HorizontalDivider(color = ProcurementColors.Graphite700)
                    }
                    if (CandidateClustering.showSources(query, expanded[groupId] == true, sort)) {
                        items(cluster.candidates, key = { it.id }) { candidate ->
                            CandidateRow(
                                vm = vm,
                                candidate = candidate,
                                poMetadata = candidate.sourceDocumentId?.let(poMetadata::get),
                                onReview = {
                                    vm.selectRateCandidate(candidate)
                                    onReviewCandidate()
                                },
                                onDismiss = { vm.dismissRateCandidate(candidate.id) }
                            )
                            HorizontalDivider(color = ProcurementColors.Graphite700)
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End
            ) {
                OutlinedButton(onClick = {
                    scrollScope.launch { candidateListState.animateScrollToItem(0) }
                }) { Text("↑ TOP") }
                OutlinedButton(onClick = {
                    scrollScope.launch {
                        val lastIndex = candidateListState.layoutInfo.totalItemsCount - 1
                        if (lastIndex >= 0) candidateListState.scrollToItem(lastIndex)
                    }
                }) { Text("↓ BOTTOM") }
            }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    vm: ProcurementViewModel,
    candidate: RateCandidateRow,
    poMetadata: PoMetadata?,
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

        if (RateCandidateParser.suspiciousUnit(candidate.unitRaw)) {
            Text(
                "SUSPECT UNIT — PO/reference text may have been parsed as a unit. Verify the original document before using this rate.",
                color = ProcurementColors.AccentRedBright,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(5.dp))
        Text(
            "Quantity: ${candidate.quantity?.let { MONEY.format(it) } ?: "needs review"} · " +
                "Unit: ${candidate.unitRaw ?: "needs review"} · " +
                "Scope: ${candidate.inferredScope ?: "needs review"}",
            style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
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
            "PO Date: " + (poMetadata?.date?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: "Unknown") +
                " · PO Number: " + (poMetadata?.number ?: "Unknown"),
            style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextPrimary
        )
        Text(
            poMetadata?.evidence?.let { "Date source: document heading · $it" }
                ?: "PO date not verified from document heading",
            style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextTertiary
        )
        Text(
            "${candidate.sourceFileName} · ${candidate.evidenceLocator} · #${candidate.id}",
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
