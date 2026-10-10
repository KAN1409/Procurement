package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

@Composable
fun RatesScreen(
    vm: ProcurementViewModel,
    onAddRate: () -> Unit
) {
    val query by vm.rateQuery.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val count by vm.rateCount.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader("Rates", "${count} commercial observations")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchField(
                value = query,
                onValueChange = vm::setRateQuery,
                placeholder = "Search item, contractor, project, scope…",
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onAddRate,
                colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed)
            ) {
                Text("ADD")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (rates.isEmpty()) {
            EmptyState(
                if (query.isBlank()) {
                    "No commercial rates yet. Add the first observation from a trusted source."
                } else {
                    "No rate observations match this search."
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
                items(rates, key = { it.id }) { rate ->
                    RateRow(vm, rate)
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
private fun RateRow(vm: ProcurementViewModel, rate: RateListRow) {
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
fun AddRateScreen(
    vm: ProcurementViewModel,
    onBack: () -> Unit
) {
    val contractors by vm.contractors.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val sourceQuery by vm.rateSourceQuery.collectAsStateWithLifecycle()
    val sourceChoices by vm.rateSourceChoices.collectAsStateWithLifecycle()

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
    var status by rememberSaveable { mutableStateOf(RateStatus.AWARDED) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var evidenceLocator by rememberSaveable { mutableStateOf("") }
    var evidenceText by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<RateSourceChoice?>(null) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

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
        ScreenHeader("Add rate", "commercial observation + source evidence")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            SectionLabel("Commercial item")
            RateField("Item description *", item) { item = it }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RateField(
                    label = "Unit *",
                    value = unit,
                    onValueChange = { unit = it },
                    modifier = Modifier.weight(1f)
                )
                RateField(
                    label = "Rate *",
                    value = rate,
                    onValueChange = { rate = it },
                    modifier = Modifier.weight(1f)
                )
                RateField(
                    label = "Currency",
                    value = currency,
                    onValueChange = { currency = it },
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
                    TextButton(onClick = { selectedSource = null }) {
                        Text("CHANGE SOURCE", color = ProcurementColors.AccentRedBright)
                    }
                }
            }

            RateField("Evidence locator", evidenceLocator) { evidenceLocator = it }
            RateField("Evidence text", evidenceText, singleLine = false) { evidenceText = it }
            RateField("Notes", notes, singleLine = false) { notes = it }

            localError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ProcurementColors.AccentRedBright
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("CANCEL")
                }
                Button(
                    onClick = {
                        val rateNumber = rate.replace(",", "").trim().toDoubleOrNull()
                        val quantityNumber = quantity.replace(",", "").trim()
                            .takeIf { it.isNotEmpty() }
                            ?.toDoubleOrNull()
                        val dateMillis = parseRateDate(date)

                        localError = when {
                            item.isBlank() -> "Item description is required."
                            unit.isBlank() -> "Unit is required."
                            rateNumber == null || rateNumber <= 0.0 -> "Enter a valid rate greater than zero."
                            quantity.isNotBlank() && quantityNumber == null -> "Quantity is not a valid number."
                            dateMillis == null -> "Date must be YYYY-MM-DD."
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
                    Text("SAVE RATE")
                }
            }
            Spacer(Modifier.height(24.dp))
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProcurementColors.AccentRed
                    )
                ) {
                    Text(label(value), style = MaterialTheme.typography.labelSmall)
                }
            } else {
                OutlinedButton(onClick = { onSelect(value) }) {
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
