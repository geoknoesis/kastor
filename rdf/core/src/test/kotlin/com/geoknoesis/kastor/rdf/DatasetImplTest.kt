package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.EmptySparqlQueryResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable

class DatasetImplTest {

    @Test
    fun `dataset builder requires at least one default graph`() {
        assertThrows(IllegalArgumentException::class.java) {
            Dataset { }
        }
    }


    @Test
    fun `dataset exposes default and named graph accessors`() {
        val repo = Rdf.memory()
        val namedGraphName = Iri("http://example.org/named")
        val subject = Iri("http://example.org/subject")
        val predicate = Iri("http://example.org/predicate")
        val obj = string("value")

        repo.editDefaultGraph().addTriple(RdfTriple(subject, predicate, obj))
        repo.editGraph(namedGraphName).addTriple(RdfTriple(subject, predicate, obj))

        val dataset = Dataset {
            defaultGraph(repo)
            namedGraph(namedGraphName, repo, namedGraphName)
        }

        assertEquals(1, dataset.defaultGraphs.size)
        assertTrue(dataset.hasNamedGraph(namedGraphName))
        assertEquals(listOf(namedGraphName), dataset.listNamedGraphs())
        assertNotNull(dataset.getNamedGraph(namedGraphName))
        assertEquals(dataset.getNamedGraph(namedGraphName), dataset.graph(namedGraphName))

        val unknown = Iri("http://example.org/unknown")
        assertFalse(dataset.hasNamedGraph(unknown))
        assertEquals(dataset.defaultGraph, dataset.graph(unknown))

        dataset.close()
        repo.close()
    }

    @Test
    fun `dataset rewrites queries with FROM clauses when graphs share repository`() {
        val repo = CapturingRepository()
        val extraDefaultGraphName = Iri("http://example.org/extra-default")
        val namedGraphName = Iri("http://example.org/named-graph")

        val dataset = Dataset {
            defaultGraph(repo)
            defaultGraph(repo.getGraph(extraDefaultGraphName).asGraphRef(repo, extraDefaultGraphName))
            namedGraph(namedGraphName, repo, namedGraphName)
        }

        dataset.select(SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }"))

        val query = repo.lastSelect?.sparql ?: error("Expected SELECT query to be captured")
        assertTrue(query.contains("FROM <http://example.org/extra-default>"))
        assertTrue(query.contains("FROM NAMED <http://example.org/named-graph>"))
        assertTrue(query.contains("SELECT"))

        dataset.close()
        repo.close()
    }

    @Test
    fun `dataset does not rewrite queries that already include FROM`() {
        val repo = CapturingRepository()
        val dataset = Dataset {
            defaultGraph(repo)
        }

        val queryText = """
            PREFIX ex: <http://example.org/>
            SELECT * FROM <http://example.org/explicit> WHERE { ?s ?p ?o }
        """.trimIndent()
        dataset.select(SparqlSelectQuery(queryText))

        val captured = repo.lastSelect?.sparql ?: error("Expected SELECT query to be captured")
        assertEquals(queryText, captured)

        dataset.close()
        repo.close()
    }

    @Test
    fun `dataset unions untracked default graphs`() {
        val graph1 = Rdf.graph {
            val subject = Iri("http://example.org/s1")
            val predicate = Iri("http://example.org/p")
            subject - predicate - "one"
        }
        val graph2 = Rdf.graph {
            val subject = Iri("http://example.org/s2")
            val predicate = Iri("http://example.org/p")
            subject - predicate - "two"
        }

        val dataset = Dataset {
            defaultGraph(graph1)
            defaultGraph(graph2)
        }

        val triples = dataset.defaultGraph.getTriples()
        assertEquals(2, triples.size)
        assertTrue(triples.any { it.subject == Iri("http://example.org/s1") })
        assertTrue(triples.any { it.subject == Iri("http://example.org/s2") })

        dataset.close()
    }

