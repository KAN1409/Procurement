package com.edib.procurement.rates

import com.edib.procurement.database.RateCandidateEntity
import com.edib.procurement.database.RateCorpusDocument
import com.edib.procurement.database.RateScope
import com.edib.procurement.database.normalizeRateUnit
import java.security.MessageDigest
import java.util.Locale

object RateCandidateParser {
    const val PARSER_VERSION = "rate-candidate-1.1"
    private const val MAX_CANDIDATES_PER_DOCUMENT = 500

    data class HeaderMap(
        val description: Int?,
        val unit: Int?,
        val rate: Int?,
        val quantity: Int?,
        val currency: Int?
    )

    fun parse(document: RateCorpusDocument, now: Long): List<RateCandidateEntity> {
        val out = ArrayList<RateCandidateEntity>()
        var currentSheet: String? = null
        var header: HeaderMap? = null

        document.extractedText.lineSequence().forEachIndexed { index, rawLine ->
            if (out.size >= MAX_CANDIDATES_PER_DOCUMENT) return@forEachIndexed
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed

            parseSheetName(line)?.let {
                currentSheet = it
                header = null
                return@forEachIndexed
            }

            val cells = rawLine.split('	').map { it.trim() }
            val detectedHeader = if (cells.size >= 2) detectHeader(cells) else null
            if (detectedHeader != null) {
                header = detectedHeader
                return@forEachIndexed
            }

            val candidate = when {
                header != null && cells.size >= 2 ->
                    parseMappedRow(document, index, currentSheet, line, cells, header!!, now)
                else ->
                    parseHeuristicRow(document, index, currentSheet, line, cells, now)
            }
            if (candidate != null) out += candidate
        }
        return out
    }

    private fun parseMappedRow(
        document: RateCorpusDocument,
        lineIndex: Int,
        sheet: String?,
        evidence: String,
        cells: List<String>,
        map: HeaderMap,
        now: Long
    ): RateCandidateEntity? {
        val rateCell = map.rate?.let { cells.getOrNull(it) } ?: return null
        val rate = extractFirstNumber(rateCell)?.takeIf(::plausibleRate) ?: return null

        val item = map.description?.let { cells.getOrNull(it) }
            ?.takeIf(::looksLikeDescription)
            ?: longestDescriptionCell(cells, setOfNotNull(map.rate, map.unit, map.quantity, map.currency))
            ?: return null

        val unitRaw = map.unit?.let { cells.getOrNull(it) }?.takeIf { it.isNotBlank() }
        val quantity = map.quantity?.let { cells.getOrNull(it) }?.let(::extractFirstNumber)
        val currency = map.currency?.let { cells.getOrNull(it) }?.let(::detectCurrency)
            ?: detectCurrency(evidence)

        val confidence = when {
            map.description != null && map.unit != null -> 94
            map.description != null -> 88
            unitRaw != null -> 82
            else -> 76
        }

        return buildCandidate(
            document = document,
            lineIndex = lineIndex,
            sheet = sheet,
            evidence = evidence,
            item = item,
            unitRaw = unitRaw,
            quantity = quantity,
            rate = rate,
            currency = currency,
            inferredScope = inferScope(evidence),
            confidence = confidence,
            method = "TABLE_HEADER",
            now = now
        )
    }

    private data class HeuristicRateEvidence(
        val rate: Double,
        val confidence: Int,
        val method: String
    )

    private fun parseHeuristicRow(
        document: RateCorpusDocument,
        lineIndex: Int,
        sheet: String?,
        evidence: String,
        cells: List<String>,
        now: Long
    ): RateCandidateEntity? {
        if (evidence.length < 6 || evidence.length > 700) return null

        val unit = detectUnit(evidence)
        val currency = detectCurrency(evidence)
        val rateEvidence = findHeuristicRate(evidence, cells, unit, currency) ?: return null

        val excluded = cells.indices.filterTo(mutableSetOf()) { idx ->
            val c = cells[idx]
            detectUnit(c) != null ||
                detectCurrency(c) != null ||
                extractOnlyNumber(c) != null ||
                RATE_LABEL_REGEX.containsMatchIn(c)
        }
        val item = longestDescriptionCell(cells, excluded)
            ?: descriptionFromLine(evidence)
            ?: return null

        return buildCandidate(
            document = document,
            lineIndex = lineIndex,
            sheet = sheet,
            evidence = evidence,
            item = item,
            unitRaw = unit,
            quantity = null,
            rate = rateEvidence.rate,
            currency = currency,
            inferredScope = inferScope(evidence),
            confidence = rateEvidence.confidence,
            method = rateEvidence.method,
            now = now
        )
    }

