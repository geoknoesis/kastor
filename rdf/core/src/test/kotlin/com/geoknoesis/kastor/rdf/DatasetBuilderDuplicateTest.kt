package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DatasetBuilderDuplicateTest {
    private val name = Iri("urn:g")

    @Test
    fun `adding a named graph name twice is an error instead of a silent overwrite`() {
        val builder = DatasetBuilder().defaultGraph(MemoryGraph()).namedGraph(name, MemoryGraph())
        val error = assertThrows(IllegalArgumentException::class.java) { builder.namedGraph(name, MemoryGraph()) }
        assertTrue(error.message!!.contains("urn:g"), error.message)
        MemoryRepository(RdfConfig()).use { repo ->
            assertThrows(IllegalArgumentException::class.java) { builder.namedGraph(name, repo) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DatasetBuilder().namedGraphs(Iri("urn:x") to MemoryGraph(), Iri("urn:x") to MemoryGraph())
        }
    }

    @Test
    fun `a dataset without a default graph is rejected with IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { DatasetBuilder().namedGraph(name, MemoryGraph()).build() }
    }
}
