package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigVariantInfoTest {
    
    @Test
    fun `get all configuration variants`() {
        val variants = RdfApiRegistry.getAllConfigVariants()
        
        assertTrue(variants.isNotEmpty(), "Should have configuration variants")
        
        println("All Configuration Variants:")
        variants.forEach { variant ->
            println("  ${variant.type}")
            println("    Description: ${variant.description}")
            if (variant.requiredParams.isNotEmpty()) {
                println("    Required Parameters: ${variant.requiredParams.joinToString()}")
            }
            if (variant.optionalParams.isNotEmpty()) {
                println("    Optional Parameters: ${variant.optionalParams.joinToString()}")
            }
            println()
        }
        
        // Verify we have the expected variants
        val expectedTypes = listOf(
            "memory",
            "jena:memory", "jena:memory:inference", "jena:tdb2", "jena:tdb2:inference",
            "rdf4j:memory", "rdf4j:native", "rdf4j:memory:star", "rdf4j:native:star",
            "rdf4j:memory:rdfs", "rdf4j:native:rdfs", "rdf4j:memory:shacl", "rdf4j:native:shacl",
            "sparql"
        )
        
        expectedTypes.forEach { expectedType ->
            assertTrue(variants.any { it.type == expectedType }, 
                "Should have variant: $expectedType")
        }
    }
    
    @Test
    fun `get configuration variant for specific type`() {
        // Test memory variant
        val memoryVariant = RdfApiRegistry.getConfigVariant("memory")
        assertNotNull(memoryVariant, "Memory variant should exist")
        memoryVariant?.let { variant ->
            assertEquals("memory", variant.type)
            assertEquals("Simple in-memory repository (fallback when no provider is available)", variant.description)
            assertTrue(variant.requiredParams.isEmpty(), "Memory should have no required params")
        }
        
        // Test Jena TDB2 variant
        val jenaTdb2Variant = RdfApiRegistry.getConfigVariant("jena:tdb2")
        assertNotNull(jenaTdb2Variant, "Jena TDB2 variant should exist")
        jenaTdb2Variant?.let { variant ->
            assertEquals("jena:tdb2", variant.type)
            assertEquals("Persistent TDB2 repository", variant.description)
            assertEquals(listOf("location"), variant.requiredParams)
        }
        
        // Test RDF4J native variant
        val rdf4jNativeVariant = RdfApiRegistry.getConfigVariant("rdf4j:native")
        assertNotNull(rdf4jNativeVariant, "RDF4J native variant should exist")
        rdf4jNativeVariant?.let { variant ->
            assertEquals("rdf4j:native", variant.type)
            assertEquals("Persistent NativeStore repository", variant.description)
            assertEquals(listOf("location"), variant.requiredParams)
        }
        
        // Test SPARQL variant
        val sparqlVariant = RdfApiRegistry.getConfigVariant("sparql")
        assertNotNull(sparqlVariant, "SPARQL variant should exist")
        sparqlVariant?.let { variant ->
            assertEquals("sparql", variant.type)
            assertEquals("Remote SPARQL endpoint repository", variant.description)
            assertEquals(listOf("location"), variant.requiredParams)
        }
        
        // Test non-existent variant
        val nonExistentVariant = RdfApiRegistry.getConfigVariant("nonexistent:type")
        assertNull(nonExistentVariant, "Non-existent variant should return null")
    }
    
    @Test
    fun `get configuration variants for specific provider`() {
        // Test Jena provider
        val jenaVariants = RdfApiRegistry.getConfigVariantsForProvider("jena")
        assertTrue(jenaVariants.isNotEmpty(), "Jena should have variants")
        assertEquals(4, jenaVariants.size, "Jena should have 4 variants")
        
        println("Jena Configuration Variants:")
        jenaVariants.forEach { variant ->
            println("  ${variant.type}: ${variant.description}")
            if (variant.requiredParams.isNotEmpty()) {
                println("    Required: ${variant.requiredParams.joinToString()}")
            }
        }
        
        // Test RDF4J provider
        val rdf4jVariants = RdfApiRegistry.getConfigVariantsForProvider("rdf4j")
        assertTrue(rdf4jVariants.isNotEmpty(), "RDF4J should have variants")
        assertEquals(8, rdf4jVariants.size, "RDF4J should have 8 variants")
        
        println("\nRDF4J Configuration Variants:")
        rdf4jVariants.forEach { variant ->
            println("  ${variant.type}: ${variant.description}")
            if (variant.requiredParams.isNotEmpty()) {
                println("    Required: ${variant.requiredParams.joinToString()}")
            }
        }
        
        // Test SPARQL provider
        val sparqlVariants = RdfApiRegistry.getConfigVariantsForProvider("sparql")
        assertTrue(sparqlVariants.isNotEmpty(), "SPARQL should have variants")
        assertEquals(1, sparqlVariants.size, "SPARQL should have 1 variant")
        
        println("\nSPARQL Configuration Variants:")
        sparqlVariants.forEach { variant ->
            println("  ${variant.type}: ${variant.description}")
            if (variant.requiredParams.isNotEmpty()) {
                println("    Required: ${variant.requiredParams.joinToString()}")
            }
        }
        
        // Test non-existent provider
        val nonExistentVariants = RdfApiRegistry.getConfigVariantsForProvider("nonexistent")
        assertTrue(nonExistentVariants.isEmpty(), "Non-existent provider should return empty list")
    }
    
    @Test
    fun `demonstrate parameter usage`() {
        // Show how to use parameter information
        val variants = RdfApiRegistry.getAllConfigVariants()
        
        println("Parameter Usage Examples:")
        variants.forEach { variant ->
            when {
                variant.requiredParams.isEmpty() -> {
                    println("${variant.type}: No parameters needed")
                    println("  val repo = RdfApiRegistry.create(RdfConfig(\"${variant.type}\"))")
                }
                variant.requiredParams.contains("location") -> {
                    println("${variant.type}: Requires 'location' parameter")
                    println("  val repo = RdfApiRegistry.create(RdfConfig(\"${variant.type}\", mapOf(\"location\" to \"/path/to/data\")))")
                }
            }
            println()
        }
    }
}
