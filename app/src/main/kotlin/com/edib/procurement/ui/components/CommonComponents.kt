package com.edib.procurement.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edib.procurement.ui.theme.ProcurementColors

/** Section label: small, letter-spaced, quiet. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = ProcurementColors.TextTertiary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(bottom = 6.dp)
    )
}

/** Dense stat row used across HOME and SETTINGS. */
@Composable
fun StatRow(
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        stats.forEachIndexed { index, (label, value) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = ProcurementColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    value,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = ProcurementColors.TextPrimary,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (index != stats.lastIndex) {
                HorizontalDivider(
                    color = ProcurementColors.Graphite700,
                    thickness = 1.dp
                )
            }
        }
    }
}

/** Small flat pill. Not rounded, just a tight rectangle — no pill shapes. */
@Composable
fun Chip(
    text: String,
    tint: Color = ProcurementColors.TextSecondary,
    background: Color = ProcurementColors.Graphite700,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .background(background, RoundedCornerShape(2.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1
        )
    }
}

/** Human byte size. */
/**
 * Human-readable byte size.
 *
 * Binary (1024-based) units, consistently: every step divides by the unit it is
 * leaving, so the MB branch must divide by 1024^2, not 1024. Getting that wrong
 * made a 1.7 MB workbook display as "1703.7 MB".
 */
object ByteFormat {

    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val TB = GB * 1024

    /** Renders [bytes] using binary units. A non-positive size shows as "—". */
    fun size(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < KB -> "$bytes B"
        bytes < MB -> oneDecimal(bytes.toDouble() / KB) + " KB"
        bytes < GB -> oneDecimal(bytes.toDouble() / MB) + " MB"
        bytes < TB -> oneDecimal(bytes.toDouble() / GB) + " GB"
        else -> oneDecimal(bytes.toDouble() / TB) + " TB"
    }

    /** Whole bytes get no decimal part: "586 B", not "586.0 B". */
    private fun oneDecimal(value: Double): String {
        // One decimal, but never a misleading ".0" on an exact figure.
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString()
        else String.format("%.1f", rounded)
    }
}

/** Convenience alias used across the screens. */
fun formatSize(bytes: Long): String = ByteFormat.size(bytes)

/**
 * A compact list row with a title line, optional subtitle and trailing meta.
 * Density over decoration.
 */
@Composable
fun DenseRow(
    title: String,
    subtitle: String? = null,
    meta: String? = null,
    accent: Color? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(if (subtitle == null) 28.dp else 40.dp)
                    .background(accent)
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = ProcurementColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = ProcurementColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!meta.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                meta,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = ProcurementColors.TextTertiary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** Empty-state line, kept terse. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = ProcurementColors.TextTertiary
        )
    }
}

/** Flat bordered panel, sharp corners: no oversized rounded cards. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(ProcurementColors.Graphite850, RoundedCornerShape(3.dp))
            .border(1.dp, ProcurementColors.Graphite700, RoundedCornerShape(3.dp))
            .padding(padding),
        content = content
    )
}

/** Vertical list of rows separated by hairlines. */
@Composable
fun <T> RowList(
    items: List<T>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    key: ((T) -> Any)? = null,
    itemContent: @Composable (T) -> Unit
) {
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(items, key = key) { item ->
            itemContent(item)
            HorizontalDivider(
                color = ProcurementColors.Graphite700,
                thickness = 1.dp
            )
        }
    }
}
