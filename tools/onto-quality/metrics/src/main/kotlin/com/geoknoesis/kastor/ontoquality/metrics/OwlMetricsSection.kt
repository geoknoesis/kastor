package com.geoknoesis.kastor.ontoquality.metrics

data class OwlMetricsSection(
    val entityCounts: OwlEntityCounts,
    /** The 15 OQuaRE metrics, computed with the published OQuaRE definitions and scored with the OQuaRE bands. */
    val oquare: OquareMetrics,
    val extensions: OwlExtensions,
    /** Kastor-adapted variants of NOCOnto, CBOOnto and TMOnto. They are **not** OQuaRE metrics (see [KastorAdaptedMetrics]). */
    val kastorAdapted: KastorAdaptedMetrics,
)

data class OwlEntityCounts(
    val owlClasses: Long,
    val rdfsClasses: Long,
    val owlObjectProperties: Long,
    val owlDatatypeProperties: Long,
    val owlAnnotationProperties: Long,
    val owlOntologies: Long,
    val owlNamedIndividuals: Long,
    val owlRestrictions: Long,
    val totalNamedClasses: Long,
    val totalProperties: Long,
)

data class OquareMetrics(
    val depthOfInheritanceTree: MetricValue,
    val numberOfAncestorClasses: MetricValue,
    val numberOfChildren: MetricValue,
    val couplingBetweenObjects: MetricValue,
    val weightedMethodCount: MetricValue,
    val responseForClass: MetricValue,
    val numberOfProperties: MetricValue,
    val lackOfCohesionInMethods: MetricValue,
    val relationshipRichness: MetricValue,
    val inheritanceRichness: MetricValue,
    val attributeRichness: MetricValue,
    val classRichness: MetricValue,
    val annotationRichness: MetricValue,
    val propertiesRichness: MetricValue,
    val tangledness: MetricValue,
) {
    fun toList(): List<MetricValue> =
        listOf(
            depthOfInheritanceTree,
            numberOfAncestorClasses,
            numberOfChildren,
            couplingBetweenObjects,
            weightedMethodCount,
            responseForClass,
            numberOfProperties,
            lackOfCohesionInMethods,
            relationshipRichness,
            inheritanceRichness,
            attributeRichness,
            classRichness,
            annotationRichness,
            propertiesRichness,
            tangledness,
        )
}

/**
 * Kastor-adapted structural metrics, kept next to (not instead of) their OQuaRE counterparts. They use their own
 * names (`NOCOntoKastor`, `CBOOntoKastor`, `TMOntoKastor`), IRIs and documented score bands, so their values are not
 * comparable with OQuaRE scores from other tools.
 *
 * - [numberOfChildren] `NOCOntoKastor` = Σ|Sub_C| / |{C : Sub_C ≠ ∅}| — fan-out of the classes that have subclasses.
 * - [couplingBetweenObjects] `CBOOntoKastor` = Σ|Sup_C ∪ Assoc_C| / |C| — superclasses plus property-associated classes.
 * - [tangledness] `TMOntoKastor` = mean number of direct superclasses of classes with more than one; 0 without
 *   multiple inheritance.
 */
data class KastorAdaptedMetrics(
    val numberOfChildren: MetricValue,
    val couplingBetweenObjects: MetricValue,
    val tangledness: MetricValue,
) {
    fun toList(): List<MetricValue> = listOf(numberOfChildren, couplingBetweenObjects, tangledness)
}

data class OwlExtensions(
    val subClassFanOut: SubClassFanOutMetrics,
    val classHierarchyDepth: HierarchyDepthMetrics,
    val imports: ImportsMetrics,
    val ontologyHeaders: List<OntologyHeader>,
    /** Named OWL class IRIs tracked during metrics scan; populated when using unbounded fan-out integration mode. */
    val integrationNamedClasses: Set<String> = emptySet(),
)

data class SubClassFanOutMetrics(
    val parentsWithChildren: Long,
    val totalDirectEdges: Long,
    val maxFanOut: Int,
    val maxFanOutParents: List<String>,
    val meanFanOut: Double,
    val medianFanOut: Double,
    val topNByFanOut: List<FanOutEntry>,
    val fanOutDistribution: FanOutDistribution,
    /** Present when [MetricsConfig.unboundedFanOutBreakdown]; parent IRI → direct subclass count. */
    val fullFanOutMap: Map<String, Int> = emptyMap(),
    /** Present when [MetricsConfig.unboundedFanOutBreakdown]; parent → direct child IRIs (non-empty child sets only). */
    val fullSubClassChildrenOf: Map<String, Set<String>> = emptyMap(),
)

data class FanOutEntry(val parent: String, val directChildCount: Int)

data class FanOutDistribution(
    val singleChild: Long,
    val twoToFive: Long,
    val sixToTwenty: Long,
    val twentyOneToFifty: Long,
    val overFifty: Long,
)

data class HierarchyDepthMetrics(
    val maxDepthFound: Int,
    val deepestChainExample: List<String>?,
    val classesAtMaxDepth: Long,
    val cyclesDetected: Long,
    val cycleParticipants: List<String>,
    val depthCapHit: Boolean,
    /** Named class → DITOnto depth when [MetricsConfig.unboundedFanOutBreakdown]. */
    val depthByClass: Map<String, Int> = emptyMap(),
)

data class ImportsMetrics(
    val importStatements: Long,
    val importedIris: List<String>,
    val versionedImports: Long,
    val unversionedImports: Long,
)

data class OntologyHeader(
    val ontologyIri: String,
    val hasLabel: Boolean,
    val hasComment: Boolean,
    val hasVersionIri: Boolean,
    val hasCreator: Boolean,
    val hasLicense: Boolean,
)
