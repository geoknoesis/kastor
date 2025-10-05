package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.RDF

/**
 * Basic reasoning example demonstrating RDFS inference.
 */
class BasicReasoningExample {
    
    fun main() {
        println("=== Basic Reasoning Example ===")
        
        // Create a graph with RDFS schema
        val graph = Rdf.graph {
            prefixes {
                put("ex", "http://example.org/")
                put("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
            }
            
            // Define class hierarchy
            val person = iri("ex:Person")
            val student = iri("ex:Student")
            val teacher = iri("ex:Teacher")
            
            person - RDFS.subClassOf - iri("rdfs:Resource")
            student - RDFS.subClassOf - person
            teacher - RDFS.subClassOf - person
            
            // Define property hierarchy
            val knows = iri("ex:knows")
            val teaches = iri("ex:teaches")
            
            knows - RDFS.subPropertyOf - iri("rdfs:seeAlso")
            teaches - RDFS.subPropertyOf - knows
            
            // Define domain and range
            knows - RDFS.domain - person
            knows - RDFS.range - person
            
            teaches - RDFS.domain - teacher
            teaches - RDFS.range - student
            
            // Add some instances
            val alice = iri("ex:alice")
            val bob = iri("ex:bob")
            
            alice - RDF.type - student
            bob - RDF.type - teacher
            
            // Alice teaches Bob
            alice - teaches - bob
        }
        
        println("Original graph has ${graph.getTriples().size} triples")
        
        // Create a reasoner
        val reasoner = RdfReasoning.reasoner(ReasonerConfig.rdfs())
        
        // Perform reasoning
        val result = reasoner.reason(graph)
        
        println("\n=== Reasoning Results ===")
        println("Reasoning time: ${result.reasoningTime.toMillis()}ms")
        println("Found ${result.inferredTriples.size} inferred triples")
        
        // Show inferred triples
        println("\nInferred triples:")
        result.inferredTriples.forEach { triple ->
            println("  $triple")
        }
        
        // Show classification results
        result.classification?.let { classification ->
            println("\n=== Classification Results ===")
            
            println("\nClass hierarchy:")
            classification.classHierarchy.forEach { (cls, superClasses) ->
                println("  $cls subClassOf ${superClasses.joinToString(", ")}")
            }
            
            println("\nInstance classifications:")
            classification.instanceClassifications.forEach { (instance, types) ->
                println("  $instance type ${types.joinToString(", ")}")
            }
            
            println("\nProperty hierarchy:")
            classification.propertyHierarchy.forEach { (prop, superProps) ->
                println("  $prop subPropertyOf ${superProps.joinToString(", ")}")
            }
        }
        
        // Check consistency
        println("\n=== Consistency Check ===")
        println("Graph is consistent: ${result.consistencyCheck.isConsistent}")
        if (!result.consistencyCheck.isConsistent) {
            println("Inconsistencies found:")
            result.consistencyCheck.inconsistencies.forEach { inconsistency ->
                println("  ${inconsistency.type}: ${inconsistency.description}")
            }
        }
        
        // Show statistics
        println("\n=== Statistics ===")
        val stats = result.statistics
        println("Total triples: ${stats.totalTriples}")
        println("Inferred triples: ${stats.inferredTriples}")
        println("Classes processed: ${stats.classesProcessed}")
        println("Properties processed: ${stats.propertiesProcessed}")
        println("Memory usage: ${stats.memoryUsage / 1024 / 1024}MB")
    }
}

fun main() {
    BasicReasoningExample().main()
}
