package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnhancedParameterInfoTest {
    
    @Test
    fun `demonstrate enhanced parameter information`() {
        println("=== Enhanced Parameter Information ===\n")
        
        // Example 1: Get detailed parameter information
        val typesToCheck = listOf("memory", "jena:memory", "jena:tdb2", "rdf4j:native", "sparql")
        
        typesToCheck.forEach { type ->
            val variant = RdfApiRegistry.getConfigVariant(type)
            assertNotNull(variant, "Variant should exist for $type")
            
            println("$type:")
            println("  Description: ${variant?.description}")
            
            if (variant?.parameters?.isEmpty() == true) {
                println("  Parameters: None")
            } else {
                println("  Parameters:")
                variant?.parameters?.forEach { param ->
                    println("    ${param.name} (${param.type})${if (param.optional) " [optional]" else " [required]"}")
                    println("      Description: ${param.description}")
                    if (param.defaultValue != null) {
                        println("      Default: ${param.defaultValue}")
                    }
                    if (param.examples.isNotEmpty()) {
                        println("      Examples: ${param.examples.joinToString(", ")}")
                    }
                }
            }
            println()
        }
    }
    
    @Test
    fun `test parameter information methods`() {
        // Test getting parameter info for specific parameter
        val locationParam = RdfApiRegistry.getParameterInfo("jena:tdb2", "location")
        assertNotNull(locationParam, "Location parameter should exist for jena:tdb2")
        locationParam?.let { param ->
            assertEquals("location", param.name)
            assertEquals("String", param.type)
            assertFalse(param.optional, "Location should be required")
            assertTrue(param.examples.isNotEmpty(), "Should have examples")
        }
        
        // Test getting all parameters
        val allParams = RdfApiRegistry.getParameters("sparql")
        assertEquals(1, allParams.size, "SPARQL should have 1 parameter")
        assertEquals("location", allParams.first().name)
        
        // Test getting required parameters
        val requiredParams = RdfApiRegistry.getRequiredParameters("jena:tdb2")
        assertEquals(1, requiredParams.size, "jena:tdb2 should have 1 required parameter")
        assertEquals("location", requiredParams.first().name)
        
        // Test getting optional parameters
        val optionalParams = RdfApiRegistry.getOptionalParameters("jena:memory")
        assertEquals(0, optionalParams.size, "jena:memory should have no optional parameters")
    }
    
    @Test
    fun `show parameter validation examples`() {
        println("=== Parameter Validation Examples ===\n")
        
        val testConfigs = listOf(
            RdfConfig("memory"),
            RdfConfig("jena:memory"),
            RdfConfig("jena:tdb2", mapOf("location" to "/data/tdb2")),
            RdfConfig("sparql", mapOf("location" to "http://dbpedia.org/sparql")),
            RdfConfig("jena:tdb2") // Missing required parameter
        )
        
        testConfigs.forEach { config ->
            println("Validating: ${config.type}")
            val variant = RdfApiRegistry.getConfigVariant(config.type)
            
            if (variant != null) {
                val requiredParams = variant.parameters.filter { !it.optional }
                val missingParams = requiredParams.filter { param -> !config.params.containsKey(param.name) }
                
                if (missingParams.isEmpty()) {
                    println("  ✓ Valid configuration")
                    
                    // Show parameter values
                    variant.parameters.forEach { param ->
                        val value = config.params[param.name]
                        if (value != null) {
                            println("    ${param.name}: $value")
                        } else if (!param.optional) {
                            println("    ${param.name}: [MISSING]")
                        }
                    }
                } else {
                    println("  ✗ Invalid configuration")
                    missingParams.forEach { param ->
                        println("    Missing required parameter: ${param.name}")
                        println("      Description: ${param.description}")
                        println("      Examples: ${param.examples.joinToString(", ")}")
                    }
                }
            } else {
                println("  ✗ Unknown repository type")
            }
            println()
        }
    }
    
    @Test
    fun `show parameter help generation`() {
        println("=== Parameter Help Generation ===\n")
        
        val variants = RdfApiRegistry.getAllConfigVariants()
        
        variants.forEach { variant ->
            println("${variant.type}: ${variant.description}")
            
            if (variant.parameters.isEmpty()) {
                println("  Usage: RdfApiRegistry.create(RdfConfig(\"${variant.type}\"))")
            } else {
                println("  Parameters:")
                variant.parameters.forEach { param ->
                    val required = if (param.optional) "optional" else "required"
                    println("    ${param.name} ($required ${param.type}): ${param.description}")
                    if (param.examples.isNotEmpty()) {
                        println("      Examples: ${param.examples.joinToString(", ")}")
                    }
                }
                
                // Generate usage example
                val requiredParams = variant.parameters.filter { !it.optional }
                if (requiredParams.isEmpty()) {
                    println("  Usage: RdfApiRegistry.create(RdfConfig(\"${variant.type}\"))")
                } else {
                    val paramMap = requiredParams.map { param ->
                        val example = param.examples.firstOrNull() ?: "\"value\""
                        "\"${param.name}\" to $example"
                    }.joinToString(", ")
                    println("  Usage: RdfApiRegistry.create(RdfConfig(\"${variant.type}\", mapOf($paramMap)))")
                }
            }
            println()
        }
    }
    
    @Test
    fun `demonstrate parameter type information`() {
        println("=== Parameter Type Information ===\n")
        
        val variants = RdfApiRegistry.getAllConfigVariants()
        val allParams = variants.flatMap { it.parameters }
        
        // Group parameters by type
        val paramsByType = allParams.groupBy { it.type }
        
        paramsByType.forEach { (type, params) ->
            println("$type parameters:")
            params.forEach { param ->
                println("  ${param.name}: ${param.description}")
                println("    Optional: ${param.optional}")
                if (param.examples.isNotEmpty()) {
                    println("    Examples: ${param.examples.joinToString(", ")}")
                }
            }
            println()
        }
        
        // Show parameter usage patterns
        println("Parameter Usage Patterns:")
        val locationParams = allParams.filter { it.name == "location" }
        locationParams.forEach { param ->
            println("  ${param.name} (${param.type}): Used by ${variants.filter { it.parameters.contains(param) }.map { it.type }}")
        }
    }
}
