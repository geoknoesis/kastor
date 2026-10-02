package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri

/**
 * Kastor extension vocabulary for SHACL validation reports (prefix `ksh:`).
 *
 * [ValidationReport.toShaclValidationReportRdf] uses these terms for information the SHACL validation report
 * vocabulary has no property for. They are additional triples on the `sh:ValidationReport` and its
 * `sh:ValidationResult` nodes: consumers that only know the `sh:` vocabulary can ignore them, and nothing is ever
 * minted in the `sh:` namespace.
 *
 * Every term is declared (with `rdfs:label`, `rdfs:comment` and `rdfs:isDefinedBy`) in the Turtle document shipped
 * with this module as the classpath resource [VOCABULARY_RESOURCE]; [terms] lists them.
 */
object KastorShaclVocabulary {
    /** Namespace of the Kastor SHACL extension terms. */
    const val NAMESPACE: String = "https://kastor.geoknoesis.com/ns/shacl#"

    /** Conventional prefix for [NAMESPACE]. */
    const val PREFIX: String = "ksh"

    /**
     * Version of the vocabulary document (its `owl:versionInfo`). The namespace IRI never changes with the version:
     * terms are only ever added, and the meaning of a published term does not change, so a consumer written against
     * an older version keeps working. The version tells which terms a document can contain.
     */
    const val VERSION: String = "1.0.0"

    /** Classpath resource (Turtle) that declares every term of this vocabulary. */
    const val VOCABULARY_RESOURCE: String = "/com/geoknoesis/kastor/rdf/shacl/kastor-shacl.ttl"

    /**
     * `ksh:resultStatus`: qualifies a `sh:ValidationResult` that does not report a definite constraint failure but
     * a constraint that could **not be decided**. Its value is one of the `ksh:ResultStatus` individuals
     * ([UndefinedRecursion], [PatternTimeout], [PatternTooComplex]); a result without this property is an ordinary
     * failure. See [ValidationViolation.resultStatus].
     */
    val resultStatus: Iri = Iri(NAMESPACE + "resultStatus")

    /** `ksh:ResultStatus`: the class of the values of [resultStatus] (named `ResultStatusClass` in Kotlin: the JVM getter of `ResultStatus` would clash with the one of [resultStatus]). */
    val ResultStatusClass: Iri = Iri(NAMESPACE + "ResultStatus")

    /**
     * `ksh:UndefinedRecursion`: the result reports that the constraint could **not be decided** because it depends
     * on a recursive shape dependency through a non-monotone operator (`sh:not`, `sh:xone`, `sh:qualifiedMaxCount`,
     * disjoint qualified value shapes), which SHACL leaves undefined ([ValidationViolation.isUndefinedRecursion]).
     * The result keeps the severity and constraint component a failure would have. A result has one status: when a
     * constraint is undecided both because of undefined recursion and because of a pattern that could not be
     * evaluated, the status is this one and the message also names the pattern.
     */
    val UndefinedRecursion: Iri = Iri(NAMESPACE + "UndefinedRecursion")

    /**
     * `ksh:PatternTimeout`: the constraint could not be decided because one `sh:pattern` evaluation used up
     * its budget ([ValidationConfig.patternTimeout], counted in steps of the regular expression engine) on a value
     * ([ValidationViolation.isPatternTimeout]). Whether the value
     * matches is unknown; it is not accepted as conforming.
     */
    val PatternTimeout: Iri = Iri(NAMESPACE + "PatternTimeout")

    /**
     * `ksh:PatternTooComplex`: the constraint could not be decided because the regular expression engine ran out of
     * stack while matching a `sh:pattern` against a value ([ValidationViolation.isPatternTooComplex]), typically an
     * alternation under a quantifier (`(a|b)*`) on a very long value. The value is not accepted as conforming.
     */
    val PatternTooComplex: Iri = Iri(NAMESPACE + "PatternTooComplex")

    /**
     * `ksh:reifier`: on a result of `sh:ReifierShapeConstraintComponent`, the reifier (the subject of the
     * `rdf:reifies` triple) that does not conform to the `sh:reifierShape`. `sh:value` of such a result is the object
     * of the reified triple, so several failing reifiers of one triple differ only by this property.
     */
    val reifier: Iri = Iri(NAMESPACE + "reifier")

    /**
     * `ksh:warning`: on the `sh:ValidationReport`, the message of a report-level [ValidationWarning] (one that is not
     * about a particular resource), e.g. a construct skipped under [UnsupportedFeatureHandling.IGNORE_WITH_WARNING].
     * Such warnings do not affect `sh:conforms`; without this property a conforming RDF report would carry no trace
     * of what was not validated.
     */
    val warning: Iri = Iri(NAMESPACE + "warning")

    /** Every term of the vocabulary, in the order of the vocabulary document. */
    val terms: List<Iri> = listOf(resultStatus, ResultStatusClass, UndefinedRecursion, PatternTimeout, PatternTooComplex, reifier, warning)
}
