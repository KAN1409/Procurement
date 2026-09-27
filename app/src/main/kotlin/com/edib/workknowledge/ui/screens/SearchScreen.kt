package com.edib.workknowledge.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.search.SearchHit
import com.edib.workknowledge.ui.components.EmptyState
import com.edib.workknowledge.ui.components.WorkDivider
import com.edib.workknowledge.ui.components.WorkToolbar

/**
 * Exact / keyword search over indexed documents.
 *
 * Works with no AI endpoint configured: this is backed purely by the
 * full-text index, and every result carries the page / sheet / row it was
 * found on so tapping it opens that exact place in the original file.
 */
@Composable
fun SearchScreen(
    results: List<SearchHit>,
    searching: Boolean,
    indexSize: Int,
    onQueryChange: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        WorkToolbar(
            title = "Search",
            subtitle = if (indexSize > 0) {
                "$indexSize indexed passage${if (indexSize == 1) "" else "s"}"
            } else {
                "Nothing indexed yet"
            },
            showBack = true,
            onBack = onBack
        )

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onQueryChange(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("MR-104, PR-023, Piatra Grey…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        onQueryChange("")
                    }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear"
                        )
                    }
                }
            }
        )

        WorkDivider()

        when {
            query.isBlank() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "Search your documents",
                subtitle = "Find exact identifiers such as item codes, purchase request " +
                    "numbers and material names. Results link to the page or row " +
                    "they came from."
            )

            searching -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Searching…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            results.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "No matches",
                subtitle = "Nothing indexed contains \"$query\"."
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = "${results.size} result${if (results.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(results, key = { it.chunkId }) { hit ->
                    SearchResultRow(hit = hit, onClick = { onOpenHit(hit) })
                    WorkDivider()
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hit.filename,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (hit.exactMatch) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "exact",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Provenance line: where in the file this actually is.
        Text(
            text = hit.location,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = hit.projectName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(2.dp))

        // Snippet: source text may be Arabic, English or mixed, so it is
        // rendered as-is with no reordering or normalisation.
        BasicText(
            text = hit.snippet.replace(Regex("\\s+"), " ").trim().take(220),
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
