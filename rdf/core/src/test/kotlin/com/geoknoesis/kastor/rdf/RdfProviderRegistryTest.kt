package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdfProviderRegistryTest {

    @Test
    fun `memory provider is discoverable with typed helpers`() {
        val provider = RdfProviderRegistry.getProvider("memory")
        assertNotNull(provider)
        assertEquals("memory", provider?.id)

        val typed = RdfProviderRegistry.getProvider(ProviderId("memory"))
        assertNotNull(typed)
        assertEquals("memory", typed?.id)
    }

    @Test
    fun `supportsVariant works for typed and string ids`() {
        assertTrue(RdfProviderRegistry.supportsVariant("memory", "memory"))
        assertTrue(RdfProviderRegistry.supportsVariant(ProviderId("memory"), VariantId("memory")))
    }

    @Test
    fun `getSupportedTypes includes memory variant`() {
        val supported = RdfProviderRegistry.getSupportedTypes()
        assertTrue(supported.contains("memory:memory"))
    }

    @Test
    fun `create uses typed config`() {
        val repo = RdfProviderRegistry.create(
            RdfConfig.of(ProviderId("memory"), VariantId("memory"))
        )
        assertNotNull(repo)
        repo.close()
    }

    @Test
    fun `providers by category include memory`() {
        val providers = RdfProviderRegistry.getProvidersByCategory(ProviderCategory.RDF_STORE)
        assertTrue(providers.any { it.id == "memory" })
    }

    @Test
    fun `discoverAllCapabilities returns memory entry`() {
        val capabilities = RdfProviderRegistry.discoverAllCapabilities()
        val memory = capabilities["memory"]
        assertNotNull(memory)
        assertEquals(ProviderCategory.RDF_STORE, memory?.providerCategory)
        assertTrue(memory?.basic?.supportsNamedGraphs == true)
    }

    @Test
    fun `getAllServiceDescriptions includes memory graph`() {
        val descriptions = RdfProviderRegistry.getAllServiceDescriptions("http://example.org")
        val memory = descriptions["memory"]
        assertNotNull(memory)
        assertEquals(0, memory?.size())
    }

    @Test
    fun `repository can use custom registry`() {
        val registry = DefaultProviderRegistry(
            autoDiscover = false,
            registerDefaultMemoryProvider = false
        )
        registry.register(CustomProvider())

        val repo = Rdf.repository(registry) {
            providerId = "custom"
            variantId = "memory"
        }

        assertNotNull(repo)
        repo.close()
    }

    @Test
    fun `registry delegate can be swapped and reset`() {
        val registry = DefaultProviderRegistry(
            autoDiscover = false,
            registerDefaultMemoryProvider = false
        )
        registry.register(CustomProvider())

        try {
            RdfProviderRegistry.setDelegate(registry)
            assertNotNull(RdfProviderRegistry.getProvider("custom"))
        } finally {
            RdfProviderRegistry.resetDelegate()
        }

        assertNull(RdfProviderRegistry.getProvider("custom"))
    }

    private class CustomProvider : RdfProvider {
        override val id: String = "custom"

        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
            return MemoryRepository(config)
        }

        override fun variants(): List<RdfVariant> = listOf(RdfVariant("memory"))
    }
}

