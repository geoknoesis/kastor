package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.ServiceConfigurationError

/** One provider that cannot be loaded must neither stop discovery nor take the registry down. */
class ProviderDiscoveryFailureTest {
    private class Fake(override val id: String) : RdfProvider {
        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository =
            throw UnsupportedOperationException()
    }

    private fun source(vararg steps: () -> RdfProvider): Iterator<RdfProvider> {
        var index = 0
        return object : Iterator<RdfProvider> {
            override fun hasNext() = index < steps.size
            override fun next(): RdfProvider = steps[index++]()
        }
    }

    @Test
    fun `errors and exceptions from a provider are recorded and later providers still register`() {
        val registry = DefaultProviderRegistry(autoDiscover = false, registerDefaultMemoryProvider = false)
        registry.discoverFrom(
            source(
                { throw ServiceConfigurationError("bad service entry") },
                { throw NoClassDefFoundError("Missing") },
                { throw IllegalStateException("constructor failed") },
                { Fake("r9-ok") },
            ),
        )
        assertEquals(3, registry.getDiscoveryErrors().size)
        assertTrue(registry.getDiscoveryErrors().any { it is ServiceConfigurationError })
        assertNotNull(registry.getProvider("r9-ok"))
    }

    @Test
    fun `a failing hasNext does not hang discovery`() {
        val registry = DefaultProviderRegistry(autoDiscover = false, registerDefaultMemoryProvider = false)
        registry.discoverFrom(object : Iterator<RdfProvider> {
            override fun hasNext(): Boolean = throw ServiceConfigurationError("always")
            override fun next(): RdfProvider = throw NoSuchElementException()
        })
        assertTrue(registry.getDiscoveryErrors().isNotEmpty())
    }
}
