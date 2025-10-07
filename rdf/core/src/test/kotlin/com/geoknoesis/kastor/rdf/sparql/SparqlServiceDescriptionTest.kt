package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.SPARQL_SD
import com.geoknoesis.kastor.rdf.vocab.SPARQL12
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SparqlServiceDescriptionTest {
    
    @Test
    fun `test SPARQL service description generation`() {
        val capabilities = ProviderCapabilities(
            sparqlVersion = "1.2",
            supportsRdfStar = true,
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsSubSelect = true,
            supportsFederation = true,
            supportsVersionDeclaration = true,
            extensionFunctions = Sparql12BuiltInFunctions.functions,
            supportedLanguages = listOf("sparql", "sparql12"),
            supportedResultFormats = listOf(
                "application/sparql-results+json",
                "application/sparql-results+xml"
            )
        )
        
        val generator = SparqlServiceDescriptionGenerator("https://example.com/sparql", capabilities)
        val serviceDescription = generator.generateServiceDescription()
        
        assertNotNull(serviceDescription)
        assertTrue(serviceDescription.size() > 0)
        
        // Check that service description contains expected triples
        val serviceUri = iri("https://example.com/sparql")
        assertTrue(serviceDescription.hasTriple(RdfTriple(serviceUri, iri("${SPARQL_SD.namespace}Service"), iri("${SPARQL_SD.namespace}Service"))))
        assertTrue(serviceDescription.hasTriple(RdfTriple(serviceUri, SPARQL12.supportedSparqlVersion, string("1.2"))))
        assertTrue(serviceDescription.hasTriple(RdfTriple(serviceUri, SPARQL12.supportsRdfStar, boolean(true))))
    }
    
    @Test
    fun `test extension function registry`() {
        val customFunction = SparqlExtensionFunction(
            iri = "http://example.org/customFunction",
            name = "customFunction",
            description = "A custom SPARQL function",
            argumentTypes = listOf("xsd:string"),
            returnType = "xsd:integer",
            isBuiltIn = false
        )
        
        SparqlExtensionFunctionRegistry.register(customFunction)
        
        assertTrue(SparqlExtensionFunctionRegistry.isRegistered(customFunction.iri))
        assertEquals(customFunction, SparqlExtensionFunctionRegistry.getFunction(customFunction.iri))
        
        val functions = SparqlExtensionFunctionRegistry.getFunctionsByName("customFunction")
        assertEquals(1, functions.size)
        assertEquals(customFunction, functions.first())
    }
    
    @Test
    fun `test enhanced provider registry`() {
        val providers = EnhancedRdfApiRegistry.getAllProviders()
        assertTrue(providers.isNotEmpty())
        
        val sparqlProviders = EnhancedRdfApiRegistry.getProvidersByCategory(ProviderCategory.SPARQL_ENDPOINT)
        assertEquals(1, sparqlProviders.size)
        
        val capabilities = EnhancedRdfApiRegistry.discoverAllCapabilities()
        assertTrue(capabilities.isNotEmpty())
        
        assertTrue(EnhancedRdfApiRegistry.supportsFeature("sparql-endpoint", "RDF-star"))
        assertTrue(EnhancedRdfApiRegistry.supportsFeature("reasoner", "Inference"))
        assertTrue(EnhancedRdfApiRegistry.supportsFeature("shacl-validator", "SHACL Validation"))
    }
    
    @Test
    fun `test service description as turtle`() {
        val capabilities = ProviderCapabilities(
            sparqlVersion = "1.2",
            supportsRdfStar = true,
            supportsPropertyPaths = true
        )
        
        val generator = SparqlServiceDescriptionGenerator("https://example.com/sparql", capabilities)
        val turtle = generator.generateAsTurtle()
        
        assertNotNull(turtle)
        assertTrue(turtle.contains("@prefix sd:"))
        assertTrue(turtle.contains("@prefix sparql:"))
        assertTrue(turtle.contains("supportsRdfStar") && turtle.contains("true"))
    }
    
    @Test
    fun `test built-in functions registration`() {
        // Initialize built-in functions
        Sparql12BuiltInFunctions
        
        val builtInFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()
        assertTrue(builtInFunctions.isNotEmpty())
        
        // Check that key SPARQL 1.2 functions are registered
        val tripleFunction = builtInFunctions.find { it.name == "TRIPLE" }
        assertNotNull(tripleFunction)
        assertEquals("rdf:TripleTerm", tripleFunction?.returnType)
        
        val isTripleFunction = builtInFunctions.find { it.name == "isTRIPLE" }
        assertNotNull(isTripleFunction)
        assertEquals("xsd:boolean", isTripleFunction?.returnType)
        
        val replaceAllFunction = builtInFunctions.find { it.name == "replaceAll" }
        assertNotNull(replaceAllFunction)
        assertTrue(replaceAllFunction?.argumentTypes?.contains("xsd:string") == true)
    }
    
    @Test
    fun `test provider capabilities`() {
        val sparqlProvider = SparqlEndpointProvider()
        val capabilities = sparqlProvider.getDetailedCapabilities()
        
        assertEquals(ProviderCategory.SPARQL_ENDPOINT, capabilities.providerCategory)
        assertTrue(capabilities.supportedSparqlFeatures["RDF-star"] == true)
        assertTrue(capabilities.supportedSparqlFeatures["Federation"] == true)
        assertTrue(capabilities.basic.supportsVersionDeclaration)
        
        val reasonerProvider = ReasonerProvider()
        val reasonerCapabilities = reasonerProvider.getDetailedCapabilities()
        
        assertEquals(ProviderCategory.REASONER, reasonerCapabilities.providerCategory)
        assertTrue(reasonerCapabilities.supportedSparqlFeatures["Inference"] == true)
        assertTrue(reasonerCapabilities.basic.supportsInference)
        
        val shaclProvider = ShaclValidatorProvider()
        val shaclCapabilities = shaclProvider.getDetailedCapabilities()
        
        assertEquals(ProviderCategory.SHACL_VALIDATOR, shaclCapabilities.providerCategory)
        assertTrue(shaclCapabilities.supportedSparqlFeatures["SHACL Validation"] == true)
    }
    
    @Test
    fun `test service description formats`() {
        val capabilities = ProviderCapabilities(
            sparqlVersion = "1.2",
            supportsRdfStar = true,
            supportsPropertyPaths = true,
            supportsAggregation = true,
            extensionFunctions = Sparql12BuiltInFunctions.functions.take(3) // Test with subset
        )
        
        val generator = SparqlServiceDescriptionGenerator("https://example.com/sparql", capabilities)
        
        // Test Turtle format
        val turtle = generator.generateAsTurtle()
        assertNotNull(turtle)
        assertTrue(turtle.contains("https://example.com/sparql"))
        
        // Test JSON-LD format
        val jsonLd = generator.generateAsJsonLd()
        assertNotNull(jsonLd)
        assertTrue(jsonLd.contains("https://example.com/sparql"))
        
        // Test SPARQL query format
        val sparql = generator.generateAsSparqlResult()
        assertNotNull(sparql)
        assertTrue(sparql.contains("SELECT ?subject ?predicate ?object"))
    }
    
    @Test
    fun `test provider registry operations`() {
        // Test getting all service descriptions
        val allDescriptions = EnhancedRdfApiRegistry.getAllServiceDescriptions("https://example.com")
        assertTrue(allDescriptions.isNotEmpty())
        assertTrue(allDescriptions.containsKey("sparql-endpoint"))
        assertTrue(allDescriptions.containsKey("reasoner"))
        assertTrue(allDescriptions.containsKey("shacl-validator"))
        
        // Test getting supported features
        val supportedFeatures = EnhancedRdfApiRegistry.getSupportedFeatures()
        assertTrue(supportedFeatures.isNotEmpty())
        
        // Check that each provider has some features
        supportedFeatures.values.forEach { features ->
            assertTrue(features.isNotEmpty())
        }
        
        // Test feature support checking
        assertTrue(EnhancedRdfApiRegistry.supportsFeature("sparql-endpoint", "RDF-star"))
        assertFalse(EnhancedRdfApiRegistry.supportsFeature("sparql-endpoint", "NonExistentFeature"))
    }
    
    @Test
    fun `test new registry features`() {
        // Test hasProviderWithFeature
        assertTrue(EnhancedRdfApiRegistry.hasProviderWithFeature("RDF-star"))
        assertFalse(EnhancedRdfApiRegistry.hasProviderWithFeature("NonExistentFeature"))
        
        // Test getProviderStatistics
        val statistics = EnhancedRdfApiRegistry.getProviderStatistics()
        assertTrue(statistics.isNotEmpty())
        assertEquals(1, statistics[ProviderCategory.SPARQL_ENDPOINT])
        assertEquals(1, statistics[ProviderCategory.REASONER])
        assertEquals(1, statistics[ProviderCategory.SHACL_VALIDATOR])
        
        // Test getProvider
        val sparqlProvider = EnhancedRdfApiRegistry.getProvider("sparql-endpoint")
        assertNotNull(sparqlProvider)
        assertEquals("sparql-endpoint", sparqlProvider?.getType())
        
        val nonExistentProvider = EnhancedRdfApiRegistry.getProvider("non-existent")
        assertNull(nonExistentProvider)
    }
}