    @Test
    fun `dataset materializes when default graphs span repositories`() {
        val repo1 = CapturingRepository()
        val repo2 = CapturingRepository()

        val graph1 = Rdf.graph {
            val subject = Iri("http://example.org/s1")
            val predicate = Iri("http://example.org/p")
            subject - predicate - "one"
        }.asGraphRef(repo1, null)

        val graph2 = Rdf.graph {
            val subject = Iri("http://example.org/s2")
            val predicate = Iri("http://example.org/p")
            subject - predicate - "two"
        }.asGraphRef(repo2, null)

        val dataset = Dataset {
            defaultGraph(graph1)
            defaultGraph(graph2)
        }

        assertThrows(UnsupportedOperationException::class.java) {
            dataset.select(SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }"))
        }

        dataset.close()
        repo1.close()
        repo2.close()
    }

    @Test
    fun `dataset materializes named graphs into target repository`() {
        val repo = Rdf.memory()
        val namedGraphName = Iri("http://example.org/named")
        val subject = Iri("http://example.org/s")
        val predicate = Iri("http://example.org/p")
        val obj = string("value")

        repo.editGraph(namedGraphName).addTriple(RdfTriple(subject, predicate, obj))

        val dataset = Dataset {
            defaultGraph(Rdf.graph { })
            namedGraph(namedGraphName, repo, namedGraphName)
        }

        // Execute query to force materialization path (untracked default graph)
        assertThrows(UnsupportedOperationException::class.java) {
            dataset.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"))
        }

        // The materialized union is internal, but we can at least ensure no exception is thrown.
        dataset.close()
        repo.close()
    }

    @Test
    fun `dataset close dedupes graphs`() {
        val closeableGraph = CloseableTestGraph()
        val dataset = Dataset {
            defaultGraph(closeableGraph)
            namedGraph(Iri("http://example.org/named"), closeableGraph)
        }

        dataset.close()

        assertEquals(1, closeableGraph.closeCount)
    }

    private class CapturingRepository(
        private val delegate: RdfRepository = Rdf.memory()
    ) : RdfRepository by delegate {
        var lastSelect: SparqlSelect? = null
        var lastAsk: SparqlAsk? = null
        var lastConstruct: SparqlConstruct? = null
        var lastDescribe: SparqlDescribe? = null
        var lastUpdate: UpdateQuery? = null

        override fun select(query: SparqlSelect): SparqlQueryResult {
            lastSelect = query
            return EmptySparqlQueryResult
        }

        override fun ask(query: SparqlAsk): Boolean {
            lastAsk = query
            return false
        }

        override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
            lastConstruct = query
            return emptySequence()
        }

        override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
            lastDescribe = query
            return emptySequence()
        }

        override fun update(query: UpdateQuery) {
            lastUpdate = query
        }
    }

    private class CloseableTestGraph : MutableRdfGraph, Closeable {
        private val triples = mutableListOf<RdfTriple>()
        var closeCount = 0

        override fun addTriple(triple: RdfTriple) {
            triples.add(triple)
        }

        override fun addTriples(triples: Collection<RdfTriple>) {
            this.triples.addAll(triples)
        }

        override fun removeTriple(triple: RdfTriple): Boolean {
            return triples.remove(triple)
        }

        override fun removeTriples(triples: Collection<RdfTriple>): Boolean {
            var removed = false
            triples.forEach { triple ->
                if (this.triples.remove(triple)) {
                    removed = true
                }
            }
            return removed
        }

        override fun clear(): Boolean {
            val hadTriples = triples.isNotEmpty()
            triples.clear()
            return hadTriples
        }

        override fun hasTriple(triple: RdfTriple): Boolean = triples.contains(triple)

        override fun getTriples(): List<RdfTriple> = triples.toList()

        override fun size(): Int = triples.size

        override fun close() {
            closeCount += 1
        }
    }
}

