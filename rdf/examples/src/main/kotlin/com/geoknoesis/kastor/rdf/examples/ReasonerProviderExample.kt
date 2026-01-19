package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS

/**
 * Example demonstrating how to use different reasoner providers.
 */
class ReasonerProviderExample {
    
    fun main() {
        println("=== Reasoner Provider Example ===")
        
        // Create a sample graph
        val graph = createSampleGraph()
        
        // Discover available providers
        val providers = RdfReasoning.reasonerProviders()
        println("Available reasoner providers:")
        providers.forEach { provider ->
            println("  ${provider.name} (${provider.getType()}) - v${provider.version}")
            println("    Supported types: ${provider.getSupportedTypes().joinToString(", ")}")
            println("    Capabilities: ${provider.getCapabilities()}")
        }
        
        // Test each available provider
        providers.forEach { provider ->
            println("\n=== Testing ${provider.name} ===")
            
            // Find a supported reasoner type
            val supportedType = provider.getSupportedTypes().firstOrNull()
            if (supportedType != null) {
                try {
                    val config = ReasonerConfig(reasonerType = supportedType)
                    val reasoner = provider.createReasoner(config)
                    
                    val startTime = System.currentTimeMillis()
                    val result = reasoner.reason(graph)
                    val endTime = System.currentTimeMillis()
                    
                    println("✓ Successfully reasoned with ${provider.name}")
                    println("  Reasoning time: ${endTime - startTime}ms")
                    println("  Inferred triples: ${result.inferredTriples.size}")
                    println("  Consistent: ${result.consistencyCheck.isConsistent}")
                    
                } catch (e: Exception) {
                    println("✗ Failed to reason with ${provider.name}: ${e.message}")
                }
            } else {
                println("  No supported reasoner types")
            }
        }
        
        // Demonstrate different configurations
        println("\n=== Configuration Examples ===")
        
        val supportedTypes = RdfReasoning.supportedReasonerTypes()
        println("Supported reasoner types: ${supportedTypes.joinToString(", ")}")
        
        // Test different configurations if RDFS is supported
        if (supportedTypes.contains(ReasonerType.RDFS)) {
            println("\nTesting different RDFS configurations:")
            
            val configs = listOf(
                "Default" to ReasonerConfig.default(),
                "RDFS only" to ReasonerConfig.rdfs(),
                "Large graphs" to ReasonerConfig.forLargeGraphs(),
                "Memory constrained" to ReasonerConfig.forMemoryConstrained()
            )
            
            configs.forEach { (name, config) ->
                try {
                    val reasoner = RdfReasoning.reasoner(config)
                    val result = reasoner.reason(graph)
                    println("  $name: ${result.inferredTriples.size} inferred triples in ${result.reasoningTime.toMillis()}ms")
                } catch (e: Exception) {
                    println("  $name: Failed - ${e.message}")
                }
            }
        }
        
        // Demonstrate custom rules (if supported)
        println("\n=== Custom Rules Example ===")
        
        val customRuleProvider = providers.find { it.getCapabilities().supportsCustomRules }
        if (customRuleProvider != null) {
            try {
                val customRule = CustomRule(
                    name = "sibling-rule",
                    pattern = "(?x ex:hasParent ?y) (?z ex:hasParent ?y)",
                    conclusion = "(?x ex:hasSibling ?z)",
                    description = "If two people have the same parent, they are siblings"
                )
                
                val config = ReasonerConfig(
                    reasonerType = ReasonerType.CUSTOM,
                    customRules = listOf(customRule)
                )
                
                val reasoner = customRuleProvider.createReasoner(config)
                val result = reasoner.reason(graph)
                
                println("✓ Custom rules applied successfully")
                println("  Inferred triples: ${result.inferredTriples.size}")
                
            } catch (e: Exception) {
                println("✗ Custom rules failed: ${e.message}")
            }
        } else {
            println("No providers support custom rules")
        }
    }
    
    private fun createSampleGraph(): RdfGraph {
        return Rdf.graph {
            prefixes {
                put("ex", "http://example.org/")
                put("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
            }
            
            // Simple RDFS schema
            val person = Iri("ex:Person")
            val student = Iri("ex:Student")
            val teacher = Iri("ex:Teacher")
            
            person - RDFS.subClassOf - Iri("rdfs:Resource")
            student - RDFS.subClassOf - person
            teacher - RDFS.subClassOf - person
            
            // Property hierarchy
            val knows = Iri("ex:knows")
            val teaches = Iri("ex:teaches")
            
            knows - RDFS.subPropertyOf - Iri("rdfs:seeAlso")
            teaches - RDFS.subPropertyOf - knows
            
            // Domain and range
            knows - RDFS.domain - person
            knows - RDFS.range - person
            
            // Instances
            val alice = Iri("ex:alice")
            val bob = Iri("ex:bob")
            
            alice - RDF.type - student
            bob - RDF.type - teacher
            alice - teaches - bob
        }
    }
}

fun main() {
    ReasonerProviderExample().main()
}









