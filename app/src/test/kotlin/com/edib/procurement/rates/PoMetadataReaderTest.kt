package com.edib.procurement.rates

import com.edib.procurement.database.PoSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PoMetadataReaderTest {
    private fun source(text: String) = PoSourceText(17L, text)

    @Test fun explicitPoDateAndNumberAreShown() {
        val result = PoMetadataReader.extract(source("PO No: ALMBT-UN-FU-0049-26\nPO Date: 12/10/2026\nItem\tRate"))
        assertEquals("ALMBT-UN-FU-0049-26", result.number)
        assertEquals(LocalDate.of(2026, 10, 12), result.date)
    }

    @Test fun unrelatedDatesAndFileTimestampsCannotBecomePoDates() {
        val result = PoMetadataReader.extract(source("Revision date: 11/10/2026\nDelivery: 15/10/2026\nRate 1200"))
        assertNull(result.date)
        assertNull(result.number)
    }

    @Test fun contradictoryPoDatesRemainUnknown() {
        val result = PoMetadataReader.extract(source("PO Date: 12/10/2026\nPO Date: 13/10/2026"))
        assertNull(result.date)
    }

    @Test fun arabicDateLabelAndDigitsAreAccepted() {
        val result = PoMetadataReader.extract(source("تاريخ أمر الإسناد: ١٢/١٠/٢٠٢٦"))
        assertEquals(LocalDate.of(2026, 10, 12), result.date)
    }

    @Test fun invalidCalendarDatesAreRejected() {
        val result = PoMetadataReader.extract(source("PO Date: 31/02/2026"))
        assertNull(result.date)
    }
}
