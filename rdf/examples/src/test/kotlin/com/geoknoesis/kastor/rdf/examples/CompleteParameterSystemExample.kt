package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Test

class CompleteParameterSystemExample {
    
    @Test
    fun `demonstrate complete parameter system`() {
        println("=== Complete Parameter System Demonstration ===\n")
        
        // 1. Discover all available variants
        println("1. Available Configuration Variants:")
        val variants = RdfApiRegistry.getAllConfigVariants()
        variants.forEach { variant ->
            println("  ${variant.type}: ${variant.description}")
            if (variant.parameters.isNotEmpty()) {
                variant.parameters.forEach { param ->
                    val required = if (param.optional) "optional" else "required"
                    println("    ${param.name} ($required ${param.type}): ${param.description}")
                    if (param.examples.isNotEmpty()) {
                        println("      Examples: ${param.examples.joinToString(", ")}")
                    }
                }
            } else {
                println("    No parameters required")
            }
            println()
        }
        
        // 2. Show parameter validation
        println("2. Parameter Validation Examples:")
        val testConfigs = listOf(
            RdfConfig("memory"),
            RdfConfig("jena:memory"),
            RdfConfig("jena:tdb2", mapOf("location" to "/data/tdb2")),
            RdfConfig("sparql", mapOf("location" to "http://dbpedia.org/sparql")),
            RdfConfig("jena:tdb2") // Missing required parameter
        )
        
        testConfigs.forEach { config ->
            println("  Validating: ${config.type}")
            val variant = RdfApiRegistry.getConfigVariant(config.type)
            
            if (variant != null) {
                val requiredParams = variant.parameters.filter { !it.optional }
                val missingParams = requiredParams.filter { param -> !config.params.containsKey(param.name) }
                
                if (missingParams.isEmpty()) {
                    println("    ✓ Valid configuration")
                    variant.parameters.forEach { param ->
                        val value = config.params[param.name]
                        if (value != null) {
                            println("      ${param.name}: $value")
                        }
                    }
                } else {
                    println("    ✗ Invalid configuration - missing required parameters:")
                    missingParams.forEach { param ->
                        println("      ${param.name}: ${param.description}")
                        println("        Examples: ${param.examples.joinToString(", ")}")
                    }
                }
            } else {
                println("    ✗ Unknown repository type")
            }
            println()
        }
        
        // 3. Generate usage examples
        println("3. Generated Usage Examples:")
        variants.forEach { variant ->
            println("  ${variant.type}:")
            if (variant.parameters.isEmpty()) {
                println("    RdfApiRegistry.create(RdfConfig(\"${variant.type}\"))")
            } else {
                val requiredParams = variant.parameters.filter { !it.optional }
                if (requiredParams.isEmpty()) {
                    println("    RdfApiRegistry.create(RdfConfig(\"${variant.type}\"))")
                } else {
                    val paramMap = requiredParams.map { param ->
                        val example = param.examples.firstOrNull() ?: "\"value\""
                        "\"${param.name}\" to $example"
                    }.joinToString(", ")
                    println("    RdfApiRegistry.create(RdfConfig(\"${variant.type}\", mapOf($paramMap)))")
                }
            }
            println()
        }
        
        // 4. Show parameter type analysis
        println("4. Parameter Type Analysis:")
        val allParams = variants.flatMap { it.parameters }
        val paramsByType = allParams.groupBy { it.type }
        
        paramsByType.forEach { (type, params) ->
            println("  $type parameters:")
            params.forEach { param ->
                val usedBy = variants.filter { it.parameters.contains(param) }.map { it.type }
                println("    ${param.name}: Used by ${usedBy.joinToString(", ")}")
                println("      Description: ${param.description}")
                if (param.examples.isNotEmpty()) {
                    println("      Examples: ${param.examples.joinToString(", ")}")
                }
            }
            println()
        }
        
        // 5. Show provider-specific information
        println("5. Provider-Specific Information:")
        val providers = RdfApiRegistry.discoverProviders()
        providers.forEach { provider ->
            println("  ${provider.name} v${provider.version}:")
            val providerVariants = RdfApiRegistry.getConfigVariantsForProvider(provider.name)
            providerVariants.forEach { variant ->
                println("    ${variant.type}: ${variant.description}")
                if (variant.parameters.isNotEmpty()) {
                    variant.parameters.forEach { param ->
                        println("      ${param.name}: ${param.description}")
                    }
                }
            }
            println()
        }
    }
    
    @Test
    fun `show parameter help system`() {
        println("=== Parameter Help System ===\n")
        
        // Generate comprehensive help for each variant
        val variants = RdfApiRegistry.getAllConfigVariants()
        
        variants.forEach { variant ->
            println("${variant.type}")
            println("=".repeat(variant.type.length))
            println("Description: ${variant.description}")
            println()
            
            if (variant.parameters.isEmpty()) {
                println("No parameters required.")
                println()
                println("Usage:")
                println("  RdfApiRegistry.create(RdfConfig(\"${variant.type}\"))")
            } else {
                println("Parameters:")
                variant.parameters.forEach { param ->
                    val required = if (param.optional) "Optional" else "Required"
                    println("  ${param.name} ($required ${param.type})")
                    println("    ${param.description}")
                    if (param.defaultValue != null) {
                        println("    Default: ${param.defaultValue}")
                    }
                    if (param.examples.isNotEmpty()) {
                        println("    Examples:")
                        param.examples.forEach { example ->
                            println("      - $example")
                        }
                    }
                    println()
                }
                
                println("Usage:")
                val requiredParams = variant.parameters.filter { !it.optional }
                if (requiredParams.isEmpty()) {
                    println("  RdfApiRegistry.create(RdfConfig(\"${variant.type}\"))")
                } else {
                    val paramMap = requiredParams.map { param ->
                        val example = param.examples.firstOrNull() ?: "\"value\""
                        "\"${param.name}\" to $example"
                    }.joinToString(", ")
                    println("  RdfApiRegistry.create(RdfConfig(\"${variant.type}\", mapOf($paramMap)))")
                }
            }
            println()
            println("-".repeat(50))
            println()
        }
    }
}
