package com.example.ontomapper.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PropertyBagImplTest {

    @Test
    fun `property bag excludes known predicates and returns deterministic order`() {
        val repo = Rdf.memory()
        val subject = iri("http://example.org/person")
        val knownPredicates = setOf(DCTERMS.title, FOAF.name)
        
        repo.add {
            subject - DCTERMS.title - "John Doe"
            subject - FOAF.name - "John"
            subject - FOAF.age - 30
            subject - DCTERMS.description - "A person"
        }
        
        val propertyBag = KastorGraphOps.extras(repo.defaultGraph, subject, knownPredicates)
        val predicates = propertyBag.predicates()
        
        // Should exclude known predicates
        assertFalse(predicates.contains(DCTERMS.title))
        assertFalse(predicates.contains(FOAF.name))
        
        // Should include unknown predicates
        assertTrue(predicates.contains(FOAF.age))
        assertTrue(predicates.contains(DCTERMS.description))
        
        // Should be in deterministic order (lexicographic by IRI)
        val predicateList = predicates.toList()
        assertTrue(predicateList.indexOf(DCTERMS.description) < predicateList.indexOf(FOAF.age))
    }
    
    @Test
    fun `property bag returns correct values for different types`() {
        val repo = Rdf.memory()
        val subject = iri("http://example.org/resource")
        
        repo.add {
            subject - DCTERMS.title - "Test Title"
            subject - FOAF.age - 25
            subject - DCTERMS.creator - iri("http://example.org/creator")
        }
        
        val propertyBag = KastorGraphOps.extras(repo.defaultGraph, subject, emptySet())
        
        // Test strings
        val titles = propertyBag.strings(DCTERMS.title)
        assertEquals(listOf("Test Title"), titles)
        
        // Test literals
        val ages = propertyBag.literals(FOAF.age)
        assertEquals(1, ages.size)
        assertEquals("25", ages.first().lexical)
        
        // Test IRIs
        val creators = propertyBag.iris(DCTERMS.creator)
        assertEquals(1, creators.size)
        assertEquals("http://example.org/creator", creators.first().value)
        
        // Test all values
        val allValues = propertyBag.values(DCTERMS.title)
        assertEquals(1, allValues.size)
        assertTrue(allValues.first() is Literal)
    }
}
