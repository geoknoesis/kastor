package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RdfRepositoryExtensionsTest {
    
    @Test
    fun `add extension function works with DSL`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = Iri("http://example.org/person")
            person - Iri("http://example.org/name") - "Alice"
            person - Iri("http://example.org/age") - 30
        }
        
        val triples = repo.getTriples()
        assertEquals(2, triples.size, "Should have 2 triples after add")
        
        repo.close()
    }
    
    @Test
    fun `addToGraph extension function works`() {
        val repo = Rdf.memory()
        val graphName = Iri("http://example.org/graph")
        
        repo.addToGraph(graphName) {
            val person = Iri("http://example.org/person")
            person - Iri("http://example.org/name") - "Bob"
        }
        
        val graph = repo.getGraph(graphName)
        val triples = graph.getTriples()
        assertEquals(1, triples.size, "Should have 1 triple in named graph")
        
        repo.close()
    }
    
    @Test
    fun `addTriple extension function works`() {
        val repo = Rdf.memory()
        val triple = RdfTriple(
            Iri("http://example.org/person"),
            Iri("http://example.org/name"),
            string("Charlie")
        )
        
        repo.addTriple(triple)
        
        assertTrue(repo.hasTriple(triple), "Repository should contain the triple")
        assertEquals(1, repo.getTriples().size, "Should have 1 triple")
        
        repo.close()
    }
    
    @Test
    fun `addTriples extension function works with collection`() {
        val repo = Rdf.memory()
        val triples = listOf(
            RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/name"), string("David")),
            RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/age"), int(25))
        )
        
        repo.addTriples(triples)
        
        assertEquals(2, repo.getTriples().size, "Should have 2 triples")
        assertTrue(repo.hasTriple(triples[0]), "Should contain first triple")
        assertTrue(repo.hasTriple(triples[1]), "Should contain second triple")
        
        repo.close()
    }
    
    @Test
    fun `addTriple with graph name works`() {
        val repo = Rdf.memory()
        val graphName = Iri("http://example.org/graph")
        val triple = RdfTriple(
            Iri("http://example.org/person"),
            Iri("http://example.org/name"),
            string("Eve")
        )
        
        repo.addTriple(graphName, triple)
        
        val graph = repo.getGraph(graphName)
        assertTrue(graph.hasTriple(triple), "Named graph should contain the triple")
        
        repo.close()
    }
    
    @Test
    fun `addTriple with null graph name uses default graph`() {
        val repo = Rdf.memory()
        val triple = RdfTriple(
            Iri("http://example.org/person"),
            Iri("http://example.org/name"),
            string("Frank")
        )
        
        repo.addTriple(null, triple)
        
        assertTrue(repo.hasTriple(triple), "Default graph should contain the triple")
        assertEquals(1, repo.getTriples().size, "Should have 1 triple in default graph")
        
        repo.close()
    }
    
    @Test
    fun `removeTriple extension function works`() {
        val repo = Rdf.memory()
        val triple = RdfTriple(
            Iri("http://example.org/person"),
            Iri("http://example.org/name"),
            string("Grace")
        )
        
        repo.addTriple(triple)
        assertTrue(repo.hasTriple(triple), "Should contain triple before removal")
        
        val removed = repo.removeTriple(triple)
        assertTrue(removed, "removeTriple should return true")
        assertFalse(repo.hasTriple(triple), "Should not contain triple after removal")
        assertEquals(0, repo.getTriples().size, "Should have 0 triples after removal")
        
        repo.close()
    }
    
    @Test
    fun `removeTriples extension function works`() {
        val repo = Rdf.memory()
        val triples = listOf(
            RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/name"), string("Henry")),
            RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/age"), int(30)),
            RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/email"), string("henry@example.org"))
        )
        
        repo.addTriples(triples)
        assertEquals(3, repo.getTriples().size, "Should have 3 triples")
        
        val removed = repo.removeTriples(listOf(triples[0], triples[1]))
        assertTrue(removed, "removeTriples should return true")
        assertEquals(1, repo.getTriples().size, "Should have 1 triple after removal")
        assertTrue(repo.hasTriple(triples[2]), "Should still contain third triple")
        
        repo.close()
    }
    
    @Test
    fun `hasTriple extension function works`() {
        val repo = Rdf.memory()
        val triple = RdfTriple(
            Iri("http://example.org/person"),
            Iri("http://example.org/name"),
            string("Iris")
        )
        
        assertFalse(repo.hasTriple(triple), "Should not contain triple initially")
        
        repo.addTriple(triple)
        assertTrue(repo.hasTriple(triple), "Should contain triple after adding")
        
        repo.close()
    }
    
    @Test
    fun `getTriples extension function works`() {
        val repo = Rdf.memory()
        
        assertEquals(0, repo.getTriples().size, "Should have 0 triples initially")
        
        repo.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/name"), string("Jack")))
        repo.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/age"), int(35)))
        
        val triples = repo.getTriples()
        assertEquals(2, triples.size, "Should have 2 triples")
        
        repo.close()
    }
}

