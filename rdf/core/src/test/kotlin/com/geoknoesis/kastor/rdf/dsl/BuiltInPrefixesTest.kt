package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Test built-in prefixes for common vocabularies.
 */
class BuiltInPrefixesTest {

    @Test
    fun `built-in prefixes work in repository DSL`() {
        val repo = Rdf.memory()
        
        repo.add {
            // No need to declare common prefixes - they're built-in!
            val person = Iri("http://example.org/person")
            
            // RDF vocabulary
            person - RDF.type - qname("rdfs:Class")
            person[RDF.type] = qname("rdf:Statement")
            
            // RDFS vocabulary
            person - qname("rdfs:label") - "Person Class"
            person - qname("rdfs:comment") - "A person in our system"
            person - qname("rdfs:subClassOf") - qname("rdfs:Resource")
            
            // OWL vocabulary
            person - qname("owl:sameAs") - Iri("http://example.org/person2")
            person - qname("owl:differentFrom") - Iri("http://example.org/animal")
            
            // SHACL vocabulary
            person - qname("sh:targetClass") - qname("rdfs:Class")
            person - qname("sh:property") - qname("rdf:type")
            
            // XSD datatypes (used in smart object creation)
            person - qname("rdfs:range") - qname("xsd:string")
            person - qname("rdfs:domain") - qname("xsd:integer")
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify RDF triples
        val typeTriple = triples.find { it.predicate == RDF.type && it.obj is Iri }
        assertNotNull(typeTriple, "Should have rdf:type triple")
        assertEquals("http://www.w3.org/2000/01/rdf-schema#Class", (typeTriple!!.obj as Iri).value)
        
        // Verify RDFS triples
        val labelTriple = triples.find { it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertNotNull(labelTriple, "Should have rdfs:label triple")
        assertEquals("Person Class", (labelTriple!!.obj as Literal).lexical)
        
        // Verify OWL triples
        val sameAsTriple = triples.find { it.predicate.value == "http://www.w3.org/2002/07/owl#sameAs" }
        assertNotNull(sameAsTriple, "Should have owl:sameAs triple")
        assertEquals("http://example.org/person2", (sameAsTriple!!.obj as Iri).value)
        
        // Verify SHACL triples
        val targetClassTriple = triples.find { it.predicate.value == "http://www.w3.org/ns/shacl#targetClass" }
        assertNotNull(targetClassTriple, "Should have sh:targetClass triple")
        assertEquals("http://www.w3.org/2000/01/rdf-schema#Class", (targetClassTriple!!.obj as Iri).value)
        
        // Verify XSD datatypes in smart object creation
        val rangeTriple = triples.find { it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#range" }
        assertNotNull(rangeTriple, "Should have rdfs:range triple")
        assertEquals("http://www.w3.org/2001/XMLSchema#string", (rangeTriple!!.obj as Iri).value)
        
        println("✅ Built-in prefixes test passed with ${triples.size} triples")
    }

    @Test
    fun `built-in prefixes work in graph DSL`() {
        val graph = Rdf.graph {
            // No need to declare common prefixes - they're built-in!
            val concept = Iri("http://example.org/concept")
            
            // Mix of built-in prefixes
            concept - RDF.type - qname("owl:Class")
            concept - qname("rdfs:label") - "Example Concept"
            concept - qname("owl:equivalentClass") - qname("rdfs:Resource")
            concept - qname("sh:targetClass") - qname("owl:Class")
        }
        
        val triples = graph.getTriples()
        assertEquals(4, triples.size, "Should have 4 triples")
        
        // Verify all triples use correct namespaces
        val typeTriple = triples.find { it.predicate == RDF.type }
        assertNotNull(typeTriple, "Should have rdf:type triple")
        assertEquals("http://www.w3.org/2002/07/owl#Class", (typeTriple!!.obj as Iri).value)
        
        val labelTriple = triples.find { it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertNotNull(labelTriple, "Should have rdfs:label triple")
        assertEquals("Example Concept", (labelTriple!!.obj as Literal).lexical)
        
        println("✅ Graph DSL built-in prefixes test passed")
    }

    @Test
    fun `built-in prefixes can be overridden`() {
        val repo = Rdf.memory()
        
        repo.add {
            // Override built-in prefix
            prefixes {
                put("rdf", "http://example.org/custom-rdf#")
            }
            
            val resource = Iri("http://example.org/resource")
            
            // Should use custom namespace, not built-in
            resource[qname("rdf:type")] = qname("rdf:CustomType")
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify custom prefix was used
        val typeTriple = triples.find { it.predicate.value == "http://example.org/custom-rdf#type" }
        assertNotNull(typeTriple, "Should use custom rdf prefix")
        
        val customTypeTriple = triples.find { it.obj is Iri && (it.obj as Iri).value == "http://example.org/custom-rdf#CustomType" }
        assertNotNull(customTypeTriple, "Should use custom namespace for rdf:CustomType")
        
        println("✅ Built-in prefix override test passed")
    }

    @Test
    fun `built-in prefixes work with smart QName detection`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = Iri("http://example.org/person")
            
            // Smart QName detection with built-in prefixes
            person - RDF.type - qname("rdfs:Class")           // Both QNames → IRIs
            person - qname("rdfs:subClassOf") - qname("rdfs:Resource") // Both QNames → IRIs
            person - qname("owl:sameAs") - Iri("http://example.org/person2")  // QName + Full IRI → IRIs
            person - qname("sh:targetClass") - qname("owl:Class")      // Both QNames → IRIs
            
            // Mixed with custom prefixes
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
            }
            person - qname("foaf:name") - "Alice"               // Custom prefix + string literal
            person - qname("rdfs:label") - "Person"             // Built-in prefix + string literal
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify smart detection worked with built-in prefixes
        val typeTriple = triples.find { it.predicate == RDF.type }
        assertNotNull(typeTriple, "Should have rdf:type triple")
        assertTrue(typeTriple!!.obj is Iri, "rdfs:Class should be resolved to IRI")
        assertEquals("http://www.w3.org/2000/01/rdf-schema#Class", (typeTriple.obj as Iri).value)
        
        val subClassTriple = triples.find { it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#subClassOf" }
        assertNotNull(subClassTriple, "Should have rdfs:subClassOf triple")
        assertTrue(subClassTriple!!.obj is Iri, "rdfs:Resource should be resolved to IRI")
        
        val sameAsTriple = triples.find { it.predicate.value == "http://www.w3.org/2002/07/owl#sameAs" }
        assertNotNull(sameAsTriple, "Should have owl:sameAs triple")
        assertTrue(sameAsTriple!!.obj is Iri, "Full IRI should be resolved to IRI")
        
        println("✅ Built-in prefixes with smart QName detection test passed")
    }

    @Test
    fun `built-in prefixes include all expected vocabularies`() {
        val repo = Rdf.memory()
        
        repo.add {
            val resource = Iri("http://example.org/resource")
            
            // Test all built-in prefixes
            resource - RDF.type - qname("rdfs:Class")           // RDF namespace
            resource - qname("rdfs:label") - "Test Resource"      // RDFS namespace
            resource - qname("owl:sameAs") - Iri("http://example.org/other")  // OWL namespace
            resource - qname("sh:targetClass") - qname("rdfs:Class")     // SHACL namespace
            resource - qname("xsd:string") - "test"               // XSD namespace (as predicate)
        }
        
        val triples = repo.defaultGraph.getTriples()
        
        // Verify all namespaces are correctly resolved
        val expectedNamespaces = listOf(
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#",
            "http://www.w3.org/2002/07/owl#",
            "http://www.w3.org/ns/shacl#",
            "http://www.w3.org/2001/XMLSchema#"
        )
        
        val actualNamespaces = triples.flatMap { listOf(it.predicate.value, (it.obj as? Iri)?.value) }
            .filterNotNull()
            .filter { it.contains("://") }
            .map { it.substringBeforeLast("#") + "#" }
        
        expectedNamespaces.forEach { expectedNs ->
            assertTrue(actualNamespaces.contains(expectedNs), "Should include namespace: $expectedNs")
        }
        
        println("✅ All built-in prefixes verified")
        println("   Built-in prefixes: rdf, rdfs, owl, sh, xsd")
        println("   Total triples: ${triples.size}")
    }
}









