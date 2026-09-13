package com.geoknoesis.kastor.ontoquality.metrics.compute

/**
 * Quantities shared by the OQuaRE calculators. All sets and counts are computed over the **asserted**
 * graph after namespace exclusion ([com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig.excludedNamespaces]).
 */
internal data class IntermediateQuantities(
    val namedClasses: Set<String>,
    val objectProperties: Set<String>,
    val datatypeProperties: Set<String>,
    val annotationProperties: Set<String>,
    val allProperties: Set<String>,
    val subClassChildrenOf: Map<String, Set<String>>,
    val superClassesOf: Map<String, Set<String>>,
    val leaves: Set<String>,
    val roots: Set<String>,
    val cycleParticipants: Set<String>,
    /**
     * P_C: declared object/datatype properties *used by* class C — `p rdfs:domain C`, or an
     * `owl:Restriction` with `owl:onProperty p` attached to C via `rdfs:subClassOf` / `owl:equivalentClass`.
     */
    val propertiesOfClass: Map<String, Set<String>>,
    /** Datatype properties whose `rdfs:domain` is a named class (AROnto attributes). */
    val datatypePropertyDomainAssertions: Long,
    val annotationAssertionsOnClasses: Long,
    val classesWithInstances: Set<String>,
    /** Distinct `(individual, named class)` rdf:type assertions. */
    val instanceAssertionCount: Long,
    /** Named-to-named rdfs:subClassOf edges (owl:Thing and excluded namespaces removed). */
    val subClassEdgeCount: Long,
    /** Sum of |P_C| over named classes (property usages; OQuaRE RROnto numerator). */
    val propertyUsageCount: Long,
    val ditDepthOf: Map<String, Int>,
    /** Number of root-to-leaf paths over the acyclic part of the hierarchy; saturates at [Long.MAX_VALUE]. */
    val pathsFromThingToLeaves: Long,
    /** Same as [pathsFromThingToLeaves] as a double (does not saturate for very tangled hierarchies). */
    val pathCount: Double,
    /** Sum of the edge lengths of all root-to-leaf paths (LCOMOnto numerator). */
    val totalPathLength: Double,
)
