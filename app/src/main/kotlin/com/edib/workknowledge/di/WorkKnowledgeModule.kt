package com.edib.workknowledge.di

import android.content.Context
import androidx.room.Room
import com.edib.workknowledge.data.WorkKnowledgeDatabase
import com.edib.workknowledge.data.WorkRepository
import com.edib.workknowledge.embeddings.EmbeddingProvider
import com.edib.workknowledge.embeddings.EmbeddingProviderFactory
import com.edib.workknowledge.llm.ChatModelProvider
import com.edib.workknowledge.llm.ChatModelProviderFactory
import com.edib.workknowledge.parsers.ParserRegistry
import com.edib.workknowledge.rag.DefaultRagEngine
import com.edib.workknowledge.rag.RagEngine
import com.edib.workknowledge.rag.HierarchicalSummarizationEngine
import com.edib.workknowledge.rag.SummarizationEngine
import com.edib.workknowledge.retrieval.RoomVectorRepository
import com.edib.workknowledge.retrieval.VectorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Dependency provider for Work Knowledge system.
 * 
 * This object provides factory methods to create all required dependencies.
 * Not using Hilt/Dagger for V1 to avoid KAPT annotation processing overhead
 * and build complexity in this environment. All dependencies are created
 * manually and passed through constructors.
 */
object WorkKnowledgeModule {
    
    fun provideDatabase(context: Context): WorkKnowledgeDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            WorkKnowledgeDatabase::class.java,
            "workknowledge.db"
        ).build()
    }
    
    fun provideWorkRepository(context: Context): WorkRepository {
        return WorkRepository(context)
    }
    
    fun provideParserRegistry(context: Context): ParserRegistry {
        return ParserRegistry(context)
    }
    
    fun provideEmbeddingProviderFactory(context: Context): EmbeddingProviderFactory {
        return EmbeddingProviderFactory(context)
    }
    
    fun provideChatModelProviderFactory(context: Context): ChatModelProviderFactory {
        return ChatModelProviderFactory(context)
    }
    
    fun provideVectorRepository(database: WorkKnowledgeDatabase): VectorRepository {
        return RoomVectorRepository(database.chunkDao())
    }
    
    fun provideSummarizationEngine(chatModelProvider: ChatModelProvider): SummarizationEngine {
        return HierarchicalSummarizationEngine(chatModelProvider)
    }
    
    fun provideRagEngine(
        embeddingProvider: EmbeddingProvider,
        vectorRepository: VectorRepository,
        chatModelProvider: ChatModelProvider
    ): RagEngine {
        return DefaultRagEngine(embeddingProvider, vectorRepository, chatModelProvider)
    }
    
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

// Entry point for Workers and other background components
interface WorkKnowledgeEntryPoint {
    fun workRepository(): WorkRepository
    fun parserRegistry(): ParserRegistry
    fun embeddingProvider(): EmbeddingProvider
    fun chatModelProvider(): ChatModelProvider
    fun vectorRepository(): VectorRepository
    fun summarizationEngine(): SummarizationEngine
    fun ragEngine(): RagEngine
}
