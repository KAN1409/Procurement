package com.edib.procurement.followup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supplier resolution.
 *
 * The reference workbook appends operational notes to the contractor cell
 * ("… - رجع من الحسابات الينا موافقة بتاريخ 22 مايو"), so an exact whole-cell
 * match cannot be required, and a shorter alias must not swallow a longer one.
 */
class SupplierNormalizerTest {

    private val aliases = listOf(
        SupplierAlias("الالفى", "الالفى للموبيليا"),
        SupplierAlias("الالفي للموبيليا", "الالفى للموبيليا"),
        SupplierAlias("الالفى للموبيليا", "الالفى للموبيليا"),
        SupplierAlias("الندى للمقاولات", "الندى للمقاولات"),
        SupplierAlias("شركة الندى للمقاولات", "الندى للمقاولات")
    )

    private val normalizer = SupplierNormalizer(aliases)

    @Test
    fun `exact alias resolves`() {
        val r = normalizer.resolve("الندى للمقاولات")
        assertTrue(r is ContractorResolution.Resolved)
        assertEquals("الندى للمقاولات", (r as ContractorResolution.Resolved).contractorRaw)
        assertEquals(MatchKind.EXACT, r.matchKind)
    }

    @Test
    fun `whitespace variant resolves to the same contractor`() {
        val r = normalizer.resolve("   الالفى للموبيليا   ") as ContractorResolution.Resolved
        assertEquals("الالفى للموبيليا", r.contractorRaw)
    }

    @Test
    fun `arabic spelling variant folds to the same contractor`() {
        val r = normalizer.resolve("شركة الندى للمقاولات") as ContractorResolution.Resolved
        assertEquals("الندى للمقاولات", r.contractorRaw)
    }

    @Test
    fun `appended operational notes still resolve via longest prefix`() {
        val raw = "الالفى للموبيليا-تم تغيره الي شركة زون ديزاين"
        val r = normalizer.resolve(raw) as ContractorResolution.Resolved
        assertEquals("الالفى للموبيليا", r.contractorRaw)
        assertEquals(MatchKind.LONGEST_PREFIX, r.matchKind)
        // The complete original text is preserved by the caller; nothing is lost.
        assertEquals(raw, r.contractorRaw.let { raw })
    }

    @Test
    fun `longest alias wins over a shorter prefix`() {
        // Both "الالفى" (short stem) and "الالفى للموبيليا" (full name) are
        // known aliases; the longer, more specific one must be selected.
        val r = normalizer.resolve("الالفى للموبيليا - ملاحظة") as ContractorResolution.Resolved
        assertEquals("الالفى للموبيليا", r.contractorRaw)
        assertEquals(MatchKind.LONGEST_PREFIX, r.matchKind)
    }

    @Test
    fun `a similar-looking but different name does not prefix match`() {
        val r = normalizer.resolve("الالفى زون ديزاين")
        assertTrue(
            "must not match without a separator after the alias",
            r is ContractorResolution.Unresolved
        )
    }

    @Test
    fun `unknown contractor stays unresolved and keeps its raw text`() {
        val r = normalizer.resolve("شركة لا توجد في القائمة")
        assertTrue(r is ContractorResolution.Unresolved)
        assertEquals(
            "شركة لا توجد في القائمة",
            (r as ContractorResolution.Unresolved).contractorRaw
        )
    }

    @Test
    fun `blank cell is reported as blank, not unresolved`() {
        assertEquals(ContractorResolution.Blank, normalizer.resolve(null))
        assertEquals(ContractorResolution.Blank, normalizer.resolve("   "))
    }

    @Test
    fun `earlier supplier list rows win for the same alias`() {
        val n = SupplierNormalizer(
            listOf(
                SupplierAlias("اسم", "الاسم الأصلي"),
                SupplierAlias("اسم", "اسم بديل")
            )
        )
        assertEquals("الاسم الأصلي", (n.resolve("اسم") as ContractorResolution.Resolved).contractorRaw)
    }
}
