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
    private class TrackingMemoryProvider(override val id: String, override val priority: Int) : RdfProvider {
        val created = mutableListOf<MemoryRepository>()
        override fun variants(): List<RdfVariant> = listOf(RdfVariant("memory"))
        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = MemoryRepository(config).also { created += it }
        override fun getCapabilities(variantId: String?): ProviderCapabilities = ProviderCapabilities()
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
}
