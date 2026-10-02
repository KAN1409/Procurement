package com.edib.procurement.followup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Header mapping.
 *
 * Nothing may depend on a column position: the reference workbook could gain,
 * lose or reorder columns in the next version.
 */
class HeaderMapperTest {

    @Test
    fun `the reference workbook headers map to the expected fields`() {
        // Exactly the header row of the attached workbook. The blank spacer
        // columns (I and S) are absent, which is what a real parse produces.
        val header = mapOf(
            1 to "No.", 2 to "Project ", 3 to "Area", 4 to "Related to ..",
            5 to "Code", 6 to "hyper link for PR", 7 to "hyper link for order",
            9 to "NEW Company", 10 to "Order Description",
            11 to "PR Date ", 12 to "Order  Date ", 13 to "PR WITH ENG.",
            14 to "Notes", 15 to "Financial Received Date ",
            16 to "Final Approval Date", 17 to "Check ",
            19 to "OLD Company till (14/MAY/2023)"
        )

        val map = HeaderMapper.map(header)

        assertEquals(FollowUpField.NUMBER, map.ref(FollowUpField.NUMBER)?.field)
        assertEquals(FollowUpField.PROJECT, map.ref(FollowUpField.PROJECT)?.field)
        assertEquals(FollowUpField.AREA, map.ref(FollowUpField.AREA)?.field)
        assertEquals(FollowUpField.RELATED_TO, map.ref(FollowUpField.RELATED_TO)?.field)
        assertEquals(FollowUpField.CODE, map.ref(FollowUpField.CODE)?.field)
        assertEquals(FollowUpField.PR_PATH, map.ref(FollowUpField.PR_PATH)?.field)
        assertEquals(FollowUpField.ORDER_PATH, map.ref(FollowUpField.ORDER_PATH)?.field)
        assertEquals(FollowUpField.COMPANY, map.ref(FollowUpField.COMPANY)?.field)
        assertEquals(FollowUpField.DESCRIPTION, map.ref(FollowUpField.DESCRIPTION)?.field)
        assertEquals(FollowUpField.PR_DATE, map.ref(FollowUpField.PR_DATE)?.field)
        assertEquals(FollowUpField.ORDER_DATE, map.ref(FollowUpField.ORDER_DATE)?.field)
        assertEquals(FollowUpField.PR_WITH_ENGINEER, map.ref(FollowUpField.PR_WITH_ENGINEER)?.field)
        assertEquals(FollowUpField.NOTES, map.ref(FollowUpField.NOTES)?.field)
        assertEquals(FollowUpField.FINANCIAL_RECEIVED_DATE, map.ref(FollowUpField.FINANCIAL_RECEIVED_DATE)?.field)
        assertEquals(FollowUpField.FINAL_APPROVAL_DATE, map.ref(FollowUpField.FINAL_APPROVAL_DATE)?.field)
        assertEquals(FollowUpField.CHECK, map.ref(FollowUpField.CHECK)?.field)
        assertEquals(FollowUpField.OLD_COMPANY, map.ref(FollowUpField.OLD_COMPANY)?.field)
    }

    @Test
    fun `whitespace and casing differences resolve to the same field`() {
        assertEquals(FollowUpField.PROJECT, HeaderMapper.resolveField(TextNormalizer.normalize("Project")))
        assertEquals(FollowUpField.PROJECT, HeaderMapper.resolveField(TextNormalizer.normalize("PROJECT")))
        assertEquals(FollowUpField.PROJECT, HeaderMapper.resolveField(TextNormalizer.normalize("  Project  ")))
    }

    @Test
    fun `columns may move without changing the mapping`() {
        val moved = mapOf(
            0 to "Code", 1 to "Project", 2 to "NEW Company", 3 to "Order Description"
        )
        val map = HeaderMapper.map(moved)
        assertEquals(0, map.column(FollowUpField.CODE))
        assertEquals(1, map.column(FollowUpField.PROJECT))
        assertEquals(2, map.column(FollowUpField.COMPANY))
    }

    @Test
    fun `unknown columns are retained rather than dropped`() {
        val map = HeaderMapper.map(
            mapOf(
                0 to "Project", 1 to "Code", 2 to "Cost Centre", 3 to "Buyer Email"
            )
        )
        val unknownHeaders = map.unknownColumns.map { it.headerLabel }
        assertTrue(unknownHeaders.contains("Cost Centre"))
        assertTrue(unknownHeaders.contains("Buyer Email"))
        assertEquals(2, map.unknownColumns.size)
    }

    @Test
    fun `a duplicate column claiming the same field does not overwrite the first`() {
        val map = HeaderMapper.map(mapOf(0 to "Code", 1 to "Order Code"))
        assertEquals(0, map.column(FollowUpField.CODE))
        // The second one is still there, just unmapped.
        assertEquals(FollowUpField.UNKNOWN, map.refAt(1)?.field)
        assertEquals("Order Code", map.refAt(1)?.headerLabel)
    }

    @Test
    fun `a sheet with no recognisable header is flagged as a fallback`() {
        val map = HeaderMapper.map(mapOf(0 to "أ", 1 to "ب", 2 to "ج"))
        assertTrue(map.usedFallback)
    }

    @Test
    fun `a longer qualifier does not steal the description column`() {
        // "Order Description" must win over the bare "description" alias.
        assertEquals(FollowUpField.DESCRIPTION, HeaderMapper.resolveField("ORDER DESCRIPTION"))
        assertEquals(FollowUpField.PR_DATE, HeaderMapper.resolveField("PR DATE"))
    }

    @Test
    fun `columns outside the used range simply resolve to null`() {
        val map = HeaderMapper.map(mapOf(0 to "Project", 1 to "Code"))
        assertNull(map.column(FollowUpField.OLD_COMPANY))
        assertNotNull(map.column(FollowUpField.PROJECT))
    }
}
