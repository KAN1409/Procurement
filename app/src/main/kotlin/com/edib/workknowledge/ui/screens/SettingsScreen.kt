package com.edib.workknowledge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.embeddings.EmbeddingConfig
import com.edib.workknowledge.llm.ModelConfig
import com.edib.workknowledge.ui.components.SectionHeader
import com.edib.workknowledge.ui.components.WorkDivider
import com.edib.workknowledge.ui.components.WorkToolbar

/**
 * Provider configuration.
 *
 * Defaults target a LAN Ollama server, which is the primary supported setup.
 * The URL is editable because a physical device cannot reach 10.0.2.2 - the
 * emulator's host loopback - and must point at the host's real address.
 */
@Composable
fun SettingsScreen(
    embeddingConfig: EmbeddingConfig,
    chatConfig: ModelConfig,
    onSaveEmbedding: (EmbeddingConfig) -> Unit,
    onSaveChat: (ModelConfig) -> Unit,
    onBack: () -> Unit
) {
    var embedding by remember { mutableStateOf(embeddingConfig) }
    var chat by remember { mutableStateOf(chatConfig) }

    Column(modifier = Modifier.fillMaxSize()) {
        WorkToolbar(title = "Settings", showBack = true, onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            SectionHeader("Chat model")
            ChipRow(
                label = "Provider",
                options = ModelConfig.ProviderType.entries.map { it.name },
                selected = chat.providerType.name,
                onSelect = { name ->
                    chat = chat.copy(providerType = ModelConfig.ProviderType.valueOf(name))
                }
            )
            Field("Model name", chat.modelName) { chat = chat.copy(modelName = it) }
            Field("Ollama URL", chat.ollamaBaseUrl) { chat = chat.copy(ollamaBaseUrl = it) }
            if (chat.providerType != ModelConfig.ProviderType.OLLAMA) {
                Field("OpenAI-compatible base URL", chat.openAiBaseUrl) {
                    chat = chat.copy(openAiBaseUrl = it)
                }
                Field("API key", chat.apiKey.orEmpty(), secret = true) {
                    chat = chat.copy(apiKey = it.ifBlank { null })
                }
            }
            TextButton(onClick = { onSaveChat(chat) }) { Text("Save chat settings") }

            WorkDivider()
            SectionHeader("Embedding model")
            ChipRow(
                label = "Provider",
                options = EmbeddingConfig.ProviderType.entries.map { it.name },
                selected = embedding.providerType.name,
                onSelect = { name ->
                    embedding = embedding.copy(
                        providerType = EmbeddingConfig.ProviderType.valueOf(name)
                    )
                }
            )
            Field("Model name", embedding.modelName) { embedding = embedding.copy(modelName = it) }
            Field("Ollama URL", embedding.ollamaBaseUrl) {
                embedding = embedding.copy(ollamaBaseUrl = it)
            }
            if (embedding.providerType != EmbeddingConfig.ProviderType.OLLAMA) {
                Field("API key", embedding.apiKey.orEmpty(), secret = true) {
                    embedding = embedding.copy(apiKey = it.ifBlank { null })
                }
            }
            TextButton(onClick = { onSaveEmbedding(embedding) }) { Text("Save embedding settings") }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else
            androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option) }
            )
        }
    }
}
