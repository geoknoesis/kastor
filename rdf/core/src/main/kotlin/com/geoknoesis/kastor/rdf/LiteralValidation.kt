package com.geoknoesis.kastor.rdf

/**
 * Shared well-formedness rules for literals, for providers whose parsers or stores accept literals that are
 * not valid RDF (or cannot be represented by Kastor terms).
 *
 * Rules:
 * - a language tag must be BCP 47-shaped: the Turtle/SPARQL `LANGTAG` production `[a-zA-Z]+ ('-' [a-zA-Z0-9]+)*`
 *   with every subtag at most 8 characters, as RDF 1.2 requires well-formed BCP 47 tags (the W3C RDF 1.2 suite
 *   rejects longer subtags, e.g. `ntriples-langdir-bad-4`); case-insensitive, registry rules are not enforced;
 * - `rdf:langString` requires a language tag;
 * - `rdf:dirLangString` requires both a language tag and a base direction;
 * - a base direction is only valid together with a language tag.
 */
object LiteralValidation {
    /** IRI of `rdf:langString`. */
    const val RDF_LANG_STRING: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"

    /** IRI of `rdf:dirLangString` (RDF 1.2). */
    const val RDF_DIR_LANG_STRING: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#dirLangString"

    /**
     * The longest lexical form of a literal of one of the [BOUNDED_NUMERIC_DATATYPES] (`xsd:decimal`, `xsd:integer`
     * and its derived types, and the date/time, Gregorian and duration types) that Kastor's parsers accept, and that
     * the Jena provider accepts through its graph API. The engines compute the value of such a literal with
     * `BigInteger` / `BigDecimal` (Jena does for every number it parses, and SPARQL engines do to compare numbers,
     * years and fractional seconds), in time quadratic in its length: 12 s for an 800,000-digit integer, 2.5 s for one
     * comparison of a dateTime with 400,000 fraction digits. Both providers apply this limit to every parse
     * ([numericLengthProblem]). Kastor's own XSD code (`com.geoknoesis.kastor.rdf.xsd`) is linear and needs no limit.
     */
    const val MAX_NUMERIC_LEXICAL_LENGTH: Int = 10_000

    /**
     * The datatype IRIs [MAX_NUMERIC_LEXICAL_LENGTH] applies to: `xsd:decimal`, `xsd:integer` and its derived types,
     * `xsd:dateTime`, `xsd:dateTimeStamp`, `xsd:date`, `xsd:time`, the Gregorian types and the duration types.
     * (`xsd:double` and `xsd:float` are not limited: the JDK reads them in linear time.)
     */
    @JvmField
    val BOUNDED_NUMERIC_DATATYPES: Set<String> = listOf(
        "decimal", "integer", "long", "int", "short", "byte", "nonNegativeInteger", "positiveInteger",
        "nonPositiveInteger", "negativeInteger", "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte",
        "dateTime", "dateTimeStamp", "date", "time", "gYear", "gYearMonth", "gMonthDay", "gDay", "gMonth",
        "duration", "dayTimeDuration", "yearMonthDuration",
    ).mapTo(HashSet()) { "http://www.w3.org/2001/XMLSchema#$it" }

    /**
     * Why a literal with [lexical] form and [datatypeIri] is refused for its length ([MAX_NUMERIC_LEXICAL_LENGTH]),
     * or `null` when it is not.
     */
    @JvmStatic
    fun numericLengthProblem(datatypeIri: String?, lexical: String): String? =
        if (lexical.length <= MAX_NUMERIC_LEXICAL_LENGTH || datatypeIri !in BOUNDED_NUMERIC_DATATYPES) null
        else "the ${datatypeIri!!.substringAfter('#')} literal has a lexical form of ${lexical.length} characters, more " +
            "than the $MAX_NUMERIC_LEXICAL_LENGTH Kastor accepts (computing its value takes time quadratic in its length)"

    /**
     * True if [tag] matches the Turtle/SPARQL `LANGTAG` grammar `[a-zA-Z]+ ('-' [a-zA-Z0-9]+)*` (either case) with
     * subtags of at most 8 characters (BCP 47 well-formedness, required by RDF 1.2).
     * This is the single language-tag rule used by [LangString] and [normalizeLanguageTag].
     */
    fun isWellFormedLanguageTag(tag: String): Boolean {
        var segment = 0
        var first = true
        for (c in tag) {
            if (c == '-') {
                if (segment == 0) return false
                segment = 0
                first = false
                continue
            }
            val ok = c in 'a'..'z' || c in 'A'..'Z' || (!first && c in '0'..'9')
            if (!ok || ++segment > 8) return false
        }
        return segment > 0
    }

    /**
     * Describes why a literal with the given parts is not well-formed, or returns `null` if it is.
     *
     * @param datatypeIri the literal's datatype IRI, if known
     * @param languageTag the language tag; `null` or empty means "no tag"
     * @param hasDirection whether the literal carries a base direction
     */
    fun problem(datatypeIri: String?, languageTag: String?, hasDirection: Boolean): String? {
        if (languageTag.isNullOrEmpty()) {
            return when {
                datatypeIri == RDF_LANG_STRING -> "Literal typed $RDF_LANG_STRING requires a language tag"
                datatypeIri == RDF_DIR_LANG_STRING ->
                    "Literal typed $RDF_DIR_LANG_STRING requires a language tag and a base direction"
                hasDirection -> "A base direction requires a language tag"
                else -> null
            }
        }
        if (!isWellFormedLanguageTag(languageTag)) return "Invalid language tag: '$languageTag'"
        if (datatypeIri == RDF_DIR_LANG_STRING && !hasDirection) {
            return "Literal typed $RDF_DIR_LANG_STRING requires a base direction"
        }
        return null
    }

    /**
     * Checks a Kastor term (recursing into triple-term objects).
     *
     * @throws IllegalArgumentException when the literal is not well-formed
     */
    fun requireWellFormed(term: RdfTerm) {
        when (term) {
            is LangString -> problem(term.datatype.value, term.lang, term.direction != null)
            is Literal -> problem(term.datatype.value, null, false)
            is TripleTerm -> { requireWellFormed(term.triple.obj); null }
            else -> null
        }?.let { throw IllegalArgumentException(it) }
    }
}
