package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edib.procurement.database.RateDraft
import com.edib.procurement.database.RateListRow
import com.edib.procurement.database.RateScope
import com.edib.procurement.database.RateSourceChoice
import com.edib.procurement.database.RateStatus
import com.edib.procurement.rates.RateCheckRequest
import com.edib.procurement.rates.RateCheckResult
import com.edib.procurement.rates.RateCheckVerdict
import com.edib.procurement.rates.parseCommercialNumber
import com.edib.procurement.rates.rankRelatedRates
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.components.Panel
import com.edib.procurement.ui.components.SectionLabel
import com.edib.procurement.ui.theme.ProcurementColors
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun RatesScreen(
    vm: ProcurementViewModel,
    onAddRate: () -> Unit,
    onFindRate: () -> Unit,
    onCheckRate: () -> Unit,
    onCandidates: () -> Unit
) {
    val query by vm.rateQuery.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val count by vm.rateCount.collectAsStateWithLifecycle()
    val candidateCount by vm.rateCandidateCount.collectAsStateWithLifecycle()
    val evidenceCandidates by vm.rateEvidenceCandidates.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("Rates", "${count} commercial observations")
        SearchField(
            value = query,
            onValueChange = vm::setRateQuery,
            placeholder = "Search ledger by item, contractor, project, scope…",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        )
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OutlinedButton(
                onClick = onFindRate,
                modifier = Modifier.weight(1f)
            ) {
                Text("FIND RATES")
            }
            OutlinedButton(
                onClick = onCheckRate,
                modifier = Modifier.weight(1f)
            ) {
                Text("COMPARE")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OutlinedButton(
                onClick = onCandidates,
                modifier = Modifier.weight(1.4f)
            ) {
                Text("CANDIDATES $candidateCount")
            }
            Button(
                onClick = onAddRate,
                modifier = Modifier.weight(0.8f),
                colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed)
            ) {
                Text("ADD RATE")
            }
        }
        Spacer(Modifier.height(8.dp))

        // Keep ledger and unreviewed evidence distinct, even when ledger is empty.
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp, vertical = 4.dp
            )
        ) {
            item {
                SectionLabel("COMMERCIAL LEDGER · ${rates.size} results")
                if (rates.isEmpty()) {
                    Text(
                        if (query.isBlank()) "No saved commercial observations yet."
                        else "No saved commercial observations match this search.",
                        color = ProcurementColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
            items(rates, key = { "rate:${it.id}" }) { rate ->
                RateRow(vm, rate)
                HorizontalDivider(color = ProcurementColors.Graphite700, thickness = 1.dp)
            }
            item {
                Spacer(Modifier.height(14.dp))
                SectionLabel("UNREVIEWED EXTRACTED EVIDENCE · ${evidenceCandidates.size} results")
                Text(
                    "Searchable hints only. Not approved prices. Contractor and PO date are unverified; open the original source before any decision.",
                    color = ProcurementColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            items(evidenceCandidates.take(12), key = { "candidate:${it.id}" }) { candidate ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(
                        candidate.itemRaw,
                        style = MaterialTheme.typography.titleSmall,
                        color = ProcurementColors.TextPrimary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "${MONEY.format(candidate.rate)} ${candidate.currency ?: "currency unknown"} / ${candidate.unitRaw ?: "UNIT UNKNOWN"}",
                        color = ProcurementColors.AccentRedBright,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (candidate.unitRaw.isNullOrBlank()) {
                        Text(
                            "Unit must be verified against the original document.",
                            color = ProcurementColors.StatusWarn,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        "UNREVIEWED EXTRACTION · ${candidate.confidence}% parser confidence",
                        color = ProcurementColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        candidate.evidenceText,
                        color = ProcurementColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "SOURCE · ${candidate.sourceFileName} · ${candidate.evidenceLocator}",
                        color = ProcurementColors.TextTertiary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (candidate.sourceUri.isNotBlank()) {
                            TextButton(onClick = {
                                vm.openDocument(
                                    candidate.sourceUri,
                                    candidate.sourceExtension,
                                    candidate.sourceMimeType
                                )
                            }) { Text("OPEN SOURCE") }
                        } else {
                            Text(
                                "Source unavailable",
                                color = ProcurementColors.StatusWarn,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = onCandidates) { Text("REVIEW IN CANDIDATES") }
                    }
                }
                HorizontalDivider(color = ProcurementColors.Graphite700, thickness = 1.dp)
            }
            if (evidenceCandidates.size > 12) {
                item {
                    TextButton(onClick = onCandidates) {
                        Text("VIEW ALL ${evidenceCandidates.size} MATCHING CANDIDATES")
                    }
                }
            }
            if (evidenceCandidates.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) "No pending extracted evidence available."
                        else "No pending extracted evidence matches this search.",
                        color = ProcurementColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RateRow(
    vm: ProcurementViewModel,
    rate: RateListRow,
    targetRate: Double? = null
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
                    rate.itemRaw,
                    style = MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = buildList {
                    rate.dimensions?.takeIf { it.isNotBlank() }?.let(::add)
                    rate.material?.takeIf { it.isNotBlank() }?.let(::add)
                    rate.specification?.takeIf { it.isNotBlank() }?.let(::add)
                }.joinToString(" · ")
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = ProcurementColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${MONEY.format(rate.rate)} ${rate.currency}",
                    style = MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.AccentRedBright,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "/ ${rate.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
                if (targetRate != null) {
                    val delta = rate.rate - targetRate
                    Text(
                        when {
                            abs(delta) < 0.000001 -> "exact target"
                            delta > 0 -> "+${MONEY.format(delta)} from target"
                            else -> "${MONEY.format(delta)} from target"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = ProcurementColors.TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Chip(
                rate.status,
                when (rate.status) {
                    RateStatus.AWARDED -> ProcurementColors.StatusOk
                    RateStatus.APPROVED -> ProcurementColors.StatusInfo
                    RateStatus.QUOTED -> ProcurementColors.StatusWarn
                    else -> ProcurementColors.TextSecondary
                }
            )
            Chip(scopeLabel(rate.scope))
            rate.contractorName?.takeIf { it.isNotBlank() }?.let { Chip(it) }
            rate.projectName?.takeIf { it.isNotBlank() }?.let { Chip(it) }
        }

        val contextLine = buildList {
            rate.location?.takeIf { it.isNotBlank() }?.let(::add)
            rate.quantity?.let { add("Qty ${MONEY.format(it)}") }
            add(formatRateDate(rate.observedDate))
        }.joinToString(" · ")
        Spacer(Modifier.height(5.dp))
        Text(
            contextLine,
            style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextTertiary
        )

        if (!rate.sourceFileName.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        rate.sourceFileName,
                        style = MaterialTheme.typography.labelMedium,
                        color = ProcurementColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    rate.evidenceLocator?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = ProcurementColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!rate.sourceUri.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            vm.openDocument(
                                rate.sourceUri,
                                rate.sourceExtension,
                                rate.sourceMimeType
                            )
                        }
                    ) {
                        Text(
                            "OPEN SOURCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProcurementColors.AccentRedBright
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(5.dp))
            Text(
                "OBSERVED · NO SOURCE",
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.StatusWarn
            )
        }
    }
}

@Composable
fun FindRatesScreen(
    vm: ProcurementViewModel,
    onBack: () -> Unit,
    onCompare: () -> Unit
) {
    val query by vm.rateFindQuery.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val selectedEvidence = remember { mutableStateMapOf<Long, Boolean>() }
    val allRows by vm.rateFindResults.collectAsStateWithLifecycle()

    var targetText by rememberSaveable { mutableStateOf("") }
    var targetCurrency by rememberSaveable { mutableStateOf("EGP") }
    var selectedStatus by rememberSaveable { mutableStateOf("ALL") }
    var selectedScope by rememberSaveable { mutableStateOf("ANY") }

    val target = targetText.trim().takeIf { it.isNotEmpty() }?.let(::parseCommercialNumber)
    val targetInvalid = targetText.isNotBlank() && target == null
    val currencyNormalized = targetCurrency.trim().uppercase()

    val results = remember(
        allRows,
        target,
        targetInvalid,
        currencyNormalized,
        selectedStatus,
        selectedScope
    ) {
        rankRelatedRates(
            rows = allRows,
            targetRate = target?.takeIf { !targetInvalid },
            targetCurrency = currencyNormalized,
            status = selectedStatus.takeUnless { it == "ALL" },
            scope = selectedScope.takeUnless { it == "ANY" }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("BOQ Price Research", "new BOQ item → historical evidence → reviewed Trafco rate")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            Panel {
                Text(
                    "1 · Paste a new BOQ item below. 2 · Inspect historical rates and open the original PO. 3 · Verify specification, unit, scope, date and contractor. 4 · Compare a proposed Trafco rate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ProcurementColors.TextPrimary
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Search results are leads, not approved Trafco prices. OBSERVED is not AWARDED; never use a different unit, scope or currency as a direct comparison. Unknown source details must be checked in the original document.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Optional target price ranks related observations by proximity only. It does not validate equivalence or convert units.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }
            Spacer(Modifier.height(9.dp))
            SearchField(
                value = query,
                onValueChange = vm::setRateFindQuery,
                placeholder = "Paste BOQ item / search material, project or contractor…",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RateField(
                    label = "Around rate (optional)",
                    value = targetText,
                    onValueChange = { targetText = it },
                    modifier = Modifier.weight(1.4f)
                )
                RateField(
                    label = "Currency",
                    value = targetCurrency,
                    onValueChange = { targetCurrency = it },
                    modifier = Modifier.weight(0.8f)
                )
            }
            if (targetInvalid) {
                Text(
                    "Enter a valid number, or leave target rate blank.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.AccentRedBright
                )
            } else if (target != null) {
                Text(
                    "Sorted by distance from ${MONEY.format(target)} ${currencyNormalized.ifBlank { "EGP" }}. Unit is not used as a filter.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel("Status")
            ChoiceRow(
                choices = listOf("ALL") + RateStatus.all,
                selected = selectedStatus,
                label = { it },
                onSelect = { selectedStatus = it }
            )
            Spacer(Modifier.height(8.dp))
            SectionLabel("Scope · optional")
            ChoiceRow(
                choices = listOf("ANY") + RateScope.all,
                selected = selectedScope,
                label = { if (it == "ANY") "ANY" else scopeLabel(it) },
                onSelect = { selectedScope = it }
            )
            Spacer(Modifier.height(5.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${results.size} result(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = ProcurementColors.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onBack) {
                        Text("BACK", color = ProcurementColors.TextSecondary)
                    }
                    TextButton(
                        onClick = {
                            if (query.isNotBlank()) vm.setRateCheckChoiceQuery(query)
                            onCompare()
                        }
                    ) {
                        Text("CHECK TRAFCO RATE", color = ProcurementColors.AccentRedBright)
                    }
                }
            }
        }

        if (selectedEvidence.values.any { it }) {
            val selectedRows = allRows.filter { selectedEvidence[it.id] == true }
            Panel {
                Text("BOQ RESEARCH SHORTLIST · ${selectedRows.size} historical records",
                    style = MaterialTheme.typography.titleSmall, color = ProcurementColors.TextPrimary)
                Text("Evidence leads only. Not a Trafco approved rate or a verified comparison.",
                    style = MaterialTheme.typography.labelSmall, color = ProcurementColors.TextSecondary)
                TextButton(onClick = {
                    val note = buildString {
                        appendLine("BOQ PRICE RESEARCH · DRAFT / NOT APPROVED")
                        appendLine("BOQ search: ${query.ifBlank { "Not entered" }}")
                        appendLine("Check item, material, specification, dimensions, unit, scope, currency and source before pricing.")
                        selectedRows.forEach { row ->
                            appendLine()
                            appendLine("Historical record #${row.id}: ${row.itemRaw}")
                            appendLine("Rate: ${row.rate} ${row.currency} / ${row.unit} · ${row.scope} · ${row.status}")
                            appendLine("Material: ${row.material ?: "Unknown"} · Spec: ${row.specification ?: "Unknown"} · Dimensions: ${row.dimensions ?: "Unknown"}")
                            appendLine("Contractor: ${row.contractorName ?: "Unknown"} · Project: ${row.projectName ?: "Unknown"}")
                            appendLine("Recorded date: ${formatRateDate(row.observedDate)}")
                            appendLine("Source: ${row.sourceFileName ?: "NOT LINKED"} · ${row.evidenceLocator ?: "Locator unknown"}")
                            appendLine("Source URI: ${row.sourceUri ?: "Unknown"}")
                        }
                        appendLine()
                        appendLine("Trafco proposed rate: NOT SET")
                        appendLine("Selection rationale: NOT VERIFIED")
                        appendLine("Negotiation points: NOT RECORDED")
                    }
                    clipboard.setText(AnnotatedString(note))
                }) { Text("COPY AUDITABLE RESEARCH NOTE") }
                TextButton(onClick = { selectedEvidence.clear() }) { Text("CLEAR SHORTLIST") }
            }
        }
        if (results.isEmpty()) {
            EmptyState(
                when {
                    query.isBlank() && target == null ->
                        "Search for an item, material, contractor or project. You can also enter only a target price."
                    else -> "No related commercial rates found."
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
                items(results, key = { it.id }) { rate ->
                    RateRow(
                        vm = vm,
                        rate = rate,
                        targetRate = target?.takeIf { !targetInvalid }
                    )
                    TextButton(onClick = { selectedEvidence[rate.id] = selectedEvidence[rate.id] != true }) {
                        Text(if (selectedEvidence[rate.id] == true) "REMOVE FROM BOQ RESEARCH" else "ADD TO BOQ RESEARCH")
                    }
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
fun RateCheckScreen(
    vm: ProcurementViewModel,
    onBack: () -> Unit,
    onFindRates: () -> Unit
) {
    val contractors by vm.contractors.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val result by vm.rateCheckResult.collectAsStateWithLifecycle()
    val running by vm.rateCheckRunning.collectAsStateWithLifecycle()
    val rateChoices by vm.rateCheckChoices.collectAsStateWithLifecycle()

    var item by rememberSaveable { mutableStateOf("") }
    var material by rememberSaveable { mutableStateOf("") }
    var specification by rememberSaveable { mutableStateOf("") }
    var dimensions by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(RateScope.SUPPLY_INSTALL) }
    var contractorText by rememberSaveable { mutableStateOf("") }
    var contractorId by rememberSaveable { mutableStateOf<Long?>(null) }
    var projectText by rememberSaveable { mutableStateOf("") }
    var projectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var location by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var proposedRate by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("EGP") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }

    val contractorSuggestions = contractors
        .filter {
            contractorText.isNotBlank() &&
                it.canonicalName.contains(contractorText, ignoreCase = true)
        }
        .take(5)

    val projectSuggestions = projects
        .filter {
            projectText.isNotBlank() &&
                it.canonicalName.contains(projectText, ignoreCase = true)
        }
        .take(5)

    fun invalidate() {
        localError = null
        vm.clearRateCheck()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("Compare Price", "proposed rate vs directly comparable awarded history")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Panel {
                Text(
                    "No averages. No dimensional scaling. No location coefficient.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProcurementColors.TextSecondary
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Only exact item + material + specification + dimensions + controlled unit equivalence + scope + currency can become a comparison basis.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel("Proposed item")
            RateField("Item description *", item, onValueChange = {
                item = it
                selectedTemplateId = null
                vm.setRateCheckChoiceQuery(it)
                invalidate()
            })
            if (item.isNotBlank() && selectedTemplateId == null && rateChoices.isNotEmpty()) {
                Text(
                    "Use an existing awarded item to avoid wording/unit mismatches:",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                SuggestionPanel {
                    rateChoices.take(6).forEach { choice ->
                        SuggestionLine(
                            title = choice.itemRaw,
                            subtitle = buildList {
                                choice.material?.takeIf { it.isNotBlank() }?.let(::add)
                                choice.dimensions?.takeIf { it.isNotBlank() }?.let(::add)
                                add(choice.unit)
                                add(scopeLabel(choice.scope))
                                add("${MONEY.format(choice.rate)} ${choice.currency} awarded")
                            }.joinToString(" · ")
                        ) {
                            item = choice.itemRaw
                            material = choice.material.orEmpty()
                            specification = choice.specification.orEmpty()
                            dimensions = choice.dimensions.orEmpty()
                            unit = choice.unit
                            scope = choice.scope
                            currency = choice.currency
                            selectedTemplateId = choice.id
                            vm.clearRateCheckChoiceQuery()
                            invalidate()
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RateField(
                    label = "Material",
                    value = material,
                    onValueChange = {
                        material = it
                        invalidate()
                    },
                    modifier = Modifier.weight(1f)
                )
                RateField(
                    label = "Dimensions",
                    value = dimensions,
                    onValueChange = {
                        dimensions = it
                        invalidate()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            RateField("Specification", specification, onValueChange = {
                specification = it
                invalidate()
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RateField(
                    label = "Unit *",
                    value = unit,
                    onValueChange = {
                        unit = it
                        invalidate()
                    },
                    modifier = Modifier.weight(1f)
                )
                RateField(
                    label = "Proposed rate *",
                    value = proposedRate,
                    onValueChange = {
                        proposedRate = it
                        invalidate()
                    },
                    modifier = Modifier.weight(1f)
                )
                RateField(
                    label = "Currency",
                    value = currency,
                    onValueChange = {
                        currency = it
                        invalidate()
                    },
                    modifier = Modifier.weight(0.8f)
                )
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel("Scope")
            ChoiceRow(
                choices = RateScope.all,
                selected = scope,
                label = ::scopeLabel,
                onSelect = {
                    scope = it
                    invalidate()
                }
            )

            Spacer(Modifier.height(12.dp))
            SectionLabel("Relevance context")
            RateField(
                "Contractor",
                contractorText,
                onValueChange = {
                    contractorText = it
                    contractorId = null
                    invalidate()
                }
            )
            if (contractorSuggestions.isNotEmpty() && contractorId == null) {
                SuggestionPanel {
                    contractorSuggestions.forEach { c ->
                        SuggestionLine(c.canonicalName) {
                            contractorText = c.canonicalName
                            contractorId = c.id
                            invalidate()
                        }
                    }
                }
            }

            RateField(
                "Project",
                projectText,
                onValueChange = {
                    projectText = it
                    projectId = null
                    invalidate()
                }
            )
            if (projectSuggestions.isNotEmpty() && projectId == null) {
                SuggestionPanel {
                    projectSuggestions.forEach { p ->
                        SuggestionLine(p.canonicalName) {
                            projectText = p.canonicalName
                            projectId = p.id
                            invalidate()
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RateField(
                    label = "Location",
                    value = location,
                    onValueChange = {
                        location = it
                        invalidate()
                    },
                    modifier = Modifier.weight(1f)
                )
                RateField(
                    label = "Quantity",
                    value = quantity,
                    onValueChange = {
                        quantity = it
                        invalidate()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                "Project, location and quantity rank relevance only. They never change the historical rate.",
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary
            )

            localError?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ProcurementColors.AccentRedBright
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        vm.clearRateCheck()
                        vm.clearRateCheckChoiceQuery()
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("BACK")
                }
                Button(
                    onClick = {
                        val proposed = parseCommercialNumber(proposedRate)
                        val qty = quantity.trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let(::parseCommercialNumber)

                        localError = when {
                            item.isBlank() -> "Item description is required."
                            unit.isBlank() -> "Unit is required."
                            proposed == null || proposed <= 0.0 -> "Enter a valid proposed rate."
                            quantity.isNotBlank() && qty == null -> "Quantity is not a valid number."
                            else -> null
                        }

                        if (localError == null) {
                            vm.checkRate(
                                RateCheckRequest(
                                    itemRaw = item,
                                    material = material,
                                    specification = specification,
                                    dimensions = dimensions,
                                    unit = unit,
                                    scope = scope,
                                    contractorId = contractorId,
                                    projectId = projectId,
                                    location = location,
                                    quantity = qty,
                                    proposedRate = proposed!!,
                                    currency = currency
                                )
                            )
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProcurementColors.AccentRed
                    )
                ) {
                    Text(if (running) "CHECKING…" else "CHECK RATE")
                }
            }

            result?.let {
                Spacer(Modifier.height(16.dp))
                RateCheckResultPanel(vm, it, onFindRates)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RateCheckResultPanel(
    vm: ProcurementViewModel,
    result: RateCheckResult,
    onFindRates: () -> Unit
) {
    val basis = result.comparisonBasis
    val verdictTint = when (result.verdict) {
        RateCheckVerdict.NO_COMPARABLE -> ProcurementColors.StatusWarn
        RateCheckVerdict.SAME -> ProcurementColors.StatusInfo
        RateCheckVerdict.LOWER -> ProcurementColors.StatusOk
        RateCheckVerdict.HIGHER -> ProcurementColors.AccentRedBright
    }

    SectionLabel("Check result")
    Panel {
        Text(
            when (result.verdict) {
                RateCheckVerdict.NO_COMPARABLE -> "NO DIRECT COMPARABLE"
                RateCheckVerdict.SAME -> "SAME AS BASIS"
                RateCheckVerdict.LOWER -> "LOWER THAN BASIS"
                RateCheckVerdict.HIGHER -> "HIGHER THAN BASIS"
            },
            style = MaterialTheme.typography.titleMedium,
            color = verdictTint
        )

        if (basis != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${MONEY.format(result.request.proposedRate)} ${result.request.currency} proposed",
                style = MaterialTheme.typography.bodyMedium,
                color = ProcurementColors.TextPrimary
            )
            val delta = result.deltaAmount ?: 0.0
            val pct = result.deltaPercent
            Text(
                buildString {
                    append(if (delta >= 0) "+" else "")
                    append(MONEY.format(delta))
                    append(" ")
                    append(result.request.currency)
                    if (pct != null) {
                        append(" · ")
                        append(if (pct >= 0) "+" else "")
                        append(MONEY.format(pct))
                        append("%")
                    }
                },
                style = MaterialTheme.typography.titleSmall,
                color = verdictTint,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(8.dp))
        result.explanation.forEach { line ->
            Text(
                "• $line",
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        if (result.verdict == RateCheckVerdict.NO_COMPARABLE) {
            Spacer(Modifier.height(7.dp))
            OutlinedButton(
                onClick = {
                    vm.setRateFindQuery(result.request.itemRaw)
                    onFindRates()
                }
            ) {
                Text("VIEW RELATED RATES")
            }
        }
    }

    result.latestAwardedForContractor?.let {
        Spacer(Modifier.height(12.dp))
        SectionLabel("Latest awarded · selected contractor")
        ComparableRateCard(vm, it)
    }

    result.closestComparable
        ?.takeIf { it.id != result.latestAwardedForContractor?.id }
        ?.let {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Closest strict comparable")
            ComparableRateCard(vm, it)
        }
}

@Composable
private fun ComparableRateCard(
    vm: ProcurementViewModel,
    rate: RateListRow
) {
    Panel {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    rate.itemRaw,
                    style = MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildList {
                        rate.contractorName?.let(::add)
                        rate.projectName?.let(::add)
                        rate.location?.let(::add)
                    }.joinToString(" · ").ifBlank { "No contractor/project context" },
                    style = MaterialTheme.typography.bodySmall,
                    color = ProcurementColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${MONEY.format(rate.rate)} ${rate.currency}",
                    style = MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.AccentRedBright,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "/ ${rate.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            buildList {
                add(formatRateDate(rate.observedDate))
                rate.quantity?.let { add("Qty ${MONEY.format(it)}") }
                add(scopeLabel(rate.scope))
            }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextTertiary
        )

        if (!rate.sourceFileName.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                rate.sourceFileName,
                style = MaterialTheme.typography.labelMedium,
                color = ProcurementColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            rate.evidenceLocator?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.TextTertiary
                )
            }
            if (!rate.sourceUri.isNullOrBlank()) {
                TextButton(
                    onClick = {
                        vm.openDocument(
                            rate.sourceUri,
                            rate.sourceExtension,
                            rate.sourceMimeType
                        )
                    }
                ) {
                    Text(
                        "OPEN SOURCE",
                        style = MaterialTheme.typography.labelSmall,
                        color = ProcurementColors.AccentRedBright
                    )
                }
            }
        }
    }
}

@Composable
fun AddRateScreen(
    vm: ProcurementViewModel,
    onBack: () -> Unit
) {
    val contractors by vm.contractors.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val sourceQuery by vm.rateSourceQuery.collectAsStateWithLifecycle()
    val sourceChoices by vm.rateSourceChoices.collectAsStateWithLifecycle()
    val candidate by vm.selectedRateCandidate.collectAsStateWithLifecycle()

    var item by rememberSaveable { mutableStateOf("") }
    var material by rememberSaveable { mutableStateOf("") }
    var specification by rememberSaveable { mutableStateOf("") }
    var dimensions by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(RateScope.SUPPLY_INSTALL) }
    var contractorText by rememberSaveable { mutableStateOf("") }
    var contractorId by rememberSaveable { mutableStateOf<Long?>(null) }
    var projectText by rememberSaveable { mutableStateOf("") }
    var projectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var location by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("EGP") }
    var status by rememberSaveable { mutableStateOf(RateStatus.OBSERVED) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var evidenceLocator by rememberSaveable { mutableStateOf("") }
    var evidenceText by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<RateSourceChoice?>(null) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    var evidenceExpanded by rememberSaveable(candidate?.id) { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable(candidate?.id) { mutableStateOf(false) }

    LaunchedEffect(candidate?.id) {
        candidate?.let { c ->
            item = c.itemRaw
            unit = when (c.unitRaw?.trim()?.lowercase()) {
                "م.ط", "م ط", "lm", "متر طولي" -> "م.ط"
                "م٢", "م2", "م²", "m2", "sqm", "متر مربع" -> "م٢"
                "عدد", "بالعدد", "each", "no", "pcs" -> "عدد"
                "مقطوعية", "مقطوعيه", "lot", "lump sum" -> "مقطوعية"
                "م٣", "م3", "م³", "m3" -> "م³"
                "كجم", "kg" -> "كجم"
                "طن", "ton" -> "طن"
                else -> c.unitRaw?.takeIf { raw -> COMMERCIAL_UNITS.any { it.first == raw } }.orEmpty()
            }
            quantity = c.quantity?.let { MONEY.format(it).replace(",", "") }.orEmpty()
            rate = MONEY.format(c.rate).replace(",", "")
            // Never infer commercial currency or observation date from file metadata.
            currency = c.currency.orEmpty()
            date = ""
            scope = c.inferredScope ?: RateScope.OTHER
            status = RateStatus.OBSERVED
            evidenceLocator = c.evidenceLocator
            evidenceText = c.evidenceText
            selectedSource = c.sourceDocumentId?.let { documentId ->
                RateSourceChoice(
                    id = documentId,
                    displayName = c.sourceFileName,
                    relativePath = c.sourceRelativePath,
                    documentUri = c.sourceUri,
                    mimeType = c.sourceMimeType,
                    extension = c.sourceExtension,
                    contentState = "INDEXED"
                )
            }
        }
    }

    val contractorSuggestions = contractors
        .filter {
            contractorText.isNotBlank() &&
                it.canonicalName.contains(contractorText, ignoreCase = true)
        }
        .take(5)

    val projectSuggestions = projects
        .filter {
            projectText.isNotBlank() &&
                it.canonicalName.contains(projectText, ignoreCase = true)
        }
        .take(5)

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader(
            if (candidate != null) "Review candidate" else "Add rate",
            if (candidate != null) "verify extracted values before saving" else "commercial observation + source evidence"
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            if (candidate != null) {
                Panel {
                    Text("EXTRACTED · NOT VERIFIED", color = ProcurementColors.StatusWarn,
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(candidate!!.itemRaw, color = ProcurementColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("${MONEY.format(candidate!!.rate)} ${candidate!!.currency ?: "CURRENCY UNKNOWN"} / ${candidate!!.unitRaw ?: "UNIT UNKNOWN"}",
                        color = ProcurementColors.AccentRedBright,
                        style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(5.dp))
                    Text("Extracted value only. Check item, price, unit and source before saving.",
                        color = ProcurementColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("Source evidence")
            if (selectedSource == null) {
                RateField(
                    "Search indexed files",
                    sourceQuery,
                    onValueChange = vm::setRateSourceQuery
                )
                if (sourceQuery.isNotBlank()) {
                    SuggestionPanel {
                        sourceChoices.take(8).forEach { doc ->
                            SuggestionLine(
                                title = doc.displayName,
                                subtitle = doc.relativePath
                            ) {
                                selectedSource = doc
                                vm.setRateSourceQuery("")
                            }
                        }
                        if (sourceChoices.isEmpty()) {
                            Text(
                                "No indexed files match.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ProcurementColors.TextTertiary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            } else {
                Panel {
                    Text(
                        selectedSource!!.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = ProcurementColors.TextPrimary
                    )
                    Text(
                        selectedSource!!.relativePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = ProcurementColors.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = {
                                vm.openDocument(
                                    selectedSource!!.documentUri,
                                    selectedSource!!.extension,
                                    selectedSource!!.mimeType
                                )
                            }
                        ) {
                            Text("OPEN SOURCE", color = ProcurementColors.TextSecondary)
                        }
                        TextButton(onClick = { selectedSource = null }) {
                            Text("CHANGE SOURCE", color = ProcurementColors.AccentRedBright)
                        }
                    }
                }
            }

            if (candidate != null) {
                SectionLabel("VERIFY BEFORE SAVING")
                Text(
                    "Required: item, unit, positive rate, currency, date and linked source. Saving creates OBSERVED only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProcurementColors.StatusWarn
                )
                Spacer(Modifier.height(8.dp))
            }
            SectionLabel("Commercial item")
            RateField("Item description *", item, singleLine = false) { item = it }
            if (candidate == null || detailsExpanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RateField(
                    label = "Material",
                    value = material,
                    onValueChange = { material = it },
                    modifier = Modifier.weight(1f)
                )
                RateField(
                    label = "Dimensions",
                    value = dimensions,
                    onValueChange = { dimensions = it },
                    modifier = Modifier.weight(1f)
                )
            }
            RateField("Specification", specification) { specification = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitDropdown(
                    value = unit,
                    extractedValue = candidate?.unitRaw,
                    onSelect = { unit = it },
                    modifier = Modifier.weight(1f)
                )
                RateField(
                    label = "Rate *",
                    value = rate,
                    onValueChange = { rate = it },
                    modifier = Modifier.weight(1f)
                )
                SimpleCurrencyDropdown(
                    value = currency,
                    onSelect = { currency = it },
                    modifier = Modifier.weight(0.8f)
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel("Scope")
            ChoiceRow(
                choices = RateScope.all,
                selected = scope,
                label = ::scopeLabel,
                onSelect = { scope = it }
            )

            if (candidate == null) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Commercial status")
            ChoiceRow(
                choices = RateStatus.all,
                selected = status,
                label = { it },
                onSelect = { status = it }
            )
            if (status in RateStatus.sourceRequired) {
                Text(
                    "A source document is required for ${status.lowercase()} rates.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProcurementColors.StatusWarn
                )
            }

            } else {
                Text("Commercial status: OBSERVED · approval unavailable in candidate review",
                    color = ProcurementColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel("Context")
            RateField(
                "Contractor",
                contractorText,
                onValueChange = {
                    contractorText = it
                    contractorId = null
                }
            )
            if (contractorSuggestions.isNotEmpty() && contractorId == null) {
                SuggestionPanel {
                    contractorSuggestions.forEach { c ->
                        SuggestionLine(c.canonicalName) {
                            contractorText = c.canonicalName
                            contractorId = c.id
                        }
                    }
                }
            }

            RateField(
                "Project",
                projectText,
                onValueChange = {
                    projectText = it
                    projectId = null
                }
            )
            if (projectSuggestions.isNotEmpty() && projectId == null) {
                SuggestionPanel {
                    projectSuggestions.forEach { p ->
                        SuggestionLine(p.canonicalName) {
                            projectText = p.canonicalName
                            projectId = p.id
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RateField(
                    label = "Location",
                    value = location,
                    onValueChange = { location = it },
                    modifier = Modifier.weight(1f)
                )
                RateField(
                    label = "Quantity",
                    value = quantity,
                    onValueChange = { quantity = it },
                    modifier = Modifier.weight(1f)
                )
            }
            RateField("Date (YYYY-MM-DD) *", date) { date = it }

            if (candidate != null) {
                TextButton(onClick = { evidenceExpanded = !evidenceExpanded }) {
                    Text(if (evidenceExpanded) "HIDE EVIDENCE & NOTES" else "SHOW EVIDENCE & NOTES")
                }
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(if (detailsExpanded) "HIDE MATERIAL DETAILS" else "EDIT MATERIAL DETAILS")
                }
            }
            if (candidate == null || evidenceExpanded) {
            RateField("Evidence locator", evidenceLocator) { evidenceLocator = it }
            RateField("Evidence text", evidenceText, singleLine = false) { evidenceText = it }
            RateField("Notes", notes, singleLine = false) { notes = it }

            }

            localError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ProcurementColors.AccentRedBright
                )
            }

            Spacer(Modifier.height(24.dp))
        }
        // Persistent save action stays visible while the verification form scrolls.
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        vm.clearSelectedRateCandidate()
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("CANCEL")
                }
                Button(
                    onClick = {
                        val rateNumber = parseCommercialNumber(rate)
                        val quantityNumber = quantity.trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let(::parseCommercialNumber)
                        val dateMillis = parseRateDate(date)

                        localError = when {
                            candidate?.let { c ->
                                com.edib.procurement.rates.CandidateIdentityGuard.blocksUnchangedItem(
                                    item, c.itemRaw, c.evidenceText
                                )
                            } == true ->
                                "Item may be a trailing specification, not the main BOQ item. Verify and correct the description against the source before saving."
                            item.isBlank() -> "Item description is required."
                            unit.isBlank() -> "Unit is required."
                            rateNumber == null || rateNumber <= 0.0 -> "Enter a valid rate greater than zero."
                            quantity.isNotBlank() && quantityNumber == null -> "Quantity is not a valid number."
                            currency.isBlank() -> "Currency is required."
                            dateMillis == null -> "Date must be YYYY-MM-DD."
                            candidate != null && selectedSource == null ->
                                "A candidate must retain a linked source document before saving."
                            candidate != null && status != RateStatus.OBSERVED ->
                                "Candidate evidence can only be saved as OBSERVED. Approval requires a separate verified workflow."
                            status in RateStatus.sourceRequired && selectedSource == null ->
                                "${status} requires a source document."
                            else -> null
                        }

                        if (localError == null) {
                            vm.addRate(
                                RateDraft(
                                    itemRaw = item,
                                    material = material,
                                    specification = specification,
                                    dimensions = dimensions,
                                    unit = unit,
                                    scope = scope,
                                    contractorId = contractorId,
                                    contractorRaw = contractorText,
                                    projectId = projectId,
                                    projectRaw = projectText,
                                    location = location,
                                    quantity = quantityNumber,
                                    rate = rateNumber!!,
                                    currency = currency,
                                    status = status,
                                    observedDate = dateMillis!!,
                                    sourceDocumentId = selectedSource?.id,
                                    candidateId = candidate?.id,
                                    evidenceLocator = evidenceLocator,
                                    evidenceText = evidenceText,
                                    notes = notes
                                ),
                                onSaved = onBack
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProcurementColors.AccentRed
                    )
                ) {
                    Text(if (candidate != null) "SAVE AS OBSERVED" else "SAVE RATE")
                }
            }
    }
}

@Composable
private fun RateField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
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
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp)
    )
}

@Composable
private fun SimpleCurrencyDropdown(
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text("Currency", style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextSecondary)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("$value ▾", maxLines = 1) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("EGP", "USD", "EUR").forEach { code ->
                    DropdownMenuItem(text = { Text(code) }, onClick = {
                        onSelect(code)
                        expanded = false
                    })
                }
            }
        }
    }
}

private val COMMERCIAL_UNITS = listOf(
    "م.ط" to "متر طولي", "م٢" to "متر مسطح",
    "عدد" to "بالعدد", "مقطوعية" to "Lump sum",
    "م³" to "متر مكعب", "كجم" to "كيلوجرام",
    "طن" to "طن", "طقم" to "Set", "نقطة" to "Point",
    "لفة" to "Roll", "لوح" to "Sheet / Slab", "متر" to "Meter"
)

@Composable
private fun UnitDropdown(
    value: String,
    extractedValue: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text("Unit *", style = MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextSecondary)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text((value.takeIf { selected -> COMMERCIAL_UNITS.any { it.first == selected } }
                    ?: "Needs Review") + " ▾", maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Needs Review — select a unit") }, onClick = {
                    onSelect("")
                    expanded = false
                })
                COMMERCIAL_UNITS.forEach { (code, label) ->
                    DropdownMenuItem(text = { Text("$code — $label") }, onClick = {
                        onSelect(code)
                        expanded = false
                    })
                }
            }
        }
        if (!extractedValue.isNullOrBlank()) {
            Text("Extracted: $extractedValue", style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary, maxLines = 2)
        }
    }
}

@Composable
private fun ChoiceRow(
    choices: List<String>,
    selected: String,
    label: (String) -> String,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        choices.forEach { value ->
            if (value == selected) {
                Button(
                    onClick = { onSelect(value) },
                    modifier = Modifier.semantics { this.selected = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProcurementColors.AccentRed
                    )
                ) {
                    Text(label(value), style = MaterialTheme.typography.labelSmall)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(value) },
                    modifier = Modifier.semantics { this.selected = false }
                ) {
                    Text(label(value), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SuggestionPanel(content: @Composable () -> Unit) {
    Panel(
        modifier = Modifier.padding(bottom = 7.dp),
        padding = androidx.compose.foundation.layout.PaddingValues(4.dp)
    ) {
        content()
    }
}

@Composable
private fun SuggestionLine(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = ProcurementColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun scopeLabel(scope: String): String = when (scope) {
    RateScope.SUPPLY -> "SUPPLY"
    RateScope.INSTALL -> "INSTALL"
    RateScope.SUPPLY_INSTALL -> "SUPPLY + INSTALL"
    else -> "OTHER"
}

private fun parseRateDate(value: String): Long? = runCatching {
    LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrNull()

private fun formatRateDate(value: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(value)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
}.getOrElse { "—" }

private val MONEY = DecimalFormat("#,##0.##")
