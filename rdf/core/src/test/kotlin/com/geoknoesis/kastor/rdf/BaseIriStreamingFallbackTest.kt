package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
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

            Rdf.openTripleStream(data.byteInputStream(), RdfFormat.N_TRIPLES, "http://example.org/base").use { rows ->
                assertEquals(List(3) { RdfTriple(Iri("http://example.org/base"), p, string("$it")) }, rows.toList())
            }
            assertEquals(3, Rdf.parseStreaming(data.byteInputStream(), RdfFormat.N_TRIPLES, "http://example.org/base").count())
            assertEquals(2, provider.eagerParses)
        } finally {
            RdfProviderRegistry.resetDelegate()
        }
    }

    @Test
    fun `the eager fallback warning is emitted once per provider class, also under concurrent use`() {
        val warnings = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val fallback = EagerBaseIriFallback(warnings::add)
        val first = NoBaseStreamingProvider()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val tasks = (1..32).map { pool.submit { start.await(); fallback.record(first) } }
            start.countDown()
            tasks.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        fallback.record(NoBaseStreamingProvider())
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("no-base-streaming") && warnings.single().contains(NoBaseStreamingProvider::class.java.name))

        fallback.record(MemoryRepositoryProviderStub)
        assertEquals(2, warnings.size)
        fallback.record(MemoryRepositoryProviderStub)
        assertEquals(2, warnings.size)
    }

    private object MemoryRepositoryProviderStub : RdfProvider {
        override val id = "stub"
        override fun variants(): List<RdfVariant> = listOf(RdfVariant("memory"))
        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = MemoryRepository(config)
    }

    @Test
    fun `a base IRI dropped by the default parseDataset is reported once per provider class`() {
        val messages = mutableListOf<String>()
        val fallback = EagerBaseIriFallback { messages.add(it) }
        val provider = NoBaseStreamingProvider()
        fallback.recordIgnoredDatasetBase(provider)
        fallback.recordIgnoredDatasetBase(provider)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("base IRI is ignored"), messages.single())
    }
}
