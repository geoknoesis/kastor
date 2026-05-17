package com.geoknoesis.kastor.ontoquality.metrics

data class MetricsConfig(
    val emitOQuaREScores: Boolean = true,
    val useInferredGraph: Boolean = false,
    val maxDepthCap: Int = 50,
    val topNHotSpots: Int = 20,
    /**
     * When `true`, subclass fan-out metrics include an uncapped [SubClassFanOutMetrics.fullFanOutMap]
     * and full parent→children adjacency ([SubClassFanOutMetrics.fullSubClassChildrenOf]), and hierarchy
     * depth metrics include [HierarchyDepthMetrics.depthByClass]. Intended for downstream consumers
     * (e.g. SHACL finding prioritization); produces larger in-memory reports and JSON payloads.
     */
    val unboundedFanOutBreakdown: Boolean = false,
    val excludedNamespaces: Set<String> = DEFAULT_EXCLUDED_NAMESPACES,
    val includePerParentBreakdowns: Boolean = true,
    val includeSubPropertyMetrics: Boolean = false,
) {
    companion object {
        fun default(): MetricsConfig = MetricsConfig()

        val DEFAULT_EXCLUDED_NAMESPACES: Set<String> =
            setOf(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                "http://www.w3.org/2000/01/rdf-schema#",
                "http://www.w3.org/2002/07/owl#",
                "http://www.w3.org/2001/XMLSchema#",
                "http://www.w3.org/2004/02/skos/core#",
                "http://purl.org/dc/terms/",
                "http://www.w3.org/ns/dcat#",
                "http://xmlns.com/foaf/0.1/",
                "http://www.w3.org/ns/prov#",
                "http://www.w3.org/ns/shacl#",
                "http://purl.org/vocab/vann/",
                "http://creativecommons.org/ns#",
            )
    }
}
