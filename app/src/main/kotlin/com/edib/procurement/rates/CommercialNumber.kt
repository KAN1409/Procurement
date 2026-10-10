package com.edib.procurement.rates

import java.util.Locale

/**
 * Normalizes Arabic-Indic and Persian digits plus common Arabic separators.
 *
 * Examples:
 * ٨٠٠ -> 800
 * ۸۰۰ -> 800
 * ١٬٢٥٠٫٥ -> 1250.5
 */
fun normalizeCommercialDigits(value: String): String =
    value.map { ch ->
        when (ch) {
            '٠', '۰' -> '0'
            '١', '۱' -> '1'
            '٢', '۲' -> '2'
            '٣', '۳' -> '3'
            '٤', '۴' -> '4'
            '٥', '۵' -> '5'
            '٦', '۶' -> '6'
            '٧', '۷' -> '7'
            '٨', '۸' -> '8'
            '٩', '۹' -> '9'
            '٫' -> '.'
            '٬' -> ','
            else -> ch
        }
    }.joinToString("")

/**
 * Parses a commercial number without guessing locale-dependent meanings.
 *
 * Commas are treated as grouping separators. Arabic decimal separator is
 * normalized to '.', and spaces/non-breaking spaces are ignored.
 */
fun parseCommercialNumber(value: String): Double? {
    val normalized = normalizeCommercialDigits(value)
        .replace("\u00A0", "")
        .replace(" ", "")
        .replace(",", "")
        .trim()
    if (normalized.isEmpty()) return null
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}

fun normalizedCurrency(value: String): String =
    value.trim().uppercase(Locale.ROOT).ifEmpty { "EGP" }
