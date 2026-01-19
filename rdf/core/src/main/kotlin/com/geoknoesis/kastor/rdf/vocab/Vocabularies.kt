package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * Main vocabulary index providing access to all RDF vocabularies.
 * This object serves as a central point for accessing vocabulary terms
 * and provides utility functions for working with multiple vocabularies.
 */
object Vocabularies {
    
    /**
     * Get all available vocabularies.
     */
    val all: List<Vocabulary> = listOf(
        RDF, XSD, RDFS, OWL, SHACL, SKOS, DCTERMS, FOAF, DCAT, SPARQL_SD, SPARQL12
    )
    
    /**
     * Find a vocabulary by its prefix.
     */
    fun findByPrefix(prefix: String): Vocabulary? {
        return all.find { it.prefix == prefix }
    }
    
    /**
     * Find a vocabulary by its namespace.
     */
    fun findByNamespace(namespace: String): Vocabulary? {
        return all.find { it.namespace == namespace }
    }
    
    /**
     * Find which vocabulary a term belongs to.
     */
    fun findVocabularyForTerm(term: Iri): Vocabulary? {
        return all.find { it.contains(term) }
    }
    
    /**
     * Get the local name of a term from any vocabulary.
     */
    fun getLocalName(term: Iri): String? {
        return findVocabularyForTerm(term)?.localname(term)
    }
    
    /**
     * Check if a term belongs to any of the known vocabularies.
     */
    fun isKnownTerm(term: Iri): Boolean {
        return findVocabularyForTerm(term) != null
    }
    
    /**
     * Get all terms from a specific vocabulary by prefix.
     * Note: This is a simplified implementation that returns null.
     * For full reflection-based implementation, additional setup is required.
     */
    @Suppress("UNUSED_PARAMETER")
    fun getTermsByPrefix(prefix: String): Map<String, Iri>? {
        // Simplified implementation - returns null for now
        // In a full implementation, this would use reflection to get all properties
        return null
    }
    
    /**
     * Get all terms from a specific vocabulary by namespace.
     */
    fun getTermsByNamespace(namespace: String): Map<String, Iri>? {
        val vocab = findByNamespace(namespace) ?: return null
        return getTermsByPrefix(vocab.prefix)
    }
}









