package com.geoknoesis.kastor.gen.runtime

/**
 * Thrown when a validation adapter refuses shapes **embedded in the data graph** because they use a construct that
 * the adapter's engine would run without a bound (see [EmbeddedShapes]). An [IllegalArgumentException], like every
 * other shapes graph the adapters cannot admit.
 */
class EmbeddedShapesRefusedException(message: String, cause: Throwable?) : IllegalArgumentException(message, cause) {
    constructor(message: String) : this(message, null)
}

/**
 * **Not public API.** The admission rule of the validation adapters (`JenaValidation`, `Rdf4jValidation`) for shapes
 * that come from the data graph, which is untrusted input.
 *
 * Their engines (`jena-shacl`, RDF4J `ShaclSail`) run SHACL-SPARQL without a sandbox - a `SERVICE` clause sends a
 * request to any host, a query has no deadline - and evaluate `sh:pattern` with `java.util.regex`, which a hostile
 * pattern makes backtrack without bound. So embedded shapes are admitted only when every predicate they use in the
 * SHACL namespace is in [ALLOWED]: SHACL Core targets, paths, constraints and their non-validating properties, the
 * terms of a validation report and prefix declarations. Anything else (`sh:sparql`, `sh:select`, `sh:ask`,
 * `sh:target`, `sh:validator`, `sh:pattern`, `sh:flags`, `sh:expression`, `sh:rule`, a term of a later SHACL version,
 * ...) fails closed with [EmbeddedShapesRefusedException]. Shapes the application passes to an adapter are trusted
 * and are not checked.
 */
@KastorGenInternalApi
object EmbeddedShapes {
    private const val SH = "http://www.w3.org/ns/shacl#"

    /** The SHACL-namespace predicates (local names) that embedded shapes may use. */
    val ALLOWED: Set<String> = setOf(
        // Targets and shape structure.
        "targetClass", "targetNode", "targetSubjectsOf", "targetObjectsOf", "property", "path", "node",
        "inversePath", "alternativePath", "zeroOrMorePath", "oneOrMorePath", "zeroOrOnePath",
        // Core constraints (no regular expression, no query).
        "class", "datatype", "nodeKind", "minCount", "maxCount", "minExclusive", "minInclusive", "maxExclusive",
        "maxInclusive", "minLength", "maxLength", "languageIn", "uniqueLang", "equals", "disjoint", "lessThan",
        "lessThanOrEquals", "not", "and", "or", "xone", "qualifiedValueShape", "qualifiedMinCount",
        "qualifiedMaxCount", "qualifiedValueShapesDisjoint", "closed", "ignoredProperties", "hasValue", "in",
        // Non-validating properties.
        "name", "description", "message", "severity", "deactivated", "order", "group", "defaultValue",
        // Prefix declarations (inert without a SPARQL query, which is refused).
        "declare", "prefix", "namespace",
        // A validation report stored in the data.
        "conforms", "result", "focusNode", "resultPath", "resultSeverity", "resultMessage", "sourceShape",
        "sourceConstraintComponent", "sourceConstraint", "value", "detail",
    ).mapTo(HashSet()) { SH + it }

    /** The SHACL-namespace predicates among [predicates] that embedded shapes may not use, sorted and distinct. */
    fun refused(predicates: Iterator<String>): List<String> {
        val refused = java.util.TreeSet<String>()
        for (predicate in predicates) if (predicate.startsWith(SH) && predicate !in ALLOWED) refused.add(predicate)
        return refused.toList()
    }

    /**
     * Throws [EmbeddedShapesRefusedException] when [refused] (see [refused]) is not empty; [adapter] names the
     * validator class, whose `trustingEmbeddedShapes()` factory is the opt-in.
     */
    fun requireNone(refused: List<String>, adapter: String) {
        if (refused.isEmpty()) return
        throw EmbeddedShapesRefusedException(
            "$adapter refuses the SHACL shapes embedded in the data graph: they use ${refused.joinToString { "<$it>" }}, " +
                "which the engine would run without a sandbox (SHACL-SPARQL can reach any host and has no deadline; " +
                "sh:pattern runs on java.util.regex). Embedded shapes come from the data and are untrusted: pass the " +
                "shapes to the constructor, validate with the Kastor SHACL validator, or, only when the data graph is " +
                "trusted, create the validator with $adapter.trustingEmbeddedShapes().",
        )
    }
}
