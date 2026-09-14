package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DatasetReauditTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val g1 = Iri("urn:g1")
    private val g2 = Iri("urn:g2")

    @Test
    fun `queries declaring FROM or FROM NAMED are rejected on every execution path`() {
        val repo = Rdf.memory()
        try {
            repo.editGraph(g1).addTriple(RdfTriple(s, p, string("secret")))
            repo.editGraph(g2).addTriple(RdfTriple(s, p, string("visible")))
            val unchanged = Dataset { defaultGraph(repo) }
            val rewritten = Dataset { defaultGraph(repo.getGraph(g2).asGraphRef(repo, g2)) }
            val materialized = Dataset { defaultGraph(Rdf.graph { s - p - "public" }) }

            listOf(unchanged, rewritten, materialized).forEach { dataset ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    dataset.select(SparqlSelectQuery("SELECT ?o FROM <urn:g1> WHERE { ?s ?p ?o }"))
                }
                assertTrue(error.message!!.contains("FROM"), error.message)
                assertThrows(IllegalArgumentException::class.java) {
                    dataset.ask(SparqlAskQuery("ASK from named <urn:g1> { GRAPH ?g { ?s ?p ?o } }"))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    dataset.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } FROM <urn:g1> WHERE { ?s ?p ?o }"))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    dataset.describe(SparqlDescribeQuery("DESCRIBE ?s FROM <urn:g1> WHERE { ?s ?p ?o }"))
                }
            }

            // FROM inside comments, strings, IRIs, prefixed names and variables is not a dataset clause.
            val query = "PREFIX ex: <urn:FROM>\n# FROM <urn:g1>\nSELECT ?o WHERE { ?s ?p ?o FILTER(?o != \"FROM <urn:g1>\" && ?o != ex:FROM) OPTIONAL { ?s <urn:FROM> ?from } }"
            assertEquals(listOf("public"), materialized.select(SparqlSelectQuery(query)).map { it.getString("o") })
            assertEquals(listOf("visible"), rewritten.select(SparqlSelectQuery(query)).map { it.getString("o") })
        } finally {
            repo.close()
        }
    }

    @Test
    fun `optimized union graph enumerates and counts on the graph-only memory provider`() {
        val repo = RdfProviderRegistry.create(RdfConfig(providerId = "memory", variantId = "memory"))
        val t1 = RdfTriple(s, p, string("1"))
        val t2 = RdfTriple(BlankNode("b"), p, string("2"))
        repo.editGraph(g1).addTriple(t1)
        repo.editGraph(g2).addTriple(t1)
        repo.editGraph(g2).addTriple(t2)
        val dataset = Dataset {
            defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1))
            defaultGraph(repo.getGraph(g2).asGraphRef(repo, g2))
        }

        val union = dataset.defaultGraph
        assertEquals(listOf(t1, t2), union.getTriples())
        assertEquals(listOf(t1, t2), union.getTriplesSequence().toList())
        assertEquals(2, union.size())
        repo.close()
    }

    private class CountingRepository(private val delegate: RdfRepository = Rdf.memory()) : RdfRepository by delegate {
        var singleAdds = 0
        var batchAdds = 0
        var transactions = 0
        var closed = false

        private inner class CountingGraph(private val graph: MutableRdfGraph) : MutableRdfGraph by graph {
            override fun addTriple(triple: RdfTriple) { singleAdds++; graph.addTriple(triple) }
            override fun addTriples(triples: Collection<RdfTriple>) { batchAdds++; graph.addTriples(triples) }
            override fun addTriples(triples: Iterable<RdfTriple>) { batchAdds++; graph.addTriples(triples) }
            override fun addTriples(triples: Sequence<RdfTriple>) { batchAdds++; graph.addTriples(triples) }
        }

        override fun editDefaultGraph(): MutableRdfGraph = CountingGraph(delegate.editDefaultGraph())
        override fun editGraph(name: Iri): MutableRdfGraph = CountingGraph(delegate.editGraph(name))
        override fun transaction(operations: RdfRepository.() -> Unit) {
            transactions++
            delegate.transaction { operations(this@CountingRepository) }
        }
        override fun close() { closed = true; delegate.close() }
    }

    @Test
    fun `materialized fallback loads graphs in batches inside one transaction`() {
        val dataset = Dataset {
            defaultGraph(Rdf.graph { s - p - "a"; s - p - "b" })
            defaultGraph(Rdf.graph { s - p - "c" })
            namedGraph(g1, Rdf.graph { s - p - "d"; s - p - "e" })
        } as DatasetImpl
        val counting = CountingRepository()
        dataset.materializationRepositoryFactory = { counting }

        val values = dataset.select(
            SparqlSelectQuery("SELECT ?o WHERE { { ?s ?p ?o } UNION { GRAPH ?g { ?s ?p ?o } } }")
        ).map { it.getString("o") }.toSet()

        assertEquals(setOf("a", "b", "c", "d", "e"), values)
        assertEquals(0, counting.singleAdds, "no per-triple writes")
        assertTrue(counting.batchAdds in 1..3, "one batch per target graph, got ${counting.batchAdds}")
        assertEquals(1, counting.transactions)
        assertTrue(counting.closed)
    }
}
