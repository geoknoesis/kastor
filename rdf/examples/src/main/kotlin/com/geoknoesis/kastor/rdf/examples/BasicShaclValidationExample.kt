package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Basic SHACL validation example demonstrating how to use the SHACL validation framework.
 */
class BasicShaclValidationExample {
    
    fun run() {
        println("=== Basic SHACL Validation Example ===\n")
        
        // Create a data graph with person information
        val dataGraph = createPersonDataGraph()
        println("Created data graph with ${dataGraph.getTriples().size} triples")
        
        // Create a shapes graph with SHACL constraints
        val shapesGraph = createPersonShapesGraph()
        println("Created shapes graph with ${shapesGraph.getTriples().size} triples\n")
        
        // Validate the data against the shapes
        val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
        val report = validator.validate(dataGraph, shapesGraph)
        
        // Display validation results
        displayValidationResults(report)
        
        // Demonstrate different validation scenarios
        demonstrateValidationScenarios()
        
        // Show validator capabilities
        showValidatorCapabilities()
    }
    
    private fun createPersonDataGraph(): RdfGraph {
        return Rdf.graph {
            // Valid person with all required properties
            val alice = iri("http://example.org/alice")
            alice - RDF.type - iri("http://example.org/Person")
            alice - iri("http://example.org/name") - "Alice Johnson"
            alice - iri("http://example.org/email") - "alice@example.org"
            alice - iri("http://example.org/age") - 30
            
            // Person with missing required email
            val bob = iri("http://example.org/bob")
            bob - RDF.type - iri("http://example.org/Person")
            bob - iri("http://example.org/name") - "Bob Smith"
            bob - iri("http://example.org/age") - 25
            
            // Person with invalid age (negative)
            val charlie = iri("http://example.org/charlie")
            charlie - RDF.type - iri("http://example.org/Person")
            charlie - iri("http://example.org/name") - "Charlie Brown"
            charlie - iri("http://example.org/email") - "charlie@example.org"
            charlie - iri("http://example.org/age") - (-5)
        }
    }
    
    private fun createPersonShapesGraph(): RdfGraph {
        return Rdf.graph {
            // Person shape
            val personShape = iri("http://example.org/PersonShape")
            val nameProperty = iri("http://example.org/nameProperty")
            val emailProperty = iri("http://example.org/emailProperty")
            val ageProperty = iri("http://example.org/ageProperty")
            
            personShape - RDF.type - iri("http://www.w3.org/ns/shacl#NodeShape")
            personShape - iri("http://www.w3.org/ns/shacl#targetClass") - iri("http://example.org/Person")
            
            // Name property constraints
            personShape - iri("http://www.w3.org/ns/shacl#property") - nameProperty
            nameProperty - RDF.type - iri("http://www.w3.org/ns/shacl#PropertyShape")
            nameProperty - iri("http://www.w3.org/ns/shacl#path") - iri("http://example.org/name")
            nameProperty - iri("http://www.w3.org/ns/shacl#minCount") - literal(1)
            nameProperty - iri("http://www.w3.org/ns/shacl#maxCount") - literal(1)
            nameProperty - iri("http://www.w3.org/ns/shacl#datatype") - XSD.string
            
            // Email property constraints
            personShape - iri("http://www.w3.org/ns/shacl#property") - emailProperty
            emailProperty - RDF.type - iri("http://www.w3.org/ns/shacl#PropertyShape")
            emailProperty - iri("http://www.w3.org/ns/shacl#path") - iri("http://example.org/email")
            emailProperty - iri("http://www.w3.org/ns/shacl#minCount") - literal(1)
            emailProperty - iri("http://www.w3.org/ns/shacl#maxCount") - literal(1)
            emailProperty - iri("http://www.w3.org/ns/shacl#datatype") - XSD.string
            
            // Age property constraints
            personShape - iri("http://www.w3.org/ns/shacl#property") - ageProperty
            ageProperty - RDF.type - iri("http://www.w3.org/ns/shacl#PropertyShape")
            ageProperty - iri("http://www.w3.org/ns/shacl#path") - iri("http://example.org/age")
            ageProperty - iri("http://www.w3.org/ns/shacl#minCount") - literal(1)
            ageProperty - iri("http://www.w3.org/ns/shacl#maxCount") - literal(1)
            ageProperty - iri("http://www.w3.org/ns/shacl#datatype") - XSD.integer
        }
    }
    
