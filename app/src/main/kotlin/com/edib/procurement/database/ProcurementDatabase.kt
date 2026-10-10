package com.edib.procurement.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Its own database file, deliberately separate from the pre-existing
 * `workknowledge.db` so the two features cannot corrupt each other.
 *
 * There is no destructive fallback: manual links, tags and contractor
 * corrections are the user's data and must survive an app upgrade. Every schema
 * change goes through [Migrations].
 */
@Database(
    entities = [
        FollowUpImportEntity::class,
        ProjectEntity::class,
        FollowUpGroupEntity::class,
        FollowUpEntryEntity::class,
        ContractorEntity::class,
        ContractorAliasEntity::class,
        DocumentEntity::class,
        DocumentLinkEntity::class,
        UserTagEntity::class,
        AppSettingEntity::class,
        AiPackEntity::class,
        AiPackSourceEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class ProcurementDatabase : RoomDatabase() {
    abstract fun followUpDao(): FollowUpDao
    abstract fun contractorDao(): ContractorDao
    abstract fun documentDao(): DocumentDao
    abstract fun packDao(): PackDao
    abstract fun aiPackDao(): AiPackDao

    companion object {
        @Volatile
        private var instance: ProcurementDatabase? = null

        fun getInstance(context: Context): ProcurementDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProcurementDatabase::class.java,
                    "procurement.db"
                )
                    .addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
