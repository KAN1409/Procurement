package com.edib.workknowledge.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.edib.workknowledge.ui.components.LoadingIndicator
import com.edib.workknowledge.ui.components.WorkDivider
import com.edib.workknowledge.ui.components.WorkToolbar
import com.edib.workknowledge.ui.viewer.PdfPageRenderer

/**
 * PDF viewer that opens directly at a cited page.
 *
 * [initialPage] is 1-based and comes from the citation, so tapping
 * "Material Approval.pdf · Page 6" really does land on page 6.
 */
@Composable
fun PdfViewerScreen(
    filename: String,
    uri: android.net.Uri,
    initialPage: Int = 1,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val renderer = remember { PdfPageRenderer(context) }

    var pageCount by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(initialPage.coerceAtLeast(1)) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose { renderer.close() }
    }

    LaunchedEffect(uri) {
        loading = true
        error = runCatching { renderer.open(uri) }
            .exceptionOrNull()
            ?.let { "This PDF could not be opened: ${it.message ?: "file unavailable"}" }
        pageCount = renderer.pageCount
        page = initialPage.coerceIn(1, maxOf(1, pageCount))
        loading = false
    }

    LaunchedEffect(page, pageCount) {
        if (pageCount == 0) return@LaunchedEffect
        val widthPx = with(density) { 1080.dp.roundToPx() }
        bitmap = renderer.renderPage(page, widthPx)
        if (bitmap == null && error == null) {
            error = "Page $page could not be rendered."
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WorkToolbar(
            title = filename,
            subtitle = if (pageCount > 0) "Page $page of $pageCount" else filename,
            showBack = true,
            onBack = onBack
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                error != null -> Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
                loading -> LoadingIndicator("Opening document…")
                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Page $page",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    contentScale = ContentScale.FillWidth
                )
                else -> LoadingIndicator("Rendering page $page…")
            }
        }

        WorkDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (page > 1) page-- },
                enabled = page > 1
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Previous page",
                    tint = if (page > 1) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }
            Text(
                text = "Page $page / ${maxOf(pageCount, 1)}",
                style = MaterialTheme.typography.labelLarge
            )
            TextButton(
                onClick = { if (page < pageCount) page++ },
                enabled = page < pageCount
            ) {
                Text("Next")
            }
        }
    }
}
