package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Dataset.describe` run in place: the result is restricted to the dataset's graphs inside the read transaction of
 * the query, with a bounded number of lookups, and not at all for a repository that describes from the query's
 * dataset by contract. Also: `Dataset.graph(name)` for a name the dataset does not have.
 */
class DatasetDescribeFilterTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val g1 = Iri("urn:g1")
    private val inside = (1..40).map { RdfTriple(s, p, string("in$it")) }
    private val nested = BlankNode("b")
    private val closure = listOf(RdfTriple(s, Iri("urn:q"), nested), RdfTriple(nested, p, string("nested")))
    private val outside = listOf(RdfTriple(s, p, string("secret")), RdfTriple(Iri("urn:other"), p, string("secret")))

    /** Counts graph lookups and records whether they, and the describe itself, ran inside a read transaction. */
    private open class Probe(private val delegate: RdfRepository, private val described: List<RdfTriple>) :
        RdfRepository by delegate {
        var inTransaction = false
        var transactions = 0
        var lookups = 0
        var lookupsOutsideTransaction = 0
        var consumedOutsideTransaction = 0
        var describes = 0

        private inner class ProbedGraph(private val graph: RdfGraph) : RdfGraph by graph {
            private fun lookup() { lookups++; if (!inTransaction) lookupsOutsideTransaction++ }
            override fun hasTriple(triple: RdfTriple): Boolean { lookup(); return graph.hasTriple(triple) }
            override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
                lookup(); return graph.find(subject, predicate, obj)
            }
            override fun getTriples(): List<RdfTriple> { lookup(); return graph.getTriples() }
            override fun getTriplesSequence(): Sequence<RdfTriple> { lookup(); return graph.getTriplesSequence() }
        }

        override val defaultGraph: RdfGraph get() = ProbedGraph(delegate.defaultGraph)
        override fun getGraph(name: Iri): RdfGraph = ProbedGraph(delegate.getGraph(name))

        override fun readTransaction(operations: RdfRepository.() -> Unit) {
            transactions++
            delegate.readTransaction {
                inTransaction = true
                try { operations(this@Probe) } finally { inTransaction = false }
            }
        }

        override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
            describes++
            return sequence {
                for (triple in described) {
                    if (!inTransaction) consumedOutsideTransaction++
                    yield(triple)
                }
            }
        }
    }

    private fun store(): RdfRepository = Rdf.memory().apply {
        editDefaultGraph().addTriples(inside + closure)
        editGraph(g1).addTriples(outside)
    }

    @Test
    fun `the result is restricted to the dataset inside one read transaction with few lookups`() {
        val repo = store()
        try {
            val probe = Probe(repo, inside + closure + outside)
            val dataset = Dataset { defaultGraph(probe) }
            val described = dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:s>"))
            // Nothing is left to do lazily: the transaction is over and the result is complete.
            assertEquals(1, probe.transactions)
            assertEquals(1, probe.describes)
            assertEquals(0, probe.consumedOutsideTransaction, "the repository's sequence is consumed inside the transaction")
            val lookups = probe.lookups
            assertEquals((inside + closure).toSet(), described.toSet())
            assertEquals(lookups, probe.lookups, "reading the result does no further lookups")
            assertEquals(0, probe.lookupsOutsideTransaction)
            assertTrue(lookups in 1..6, "44 described triples of 3 subjects need a handful of lookups, not $lookups")
        } finally {
            repo.close()
        }
    }

    @Test
    fun `a repository that describes from the query dataset by contract is not filtered`() {
        val repo = store()
        try {
            class Scoped : Probe(repo, inside + closure + outside), DescribesQueryDataset
            val probe = Scoped()
            val dataset = Dataset { defaultGraph(probe) }
            val described = dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).toList()
            assertEquals(inside + closure + outside, described)
            assertEquals(0, probe.lookups)
            assertEquals(0, probe.consumedOutsideTransaction)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `a repository without read transactions is described and filtered without one`() {
        val repo = store()
        try {
            val probe = object : Probe(repo, inside + outside) {
                override fun readTransaction(operations: RdfRepository.() -> Unit) =
                    throw UnsupportedOperationException("no transactions")
            }
            val dataset = Dataset { defaultGraph(probe) }
            assertEquals(inside.toSet(), dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).toSet())
            assertEquals(1, probe.describes)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `a failure inside the read transaction is not retried without one`() {
        val repo = store()
        try {
            val probe = object : Probe(repo, inside) {
                override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
                    describes++
                    throw UnsupportedOperationException("no DESCRIBE")
                }
            }
            val dataset = Dataset { defaultGraph(probe) }
            val error = runCatching { dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).toList() }.exceptionOrNull()
            assertTrue(error is UnsupportedOperationException, error.toString())
            assertEquals(1, probe.describes)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `graph of a name the dataset does not have is an empty graph, not the default graph`() {
        val repo = store()
        try {
            val dataset = Dataset {
                defaultGraph(repo)
                namedGraph(g1, repo, g1)
            }
            assertEquals(2, dataset.graph(g1).size())
            val unknown = dataset.graph(Iri("urn:unknown"))
            assertNotSame(dataset.defaultGraph, unknown)
            assertEquals(0, unknown.size())
            assertEquals(emptyList<RdfTriple>(), unknown.getTriples())
            assertFalse(unknown.hasTriple(inside.first()))
            assertNull(dataset.getNamedGraph(Iri("urn:unknown")))
        } finally {
            repo.close()
        }
    }
}
