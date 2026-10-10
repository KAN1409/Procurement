package com.edib.procurement.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RateNormalizationTest {

    @Test
    fun conservativeItemNormalizationKeepsCommercialDifferences() {
        val ten = normalizeRateItem("  وزرة   رخام 10 سم  ")
        val fifteen = normalizeRateItem("وزرة رخام 15 سم")

        assertEquals("وزرة رخام 10 سم", ten)
        assertEquals("وزرة رخام 15 سم", fifteen)
        assertNotEquals(ten, fifteen)
    }

    @Test
    fun normalizationDoesNotInventSynonymsOrUnits() {
        assertEquals("marble skirting", normalizeRateItem("Marble   Skirting"))
        assertEquals("lm", normalizeRateUnit(" LM "))
        assertEquals("m²", normalizeRateUnit("m²"))
    }
}
