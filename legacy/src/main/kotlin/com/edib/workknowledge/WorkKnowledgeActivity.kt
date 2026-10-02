package com.edib.workknowledge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.edib.workknowledge.ui.WorkKnowledgeNavHost
import com.edib.workknowledge.ui.WorkViewModel
import com.edib.workknowledge.ui.theme.WorkKnowledgeTheme

class WorkKnowledgeActivity : ComponentActivity() {

    private val viewModel: WorkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WorkKnowledgeTheme {
                WorkKnowledgeNavHost(viewModel = viewModel)
            }
        }
    }
}
