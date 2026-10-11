package com.edib.procurement.rates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateIdentityGuardTest {
    @Test fun gypsumCeilingAccessoriesTailIsBlocked() {
        val item = "الإكسسوارات من إنتاج كناوف او جودين ميتال بسمك لا يقل عن 0.5 مم"
        val evidence = "بالمتر المسطح - توريد وتركيب أسقف معلقة من الواح الجبس الأخضر المقاوم للرطوبة لزوم الحمام طبقاً للرسومات والمواصفات الفنية شاملة جميع القطاعات و" + item + " 720 740 532,800.00"
        assertTrue(CandidateIdentityGuard.blocksUnchangedItem(item, item, evidence))
        assertFalse(CandidateIdentityGuard.blocksUnchangedItem("توريد وتركيب أسقف معلقة من الواح الجبس الأخضر", item, evidence))
    }

    @Test fun fullDescriptionAndUnrelatedEvidenceRemainUnblocked() {
        val item = "بالمتر المسطح - توريد وتركيب أسقف معلقة من الواح الجبس الأخضر المقاوم للرطوبة"
        assertFalse(CandidateIdentityGuard.blocksUnchangedItem(item, item, item + " 720 740 532800"))
        assertFalse(CandidateIdentityGuard.blocksUnchangedItem("رخام جلالة", "رخام جلالة", "رخام جلالة 20 800"))
    }

    @Test fun whitespaceNormalizationDoesNotBypassProtection() {
        val item = "الإكسسوارات من إنتاج كناوف او جودين ميتال بسمك لا يقل عن 0.5 مم"
        val evidence = "توريد وتركيب أسقف معلقة من الواح الجبس الأخضر المقاوم للرطوبة شاملة جميع القطاعات والمستلزمات والتشطيبات والمواد اللازمة للتنفيذ " + item
        assertTrue(CandidateIdentityGuard.blocksUnchangedItem("  الإكسسوارات   من إنتاج كناوف او جودين ميتال بسمك لا يقل عن 0.5 مم ", item, evidence))
    }
}
