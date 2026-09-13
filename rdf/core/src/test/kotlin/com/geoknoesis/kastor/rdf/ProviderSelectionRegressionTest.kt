package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderSelectionRegressionTest {

    private class FakeProvider(
        override val id: String,
        override val priority: Int = 0,
        private val inputs: List<String> = emptyList(),
        private val outputs: List<String> = emptyList(),
        private val variantIds: List<String> = listOf("default"),
        private val inference: Boolean = false,
    ) : RdfProvider {
        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = MemoryRepository(config)
        override fun variants(): List<RdfVariant> = variantIds.map { RdfVariant(it) }
        override fun getCapabilities(variantId: String?): ProviderCapabilities = ProviderCapabilities(
            supportsInference = inference,
            supportedInputFormats = inputs,
            supportedOutputFormats = outputs,
        )
    }

    private fun isolatedRegistry() = DefaultProviderRegistry(autoDiscover = false, registerDefaultMemoryProvider = false)

    private fun <T> withGlobalRegistry(registry: ProviderRegistry, block: () -> T): T {
        RdfProviderRegistry.setDelegate(registry)
        try {
            return block()
        } finally {
            RdfProviderRegistry.resetDelegate()
        }
    }

    @Test
    fun `a declining provider does not exhaust the input for the next one`() {
        val formats = listOf(RdfFormat.N_TRIPLES.formatName, RdfFormat.N_QUADS.formatName)
        val registry = DefaultProviderRegistry().apply {
            register(FakeProvider("declines-first", priority = 100, inputs = formats))
        }
        withGlobalRegistry(registry) {
            assertEquals("declines-first", RdfProviderRegistry.discoverProviders().first().id)

            val graph = Rdf.parse("<urn:s> <urn:p> <urn:o> .", RdfFormat.N_TRIPLES)
            assertEquals(1, graph.size())

            val streamed = Rdf.parseStreaming("<urn:s> <urn:p> <urn:o> .".byteInputStream(), RdfFormat.N_TRIPLES).toList()
            assertEquals(1, streamed.size)

            val repo = Rdf.memory()
            Rdf.parseDataset(repo, "<urn:s> <urn:p> <urn:o> <urn:g> .".byteInputStream(), RdfFormat.N_QUADS)
            assertEquals(1, repo.getGraph(Iri("urn:g")).size())
            repo.close()
        }
    }

    @Test
    fun `providers are ordered by priority then registration order`() {
        val registry = isolatedRegistry()
        registry.register(FakeProvider("a"))
        registry.register(FakeProvider("b", priority = 10))
        registry.register(FakeProvider("c"))
        registry.register(FakeProvider("a")) // re-registration keeps the original position
        assertEquals(listOf("b", "a", "c"), registry.discoverProviders().map { it.id })
    }

    @Test
    fun `bundled memory provider sorts after default-priority providers`() {
        val registry = DefaultProviderRegistry(autoDiscover = false)
        registry.register(FakeProvider("late"))
        assertEquals(listOf("late", "memory"), registry.discoverProviders().map { it.id })
    }

    @Test
    fun `re-registering a provider removes stale variant types`() {
        val registry = isolatedRegistry()
        registry.register(FakeProvider("p", variantIds = listOf("v1", "v2")))
        registry.register(FakeProvider("p", variantIds = listOf("v1")))
        assertTrue(registry.supportsVariant("p", "v1"))
        assertFalse(registry.supportsVariant("p", "v2"))
        assertEquals(listOf("p:v1"), registry.getSupportedTypes())
    }

    @Test
    fun `explicit provider id is honoured or fails loudly`() {
        val registry = isolatedRegistry()
        registry.register(FakeProvider("plain", inference = false))
        registry.register(FakeProvider("smart", inference = true))

        val mismatch = assertThrows(IllegalArgumentException::class.java) {
            registry.create(RdfConfig(providerId = "plain", requirements = ProviderRequirements(supportsInference = true)))
        }
        assertTrue(mismatch.message!!.contains("plain"))

        val unknown = assertThrows(IllegalArgumentException::class.java) {
            registry.create(RdfConfig(providerId = "missing"))
        }
        assertTrue(unknown.message!!.contains("missing"))

        assertThrows(IllegalArgumentException::class.java) {
            registry.create(RdfConfig(providerId = "plain", variantId = "nope"))
        }

        // Without an explicit provider id, requirements still select a matching provider.
        registry.create(RdfConfig(requirements = ProviderRequirements(supportsInference = true))).close()
    }

    @Test
    fun `input and output format support are checked separately`() {
        val provider = FakeProvider(
            "split",
            inputs = listOf(RdfFormat.TURTLE.formatName),
            outputs = listOf(RdfFormat.N_TRIPLES.formatName),
        )
        assertTrue(provider.supportsInputFormat("TURTLE"))
        assertTrue(provider.supportsInputFormat("ttl"))
        assertFalse(provider.supportsInputFormat(RdfFormat.N_TRIPLES.formatName))
        assertTrue(provider.supportsOutputFormat(RdfFormat.N_TRIPLES.formatName))
        assertFalse(provider.supportsOutputFormat("TURTLE"))
        assertTrue(provider.supportsFormat("TURTLE") && provider.supportsFormat(RdfFormat.N_TRIPLES.formatName))

        val outputsOnly = FakeProvider("legacy", outputs = listOf(RdfFormat.TURTLE.formatName))
        assertTrue(outputsOnly.supportsInputFormat("TURTLE"), "an undeclared input list falls back to outputs")
        assertFalse(FakeProvider("none").supportsInputFormat("TURTLE"))
    }

    @Test
    fun `openTripleStream reports an unsupported format and closes the input`() {
        var closed = false
        val input = object : java.io.ByteArrayInputStream(ByteArray(0)) {
            override fun close() { closed = true; super.close() }
        }
        withGlobalRegistry(DefaultProviderRegistry(autoDiscover = false)) {
            assertThrows(RdfFormatException.UnsupportedFormat::class.java) {
                Rdf.openTripleStream(input, RdfFormat.TURTLE)
            }
        }
        assertTrue(closed)
    }
}
