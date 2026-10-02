package com.edib.workknowledge.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.ui.components.LoadingIndicator
import com.edib.workknowledge.ui.components.WorkDivider
import com.edib.workknowledge.ui.components.WorkToolbar
import com.edib.workknowledge.ui.viewer.ExcelSheetReader

/**
 * Read-only spreadsheet viewer.
 *
 * A citation such as "Master Bathroom · Rows 42–47" opens the correct sheet,
 * scrolls to the first cited row and highlights the whole cited range, so the
 * user can confirm the number in the actual file rather than trusting the chat.
 */
@Composable
fun ExcelViewerScreen(
    filename: String,
    uri: android.net.Uri,
    initialSheet: String? = null,
    initialRowStart: Int? = null,
    initialRowEnd: Int? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val reader = remember { ExcelSheetReader(context) }

    var sheets by remember { mutableStateOf<List<ExcelSheetReader.SheetSummary>>(emptyList()) }
    var activeSheet by remember { mutableStateOf(initialSheet) }
    var window by remember { mutableStateOf<ExcelSheetReader.SheetWindow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()

    DisposableEffect(Unit) { onDispose { reader.close() } }

    LaunchedEffect(uri) {
        loading = true
        runCatching { reader.open(uri) }
            .onFailure { error = "This workbook could not be opened: ${it.message ?: "unsupported file"}" }
        sheets = reader.sheets()
        activeSheet = activeSheet ?: sheets.firstOrNull { !it.hidden }?.name
        loading = false
    }

    LaunchedEffect(activeSheet, initialRowStart) {
        val name = activeSheet ?: return@LaunchedEffect
        // Centre the window on the cited rows so the highlight is visible.
        val start = ((initialRowStart ?: 1) - 4).coerceAtLeast(1)
        window = runCatching { reader.window(name, start, WINDOW_ROWS) }
            .onFailure { error = "Could not read sheet \"$name\": ${it.message}" }
            .getOrNull()
    }

    // Scroll the first cited row into view once the window is populated.
    LaunchedEffect(window, initialRowStart) {
        val w = window ?: return@LaunchedEffect
        val target = initialRowStart ?: return@LaunchedEffect
        val index = w.rows.indexOfFirst { it.first >= target }
        if (index >= 0) listState.scrollToItem(index)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WorkToolbar(
            title = filename,
            subtitle = activeSheet?.let { "Sheet: $it" } ?: "Workbook",
            showBack = true,
            onBack = onBack
        )

        if (sheets.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sheets, key = { it.name }) { sheet ->
                    FilterChip(
                        selected = sheet.name == activeSheet,
                        enabled = !sheet.hidden,
                        onClick = { activeSheet = sheet.name },
                        label = { Text(sheet.name, maxLines = 1) }
                    )
                }
            }
        }

        WorkDivider()

        when {
            error != null -> Text(
                text = error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            )
            loading -> LoadingIndicator("Opening workbook…")
            window == null -> LoadingIndicator("Reading sheet…")
            else -> {
                val w = window!!
                val highlight = initialRowStart != null && initialRowEnd != null &&
                    initialSheet != null && activeSheet == initialSheet

                Column(modifier = Modifier.fillMaxSize()) {
                    // Header row, pinned above the scrolling body.
                    if (w.headers.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(horizontal)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = "Row",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .width(ROW_HEADER_WIDTH)
                                    .padding(horizontal = 8.dp)
                            )
                            w.headers.forEach { header ->
                                Text(
                                    text = header,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .width(CELL_WIDTH)
                                        .padding(horizontal = 8.dp)
                                )
                            }
                        }
                        WorkDivider()
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .horizontalScroll(horizontal)
                    ) {
                        items(w.rows, key = { it.first }) { (rowNumber, cells) ->
                            val inRange = highlight && rowNumber in initialRowStart!!..initialRowEnd!!
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (inRange) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .padding(vertical = 5.dp)
                            ) {
                                Text(
                                    text = rowNumber.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .width(ROW_HEADER_WIDTH)
                                        .padding(horizontal = 8.dp)
                                )
                                cells.forEach { cell ->
                                    Text(
                                        text = cell.value,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .width(CELL_WIDTH)
                                            .padding(horizontal = 8.dp)
                                    )
                                }
                            }
                            WorkDivider()
                        }
                    }
                }
            }
        }
    }
}

private const val WINDOW_ROWS = 200
private val ROW_HEADER_WIDTH = 52.dp
private val CELL_WIDTH = 140.dp
