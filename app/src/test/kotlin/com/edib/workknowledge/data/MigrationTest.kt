package com.edib.workknowledge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * Exercises the real v1 -> v2 SQL against a real SQLite file.
 *
 * The migration previously shipped a malformed FTS4 option chain
 * (`notindexed=tokenize=unicode61`). That only surfaced as a crash when the
 * app opened an existing database on a device, because Room's own migration
 * runner is not reachable from a plain JVM test. Running the same statements
 * through SQLite directly catches the syntax error here instead.
 *
 * The statements are read from the production source of truth
 * ([MigrationScripts]) so this test cannot drift from what actually ships.
 */
class MigrationTest {

    @Test
    fun `migration sql applies cleanly to a v1 schema`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { st ->
                V1_SCHEMA.statements.forEach { st.execute(it) }

                // Same statements the app runs on upgrade.
                MigrationScripts.ADD_COLUMNS.forEach { st.execute(it) }
                MigrationScripts.BACKFILL.forEach { st.execute(it) }
                st.execute(MigrationScripts.CREATE_FTS)
                MigrationScripts.POPULATE_FTS.forEach { st.execute(it) }

                // Denormalised columns were backfilled from the parent tables.
                st.executeQuery("SELECT filename, projectName FROM chunks").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("WK_Runtime_Test.pdf", rs.getString(1))
                    assertEquals("WK Runtime Verification", rs.getString(2))
                }

                // The index was populated and is queryable.
                st.executeQuery("SELECT COUNT(*) FROM chunks_fts").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `fts table declares the tokenizer as a real option`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { st ->
                st.execute(MigrationScripts.CREATE_FTS)
                st.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE name = 'chunks_fts'"
                ).use { rs ->
                    assertTrue(rs.next())
                    val sql = rs.getString(1)
                    assertNotNull(sql)
                    assertTrue(
                        "tokenizer must be a standalone option; a malformed chain produced " +
                            "\"no such column: tokenize=unicode61\" on device",
                        sql.contains("tokenize=unicode61")
                    )
                    assertTrue(
                        "tokenizer must not be swallowed by a notindexed argument",
                        !sql.contains("notindexed")
                    )
                }
            }
        }
    }

    @Test
    fun `fts accepts a quoted identifier match after migration`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { st ->
                V1_SCHEMA.statements.forEach { st.execute(it) }
                st.execute(MigrationScripts.CREATE_FTS)
                st.execute(
                    "INSERT INTO chunks_fts(rowid,content,filename,projectName,sheetName) " +
                        "VALUES (1,'Purchase Request: PR-023 Status: Approved'," +
                        "'WK_Runtime_Test.pdf','WK Runtime Verification','')"
                )
                st.executeQuery(
                    "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH " +
                        "'\"PR\" AND \"023\"*'"
                ).use { rs ->
                    assertTrue("a quoted identifier query must match", rs.next())
                }
            }
        }
    }

    private object V1_SCHEMA {
        val statements = listOf(
            "CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, description TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, isArchived INTEGER NOT NULL)",
            "CREATE TABLE documents (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "projectId INTEGER NOT NULL, filename TEXT NOT NULL, originalUri TEXT NOT NULL, " +
                "mimeType TEXT NOT NULL, fileSize INTEGER NOT NULL, sha256 TEXT NOT NULL, " +
                "pageCount INTEGER NOT NULL, sheetCount INTEGER NOT NULL, status TEXT NOT NULL, " +
                "errorMessage TEXT, importedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                "revisionOf INTEGER)",
            "CREATE TABLE chunks (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "projectId INTEGER NOT NULL, documentId INTEGER NOT NULL, " +
                "chunkIndex INTEGER NOT NULL, content TEXT NOT NULL, embedding BLOB, " +
                "pageNumber INTEGER, sheetName TEXT, rowStart INTEGER, rowEnd INTEGER, " +
                "cellRange TEXT, metadataJson TEXT NOT NULL, createdAt INTEGER NOT NULL)",
            "INSERT INTO projects (id,name,description,createdAt,updatedAt,isArchived) " +
                "VALUES (1,'WK Runtime Verification','',0,0,0)",
            "INSERT INTO documents (id,projectId,filename,originalUri,mimeType,fileSize," +
                "sha256,pageCount,sheetCount,status,errorMessage,importedAt,updatedAt,revisionOf) " +
                "VALUES (1,1,'WK_Runtime_Test.pdf','content://x','application/pdf',1323," +
                "'abc',3,0,'READY',NULL,0,0,NULL)",
            "INSERT INTO chunks (projectId,documentId,chunkIndex,content,pageNumber," +
                "metadataJson,createdAt) VALUES " +
                "(1,1,0,'Purchase Request: PR-023 Status: Approved',3,'{}',0)"
        )
    }
}
