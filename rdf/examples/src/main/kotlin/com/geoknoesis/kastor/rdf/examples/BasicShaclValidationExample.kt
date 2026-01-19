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
            val alice = Iri("http://example.org/alice")
            alice - RDF.type - Iri("http://example.org/Person")
            alice - Iri("http://example.org/name") - "Alice Johnson"
            alice - Iri("http://example.org/email") - "alice@example.org"
            alice - Iri("http://example.org/age") - 30
            
            // Person with missing required email
            val bob = Iri("http://example.org/bob")
            bob - RDF.type - Iri("http://example.org/Person")
            bob - Iri("http://example.org/name") - "Bob Smith"
            bob - Iri("http://example.org/age") - 25
            
            // Person with invalid age (negative)
            val charlie = Iri("http://example.org/charlie")
            charlie - RDF.type - Iri("http://example.org/Person")
            charlie - Iri("http://example.org/name") - "Charlie Brown"
            charlie - Iri("http://example.org/email") - "charlie@example.org"
            charlie - Iri("http://example.org/age") - (-5)
        }
    }
    
    private fun createPersonShapesGraph(): RdfGraph {
        return Rdf.graph {
            // Person shape
            val personShape = Iri("http://example.org/PersonShape")
            val nameProperty = Iri("http://example.org/nameProperty")
            val emailProperty = Iri("http://example.org/emailProperty")
            val ageProperty = Iri("http://example.org/ageProperty")
            
            personShape - RDF.type - Iri("http://www.w3.org/ns/shacl#NodeShape")
            personShape - Iri("http://www.w3.org/ns/shacl#targetClass") - Iri("http://example.org/Person")
            
            // Name property constraints
            personShape - Iri("http://www.w3.org/ns/shacl#property") - nameProperty
            nameProperty - RDF.type - Iri("http://www.w3.org/ns/shacl#PropertyShape")
            nameProperty - Iri("http://www.w3.org/ns/shacl#path") - Iri("http://example.org/name")
            nameProperty - Iri("http://www.w3.org/ns/shacl#minCount") - 1.toLiteral()
            nameProperty - Iri("http://www.w3.org/ns/shacl#maxCount") - 1.toLiteral()
            nameProperty - Iri("http://www.w3.org/ns/shacl#datatype") - XSD.string
            
            // Email property constraints
            personShape - Iri("http://www.w3.org/ns/shacl#property") - emailProperty
            emailProperty - RDF.type - Iri("http://www.w3.org/ns/shacl#PropertyShape")
            emailProperty - Iri("http://www.w3.org/ns/shacl#path") - Iri("http://example.org/email")
            emailProperty - Iri("http://www.w3.org/ns/shacl#minCount") - 1.toLiteral()
            emailProperty - Iri("http://www.w3.org/ns/shacl#maxCount") - 1.toLiteral()
            emailProperty - Iri("http://www.w3.org/ns/shacl#datatype") - XSD.string
            
            // Age property constraints
            personShape - Iri("http://www.w3.org/ns/shacl#property") - ageProperty
            ageProperty - RDF.type - Iri("http://www.w3.org/ns/shacl#PropertyShape")
            ageProperty - Iri("http://www.w3.org/ns/shacl#path") - Iri("http://example.org/age")
            ageProperty - Iri("http://www.w3.org/ns/shacl#minCount") - 1.toLiteral()
            ageProperty - Iri("http://www.w3.org/ns/shacl#maxCount") - 1.toLiteral()
            ageProperty - Iri("http://www.w3.org/ns/shacl#datatype") - XSD.integer
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
        
        val alice = Iri("http://example.org/alice")
        val aliceReport = validator.validateResource(dataGraph, shapesGraph, alice)
        println("Alice validation: ${if (aliceReport.isValid) "PASSED" else "FAILED"}")
        
        val bob = Iri("http://example.org/bob")
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









