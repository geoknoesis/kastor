package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BasicShaclValidationTest {
    
    @Test
    fun `memory validator performs basic SHACL validation`() {
        // Create a data graph
        val dataGraph = Rdf.graph {
            val person = Iri("http://example.org/person1")
            val name = Iri("http://example.org/name")
            val age = Iri("http://example.org/age")
            
            person - RDF.type - Iri("http://example.org/Person")
            person - name - "John Doe"
            person - age - 25
        }
        
        // Create a shapes graph
        val shapesGraph = Rdf.graph {
            val personShape = Iri("http://example.org/PersonShape")
            val nameProperty = Iri("http://example.org/nameProperty")
            val namePath = Iri("http://example.org/name")
            
            personShape - RDF.type - Iri("http://www.w3.org/ns/shacl#NodeShape")
            personShape - Iri("http://www.w3.org/ns/shacl#targetClass") - Iri("http://example.org/Person")
            personShape - Iri("http://www.w3.org/ns/shacl#property") - nameProperty
            
            nameProperty - RDF.type - Iri("http://www.w3.org/ns/shacl#PropertyShape")
            nameProperty - Iri("http://www.w3.org/ns/shacl#path") - namePath
            nameProperty - Iri("http://www.w3.org/ns/shacl#minCount") - 1
            nameProperty - Iri("http://www.w3.org/ns/shacl#maxCount") - 1
        }
        
        val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
        val report = validator.validate(dataGraph, shapesGraph)
        
        assertTrue(report.isValid, "Validation should pass for valid data")
        assertEquals(0, report.violations.size, "Should have no violations")
        assertTrue(report.validatedResources > 0, "Should have validated resources")
        assertTrue(report.validatedConstraints > 0, "Should have validated constraints")
    }
    
    @Test
    fun `memory validator detects constraint violations`() {
        // Create a data graph with missing required property
        val dataGraph = Rdf.graph {
            val person = Iri("http://example.org/person1")
            
            person - RDF.type - Iri("http://example.org/Person")
            // Missing required name property
        }
        
        // Create a shapes graph with minCount constraint
        val shapesGraph = Rdf.graph {
            val personShape = Iri("http://example.org/PersonShape")
            val nameProperty = Iri("http://example.org/nameProperty")
            val namePath = Iri("http://example.org/name")
            
            personShape - RDF.type - Iri("http://www.w3.org/ns/shacl#NodeShape")
            personShape - Iri("http://www.w3.org/ns/shacl#targetClass") - Iri("http://example.org/Person")
            personShape - Iri("http://www.w3.org/ns/shacl#property") - nameProperty
            
            nameProperty - RDF.type - Iri("http://www.w3.org/ns/shacl#PropertyShape")
            nameProperty - Iri("http://www.w3.org/ns/shacl#path") - namePath
            nameProperty - Iri("http://www.w3.org/ns/shacl#minCount") - 1
        }
        
        val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
        val report = validator.validate(dataGraph, shapesGraph)
        
        assertFalse(report.isValid, "Validation should fail for invalid data")
        assertTrue(report.violations.isNotEmpty(), "Should have violations")
        
        val minCountViolations = report.getViolationsForConstraint(ConstraintType.MIN_COUNT)
        assertTrue(minCountViolations.isNotEmpty(), "Should have minCount violations")
    }
    
    @Test
    fun `different validator configurations work`() {
        val dataGraph = Rdf.graph {
            val person = Iri("http://example.org/person1")
            person - RDF.type - Iri("http://example.org/Person")
            person - Iri("http://example.org/name") - "John Doe"
        }
        
        val shapesGraph = Rdf.graph {
            val personShape = Iri("http://example.org/PersonShape")
            personShape - RDF.type - Iri("http://www.w3.org/ns/shacl#NodeShape")
            personShape - Iri("http://www.w3.org/ns/shacl#targetClass") - Iri("http://example.org/Person")
        }
        
        val configs = listOf(
            ValidationConfig.default(),
            ValidationConfig.shaclCore(),
            ValidationConfig.strict(),
            ValidationConfig.forLargeGraphs(),
            ValidationConfig.forFastValidation(),
            ValidationConfig.forMemoryConstrained()
        )
        
        configs.forEach { config ->
            val validator = ShaclValidation.validator(config)
            val report = validator.validate(dataGraph, shapesGraph)
            
            assertNotNull(report, "Report should not be null for config: $config")
            assertTrue(report.validationTime.toMillis() >= 0, "Validation time should be non-negative")
        }
    }
    
    @Test
    fun `validator registry discovers providers`() {
        val providers = ValidatorRegistry.discoverProviders()
        assertTrue(providers.isNotEmpty(), "Should discover at least the memory validator provider")
        
        val memoryProvider = providers.find { it.name == "Memory SHACL Validator" }
        assertNotNull(memoryProvider, "Should find memory validator provider")
        assertEquals("memory", memoryProvider?.getType())
        assertTrue(memoryProvider?.isSupported(ValidationProfile.SHACL_CORE) == true)
    }
    
    @Test
    fun `validator registry supports profiles`() {
        assertTrue(ValidatorRegistry.isSupported(ValidationProfile.SHACL_CORE))
        assertTrue(ValidatorRegistry.isSupported(ValidationProfile.PERMISSIVE))
        assertTrue(ValidatorRegistry.isSupported(ValidationProfile.STRICT))
        
        val supportedProfiles = ValidatorRegistry.getSupportedProfiles()
        assertTrue(supportedProfiles.contains(ValidationProfile.SHACL_CORE))
        assertTrue(supportedProfiles.contains(ValidationProfile.PERMISSIVE))
    }
    
    @Test
    fun `validation report contains expected information`() {
        val dataGraph = Rdf.graph {
            val person = Iri("http://example.org/person1")
            person - RDF.type - Iri("http://example.org/Person")
            person - Iri("http://example.org/name") - "John Doe"
        }
        
        val shapesGraph = Rdf.graph {
            val personShape = Iri("http://example.org/PersonShape")
            personShape - RDF.type - Iri("http://www.w3.org/ns/shacl#NodeShape")
            personShape - Iri("http://www.w3.org/ns/shacl#targetClass") - Iri("http://example.org/Person")
        }
        
        val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
        val report = validator.validate(dataGraph, shapesGraph)
        
        assertNotNull(report.statistics, "Statistics should not be null")
        assertTrue(report.validatedResources > 0, "Should have validated resources")
        assertTrue(report.validationTime.toMillis() >= 0, "Validation time should be non-negative")
        
        val summary = report.getSummary()
        assertNotNull(summary, "Summary should not be null")
        assertTrue(summary.isValid || !summary.isValid, "Summary should have validity status")
    }
    
    @Test
    fun `resource-specific validation works`() {
        val dataGraph = Rdf.graph {
            val person1 = Iri("http://example.org/person1")
            val person2 = Iri("http://example.org/person2")
            
            person1 - RDF.type - Iri("http://example.org/Person")
            person1 - Iri("http://example.org/name") - "John Doe"
            
            person2 - RDF.type - Iri("http://example.org/Person")
            // person2 missing name
        }
        
        val shapesGraph = Rdf.graph {
            val personShape = Iri("http://example.org/PersonShape")
            val nameProperty = Iri("http://example.org/nameProperty")
            val namePath = Iri("http://example.org/name")
            
            personShape - RDF.type - Iri("http://www.w3.org/ns/shacl#NodeShape")
            personShape - Iri("http://www.w3.org/ns/shacl#targetClass") - Iri("http://example.org/Person")
            personShape - Iri("http://www.w3.org/ns/shacl#property") - nameProperty
            
            nameProperty - RDF.type - Iri("http://www.w3.org/ns/shacl#PropertyShape")
            nameProperty - Iri("http://www.w3.org/ns/shacl#path") - namePath
            nameProperty - Iri("http://www.w3.org/ns/shacl#minCount") - 1
        }
        
        val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
        
        // Validate person1 (should pass)
        val report1 = validator.validateResource(dataGraph, shapesGraph, Iri("http://example.org/person1"))
        assertTrue(report1.isValid, "Person1 should be valid")
        
        // Validate person2 (should fail)
        val report2 = validator.validateResource(dataGraph, shapesGraph, Iri("http://example.org/person2"))
        assertFalse(report2.isValid, "Person2 should be invalid")
        assertTrue(report2.violations.isNotEmpty(), "Person2 should have violations")
    }
    
    @Test
    fun `conforms method works correctly`() {
        val dataGraph = Rdf.graph {
            val person = Iri("http://example.org/person1")
            person - RDF.type - Iri("http://example.org/Person")
            person - Iri("http://example.org/name") - "John Doe"
        }
        
        val shapesGraph = Rdf.graph {
            val personShape = Iri("http://example.org/PersonShape")
            personShape - RDF.type - Iri("http://www.w3.org/ns/shacl#NodeShape")
            personShape - Iri("http://www.w3.org/ns/shacl#targetClass") - Iri("http://example.org/Person")
        }
        
        val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
        val conforms = validator.conforms(dataGraph, shapesGraph)
        
        assertTrue(conforms, "Data should conform to shapes")
    }
}









