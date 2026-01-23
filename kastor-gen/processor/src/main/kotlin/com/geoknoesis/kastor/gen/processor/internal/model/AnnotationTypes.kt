package com.geoknoesis.kastor.gen.processor.internal.model

/**
 * Sealed class representing annotation types for code generation.
 */
internal sealed class GenerationAnnotationType(val name: String) {
    object FromOntology : GenerationAnnotationType("GenerateFromOntology")
    object InstanceDsl : GenerationAnnotationType("GenerateInstanceDsl")
    
    companion object {
        const val FROM_ONTOLOGY_FQN = "com.geoknoesis.kastor.gen.annotations.GenerateFromOntology"
        const val INSTANCE_DSL_FQN = "com.geoknoesis.kastor.gen.annotations.GenerateInstanceDsl"
        
        fun fromName(name: String): GenerationAnnotationType? {
            return when (name) {
                "GenerateFromOntology" -> FromOntology
                "GenerateInstanceDsl" -> InstanceDsl
                else -> null
            }
        }
    }
}


