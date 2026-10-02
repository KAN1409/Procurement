package com.edib.workknowledge.search

/**
 * The single place FTS4 query syntax is produced.
 *
 * Everything is derived from measured behaviour of the SQLite build that ships
 * with the app, not from assumptions about FTS grammar. On that build:
 *
 *   "MR" "104"     -> 1 row   (two quoted terms, implicit AND: CORRECT)
 *   "MR" AND "104" -> 0 rows  (explicit AND is not supported)
 *   MR-023         -> 0 rows  (the hyphen is parsed, not literal)
 *   "MR-023"       -> 1 row   (quoted, so the hyphen is literal)
 *   OR / NEAR      -> SQLiteException: malformed MATCH expression
 *
 * Two consequences shape this builder:
 *
 *  1. Terms are never joined with `AND`. A space is the implicit AND and is
 *     the only form that works.
 *  2. Every token is quoted, so it can never be interpreted as an operator.
 *     `OR` and `NEAR` are reserved words; quoting them makes them ordinary
 *     search terms instead of a syntax error.
 *
 * A user-supplied `*` is dropped rather than passed through, so it cannot turn
 * a term into a malformed prefix.
 */
object FtsQueryBuilder {

    /**
     * Letters, digits, underscore and the Arabic block. Everything else is
     * treated as a separator, which is what keeps `-`, `/`, `+`, `%`, `(`, `)`
     * and the rest out of the query grammar entirely.
     */
    private val TOKEN_SPLIT = Regex("[^A-Za-z0-9_\\u0600-\\u06FF]+")

    /**
     * A code like MR-104, PR-023, CL-201.
     *
     * Matching this shape matters because the hyphen is what breaks naive
     * splitting: `PR-023` must become two required terms, not the operator
     * expression the raw string would produce.
     */
    private val IDENTIFIER = Regex("^[A-Za-z]{1,6}[-–—_][A-Za-z0-9]{1,8}$")

    /** Splits user input into safe terms. Exposed for testing. */
    fun tokenize(input: String): List<String> =
        TOKEN_SPLIT.split(input.lowercase())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // A single "*" survives the split; drop it so it cannot be
            // interpreted as a prefix operator.
            .filter { it != "*" }
            .distinct()

    /**
     * Builds a MATCH expression.
     *
     * Terms are joined with a space (implicit AND) and each is quoted, so the
     * result is safe for any input and returns only documents containing all
     * of the terms.
     */
    fun build(input: String): String? {
        val terms = tokenize(input)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { quote(it) }
    }

    /**
     * True when the input looks like a single code, so the caller can treat it
     * as an exact identifier and boost the matching result.
     */
    fun isIdentifierLike(input: String): Boolean =
        IDENTIFIER.matches(input.trim())

    /**
     * Escapes a term for use inside double quotes.
     *
     * FTS doubles a quote by doubling it again. Backslashes are left intact
     * because the tokenizer has already removed every character that is not
     * alphanumeric, so nothing requiring backslash escaping can reach here.
     */
    private fun quote(term: String): String = "\"" + term.replace("\"", "\"\"") + "\""
}