    /**
     * Free-text extraction is deliberately precision-first.
     *
     * A row is accepted only when rate evidence is unambiguous:
     *  - an explicit rate/price label points to the number, or
     *  - a simple unit + one numeric cell + currency row, or
     *  - one non-identifier number appears in a line that has both unit and currency.
     *
     * Project/building/revision/code/quantity/total numbers are never promoted merely
     * because EGP or a unit happens to occur elsewhere on the same line.
     */
    private fun findHeuristicRate(
        line: String,
        cells: List<String>,
        unit: String?,
        currency: String?
    ): HeuristicRateEvidence? {
        findExplicitRate(line, unit, currency)?.let { return it }

        // Without an explicit rate label, require both commercial dimensions.
        if (unit == null || currency == null) return null

        findStructuredRateCell(cells)?.let { rate ->
            return HeuristicRateEvidence(
                rate = rate,
                confidence = 84,
                method = "UNIT_RATE_CURRENCY_ROW"
            )
        }

        val usableNumbers = NUMBER_REGEX.findAll(line)
            .filter { match ->
                parseCommercialNumber(match.value)?.let(::plausibleRate) == true &&
                    !isDisqualifiedNumber(line, match)
            }
            .toList()

        if (usableNumbers.size != 1) return null
        val rate = parseCommercialNumber(usableNumbers.single().value) ?: return null
        return HeuristicRateEvidence(
            rate = rate,
            confidence = 80,
            method = "UNIT_CURRENCY_SINGLE_NUMBER"
        )
    }

    private fun findExplicitRate(
        line: String,
        unit: String?,
        currency: String?
    ): HeuristicRateEvidence? {
        for (label in RATE_LABEL_REGEX.findAll(line)) {
            val searchStart = label.range.last + 1
            val match = NUMBER_REGEX.find(line, searchStart) ?: continue
            // Do not let a label at the start of a long paragraph capture an
            // unrelated number much later in the text.
            if (match.range.first - searchStart > 48) continue
            val rate = parseCommercialNumber(match.value)?.takeIf(::plausibleRate) ?: continue
            val confidence = when {
                unit != null && currency != null -> 92
                unit != null || currency != null -> 87
                else -> 80
            }
            return HeuristicRateEvidence(
                rate = rate,
                confidence = confidence,
                method = "EXPLICIT_RATE_LABEL"
            )
        }
        return null
    }

    /**
     * Conservative headerless table fallback.
     *
     * "Marble | lm | 775 | EGP" is useful. A row with two numeric-only cells is
     * ambiguous (qty/rate/total) and is rejected until a real header or label
     * identifies which number is the rate.
     */
    private fun findStructuredRateCell(cells: List<String>): Double? {
        if (cells.size < 3) return null

        val numericCells = cells.mapIndexedNotNull { index, cell ->
            extractOnlyNumber(cell)
                ?.takeIf(::plausibleRate)
                ?.let { index to it }
        }
        if (numericCells.size != 1) return null

        val unitCells = cells.indices.filter { idx ->
            val cell = cells[idx].trim()
            cell.isNotEmpty() && detectUnit(cell) != null && cell.length <= 24
        }
        val currencyCells = cells.indices.filter { idx ->
            val cell = cells[idx].trim()
            cell.isNotEmpty() && detectCurrency(cell) != null && cell.length <= 16
        }
        if (unitCells.isEmpty() || currencyCells.isEmpty()) return null

        val (numberIndex, rate) = numericCells.single()
        val unitDistance = unitCells.minOf { kotlin.math.abs(it - numberIndex) }
        val currencyDistance = currencyCells.minOf { kotlin.math.abs(it - numberIndex) }
        if (unitDistance > 2 || currencyDistance > 2) return null

        return rate
    }

    private fun buildCandidate(
        document: RateCorpusDocument,
        lineIndex: Int,
        sheet: String?,
        evidence: String,
        item: String,
        unitRaw: String?,
        quantity: Double?,
        rate: Double,
        currency: String?,
        inferredScope: String?,
        confidence: Int,
        method: String,
        now: Long
    ): RateCandidateEntity {
        val locator = buildString {
            if (!sheet.isNullOrBlank()) append(sheet).append(" · ")
            append("line ").append(lineIndex + 1)
        }
        val sourceKey = sha256(
            listOf(
                PARSER_VERSION,
                document.id.toString(),
                document.lastModified.toString(),
                locator,
                item.trim(),
                rate.toString(),
                unitRaw.orEmpty(),
                evidence
            ).joinToString("|")
        )
        return RateCandidateEntity(
            sourceKey = sourceKey,
            sourceDocumentId = document.id,
            sourceFileNameSnapshot = document.displayName,
            sourceRelativePathSnapshot = document.relativePath,
            sourceUriSnapshot = document.documentUri,
            sourceModifiedAt = document.lastModified,
            sourceExtension = document.extension,
            sourceMimeType = document.mimeType,
            itemRaw = item.trim(),
            unitRaw = unitRaw?.trim()?.takeIf { it.isNotEmpty() },
            quantity = quantity?.takeIf { it.isFinite() && it >= 0.0 },
            rate = rate,
            currency = currency,
            inferredScope = inferredScope,
            evidenceLocator = locator,
            evidenceText = evidence.take(900),
            confidence = confidence.coerceIn(0, 100),
            detectionMethod = method,
            parserVersion = PARSER_VERSION,
            createdAt = now
        )
    }

