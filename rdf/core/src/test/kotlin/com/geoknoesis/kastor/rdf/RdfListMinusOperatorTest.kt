package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RdfListMinusOperatorTest {

    @Test
    fun `minus operator with list creates RDF List`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            val friends = listOf(
                iri("http://example.org/friend1"),
                iri("http://example.org/friend2"),
                iri("http://example.org/friend3")
            )
            
            person - FOAF.name - "Alice"
            person - FOAF.knows - friends  // Creates RDF List
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 7, "Should have at least 7 triples (1 name + 6 for RDF list structure)")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(2, personTriples.size, "Person should have 2 direct properties (name and knows)")
        
        // Verify name property
        val nameTriples = personTriples.filter { it.predicate == FOAF.name }
        assertEquals(1, nameTriples.size, "Should have 1 name")
        assertEquals("Alice", (nameTriples.first().obj as Literal).lexical)
        
        // Verify knows property points to RDF List
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(1, knowsTriples.size, "Should have 1 knows property")
        assertTrue(knowsTriples.first().obj is BlankNode, "Knows should point to a blank node (RDF list head)")
        
        // Verify RDF List structure
        val listHead = knowsTriples.first().obj as BlankNode
        val listTriples = allTriples.filter { it.subject == listHead }
        assertEquals(2, listTriples.size, "List head should have first and rest properties")
        
        val firstTriple = listTriples.find { it.predicate == RDF.first }
        assertNotNull(firstTriple, "Should have rdf:first property")
        assertEquals(iri("http://example.org/friend1"), firstTriple!!.obj)
        
        val restTriple = listTriples.find { it.predicate == RDF.rest }
        assertNotNull(restTriple, "Should have rdf:rest property")
        assertTrue(restTriple!!.obj is BlankNode, "Rest should point to another blank node")
        
        repo.close()
    }

    @Test
    fun `minus operator with empty list creates nil`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            
            person - FOAF.name - "Alice"
            person - FOAF.knows - emptyList<Any>()  // Creates rdf:nil
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(2, allTriples.size, "Should have 2 triples")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(2, personTriples.size, "Person should have 2 properties")
        
        // Verify knows property points to nil
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(1, knowsTriples.size, "Should have 1 knows property")
        assertEquals(RDF.nil, knowsTriples.first().obj, "Knows should point to rdf:nil")
        
        repo.close()
    }

    @Test
    fun `minus operator with mixed types in list creates proper RDF List`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            val friend1 = iri("http://example.org/friend1")
            val friend2 = iri("http://example.org/friend2")
            
            person - FOAF.name - "Alice"
            // Mixed types: IRI, String, Int, Boolean
            person - DCTERMS.subject - listOf(friend1, "Technology", 42, true)
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 9, "Should have at least 9 triples (1 name + 8 for RDF list structure)")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(2, personTriples.size, "Person should have 2 direct properties")
        
        // Verify subject property points to RDF List
        val subjectTriples = personTriples.filter { it.predicate == DCTERMS.subject }
        assertEquals(1, subjectTriples.size, "Should have 1 subject property")
        assertTrue(subjectTriples.first().obj is BlankNode, "Subject should point to a blank node (RDF list head)")
        
        // Verify RDF List contains mixed types
        val listHead = subjectTriples.first().obj as BlankNode
        val listTriples = allTriples.filter { it.subject == listHead }
        assertEquals(2, listTriples.size, "List head should have first and rest properties")
        
        val firstTriple = listTriples.find { it.predicate == RDF.first }
        assertNotNull(firstTriple, "Should have rdf:first property")
        assertEquals(iri("http://example.org/friend1"), firstTriple!!.obj, "First element should be the IRI")
        
        repo.close()
    }

    @Test
    fun `minus operator with list works in standalone graph`() {
        val person = iri("http://example.org/person")
        val friends = listOf(
            iri("http://example.org/friend1"),
            iri("http://example.org/friend2")
        )
        
        val graph = Rdf.graph {
            person - FOAF.name - "Alice"
            person - FOAF.knows - friends  // Creates RDF List
        }
        
        val allTriples = graph.getTriples()
        assertTrue(allTriples.size >= 5, "Should have at least 5 triples (1 name + 4 for RDF list structure)")
        
        val personTriples = allTriples.filter { it.subject == person }
        assertEquals(2, personTriples.size, "Person should have 2 direct properties")
        
        // Verify knows property points to RDF List
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(1, knowsTriples.size, "Should have 1 knows property")
        assertTrue(knowsTriples.first().obj is BlankNode, "Knows should point to a blank node (RDF list head)")
        
        // Verify RDF List structure
        val listHead = knowsTriples.first().obj as BlankNode
        val listTriples = allTriples.filter { it.subject == listHead }
        assertEquals(2, listTriples.size, "List head should have first and rest properties")
    }

    @Test
    fun `array still creates multiple individual triples`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            val friends = arrayOf(
                iri("http://example.org/friend1"),
                iri("http://example.org/friend2"),
                iri("http://example.org/friend3")
            )
            
            person - FOAF.name - "Alice"
            person - FOAF.knows - friends  // Creates multiple individual triples
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(4, allTriples.size, "Should have 4 triples (1 name + 3 individual knows)")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(4, personTriples.size, "Person should have 4 properties")
        
        // Verify multiple knows relationships
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(3, knowsTriples.size, "Should have 3 knows relationships")
        
        val knowsObjects = knowsTriples.map { it.obj }
        assertTrue(knowsObjects.contains(iri("http://example.org/friend1")), "Should know friend1")
        assertTrue(knowsObjects.contains(iri("http://example.org/friend2")), "Should know friend2")
        assertTrue(knowsObjects.contains(iri("http://example.org/friend3")), "Should know friend3")
        
        repo.close()
    }
}
