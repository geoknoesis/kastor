package com.geoknoesis.kastor.gen.processor.internal.utils

import com.squareup.kotlinpoet.CodeBlock

/**
 * Centralized constants for code generation.
 * Eliminates magic strings throughout the codebase.
 */
internal object CodegenConstants {
    const val RDF_PACKAGE = "com.geoknoesis.kastor.rdf"
    const val RDF_PROVIDER_PACKAGE = "com.geoknoesis.kastor.rdf.provider"
    const val RUNTIME_PACKAGE = "com.geoknoesis.kastor.gen.runtime"
    const val VOCAB_PACKAGE = "com.geoknoesis.kastor.rdf.vocab"
    
    /**
     * Creates a CodeBlock for an IRI constant.
     * Uses vocabulary constant if available, otherwise creates Iri() call.
     */
    fun iriConstant(iri: String): CodeBlock {
        val constant = VocabularyMapper.getVocabularyConstant(iri)
        return if (constant != null) {
            CodeBlock.of("%L", constant)
        } else {
            CodeBlock.of("Iri(%S)", iri)
        }
    }
}


