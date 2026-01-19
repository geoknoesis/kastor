package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Example demonstrating how to work with SHACL validator providers.
 */
class ShaclValidatorProviderExample {
    
    fun run() {
        println("=== SHACL Validator Provider Example ===\n")
        
        // Discover and examine validator providers
        examineValidatorProviders()
        
        // Demonstrate different validation profiles
        demonstrateValidationProfiles()
        
        // Show provider selection and capabilities
        demonstrateProviderSelection()
        
        // Create custom validation configurations
        demonstrateCustomConfigurations()
        
        // Show validation with different backends
        demonstrateBackendValidation()
    }
    
    private fun examineValidatorProviders() {
        println("=== Examining Validator Providers ===")
        
        val providers = ShaclValidation.validatorProviders()
        println("Discovered ${providers.size} validator providers:")
        
        providers.forEach { provider ->
            println("\n${provider.name}:")
            println("  Type: ${provider.getType()}")
            println("  Version: ${provider.version}")
            
            val capabilities = provider.getCapabilities()
            println("  Capabilities:")
            println("    SHACL Core: ${capabilities.supportsShaclCore}")
            println("    SHACL SPARQL: ${capabilities.supportsShaclSparql}")
            println("    SHACL JS: ${capabilities.supportsShaclJs}")
            println("    SHACL Python: ${capabilities.supportsShaclPy}")
            println("    SHACL DASH: ${capabilities.supportsShaclDash}")
            println("    Custom constraints: ${capabilities.supportsCustomConstraints}")
            println("    Parallel validation: ${capabilities.supportsParallelValidation}")
            println("    Streaming validation: ${capabilities.supportsStreamingValidation}")
            println("    Incremental validation: ${capabilities.supportsIncrementalValidation}")
            println("    Max graph size: ${capabilities.maxGraphSize}")
            println("    Performance profile: ${capabilities.performanceProfile}")
            
            val supportedProfiles = provider.getSupportedProfiles()
            println("  Supported profiles: ${supportedProfiles.joinToString(", ")}")
        }
    }
    
    private fun demonstrateValidationProfiles() {
        println("\n=== Validation Profiles ===")
        
        val profiles = ValidationProfile.values()
        println("Available validation profiles:")
        
        profiles.forEach { profile ->
            val isSupported = ShaclValidation.isSupported(profile)
            val bestProvider = ShaclValidation.getBestProvider(profile)
            
            println("  $profile: ${if (isSupported) "Supported" else "Not supported"}")
            if (isSupported && bestProvider != null) {
                println("    Best provider: ${bestProvider.name}")
            }
        }
        
        // Test each supported profile
        val supportedProfiles = ShaclValidation.supportedProfiles()
        println("\nTesting supported profiles:")
        
        val testData = createTestDataGraph()
        val testShapes = createTestShapesGraph()
        
        supportedProfiles.forEach { profile ->
            try {
                val validator = ShaclValidation.validator(profile)
                val report = validator.validate(testData, testShapes)
                
                println("  $profile: ${if (report.isValid) "PASSED" else "FAILED"} " +
                        "(${report.validationTime.toMillis()}ms, ${report.violations.size} violations)")
            } catch (e: Exception) {
                println("  $profile: ERROR - ${e.message}")
            }
        }
    }
    
    private fun demonstrateProviderSelection() {
        println("\n=== Provider Selection ===")
        
        val registryStats = ShaclValidation.getRegistryStatistics()
        println("Registry statistics:")
        println("  Total providers: ${registryStats.totalProviders}")
        println("  Supported profiles: ${registryStats.supportedProfiles.joinToString(", ")}")
        println("  Unsupported profiles: ${registryStats.unsupportedProfiles.joinToString(", ")}")
        
        println("\nProviders by profile:")
        registryStats.providersByProfile.forEach { (profile, providerNames) ->
            println("  $profile: ${providerNames.joinToString(", ")}")
        }
        
        // Demonstrate automatic provider selection
        val testConfigs = listOf(
            ValidationConfig.shaclCore(),
            ValidationConfig.shaclSparql(),
            ValidationConfig.strict(),
            ValidationConfig.forLargeGraphs()
        )
        
        println("\nAutomatic provider selection:")
        testConfigs.forEach { config ->
            try {
                val validator = ShaclValidation.validator(config)
                println("  ${config.profile}: ${validator::class.simpleName}")
            } catch (e: Exception) {
                println("  ${config.profile}: No provider available - ${e.message}")
            }
        }
    }
    
