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
    fun normalizationUsesOnlyControlledUnitAliases() {
        assertEquals("marble skirting", normalizeRateItem("Marble   Skirting"))
        assertEquals("lm", normalizeRateUnit(" LM "))
        assertEquals("lm", normalizeRateUnit("متر طولي"))
        assertEquals("m2", normalizeRateUnit("m2"))
        assertEquals("m2", normalizeRateUnit("m²"))
        assertEquals("m2", normalizeRateUnit("sqm"))
        assertEquals("m3", normalizeRateUnit("m³"))
        assertEquals("custom-unit", normalizeRateUnit("Custom-Unit"))
    }
}
