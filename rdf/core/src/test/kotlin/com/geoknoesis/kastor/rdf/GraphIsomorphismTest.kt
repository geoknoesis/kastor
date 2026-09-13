package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GraphIsomorphismTest {
    
    @Test
    fun `identical graphs are isomorphic`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        
        val triple = RdfTriple(
            Iri("http://example.org/person"),
            Iri("http://example.org/name"),
            string("Alice")
        )
        
        repo1.addTriple(triple)
        repo2.addTriple(triple)
        
        assertTrue(repo1.defaultGraph.isIsomorphicTo(repo2.defaultGraph), "Identical graphs should be isomorphic")
        
        repo1.close()
        repo2.close()
    }
    
    @Test
    fun `graphs with different blank node IDs are isomorphic`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        
        val bnode1 = bnode("b1")
        val bnode2 = bnode("b2")
        
        repo1.addTriple(RdfTriple(bnode1, Iri("http://example.org/name"), string("Bob")))
        repo2.addTriple(RdfTriple(bnode2, Iri("http://example.org/name"), string("Bob")))
        
        assertTrue(repo1.defaultGraph.isIsomorphicTo(repo2.defaultGraph), "Graphs with different blank node IDs but same structure should be isomorphic")
        
        repo1.close()
        repo2.close()
    }
    
    @Test
    fun `graphs with different structures are not isomorphic`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        
        repo1.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/name"), string("Charlie")))
        repo2.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/age"), int(30)))
        
        assertFalse(repo1.defaultGraph.isIsomorphicTo(repo2.defaultGraph), "Graphs with different structures should not be isomorphic")
        
        repo1.close()
        repo2.close()
    }
    
    @Test
    fun `graphs with different sizes are not isomorphic`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        
        repo1.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/name"), string("David")))
        repo1.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/age"), int(25)))
        
        repo2.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/name"), string("David")))
        
        assertFalse(repo1.defaultGraph.isIsomorphicTo(repo2.defaultGraph), "Graphs with different sizes should not be isomorphic")
        
        repo1.close()
        repo2.close()
    }
    
    @Test
    fun `graphs with same structure but different literal values are not isomorphic`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        
        // Use different datatypes to ensure they're not isomorphic
        repo1.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/age"), int(30)))
        repo2.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/age"), string("thirty")))
        
        // Different datatypes should make them non-isomorphic
        assertFalse(repo1.defaultGraph.isIsomorphicTo(repo2.defaultGraph), "Graphs with different literal datatypes should not be isomorphic")
        
        repo1.close()
        repo2.close()
    }
    
    @Test
    fun `complex graphs with blank nodes are isomorphic`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        
        val bnode1a = bnode("b1")
        val bnode2a = bnode("x1")
        
        val person1 = Iri("http://example.org/person1")
        val person2 = Iri("http://example.org/person2")
        val namePred = Iri("http://example.org/name")
        val friendPred = Iri("http://example.org/friend")
        
        // Graph 1: person1 has name "Grace", friend bnode1a, bnode1a has name "Hank"
        repo1.addTriple(RdfTriple(person1, namePred, string("Grace")))
        repo1.addTriple(RdfTriple(person1, friendPred, bnode1a))
        repo1.addTriple(RdfTriple(bnode1a, namePred, string("Hank")))
        
        // Graph 2: person2 has name "Grace", friend bnode2a, bnode2a has name "Hank"
        repo2.addTriple(RdfTriple(person2, namePred, string("Grace")))
        repo2.addTriple(RdfTriple(person2, friendPred, bnode2a))
        repo2.addTriple(RdfTriple(bnode2a, namePred, string("Hank")))
        
        assertFalse(repo1.defaultGraph.isIsomorphicTo(repo2.defaultGraph), "Different fixed IRIs must not be renamed by isomorphism")
        
        repo1.close()
        repo2.close()
    }
    
    @Test
    fun `findBlankNodeMapping returns null for non-isomorphic graphs`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        
        repo1.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/name"), string("Iris")))
        repo2.addTriple(RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/age"), int(30)))
        
        val mapping = repo1.defaultGraph.findBlankNodeMapping(repo2.defaultGraph)
        assertNull(mapping, "findBlankNodeMapping should return null for non-isomorphic graphs")
        
        repo1.close()
        repo2.close()
    }
    
    @Test
    fun `findBlankNodeMapping returns mapping for isomorphic graphs`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        
        val bnode1 = bnode("b1")
        val bnode2 = bnode("b2")
        
        repo1.addTriple(RdfTriple(bnode1, Iri("http://example.org/name"), string("Jack")))
        repo2.addTriple(RdfTriple(bnode2, Iri("http://example.org/name"), string("Jack")))
        
        val mapping = repo1.defaultGraph.findBlankNodeMapping(repo2.defaultGraph)
        assertNotNull(mapping, "findBlankNodeMapping should return a mapping for isomorphic graphs")
        
        repo1.close()
        repo2.close()
    }
    
    @Test
    fun `graphs with duplicate triples are handled correctly`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        
        val triple = RdfTriple(Iri("http://example.org/person"), Iri("http://example.org/name"), string("Kevin"))
        
        repo1.addTriple(triple)
        repo1.addTriple(triple)  // Duplicate
        
        repo2.addTriple(triple)
        
        // RDF graphs are sets: adding the same triple twice leaves one triple, so the graphs are equal.
        assertEquals(1, repo1.defaultGraph.size())
        assertTrue(repo1.defaultGraph.isIsomorphicTo(repo2.defaultGraph))
        
        repo1.close()
        repo2.close()
    }
}
