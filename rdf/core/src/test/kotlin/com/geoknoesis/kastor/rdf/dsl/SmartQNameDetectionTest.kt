package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Test smart QName detection in object position.
 * 
 * Smart QName detection rules:
 * - If predicate is rdf:type: Always try to resolve as QName/IRI
 * - If value looks like QName and prefix is declared: Resolve as IRI
 * - Otherwise: Create string literal
 */
class SmartQNameDetectionTest {

    @Test
    fun `smart QName detection works with minus operator`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
                put("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
            }
            
            val person = iri("http://example.org/person")
            
            // ✅ Smart: QName with declared prefix → IRI
            person - "foaf:knows" - "foaf:Person"
            
            // ✅ Smart: String without colon → string literal
            person - "foaf:name" - "Alice"
            
            // ✅ Smart: QName with undeclared prefix → string literal
            person - "foaf:name" - "unknown:Person"
            
            // ✅ Smart: Full IRI → IRI
            person - "foaf:homepage" - "http://example.org/friend"
            
            // ✅ Special case: rdf:type always resolves QNames
            person - "a" - "foaf:Agent"
            person `is` "foaf:Person"
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify QName resolution
        val knowsTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/knows" }
        assertNotNull(knowsTriple, "Should have knows triple")
        assertTrue(knowsTriple!!.obj is Iri, "foaf:Person should be resolved to IRI")
        assertEquals("http://xmlns.com/foaf/0.1/Person", (knowsTriple.obj as Iri).value)
        
        // Verify string literal
        val aliceTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/name" && (it.obj as? Literal)?.lexical == "Alice" }
        assertNotNull(aliceTriple, "Should have Alice as string literal")
        assertTrue(aliceTriple!!.obj is Literal, "Alice should be string literal")
        
        // Verify undeclared prefix becomes string literal
        val unknownTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/name" && (it.obj as? Literal)?.lexical == "unknown:Person" }
        assertNotNull(unknownTriple, "Should have unknown:Person as string literal")
        
        // Verify full IRI
        val iriTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/homepage" && (it.obj as? Iri)?.value == "http://example.org/friend" }
        assertNotNull(iriTriple, "Should have full IRI")
        
