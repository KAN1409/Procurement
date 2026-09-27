package com.edib.workknowledge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.ui.components.SectionHeader
import com.edib.workknowledge.ui.components.WorkToolbar

/**
 * Diagnostics.
 *
 * Reports what is actually configured and reachable, so a user can tell the
 * difference between "the model server is down" and "the index is empty".
 * Secrets are never shown - only whether a key is present.
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
        WorkToolbar(title = "Diagnostics", showBack = true, onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            SectionHeader("Index")
            InfoLine("Documents indexed", state.documentCount.toString())
            InfoLine("Chunks indexed", state.chunkCount.toString())
            InfoLine("Embedding model", state.embeddingModel)
            InfoLine("Vector dimensions", state.storedDimensions.toString())
            InfoLine("Index status", state.indexStatus)

            SectionHeader("Models")
            InfoLine("Chat provider", state.chatProvider)
            InfoLine("Chat model", state.chatModel)
            InfoLine("Embedding provider", state.embeddingProvider)
            InfoLine("API key configured", if (state.hasApiKey) "Yes" else "No")

            SectionHeader("Server")
            InfoLine("Ollama URL", state.ollamaUrl)
            InfoLine("Reachability", state.ollamaStatus)

            SectionHeader("Recent errors")
            if (state.recentErrors.isEmpty()) {
                InfoLine("None", "No processing errors recorded.")
            } else {
                state.recentErrors.forEach { error ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            text = error.first,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = RelativeTime.format(error.second),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
