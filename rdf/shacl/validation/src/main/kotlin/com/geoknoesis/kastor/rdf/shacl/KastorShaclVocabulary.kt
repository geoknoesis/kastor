package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri

/**
 * Kastor extension vocabulary for SHACL validation reports (prefix `ksh:`).
 *
 * [ValidationReport.toShaclValidationReportRdf] uses these terms for information the SHACL validation report
 * vocabulary has no property for. They are additional triples on `sh:ValidationResult` nodes: consumers that only
 * know the `sh:` vocabulary can ignore them, and nothing is ever minted in the `sh:` namespace.
 */
object KastorShaclVocabulary {
    /** Namespace of the Kastor SHACL extension terms. */
    const val NAMESPACE: String = "https://kastor.geoknoesis.com/ns/shacl#"

    /** Conventional prefix for [NAMESPACE]. */
    const val PREFIX: String = "ksh"

    /**
     * `ksh:resultStatus`: qualifies a `sh:ValidationResult` that does not report a definite constraint failure. The
     * only value is [UndefinedRecursion]; a result without this property is an ordinary failure.
     */
    val resultStatus: Iri = Iri(NAMESPACE + "resultStatus")

    /**
     * `ksh:UndefinedRecursion`: the result reports that the constraint could **not be decided** because it depends
     * on a recursive shape dependency through a non-monotone operator (`sh:not`, `sh:xone`, `sh:qualifiedMaxCount`,
     * disjoint qualified value shapes), which SHACL leaves undefined ([ValidationViolation.isUndefinedRecursion]).
     * The result keeps the severity and constraint component a failure would have.
     */
    val UndefinedRecursion: Iri = Iri(NAMESPACE + "UndefinedRecursion")

    /**
     * `ksh:reifier`: on a result of `sh:ReifierShapeConstraintComponent`, the reifier (the subject of the
     * `rdf:reifies` triple) that does not conform to the `sh:reifierShape`. `sh:value` of such a result is the object
     * of the reified triple, so several failing reifiers of one triple differ only by this property.
     */
    val reifier: Iri = Iri(NAMESPACE + "reifier")
}