    private fun displayValidationResults(report: ValidationReport) {
        println("=== Validation Results ===")
        println("Valid: ${report.isValid}")
        println("Violations: ${report.violations.size}")
        println("Warnings: ${report.warnings.size}")
        println("Validation time: ${report.validationTime.toMillis()}ms")
        println("Validated resources: ${report.validatedResources}")
        println("Validated constraints: ${report.validatedConstraints}")
        
        if (report.violations.isNotEmpty()) {
            println("\n=== Violations ===")
            report.violations.forEach { violation ->
                println("${violation.severity}: ${violation.message}")
                println("  Resource: ${violation.resource}")
                println("  Constraint: ${violation.constraint.constraintType}")
                violation.explanation?.let { println("  Explanation: $it") }
                violation.suggestedFix?.let { println("  Suggested fix: $it") }
                println()
            }
        }
        
        val summary = report.getSummary()
        println("=== Summary ===")
        println(summary.getDescription())
    }
    
    private fun demonstrateValidationScenarios() {
        println("\n=== Validation Scenarios ===")
        
        // Scenario 1: Resource-specific validation
        val dataGraph = createPersonDataGraph()
        val shapesGraph = createPersonShapesGraph()
        val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
        
        val alice = iri("http://example.org/alice")
        val aliceReport = validator.validateResource(dataGraph, shapesGraph, alice)
        println("Alice validation: ${if (aliceReport.isValid) "PASSED" else "FAILED"}")
        
        val bob = iri("http://example.org/bob")
        val bobReport = validator.validateResource(dataGraph, shapesGraph, bob)
        println("Bob validation: ${if (bobReport.isValid) "PASSED" else "FAILED"}")
        if (!bobReport.isValid) {
            println("  Bob's violations: ${bobReport.violations.size}")
        }
        
        // Scenario 2: Conformance check
        val conforms = validator.conforms(dataGraph, shapesGraph)
        println("Overall conformance: ${if (conforms) "PASSED" else "FAILED"}")
        
        // Scenario 3: Different validator configurations
        println("\nDifferent validator configurations:")
        val configs = listOf(
            "Default" to ValidationConfig.default(),
            "Strict" to ValidationConfig.strict(),
            "Fast" to ValidationConfig.forFastValidation(),
            "Large graphs" to ValidationConfig.forLargeGraphs()
        )
        
        configs.forEach { (name, config) ->
            val configValidator = ShaclValidation.validator(config)
            val configReport = configValidator.validate(dataGraph, shapesGraph)
            println("  $name: ${if (configReport.isValid) "PASSED" else "FAILED"} (${configReport.validationTime.toMillis()}ms)")
        }
    }
    
    private fun showValidatorCapabilities() {
        println("\n=== Validator Capabilities ===")
        
        val providers = ShaclValidation.validatorProviders()
        println("Available validator providers: ${providers.size}")
        
        providers.forEach { provider ->
            println("\n${provider.name} (${provider.version}):")
            println("  Type: ${provider.getType()}")
            
            val capabilities = provider.getCapabilities()
            println("  Capabilities:")
            println("    SHACL Core: ${capabilities.supportsShaclCore}")
            println("    SHACL SPARQL: ${capabilities.supportsShaclSparql}")
            println("    SHACL JS: ${capabilities.supportsShaclJs}")
            println("    Parallel validation: ${capabilities.supportsParallelValidation}")
            println("    Streaming validation: ${capabilities.supportsStreamingValidation}")
            println("    Performance profile: ${capabilities.performanceProfile}")
            println("    Max graph size: ${capabilities.maxGraphSize}")
            
            val supportedProfiles = provider.getSupportedProfiles()
            println("  Supported profiles: ${supportedProfiles.joinToString(", ")}")
        }
        
        val supportedProfiles = ShaclValidation.supportedProfiles()
        println("\nOverall supported profiles: ${supportedProfiles.joinToString(", ")}")
        
        val registryStats = ShaclValidation.getRegistryStatistics()
        println("\nRegistry statistics:")
        println("  Total providers: ${registryStats.totalProviders}")
        println("  Supported profiles: ${registryStats.supportedProfiles.size}")
        println("  Unsupported profiles: ${registryStats.unsupportedProfiles.size}")
    }
}

fun main() {
    val example = BasicShaclValidationExample()
    example.run()
}
