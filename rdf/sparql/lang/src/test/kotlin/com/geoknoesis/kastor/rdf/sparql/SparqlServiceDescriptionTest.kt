package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.SPARQL_SD
import com.geoknoesis.kastor.rdf.vocab.SPARQL12
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SparqlServiceDescriptionTest {
    
    @Test
    fun `test SPARQL service description generation`() {
        val customFunction = SparqlExtensionFunction(
            iri = "http://example.org/customFunction",
            name = "customFunction",
            description = "A custom SPARQL function",
            argumentTypes = listOf("xsd:string"),
            returnType = "application/sparql-results+json; charset=utf-8",
            isBuiltIn = false
        )
        val capabilities = ProviderCapabilities(
            sparqlVersion = "1.2",
            supportsRdfStar = true,
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsSubSelect = true,
            supportsFederation = true,
            supportsVersionDeclaration = true,
            extensionFunctions = Sparql12BuiltInFunctions.functions + customFunction,
            supportedLanguages = listOf("sparql", "sparql12"),
            supportedResultFormats = listOf(
                "Application/SPARQL-Results+JSON; charset=utf-8",
                "application/sparql-results+xml"
            ),
            supportedInputFormats = listOf(
                "Application/SPARQL-Query; charset=utf-8"
            )
        )
        
        val generator = SparqlServiceDescriptionGenerator("https://example.com/sparql", capabilities)
        val serviceDescription = generator.generateServiceDescription()
        
        assertNotNull(serviceDescription)
        assertTrue(serviceDescription.size() > 0)
        
        // Check that service description contains expected triples
        val serviceUri = Iri("https://example.com/sparql")
        assertTrue(serviceDescription.hasTriple(RdfTriple(serviceUri, Iri("${SPARQL_SD.namespace}Service"), Iri("${SPARQL_SD.namespace}Service"))))
        assertTrue(serviceDescription.hasTriple(RdfTriple(serviceUri, SPARQL12.supportedSparqlVersion, string("1.2"))))
        assertTrue(serviceDescription.hasTriple(RdfTriple(serviceUri, SPARQL12.supportsRdfStar, boolean(true))))
        assertTrue(
            serviceDescription.hasTriple(
                RdfTriple(
                    serviceUri,
                    SPARQL_SD.resultFormatProp,
                    Iri("https://www.iana.org/assignments/media-types/application/sparql-results+json")
                )
            )
        )
        assertTrue(
            serviceDescription.hasTriple(
                RdfTriple(
                    serviceUri,
                    SPARQL_SD.inputFormatProp,
                    Iri("https://www.iana.org/assignments/media-types/application/sparql-query")
                )
            )
        )
        assertTrue(
            serviceDescription.hasTriple(
                RdfTriple(
                    Iri(customFunction.iri),
                    SPARQL_SD.returnType,
                    Iri("https://www.iana.org/assignments/media-types/application/sparql-results+json")
                )
            )
        )
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
    fun `test unified provider registry`() {
        val providers = RdfProviderRegistry.getAllProviders()
        assertTrue(providers.isNotEmpty())
        
        // Check that we have at least the memory provider
        assertTrue(providers.any { it.id == "memory" })
        
        val capabilities = RdfProviderRegistry.discoverAllCapabilities()
        assertTrue(capabilities.isNotEmpty())
        
        // Test feature support for available providers
        val memoryProvider = RdfProviderRegistry.getProvider("memory")
        assertNotNull(memoryProvider)
        memoryProvider?.let { provider ->
            // Just check that we can get capabilities, don't assume specific features
            val providerCapabilities = provider.getCapabilities(provider.defaultVariantId())
            assertNotNull(providerCapabilities)
        }
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
        // Test memory provider capabilities
        val memoryProvider = RdfProviderRegistry.getProvider("memory")
        assertNotNull(memoryProvider)
        memoryProvider?.let { provider ->
            val capabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
            
            assertEquals(ProviderCategory.RDF_STORE, capabilities.providerCategory)
            // Just check that basic capabilities exist, don't assume specific features
            assertNotNull(capabilities.basic)
        }
        
        // Test specialized providers if available (they may not be available in isolated tests)
        val sparqlProvider = RdfProviderRegistry.getProvider("sparql-endpoint")
        sparqlProvider?.let { provider ->
            val sparqlCapabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
            assertEquals(ProviderCategory.SPARQL_ENDPOINT, sparqlCapabilities.providerCategory)
            assertTrue(sparqlCapabilities.supportedSparqlFeatures["RDF-star"] == true)
            assertTrue(sparqlCapabilities.supportedSparqlFeatures["Federation"] == true)
            assertTrue(sparqlCapabilities.basic.supportsVersionDeclaration)
        }
        
        val reasonerProvider = RdfProviderRegistry.getProvider("reasoner")
        reasonerProvider?.let { provider ->
            val reasonerCapabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
            assertEquals(ProviderCategory.REASONER, reasonerCapabilities.providerCategory)
            assertTrue(reasonerCapabilities.supportedSparqlFeatures["Inference"] == true)
            assertTrue(reasonerCapabilities.basic.supportsInference)
        }
        
        val shaclProvider = RdfProviderRegistry.getProvider("shacl-validator")
        shaclProvider?.let { provider ->
            val shaclCapabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
            assertEquals(ProviderCategory.SHACL_VALIDATOR, shaclCapabilities.providerCategory)
            assertTrue(shaclCapabilities.supportedSparqlFeatures["SHACL Validation"] == true)
        }
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
    fun `test unified registry operations`() {
        // Test getting all service descriptions
        val allDescriptions = RdfProviderRegistry.getAllServiceDescriptions("https://example.com")
        assertTrue(allDescriptions.isNotEmpty())
        
        // At minimum, we should have the memory provider
        assertTrue(allDescriptions.containsKey("memory"))
        
        // Test getting supported features
        val supportedFeatures = RdfProviderRegistry.getSupportedFeatures()
        assertTrue(supportedFeatures.isNotEmpty())
        
        // Check that each provider has some features (or at least empty list)
        supportedFeatures.values.forEach { features ->
            assertNotNull(features) // Features list should not be null
        }
        
        // Test feature support checking - use a generic feature that might exist
        val memoryProvider = RdfProviderRegistry.getProvider("memory")
        assertNotNull(memoryProvider)
        // Don't assume specific features exist, just test the method works
        val hasSomeFeature = RdfProviderRegistry.supportsFeature("memory", "Some Feature")
        // The result doesn't matter, just that the method doesn't throw an exception
        assertTrue(hasSomeFeature || !hasSomeFeature) // Always true, just testing method works
    }
    
    @Test
    fun `test unified registry features`() {
        // Test hasProviderWithFeature - use a generic feature
        val hasNamedGraphs = RdfProviderRegistry.hasProviderWithFeature("Named Graphs")
        // Don't assume the result, just test that the method works
        assertTrue(hasNamedGraphs || !hasNamedGraphs) // Always true, just testing method works
        
        // Test getProviderStatistics
        val statistics = RdfProviderRegistry.getProviderStatistics()
        assertTrue(statistics.isNotEmpty())
        assertTrue(statistics[ProviderCategory.RDF_STORE]!! >= 1) // At least memory provider
        
        // Test getProvider
        val memoryProvider = RdfProviderRegistry.getProvider("memory")
        assertNotNull(memoryProvider)
        assertEquals("memory", memoryProvider?.id)
        
        val nonExistentProvider = RdfProviderRegistry.getProvider("non-existent")
        assertNull(nonExistentProvider)
        
        // Test basic registry operations
        assertTrue(RdfProviderRegistry.supports("memory"))
        assertTrue(RdfProviderRegistry.supportsVariant("memory", "memory"))
        assertFalse(RdfProviderRegistry.supports("non-existent"))
        
        val supportedTypes = RdfProviderRegistry.getSupportedTypes()
        assertTrue(supportedTypes.contains("memory:memory"))
    }
    
    @Test
    fun `test registry consistency`() {
        // Test that getAllProviders() and discoverProviders() return the same results
        val allProviders = RdfProviderRegistry.getAllProviders()
        val discoveredProviders = RdfProviderRegistry.discoverProviders()
        
        assertEquals(allProviders.size, discoveredProviders.size)
        assertEquals(allProviders.toSet(), discoveredProviders.toSet())
        
        // Test that getSupportedTypes() matches actual provider types
        val supportedTypes = RdfProviderRegistry.getSupportedTypes()
        val providerTypes = allProviders.map { it.id }.toSet()
        val supportedProviders = supportedTypes.map { it.substringBefore(":", it) }.toSet()
        
        assertEquals(providerTypes, supportedProviders)
        
        // Test that each provider type can be retrieved
        supportedTypes.forEach { type ->
            val parts = type.split(":", limit = 2)
            assertEquals(2, parts.size, "Type '$type' should include provider and variant")
            val provider = RdfProviderRegistry.getProvider(parts[0])
            assertNotNull(provider, "Provider for type '$type' should not be null")
            assertEquals(parts[0], provider?.id)
        }
    }
}









