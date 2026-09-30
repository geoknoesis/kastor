package com.geoknoesis.kastor.ontoquality.metrics

object KastorMetricsVocab {
    const val NS = "https://w3id.org/kastor/metrics#"
    const val OQUARE_NS = "https://w3id.org/kastor/metrics/oquare#"

    const val depthOfInheritanceTree = "${NS}depthOfInheritanceTree"
    const val numberOfAncestorClasses = "${NS}numberOfAncestorClasses"

    /** NOCOnto, published OQuaRE formula (Σ|Sub_C| / (|C| − |Root|)). */
    const val numberOfChildrenOquare = "${NS}numberOfChildrenOquare"

    /** CBOOnto, published OQuaRE formula (Σ|Sup_C| / (|C| − |Root|)). */
    const val couplingBetweenObjectsOquare = "${NS}couplingBetweenObjectsOquare"

    /**
     * Retired: in 0.2.x this IRI carried a different formula (Σ|Sub_C| / (|C| − 1)). The published OQuaRE metric is [numberOfChildrenOquare];
     * a metric IRI never changes meaning, so this one is no longer emitted.
     */
    @Deprecated("Retired 0.2.x metric (Σ|Sub_C| / (|C| − 1)); use numberOfChildrenOquare", ReplaceWith("KastorMetricsVocab.numberOfChildrenOquare"))
    const val numberOfChildren = "${NS}numberOfChildren"

    /**
     * Retired: in 0.2.x this IRI carried a different formula (Σ|Sup_C| / (|C| − 1)). The published OQuaRE metric is [couplingBetweenObjectsOquare];
     * a metric IRI never changes meaning, so this one is no longer emitted.
     */
    @Deprecated("Retired 0.2.x metric (Σ|Sup_C| / (|C| − 1)); use couplingBetweenObjectsOquare", ReplaceWith("KastorMetricsVocab.couplingBetweenObjectsOquare"))
    const val couplingBetweenObjects = "${NS}couplingBetweenObjects"

    const val weightedMethodCount = "${NS}weightedMethodCount"
    const val responseForClass = "${NS}responseForClass"
    const val numberOfProperties = "${NS}numberOfProperties"
    const val lackOfCohesionInMethods = "${NS}lackOfCohesionInMethods"
    const val relationshipRichness = "${NS}relationshipRichness"
    const val inheritanceRichness = "${NS}inheritanceRichness"
    const val attributeRichness = "${NS}attributeRichness"
    const val classRichness = "${NS}classRichness"
    const val annotationRichness = "${NS}annotationRichness"
    const val propertiesRichness = "${NS}propertiesRichness"

    /** TMOnto, published OQuaRE formula (|C_DP| / |C|). */
    const val tanglednessOquare = "${NS}tanglednessOquare"

    /**
     * Retired: in 0.2.x this IRI carried a different formula (owl:Thing-to-leaf paths / |leaves|). The published OQuaRE metric is [tanglednessOquare];
     * a metric IRI never changes meaning, so this one is no longer emitted.
     */
    @Deprecated("Retired 0.2.x metric (paths / leaves); use tanglednessOquare", ReplaceWith("KastorMetricsVocab.tanglednessOquare"))
    const val tangledness = "${NS}tangledness"

    /** Kastor-adapted variants (not OQuaRE); see [KastorAdaptedMetrics]. */
    const val numberOfChildrenKastor = "${NS}numberOfChildrenKastor"
    const val couplingBetweenObjectsKastor = "${NS}couplingBetweenObjectsKastor"
    const val tanglednessKastor = "${NS}tanglednessKastor"

    const val conceptCount = "${NS}conceptCount"
    const val prefLabelCoverage = "${NS}prefLabelCoverage"
    const val definitionCoverage = "${NS}definitionCoverage"
    const val orphanConceptCount = "${NS}orphanConceptCount"
    const val siblingCohortCount = "${NS}siblingCohortCount"
    const val maxSiblingCohortSize = "${NS}maxSiblingCohortSize"

    const val oquareDITOnto = "${OQUARE_NS}DITOnto"
    const val oquareNACOnto = "${OQUARE_NS}NACOnto"
    const val oquareNOCOnto = "${OQUARE_NS}NOCOnto"
    const val oquareCBOOnto = "${OQUARE_NS}CBOOnto"
    const val oquareWMCOnto = "${OQUARE_NS}WMCOnto"
    const val oquareRFCOnto = "${OQUARE_NS}RFCOnto"
    const val oquareNOMOnto = "${OQUARE_NS}NOMOnto"
    const val oquareLCOMOnto = "${OQUARE_NS}LCOMOnto"
    const val oquareRROnto = "${OQUARE_NS}RROnto"
    const val oquareINROnto = "${OQUARE_NS}INROnto"
    const val oquareAROnto = "${OQUARE_NS}AROnto"
    const val oquareCROnto = "${OQUARE_NS}CROnto"
    const val oquareANOnto = "${OQUARE_NS}ANOnto"
    const val oquarePROnto = "${OQUARE_NS}PROnto"
    const val oquareTMOnto = "${OQUARE_NS}TMOnto"

    const val oquareScoring = "${OQUARE_NS}DuqueRamos2014Scoring"

    /** Score bands of the Kastor-adapted variants (documented in the module README and `kastor-metrics.ttl`). */
    const val kastorAdaptedScoring = "${NS}KastorAdaptedScoring"
}