    private fun detectHeader(cells: List<String>): HeaderMap? {
        var description: Int? = null
        var unit: Int? = null
        var rate: Int? = null
        var quantity: Int? = null
        var currency: Int? = null

        cells.forEachIndexed { index, raw ->
            val h = normalizeHeader(raw)
            when {
                h in DESCRIPTION_HEADERS -> description = index
                h in UNIT_HEADERS -> unit = index
                h in RATE_HEADERS -> rate = index
                h in QUANTITY_HEADERS -> quantity = index
                h in CURRENCY_HEADERS -> currency = index
            }
        }
        if (rate == null) return null
        if (description == null && unit == null) return null
        return HeaderMap(description, unit, rate, quantity, currency)
    }

    private fun normalizeHeader(value: String): String =
        normalizeCommercialDigits(value)
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .replace(":", "")
            .replace("/", " ")

    private fun longestDescriptionCell(cells: List<String>, excluded: Set<Int>): String? =
        cells.withIndex()
            .asSequence()
            .filter { it.index !in excluded }
            .map { it.value.trim() }
            .filter(::looksLikeDescription)
            .maxByOrNull { it.length }

    private fun descriptionFromLine(line: String): String? {
        val cleaned = line
            .replace(NUMBER_REGEX, " ")
            .replace(CURRENCY_REGEX, " ")
            .replace(UNIT_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', ':', '|')
        return cleaned.takeIf(::looksLikeDescription)
    }

    private fun looksLikeDescription(value: String): Boolean {
        val v = value.trim()
        if (v.length < 3) return false
        if (extractOnlyNumber(v) != null) return false
        if (detectUnit(v) != null && v.length <= 12) return false
        val normalized = normalizeHeader(v)
        if (normalized in DESCRIPTION_HEADERS || normalized in RATE_HEADERS ||
            normalized in UNIT_HEADERS || normalized in QUANTITY_HEADERS) return false
        return v.any { it.isLetter() }
    }

    private fun extractOnlyNumber(value: String): Double? {
        val trimmed = value.trim()
        val match = NUMBER_REGEX.matchEntire(trimmed) ?: return null
        return parseCommercialNumber(match.value)
    }

    private fun extractFirstNumber(value: String): Double? =
        NUMBER_REGEX.find(value)?.value?.let(::parseCommercialNumber)

    private fun isDisqualifiedNumber(line: String, match: MatchResult): Boolean {
        val normalized = normalizeCommercialDigits(line).lowercase(Locale.ROOT)
        val start = match.range.first
        val endExclusive = match.range.last + 1

        val before = normalized.substring(maxOf(0, start - 48), start)
        val after = normalized.substring(endExclusive, minOf(normalized.length, endExclusive + 24))

        if (IDENTIFIER_PREFIX_REGEX.containsMatchIn(before)) return true
        if (NON_RATE_PREFIX_REGEX.containsMatchIn(before)) return true
        if (DIMENSION_SUFFIX_REGEX.containsMatchIn(after)) return true

        // Reject components of dates such as 2026-10-10 or 10/10/2026.
        val previous = normalized.getOrNull(start - 1)
        val previousPrevious = normalized.getOrNull(start - 2)
        val next = normalized.getOrNull(endExclusive)
        val nextNext = normalized.getOrNull(endExclusive + 1)
        if ((previous == '-' || previous == '/') && previousPrevious?.isDigit() == true) return true
        if ((next == '-' || next == '/') && nextNext?.isDigit() == true) return true

        // Procurement codes like MMH2-GR-AR-14-25A contain numeric chunks that
        // are identifiers, not commercial values.
        val tokenStart = normalized.lastIndexOfAny(
            charArrayOf(' ', '\t', '|', ';', ','),
            startIndex = maxOf(0, start - 1)
        ).let { if (it < 0) 0 else it + 1 }
        val nextBreak = normalized.indexOfAny(
            charArrayOf(' ', '\t', '|', ';', ','),
            startIndex = endExclusive
        )
        val tokenEnd = if (nextBreak < 0) normalized.length else nextBreak
        val token = normalized.substring(tokenStart, tokenEnd)
        if ((token.contains('-') || token.contains('/')) &&
            token.any { it.isLetter() } &&
            token.any { it.isDigit() }
        ) {
            return true
        }

        return false
    }

    private fun plausibleRate(value: Double): Boolean =
        value.isFinite() && value > 0.0 && value <= 1_000_000_000.0

    private fun detectUnit(value: String): String? {
        val normalized = normalizeCommercialDigits(value).lowercase(Locale.ROOT)
        val match = UNIT_REGEX.find(normalized)?.value ?: return null
        return when (normalizeRateUnit(match)) {
            "m2" -> "m2"
            "m3" -> "m3"
            "lm" -> "lm"
            "m" -> "m"
            "kg" -> "kg"
            "ton" -> "ton"
            "no" -> "no"
            "lot" -> "lot"
            else -> match.trim()
        }
    }

    private fun detectCurrency(value: String): String? {
        val match = CURRENCY_REGEX.find(value.lowercase(Locale.ROOT))?.value ?: return null
        return when {
            match.contains("usd") || match.contains("$") -> "USD"
            match.contains("eur") || match.contains("€") -> "EUR"
            else -> "EGP"
        }
    }

    private fun inferScope(value: String): String? {
        val v = value.lowercase(Locale.ROOT)
        val supply = listOf("توريد", "supply").any(v::contains)
        val install = listOf("تركيب", "install", "installation").any(v::contains)
        return when {
            supply && install -> RateScope.SUPPLY_INSTALL
            supply -> RateScope.SUPPLY
            install -> RateScope.INSTALL
            else -> null
        }
    }

    private fun parseSheetName(line: String): String? {
        if (!line.startsWith("--- Sheet:") || !line.endsWith("---")) return null
        return line.removePrefix("--- Sheet:").removeSuffix("---").trim().takeIf { it.isNotEmpty() }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val DESCRIPTION_HEADERS = setOf(
        "description", "item", "item description", "description of item",
        "البند", "البيان", "الوصف", "توصيف", "وصف البند"
    )
    private val UNIT_HEADERS = setOf(
        "unit", "uom", "الوحدة", "وحدة", "الوحده"
    )
    private val RATE_HEADERS = setOf(
        "rate", "unit rate", "unit price", "price",
        "سعر", "السعر", "سعر الوحدة", "سعر الوحده", "الفئة", "فئة"
    )
    private val QUANTITY_HEADERS = setOf(
        "qty", "quantity", "الكمية", "كمية", "الكميه"
    )
    private val CURRENCY_HEADERS = setOf(
        "currency", "العملة", "عمله", "العمله"
    )

    private val NUMBER_REGEX = Regex(
        """(?<![\\p{L}\\p{N}])[-+]?[0-9٠-٩۰-۹]+(?:[,.٬٫][0-9٠-٩۰-۹]{1,3})*(?:[.٫][0-9٠-٩۰-۹]+)?(?![\\p{L}\\p{N}])"""
    )

    private val RATE_LABEL_REGEX = Regex(
        """(?i)(?:\\bunit\\s*rate\\b|\\bunit\\s*price\\b|\\brate\\b|\\bprice\\b|سعر\\s*الوحد[ةه]|السعر|سعر|الفئ[ةه]|فئ[ةه])"""
    )

    private val IDENTIFIER_PREFIX_REGEX = Regex(
        """(?i)(?:project|proj|building|bldg|villa|room|floor|phase|revision|rev|code|pr|po|rfq|رقم|مشروع|مبنى|مبني|فيلا|غرفة|غرفه|دور|مرحلة|مراجعة)\\s*[:#./-]?\\s*$"""
    )

    private val NON_RATE_PREFIX_REGEX = Regex(
        """(?i)(?:qty|quantity|total|amount|number|no\\.?|الكمية|الكميه|كمية|إجمالي|اجمالي|الإجمالي|المجموع)\\s*[:#-]?\\s*$"""
    )

    private val DIMENSION_SUFFIX_REGEX = Regex(
        """(?i)^\\s*(?:mm|cm|inch|inches|سم|مم|%|٪)(?![\\p{L}\\p{N}])"""
    )

    private val UNIT_REGEX = Regex(
        """(?i)(?<![\p{L}\p{N}])(?:m\s*2|m²|sqm|sq\s*m|م\s*[2٢²]|متر\s*مربع|m\s*3|m³|cum|cu\s*m|م\s*[3٣³]|متر\s*مكعب|lm|l\s*m|linear\s*met(?:er|re)s?|م\s*ط|متر\s*طولي|kg|كجم|كيلو(?:جرام)?|ton|tons|طن|no|nos|each|ea|عدد|بالعدد|lot|lump\s*sum|مقطوعية|m|meter|metre|متر)(?![\p{L}\p{N}])"""
    )

    private val CURRENCY_REGEX = Regex(
        """(?i)(?:egp|l\.?e\.?|ج\.?\s*م|جنيه|usd|\$|eur|€)"""
    )
}
