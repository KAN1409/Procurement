package com.edib.workknowledge.ui

import android.content.Context
import com.edib.workknowledge.data.WorkKnowledgeDatabase
import com.edib.workknowledge.data.WorkRepository
import com.edib.workknowledge.embeddings.EmbeddingProviderFactory
import com.edib.workknowledge.llm.ChatModelProviderFactory
import com.edib.workknowledge.parsers.ParserRegistry
import com.edib.workknowledge.rag.DefaultRagEngine
import com.edib.workknowledge.rag.HierarchicalSummarizationEngine
import com.edib.workknowledge.rag.RagConfig
import com.edib.workknowledge.retrieval.RoomVectorRepository

/**
 * Manual dependency container.
 *
 * Deliberately not Hilt: the graph is small and fully known at startup, and
 * avoiding annotation processing keeps the build fast and the wiring visible.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: WorkKnowledgeDatabase by lazy {
        // Must be the same instance the repository uses, and must not carry a
        // destructive fallback: the v1 -> v2 migration preserves existing data.
        WorkKnowledgeDatabase.getInstance(appContext)
    }

    val repository: WorkRepository by lazy { WorkRepository(appContext) }
    val parsers: ParserRegistry by lazy { ParserRegistry(appContext) }
    val vectorRepository by lazy { RoomVectorRepository(database.chunkDao()) }

    val embeddingFactory by lazy { EmbeddingProviderFactory(appContext) }
    val chatFactory by lazy { ChatModelProviderFactory(appContext) }

    val ragEngine by lazy {
        DefaultRagEngine(
            embeddingProvider = embeddingFactory.createProvider(),
            vectorRepository = vectorRepository,
            chatModelProvider = chatFactory.createProvider(),
            config = RagConfig()
        )
    }

    val summarizationEngine by lazy {
        HierarchicalSummarizationEngine(chatFactory.createProvider())
    }
}
