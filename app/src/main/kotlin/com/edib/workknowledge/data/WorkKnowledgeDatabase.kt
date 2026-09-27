package com.edib.workknowledge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        ProjectEntity::class,
        DocumentEntity::class,
        ChunkEntity::class,
        ChunkFtsEntity::class,
        DocumentSummaryEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ProcessingJobEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(WorkKnowledgeConverters::class)
abstract class WorkKnowledgeDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun documentDao(): DocumentDao
    abstract fun chunkDao(): ChunkDao
    abstract fun searchDao(): SearchDao
    abstract fun documentSummaryDao(): DocumentSummaryDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun processingJobDao(): ProcessingJobDao

    companion object {
        @Volatile
        private var instance: WorkKnowledgeDatabase? = null

        fun getInstance(context: Context): WorkKnowledgeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WorkKnowledgeDatabase::class.java,
                    "workknowledge.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                    .also { instance = it }
            }
    }
}

class WorkKnowledgeConverters {
    @TypeConverter
    fun documentStatusToString(value: DocumentEntity.ProcessingStatus): String = value.name

    @TypeConverter
    fun stringToDocumentStatus(value: String): DocumentEntity.ProcessingStatus =
        runCatching { DocumentEntity.ProcessingStatus.valueOf(value) }
            .getOrDefault(DocumentEntity.ProcessingStatus.QUEUED)

    @TypeConverter
    fun scopeToString(value: ChatSessionEntity.ScopeType): String = value.name

    @TypeConverter
    fun stringToScope(value: String): ChatSessionEntity.ScopeType =
        runCatching { ChatSessionEntity.ScopeType.valueOf(value) }
            .getOrDefault(ChatSessionEntity.ScopeType.PROJECT)

    @TypeConverter
    fun roleToString(value: ChatMessageEntity.Role): String = value.name

    @TypeConverter
    fun stringToRole(value: String): ChatMessageEntity.Role =
        runCatching { ChatMessageEntity.Role.valueOf(value) }
            .getOrDefault(ChatMessageEntity.Role.ASSISTANT)

    @TypeConverter
    fun jobTypeToString(value: ProcessingJobEntity.JobType): String = value.name

    @TypeConverter
    fun stringToJobType(value: String): ProcessingJobEntity.JobType =
        runCatching { ProcessingJobEntity.JobType.valueOf(value) }
            .getOrDefault(ProcessingJobEntity.JobType.PARSE)

    @TypeConverter
    fun jobStatusToString(value: ProcessingJobEntity.JobStatus): String = value.name

    @TypeConverter
    fun stringToJobStatus(value: String): ProcessingJobEntity.JobStatus =
        runCatching { ProcessingJobEntity.JobStatus.valueOf(value) }
            .getOrDefault(ProcessingJobEntity.JobStatus.PENDING)
}
