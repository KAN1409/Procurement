package com.edib.workknowledge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.data.ChatMessageEntity
import com.edib.workknowledge.ui.components.CitationChip
import com.edib.workknowledge.ui.components.WorkDivider
import com.edib.workknowledge.ui.components.WorkToolbar

/**
 * Grounded Q&A over one project's indexed documents.
 *
 * The scope is in the toolbar at all times. Citations are rendered from the
 * chunks that were actually retrieved, and tapping one navigates to that exact
 * page / sheet and row range.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    projectName: String,
    messages: List<ChatMessageEntity>,
    asking: Boolean,
    onBack: () -> Unit,
    onAsk: (String) -> Unit,
    onOpenCitation: (ChatMessageEntity.Citation) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WorkToolbar(
            title = "Ask $projectName",
            subtitle = "Answered from this project's indexed documents only",
            showBack = true,
            onBack = onBack
        )

        if (messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Ask a question about the indexed documents",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Answers are generated from retrieved document text and cite the " +
                        "page, sheet and row they came from. If the documents do not contain " +
                        "the answer, the assistant will say so.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                listOf(
                    "What documents are in this project?",
                    "Which quantities changed between revisions?",
                    "Which items are still outstanding?"
                ).forEach { suggestion ->
                    TextButton(
                        onClick = { onAsk(suggestion) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBlock(message = message, onOpenCitation = onOpenCitation)
                }
            }
        }

        WorkDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about these documents…") },
                enabled = !asking,
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    if (input.isNotBlank()) {
                        onAsk(input)
                        input = ""
                    }
                },
                enabled = input.isNotBlank() && !asking
            ) {
                Text(if (asking) "…" else "Ask")
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MessageBlock(
    message: ChatMessageEntity,
    onOpenCitation: (ChatMessageEntity.Citation) -> Unit
) {
    val isUser = message.role == ChatMessageEntity.Role.USER
    val citations = remember(message.citationsJson) { message.getCitations() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(start = if (isUser) 24.dp else 0.dp, end = if (isUser) 0.dp else 24.dp)
        ) {
            Text(
                text = if (isUser) "You" else "Assistant",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            // BiDi: source text may be Arabic, English, or mixed. Compose's
            // text layout applies the Unicode BiDi algorithm automatically, so
            // no reordering, translation or normalisation is applied here - the
            // string reaches the screen byte-for-byte as extracted.
            BasicText(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (citations.isNotEmpty() && !isUser) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Sources",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    citations.forEach { citation ->
                        CitationChip(
                            label = citation.documentName.ifBlank { "Document" },
                            detail = describeLocation(citation),
                            onClick = { onOpenCitation(citation) }
                        )
                    }
                }
            }
        }
    }
}

/** Human-readable provenance, e.g. "Master Bathroom · Rows 42–47". */
fun describeLocation(citation: ChatMessageEntity.Citation): String = buildString {
    citation.sheetName?.let { append(it) }
    if (citation.rowStart != null && citation.rowEnd != null) {
        if (isNotEmpty()) append(" · ")
        append("Rows $citation.rowStart–$citation.rowEnd")
    }
    citation.pageNumber?.let {
        if (isNotEmpty()) append(" · ")
        append("Page $it")
    }
    if (isEmpty()) append("Indexed content")
}

