package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.iri

/**
 * Base interface for RDF vocabularies.
 * Provides common functionality for vocabulary management and term access.
 */
interface Vocabulary {
    /**
     * The namespace URI for this vocabulary.
     */
    val namespace: String
    
    /**
     * The namespace prefix for this vocabulary.
     */
    val prefix: String
    
    /**
     * Get the local name part of a term.
     * @param term The full IRI term
     * @return The local name part, or null if the term doesn't belong to this vocabulary
     */
    fun localname(term: Iri): String? {
        return if (term.value.startsWith(namespace)) {
            term.value.substring(namespace.length)
        } else null
    }
    
    /**
     * Create an IRI for a term in this vocabulary.
     * @param localName The local name of the term
     * @return The full IRI for the term
     */
    fun term(localName: String): Iri {
        return iri(namespace + localName)
    }
    
    /**
     * Check if a term belongs to this vocabulary.
     * @param term The IRI to check
     * @return true if the term belongs to this vocabulary
     */
    fun contains(term: Iri): Boolean {
        return term.value.startsWith(namespace)
    }
}
