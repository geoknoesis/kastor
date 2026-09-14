package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CoreFactoryReauditTest {

    @TempDir
    lateinit var dir: Path

    /** A provider with a `memory` variant whose repositories are recorded. */
    private class TrackingMemoryProvider(
        override val id: String,
        override val priority: Int,
        private val sparql: Boolean = true,
    ) : RdfProvider {
        val created = mutableListOf<MemoryRepository>()
        override fun variants(): List<RdfVariant> = listOf(RdfVariant("memory"))
        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = MemoryRepository(config).also { created += it }
        override fun getCapabilities(variantId: String?): ProviderCapabilities =
            if (sparql) ProviderCapabilities(supportsPropertyPaths = true, supportsAggregation = true, supportsSubSelect = true)
            else ProviderCapabilities()
    }

    private fun <T> withRegistry(provider: RdfProvider, block: () -> T): T {
        RdfProviderRegistry.setDelegate(DefaultProviderRegistry().apply { register(provider) })
        try {
            return block()
        } finally {
            RdfProviderRegistry.resetDelegate()
        }
    }

    @Test
    fun `Rdf memory follows provider priority instead of a hard-coded order`() {
        val preferred = TrackingMemoryProvider("preferred", priority = 1_000)
        withRegistry(preferred) {
            val repo = Rdf.memory()
            assertSame(preferred.created.single(), repo)
            repo.close()
        }

        val fallback = TrackingMemoryProvider("fallback", priority = -50)
        withRegistry(fallback) {
            val repo = Rdf.memory()
            assertTrue(fallback.created.isEmpty(), "a higher-priority SPARQL-capable provider (Jena) wins")
            assertFalse(repo is MemoryRepository)
            repo.close()
        }
    }

    @Test
    fun `Rdf memory skips providers that do not declare SPARQL support`() {
        val graphOnly = TrackingMemoryProvider("graph-only", priority = 1_000, sparql = false)
        withRegistry(graphOnly) {
            val repo = Rdf.memory()
            try {
                assertTrue(graphOnly.created.isEmpty(), "a memory variant without SPARQL must not be chosen")
                assertFalse(repo is MemoryRepository)
                assertTrue(repo.ask(SparqlAskQuery("ASK { }")))
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun `dataset parsing closes the repository it created when parsing fails`() {
        val tracking = TrackingMemoryProvider("tracking", priority = 1_000)
        val malformed = "GRAPH <urn:g> { <urn:s> <urn:p> "
        val file = dir.resolve("bad.trig")
        Files.writeString(file, malformed)

        withRegistry(tracking) {
            assertThrows(RdfFormatException::class.java) { Rdf.parseDataset(malformed, RdfFormat.TRIG) }
            assertThrows(RdfFormatException::class.java) { Rdf.parseDatasetFromFile(file.toString(), RdfFormat.TRIG) }
        }

        assertEquals(2, tracking.created.size)
        assertTrue(tracking.created.all { it.isClosed() })
    }

    @Test
    fun `a truly malformed payload in a supported format is a parse error and the repository is closed`() {
        val tracking = TrackingMemoryProvider("tracking", priority = 1_000)
        // TriG is supported (by Jena); the payload is syntactically broken rather than in an unknown format.
        val malformed = "@prefix ex: <http://example.org/> .\nGRAPH ex:g { ex:s ex:p \"unterminated . }\n"
        val file = dir.resolve("broken.trig")
        Files.writeString(file, malformed)

        withRegistry(tracking) {
            assertTrue(RdfProviderRegistry.discoverProviders().any { it.supportsInputFormat(RdfFormat.TRIG.formatName) })
            val fromString = assertThrows(RdfFormatException::class.java) { Rdf.parseDataset(malformed, RdfFormat.TRIG) }
            val fromFile = assertThrows(RdfFormatException::class.java) { Rdf.parseDatasetFromFile(file.toString(), RdfFormat.TRIG) }
            listOf(fromString, fromFile).forEach { error ->
                assertFalse(error is RdfFormatException.UnsupportedFormat, "expected a parse failure, got $error")
            }
        }

        assertEquals(2, tracking.created.size)
        assertTrue(tracking.created.all { it.isClosed() })
    }
}
