package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Test

class ParameterUsageExample {
    
    @Test
    fun `demonstrate parameter usage patterns`() {
        println("=== Parameter Usage Examples ===\n")
        
        // Example 1: Check what parameters a type needs
        val typesToCheck = listOf("memory", "jena:memory", "jena:tdb2", "rdf4j:native", "sparql")
        
        typesToCheck.forEach { type ->
            val requiredParams = RdfApiRegistry.getRequiredParams(type)
            val optionalParams = RdfApiRegistry.getOptionalParams(type)
            
            println("$type:")
            println("  Required parameters: ${if (requiredParams.isEmpty()) "none" else requiredParams.joinToString()}")
            println("  Optional parameters: ${if (optionalParams.isEmpty()) "none" else optionalParams.joinToString()}")
            
            // Show usage example
            when {
                requiredParams.isEmpty() -> {
                    println("  Usage: RdfApiRegistry.create(RdfConfig(\"$type\"))")
                }
                requiredParams.contains("location") -> {
                    println("  Usage: RdfApiRegistry.create(RdfConfig(\"$type\", mapOf(\"location\" to \"/path/to/data\")))")
                }
            }
            println()
        }
        
        // Example 2: Check if a specific parameter is required
        println("Parameter requirement checks:")
        println("  jena:tdb2 requires 'location': ${RdfApiRegistry.requiresParam("jena:tdb2", "location")}")
        println("  jena:memory requires 'location': ${RdfApiRegistry.requiresParam("jena:memory", "location")}")
        println("  sparql requires 'location': ${RdfApiRegistry.requiresParam("sparql", "location")}")
        println()
        
        // Example 3: Get all variants that require location parameter
        println("Variants that require 'location' parameter:")
        val allVariants = RdfApiRegistry.getAllConfigVariants()
        val locationRequiredVariants = allVariants.filter { it.requiredParams.contains("location") }
        locationRequiredVariants.forEach { variant ->
            println("  ${variant.type}: ${variant.description}")
        }
        println()
        
        // Example 4: Show how to validate configuration before creating repository
        println("Configuration validation example:")
        val configsToValidate = listOf(
            RdfConfig("memory"),
            RdfConfig("jena:memory"),
            RdfConfig("jena:tdb2", mapOf("location" to "/data/tdb2")),
            RdfConfig("sparql", mapOf("location" to "http://dbpedia.org/sparql")),
            RdfConfig("jena:tdb2") // This should fail validation
        )
        
        configsToValidate.forEach { config ->
            val requiredParams = RdfApiRegistry.getRequiredParams(config.type)
            val missingParams = requiredParams.filter { param -> !config.params.containsKey(param) }
            
            if (missingParams.isEmpty()) {
                println("  ✓ ${config.type}: Configuration is valid")
            } else {
                println("  ✗ ${config.type}: Missing required parameters: ${missingParams.joinToString()}")
            }
        }
    }
    
    @Test
    fun `show provider-specific parameter information`() {
        println("=== Provider-Specific Parameter Information ===\n")
        
        val providers = RdfApiRegistry.discoverProviders()
        
        providers.forEach { provider ->
            println("Provider: ${provider.name} v${provider.version}")
            val variants = provider.getConfigVariants()
            
            variants.forEach { variant ->
                println("  ${variant.type}")
                println("    Description: ${variant.description}")
                
                if (variant.requiredParams.isNotEmpty()) {
                    println("    Required parameters:")
                    variant.requiredParams.forEach { param ->
                        println("      - $param")
                    }
                }
                
                if (variant.optionalParams.isNotEmpty()) {
                    println("    Optional parameters:")
                    variant.optionalParams.forEach { param ->
                        println("      - $param")
                    }
                }
                
                println()
            }
        }
    }
}
