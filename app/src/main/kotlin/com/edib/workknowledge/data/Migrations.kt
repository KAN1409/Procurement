package com.edib.workknowledge.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: adds the full-text search index.
 *
 * Two new columns are added to `chunks` (denormalised filename and project
 * name), a fresh FTS4 table is created, and the table is populated from the
 * existing rows so a user upgrading with data already indexed does not lose
 * search.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chunks ADD COLUMN filename TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE chunks ADD COLUMN projectName TEXT NOT NULL DEFAULT ''")

        // Backfill the denormalised columns from the existing tables.
        db.execSQL(
            """
            UPDATE chunks SET filename = COALESCE((
                SELECT d.filename FROM documents d WHERE d.id = chunks.documentId
            ), '')
            """.trimIndent()
        )
        db.execSQL(
            """
            UPDATE chunks SET projectName = COALESCE((
                SELECT p.name FROM projects p WHERE p.id = chunks.projectId
            ), '')
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts4(
                content,
                filename,
                projectName,
                sheetName,
                notindexed=tokenize=unicode61
            )
            """.trimIndent()
        )

        // Populate the index for anything already ingested.
        db.execSQL(
            """
            INSERT INTO chunks_fts(rowid, content, filename, projectName, sheetName)
            SELECT id, content, filename, projectName, COALESCE(sheetName, '')
            FROM chunks
            """.trimIndent()
        )
    }
}
