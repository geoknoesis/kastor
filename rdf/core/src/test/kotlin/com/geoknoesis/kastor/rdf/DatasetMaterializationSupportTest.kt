package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.Closeable

class DatasetMaterializationSupportTest {
    @Test
    fun `dataset close attempts every distinct graph even when earlier cleanup fails`() {
        val closed = mutableListOf<String>()
        val firstFailure = IllegalStateException("first close failed")
        val secondFailure = IllegalStateException("second close failed")
        class ClosingGraph(private val name: String, private val failure: Throwable) : RdfGraph by MemoryGraph(), Closeable {
            override fun close() { closed.add(name); throw failure }
            override fun equals(other: Any?): Boolean = other is ClosingGraph
            override fun hashCode(): Int = 1
        }
        val first = ClosingGraph("first", firstFailure)
        val second = ClosingGraph("second", secondFailure)
        val dataset = Dataset { defaultGraph(first); defaultGraph(second); namedGraph(Iri("urn:again"), second) }
        val error = assertThrows(IllegalStateException::class.java) { dataset.close() }
        assertEquals(listOf("first", "second"), closed)
        assertSame(firstFailure, error)
        assertEquals(listOf(secondFailure), error.suppressed.toList())
    }

    @Test
    fun `accepted candidates transfer ownership and rejected candidates close once`() {
        listOf(true, false).forEach { supported ->
            val backing = MemoryRepository(RdfConfig())
            var closes = 0
            val candidate = object : RdfRepository by backing, DatasetClauseSupport {
                override fun supportsDatasetGraph(name: Iri) = supported
                override fun close() { closes++; backing.close() }
            }
            val result = DatasetRepositories.retainCompatible(candidate, listOf(Iri("urn:graph")))
            if (supported) {
                assertSame(candidate, result)
                assertEquals(0, closes)
                result!!.close()
            } else assertNull(result)
            assertEquals(1, closes)
        }
    }

    @Test
    fun `capability failure closes a candidate and retains cleanup failure as suppressed`() {
        val backing = MemoryRepository(RdfConfig())
        val capabilityFailure = IllegalStateException("capability failed")
        val closeFailure = IllegalStateException("close failed")
        var closes = 0
        val candidate = object : RdfRepository by backing, DatasetClauseSupport {
            override fun supportsDatasetGraph(name: Iri): Boolean = throw capabilityFailure
            override fun close() { closes++; backing.close(); throw closeFailure }
        }
        val error = assertThrows(IllegalStateException::class.java) {
            DatasetRepositories.retainCompatible(candidate, listOf(Iri("urn:graph")))
        }
        assertSame(capabilityFailure, error)
        assertEquals(listOf(closeFailure), error.suppressed.toList())
        assertEquals(1, closes)
    }

    @Test
    fun `cleanup failure cannot hide the original query failure`() {
        val backing = MemoryRepository(RdfConfig())
        val queryFailure = IllegalStateException("query failed")
        val closeFailure = IllegalStateException("close failed")
        val failing = object : RdfRepository by backing {
            override fun ask(query: SparqlAsk): Boolean = throw queryFailure
            override fun close() { backing.close(); throw closeFailure }
        }
        val dataset = Dataset { defaultGraph(MemoryGraph()) } as DatasetImpl
        dataset.materializationRepositoryFactory = { failing }
        val error = assertThrows(IllegalStateException::class.java) { dataset.ask(SparqlAskQuery("ASK { ?s ?p ?o }")) }
        assertSame(queryFailure, error)
        assertEquals(listOf(closeFailure), error.suppressed.toList())
    }

    @Test
    fun `an incompatible materialization provider is closed and rejected before writes`() {
        val backing = MemoryRepository(RdfConfig())
        var writes = 0
        var closed = false
        val incompatible = object : RdfRepository by backing, DatasetClauseSupport {
            override fun supportsDatasetGraph(name: Iri) = false
            override fun editDefaultGraph(): MutableRdfGraph { writes++; return backing.editDefaultGraph() }
            override fun editGraph(name: Iri): MutableRdfGraph { writes++; return backing.editGraph(name) }
            override fun close() { closed = true; backing.close() }
        }
        val dataset = Dataset {
            defaultGraph(MemoryGraph())
            namedGraph(Iri("urn:reserved"), MemoryGraph())
        } as DatasetImpl
        dataset.materializationRepositoryFactory = { incompatible }
        val error = assertThrows(UnsupportedOperationException::class.java) {
            dataset.ask(SparqlAskQuery("ASK { GRAPH ?g { ?s ?p ?o } }"))
        }
        assertTrue(error.message.orEmpty().contains("compatible"))
        assertEquals(0, writes)
        assertTrue(closed)
    }
}
