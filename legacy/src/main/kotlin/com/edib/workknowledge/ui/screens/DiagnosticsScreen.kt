package com.edib.workknowledge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.ui.components.SectionHeader
import com.edib.workknowledge.ui.components.WorkToolbar

/**
 * Diagnostics, split so partial functionality is obvious.
 *
 * A device with no AI endpoint is in "Search-only mode", not broken: source
 * parsing, exact search and the source viewers all still work. Each capability
 * reports its own status so the user is never told the app has failed just
 * because an optional enrichment step is unavailable.
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsState,
    onBack: () -> Unit,
    onTestOllama: () -> Unit,
    onTestEmbedding: () -> Unit,
    onTestChat: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        WorkToolbar(
            title = "Diagnostics",
            subtitle = state.overall,
            showBack = true,
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // ------------------------------------------------------ source data
            SectionHeader("Source data")
            InfoLine("Projects", "${state.projects}")
            InfoLine("Documents", "${state.documents}")
            InfoLine("Chunks", "${state.chunks}")
            StatusLine("Status", state.sourceStatus, state.sourceDetail)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ----------------------------------------------------- exact search
            SectionHeader("Exact search")
            InfoLine("FTS indexed chunks", "${state.ftsIndexed}")
            StatusLine("Status", state.searchStatus, state.searchDetail)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ------------------------------------------------------- embeddings
            SectionHeader("Embeddings")
            InfoLine("Provider", state.embeddingProvider)
            InfoLine("Model", state.embeddingModel)
            InfoLine("Embedded chunks", "${state.embeddedChunks} / ${state.chunks}")
            InfoLine(
                "Dimension",
                if (state.embeddingDimension > 0) "${state.embeddingDimension}"
                else "Not established"
            )
            StatusLine("Status", state.embeddingStatus, state.embeddingDetail)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ------------------------------------------------------- chat model
            SectionHeader("Chat model")
            InfoLine("Provider", state.chatProvider)
            InfoLine("Model", state.chatModel)
            StatusLine("Status", state.chatStatus, state.chatDetail)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // -------------------------------------------------------------- RAG
            SectionHeader("RAG")
            StatusLine("Exact retrieval", state.searchStatus)
            StatusLine(
                "Semantic retrieval",
                if (state.semanticAvailable) CapabilityStatus.READY
                else CapabilityStatus.UNAVAILABLE
            )
            StatusLine(
                "Chat generation",
                if (state.chatAvailable) CapabilityStatus.READY
                else CapabilityStatus.UNAVAILABLE
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Overall",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    state.overall,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ------------------------------------------------------------ server
            SectionHeader("Server")
            InfoLine("Ollama URL", state.ollamaUrl)
            InfoLine("API key configured", if (state.hasApiKey) "Yes" else "No")
            StatusLine("Reachability", state.ollamaStatus)

            if (state.recentErrors.isNotEmpty()) {
                SectionHeader("Recent errors")
                state.recentErrors.forEach { (error, _) ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)
                    )
                }
            }

            SectionHeader("Connection tests")
            OutlinedButton(
                onClick = onTestOllama,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text("Test Ollama connection") }
            OutlinedButton(
                onClick = onTestEmbedding,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text("Test embedding model") }
            OutlinedButton(
                onClick = onTestChat,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text("Test chat model") }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A status line. "Unavailable" is rendered plainly, in the same weight as the
 * other values: a missing optional endpoint is information, not an error.
 */
@Composable
private fun StatusLine(
    label: String,
    status: CapabilityStatus,
    detail: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = status.label,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = when (status) {
                    CapabilityStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
