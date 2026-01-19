package com.example.ontomapper.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class KastorGraphOpsTest {

    @Test
    fun `getLiteralValues returns literals for subject and predicate`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John Doe"
            subject - FOAF.name - "Johnny"
            subject - FOAF.age - 30
            subject - DCTERMS.description - "A person"
        }
        
        val literals = KastorGraphOps.getLiteralValues(repo.defaultGraph, subject, FOAF.name)
        
        assertEquals(2, literals.size)
        assertTrue(literals.any { it.lexical == "John Doe" })
        assertTrue(literals.any { it.lexical == "Johnny" })
    }
    
    @Test
    fun `getLiteralValues returns empty list when no matches`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John Doe"
        }
        
        val literals = KastorGraphOps.getLiteralValues(repo.defaultGraph, subject, FOAF.age)
        
        assertTrue(literals.isEmpty())
    }
    
    @Test
    fun `getLiteralValues only returns literal objects`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        val friend = Iri("http://example.org/friend")
        
        repo.add {
            subject - FOAF.name - "John Doe"
            subject - FOAF.knows - friend
        }
        
        val literals = KastorGraphOps.getLiteralValues(repo.defaultGraph, subject, FOAF.knows)
        
        // Should not include the IRI object
        assertTrue(literals.isEmpty())
    }
    
    @Test
    fun `getRequiredLiteralValue returns first literal`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John Doe"
            subject - FOAF.name - "Johnny"
        }
        
        val literal = KastorGraphOps.getRequiredLiteralValue(repo.defaultGraph, subject, FOAF.name)
        
        assertEquals("John Doe", literal.lexical)
    }
    
    @Test
    fun `getRequiredLiteralValue throws when no literals found`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John Doe"
        }
        
        assertThrows(IllegalStateException::class.java) {
            KastorGraphOps.getRequiredLiteralValue(repo.defaultGraph, subject, FOAF.age)
        }
    }
    
    @Test
    fun `getObjectValues applies factory function to object terms`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        val friend1 = Iri("http://example.org/friend1")
        val friend2 = Iri("http://example.org/friend2")
        
        repo.add {
            subject - FOAF.knows - friend1
            subject - FOAF.knows - friend2
            subject - FOAF.name - "John Doe"
        }
        
        val objects = KastorGraphOps.getObjectValues(repo.defaultGraph, subject, FOAF.knows) { term ->
            when (term) {
                is Iri -> "Iri: ${term.value}"
                is BlankNode -> "BlankNode: ${term.id}"
                else -> "Other: $term"
            }
        }
        
        assertEquals(2, objects.size)
        assertTrue(objects.contains("Iri: http://example.org/friend1"))
        assertTrue(objects.contains("Iri: http://example.org/friend2"))
    }
    
    @Test
    fun `getObjectValues returns empty list when no matches`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John Doe"
        }
        
        val objects = KastorGraphOps.getObjectValues(repo.defaultGraph, subject, FOAF.knows) { term ->
            "Object: $term"
        }
        
        assertTrue(objects.isEmpty())
    }
    
    @Test
    fun `getObjectValues handles factory exceptions gracefully`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        val friend = Iri("http://example.org/friend")
        
        repo.add {
            subject - FOAF.knows - friend
            subject - FOAF.name - "John Doe"
        }
        
        val objects = KastorGraphOps.getObjectValues(repo.defaultGraph, subject, FOAF.knows) { term ->
            when (term) {
                is Iri -> if (term.value.contains("friend")) throw RuntimeException("Bad friend") else "Good: $term"
                else -> "Other: $term"
            }
        }
        
        // Should exclude the problematic object
        assertTrue(objects.isEmpty())
    }
    
    @Test
    fun `extras creates PropertyBag with correct parameters`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John Doe"
            subject - FOAF.age - 30
        }
        
        val known = setOf(FOAF.name)
        val propertyBag = KastorGraphOps.extras(repo.defaultGraph, subject, known)
        
        assertNotNull(propertyBag)
        
        // Should exclude known predicate
        assertFalse(propertyBag.predicates().contains(FOAF.name))
        
        // Should include unknown predicate
        assertTrue(propertyBag.predicates().contains(FOAF.age))
    }
}












