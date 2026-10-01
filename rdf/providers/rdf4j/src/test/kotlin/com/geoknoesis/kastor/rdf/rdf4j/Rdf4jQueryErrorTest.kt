package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Direction
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.SparqlConstructQuery
import com.geoknoesis.kastor.rdf.SparqlDescribeQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.UpdateQuery
import org.eclipse.rdf4j.query.BooleanQuery
import org.eclipse.rdf4j.query.GraphQuery
import org.eclipse.rdf4j.query.QueryLanguage
import org.eclipse.rdf4j.query.TupleQuery
import org.eclipse.rdf4j.query.Update
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper
import org.eclipse.rdf4j.repository.base.RepositoryWrapper
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Engine failures that are not `Exception`s, and initial bindings RDF4J cannot evaluate. */
class Rdf4jQueryErrorTest {
    private val p = Iri("http://example.org/p")
    private val select = SparqlSelectQuery("SELECT ?s WHERE { ?s ?p ?o }")

    private fun rows(repo: Rdf4jRepository, bindings: Map<String, RdfTerm>): List<Map<String, RdfTerm?>> =
        repo.withSelectRows(select, bindings, Duration.ofSeconds(30)) { rows ->
            rows.map { row -> row.getVariableNames().associateWith { row.get(it) } }.toList()
        }

