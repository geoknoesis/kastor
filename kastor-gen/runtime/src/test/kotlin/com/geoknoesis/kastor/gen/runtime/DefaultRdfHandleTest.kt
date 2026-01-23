package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DefaultRdfHandleTest {

    @Test
    fun `DefaultRdfHandle provides access to node and graph`() {
        val repo = Rdf.memory()
        val node = Iri("http://example.org/person")
        
        repo.add {
            node - FOAF.name - "John Doe"
            node - FOAF.age - 30
        }
        
        val known = setOf(FOAF.name)
        val handle = DefaultRdfHandle(node, repo.defaultGraph, known)
        
        assertEquals(node, handle.node)
        assertEquals(repo.defaultGraph, handle.graph)
    }
    
    @Test
    fun `DefaultRdfHandle extras exclude known predicates`() {
        val repo = Rdf.memory()
        val node = Iri("http://example.org/person")
        
        repo.add {
            node - FOAF.name - "John Doe"
            node - FOAF.age - 30
            node - DCTERMS.description - "A person"
        }
        
        val known = setOf(FOAF.name)
        val handle = DefaultRdfHandle(node, repo.defaultGraph, known)
        val extras = handle.extras
        
        // Should not include known predicate
        assertFalse(extras.predicates().contains(FOAF.name))
        
        // Should include unknown predicates
        assertTrue(extras.predicates().contains(FOAF.age))
        assertTrue(extras.predicates().contains(DCTERMS.description))
    }
    
    @Test
    fun `DefaultRdfHandle extras are lazy and memoized`() {
        val repo = Rdf.memory()
        val node = Iri("http://example.org/person")
        
        repo.add {
            node - FOAF.name - "John Doe"
        }
        
        val known = emptySet<Iri>()
        val handle = DefaultRdfHandle(node, repo.defaultGraph, known)
        
        // Multiple calls should return the same instance
        val extras1 = handle.extras
        val extras2 = handle.extras
        
        assertSame(extras1, extras2)
    }
    
    @Test
    fun `DefaultRdfHandle validate delegates to validation context`() {
        val repo = Rdf.memory()
        val node = Iri("http://example.org/person")
        
        repo.add {
            node - FOAF.name - "John Doe"
        }
        
        val validator = object : ValidationContext {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
                return ValidationResult.Violations(listOf(
                  ShaclViolation(
                    focusNode = focus as? RdfResource ?: Iri.of("http://example.org/unknown"),
                    shapeIri = com.geoknoesis.kastor.rdf.vocab.SHACL.Shape,
                    constraintIri = com.geoknoesis.kastor.rdf.vocab.SHACL.ConstraintComponent,
                    message = "Test validation error"
                  )
                ))
            }
        }
        val known = emptySet<Iri>()
        val handle = DefaultRdfHandle(node, repo.defaultGraph, known, validationContext = validator)

        val result = handle.validate()
        assertTrue(result is ValidationResult.Violations)
    }

    @Test
    fun `DefaultRdfHandle validate throws when no context`() {
        val repo = Rdf.memory()
        val node = Iri("http://example.org/person")

        repo.add {
            node - FOAF.name - "John Doe"
        }

        val handle = DefaultRdfHandle(node, repo.defaultGraph, known = emptySet())
        assertThrows(IllegalStateException::class.java) {
            handle.validate()
        }
    }
    
    @Test
    fun `DefaultRdfHandle works with empty known set`() {
        val repo = Rdf.memory()
        val node = Iri("http://example.org/person")
        
        repo.add {
            node - FOAF.name - "John Doe"
            node - FOAF.age - 30
        }
        
        val known = emptySet<Iri>()
        val handle = DefaultRdfHandle(node, repo.defaultGraph, known)
        val extras = handle.extras
        
        // Should include all predicates when known set is empty
        assertTrue(extras.predicates().contains(FOAF.name))
        assertTrue(extras.predicates().contains(FOAF.age))
        assertEquals(2, extras.predicates().size)
    }
}












