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
     * `owl:Restriction` with `owl:onProperty p` attached to C via `rdfs:subClassOf` / `owl:equivalentClass`,
     * directly or as an operand of an `owl:intersectionOf` / `owl:unionOf` class expression (nested to any depth).
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
    /** Depth below owl:Thing (roots are 1; cycle participants 0), capped at the configured maximum. */
    val ditDepthOf: Map<String, Int>,
    /** Number of owl:Thing-to-leaf paths over the acyclic part of the hierarchy; saturates at [Long.MAX_VALUE]. */
    val pathsFromThingToLeaves: Long,
    /** Same as [pathsFromThingToLeaves] as a double (does not saturate for very tangled hierarchies). */
    val pathCount: Double,
    /** Sum of the edge lengths of all owl:Thing-to-leaf paths, counting the owl:Thing edge (LCOMOnto numerator). */
    val totalPathLength: Double,
    /**
     * CBOOnto related classes per class: direct named superclasses plus classes associated through a property
     * (named-class `rdfs:range` of a property whose `rdfs:domain` is C, or the named-class filler of a
     * restriction on C). Classes with no related class are absent.
     */
    val couplingsOf: Map<String, Set<String>> = emptyMap(),
    /**
     * Work done by the depth / path pass over the hierarchy: one step per class taken from the queue plus one per
     * subclass edge followed. At most `|classes| + |edges|` whatever the number of paths; tests bound it.
     */
    val hierarchyTraversalSteps: Long = 0,
)
