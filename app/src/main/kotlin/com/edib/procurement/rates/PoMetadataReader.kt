package com.edib.procurement.rates

import com.edib.procurement.database.PoSourceText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Explicitly labelled PO dates only. Never substitutes filesystem timestamps. */
data class PoMetadata(val number: String? = null, val date: LocalDate? = null, val evidence: String? = null)

object PoMetadataReader {
    private val dateLabel = Regex("""(?i)(?:\bpo\s*date\b|\bpurchase\s*order\s*date\b|تاريخ\s*(?:أمر\s*(?:الإسناد|الاسناد|التوريد|الشراء)|الـ?\s*po))\s*[:：-]?\s*([0-9٠-٩۰-۹]{1,4}[-/.][0-9٠-٩۰-۹]{1,2}[-/.][0-9٠-٩۰-۹]{1,4})""")
    private val numberLabel = Regex("""(?i)(?:\bpo\s*(?:no\.?|number|#)\b|رقم\s*أمر\s*(?:الإسناد|الاسناد|التوريد|الشراء))\s*[:：#-]?\s*([A-Z0-9][A-Z0-9/-]{3,})""")
    // Premier/Travco purchase-order header formats observed in real .doc sources.
    private val issuedOn = Regex("""(?:تحرير[اًاً]*\s*ف[يى]|تحرير\s*في)\s*[:：]?\s*([0-9٠-٩۰-۹]{1,2}[-/.][0-9٠-٩۰-۹]{1,2}[-/.][0-9٠-٩۰-۹]{4})""")
    private val arabicOrder = Regex("""(?:أمر|امر)\s*(?:توريد|إسناد|اسناد)\s*/?\s*(?:رقم)?\s*[/#:() ]*\s*(?:B\s*)?\(?\s*([A-Z0-9]+(?:-[A-Z0-9]+){2,})\s*\)?""", RegexOption.IGNORE_CASE)
    private val issueMonthYearDay = Regex("""(?:تحرير[اًاً]*\\s*ف[يى]|تحرير\\s*في)\\s*[:：]?\\s*([0-9٠-٩۰-۹]{1,2})/([0-9٠-٩۰-۹]{4})/\\s*([0-9٠-٩۰-۹]{1,2})""")
    private val issueWords = Regex("""(?:تحرير[اًاً]*\\s*ف[يى]|تحرير\\s*في)\\s*[:：]?\\s*([0-9٠-٩۰-۹]{1,2})\\s+(يناير|فبراير|مارس|أبريل|ابريل|مايو|يونيو|يوليو|أغسطس|اغسطس|سبتمبر|أكتوبر|اكتوبر|نوفمبر|ديسمبر)\\s+([0-9٠-٩۰-۹]{4})""")
    private val headerPoCode = Regex("""(?i)\\b(MMH[0-9]+-[A-Z]{2}-AR-[0-9]+-[0-9]{2}[A-Z]?)\\b""")
    private val formats = listOf("uuuu-M-d", "d-M-uuuu", "d/M/uuuu", "uuuu/M/d", "d.M.uuuu", "uuuu.M.d")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }

    fun extract(source: PoSourceText): PoMetadata {
        // Only inspect document heading area; dates in BOQ items aren't PO issue dates.
        val heading = source.extractedText.lineSequence().take(45).joinToString("\n")
        val headerLines = source.extractedText.lineSequence().take(4).toList()
        val firstHeader = headerLines.joinToString(" ")
        val labelled = (numberLabel.findAll(heading).map { it.groupValues[1] } +
            arabicOrder.findAll(firstHeader).map { it.groupValues[1] }).distinct().toList()
        val fallback = if (Regex("""(?:أمر|امر)\\s*(?:توريد|تركيب)""").containsMatchIn(firstHeader))
            headerPoCode.findAll(firstHeader).map { it.groupValues[1] }.distinct().toList() else emptyList()
        val numbers = if (labelled.isNotEmpty()) labelled else fallback
        val dates = (dateLabel.findAll(heading) + issuedOn.findAll(heading)).mapNotNull { match ->
            val normalized = match.groupValues[1].map { c -> when (c) {
                in '٠'..'٩' -> ('0'.code + c.code - '٠'.code).toChar()
                in '۰'..'۹' -> ('0'.code + c.code - '۰'.code).toChar()
                else -> c
            }}.joinToString("")
            formats.firstNotNullOfOrNull { runCatching { LocalDate.parse(normalized, it) }.getOrNull() }
                ?.takeIf { it.year in 2000..2100 }?.let { it to match.value }
        }.distinctBy { it.first }.toList()
        fun latin(v: String) = v.map { c -> when (c) {
            in '٠'..'٩' -> ('0'.code + c.code - '٠'.code).toChar()
            in '۰'..'۹' -> ('0'.code + c.code - '۰'.code).toChar()
            else -> c
        }}.joinToString("")
        val special = issueMonthYearDay.findAll(heading).mapNotNull { m ->
            val month = latin(m.groupValues[1]).toIntOrNull()
            val year = latin(m.groupValues[2]).toIntOrNull()
            val day = latin(m.groupValues[3]).toIntOrNull()
            if (month == null || year == null || day == null) null
            else runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { it to m.value }
        }.toList() + issueWords.findAll(heading).mapNotNull { m ->
            val month = listOf("يناير","فبراير","مارس","أبريل","ابريل","مايو","يونيو","يوليو","أغسطس","اغسطس","سبتمبر","أكتوبر","اكتوبر","نوفمبر","ديسمبر")
                .indexOf(m.groupValues[2]).let { idx -> if (idx < 0) null else when (idx) { 4 -> 4; 9 -> 8; 13 -> 10; 14 -> 11; else -> idx + 1 } }
            val day = latin(m.groupValues[1]).toIntOrNull()
            val year = latin(m.groupValues[3]).toIntOrNull()
            if (month == null || day == null || year == null) null
            else runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { it to m.value }
        }.toList()
        val uniqueDates = (dates + special).filter { it.first.year in 2000..2100 }.distinctBy { it.first }
        return PoMetadata(
            number = numbers.singleOrNull(),
            date = uniqueDates.singleOrNull()?.first,
            evidence = uniqueDates.singleOrNull()?.second
        )
    }
}
