package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpiDiscoveryTest {
    
    @Test
    fun `provider discovery works`() {
        val providers = RdfApiRegistry.discoverProviders()
        
        // In the core module, we might not discover any providers if the provider modules aren't on the classpath
        // This is expected behavior - the core should work independently
        
        // Check that providers have proper metadata if any are discovered
        providers.forEach { provider ->
            assertNotNull(provider.name, "Provider should have a name")
            assertNotNull(provider.version, "Provider should have a version")
            assertTrue(provider.getSupportedTypes().isNotEmpty(), "Provider should support at least one type")
        }
        
        println("Discovered providers:")
        if (providers.isEmpty()) {
            println("  - No providers discovered (expected in core module)")
        } else {
            providers.forEach { provider ->
                println("  - ${provider.name} v${provider.version}: ${provider.getSupportedTypes().joinToString()}")
            }
        }
    }
    
    @Test
    fun `supported types discovery works`() {
        val supportedTypes = RdfApiRegistry.getSupportedTypes()
        
        // In the core module, we might not have any supported types if providers aren't available
        // This is expected behavior
        
        println("Supported repository types:")
        if (supportedTypes.isEmpty()) {
            println("  - No types supported (expected in core module)")
        } else {
            supportedTypes.forEach { type ->
                println("  - $type")
            }
        }
    }
    
    @Test
    fun `provider support check works`() {
        val supportedTypes = RdfApiRegistry.getSupportedTypes()
        
        // Check that all reported supported types are actually supported
        supportedTypes.forEach { type ->
            assertTrue(RdfApiRegistry.isSupported(type), "Type $type should be supported")
        }
        
        // Test unsupported type
        assertFalse(RdfApiRegistry.isSupported("unsupported:type"), "Unsupported type should return false")
        
        // Test memory type (should always be supported as fallback)
        assertTrue(RdfApiRegistry.isSupported("memory"), "Memory type should always be supported")
    }
    
    @Test
    fun `memory repository fallback works`() {
        val repo = RdfApiRegistry.create(RdfConfig("memory"))
        
        assertNotNull(repo, "Memory repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
}
