package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RdfStarTest {
    
    @Test
    fun `embedded function creates correct RdfStarTriple`() {
        val alice = iri("http://example.org/alice")
        val bob = iri("http://example.org/bob")
        
        val embeddedTriple = embedded(alice, FOAF.knows, bob)
        
        assertTrue(embeddedTriple is RdfStarTriple)
        assertNotNull(embeddedTriple)
        assertEquals(alice, embeddedTriple.subject)
        assertEquals(FOAF.knows, embeddedTriple.predicate)
        assertEquals(bob, embeddedTriple.obj)
    }
    
    @Test
    fun `provider capabilities indicate RDF-star support`() {
        val repo = Rdf.memory()
        val capabilities = repo.getCapabilities()
        
        assertTrue(capabilities.supportsRdfStar, "Memory provider should support RDF-star")
    }
    
    @Test
    fun `embedded triple with invalid subject throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Rdf.graph {
                // Literal cannot be a subject in embedded triple
                val invalidEmbedded = embedded("Alice", FOAF.knows, iri("http://example.org/bob"))
                // Try to use the invalid embedded triple in a triple - this should trigger the error
                iri("http://example.org/test") - DCTERMS.source - invalidEmbedded
            }
        }
        
        assertTrue(exception.message?.contains("subject must be a resource") == true)
    }
    
    @Test
    fun `embedded triple with invalid predicate throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Rdf.graph {
                // Literal cannot be a predicate in embedded triple
                val invalidEmbedded = embedded(iri("http://example.org/alice"), "Alice", iri("http://example.org/bob"))
                // Try to use the invalid embedded triple in a triple - this should trigger the error
                iri("http://example.org/test") - DCTERMS.source - invalidEmbedded
            }
        }
        
        assertTrue(exception.message?.contains("predicate must be an IRI") == true)
    }
}