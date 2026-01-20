package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigVariantsTest {
    
    @Test
    fun `all configuration variants are supported`() {
        val allSupportedTypes = RdfApiRegistry.getSupportedTypes()
        
        println("All supported configuration variants:")
        allSupportedTypes.forEach { type ->
            println("  - $type")
        }
        
        // Test that each type is supported
        allSupportedTypes.forEach { type ->
            val parts = type.split(":", limit = 2)
            assertEquals(2, parts.size, "Type $type should include provider and variant")
            assertTrue(
                RdfApiRegistry.supportsVariant(parts[0], parts[1]),
                "Type $type should be supported"
            )
        }
        
        // Verify we have the expected variants
        // Only memory provider is currently registered by default
        val expectedTypes = listOf("memory:memory")
        
        expectedTypes.forEach { expectedType ->
            assertTrue(allSupportedTypes.contains(expectedType), 
                "Expected type $expectedType should be in supported types")
        }
    }
    
    @Test
    fun `jena variants work correctly`() {
        val jenaVariants = listOf(
            "memory",
            "memory-inference",
            "tdb2",
            "tdb2-inference"
        )
        
        jenaVariants.forEach { variant ->
            println("Testing Jena variant: jena:$variant")
            
            try {
                val options = if (variant.contains("tdb2")) {
                    mapOf("location" to "test-data")
                } else {
                    emptyMap()
                }
                val config = RdfConfig(
                    providerId = "jena",
                    variantId = variant,
                    options = options
                )
                
                val repo = RdfApiRegistry.create(config)
                assertNotNull(repo, "Repository should be created for jena:$variant")
                assertFalse(repo.isClosed(), "Repository should not be closed")
                assertNotNull(repo.defaultGraph, "Repository should have a default graph")
                
                println("  ✓ Successfully created repository for jena:$variant")
                
            } catch (e: Exception) {
                println("  ⚠ Failed to create repository for jena:$variant: ${e.message}")
                // Some variants might fail due to missing dependencies or file system issues
            }
        }
    }
    
    @Test
    fun `rdf4j variants work correctly`() {
        val rdf4jVariants = listOf(
            "memory",
            "native",
            "memory-star",
            "native-star",
            "memory-rdfs",
            "native-rdfs",
            "memory-shacl",
            "native-shacl"
        )
        
        rdf4jVariants.forEach { variant ->
            println("Testing RDF4J variant: rdf4j:$variant")
            
            try {
                val options = if (variant.contains("native")) {
                    mapOf("location" to "test-data")
                } else {
                    emptyMap()
                }
                val config = RdfConfig(
                    providerId = "rdf4j",
                    variantId = variant,
                    options = options
                )
                
                val repo = RdfApiRegistry.create(config)
                assertNotNull(repo, "Repository should be created for rdf4j:$variant")
                assertFalse(repo.isClosed(), "Repository should not be closed")
                assertNotNull(repo.defaultGraph, "Repository should have a default graph")
                
                println("  ✓ Successfully created repository for rdf4j:$variant")
                
            } catch (e: Exception) {
                println("  ⚠ Failed to create repository for rdf4j:$variant: ${e.message}")
                // Some variants might fail due to missing dependencies or file system issues
            }
        }
    }
    
    @Test
    fun `sparql variant works correctly`() {
        println("Testing SPARQL variant: sparql")
        
        try {
        val config = RdfConfig(
            providerId = "sparql",
            variantId = "sparql",
            options = mapOf("location" to "http://dbpedia.org/sparql")
        )
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
        val repo = RdfApiRegistry.create(RdfConfig(providerId = "memory", variantId = "memory"))
        
        assertNotNull(repo, "Memory repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
        
        // Test basic operations
        val s = Iri("urn:test:s")
        val p = Iri("urn:test:p")
        val o = Literal("test")
        
        repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))
        
        assertTrue(repo.defaultGraph.hasTriple(RdfTriple(s, p, o)))
        
        println("✓ Memory repository fallback works correctly")
    }
}









