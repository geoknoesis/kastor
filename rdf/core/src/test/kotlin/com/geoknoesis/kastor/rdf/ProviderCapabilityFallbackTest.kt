package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A request for a variant or for inference that the default provider lacks goes to another registered provider that
 * has it, and fails with an [RdfProviderException] naming what is missing when none has.
 */
class ProviderCapabilityFallbackTest {
    /** A provider whose variants in [inferring] support inference; records what it was asked to create. */
    private class Recording(
        override val id: String,
        private val variantIds: List<String>,
        private val inferring: Set<String> = emptySet(),
        override val priority: Int = 0,
    ) : RdfProvider {
        val created = ArrayList<String>()
        override fun variants(): List<RdfVariant> = variantIds.map { RdfVariant(it) }
        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
            created.add(variantId)
            return MemoryRepository(config)
        }
        override fun getCapabilities(variantId: String?): ProviderCapabilities =
            ProviderCapabilities(supportsInference = variantId in inferring)
    }

    /** A registry with the bundled graph-only `memory` provider (the default) and [providers]. */
    private fun registry(vararg providers: RdfProvider) =
        DefaultProviderRegistry(autoDiscover = false).apply { providers.forEach(::register) }

    @Test
    fun `inference requested from the default provider goes to a provider that supports it`() {
        val reasoning = Recording("reasoning", listOf("plain", "plain-inference"), inferring = setOf("plain-inference"))
        val registry = registry(Recording("other", listOf("default")), reasoning)
        Rdf.repository(registry) { inference = true }.close()
        assertEquals(listOf("plain-inference"), reasoning.created)

        // Without the request nothing changes: the default provider is used.
        reasoning.created.clear()
        Rdf.repository(registry) { }.close()
        Rdf.repository(registry) { inference = false }.close()
        assertEquals(emptyList<String>(), reasoning.created)
    }

    @Test
    fun `inference that no registered provider supports fails and names the modules that provide it`() {
        val registry = registry(Recording("other", listOf("default")))
        val error = assertThrows(RdfProviderException::class.java) { Rdf.repository(registry) { inference = true } }
        val message = error.message!!
        assertTrue(message.contains("inference"), message)
        assertTrue(message.contains("rdf-jena") && message.contains("rdf-rdf4j"), message)
        assertTrue(message.contains("memory"), "the message names the provider that lacks it: $message")
    }

    @Test
    fun `a variant the default provider lacks goes to a provider that has it`() {
        val store = Recording("store", listOf("memory", "tdb2"))
        val registry = registry(store)
        Rdf.repository(registry) { variantId = "tdb2" }.close()
        assertEquals(listOf("tdb2"), store.created)

        // A variant the default provider has stays with the default provider.
        store.created.clear()
        Rdf.repository(registry) { variantId = "memory" }.close()
        assertEquals(emptyList<String>(), store.created)
    }

    @Test
    fun `a variant no registered provider has fails and names the module that provides it`() {
        val registry = registry(Recording("other", listOf("default")))
        val tdb2 = assertThrows(RdfProviderException::class.java) { Rdf.repository(registry) { variantId = "tdb2" } }.message!!
        assertTrue(tdb2.contains("'tdb2'") && tdb2.contains("rdf-jena"), tdb2)
        val native = assertThrows(RdfProviderException::class.java) { Rdf.repository(registry) { variantId = "native" } }.message!!
        assertTrue(native.contains("'native'") && native.contains("rdf-rdf4j"), native)
        val unknown = assertThrows(RdfProviderException::class.java) { Rdf.repository(registry) { variantId = "no-such" } }.message!!
        assertTrue(unknown.contains("'no-such'") && unknown.contains("other"), "the message lists what is registered: $unknown")
    }

    @Test
    fun `a variant together with inference needs a provider that has both`() {
        val plain = Recording("plain", listOf("tdb2"))
        val reasoning = Recording("reasoning", listOf("tdb2"), inferring = setOf("tdb2"), priority = -5)
        Rdf.repository(registry(plain, reasoning)) { variantId = "tdb2"; inference = true }.close()
        assertEquals(emptyList<String>(), plain.created)
        assertEquals(listOf("tdb2"), reasoning.created)

        val error = assertThrows(RdfProviderException::class.java) {
            Rdf.repository(registry(plain)) { variantId = "tdb2"; inference = true }
        }
        assertTrue(error.message!!.contains("inference") && error.message!!.contains("'tdb2'"), error.message)
    }

    @Test
    fun `an explicit provider is never replaced - it uses its inference variant or fails`() {
        val reasoning = Recording("reasoning", listOf("plain", "plain-inference"), inferring = setOf("plain-inference"))
        val registry = registry(reasoning, Recording("better", listOf("x"), inferring = setOf("x"), priority = 10))
        Rdf.repository(registry) { providerId = "reasoning"; inference = true }.close()
        assertEquals(listOf("plain-inference"), reasoning.created)

        // A variant named explicitly is not replaced either.
        val variant = assertThrows(RdfProviderException::class.java) {
            Rdf.repository(registry) { providerId = "reasoning"; variantId = "plain"; inference = true }
        }
        assertTrue(variant.message!!.contains("'plain'") && variant.message!!.contains("plain-inference"), variant.message)

        val memory = assertThrows(RdfProviderException::class.java) {
            Rdf.repository(registry) { providerId = "memory"; inference = true }
        }
        assertTrue(memory.message!!.contains("'memory'") && memory.message!!.contains("inference"), memory.message)
    }

    @Test
    fun `with the bundled providers on the class path, inference = true yields an inferring repository`() {
        Rdf.repository { inference = true }.use { repo ->
            assertTrue(repo.getCapabilities().supportsInference, "capabilities: ${repo.getCapabilities()}")
        }
    }
}
