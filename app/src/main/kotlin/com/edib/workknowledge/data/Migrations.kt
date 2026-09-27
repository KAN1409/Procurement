package com.edib.workknowledge.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The v1 -> v2 SQL, kept as data so tests can execute the exact statements the
 * app runs.
 *
 * They are written out individually rather than inline in [MIGRATION_1_2]
 * because a malformed FTS option chain here only shows up when an existing
 * database is opened on a device, which is far too late to notice.
 */
object MigrationScripts {
    val ADD_COLUMNS = listOf(
        "ALTER TABLE chunks ADD COLUMN filename TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE chunks ADD COLUMN projectName TEXT NOT NULL DEFAULT ''"
    )

    val BACKFILL = listOf(
        """
        UPDATE chunks SET filename = COALESCE((
            SELECT d.filename FROM documents d WHERE d.id = chunks.documentId
        ), '')
        """.trimIndent(),
        """
        UPDATE chunks SET projectName = COALESCE((
            SELECT p.name FROM projects p WHERE p.id = chunks.projectId
        ), '')
        """.trimIndent()
    )

    /**
     * `tokenize` must be its own option. Chaining it after `notindexed=`
     * produces a column literally named "tokenize=unicode61" and the whole
     * migration fails with SQLITE_ERROR.
     */
    const val CREATE_FTS =
        "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts4(" +
            "content, filename, projectName, sheetName, tokenize=unicode61)"

    val POPULATE_FTS = listOf(
        """
        INSERT INTO chunks_fts(rowid, content, filename, projectName, sheetName)
        SELECT id, content, filename, projectName, COALESCE(sheetName, '')
        FROM chunks
        """.trimIndent()
    )
}

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
        MigrationScripts.ADD_COLUMNS.forEach(db::execSQL)
        MigrationScripts.BACKFILL.forEach(db::execSQL)
        db.execSQL(MigrationScripts.CREATE_FTS)
        MigrationScripts.POPULATE_FTS.forEach(db::execSQL)
    }
}
