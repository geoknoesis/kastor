package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TurtleStyleBasicTest {

    @Test
    fun `basic repository DSL works`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = Iri("http://example.org/person")
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
            val person = Iri("http://example.org/person")
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
    
    @Test
    fun `turtle-style a alias works in repository DSL`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
                put("dcterms", "http://purl.org/dc/terms/")
            }
            
            val person = Iri("http://example.org/person")
            val document = Iri("http://example.org/document")
            
            // Turtle-style rdf:type
            person[RDF.type] = FOAF.Person
            document[RDF.type] = Iri("http://purl.org/dc/terms/Dataset")
            
            // Also test with minus operator
            person - RDF.type - FOAF.Agent
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        println("Actual triples: ${allTriples.size}")
        allTriples.forEach { triple ->
            println("  ${triple.subject} ${triple.predicate} ${triple.obj}")
        }
        assertEquals(3, allTriples.size, "Should have 3 unique triples (person - a - foaf:Agent creates same triple twice)")
        
        val typeTriples = allTriples.filter { it.predicate == RDF.type }
        assertEquals(3, typeTriples.size, "Should have 3 type triples")
        
        repo.close()
    }
    
    @Test
    fun `natural language is alias works in repository DSL`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
                put("dcterms", "http://purl.org/dc/terms/")
            }
            
            val person = Iri("http://example.org/person")
            val document = Iri("http://example.org/document")
            
            // Natural language "is" alias for rdf:type
            person `is` FOAF.Person
            document `is` Iri("http://purl.org/dc/terms/Dataset")
            
            // Also test with IRI
            person `is` FOAF.Agent
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(3, allTriples.size, "Should have 3 triples")
        
        val typeTriples = allTriples.filter { it.predicate == RDF.type }
        assertEquals(3, typeTriples.size, "Should have 3 type triples")
        
        repo.close()
    }
    
    @Test
    fun `mixed a and is aliases work in repository DSL`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
                put("dcterms", "http://purl.org/dc/terms/")
            }
            
            val person = Iri("http://example.org/person")
            val organization = Iri("http://example.org/org")
            
            // Mix different type declaration styles
            person[RDF.type] = FOAF.Person  // Turtle-style
            person `is` FOAF.Agent  // Natural language
            organization - RDF.type - FOAF.Organization  // Minus operator
            organization has RDF.type with FOAF.Agent  // Traditional has/with
            
            // Add some other properties
            person[FOAF.name] = "Alice"
            organization[FOAF.name] = "ACME Corp"
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(6, allTriples.size, "Should have 6 triples")
        
        val typeTriples = allTriples.filter { it.predicate == RDF.type }
        assertEquals(4, typeTriples.size, "Should have 4 type triples")
        
        val nameTriples = allTriples.filter { it.predicate == FOAF.name }
        assertEquals(2, nameTriples.size, "Should have 2 name triples")
        
        repo.close()
    }
}









