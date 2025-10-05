package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TurtleStyleBasicTest {

    @Test
    fun `basic repository DSL works`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            person[FOAF.name] = "Alice"
            person[FOAF.age] = 30
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(2, allTriples.size, "Should have 2 triples")
        
        val nameTriple = allTriples.find { it.predicate == FOAF.name }
        val ageTriple = allTriples.find { it.predicate == FOAF.age }
        
        assertNotNull(nameTriple, "Should have name triple")
        assertNotNull(ageTriple, "Should have age triple")
        
        assertEquals("Alice", (nameTriple!!.obj as Literal).lexical, "Name should be Alice")
        assertEquals("30", (ageTriple!!.obj as Literal).lexical, "Age should be 30")
        
        repo.close()
    }
    
    @Test
    fun `minus operator DSL works`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            person - FOAF.name - "Alice"
            person - FOAF.age - 30
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(2, allTriples.size, "Should have 2 triples")
        
        val nameTriple = allTriples.find { it.predicate == FOAF.name }
        val ageTriple = allTriples.find { it.predicate == FOAF.age }
        
        assertNotNull(nameTriple, "Should have name triple")
        assertNotNull(ageTriple, "Should have age triple")
        
        assertEquals("Alice", (nameTriple!!.obj as Literal).lexical, "Name should be Alice")
        assertEquals("30", (ageTriple!!.obj as Literal).lexical, "Age should be 30")
        
        repo.close()
    }
}
