package com.edib.workknowledge.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.comparison.CellChange
import com.edib.workknowledge.comparison.WorkbookDiff
import com.edib.workknowledge.ui.components.EmptyState
import com.edib.workknowledge.ui.components.SectionHeader
import com.edib.workknowledge.ui.components.WorkDivider
import com.edib.workknowledge.ui.components.WorkToolbar

/**
 * Deterministic comparison of two workbook revisions.
 *
 * Everything shown here is computed by comparing cell values; nothing is
 * inferred. Tapping a change opens the new revision at the sheet and row that
 * actually changed.
 */
@Composable
fun ComparisonScreen(
    oldName: String,
    newName: String,
    candidates: List<Pair<Long, String>>,
    selectedOldId: Long,
    selectedNewId: Long,
    diff: WorkbookDiff?,
    loading: Boolean,
    error: String?,
    onPickOld: (Long) -> Unit,
    onPickNew: (Long) -> Unit,
    onOpenRow: (sheet: String, rowStart: Int?, rowEnd: Int?) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        WorkToolbar(
            title = "Compare revisions",
            subtitle = if (diff != null) "${diff.oldDocument} → ${diff.newDocument}" else "Select two workbooks",
            showBack = true,
            onBack = onBack
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Picker(
                label = "Old",
                options = candidates,
                selectedId = selectedOldId,
                onSelect = onPickOld,
                modifier = Modifier.weight(1f)
            )
            Picker(
                label = "New",
                options = candidates,
                selectedId = selectedNewId,
                onSelect = onPickNew,
                modifier = Modifier.weight(1f)
            )
        }

        WorkDivider()

        when {
            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )

            loading -> Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Comparing workbooks…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            diff == null -> EmptyState(
                icon = Icons.Filled.List,
                title = "Choose two revisions",
                subtitle = "Pick an older and a newer workbook to see exactly which " +
                    "items, quantities, rates and formulas changed."
            )

            diff.isEmpty -> EmptyState(
                icon = Icons.Filled.Check,
                title = "No differences",
                subtitle = "Every item, value and formula is identical between these two revisions."
            )

            else -> DiffBody(diff = diff, onOpenRow = onOpenRow)
        }
    }
}

@Composable
private fun Picker(
    label: String,
    options: List<Pair<Long, String>>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.first == selectedId }?.second ?: "Select…"

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = selected,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    }
                )
            }
        }
    }
}

@Composable
private fun DiffBody(diff: WorkbookDiff, onOpenRow: (String, Int?, Int?) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = diff.summary(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        if (diff.addedSheets.isNotEmpty() || diff.removedSheets.isNotEmpty()) {
            item { SectionHeader("Sheets") }
            items(diff.addedSheets) {
                ChangeLine("Sheet added", it, "", "Added in the new revision") { }
            }
            items(diff.removedSheets) {
                ChangeLine("Sheet removed", it, "", "Not present in the new revision") { }
            }
            item { WorkDivider() }
        }

        if (diff.addedRows.isNotEmpty()) {
            item { SectionHeader("Items added", subtitle = "${diff.addedRows.size}") }
            items(diff.addedRows) { row ->
                RowItem(
                    label = "Added",
                    key = row.key ?: "row ${row.rowNumber}",
                    detail = row.cells.entries
                        .firstOrNull { it.key.equals("Description", true) }?.value.orEmpty(),
                    location = "${row.sheet} · row ${row.rowNumber}",
                    onClick = { onOpenRow(row.sheet, row.rowNumber, row.rowNumber) }
                )
            }
            item { WorkDivider() }
        }

        if (diff.removedRows.isNotEmpty()) {
            item { SectionHeader("Items removed", subtitle = "${diff.removedRows.size}") }
            items(diff.removedRows) { row ->
                RowItem(
                    label = "Removed",
                    key = row.key ?: "row ${row.rowNumber}",
                    detail = row.cells.entries
                        .firstOrNull { it.key.equals("Description", true) }?.value.orEmpty(),
                    location = "${row.sheet} · row ${row.rowNumber}",
                    onClick = { }
                )
            }
            item { WorkDivider() }
        }

        if (diff.changedCells.isNotEmpty()) {
            item {
                SectionHeader(
                    "Cell changes",
                    subtitle = "${diff.changedCells.size} change(s), computed from cell values"
                )
            }
            items(diff.changedCells) { change ->
                ChangeLine(
                    label = labelFor(change),
                    key = change.key ?: change.column,
                    detail = change.describe(),
                    location = change.sheet,
                    onClick = { }
                )
            }
        }

        if (diff.relocations.isNotEmpty()) {
            item { SectionHeader("Rows moved") }
            items(diff.relocations) {
                ChangeLine(
                    label = "Moved",
                    key = it.key ?: "row",
                    detail = "row ${it.oldRow} → ${it.newRow}",
                    location = it.sheet,
                    onClick = { onOpenRow(it.sheet, it.newRow, it.newRow) }
                )
            }
        }

        // Anything the comparison could not do safely is stated, not hidden.
        if (diff.notes.isNotEmpty()) {
            item { SectionHeader("Notes") }
            items(diff.notes) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun labelFor(change: CellChange): String = when (change.kind) {
    com.edib.workknowledge.comparison.ChangeKind.VALUE -> "Changed"
    com.edib.workknowledge.comparison.ChangeKind.FORMULA -> "Formula"
    com.edib.workknowledge.comparison.ChangeKind.FORMULA_AND_VALUE -> "Formula + value"
    com.edib.workknowledge.comparison.ChangeKind.VALUE_ADDED -> "Added"
    com.edib.workknowledge.comparison.ChangeKind.VALUE_REMOVED -> "Cleared"
    com.edib.workknowledge.comparison.ChangeKind.COLUMN_ADDED -> "New column"
    com.edib.workknowledge.comparison.ChangeKind.COLUMN_REMOVED -> "Dropped column"
}

@Composable
private fun ChangeLine(
    label: String,
    key: String,
    detail: String,
    location: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(96.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = location,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RowItem(
    label: String,
    key: String,
    detail: String,
    location: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(96.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = location,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
