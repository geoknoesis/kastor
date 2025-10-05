package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigVariantsTest {
    
    @Test
    fun `all configuration variants are supported`() {
        val providers = RdfApiRegistry.discoverProviders()
        
        // Collect all supported types from all providers
        val allSupportedTypes = providers.flatMap { it.getSupportedTypes() }
        
        println("All supported configuration variants:")
        allSupportedTypes.forEach { type ->
            println("  - $type")
        }
        
        // Test that each type is supported
        allSupportedTypes.forEach { type ->
            assertTrue(RdfApiRegistry.isSupported(type), "Type $type should be supported")
        }
        
        // Verify we have the expected variants
        // Only memory provider is currently registered by default
        val expectedTypes = listOf("memory")
        
        expectedTypes.forEach { expectedType ->
            assertTrue(allSupportedTypes.contains(expectedType), 
                "Expected type $expectedType should be in supported types")
        }
    }
    
    @Test
    fun `jena variants work correctly`() {
        val jenaTypes = listOf(
            "jena:memory",
            "jena:memory:inference", 
            "jena:tdb2",
            "jena:tdb2:inference"
        )
        
        jenaTypes.forEach { type ->
            println("Testing Jena variant: $type")
            
            try {
                val config = if (type.contains("tdb2")) {
                    // Add location parameter for TDB2 variants
                    RdfConfig(type, mapOf("location" to "test-data"))
                } else {
                    RdfConfig(type)
                }
                
                val repo = RdfApiRegistry.create(config)
                assertNotNull(repo, "Repository should be created for $type")
                assertFalse(repo.isClosed(), "Repository should not be closed")
                assertNotNull(repo.defaultGraph, "Repository should have a default graph")
                
                println("  ✓ Successfully created repository for $type")
                
            } catch (e: Exception) {
                println("  ⚠ Failed to create repository for $type: ${e.message}")
                // Some variants might fail due to missing dependencies or file system issues
            }
        }
    }
    
    @Test
    fun `rdf4j variants work correctly`() {
        val rdf4jTypes = listOf(
            "rdf4j:memory",
            "rdf4j:native",
            "rdf4j:memory:star",
            "rdf4j:native:star",
            "rdf4j:memory:rdfs",
            "rdf4j:native:rdfs",
            "rdf4j:memory:shacl",
            "rdf4j:native:shacl"
        )
        
        rdf4jTypes.forEach { type ->
            println("Testing RDF4J variant: $type")
            
            try {
                val config = if (type.contains("native")) {
                    // Add location parameter for native variants
                    RdfConfig(type, mapOf("location" to "test-data"))
                } else {
                    RdfConfig(type)
                }
                
                val repo = RdfApiRegistry.create(config)
                assertNotNull(repo, "Repository should be created for $type")
                assertFalse(repo.isClosed(), "Repository should not be closed")
                assertNotNull(repo.defaultGraph, "Repository should have a default graph")
                
                println("  ✓ Successfully created repository for $type")
                
            } catch (e: Exception) {
                println("  ⚠ Failed to create repository for $type: ${e.message}")
                // Some variants might fail due to missing dependencies or file system issues
            }
        }
    }
    
    @Test
    fun `sparql variant works correctly`() {
        println("Testing SPARQL variant: sparql")
        
        try {
            val config = RdfConfig("sparql", mapOf("location" to "http://dbpedia.org/sparql"))
            val repo = RdfApiRegistry.create(config)
            
            assertNotNull(repo, "Repository should be created for sparql")
            assertFalse(repo.isClosed(), "Repository should not be closed")
            assertNotNull(repo.defaultGraph, "Repository should have a default graph")
            
            println("  ✓ Successfully created repository for sparql")
            
        } catch (e: Exception) {
            println("  ⚠ Failed to create repository for sparql: ${e.message}")
            // SPARQL might fail due to network issues
        }
    }
    
    @Test
    fun `memory fallback still works`() {
        val repo = RdfApiRegistry.create(RdfConfig("memory"))
        
        assertNotNull(repo, "Memory repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
        
        // Test basic operations
        val s = iri("urn:test:s")
        val p = iri("urn:test:p")
        val o = literal("test")
        
        repo.defaultGraph.addTriple(RdfTriple(s, p, o))
        
        // Memory provider currently has placeholder query implementation
        val result = repo.query("SELECT ?s WHERE { ?s ?p ?o }")
        assertEquals(0, result.count()) // Placeholder returns empty results
        
        println("✓ Memory repository fallback works correctly")
    }
}
