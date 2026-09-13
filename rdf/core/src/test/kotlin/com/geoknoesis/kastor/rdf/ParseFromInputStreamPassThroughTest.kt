package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.FilterInputStream
import java.io.InputStream

class ParseFromInputStreamPassThroughTest {

    private class CountingSource(input: InputStream) : FilterInputStream(input) {
        var bytesRead = 0L
        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytesRead += it }
    }

    private class ScriptedProvider(
        override val id: String,
        override val priority: Int,
        private val onParse: (InputStream) -> MutableRdfGraph,
    ) : RdfProvider {
        var calls = 0
        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = MemoryRepository(config)
        override fun variants(): List<RdfVariant> = listOf(RdfVariant("default"))
        override fun getCapabilities(variantId: String?): ProviderCapabilities =
            ProviderCapabilities(supportedInputFormats = listOf(RdfFormat.N_TRIPLES.formatName))
        override fun parseGraph(inputStream: InputStream, format: String): MutableRdfGraph {
            calls++
            return onParse(inputStream)
        }
    }

    private fun <T> withProviders(vararg providers: RdfProvider, block: () -> T): T {
        val registry = DefaultProviderRegistry(autoDiscover = false, registerDefaultMemoryProvider = false)
        providers.forEach(registry::register)
        RdfProviderRegistry.setDelegate(registry)
        try {
            return block()
        } finally {
            RdfProviderRegistry.resetDelegate()
        }
    }

    @Test
    fun `the highest priority provider receives the unread stream and no other provider is called`() {
        val source = CountingSource("<urn:s> <urn:p> <urn:o> .".byteInputStream())
        val expected = MemoryGraph(listOf(RdfTriple(Iri("urn:s"), Iri("urn:p"), Iri("urn:o"))))
        val first = ScriptedProvider("first", priority = 10) { input ->
            assertEquals(0L, source.bytesRead, "input must not be read before the provider gets it")
            input.readBytes()
            expected
        }
        val second = ScriptedProvider("second", priority = 5) { error("must not be called") }
        withProviders(second, first) {
            assertSame(expected, Rdf.parseFromInputStream(source, RdfFormat.N_TRIPLES))
        }
        assertEquals(1, first.calls)
        assertEquals(0, second.calls)
    }

    @Test
    fun `a provider declining before reading lets the next provider parse the whole input`() {
        val declining = ScriptedProvider("declining", priority = 10) { throw UnsupportedOperationException("no") }
        val parsing = ScriptedProvider("parsing", priority = 5) { input ->
            assertEquals("<urn:s> <urn:p> <urn:o> .", input.readBytes().decodeToString())
            MemoryGraph()
        }
        withProviders(declining, parsing) {
            Rdf.parseFromInputStream("<urn:s> <urn:p> <urn:o> .".byteInputStream(), RdfFormat.N_TRIPLES)
        }
        assertEquals(1, parsing.calls)
    }

    @Test
    fun `a provider declining after consuming input fails loudly instead of falling back`() {
        val consuming = ScriptedProvider("consuming", priority = 10) { input ->
            input.read()
            throw UnsupportedOperationException("changed my mind")
        }
        val fallback = ScriptedProvider("fallback", priority = 5) { MemoryGraph() }
        val error = withProviders(consuming, fallback) {
            assertThrows(RdfFormatException::class.java) {
                Rdf.parseFromInputStream("<urn:s> <urn:p> <urn:o> .".byteInputStream(), RdfFormat.N_TRIPLES)
            }
        }
        assertTrue(error.message!!.contains("consumed the input"), error.message)
        assertEquals(0, fallback.calls)
    }
}
