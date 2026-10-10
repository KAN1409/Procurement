package com.edib.procurement.aipack

/**
 * Renders Excel date cells.
 *
 * A date in a spreadsheet is not stored as a date: the cell holds a serial
 * number and the DISPLAY format comes from the cell's style. A genuine
 * procurement workbook therefore contained cells reading `43741`, which is
 * meaningless to anyone reading the pack.
 *
 * Two rules matter and are enforced here:
 *
 *  - ONLY cells whose number format is actually a date format are converted. A
 *    number that merely looks like a serial, a quantity, a rate or a currency
 *    amount must be left exactly as it is.
 *  - The serial is kept alongside the rendered date, so the conversion is
 *    lossless and auditable.
 */
object ExcelDateFormat {

    /**
     * Built-in number-format ids that Excel reserves for dates and times.
     *
     * 14-22 are date/time, 27-36 and 50-58 are the East-Asian date variants,
     * 45-47 are elapsed-time formats.
     */
    private val BUILTIN_DATE_IDS = setOf(
        14, 15, 16, 17, 18, 19, 20, 21, 22,
        27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
        45, 46, 47,
        50, 51, 52, 53, 54, 55, 56, 57, 58
    )

    /** True when this custom format code describes a date and/or time. */
    fun isDateFormatCode(code: String): Boolean {
        if (code.isBlank()) return false
        // Strip literal text, colour/condition brackets and escaped characters,
        // so only the format tokens are examined.
        var s = code
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> {
                    i++
                    while (i < s.length && s[i] != '"') i++
                    i++
                }
                c == '[' -> {
                    while (i < s.length && s[i] != ']') i++
                    i++
                }
                c == '\\' -> i += 2
                else -> {
                    out.append(c); i++
                }
            }
        }
        s = out.toString()
        // "General" is explicitly NOT a date.
        if (s.trim().equals("General", ignoreCase = true)) return false
        // Date/time tokens: y m d h s, plus AM/PM.
        return Regex("[ymdhs]", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            s.contains("AM/PM", ignoreCase = true) ||
            s.contains("A/P", ignoreCase = true)
    }

    /** True when a built-in format id is a date/time format. */
    fun isBuiltinDateId(id: Int): Boolean = id in BUILTIN_DATE_IDS

    /**
     * Converts an Excel serial to an ISO date, honouring the 1900 date system.
     *
     * Excel's default epoch is 1899-12-30. It also has the famous 1900-02-29
     * that never existed: serials from 60 onward are shifted by one day, which
     * is exactly why a naive epoch of 1899-12-31 is a day out for every modern
     * date.
     *
     * @param hasTime true when the value has a fractional part, so the time is
     *        rendered as well.
     */
    fun serialToIso(serial: Long, hasTime: Boolean = false): String? {
        if (serial < 0) return null
        val base = dateOf(serial) ?: return null
        return if (!hasTime) base.toString() else "${base}T00:00:00"
    }

    /** True when a serial carries a time-of-day fraction. */
    fun hasTimeFraction(value: Double): Boolean {
        val frac = value - Math.floor(value)
        // Excel's time resolution is a second. A half day is noon, so the
        // threshold has to sit BELOW 0.5 or the most common time of day would be
        // silently dropped.
        return frac > (0.5 / 86_400.0)
    }

    /**
     * The value to write for a date-formatted numeric cell.
     *
     * Rendered ISO first, then the untouched serial in brackets, so a reader can
     * verify the conversion or recover the exact stored number.
     */
    fun renderDateCell(value: Double): String? {
        val serial = Math.floor(value).toLong()
        val hasTime = hasTimeFraction(value)
        // The time of day comes from the fractional part. Passing only the whole
        // serial would silently render every date as midnight, which is how a
        // half-day value came out as 00:00:00.
        val iso = renderIso(serial, hasTime, value) ?: return null
        val serialText = if (value == serial.toDouble()) serial.toString()
        else "%.5f".format(value)
        return "$iso [excel-serial: $serialText]"
    }

    /** ISO date, with the time taken from [value]'s fraction when [hasTime]. */
    private fun renderIso(serial: Long, hasTime: Boolean, value: Double): String? {
        val base = dateOf(serial) ?: return null
        if (!hasTime) return base.toString()
        val frac = value - Math.floor(value)
        val secs = (frac * 86_400.0).toLong().coerceIn(0L, 86_399L)
        val t = java.time.LocalTime.ofSecondOfDay(secs)
        return "${base}T${"%02d:%02d:%02d".format(t.hour, t.minute, t.second)}"
    }

    private fun dateOf(serial: Long): java.time.LocalDate? {
        if (serial < 0) return null
        // Excel's 1900 date system: serial 1 is 1900-01-01, and Excel inserts a
        // phantom 1900-02-29 at serial 60 that never existed. Everything from
        // 61 onwards therefore reads against the 1899-12-30 epoch, while the
        // few serials before the phantom day read against 1899-12-31.
        //
        // The pre-61 range is the only ambiguous part of the format and real
        // business data sits far above it; it is handled for completeness.
        return runCatching {
            // Serial 1 is 1900-01-01, so the pre-phantom epoch is
            // 1899-12-31 advanced by (serial - 1). At serial 60 Excel shows the
            // phantom 29 February, which has no calendar equivalent, so 60 and
            // above are read from the 1899-12-30 epoch where serial 1 is the
            // 1900-01-01 that users actually see.
            when {
                // Excel's serial 60 is the non-existent 1900-02-29. There is
                // no valid LocalDate for it, so preserve monotonic business
                // dates by folding it onto the real previous day.
                serial == 60L -> java.time.LocalDate.of(1900, 2, 28)
                serial < 60L -> java.time.LocalDate.of(1899, 12, 31).plusDays(serial)
                else -> java.time.LocalDate.of(1899, 12, 30).plusDays(serial)
            }
        }.getOrNull()
    }
}
