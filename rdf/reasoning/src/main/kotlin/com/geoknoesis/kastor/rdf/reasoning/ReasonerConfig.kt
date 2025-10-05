package com.geoknoesis.kastor.rdf.reasoning

import java.time.Duration

/**
 * Reasoner configuration.
 */
data class ReasonerConfig(
    val reasonerType: ReasonerType,
    val enabledRules: Set<ReasoningRule> = getDefaultRulesForType(reasonerType),
    val timeout: Duration = Duration.ofMinutes(5),
    val includeAxioms: Boolean = true,
    val customRules: List<CustomRule> = emptyList(),
    val parameters: Map<String, Any> = emptyMap(),
    
    // Large-scale reasoning options
    val streamingMode: Boolean = true,
    val batchSize: Long = 10_000L,
    val maxMemoryUsage: Long = 1024L * 1024L * 1024L, // 1GB
    val enableIncrementalReasoning: Boolean = false,
    val cacheResults: Boolean = false,
    val materializationThreshold: Long = 1_000_000L
) {
    companion object {
        fun default(): ReasonerConfig = ReasonerConfig(
            reasonerType = ReasonerType.RDFS,
            enabledRules = ReasoningRule.values().toSet()
        )
        
        fun rdfs(): ReasonerConfig = ReasonerConfig(
            reasonerType = ReasonerType.RDFS,
            enabledRules = setOf(
                ReasoningRule.RDFS_SUBCLASS,
                ReasoningRule.RDFS_SUBPROPERTY,
                ReasoningRule.RDFS_DOMAIN,
                ReasoningRule.RDFS_RANGE
            )
        )
        
        fun owlEl(): ReasonerConfig = ReasonerConfig(
            reasonerType = ReasonerType.OWL_EL
        )
        
        fun owlDl(): ReasonerConfig = ReasonerConfig(
            reasonerType = ReasonerType.OWL_DL,
            timeout = Duration.ofMinutes(10)
        )
        
        fun forLargeGraphs(): ReasonerConfig = ReasonerConfig(
            reasonerType = ReasonerType.RDFS,
            streamingMode = true,
            batchSize = 50_000L,
            maxMemoryUsage = 2L * 1024L * 1024L * 1024L, // 2GB
            enableIncrementalReasoning = true,
            cacheResults = true,
            materializationThreshold = 100_000L
        )
        
        fun forMemoryConstrained(): ReasonerConfig = ReasonerConfig(
            reasonerType = ReasonerType.RDFS,
            streamingMode = true,
            batchSize = 1_000L,
            maxMemoryUsage = 256L * 1024L * 1024L, // 256MB
            enableIncrementalReasoning = true,
            cacheResults = false,
            materializationThreshold = 10_000L
        )
    }
}

enum class ReasonerType {
    RDFS, OWL_EL, OWL_QL, OWL_RL, OWL_DL, CUSTOM, PELLET, HERMIT, FACT_PLUS_PLUS
}

enum class ReasoningRule {
    // RDFS Rules
    RDFS_SUBCLASS, RDFS_SUBPROPERTY, RDFS_DOMAIN, RDFS_RANGE,
    
    // OWL Rules
    OWL_EQUIVALENT_CLASS, OWL_DISJOINT_CLASS, OWL_FUNCTIONAL_PROPERTY,
    OWL_INVERSE_FUNCTIONAL_PROPERTY, OWL_SYMMETRIC_PROPERTY,
    OWL_TRANSITIVE_PROPERTY, OWL_SAME_AS, OWL_DIFFERENT_FROM,
    OWL_INVERSE_OF, OWL_UNION_OF, OWL_INTERSECTION_OF,
    
    // Custom Rules
    CUSTOM_RULE
}

data class CustomRule(
    val name: String,
    val pattern: String, // SPARQL-like pattern
    val conclusion: String,
    val description: String = ""
)

/**
 * Get default rules for a reasoner type.
 */
private fun getDefaultRulesForType(type: ReasonerType): Set<ReasoningRule> {
    return when (type) {
        ReasonerType.RDFS -> setOf(
            ReasoningRule.RDFS_SUBCLASS,
            ReasoningRule.RDFS_SUBPROPERTY,
            ReasoningRule.RDFS_DOMAIN,
            ReasoningRule.RDFS_RANGE
        )
        ReasonerType.OWL_EL -> setOf(
            ReasoningRule.RDFS_SUBCLASS,
            ReasoningRule.RDFS_SUBPROPERTY,
            ReasoningRule.OWL_EQUIVALENT_CLASS,
            ReasoningRule.OWL_DISJOINT_CLASS
        )
        ReasonerType.OWL_RL -> setOf(
            ReasoningRule.RDFS_SUBCLASS,
            ReasoningRule.RDFS_SUBPROPERTY,
            ReasoningRule.OWL_FUNCTIONAL_PROPERTY,
            ReasoningRule.OWL_INVERSE_FUNCTIONAL_PROPERTY,
            ReasoningRule.OWL_SYMMETRIC_PROPERTY,
            ReasoningRule.OWL_TRANSITIVE_PROPERTY
        )
        ReasonerType.OWL_DL -> ReasoningRule.values().toSet()
        ReasonerType.CUSTOM -> emptySet()
        else -> emptySet()
    }
}
