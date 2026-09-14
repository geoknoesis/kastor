package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Deterministic versions of the scenarios of the former, permanently disabled wall-clock
 * `PerformanceBenchmarkTest`: large-graph creation and querying, file parsing (eager, streaming and scoped),
 * serialization, and batch versus per-triple writes. They assert results and write-operation counts instead of
 * timings; measure timings with a JMH benchmark rather than in unit tests.
 */
class LargeGraphOperationsTest {

    @TempDir
    lateinit var dir: Path

    private val name = Iri("http://example.org/property/name")

    private fun triple(i: Int) = RdfTriple(Iri("http://example.org/resource/$i"), name, string("Resource $i"))

    @Test
    fun `a large graph built with the DSL holds every triple and answers a limited query`() {
        val size = 20_000
        val repo = Rdf.memory()
        try {
            repo.add {
                repeat(size) { i -> Iri("http://example.org/resource/$i") - name - "Resource $i" }
            }

            assertEquals(size, repo.defaultGraph.size())
            assertTrue(repo.defaultGraph.hasTriple(triple(0)))
            assertTrue(repo.defaultGraph.hasTriple(triple(size - 1)))
            val rows = repo.select(
                SparqlSelectQuery("SELECT ?s ?o WHERE { ?s <http://example.org/property/name> ?o } LIMIT 100")
            ).toList()
            assertEquals(100, rows.size)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `a large file parses to the same triples eagerly, streamed and through a scoped stream`() {
        val size = 20_000
        val file = dir.resolve("large.ttl")
        Files.newBufferedWriter(file).use { writer ->
            repeat(size) { i -> writer.write("<http://example.org/resource/$i> <http://example.org/property/name> \"Resource $i\" .\n") }
        }
        val expected = (0 until size).map(::triple).toSet()

        assertEquals(expected, Rdf.parseFromFile(file.toString(), RdfFormat.TURTLE).getTriples().toSet())
        assertEquals(expected, Files.newInputStream(file).use { Rdf.parseStreaming(it, RdfFormat.TURTLE).toSet() })
        Rdf.openTripleStream(Files.newInputStream(file), RdfFormat.TURTLE).use { assertEquals(expected, it.toSet()) }
    }

    @Test
    fun `a large graph round-trips through every standard graph format`() {
        val repo = Rdf.memory()
        try {
            repo.editDefaultGraph().addTriples((0 until 5_000).map(::triple))
            val expected = repo.defaultGraph.getTriples().toSet()
            assertEquals(5_000, expected.size)

            listOf(RdfFormat.TURTLE, RdfFormat.N_TRIPLES, RdfFormat.JSON_LD, RdfFormat.RDF_XML).forEach { format ->
                val text = repo.defaultGraph.serialize(format)
                assertEquals(expected, Rdf.parse(text, format).getTriples().toSet(), format.name)
            }
        } finally {
            repo.close()
        }
    }

    /** Counts write calls that reach the default graph, whichever add overload or transaction path is used. */
    private class CountingRepository(private val delegate: RdfRepository) : RdfRepository by delegate {
        var writes = 0

        private inner class CountingGraph(private val graph: MutableRdfGraph) : MutableRdfGraph by graph {
            override fun addTriple(triple: RdfTriple) { writes++; graph.addTriple(triple) }
            override fun addTriples(triples: Collection<RdfTriple>) { writes++; graph.addTriples(triples) }
            override fun addTriples(triples: Iterable<RdfTriple>) { writes++; graph.addTriples(triples) }
            override fun addTriples(triples: Sequence<RdfTriple>) { writes++; graph.addTriples(triples) }
        }

        override fun editDefaultGraph(): MutableRdfGraph = CountingGraph(delegate.editDefaultGraph())
        override fun transaction(operations: RdfRepository.() -> Unit) {
            delegate.transaction { operations(this@CountingRepository) }
        }
    }

    @Test
    fun `a DSL block issues one batched write while per-triple blocks issue one write each`() {
        val size = 2_000
        val triples = (0 until size).map(::triple)
        val individual = CountingRepository(Rdf.memory())
        val batch = CountingRepository(Rdf.memory())
        try {
            triples.forEach { t -> individual.add { t.subject - t.predicate - t.obj } }
            batch.add { triples.forEach { t -> t.subject - t.predicate - t.obj } }

            assertEquals(size, individual.writes)
            assertEquals(1, batch.writes)
            assertEquals(triples.toSet(), individual.defaultGraph.getTriples().toSet())
            assertEquals(triples.toSet(), batch.defaultGraph.getTriples().toSet())
        } finally {
            individual.close()
            batch.close()
        }
    }
}
