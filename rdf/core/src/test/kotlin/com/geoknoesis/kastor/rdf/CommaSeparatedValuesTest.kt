package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.dsl.values
import com.geoknoesis.kastor.rdf.dsl.list
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Test for new curly braces and parentheses syntax in the minus operator.
 * Demonstrates: subj - pred - {value1, value2, value3} for individual triples
 *               subj - pred - (value1, value2, value3) for RDF lists
 */
class CurlyBracesParenthesesSyntaxTest {

    @Test
    fun `curly braces creates multiple individual triples`() {
        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        val friend1 = iri("http://example.org/friend1")
        val friend2 = iri("http://example.org/friend2")
        val friend3 = iri("http://example.org/friend3")

        repo.add {
            person - FOAF.name - "Alice"
            
            // Curly braces: creates 3 individual triples
            person - FOAF.knows - values(friend1, friend2, friend3)
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(4, allTriples.size, "Should have 4 triples (1 name + 3 knows)")

        val personTriples = allTriples.filter { it.subject == person }
        assertEquals(4, personTriples.size, "Person should have 4 properties")

        // Verify name property
        val nameTriples = personTriples.filter { it.predicate == FOAF.name }
        assertEquals(1, nameTriples.size, "Should have 1 name property")

        // Verify multiple knows relationships (individual triples)
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(3, knowsTriples.size, "Should have 3 knows relationships")

        val knowsObjects = knowsTriples.map { it.obj }
        assertTrue(knowsObjects.contains(friend1), "Should know friend1")
        assertTrue(knowsObjects.contains(friend2), "Should know friend2")
        assertTrue(knowsObjects.contains(friend3), "Should know friend3")

        repo.close()
    }

    @Test
    fun `curly braces with mixed types works`() {
        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        val friend1 = iri("http://example.org/friend1")
        val bnode = bnode("anon1")

        repo.add {
            person - FOAF.name - "Bob"
            
            // Curly braces with mixed types
            person - DCTERMS.subject - values(friend1, bnode, "Technology", 42, true)
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(6, allTriples.size, "Should have 6 triples (1 name + 5 subjects)")

        val personTriples = allTriples.filter { it.subject == person }
        assertEquals(6, personTriples.size, "Person should have 6 properties")

        // Verify multiple subject relationships (individual triples)
        val subjectTriples = personTriples.filter { it.predicate == DCTERMS.subject }
        assertEquals(5, subjectTriples.size, "Should have 5 subject properties")

        val subjectObjects = subjectTriples.map { it.obj }
        assertTrue(subjectObjects.contains(friend1), "Should contain friend1")
        assertTrue(subjectObjects.contains(bnode), "Should contain blank node")
        assertTrue(subjectObjects.contains(Literal("Technology", XSD.string)), "Should contain Technology")
        assertTrue(subjectObjects.contains(Literal("42", XSD.integer)), "Should contain 42")
        assertTrue(subjectObjects.contains(Literal("true", XSD.boolean)), "Should contain true")

        repo.close()
    }

    @Test
    fun `curly braces works with standalone graph`() {
        val person = iri("http://example.org/person")
        val friend1 = iri("http://example.org/friend1")
        val friend2 = iri("http://example.org/friend2")
        val friend3 = iri("http://example.org/friend3")

        val graph = Rdf.graph {
            person - FOAF.name - "Alice"
            
            // Curly braces: creates 3 individual triples
            person - FOAF.knows - values(friend1, friend2, friend3)
            
            // Curly braces with mixed types
            person - DCTERMS.subject - values("Technology", "Programming", "RDF")
        }

        val allTriples = graph.getTriples()
        assertEquals(7, allTriples.size, "Should have 7 triples (1 name + 3 knows + 3 subjects)")

        val personTriples = allTriples.filter { it.subject == person }
        assertEquals(7, personTriples.size, "Person should have 7 properties")

        // Verify knows relationships (individual triples)
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(3, knowsTriples.size, "Should have 3 knows relationships")

        val knowsObjects = knowsTriples.map { it.obj }
        assertTrue(knowsObjects.contains(friend1), "Should know friend1")
        assertTrue(knowsObjects.contains(friend2), "Should know friend2")
        assertTrue(knowsObjects.contains(friend3), "Should know friend3")

        // Verify subject relationships (individual triples)
        val subjectTriples = personTriples.filter { it.predicate == DCTERMS.subject }
        assertEquals(3, subjectTriples.size, "Should have 3 subject properties")

        val subjectObjects = subjectTriples.map { (it.obj as Literal).lexical }
        assertTrue(subjectObjects.contains("Technology"), "Should contain Technology")
        assertTrue(subjectObjects.contains("Programming"), "Should contain Programming")
        assertTrue(subjectObjects.contains("RDF"), "Should contain RDF")
    }

    @Test
    fun `curly braces vs parentheses vs arrays vs lists comparison`() {
        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        val friend1 = iri("http://example.org/friend1")
        val friend2 = iri("http://example.org/friend2")
        val friend3 = iri("http://example.org/friend3")

        repo.add {
            person - FOAF.name - "Alice"
            
            // Method 1: Curly braces (individual triples)
            person - FOAF.knows - values(friend1, friend2, friend3)
            
            // Method 2: Parentheses (RDF List)
            person - FOAF.mbox - list("alice@example.com", "alice@work.com")
            
            // Method 3: Array (individual triples)
            person - DCTERMS.subject - arrayOf("Technology", "Programming", "RDF")
            
            // Method 4: List (RDF List)
            person - DCTERMS.type - listOf("Person", "Agent")
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 11, "Should have at least 11 triples")

        val personTriples = allTriples.filter { it.subject == person }
        assertEquals(9, personTriples.size, "Person should have 9 direct properties (1 name + 3 knows + 1 mbox + 3 subjects + 1 type)")

        // Verify knows property (curly braces creates individual triples)
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(3, knowsTriples.size, "Should have 3 knows relationships")

        // Verify mbox property (parentheses creates RDF List)
        val mboxTriples = personTriples.filter { it.predicate == FOAF.mbox }
        assertEquals(1, mboxTriples.size, "Should have 1 mbox property pointing to RDF List")
        assertTrue(mboxTriples.first().obj is BlankNode, "Mbox should point to RDF List")

        // Verify subject property (array creates individual triples)
        val subjectTriples = personTriples.filter { it.predicate == DCTERMS.subject }
        assertEquals(3, subjectTriples.size, "Should have 3 subject relationships")

        // Verify type property (list creates RDF List)
        val typeTriples = personTriples.filter { it.predicate == DCTERMS.type }
        assertEquals(1, typeTriples.size, "Should have 1 type property")
        assertTrue(typeTriples.first().obj is BlankNode, "Type should point to RDF List")

        repo.close()
    }

    @Test
    fun `parentheses creates RDF list`() {
        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        val friend1 = iri("http://example.org/friend1")
        val friend2 = iri("http://example.org/friend2")
        val friend3 = iri("http://example.org/friend3")

        repo.add {
            person - FOAF.name - "Alice"
            
            // Parentheses: creates RDF List
            person - FOAF.knows - list(friend1, friend2, friend3)
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 8, "Should have at least 8 triples (1 name + 1 knows + RDF list structure)")

        val personTriples = allTriples.filter { it.subject == person }
        assertEquals(2, personTriples.size, "Person should have 2 direct properties")

        // Verify knows property points to RDF List
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(1, knowsTriples.size, "Should have 1 knows property")
        assertTrue(knowsTriples.first().obj is BlankNode, "Knows should point to a blank node (RDF list head)")

        // Verify RDF List structure
        val listHead = knowsTriples.first().obj as BlankNode
        val listElements = mutableListOf<RdfTerm>()
        var currentListElement: RdfTerm? = listHead
        var count = 0

        while (currentListElement is BlankNode && count < 10) {
            val firstTriple = allTriples.find { it.subject == currentListElement && it.predicate == RDF.first }
            assertNotNull(firstTriple, "List node should have rdf:first property")
            listElements.add(firstTriple!!.obj)

            val restTriple = allTriples.find { it.subject == currentListElement && it.predicate == RDF.rest }
            assertNotNull(restTriple, "List node should have rdf:rest property")
            currentListElement = restTriple!!.obj
            count++
        }

        assertEquals(RDF.nil, currentListElement, "Last list element should point to rdf:nil")
        assertEquals(3, listElements.size, "RDF List should contain 3 elements")
        assertTrue(listElements.contains(friend1), "RDF List should contain friend1")
        assertTrue(listElements.contains(friend2), "RDF List should contain friend2")
        assertTrue(listElements.contains(friend3), "RDF List should contain friend3")

        repo.close()
    }

}
