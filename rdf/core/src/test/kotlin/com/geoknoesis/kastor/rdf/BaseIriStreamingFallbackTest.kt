package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A third-party provider without base-IRI streaming overloads falls back to eager parsing, visibly and once. */
class BaseIriStreamingFallbackTest {
    private val p = Iri("urn:p")

    /** Streams without a base, but does not override the base-IRI streaming overloads. */
    private class NoBaseStreamingProvider : RdfProvider {
        var eagerParses = 0
        override val id = "no-base-streaming"
        override val priority = 10_000
        override fun variants(): List<RdfVariant> = listOf(RdfVariant("memory"))
        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = MemoryRepository(config)
        override fun getCapabilities(variantId: String?) = ProviderCapabilities(supportedInputFormats = listOf(RdfFormat.N_TRIPLES.formatName))
        override fun parseGraph(inputStream: java.io.InputStream, format: String): MutableRdfGraph = parseGraph(inputStream, format, null)
        override fun parseGraph(inputStream: java.io.InputStream, format: String, baseIri: String?): MutableRdfGraph {
            eagerParses++
            val count = inputStream.bufferedReader().readLines().count { it.isNotBlank() }
            return MemoryGraph((0 until count).map { RdfTriple(Iri(baseIri ?: "urn:nobase"), Iri("urn:p"), string("$it")) })
        }
        override fun openTripleStream(inputStream: java.io.InputStream, format: String): TripleStream {
            val lines = inputStream.bufferedReader().lineSequence().filter { it.isNotBlank() }.map { RdfTriple(Iri("urn:nobase"), Iri("urn:p"), string(it)) }
            return object : TripleStream {
                override fun iterator() = lines.iterator()
                override fun close() = inputStream.close()
            }
        }
    }

    @Test
    fun `base-IRI streaming on a provider without support is eager, correct and reported once`() {
        val provider = NoBaseStreamingProvider()
        RdfProviderRegistry.setDelegate(DefaultProviderRegistry().apply { register(provider) })
        try {
            val data = "a\nb\nc\n"
            Rdf.openTripleStream(data.byteInputStream(), RdfFormat.N_TRIPLES).use { assertEquals(3, it.count()) }
            assertEquals(0, provider.eagerParses)
            assertFalse(EagerBaseIriFallback.warnedProviders.contains(NoBaseStreamingProvider::class.java.name))

            Rdf.openTripleStream(data.byteInputStream(), RdfFormat.N_TRIPLES, "http://example.org/base").use { rows ->
                assertEquals(List(3) { RdfTriple(Iri("http://example.org/base"), p, string("$it")) }, rows.toList())
            }
            assertEquals(3, Rdf.parseStreaming(data.byteInputStream(), RdfFormat.N_TRIPLES, "http://example.org/base").count())
            assertEquals(2, provider.eagerParses)
            assertTrue(EagerBaseIriFallback.warnedProviders.contains(NoBaseStreamingProvider::class.java.name))
            assertEquals(1, EagerBaseIriFallback.warningsLogged(NoBaseStreamingProvider::class.java.name))
        } finally {
            RdfProviderRegistry.resetDelegate()
        }
    }
}
