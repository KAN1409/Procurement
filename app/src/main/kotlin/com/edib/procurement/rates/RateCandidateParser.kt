package com.edib.procurement.rates

import com.edib.procurement.database.RateCandidateEntity
import com.edib.procurement.database.RateCorpusDocument
import com.edib.procurement.database.RateScope
import com.edib.procurement.database.normalizeRateUnit
import java.security.MessageDigest
import java.util.Locale

object RateCandidateParser {
    const val PARSER_VERSION = "rate-candidate-1.2"
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

    private fun parseHeuristicRow(
        document: RateCorpusDocument,
        lineIndex: Int,
        sheet: String?,
        evidence: String,
        cells: List<String>,
        now: Long
    ): RateCandidateEntity? {
        if (evidence.length < 6 || evidence.length > 700) return null
        if (evidence.contains("[excel-serial:", ignoreCase = true) ||
            evidence.contains('\\') ||
            evidence.trimStart().startsWith("المشروع") ||
            evidence.trimStart().startsWith("project:", ignoreCase = true)
        ) return null
        val unit = detectUnit(evidence)
        val currency = detectCurrency(evidence)
        if (unit == null && currency == null) return null

        val numbers = NUMBER_REGEX.findAll(evidence)
            .mapNotNull { parseCommercialNumber(it.value) }
            .filter(::plausibleRate)
            .toList()
        if (numbers.isEmpty()) return null

        val rate = chooseHeuristicRate(evidence, cells, unit, currency, numbers) ?: return null
        val excluded = cells.indices.filterTo(mutableSetOf()) { idx ->
            val c = cells[idx]
            detectUnit(c) != null || detectCurrency(c) != null || extractOnlyNumber(c) != null
        }
        val item = longestDescriptionCell(cells, excluded)
            ?: descriptionFromLine(evidence)
            ?: return null

        val confidence = when {
            unit != null && currency != null -> 74
            unit != null -> 68
            else -> 62
        }

        return buildCandidate(
            document = document,
            lineIndex = lineIndex,
            sheet = sheet,
            evidence = evidence,
            item = item,
            unitRaw = unit,
            quantity = null,
            rate = rate,
            currency = currency,
            inferredScope = inferScope(evidence),
            confidence = confidence,
            method = "ROW_HEURISTIC",
            now = now
        )
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

    private fun chooseHeuristicRate(
        line: String,
        cells: List<String>,
        unit: String?,
        currency: String?,
        numbers: List<Double>
    ): Double? {
        val explicitPrice = Regex(
            """(?i)(?:سعر\s*(?:الوحدة|الوحده)?|الفئة|فئة|unit\s*(?:rate|price)|rate|price)\s*[:=\-]?\s*"""
        )
        val label = explicitPrice.find(line)
        if (label != null) {
            val after = line.substring(label.range.last + 1).trimStart()
            val value = NUMBER_REGEX.find(after)?.takeIf { it.range.first == 0 }
                ?.value?.let(::parseCommercialNumber)
            if (value != null && plausibleRate(value)) return value
        }

        // A structured row must place a description before a separate unit and price.
        // Numeric reference identifiers and unrelated currency mentions are not rates.
        if (cells.size >= 3) {
            val unitIndex = cells.indexOfFirst { detectUnit(it) != null && extractOnlyNumber(it) == null }
            if (unitIndex > 0 && cells.take(unitIndex).any(::looksLikeDescription)) {
                val priceCell = cells.drop(unitIndex + 1).firstOrNull {
                    extractOnlyNumber(it)?.let(::plausibleRate) == true
                }
                if (priceCell != null) return extractOnlyNumber(priceCell)
            }
        }

        // Free-text price must be adjacent to the unit and/or currency, with a
        // description preceding the commercial suffix. Never choose last number.
        if (unit == null) return null
        val unitMatch = UNIT_REGEX.find(line) ?: return null
        val prefix = line.substring(0, unitMatch.range.first)
        if (!looksLikeDescription(prefix) || prefix.trim().matches(Regex("""(?i)(?:project|building|reference|ref|مشروع|مبنى|مبني|كود)\s*[-#:]?\s*[\d٠-٩۰-۹]+"""))) return null
        val suffix = line.substring(unitMatch.range.last + 1).trim()
        val priceMatch = NUMBER_REGEX.find(suffix) ?: return null
        val beforePrice = suffix.substring(0, priceMatch.range.first).trim()
        val allowedBridge = beforePrice.isEmpty() ||
            beforePrice.matches(Regex("""(?i)(?:egp|usd|eur|جنيه|ج\.?\s*م|[:=\-])\s*"""))
        if (!allowedBridge) return null
        val afterPrice = suffix.substring(priceMatch.range.last + 1).trim()
        if (afterPrice.isNotEmpty() &&
            !afterPrice.matches(Regex("""(?i)(?:egp|usd|eur|جنيه|ج\.?\s*م|\$|€)"""))) return null
        val rate = parseCommercialNumber(priceMatch.value) ?: return null
        return rate.takeIf(::plausibleRate)
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

    private val NUMBER_REGEX = Regex("""[-+]?[0-9٠-٩۰-۹]+(?:[,.٬٫][0-9٠-٩۰-۹]{1,3})*(?:[.٫][0-9٠-٩۰-۹]+)?""")

    private val UNIT_REGEX = Regex(
        """(?i)(?<![\p{L}\p{N}])(?:m\s*2|m²|sqm|sq\s*m|م\s*[2٢²]|متر\s*مربع|m\s*3|m³|cum|cu\s*m|م\s*[3٣³]|متر\s*مكعب|lm|l\s*m|linear\s*met(?:er|re)s?|م\s*ط|متر\s*طولي|kg|كجم|كيلو(?:جرام)?|ton|tons|طن|no|nos|each|ea|عدد|بالعدد|lot|lump\s*sum|مقطوعية|m|meter|metre|متر)(?![\p{L}\p{N}])"""
    )

    private val CURRENCY_REGEX = Regex(
        """(?i)(?:egp|l\.?e\.?|ج\.?\s*م|جنيه|usd|\$|eur|€)"""
    )
}
