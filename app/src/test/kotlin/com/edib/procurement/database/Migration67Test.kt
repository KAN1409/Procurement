package com.edib.procurement.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration67Test {

    private lateinit var db: ProcurementDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.databaseBuilder(context, ProcurementDatabase::class.java, "rate-candidates-test.db")
            .addMigrations(
                Migrations.MIGRATION_1_2,
                Migrations.MIGRATION_2_3,
                Migrations.MIGRATION_3_4,
                Migrations.MIGRATION_4_5,
                Migrations.MIGRATION_5_6,
                Migrations.MIGRATION_6_7
            )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun candidateTableAndIndexesExist() {
        val sql = db.openHelper.writableDatabase
        sql.query("PRAGMA table_info(rate_candidate)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue(columns.contains("sourceKey"))
            assertTrue(columns.contains("sourceDocumentId"))
            assertTrue(columns.contains("itemRaw"))
            assertTrue(columns.contains("rate"))
            assertTrue(columns.contains("currency"))
            assertTrue(columns.contains("evidenceText"))
            assertTrue(columns.contains("status"))
            assertTrue(columns.contains("confirmedRateId"))
        }

        sql.query("PRAGMA index_list(rate_candidate)").use { cursor ->
            val indexes = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indexes += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue(indexes.contains("index_rate_candidate_sourceKey"))
            assertTrue(indexes.contains("index_rate_candidate_status"))
        }
    }
}
