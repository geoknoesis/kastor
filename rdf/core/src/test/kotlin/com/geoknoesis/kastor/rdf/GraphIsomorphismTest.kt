package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GraphIsomorphismTest {

    @Test
    fun `identical graphs are isomorphic`() {
        val graph1 = MemoryGraph()
        val graph2 = MemoryGraph()
        
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")
        val name = Iri("http://xmlns.com/foaf/0.1/name")
        val aliceName = Literal("Alice")
        val bobName = Literal("Bob")
        
        graph1.addTriple(RdfTriple(alice, name, aliceName))
        graph1.addTriple(RdfTriple(bob, name, bobName))
        
        graph2.addTriple(RdfTriple(alice, name, aliceName))
        graph2.addTriple(RdfTriple(bob, name, bobName))
        
        assertTrue(graph1.isIsomorphicTo(graph2))
    }
    
    @Test
    fun `graphs with different blank node labels are isomorphic`() {
        val graph1 = MemoryGraph()
        val graph2 = MemoryGraph()
        
        val alice = Iri("http://example.org/alice")
        val name = Iri("http://xmlns.com/foaf/0.1/name")
        val aliceName = Literal("Alice")
        val bnode1 = BlankNode("_:b1")
        val bnode2 = BlankNode("_:b2")
        
        // Graph 1: alice -> name -> "Alice", alice -> bnode1
        graph1.addTriple(RdfTriple(alice, name, aliceName))
        graph1.addTriple(RdfTriple(alice, name, bnode1))
        
        // Graph 2: alice -> name -> "Alice", alice -> bnode2 (different blank node ID)
        graph2.addTriple(RdfTriple(alice, name, aliceName))
        graph2.addTriple(RdfTriple(alice, name, bnode2))
        
        assertTrue(graph1.isIsomorphicTo(graph2))
    }
    
    @Test
    fun `graphs with different structure are not isomorphic`() {
        val graph1 = MemoryGraph()
        val graph2 = MemoryGraph()
        
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")
        val name = Iri("http://xmlns.com/foaf/0.1/name")
        val aliceName = Literal("Alice")
        val bobName = Literal("Bob")
        
        // Graph 1: alice -> name -> "Alice", bob -> name -> "Bob"
        graph1.addTriple(RdfTriple(alice, name, aliceName))
        graph1.addTriple(RdfTriple(bob, name, bobName))
        
        // Graph 2: alice -> name -> "Alice", alice -> name -> "Bob" (different structure)
        graph2.addTriple(RdfTriple(alice, name, aliceName))
        graph2.addTriple(RdfTriple(alice, name, bobName))
        
        assertFalse(graph1.isIsomorphicTo(graph2))
    }
    
    @Test
    fun `graphs with different sizes are not isomorphic`() {
        val graph1 = MemoryGraph()
        val graph2 = MemoryGraph()
        
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")
        val name = Iri("http://xmlns.com/foaf/0.1/name")
        val aliceName = Literal("Alice")
        val bobName = Literal("Bob")
        
        // Graph 1: one triple
        graph1.addTriple(RdfTriple(alice, name, aliceName))
        
        // Graph 2: two different triples
        graph2.addTriple(RdfTriple(alice, name, aliceName))
        graph2.addTriple(RdfTriple(bob, name, bobName))
        
        assertFalse(graph1.isIsomorphicTo(graph2))
    }
    
    @Test
    fun `complex graphs with multiple blank nodes are isomorphic`() {
        val graph1 = MemoryGraph()
        val graph2 = MemoryGraph()
        
        val alice = Iri("http://example.org/alice")
        val name = Iri("http://xmlns.com/foaf/0.1/name")
        val knows = Iri("http://xmlns.com/foaf/0.1/knows")
        val aliceName = Literal("Alice")
        val bnode1a = BlankNode("_:b1")
        val bnode1b = BlankNode("_:b2")
        val bnode2a = BlankNode("_:b3")
        val bnode2b = BlankNode("_:b4")
        
        // Graph 1: alice -> name -> "Alice", alice -> knows -> bnode1a, bnode1a -> name -> bnode1b
        graph1.addTriple(RdfTriple(alice, name, aliceName))
        graph1.addTriple(RdfTriple(alice, knows, bnode1a))
        graph1.addTriple(RdfTriple(bnode1a, name, bnode1b))
        
        // Graph 2: alice -> name -> "Alice", alice -> knows -> bnode2a, bnode2a -> name -> bnode2b
        graph2.addTriple(RdfTriple(alice, name, aliceName))
        graph2.addTriple(RdfTriple(alice, knows, bnode2a))
        graph2.addTriple(RdfTriple(bnode2a, name, bnode2b))
        
        assertTrue(graph1.isIsomorphicTo(graph2))
    }
    
    @Test
    fun `graphs with different literal types are not isomorphic`() {
        val graph1 = MemoryGraph()
        val graph2 = MemoryGraph()
        
        val alice = Iri("http://example.org/alice")
        val age = Iri("http://xmlns.com/foaf/0.1/age")
        val ageLiteral1 = Literal("25", Iri("http://www.w3.org/2001/XMLSchema#integer"))
        val ageLiteral2 = Literal("25", Iri("http://www.w3.org/2001/XMLSchema#string"))
        
        graph1.addTriple(RdfTriple(alice, age, ageLiteral1))
        graph2.addTriple(RdfTriple(alice, age, ageLiteral2))
        
        assertFalse(graph1.isIsomorphicTo(graph2))
    }
    
    @Test
    fun `graphs with language-tagged literals are isomorphic if languages match`() {
        val graph1 = MemoryGraph()
        val graph2 = MemoryGraph()
        
        val alice = Iri("http://example.org/alice")
        val name = Iri("http://xmlns.com/foaf/0.1/name")
        val nameLiteral1 = Literal("Alice", "en")
        val nameLiteral2 = Literal("Alice", "en")
        
        graph1.addTriple(RdfTriple(alice, name, nameLiteral1))
        graph2.addTriple(RdfTriple(alice, name, nameLiteral2))
        
        assertTrue(graph1.isIsomorphicTo(graph2))
    }
    
    @Test
    fun `graphs with different language tags are not isomorphic`() {
        val graph1 = MemoryGraph()
        val graph2 = MemoryGraph()
        
        val alice = Iri("http://example.org/alice")
        val name = Iri("http://xmlns.com/foaf/0.1/name")
        val nameLiteral1 = Literal("Alice", "en")
        val nameLiteral2 = Literal("Alice", "fr")
        
        graph1.addTriple(RdfTriple(alice, name, nameLiteral1))
        graph2.addTriple(RdfTriple(alice, name, nameLiteral2))
        
        assertFalse(graph1.isIsomorphicTo(graph2))
    }
}









