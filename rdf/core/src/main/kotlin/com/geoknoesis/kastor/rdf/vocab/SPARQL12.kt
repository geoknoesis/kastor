package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/** Namespace used by the SPARQL modules (`KastorSparqlVocabulary`) for Kastor-specific SPARQL terms. */
private const val KASTOR_SPARQL_NS = "https://kastor.geoknoesis.com/ns/sparql#"

/**
 * Terms in the `http://www.w3.org/ns/sparql#` namespace used by Kastor for SPARQL 1.2.
 *
 * **Most of these terms are not defined by W3C.** The W3C `sparql#` namespace document does not define them, and
 * the SPARQL Service Description vocabulary (`http://www.w3.org/ns/sparql-service-description#`, prefix `sd:`)
 * describes service capabilities with `sd:feature`, `sd:supportedLanguage`, `sd:extensionFunction` and friends,
 * not with the properties below. Terms fall into three groups:
 *
 * - **Invented service-description terms** ([Sparql12Service], [Sparql12Endpoint], `supports*`,
 *   [supportedSparqlVersion]) - deprecated. The SPARQL modules publish them in the Kastor namespace
 *   `https://kastor.geoknoesis.com/ns/sparql#`; for standard descriptions use the `sd:` vocabulary.
 * - **Invented function names with no SPARQL 1.2 built-in counterpart** ([replaceAll], [decodeForUri], [dateTime],
 *   [date], [time], [random]) - deprecated.
 * - **Names of SPARQL 1.2 built-in functions** ([TRIPLE], [isTRIPLE], [SUBJECT], [PREDICATE], [OBJECT],
 *   [encodeForUri], [LANGDIR], [hasLANG], [hasLANGDIR], [STRLANGDIR], [now], [timezone], [tz], [rand]) - kept.
 *   The functions are standard, but SPARQL 1.2 Query invokes built-ins by keyword and does not publish IRIs for
 *   them in this namespace; treat these IRIs as Kastor's identifiers for the built-ins (for example in
 *   `sd:extensionFunction` listings), not as dereferenceable W3C terms. Local names follow Kastor's historical
 *   spelling (e.g. `encodeForUri` for `ENCODE_FOR_URI`, `now` for `NOW`).
 *
 * Deprecated terms still resolve to the same IRIs, so existing data and callers keep working.
 */
object SPARQL12 : Vocabulary {
    override val namespace: String = "http://www.w3.org/ns/sparql#"
    override val prefix: String = "sparql"

    // Service-description terms (not W3C-defined)

    @Deprecated(
        "Not a W3C term: sparql:Sparql12Service is not defined in http://www.w3.org/ns/sparql#. " +
            "Use ${KASTOR_SPARQL_NS}Sparql12Service, or sd:Service from the SPARQL Service Description vocabulary.",
        level = DeprecationLevel.WARNING,
    )
    val Sparql12Service: Iri by lazy { term("Sparql12Service") }

    @Deprecated(
        "Not a W3C term: sparql:Sparql12Endpoint is not defined in http://www.w3.org/ns/sparql#. " +
            "Use ${KASTOR_SPARQL_NS}Sparql12Endpoint, or sd:endpoint from the SPARQL Service Description vocabulary.",
        level = DeprecationLevel.WARNING,
    )
    val Sparql12Endpoint: Iri by lazy { term("Sparql12Endpoint") }

    @Deprecated(
        "Not a W3C term: sparql:supportsRdfStar is not defined in http://www.w3.org/ns/sparql#. " +
            "Use ${KASTOR_SPARQL_NS}supportsRdfStar (or sd:feature).",
        level = DeprecationLevel.WARNING,
    )
    val supportsRdfStar: Iri by lazy { term("supportsRdfStar") }

    @Deprecated(
        "Not a W3C term: sparql:supportsPropertyPaths is not defined in http://www.w3.org/ns/sparql#. " +
            "Use ${KASTOR_SPARQL_NS}supportsPropertyPaths (or sd:feature).",
        level = DeprecationLevel.WARNING,
    )
    val supportsPropertyPaths: Iri by lazy { term("supportsPropertyPaths") }

    @Deprecated(
        "Not a W3C term: sparql:supportsAggregation is not defined in http://www.w3.org/ns/sparql#. " +
            "Use ${KASTOR_SPARQL_NS}supportsAggregation (or sd:feature).",
        level = DeprecationLevel.WARNING,
    )
    val supportsAggregation: Iri by lazy { term("supportsAggregation") }

    @Deprecated(
        "Not a W3C term: sparql:supportsSubSelect is not defined in http://www.w3.org/ns/sparql#. " +
            "Use ${KASTOR_SPARQL_NS}supportsSubSelect (or sd:feature).",
        level = DeprecationLevel.WARNING,
    )
    val supportsSubSelect: Iri by lazy { term("supportsSubSelect") }

