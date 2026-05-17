package com.geoknoesis.kastor.rdf.reasoning

/**
 * Factory object for creating reasoners and accessing reasoning capabilities.
 * This is the main entry point for the Kastor reasoning framework.
 */
object RdfReasoning {
    
    /**
     * Create a reasoner with the given configuration.
     */
    fun reasoner(config: ReasonerConfig): RdfReasoner {
        return ReasonerRegistry.createReasoner(config)
    }
    
    /**
     * Create a reasoner by type.
     */
    fun reasoner(type: ReasonerType): RdfReasoner {
        return ReasonerRegistry.createReasoner(type)
    }
    
    /**
     * Get available reasoner providers.
     */
    fun reasonerProviders(): List<RdfReasonerProvider> {
        return ReasonerRegistry.getProviders()
    }
    
    /**
     * Get supported reasoner types.
     */
    fun supportedReasonerTypes(): List<ReasonerType> {
        return ReasonerRegistry.getSupportedTypes()
    }
    
    /**
     * Check if a reasoner type is supported.
     */
    fun isSupported(type: ReasonerType): Boolean {
        return ReasonerRegistry.isSupported(type)
    }
}









