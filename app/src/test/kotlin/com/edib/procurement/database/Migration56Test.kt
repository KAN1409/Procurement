package com.edib.procurement.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration56Test {

    private lateinit var db: ProcurementDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.databaseBuilder(context, ProcurementDatabase::class.java, "rates-test.db")
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
    fun rateTableAndComparatorIndexExist() = runBlocking(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase

        sql.query("PRAGMA table_info(rate_observation)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue(columns.contains("itemRaw"))
            assertTrue(columns.contains("itemNormalized"))
            assertTrue(columns.contains("unit"))
            assertTrue(columns.contains("scope"))
            assertTrue(columns.contains("status"))
            assertTrue(columns.contains("sourceDocumentId"))
            assertTrue(columns.contains("evidenceLocator"))
        }

        sql.query("PRAGMA index_list(rate_observation)").use { cursor ->
            val indexes = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indexes += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue(
                indexes.contains("index_rate_observation_itemNormalized_unit_scope_status")
            )
        }
    }

    @Test
    fun rateCandidateTableExistsAndStaysSeparateFromTrustedRates() = runBlocking(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        sql.query("PRAGMA table_info(rate_candidate)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue(columns.contains("sourceKey"))
            assertTrue(columns.contains("evidenceText"))
            assertTrue(columns.contains("status"))
            assertTrue(columns.contains("confirmedRateId"))
        }
        assertEquals(0, db.rateDao().ratesFlow("").first().size)
        assertEquals(0, db.rateCandidateDao().pendingCountFlow().first())
    }

    @Test
    fun rateDaoPersistsContextNotJustNumber() = runBlocking(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = db.rateDao().insert(
            RateObservationEntity(
                itemRaw = "وزرة رخام 15 سم",
                itemNormalized = normalizeRateItem("وزرة رخام 15 سم"),
                dimensions = "15 cm",
                unit = "lm",
                scope = RateScope.SUPPLY_INSTALL,
                contractorRaw = "عبد المقصود",
                projectRaw = "Test Project",
                location = "Lobby",
                quantity = 25.0,
                rate = 1200.0,
                currency = "EGP",
                status = RateStatus.OBSERVED,
                observedDate = now,
                createdAt = now,
                updatedAt = now
            )
        )

        assertTrue(id > 0)
        val rows = db.rateDao().ratesFlow("رخام").first()
        assertEquals(1, rows.size)
        assertEquals("lm", rows.single().unit)
        assertEquals(RateScope.SUPPLY_INSTALL, rows.single().scope)
        assertEquals(1200.0, rows.single().rate, 0.0)
        assertEquals("عبد المقصود", rows.single().contractorName)
    }
}
