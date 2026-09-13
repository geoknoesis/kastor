package com.geoknoesis.kastor.rdf

/**
 * Shared well-formedness rules for literals, for providers whose parsers or stores accept literals that are
 * not valid RDF (or cannot be represented by Kastor terms).
 *
 * Rules:
 * - a language tag must be BCP 47-shaped (the Turtle/SPARQL `LANGTAG` production: `[a-zA-Z]{1,8}` followed by
 *   `-[a-zA-Z0-9]{1,8}` subtags);
 * - `rdf:langString` requires a language tag;
 * - `rdf:dirLangString` requires both a language tag and a base direction;
 * - a base direction is only valid together with a language tag.
 */
object LiteralValidation {
    /** IRI of `rdf:langString`. */
    const val RDF_LANG_STRING: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"

    /** IRI of `rdf:dirLangString` (RDF 1.2). */
    const val RDF_DIR_LANG_STRING: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#dirLangString"

    /** True if [tag] is a non-empty, BCP 47-shaped language tag (case-insensitive). */
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
