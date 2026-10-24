package com.geoknoesis.kastor.rdf.shacl.native

/**
 * The SHACL SPARQL function expressions of Node Expressions §5: each `sparql:<name>` of the §5 table, its SPARQL 1.2
 * rendering over the argument variables `?a0 ... ?a<n-1>`, and the argument counts SPARQL accepts for it. A name that
 * is not in the table, or a call with an argument count SPARQL rejects, is ill-formed ([render] returns null).
 *
 * `sparql:encode` is `ENCODE_FOR_URI` with one argument: the W3C test `shnex-sparql/encode.ttl` calls it with one
 * argument, while the example of the §5 table passes a second one (`"UTF-8"`) that SPARQL has no place for.
 */
internal object SparqlFunctionExpressions {

    /** The functions that are applied to an argument without output nodes, as an unbound value (§5). */
    val handlesUnbound: Set<String> = setOf("bound", "coalesce", "if", "logical-and", "logical-or")

    private class Function(val render: (List<String>) -> String, val arities: IntRange)

    private const val ANY = Int.MAX_VALUE

    private fun call(keyword: String, arities: IntRange) = Function({ "$keyword(${it.joinToString(", ")})" }, arities)
    private fun call(keyword: String, arity: Int) = call(keyword, arity..arity)
    private fun infix(operator: String) = Function({ "(${it[0]} $operator ${it[1]})" }, 2..2)
    private fun prefix(operator: String) = Function({ "($operator${it[0]})" }, 1..1)

    private val functions: Map<String, Function> = mapOf(
        // Operators.
        "plus" to infix("+"),
        "subtract" to infix("-"),
        "multiply" to infix("*"),
        "divide" to infix("/"),
        "equals" to infix("="),
        "not-equals" to infix("!="),
        "less-than" to infix("<"),
        "less-than-or-equal" to infix("<="),
        "greater-than" to infix(">"),
        "greater-than-or-equal" to infix(">="),
        "logical-and" to infix("&&"),
        "logical-or" to infix("||"),
        "logical-not" to prefix("!"),
        "unary-minus" to prefix("-"),
        "unary-plus" to prefix("+"),
        // Functional forms.
        "bound" to call("BOUND", 1),
        "if" to call("IF", 3),
        "coalesce" to call("COALESCE", 0..ANY),
        "sameTerm" to call("sameTerm", 2),
        "sameValue" to call("SAMEVALUE", 2),
        // RDF terms.
        "isIRI" to call("isIRI", 1),
        "isURI" to call("isURI", 1),
        "isBlank" to call("isBLANK", 1),
        "isLiteral" to call("isLITERAL", 1),
        "isNumeric" to call("isNUMERIC", 1),
        "str" to call("STR", 1),
        "lang" to call("LANG", 1),
        "langdir" to call("LANGDIR", 1),
        "hasLang" to call("HASLANG", 1),
        "hasLangdir" to call("HASLANGDIR", 1),
        "datatype" to call("DATATYPE", 1),
        "iri" to call("IRI", 1),
        "uri" to call("URI", 1),
        "bnode" to call("BNODE", 0..1),
        "strdt" to call("STRDT", 2),
        "strlang" to call("STRLANG", 2),
        "strlangdir" to call("STRLANGDIR", 3),
        "uuid" to call("UUID", 0),
        "struuid" to call("STRUUID", 0),
        // Triple terms.
        "isTriple" to call("isTRIPLE", 1),
        "triple" to call("TRIPLE", 3),
        "subject" to call("SUBJECT", 1),
        "predicate" to call("PREDICATE", 1),
        "object" to call("OBJECT", 1),
        // Strings.
        "strlen" to call("STRLEN", 1),
        "substr" to call("SUBSTR", 2..3),
        "ucase" to call("UCASE", 1),
        "lcase" to call("LCASE", 1),
        "strstarts" to call("STRSTARTS", 2),
        "strends" to call("STRENDS", 2),
        "contains" to call("CONTAINS", 2),
        "strbefore" to call("STRBEFORE", 2),
        "strafter" to call("STRAFTER", 2),
        "encode" to call("ENCODE_FOR_URI", 1),
        "concat" to call("CONCAT", 0..ANY),
        "langMatches" to call("langMatches", 2),
        "regex" to call("REGEX", 2..3),
        "replace" to call("REPLACE", 3..4),
        // Numerics.
        "abs" to call("ABS", 1),
        "round" to call("ROUND", 1),
        "ceil" to call("CEIL", 1),
        "floor" to call("FLOOR", 1),
        "rand" to call("RAND", 0),
        // Dates and times.
        "now" to call("NOW", 0),
        "year" to call("YEAR", 1),
        "month" to call("MONTH", 1),
        "day" to call("DAY", 1),
        "hours" to call("HOURS", 1),
        "minutes" to call("MINUTES", 1),
        "seconds" to call("SECONDS", 1),
        "timezone" to call("TIMEZONE", 1),
        "tz" to call("TZ", 1),
        // Hash functions.
        "md5" to call("MD5", 1),
        "sha1" to call("SHA1", 1),
        "sha256" to call("SHA256", 1),
        "sha384" to call("SHA384", 1),
        "sha512" to call("SHA512", 1),
    )

    /** The names of the §5 table. */
    val names: Set<String> get() = functions.keys

    /** The SPARQL expression of `sparql:<name>` applied to `?a0 ... ?a<arity-1>`, or null when that call is ill-formed. */
    fun render(name: String, arity: Int): String? = render(name, List(arity) { "?a$it" })

    /** The SPARQL expression of `sparql:<name>` applied to the SPARQL expressions [args], or null when that call is ill-formed. */
    fun render(name: String, args: List<String>): String? {
        val function = functions[name] ?: return null
        if (args.size !in function.arities) return null
        return function.render(args)
    }
}
