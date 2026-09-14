package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.EmptySparqlQueryResult
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Dataset queries are rewritten with FROM / FROM NAMED clauses when every graph lives in one
 * repository. The rewritten text must be valid SPARQL, so each case is parsed with Jena ARQ
 * (on the test runtime classpath only, hence the reflection).
 */
class DatasetQueryRewriteTest {

    private val g1 = Iri("http://example.org/g1")
    private val g2 = Iri("http://example.org/g2")
    private val n1 = Iri("http://example.org/n1")
    private val s = Iri("http://example.org/s")
    private val p = Iri("http://example.org/p")

    private fun assertParses(query: String) {
        val create = Class.forName("org.apache.jena.query.QueryFactory").getMethod("create", String::class.java)
        try {
            create.invoke(null, query)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            fail<Unit>("Rewritten query does not parse:\n$query\n${e.targetException.message}")
        }
    }

    private fun occurrences(text: String, needle: String) = Regex(Regex.escape(needle)).findAll(text).count()

    private fun namedDefaultsDataset(repo: RdfRepository) = Dataset {
        defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1))
        defaultGraph(repo.getGraph(g2).asGraphRef(repo, g2))
        namedGraph(n1, repo, n1)
    }

    @Test
    fun `select rewrite is valid and leaves sub-selects, strings, comments and prologue intact`() {
        val repo = CapturingRepository()
        val query = """
            BASE <http://example.org/>
            PREFIX : <http://example.org/>
            PREFIX ex: <http://example.org/ns#>
            # a comment mentioning SELECT and WHERE {
            SELECT ?s (COUNT(*) AS ?n)
            WHERE {
              ?s :p "SELECT ?x WHERE { }" .
              { SELECT ?s WHERE { ?s ex:q ?o } }
            }
            GROUP BY ?s
        """.trimIndent()

        namedDefaultsDataset(repo).select(SparqlSelectQuery(query))

        val rewritten = repo.lastSelect!!.sparql
        assertParses(rewritten)
        assertEquals(1, occurrences(rewritten, "FROM <${g1.value}>"))
        assertEquals(1, occurrences(rewritten, "FROM <${g2.value}>"))
        assertEquals(1, occurrences(rewritten, "FROM NAMED <${n1.value}>"))
        assertTrue(rewritten.contains("\"SELECT ?x WHERE { }\""), "string literal must be untouched")
        assertTrue(rewritten.contains("{ SELECT ?s WHERE { ?s ex:q ?o } }"), "sub-select must be untouched")
        assertTrue(rewritten.indexOf("FROM <") > rewritten.indexOf("SELECT ?s (COUNT"), "dataset clauses follow the projection")
    }

    @Test
    fun `construct, ask and describe rewrites are valid`() {
        val repo = CapturingRepository()
        val dataset = namedDefaultsDataset(repo)

        dataset.construct(SparqlConstructQuery("CONSTRUCT { ?s <http://example.org/p> \"}\" } WHERE { ?s ?p ?o }"))
        assertParses(repo.lastConstruct!!.sparql)
        assertTrue(repo.lastConstruct!!.sparql.contains("FROM <${g1.value}>"))

        dataset.construct(SparqlConstructQuery("PREFIX : <http://example.org/> CONSTRUCT WHERE { ?s :p ?o }"))
        assertParses(repo.lastConstruct!!.sparql)
        assertTrue(repo.lastConstruct!!.sparql.contains("FROM <${g1.value}>"))

        dataset.ask(SparqlAskQuery("ASK { ?s ?p ?o FILTER(?o < 3) }"))
        assertParses(repo.lastAsk!!.sparql)
        assertTrue(repo.lastAsk!!.sparql.contains("FROM NAMED <${n1.value}>"))

        dataset.describe(SparqlDescribeQuery("DESCRIBE <http://example.org/s> LIMIT 5"))
        assertParses(repo.lastDescribe!!.sparql)
        assertTrue(repo.lastDescribe!!.sparql.contains("FROM <${g2.value}>"))
    }

    @Test
    fun `queries that already declare a dataset are rejected`() {
        val repo = CapturingRepository()
        val query = "SELECT * FROM <http://example.org/explicit> WHERE { ?s ?p ?o }"
        val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            namedDefaultsDataset(repo).select(SparqlSelectQuery(query))
        }
        assertTrue(error.message!!.contains("FROM"))
        assertNull(repo.lastSelect)
    }

    @Test
    fun `store default graph mixed with a named graph keeps both in the default graph`() {
        val repo = Rdf.memory()
        repo.editDefaultGraph().addTriple(RdfTriple(s, p, string("default")))
        repo.editGraph(g1).addTriple(RdfTriple(s, p, string("g1")))
        repo.editGraph(g2).addTriple(RdfTriple(s, p, string("g2")))

        val dataset = Dataset {
            defaultGraph(repo)
            defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1))
        }

        val values = dataset.select(SparqlSelectQuery("SELECT ?o WHERE { ?s ?p ?o }")).map { it.getString("o") }.toSet()
        assertEquals(setOf("default", "g1"), values)
        assertEquals(2, dataset.defaultGraph.size())
        assertTrue(dataset.defaultGraph.hasTriple(RdfTriple(s, p, string("default"))))
        repo.close()
    }

    @Test
    fun `store default graph with named graphs exposes only the declared named graphs`() {
        val repo = Rdf.memory()
        repo.editDefaultGraph().addTriple(RdfTriple(s, p, string("default")))
        repo.editGraph(n1).addTriple(RdfTriple(s, p, string("n1")))
        repo.editGraph(g2).addTriple(RdfTriple(s, p, string("undeclared")))

        val dataset = Dataset {
            defaultGraph(repo)
            namedGraph(n1, repo, n1)
        }

        val defaults = dataset.select(SparqlSelectQuery("SELECT ?o WHERE { ?s ?p ?o }")).map { it.getString("o") }
        assertEquals(listOf("default"), defaults)
        val named = dataset.select(SparqlSelectQuery("SELECT ?g ?o WHERE { GRAPH ?g { ?s ?p ?o } }"))
            .map { it.getString("o") }
        assertEquals(listOf("n1"), named)
        repo.close()
    }

    @Test
    fun `union graph hasTriple matches terms exactly instead of via SPARQL text`() {
        val repo = Rdf.memory()
        val bnode = BlankNode("x")
        repo.editGraph(g1).addTriple(RdfTriple(bnode, p, string("o")))
        repo.editGraph(g2).addTriple(RdfTriple(s, p, TrueLiteral))
        repo.editGraph(g2).addTriple(RdfTriple(s, p, LangString("hi", "ar", Direction.RTL)))
        val union = Dataset {
            defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1))
            defaultGraph(repo.getGraph(g2).asGraphRef(repo, g2))
        }.defaultGraph

        assertFalse(union.hasTriple(RdfTriple(BlankNode("unrelated"), p, string("o"))), "blank nodes are not wildcards")
        assertTrue(union.hasTriple(RdfTriple(s, p, TrueLiteral)))
        assertTrue(union.hasTriple(RdfTriple(s, p, TypedLiteral("true", XSD.boolean))))
        assertFalse(union.hasTriple(RdfTriple(s, p, string("true"))))
        assertTrue(union.hasTriple(RdfTriple(s, p, LangString("hi", "ar", Direction.RTL))))
        assertFalse(union.hasTriple(RdfTriple(s, p, LangString("hi", "ar"))), "direction is significant")
        repo.close()
    }

    @Test
    fun `graphs from different repositories are never sent to a source repository`() {
        val repo = CapturingRepository()
        val other = CapturingRepository()
        val dataset = Dataset {
            defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1))
            namedGraph(n1, other, n1)
        }
        dataset.select(SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")).toList()
        assertNull(repo.lastSelect)
        assertNull(other.lastSelect)
    }

    private class CapturingRepository(
        private val delegate: RdfRepository = Rdf.memory()
    ) : RdfRepository by delegate {
        var lastSelect: SparqlSelect? = null
        var lastAsk: SparqlAsk? = null
        var lastConstruct: SparqlConstruct? = null
        var lastDescribe: SparqlDescribe? = null

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
    }
}
