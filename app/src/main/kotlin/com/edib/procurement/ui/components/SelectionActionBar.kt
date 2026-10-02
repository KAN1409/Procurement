package com.edib.procurement.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edib.procurement.ui.theme.ProcurementColors

/**
 * The action bar shown while rows are selected.
 *
 * The count is always visible, because in selection mode the reader otherwise
 * has no way to tell how much they have picked. SELECT ALL RESULTS is offered
 * separately from the count because it means "every row the current search
 * matches", including rows Paging has not loaded.
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    noun: String,
    onCreatePack: () -> Unit,
    onSelectAll: (() -> Unit)? = null,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(ProcurementColors.Graphite900)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$selectedCount $noun selected",
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = ProcurementColors.AccentRedBright
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onSelectAll != null) {
                    TextButton(onClick = onSelectAll) {
                        Text(
                            "SELECT ALL RESULTS",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = ProcurementColors.StatusInfo
                        )
                    }
                }
                TextButton(onClick = onClear) {
                    Text(
                        "CLEAR",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = ProcurementColors.TextSecondary
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onCreatePack,
            enabled = selectedCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = ProcurementColors.AccentRed,
                disabledContainerColor = ProcurementColors.Graphite850
            ),
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (selectedCount > 0) "CREATE AI PACK ($selectedCount $noun)"
                else "CREATE AI PACK"
            )
        }
    }
}
