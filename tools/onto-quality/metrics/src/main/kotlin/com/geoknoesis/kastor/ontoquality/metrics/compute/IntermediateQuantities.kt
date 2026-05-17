package com.geoknoesis.kastor.ontoquality.metrics.compute

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
    val propertiesByDomain: Map<String, Set<String>>,
    val datatypePropertyDomainAssertions: Long,
    val annotationAssertionsOnClasses: Long,
    val classesWithInstances: Set<String>,
    val subClassEdgeCount: Long,
    val nonSubClassEdgeCount: Long,
    val ditDepthOf: Map<String, Int>,
    val pathsFromThingToLeaves: Long,
)
