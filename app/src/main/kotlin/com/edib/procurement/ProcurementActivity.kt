package com.edib.procurement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.edib.procurement.ui.ProcurementApp
import com.edib.procurement.ui.theme.ProcurementColors
import com.edib.procurement.ui.theme.ProcurementTheme

/**
 * Launcher activity for the procurement / document-control app.
 *
 * Entirely offline: no network permission is declared, nothing is uploaded, and
 * the only inputs are a SAF workbook URI and a SAF document tree URI.
 */
class ProcurementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ProcurementTheme {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .background(ProcurementColors.Graphite900)
                ) {
                    ProcurementApp()
                }
            }
        }
    }
}
