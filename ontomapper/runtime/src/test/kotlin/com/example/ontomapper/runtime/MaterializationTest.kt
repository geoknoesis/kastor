package com.example.ontomapper.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

// Create a simple domain interface for testing
interface TestPerson {
    val name: List<String>
    val age: List<Int>
}

class MaterializationTest {

    @Test
    fun `RdfRef asType materializes and asRdf works on result`() {
        
        // Register a simple factory
        OntoMapper.registry[TestPerson::class.java] = { handle ->
            object : TestPerson, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age).mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }
        
        val repo = Rdf.memory()
        val subject = iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "Alice"
            subject - FOAF.age - 30
            subject - DCTERMS.description - "A test person"
        }
        
        val ref = RdfRef(subject, repo.defaultGraph)
        val person: TestPerson = ref.asType()
        
        // Test domain interface
        assertEquals(listOf("Alice"), person.name)
        assertEquals(listOf(30), person.age)
        
        // Test RDF side-channel
        val rdfHandle = person.asRdf()
        assertEquals(subject, rdfHandle.node)
        assertEquals(repo.defaultGraph, rdfHandle.graph)
        
        // Test extras
        val extras = rdfHandle.extras
        val descriptions = extras.strings(DCTERMS.description)
        assertEquals(listOf("A test person"), descriptions)
    }
    
    @Test
    fun `materialization fails for unregistered type`() {
        val repo = Rdf.memory()
        val subject = iri("http://example.org/person")
        val ref = RdfRef(subject, repo.defaultGraph)
        
        assertThrows(IllegalStateException::class.java) {
            ref.asType<Any>()
        }
    }
    
    @Test
    fun `asRdf fails for non-RdfBacked object`() {
        val regularObject = object {}
        
        assertThrows(IllegalStateException::class.java) {
            regularObject.asRdf()
        }
    }
}
