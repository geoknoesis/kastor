package com.geoknoesis.kastor.rdf.reasoning

import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasonerProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Provider discovery survives a broken provider and never replaces a provider registered by the application. */
class ReasonerRegistryDiscoveryTest {
    private fun provider(type: String): RdfReasonerProvider =
        object : RdfReasonerProvider by MemoryReasonerProvider() {
            override fun getType() = type
        }

    @AfterEach
    fun cleanUp() {
        ReasonerRegistry.unregister("r9-user")
        ReasonerRegistry.unregister("r9-later")
    }

    @Test
    fun `discovery does not overwrite a provider registered for the same type`() {
        val mine = provider("r9-user")
        ReasonerRegistry.register(mine)
        ReasonerRegistry.discoverFrom(listOf(provider("r9-user")).iterator())
        assertSame(mine, ReasonerRegistry.getProviders().single { it.getType() == "r9-user" })
    }

    @Test
    fun `a provider that fails to load is recorded and the rest still register`() {
        val later = provider("r9-later")
        var calls = 0
        val source = object : Iterator<RdfReasonerProvider> {
            override fun hasNext() = calls < 3
            override fun next(): RdfReasonerProvider = when (calls++) {
                0 -> throw java.util.ServiceConfigurationError("broken service file")
                1 -> throw NoClassDefFoundError("missing")
                else -> later
            }
        }
        ReasonerRegistry.discoverFrom(source)
        assertEquals(2, ReasonerRegistry.getDiscoveryErrors().size)
        assertTrue(ReasonerRegistry.getProviders().any { it === later })
    }

    @Test
    fun `repeated discovery replaces the recorded failures instead of accumulating them`() {
        fun broken() = object : Iterator<RdfReasonerProvider> {
            var calls = 0
            override fun hasNext() = calls < 1
            override fun next(): RdfReasonerProvider {
                calls++
                throw java.util.ServiceConfigurationError("broken service file")
            }
        }
        repeat(5) { ReasonerRegistry.discoverFrom(broken()) }
        assertEquals(1, ReasonerRegistry.getDiscoveryErrors().size)
        ReasonerRegistry.discoverFrom(emptyList<RdfReasonerProvider>().iterator())
        assertTrue(ReasonerRegistry.getDiscoveryErrors().isEmpty(), "a clean run clears the failures of the last one")
    }
}
