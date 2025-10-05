package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GraphDslTest {

    @Test
    fun `create graph with bracket syntax works`() {
        val graph = Rdf.graph {
            val person = iri("http://example.org/person")
            val friend = iri("http://example.org/friend")

            person[FOAF.name] = "Alice"
            person[FOAF.age] = 30
            person[FOAF.knows] = friend
            friend[FOAF.name] = "Bob"
        }

        assertEquals(4, graph.size(), "Graph should have 4 triples")
        
        val allTriples = graph.getTriples()
        val nameTriple = allTriples.find { it.predicate == FOAF.name }
        val ageTriple = allTriples.find { it.predicate == FOAF.age }
        val knowsTriple = allTriples.find { it.predicate == FOAF.knows }

        assertNotNull(nameTriple, "Should have name triple")
        assertNotNull(ageTriple, "Should have age triple")
        assertNotNull(knowsTriple, "Should have knows triple")

        assertEquals("Alice", (nameTriple!!.obj as Literal).lexical, "Name should be Alice")
        assertEquals("30", (ageTriple!!.obj as Literal).lexical, "Age should be 30")
    }

    @Test
    fun `create graph with has and with syntax works`() {
        val graph = Rdf.graph {
            val person = iri("http://example.org/person")
            val document = iri("http://example.org/document")

            person has FOAF.name with "Alice"
            person has FOAF.age with 25
            document has DCTERMS.title with "Sample Document"
            document has DCTERMS.creator with person
        }

        assertEquals(4, graph.size(), "Graph should have 4 triples")
        
        val allTriples = graph.getTriples()
        val personNameTriple = allTriples.find { it.predicate == FOAF.name && (it.obj as Literal).lexical == "Alice" }
        val documentTitleTriple = allTriples.find { it.predicate == DCTERMS.title }

        assertNotNull(personNameTriple, "Should have person name triple")
        assertNotNull(documentTitleTriple, "Should have document title triple")
    }

    @Test
    fun `create graph with minus operator syntax works`() {
        val graph = Rdf.graph {
            val person = iri("http://example.org/person")
            val friend = iri("http://example.org/friend")

            person - FOAF.name - "Alice"
            person - FOAF.age - 30
            person - FOAF.knows - friend
            friend - FOAF.name - "Bob"
        }

        assertEquals(4, graph.size(), "Graph should have 4 triples")
        
        val allTriples = graph.getTriples()
        val personNameTriple = allTriples.find { it.predicate == FOAF.name && (it.obj as Literal).lexical == "Alice" }
        val friendNameTriple = allTriples.find { it.predicate == FOAF.name && (it.obj as Literal).lexical == "Bob" }

        assertNotNull(personNameTriple, "Should have person name triple")
        assertNotNull(friendNameTriple, "Should have friend name triple")
    }

    @Test
    fun `create graph with mixed syntaxes works`() {
        val graph = Rdf.graph {
            val person = iri("http://example.org/person")
            val document = iri("http://example.org/document")

            // Mix all three syntaxes
            person[FOAF.name] = "Alice"  // Bracket syntax
            person has FOAF.age with 25  // Has/with syntax
            document - DCTERMS.title - "Sample Document"  // Minus operator syntax
            document[DCTERMS.creator] = person  // Bracket syntax
        }

        assertEquals(4, graph.size(), "Graph should have 4 triples")
        
        val allTriples = graph.getTriples()
        val nameTriple = allTriples.find { it.predicate == FOAF.name }
        val ageTriple = allTriples.find { it.predicate == FOAF.age }
        val titleTriple = allTriples.find { it.predicate == DCTERMS.title }
        val creatorTriple = allTriples.find { it.predicate == DCTERMS.creator }

        assertNotNull(nameTriple, "Should have name triple")
        assertNotNull(ageTriple, "Should have age triple")
        assertNotNull(titleTriple, "Should have title triple")
        assertNotNull(creatorTriple, "Should have creator triple")
    }

    @Test
    fun `create graph with different literal types works`() {
        val graph = Rdf.graph {
            val resource = iri("http://example.org/resource")

            resource - DCTERMS.title - "Test Title"
            resource - DCTERMS.extent - 1000
            resource - DCTERMS.available - true
            resource[RDFS.comment] = "A test resource"
        }

        assertEquals(4, graph.size(), "Graph should have 4 triples")
        
        val allTriples = graph.getTriples()
        val titleTriple = allTriples.find { it.predicate == DCTERMS.title }
        val extentTriple = allTriples.find { it.predicate == DCTERMS.extent }
        val availableTriple = allTriples.find { it.predicate == DCTERMS.available }
        val commentTriple = allTriples.find { it.predicate == RDFS.comment }

        assertNotNull(titleTriple, "Should have title triple")
        assertNotNull(extentTriple, "Should have extent triple")
        assertNotNull(availableTriple, "Should have available triple")
        assertNotNull(commentTriple, "Should have comment triple")

        assertEquals("Test Title", (titleTriple!!.obj as Literal).lexical, "Title should be preserved")
        assertEquals("1000", (extentTriple!!.obj as Literal).lexical, "Extent should be converted to string")
        assertEquals("true", (availableTriple!!.obj as Literal).lexical, "Available should be converted to string")
        assertEquals("A test resource", (commentTriple!!.obj as Literal).lexical, "Comment should be preserved")
    }

    @Test
    fun `create graph with IRI and BlankNode objects works`() {
        val graph = Rdf.graph {
            val subject = iri("http://example.org/subject")
            val friend = iri("http://example.org/friend")
            val bnode = bnode("anon1")

            subject - FOAF.knows - friend
            subject - DCTERMS.creator - friend
            subject - FOAF.knows - bnode
            bnode - FOAF.name - "Anonymous"
        }

        assertEquals(4, graph.size(), "Graph should have 4 triples")
        
        val allTriples = graph.getTriples()
        val knowsTriple = allTriples.find { it.predicate == FOAF.knows && it.obj == iri("http://example.org/friend") }
        val creatorTriple = allTriples.find { it.predicate == DCTERMS.creator }
        val bnodeTriple = allTriples.find { it.predicate == FOAF.knows && it.obj == bnode("anon1") }
        val bnodeNameTriple = allTriples.find { it.predicate == FOAF.name && it.subject == bnode("anon1") }

        assertNotNull(knowsTriple, "Should have knows triple")
        assertNotNull(creatorTriple, "Should have creator triple")
        assertNotNull(bnodeTriple, "Should have blank node triple")
        assertNotNull(bnodeNameTriple, "Should have blank node name triple")

        assertEquals(iri("http://example.org/friend"), knowsTriple!!.obj, "Knows object should be friend IRI")
        assertEquals(iri("http://example.org/friend"), creatorTriple!!.obj, "Creator object should be friend IRI")
        assertEquals(bnode("anon1"), bnodeTriple!!.obj, "Blank node object should be correct")
        assertEquals("Anonymous", (bnodeNameTriple!!.obj as Literal).lexical, "Blank node name should be Anonymous")
    }

    @Test
    fun `create graph with explicit triple creation works`() {
        val graph = Rdf.graph {
            val subject = iri("http://example.org/subject")
            val predicate = iri("http://example.org/predicate")
            val obj = literal("object value")

            triple(subject, predicate, obj)
            triple(subject, "http://example.org/another", literal("another value"))
        }

        assertEquals(2, graph.size(), "Graph should have 2 triples")
        
        val allTriples = graph.getTriples()
        val triple1 = allTriples.find { it.predicate == iri("http://example.org/predicate") }
        val triple2 = allTriples.find { it.predicate.value == "http://example.org/another" }

        assertNotNull(triple1, "Should have first triple")
        assertNotNull(triple2, "Should have second triple")

        assertEquals(literal("object value"), triple1!!.obj, "First triple object should match")
        assertEquals("another value", (triple2!!.obj as Literal).lexical, "Second triple object should match")
    }

    @Test
    fun `create graph with addTriples method works`() {
        val existingTriples = listOf(
            RdfTriple(iri("http://example.org/s1"), iri("http://example.org/p1"), literal("o1")),
            RdfTriple(iri("http://example.org/s2"), iri("http://example.org/p2"), literal("o2"))
        )

        val graph = Rdf.graph {
            val subject = iri("http://example.org/subject")
            
            subject - FOAF.name - "Alice"
            addTriples(existingTriples)
        }

        assertEquals(3, graph.size(), "Graph should have 3 triples")
        
        val allTriples = graph.getTriples()
        val nameTriple = allTriples.find { it.predicate == FOAF.name }
        val existingTriple1 = allTriples.find { (it.subject as Iri).value == "http://example.org/s1" }
        val existingTriple2 = allTriples.find { (it.subject as Iri).value == "http://example.org/s2" }

        assertNotNull(nameTriple, "Should have name triple")
        assertNotNull(existingTriple1, "Should have first existing triple")
        assertNotNull(existingTriple2, "Should have second existing triple")
    }

    @Test
    fun `graph operations work after creation`() {
        val graph = Rdf.graph {
            val person = iri("http://example.org/person")
            person - FOAF.name - "Alice"
            person - FOAF.age - 30
        }

        // Test basic operations
        assertEquals(2, graph.size(), "Graph should have 2 triples initially")
        
        val allTriples = graph.getTriples()
        val nameTriple = allTriples.find { it.predicate == FOAF.name }
        assertNotNull(nameTriple, "Should have name triple")
        
        // Test adding more triples
        val newTriple = RdfTriple(iri("http://example.org/person"), FOAF.knows, iri("http://example.org/friend"))
        graph.addTriple(newTriple)
        assertEquals(3, graph.size(), "Graph should have 3 triples after adding")
        
        // Test removing triples
        val removed = graph.removeTriple(nameTriple!!)
        assertTrue(removed, "Should successfully remove triple")
        assertEquals(2, graph.size(), "Graph should have 2 triples after removing")
        
        // Test checking if triple exists
        assertTrue(graph.hasTriple(newTriple), "Graph should contain the new triple")
        assertFalse(graph.hasTriple(nameTriple), "Graph should not contain the removed triple")
        
        // Test clearing
        val cleared = graph.clear()
        assertTrue(cleared, "Should successfully clear graph")
        assertEquals(0, graph.size(), "Graph should be empty after clearing")
    }

    @Test
    fun `empty graph creation works`() {
        val graph = Rdf.graph {
            // Empty DSL block
        }

        assertEquals(0, graph.size(), "Empty graph should have 0 triples")
        assertTrue(graph.getTriples().isEmpty(), "Empty graph should have no triples")
        assertTrue(graph.size() == 0, "Empty graph should be empty")
    }

    @Test
    fun `graph with complex nested structures works`() {
        val graph = Rdf.graph {
            val person = iri("http://example.org/person")
            val friend = iri("http://example.org/friend")
            val document = iri("http://example.org/document")
            val bnode = bnode("complex")

            // Create a complex graph with various relationships
            person[FOAF.name] = "Alice"
            person has FOAF.age with 30
            person - FOAF.knows - friend
            
            friend - FOAF.name - "Bob"
            friend[DCTERMS.creator] = person
            
            document - DCTERMS.title - "Sample Document"
            document has DCTERMS.creator with person
            document - DCTERMS.date - "2023-12-25"
            
            bnode - RDFS.label - "Complex Node"
            bnode - FOAF.knows - friend
            bnode has DCTERMS.creator with person
        }

        assertEquals(11, graph.size(), "Complex graph should have 11 triples")
        
        val allTriples = graph.getTriples()
        
        // Verify various relationships exist
        val personName = allTriples.find { it.subject == iri("http://example.org/person") && it.predicate == FOAF.name }
        val friendName = allTriples.find { it.subject == iri("http://example.org/friend") && it.predicate == FOAF.name }
        val knowsRelationship = allTriples.find { it.predicate == FOAF.knows && it.subject == iri("http://example.org/person") }
        val documentTitle = allTriples.find { it.subject == iri("http://example.org/document") && it.predicate == DCTERMS.title }
        val bnodeLabel = allTriples.find { it.subject == bnode("complex") && it.predicate == RDFS.label }

        assertNotNull(personName, "Should have person name")
        assertNotNull(friendName, "Should have friend name")
        assertNotNull(knowsRelationship, "Should have knows relationship")
        assertNotNull(documentTitle, "Should have document title")
        assertNotNull(bnodeLabel, "Should have blank node label")

        assertEquals("Alice", (personName!!.obj as Literal).lexical, "Person name should be Alice")
        assertEquals("Bob", (friendName!!.obj as Literal).lexical, "Friend name should be Bob")
        assertEquals("Sample Document", (documentTitle!!.obj as Literal).lexical, "Document title should be correct")
        assertEquals("Complex Node", (bnodeLabel!!.obj as Literal).lexical, "Blank node label should be correct")
    }
}