        // Verify rdf:type special case
        val typeTriples = triples.filter { it.predicate == RDF.type }
        assertEquals(2, typeTriples.size, "Should have 2 type triples")
        assertTrue(typeTriples.all { it.obj is Iri }, "All type objects should be IRIs")
    }

    @Test
    fun `smart QName detection works with bracket syntax`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
            }
            
            val person = iri("http://example.org/person")
            
            // ✅ Smart QName detection
            person["foaf:knows"] = "foaf:Person"  // Should become IRI
            person["foaf:name"] = "Alice"         // Should become string literal
            
            // ✅ Special case: rdf:type
            person["a"] = "foaf:Agent"            // Should become IRI
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify QName resolution
        val knowsTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/knows" }
        assertNotNull(knowsTriple, "Should have knows triple")
        assertTrue(knowsTriple!!.obj is Iri, "foaf:Person should be resolved to IRI")
        assertEquals("http://xmlns.com/foaf/0.1/Person", (knowsTriple.obj as Iri).value)
        
        // Verify string literal
        val nameTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/name" }
        assertNotNull(nameTriple, "Should have name triple")
        assertTrue(nameTriple!!.obj is Literal, "Alice should be string literal")
        assertEquals("Alice", (nameTriple.obj as Literal).lexical)
        
        // Verify rdf:type special case
        val typeTriples = triples.filter { it.predicate == RDF.type }
        assertEquals(1, typeTriples.size, "Should have 1 type triple")
        assertTrue(typeTriples.all { it.obj is Iri }, "Type object should be IRI")
    }

    @Test
    fun `smart QName detection works with has-with syntax`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
            }
            
            val person = iri("http://example.org/person")
            
            // ✅ Smart QName detection
            person has "foaf:knows" with "foaf:Person"  // Should become IRI
            person has "foaf:name" with "Alice"         // Should become string literal
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify QName resolution
        val knowsTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/knows" }
        assertNotNull(knowsTriple, "Should have knows triple")
        assertTrue(knowsTriple!!.obj is Iri, "foaf:Person should be resolved to IRI")
        assertEquals("http://xmlns.com/foaf/0.1/Person", (knowsTriple.obj as Iri).value)
        
        // Verify string literal
        val nameTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/name" }
        assertNotNull(nameTriple, "Should have name triple")
        assertTrue(nameTriple!!.obj is Literal, "Alice should be string literal")
        assertEquals("Alice", (nameTriple.obj as Literal).lexical)
    }

    @Test
    fun `smart QName detection works with graph DSL`() {
        val graph = Rdf.graph {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
                put("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
            }
            
            val person = iri("http://example.org/person")
            
            // ✅ Smart QName detection
            person - "foaf:knows" - "foaf:Person"
            person - "foaf:name" - "Alice"
            person - "rdfs:subClassOf" - "foaf:Agent"
            
            // ✅ Special case: rdf:type
            person["a"] = "foaf:Person"
            person `is` "foaf:Agent"
        }
        
        val triples = graph.getTriples()
        
        // Verify all QNames are resolved to IRIs
        val knowsTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/knows" }
        assertNotNull(knowsTriple, "Should have knows triple")
        assertTrue(knowsTriple!!.obj is Iri, "foaf:Person should be resolved to IRI")
        
        val subClassTriple = triples.find { it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#subClassOf" }
        assertNotNull(subClassTriple, "Should have subClassOf triple")
        assertTrue(subClassTriple!!.obj is Iri, "foaf:Agent should be resolved to IRI")
        
        // Verify string literal
        val nameTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/name" }
        assertNotNull(nameTriple, "Should have name triple")
        assertTrue(nameTriple!!.obj is Literal, "Alice should be string literal")
        
        // Verify rdf:type special case
        val typeTriples = triples.filter { it.predicate == RDF.type }
        assertEquals(2, typeTriples.size, "Should have 2 type triples")
        assertTrue(typeTriples.all { it.obj is Iri }, "All type objects should be IRIs")
    }

    @Test
    fun `explicit literal creation still works`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
            }
            
            val person = iri("http://example.org/person")
            
            // ✅ Explicit literal creation overrides smart detection
            person - "foaf:name" - literal("foaf:Person")  // Should be string literal, not IRI
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify explicit literal creation
        val nameTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/name" }
        assertNotNull(nameTriple, "Should have name triple")
        assertTrue(nameTriple!!.obj is Literal, "Explicit literal should be Literal")
        assertEquals("foaf:Person", (nameTriple.obj as Literal).lexical)
    }

    @Test
    fun `explicit qname creation still works`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
            }
            
            val person = iri("http://example.org/person")
            
            // ✅ Explicit QName creation
            person - "foaf:name" - qname("foaf:Person")  // Should be IRI
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify explicit QName creation
        val nameTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/name" }
        assertNotNull(nameTriple, "Should have name triple")
        assertTrue(nameTriple!!.obj is Iri, "Explicit QName should be IRI")
        assertEquals("http://xmlns.com/foaf/0.1/Person", (nameTriple.obj as Iri).value)
    }

    @Test
    fun `edge cases for QName detection`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
            }
            
            val person = iri("http://example.org/person")
            
            // ✅ Edge cases
            person - "foaf:name" - ":"                    // Invalid QName → string literal
            person - "foaf:name" - "foaf:"                // Invalid QName → string literal  
            person - "foaf:name" - ":Person"              // Invalid QName → string literal
            person - "foaf:name" - "http://example.org"   // Full IRI → IRI
            person - "foaf:name" - "https://example.org"  // Full IRI → IRI
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify edge cases
        val invalidTriples = triples.filter { it.predicate.value == "http://xmlns.com/foaf/0.1/name" }
        assertEquals(5, invalidTriples.size, "Should have 5 name triples")
        
        // All should be literals except the full IRIs
        val literalTriples = invalidTriples.filter { it.obj is Literal }
        assertEquals(3, literalTriples.size, "Should have 3 literal triples")
        
        val iriTriples = invalidTriples.filter { it.obj is Iri }
        assertEquals(2, iriTriples.size, "Should have 2 IRI triples")
    }
}
