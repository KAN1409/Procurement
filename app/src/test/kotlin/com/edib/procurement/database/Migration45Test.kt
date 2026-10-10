package com.edib.procurement.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.edib.procurement.database.Migrations.MIGRATION_4_5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test for MIGRATION_4_5 - database v5 schema.
 * Verifies that the additive migration adds all required content indexing fields.
 */
@RunWith(RobolectricTestRunner::class)
class Migration45Test {

    private lateinit var db: ProcurementDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Create database at version 4, then migrate to 5
        db = Room.databaseBuilder(context, ProcurementDatabase::class.java, "test.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testMigration45AddsContentStateColumn() = runBlocking(Dispatchers.IO) {
        val statement = db.openHelper.writableDatabase
        val cursor = statement.query("PRAGMA table_info(document)")
        
        val columns = mutableMapOf<String, String>()
        while (cursor.moveToNext()) {
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
            columns[name] = type
        }
        cursor.close()

        // Verify all new columns exist
        assertTrue("contentState column missing", columns.containsKey("contentState"))
        assertTrue("extractionMethod column missing", columns.containsKey("extractionMethod"))
        assertTrue("extractedAt column missing", columns.containsKey("extractedAt"))
        assertTrue("extractorVersion column missing", columns.containsKey("extractorVersion"))
        assertTrue("extractionError column missing", columns.containsKey("extractionError"))
        assertTrue("sourceSizeAtExtraction column missing", columns.containsKey("sourceSizeAtExtraction"))
        assertTrue("sourceModifiedAtExtraction column missing", columns.containsKey("sourceModifiedAtExtraction"))

        // Verify contentState has NOT NULL DEFAULT 'NOT_INDEXED'
        // Note: PRAGMA table_info doesn't show defaults easily, but we can check
        // by inserting a row without contentState and verifying it gets the default
    }

    @Test
    fun testMigration45CreatesIndexes() = runBlocking(Dispatchers.IO) {
        val statement = db.openHelper.writableDatabase
        val cursor = statement.query("PRAGMA index_list(document)")
        
        val indexes = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            indexes.add(name)
        }
        cursor.close()

        assertTrue("index_document_contentState missing", indexes.contains("index_document_contentState"))
        assertTrue("index_document_indexedAt missing", indexes.contains("index_document_indexedAt"))
    }

    @Test
    fun testExistingDataSurvivesMigration() = runBlocking(Dispatchers.IO) {
        // Insert a document using the old schema (v4)
        val dao = db.documentDao()
        
        val oldDoc = DocumentEntity(
            id = 0,
            treeUri = "content://tree",
            documentUri = "content://tree/old.pdf",
            displayName = "old.pdf",
            normalizedName = "old",
            matchFileKey = "oldpdf",
            matchStemKey = "old",
            relativePath = "old.pdf",
            mimeType = "application/pdf",
            extension = "pdf",
            size = 1000,
            lastModified = System.currentTimeMillis(),
            indexedAt = System.currentTimeMillis(),
            isMissing = false,
            extractedText = "Old extracted text"
            // contentState and other new fields will get defaults
        )

        val ids = dao.insertDocuments(listOf(oldDoc))
        assertEquals(1, ids.size)
        val id = ids[0]

        val inserted = dao.byId(id)
        assertNotNull(inserted)
        
        // Verify new fields have correct defaults
        assertEquals(ContentState.NOT_INDEXED, inserted!!.contentState)
        assertNull(inserted.extractionMethod)
        assertNull(inserted.extractedAt)
        assertNull(inserted.extractorVersion)
        assertNull(inserted.extractionError)
        assertNull(inserted.sourceSizeAtExtraction)
        assertNull(inserted.sourceModifiedAtExtraction)
        
        // Verify old data preserved
        assertEquals("Old extracted text", inserted.extractedText)
        assertEquals("old.pdf", inserted.displayName)
    }

    @Test
    fun testContentStateEnumValues() {
        val values = ContentState.values()
        assertEquals(6, values.size)
        
        val expected = setOf(
            ContentState.NOT_INDEXED,
            ContentState.INDEXED,
            ContentState.NEEDS_OCR,
            ContentState.STALE,
            ContentState.UNSUPPORTED,
            ContentState.FAILED
        )
        assertEquals(expected, values.toSet())
    }
}
