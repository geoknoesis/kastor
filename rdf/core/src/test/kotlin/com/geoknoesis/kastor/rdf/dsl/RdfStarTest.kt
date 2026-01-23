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
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")
        
        val embeddedTriple = embedded(alice, FOAF.knows, bob)
        
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
    
    // Invalid embedded triples are now prevented at compile time by type signatures.
}