    @Test
    fun `a triple-term binding with a blank-node component is rejected up front`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val term = TripleTerm(RdfTriple(BlankNode("b1"), p, Literal("x")))
            repo.editDefaultGraph().addTriple(RdfTriple(Iri("http://example.org/s"), p, term))
            val error = assertFailsWith<IllegalArgumentException> { rows(repo, mapOf("o" to term)) }
            assertTrue(error.message!!.contains("blank node"), error.message)
            assertTrue(error.message!!.contains("?o"), error.message)
            // Nested components are checked too.
            val nested = TripleTerm(RdfTriple(Iri("http://example.org/s"), p, term))
            assertFailsWith<IllegalArgumentException> { rows(repo, mapOf("o" to nested)) }
        }
    }

    @Test
    fun `a triple-term binding with a directional language string component is rejected up front`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val term = TripleTerm(RdfTriple(Iri("http://example.org/s"), p, LangString("x", "ar", Direction.RTL)))
            val error = assertFailsWith<IllegalArgumentException> { rows(repo, mapOf("o" to term)) }
            assertTrue(error.message!!.contains("directional"), error.message)
        }
    }

    @Test
    fun `a triple-term binding whose components can be spelled still works`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val term = TripleTerm(RdfTriple(Iri("http://example.org/a"), p, Literal("x")))
            repo.editDefaultGraph().addTriple(RdfTriple(Iri("http://example.org/s"), p, term))
            assertEquals(listOf<Map<String, RdfTerm?>>(mapOf("s" to Iri("http://example.org/s"))), rows(repo, mapOf("o" to term)))
        }
    }

    /**
     * A repository whose operations fail with [error]: when they are prepared, or (with [onEvaluate]) when a boolean
     * or graph query that was prepared normally is evaluated.
     */
    private class FailingRepository(
        delegate: Repository,
        private val onEvaluate: Boolean,
        private val error: () -> Throwable,
    ) : RepositoryWrapper(delegate) {
        /** [query] with an `evaluate()` that throws [error]. */
        private fun <Q : Any> failingOnEvaluate(type: Class<Q>, query: Q): Q = type.cast(
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                if (method.name == "evaluate") throw error()
                method.invoke(query, *(args ?: emptyArray()))
            },
        )

        override fun getConnection(): RepositoryConnection = object : RepositoryConnectionWrapper(this, delegate.connection) {
            override fun prepareTupleQuery(ql: QueryLanguage?, query: String?): TupleQuery = throw error()
            override fun prepareTupleQuery(query: String?): TupleQuery = throw error()
            override fun prepareTupleQuery(ql: QueryLanguage?, query: String?, baseURI: String?): TupleQuery = throw error()

            override fun prepareBooleanQuery(ql: QueryLanguage?, query: String?): BooleanQuery = prepareBooleanQuery(ql, query, null)
            override fun prepareBooleanQuery(query: String?): BooleanQuery = prepareBooleanQuery(QueryLanguage.SPARQL, query, null)
            override fun prepareBooleanQuery(ql: QueryLanguage?, query: String?, baseURI: String?): BooleanQuery =
                if (onEvaluate) failingOnEvaluate(BooleanQuery::class.java, super.prepareBooleanQuery(ql, query, baseURI)) else throw error()

            override fun prepareGraphQuery(ql: QueryLanguage?, query: String?): GraphQuery = prepareGraphQuery(ql, query, null)
            override fun prepareGraphQuery(query: String?): GraphQuery = prepareGraphQuery(QueryLanguage.SPARQL, query, null)
            override fun prepareGraphQuery(ql: QueryLanguage?, query: String?, baseURI: String?): GraphQuery =
                if (onEvaluate) failingOnEvaluate(GraphQuery::class.java, super.prepareGraphQuery(ql, query, baseURI)) else throw error()

            override fun prepareUpdate(ql: QueryLanguage?, update: String?): Update = throw error()
            override fun prepareUpdate(update: String?): Update = throw error()
            override fun prepareUpdate(ql: QueryLanguage?, update: String?, baseURI: String?): Update = throw error()
        }
    }

    private fun failing(onEvaluate: Boolean = false, error: () -> Throwable): Rdf4jRepository =
        Rdf4jRepository(FailingRepository(SailRepository(MemoryStore()).also { it.init() }, onEvaluate, error))

    /** ASK, CONSTRUCT, DESCRIBE (and the scoped CONSTRUCT) as named operations on [repo]. */
    private fun booleanAndGraphQueries(repo: Rdf4jRepository): Map<String, () -> Any> = linkedMapOf(
        "ask" to { repo.ask(SparqlAskQuery("ASK { ?s ?p ?o }")) },
        "construct" to { repo.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")).toList() },
        "describe" to { repo.describe(SparqlDescribeQuery("DESCRIBE <http://example.org/s>")).toList() },
        "withConstructTriples" to {
            repo.withConstructTriples(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")) { it.toList() }
        },
    )

    @Test
    fun `an assertion error while preparing or evaluating ASK, CONSTRUCT and DESCRIBE surfaces as RdfQueryException`() {
        for (onEvaluate in listOf(false, true)) {
            failing(onEvaluate) { AssertionError("engine invariant") }.use { repo ->
                for ((name, operation) in booleanAndGraphQueries(repo)) {
                    val error = assertFailsWith<RdfQueryException>("$name, onEvaluate=$onEvaluate") { operation() }
                    assertIs<AssertionError>(error.cause, name)
                    assertTrue(error.message!!.contains("engine invariant"), error.message)
                    assertTrue(error.query != null, name)
                }
            }
        }
    }

    @Test
    fun `other errors of ASK, CONSTRUCT and DESCRIBE are not wrapped`() {
        for (onEvaluate in listOf(false, true)) {
            failing(onEvaluate) { StackOverflowError("simulated") }.use { repo ->
                for ((name, operation) in booleanAndGraphQueries(repo)) {
                    assertFailsWith<StackOverflowError>("$name, onEvaluate=$onEvaluate") { operation() }
                }
            }
        }
    }

    @Test
    fun `an assertion error of an update surfaces as RdfQueryException`() {
        failing { AssertionError("engine invariant") }.use { repo ->
            val error = assertFailsWith<RdfQueryException> { repo.update(UpdateQuery("DELETE WHERE { ?s ?p ?o }")) }
            assertIs<AssertionError>(error.cause)
        }
        failing { OutOfMemoryError("simulated") }.use { repo ->
            assertFailsWith<OutOfMemoryError> { repo.update(UpdateQuery("DELETE WHERE { ?s ?p ?o }")) }
        }
    }

    @Test
    fun `an assertion error of the query engine surfaces as RdfQueryException`() {
        failing { AssertionError("engine invariant") }.use { repo ->
            val plain = assertFailsWith<RdfQueryException> { repo.select(select) }
            assertIs<AssertionError>(plain.cause)
            assertTrue(plain.message!!.contains("engine invariant"), plain.message)
            val bound = assertFailsWith<RdfQueryException> { rows(repo, mapOf("o" to Literal("x"))) }
            assertIs<AssertionError>(bound.cause)
        }
    }

    @Test
    fun `other errors of the query engine are not wrapped`() {
        failing { OutOfMemoryError("simulated") }.use { repo ->
            assertFailsWith<OutOfMemoryError> { repo.select(select) }
        }
        failing { StackOverflowError("simulated") }.use { repo ->
            assertFailsWith<StackOverflowError> { rows(repo, emptyMap()) }
        }
    }
}
