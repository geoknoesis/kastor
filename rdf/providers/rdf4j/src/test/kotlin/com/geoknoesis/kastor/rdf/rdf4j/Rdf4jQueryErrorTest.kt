package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Direction
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.TripleTerm
import org.eclipse.rdf4j.query.QueryLanguage
import org.eclipse.rdf4j.query.TupleQuery
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

    /** A repository whose tuple queries fail with [error] when they are prepared. */
    private class FailingRepository(delegate: Repository, private val error: () -> Throwable) : RepositoryWrapper(delegate) {
        override fun getConnection(): RepositoryConnection = object : RepositoryConnectionWrapper(this, delegate.connection) {
            override fun prepareTupleQuery(ql: QueryLanguage?, query: String?): TupleQuery = throw error()
            override fun prepareTupleQuery(query: String?): TupleQuery = throw error()
            override fun prepareTupleQuery(ql: QueryLanguage?, query: String?, baseURI: String?): TupleQuery = throw error()
        }
    }

    private fun failing(error: () -> Throwable): Rdf4jRepository =
        Rdf4jRepository(FailingRepository(SailRepository(MemoryStore()).also { it.init() }, error))

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
