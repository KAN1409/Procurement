package com.edib.procurement.rates

import com.edib.procurement.database.PoSourceText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Explicitly labelled PO dates only. Never substitutes filesystem timestamps. */
data class PoMetadata(val number: String? = null, val date: LocalDate? = null, val evidence: String? = null)

object PoMetadataReader {
    private val dateLabel = Regex("""(?i)(?:\bpo\s*date\b|\bpurchase\s*order\s*date\b|تاريخ\s*(?:أمر\s*(?:الإسناد|الاسناد|التوريد|الشراء)|الـ?\s*po))\s*[:：-]?\s*(\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4})""")
    private val numberLabel = Regex("""(?i)(?:\bpo\s*(?:no\.?|number|#)\b|رقم\s*أمر\s*(?:الإسناد|الاسناد|التوريد|الشراء))\s*[:：#-]?\s*([A-Z0-9][A-Z0-9/-]{3,})""")
    private val formats = listOf("uuuu-M-d", "d-M-uuuu", "d/M/uuuu", "uuuu/M/d", "d.M.uuuu", "uuuu.M.d")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }

    fun extract(source: PoSourceText): PoMetadata {
        // Only inspect document heading area; dates in BOQ items aren't PO issue dates.
        val heading = source.extractedText.lineSequence().take(45).joinToString("\n")
        val numbers = numberLabel.findAll(heading).map { it.groupValues[1] }.distinct().toList()
        val dates = dateLabel.findAll(heading).mapNotNull { match ->
            val normalized = match.groupValues[1].map { c -> when (c) {
                in '٠'..'٩' -> ('0'.code + c.code - '٠'.code).toChar()
                in '۰'..'۹' -> ('0'.code + c.code - '۰'.code).toChar()
                else -> c
            }}.joinToString("")
            formats.firstNotNullOfOrNull { runCatching { LocalDate.parse(normalized, it) }.getOrNull() }
                ?.takeIf { it.year in 2000..2100 }?.let { it to match.value }
        }.distinctBy { it.first }.toList()
        return PoMetadata(
            number = numbers.singleOrNull(),
            date = dates.singleOrNull()?.first,
            evidence = dates.singleOrNull()?.second
        )
    }
}