    private fun demonstrateCustomConfigurations() {
        println("\n=== Custom Validation Configurations ===")
        
        val testData = createTestDataGraph()
        val testShapes = createTestShapesGraph()
        
        // Custom configuration for strict validation
        val strictConfig = ValidationConfig(
            profile = ValidationProfile.SHACL_CORE,
            strictMode = true,
            includeWarnings = true,
            maxViolations = 100,
            timeout = java.time.Duration.ofMinutes(2),
            parallelValidation = true,
            streamingMode = false,
            batchSize = 500,
            enableExplanations = true,
            enableSuggestions = true,
            validateClosedShapes = true,
            validateInactiveShapes = false,
            customParameters = mapOf(
                "debug" to true,
                "verbose" to false,
                "optimize" to true
            )
        )
        
        println("Testing custom strict configuration:")
        val strictValidator = ShaclValidation.validator(strictConfig)
        val strictReport = strictValidator.validate(testData, testShapes)
        println("  Result: ${if (strictReport.isValid) "PASSED" else "FAILED"}")
        println("  Violations: ${strictReport.violations.size}")
        println("  Validation time: ${strictReport.validationTime.toMillis()}ms")
        
        // Custom configuration for fast validation
        val fastConfig = ValidationConfig(
            profile = ValidationProfile.SHACL_CORE,
            strictMode = false,
            includeWarnings = false,
            maxViolations = 50,
            timeout = java.time.Duration.ofSeconds(30),
            parallelValidation = false,
            streamingMode = true,
            batchSize = 1000,
            enableExplanations = false,
            enableSuggestions = false
        )
        
        println("\nTesting custom fast configuration:")
        val fastValidator = ShaclValidation.validator(fastConfig)
        val fastReport = fastValidator.validate(testData, testShapes)
        println("  Result: ${if (fastReport.isValid) "PASSED" else "FAILED"}")
        println("  Violations: ${fastReport.violations.size}")
        println("  Validation time: ${fastReport.validationTime.toMillis()}ms")
        
        // Custom configuration for memory-constrained environment
        val memoryConfig = ValidationConfig(
            profile = ValidationProfile.SHACL_CORE,
            strictMode = false,
            includeWarnings = true,
            maxViolations = 25,
            timeout = java.time.Duration.ofMinutes(1),
            parallelValidation = false,
            streamingMode = true,
            batchSize = 100,
            enableExplanations = true,
            enableSuggestions = false
        )
        
        println("\nTesting custom memory-constrained configuration:")
        val memoryValidator = ShaclValidation.validator(memoryConfig)
        val memoryReport = memoryValidator.validate(testData, testShapes)
        println("  Result: ${if (memoryReport.isValid) "PASSED" else "FAILED"}")
        println("  Violations: ${memoryReport.violations.size}")
        println("  Validation time: ${memoryReport.validationTime.toMillis()}ms")
    }
    
    private fun demonstrateBackendValidation() {
        println("\n=== Backend Validation ===")
        
        val testData = createTestDataGraph()
        val testShapes = createTestShapesGraph()
        
        // Try to validate with different backends if available
        val providers = ShaclValidation.validatorProviders()
        
        providers.forEach { provider ->
            println("\nTesting with ${provider.name}:")
            
            try {
                val validator = provider.createValidator(ValidationConfig.shaclCore())
                val report = validator.validate(testData, testShapes)
                
                println("  Result: ${if (report.isValid) "PASSED" else "FAILED"}")
                println("  Violations: ${report.violations.size}")
                println("  Warnings: ${report.warnings.size}")
                println("  Validation time: ${report.validationTime.toMillis()}ms")
                println("  Validated resources: ${report.validatedResources}")
                println("  Validated constraints: ${report.validatedConstraints}")
                
                // Show detailed statistics
                val stats = report.statistics
                println("  Statistics:")
                println("    Total resources: ${stats.totalResources}")
                println("    Constraints by type: ${stats.constraintsByType}")
                println("    Violations by type: ${stats.violationsByType}")
                
            } catch (e: Exception) {
                println("  ERROR: ${e.message}")
            }
        }
    }
    
    private fun createTestDataGraph(): RdfGraph {
        return Rdf.graph {
            val person = Iri("http://example.org/person1")
            person - RDF.type - Iri("http://example.org/Person")
            person - Iri("http://example.org/name") - "John Doe"
            person - Iri("http://example.org/email") - "john@example.org"
            person - Iri("http://example.org/age") - 30
        }
    }
    
    private fun createTestShapesGraph(): RdfGraph {
        return Rdf.graph {
            val personShape = Iri("http://example.org/PersonShape")
            val nameProperty = Iri("http://example.org/nameProperty")
            
            personShape - RDF.type - Iri("http://www.w3.org/ns/shacl#NodeShape")
            personShape - Iri("http://www.w3.org/ns/shacl#targetClass") - Iri("http://example.org/Person")
            personShape - Iri("http://www.w3.org/ns/shacl#property") - nameProperty
            
            nameProperty - RDF.type - Iri("http://www.w3.org/ns/shacl#PropertyShape")
            nameProperty - Iri("http://www.w3.org/ns/shacl#path") - Iri("http://example.org/name")
            nameProperty - Iri("http://www.w3.org/ns/shacl#minCount") - 1.toLiteral()
            nameProperty - Iri("http://www.w3.org/ns/shacl#maxCount") - 1.toLiteral()
            nameProperty - Iri("http://www.w3.org/ns/shacl#datatype") - XSD.string
        }
    }
}

fun main() {
    val example = ShaclValidatorProviderExample()
    example.run()
}