    @Deprecated(
        "Not a W3C term: sparql:supportsFederation is not defined in http://www.w3.org/ns/sparql#. " +
            "Use ${KASTOR_SPARQL_NS}supportsFederation, or sd:feature sd:BasicFederatedQuery.",
        level = DeprecationLevel.WARNING,
    )
    val supportsFederation: Iri by lazy { term("supportsFederation") }

    @Deprecated(
        "Not a W3C term: sparql:supportsVersionDeclaration is not defined in http://www.w3.org/ns/sparql#. " +
            "Use ${KASTOR_SPARQL_NS}supportsVersionDeclaration (or sd:feature).",
        level = DeprecationLevel.WARNING,
    )
    val supportsVersionDeclaration: Iri by lazy { term("supportsVersionDeclaration") }

    @Deprecated(
        "Not a W3C term: sparql:supportedSparqlVersion is not defined in http://www.w3.org/ns/sparql#. " +
            "Use ${KASTOR_SPARQL_NS}supportedSparqlVersion, or sd:supportedLanguage.",
        level = DeprecationLevel.WARNING,
    )
    val supportedSparqlVersion: Iri by lazy { term("supportedSparqlVersion") }

    // Triple-term functions (SPARQL 1.2 built-ins; see the class documentation about their IRIs)
    val TRIPLE: Iri by lazy { term("TRIPLE") }
    val isTRIPLE: Iri by lazy { term("isTRIPLE") }
    val SUBJECT: Iri by lazy { term("SUBJECT") }
    val PREDICATE: Iri by lazy { term("PREDICATE") }
    val OBJECT: Iri by lazy { term("OBJECT") }

    // String functions

    @Deprecated(
        "Not a W3C term and not a SPARQL 1.2 built-in (use REPLACE): sparql:replaceAll is not defined in " +
            "http://www.w3.org/ns/sparql#. Use ${KASTOR_SPARQL_NS}replaceAll.",
        level = DeprecationLevel.WARNING,
    )
    val replaceAll: Iri by lazy { term("replaceAll") }

    /** Kastor's identifier for the SPARQL built-in `ENCODE_FOR_URI` (see the class documentation). */
    val encodeForUri: Iri by lazy { term("encodeForUri") }

    @Deprecated(
        "Not a W3C term and not a SPARQL 1.2 built-in: sparql:decodeForUri is not defined in " +
            "http://www.w3.org/ns/sparql#. Use ${KASTOR_SPARQL_NS}decodeForUri.",
        level = DeprecationLevel.WARNING,
    )
    val decodeForUri: Iri by lazy { term("decodeForUri") }

    // Language and direction functions (SPARQL 1.2 built-ins)
    val LANGDIR: Iri by lazy { term("LANGDIR") }
    val hasLANG: Iri by lazy { term("hasLANG") }
    val hasLANGDIR: Iri by lazy { term("hasLANGDIR") }
    val STRLANGDIR: Iri by lazy { term("STRLANGDIR") }

    // Date/time functions

    /** Kastor's identifier for the SPARQL built-in `NOW`. */
    val now: Iri by lazy { term("now") }

    /** Kastor's identifier for the SPARQL built-in `TIMEZONE`. */
    val timezone: Iri by lazy { term("timezone") }

    @Deprecated(
        "Not a W3C term and not a SPARQL 1.2 built-in (use xsd:dateTime casts): sparql:dateTime is not defined in " +
            "http://www.w3.org/ns/sparql#. Use ${KASTOR_SPARQL_NS}dateTime.",
        level = DeprecationLevel.WARNING,
    )
    val dateTime: Iri by lazy { term("dateTime") }

    @Deprecated(
        "Not a W3C term and not a SPARQL 1.2 built-in: sparql:date is not defined in " +
            "http://www.w3.org/ns/sparql#. Use ${KASTOR_SPARQL_NS}date.",
        level = DeprecationLevel.WARNING,
    )
    val date: Iri by lazy { term("date") }

    @Deprecated(
        "Not a W3C term and not a SPARQL 1.2 built-in: sparql:time is not defined in " +
            "http://www.w3.org/ns/sparql#. Use ${KASTOR_SPARQL_NS}time.",
        level = DeprecationLevel.WARNING,
    )
    val time: Iri by lazy { term("time") }

    /** Kastor's identifier for the SPARQL built-in `TZ`. */
    val tz: Iri by lazy { term("tz") }

    // Random functions

    /** Kastor's identifier for the SPARQL built-in `RAND`. */
    val rand: Iri by lazy { term("rand") }

    @Deprecated(
        "Not a W3C term and not a SPARQL 1.2 built-in (use RAND): sparql:random is not defined in " +
            "http://www.w3.org/ns/sparql#. Use ${KASTOR_SPARQL_NS}random.",
        level = DeprecationLevel.WARNING,
    )
    val random: Iri by lazy { term("random") }
}
