package com.edib.procurement.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
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
        context.deleteDatabase("rate-candidates-test.db")
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

    @Test
    fun parserVersionInvalidationStalesOnlyPendingRows() = runBlocking {
        val dao = db.rateCandidateDao()
        val oldPending = candidate("old-pending", RateCandidateStatus.PENDING, "rate-candidate-1.0")
        val oldDismissed = candidate("old-dismissed", RateCandidateStatus.DISMISSED, "rate-candidate-1.0")
        val currentPending = candidate("current-pending", RateCandidateStatus.PENDING, "rate-candidate-1.1")

        val ids = dao.insertAll(listOf(oldPending, oldDismissed, currentPending))
        dao.markPendingFromOtherParserStale("rate-candidate-1.1")

        assertEquals(RateCandidateStatus.STALE, dao.byId(ids[0])!!.status)
        assertEquals(RateCandidateStatus.DISMISSED, dao.byId(ids[1])!!.status)
        assertEquals(RateCandidateStatus.PENDING, dao.byId(ids[2])!!.status)
    }

    private fun candidate(
        sourceKey: String,
        status: String,
        parserVersion: String
    ) = RateCandidateEntity(
        sourceKey = sourceKey,
        sourceFileNameSnapshot = "source.xlsx",
        sourceRelativePathSnapshot = "Rates/source.xlsx",
        sourceUriSnapshot = "content://source/$sourceKey",
        sourceModifiedAt = 100L,
        sourceExtension = "xlsx",
        itemRaw = "رخام",
        unitRaw = "m2",
        rate = 800.0,
        currency = "EGP",
        evidenceLocator = "line 1",
        evidenceText = "رخام m2 800 EGP",
        confidence = 80,
        detectionMethod = "TEST",
        parserVersion = parserVersion,
        status = status,
        createdAt = 100L
    )

}