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

    @Test fun realPremierArabicOrderHeadingExtractsDateAndNumber() {
        val result = PoMetadataReader.extract(source("امر توريد / رقم (MMH2-GR-AR-86-25)\nتحريرا في :17/11/2025\nإسم المورد محجوب للتجارة والاستيراد"))
        assertEquals("MMH2-GR-AR-86-25", result.number)
        assertEquals(LocalDate.of(2025, 11, 17), result.date)
    }

    @Test fun realPremierRevisedOrderUsesItsOwnDate() {
        val result = PoMetadataReader.extract(source("امر توريد / رقم B (MMH2-GR-AR-86-25)\nتحريرا في :25/11/2025"))
        assertEquals(LocalDate.of(2025, 11, 25), result.date)
        assertEquals("MMH2-GR-AR-86-25", result.number)
    }

    @Test fun laterOrderSortDateAndActualInternalIdentifier() {
        val result = PoMetadataReader.extract(source("امر توريد / رقم (MMH2-HS-AR-88-25)\nتحريرا في :17/11/2025"))
        assertEquals("MMH2-HS-AR-88-25", result.number)
        assertEquals(LocalDate.of(2025, 11, 17), result.date)
    }

    @Test fun realDecemberOrderDate() {
        val result = PoMetadataReader.extract(source("امر توريد / رقم (MMH2-GR-AR-94-25)\nتحريرا في :1/12/2025"))
        assertEquals(LocalDate.of(2025, 12, 1), result.date)
    }

    @Test fun realReorderedArabicDateAndInstallationOrder() {
        val result = PoMetadataReader.extract(source("أمر تركيب / B رقم (MMH2-GR-AR-0238-26)\\nتحريرا في 07/2026/ 6 :"))
        assertEquals("MMH2-GR-AR-0238-26", result.number)
        assertEquals(LocalDate.of(2026, 7, 6), result.date)
    }

    @Test fun realJuneOrdersAreDistinctFromJulyRevision() {
        val result = PoMetadataReader.extract(source("أمر توريد / A رقم (MMH2-GR-AR-0238-26)\\nتحريرا في 06/2026/ 4 :"))
        assertEquals(LocalDate.of(2026, 6, 4), result.date)
    }

    @Test fun realFebruaryOrderHandlesReversedDateRendering() {
        val result = PoMetadataReader.extract(source("أمر توريد / رقم (MMH2-GR-AR-0131-26)\\nتحريرا في 02/2026/ 12 :"))
        assertEquals(LocalDate.of(2026, 2, 12), result.date)
    }

    @Test fun wordMonthDateFromNewDocx() {
        val result = PoMetadataReader.extract(source("16 يونيو 2025 تحريرًا في MMH2-GR-AR-0029-25a أمر توريد رقم"))
        assertEquals("MMH2-GR-AR-0029-25a", result.number)
    }

    @Test fun invalidCalendarDatesAreRejected() {
        val result = PoMetadataReader.extract(source("PO Date: 31/02/2026"))
        assertNull(result.date)
    }
}
