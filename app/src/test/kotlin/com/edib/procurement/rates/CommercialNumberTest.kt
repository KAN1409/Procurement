package com.edib.procurement.rates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommercialNumberTest {
    @Test
    fun parsesArabicIndicAndPersianDigits() {
        assertEquals(800.0, parseCommercialNumber("٨٠٠") ?: Double.NaN, 0.0)
        assertEquals(800.0, parseCommercialNumber("۸۰۰") ?: Double.NaN, 0.0)
        assertEquals(1250.5, parseCommercialNumber("١٬٢٥٠٫٥") ?: Double.NaN, 0.0)
        assertEquals(1250.5, parseCommercialNumber("1,250.5") ?: Double.NaN, 0.0)
    }

    @Test
    fun invalidTextIsNotInventedAsNumber() {
        assertNull(parseCommercialNumber("ثمانمائة"))
        assertNull(parseCommercialNumber(""))
    }
}
