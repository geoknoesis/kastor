package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.eclipse.rdf4j.common.iteration.CloseableIteration
import org.eclipse.rdf4j.common.iteration.EmptyIteration
import org.eclipse.rdf4j.common.transaction.QueryEvaluationMode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.query.QueryEvaluationException
import org.eclipse.rdf4j.query.algebra.QueryRoot
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedService
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics
import org.eclipse.rdf4j.query.impl.EmptyBindingSet
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a [SandboxedSelectQuery] may evaluate on RDF4J is an allowlist: the SPARQL built-ins RDF4J implements itself,
 * and the query's custom functions. A function some other code registered in RDF4J's global `FunctionRegistry` is an
 * unknown function to it, and `SERVICE` has no resolver even if a query reached evaluation without the static check.
 */
class Rdf4jSandboxFunctionsTest {

    private val repository: RdfRepository = Rdf4jProvider().createRepository("memory", RdfConfig()).apply {
        editDefaultGraph().addTriples(Rdf.parse("<urn:a> <urn:p> 1 .", RdfFormat.TURTLE).getTriples())
    }

    private val global = "urn:kastor:test:global-function"
    private val calls = AtomicInteger()

    /** A function with a side effect (it counts its calls), registered globally as an application might. */
    private val counting = object : Function {
        override fun getURI(): String = global
        override fun evaluate(valueFactory: ValueFactory, vararg args: Value): Value {
            calls.incrementAndGet()
            return args[0]
        }
    }

    @AfterEach fun close() {
        FunctionRegistry.getInstance().remove(counting)
        repository.close()
    }

    private fun bound(sparql: String) =
        repository.withSelectRows(SandboxedSelectQuery(sparql), emptyMap(), Duration.ofSeconds(10)) { it.toList() }

    private fun plain(sparql: String) = repository.withSelectRows(SandboxedSelectQuery(sparql)) { it.toList() }

    @Test
    fun `a globally registered function is not called by a sandboxed query`() {
        FunctionRegistry.getInstance().add(counting)
        val sparql = "SELECT ?x WHERE { <urn:a> <urn:p> ?o BIND (<$global>(?o) AS ?x) }"
        assertEquals(listOf(null), bound(sparql).map { it.get("x") })
        assertEquals(listOf(null), plain(sparql).map { it.get("x") })
        assertEquals(0, calls.get())
        // Outside the sandbox it runs: the sandbox is what keeps it out.
        repository.withSelectRows(SparqlSelectQuery(sparql)) { it.toList() }
        assertTrue(calls.get() > 0)
    }

    @Test
    fun `a custom function wins over a non-standard global function with its IRI`() {
        FunctionRegistry.getInstance().add(counting)
        val custom = com.geoknoesis.kastor.rdf.SparqlFunction(global, listOf(false)) { com.geoknoesis.kastor.rdf.string("custom") }
        val sparql = "SELECT ?x WHERE { <urn:a> <urn:p> ?o BIND (<$global>(?o) AS ?x) }"
        val rows = repository.withSelectRows(SandboxedSelectQuery(sparql, SparqlFunctionLibrary.of(listOf(custom)))) { it.toList() }
        assertEquals(listOf(TypedLiteral("custom", XSD.string)), rows.map { it.get("x") })
        assertEquals(0, calls.get())
    }

    @Test
    fun `standard functions still evaluate in the sandbox`() {
        val sparql = """
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            SELECT ?i ?len ?u ?h ?id WHERE {
              <urn:a> <urn:p> ?o
              BIND (xsd:integer("7") AS ?i) BIND (STRLEN(CONCAT("a", STR(?o))) AS ?len) BIND (UCASE("ab") AS ?u)
              BIND (STRLEN(SHA256("x")) = 64 AS ?h) BIND (STRLEN(STRUUID()) AS ?id)
            }
        """.trimIndent()
        for (row in listOf(bound(sparql).single(), plain(sparql).single())) {
            assertEquals(TypedLiteral("7", XSD.integer), row.get("i"))
            assertEquals(TypedLiteral("2", XSD.integer), row.get("len"))
            assertEquals(TypedLiteral("AB", XSD.string), row.get("u"))
            assertEquals(TrueLiteral, row.get("h"))
            assertEquals(TypedLiteral("36", XSD.integer), row.get("id"))
        }
    }

    @Test
    fun `every function the sandbox allows is one of RDF4J's own built-ins`() {
        val allowed = FunctionRegistry.getInstance().keys.filter(Rdf4jSandboxFunctions::isStandard)
        assertTrue(allowed.size >= 50, "$allowed")
        // W3C IRIs (XPath functions, XSD casts, rdf:subject ...) or the bare names of SPARQL keywords (RAND, UUID ...).
        assertTrue(allowed.all { it.startsWith("http://www.w3.org/") || ':' !in it }, "$allowed")
        assertTrue("RAND" in allowed && "http://www.w3.org/2001/XMLSchema#integer" in allowed, "$allowed")
    }

    /**
     * The strategy of a sandboxed query alone, on a query that never went through the static check: `SERVICE` fails
     * without asking the resolver for a service.
     */
    @Test
    fun `service fails at evaluation even without the static check`() {
        val asked = AtomicInteger()
        val resolver = FederatedServiceResolver { _: String -> asked.incrementAndGet(); throw QueryEvaluationException("resolver reached") }
        val scope = Rdf4jFunctionScope.Scope(SparqlFunctionLibrary.EMPTY, 0, Long.MAX_VALUE, System::nanoTime, { _, _, _ -> error("unused") }, null)
        val strategy = KastorEvaluationStrategy(EmptySource, null, resolver, 0, EvaluationStatistics(), false, QueryEvaluationMode.STANDARD, scope)
        val parsed = SPARQLParser().parseQuery("SELECT * WHERE { SERVICE <http://127.0.0.1:1/s> { ?s ?p ?o } }", null)
        val error = assertThrows(QueryEvaluationException::class.java) {
            val optimized = strategy.optimize(QueryRoot(parsed.tupleExpr), EvaluationStatistics(), EmptyBindingSet.getInstance())
            strategy.precompile(optimized).evaluate(EmptyBindingSet.getInstance()).use { it.hasNext() }
        }
        assertTrue(error.message!!.contains("SERVICE"), error.message)
        assertEquals(0, asked.get())
        assertThrows(QueryEvaluationException::class.java) { strategy.getService("http://127.0.0.1:1/s") }
        assertEquals(0, asked.get())
    }

    /** The strategy refuses a custom aggregate too, for a query that reached evaluation without the static check. */
    @Test
    fun `a custom aggregate fails at evaluation even without the static check`() {
        val scope = Rdf4jFunctionScope.Scope(SparqlFunctionLibrary.EMPTY, 0, Long.MAX_VALUE, System::nanoTime, { _, _, _ -> error("unused") }, null)
        val strategy = KastorEvaluationStrategy(EmptySource, null, null, 0, EvaluationStatistics(), false, QueryEvaluationMode.STANDARD, scope)
        val parsed = SPARQLParser().parseQuery("SELECT (<http://rdf4j.org/aggregate#stdev>(?o) AS ?x) WHERE { BIND (1 AS ?o) }", null)
        val error = assertThrows(QueryEvaluationException::class.java) {
            val optimized = strategy.optimize(QueryRoot(parsed.tupleExpr), EvaluationStatistics(), EmptyBindingSet.getInstance())
            strategy.precompile(optimized).evaluate(EmptyBindingSet.getInstance()).use { it.hasNext() }
        }
        assertTrue(error.message!!.contains("aggregate"), error.message)
    }

    private object EmptySource : TripleSource {
        override fun getStatements(subj: Resource?, pred: IRI?, obj: Value?, vararg contexts: Resource?): CloseableIteration<out Statement> =
            EmptyIteration()

        override fun getValueFactory(): ValueFactory = SimpleValueFactory.getInstance()
    }

    @Test
    fun `nested parses are checked once per text and refusals are never cached`() {
        val checks = AtomicInteger()
        val parses = Rdf4jQuerySandbox.NestedParses({ sparql ->
            checks.incrementAndGet()
            Rdf4jQuerySandbox.check(SPARQLParser().parseQuery(sparql, null))
        }, capacity = 2)
        val a = "SELECT * WHERE { ?s ?p ?o }"
        repeat(3) { parses.get(a) }
        assertEquals(1, checks.get())
        val service = "SELECT * WHERE { SERVICE <http://127.0.0.1:1/s> { ?s ?p ?o } }"
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { parses.get(service) } }
        assertEquals(3, checks.get())
        parses.get("SELECT * WHERE { ?s ?p 1 }")
        parses.get("SELECT * WHERE { ?s ?p 2 }")
        parses.get(a) // dropped, least recently used
        assertEquals(6, checks.get())
    }

    @Test
    fun `a nested query run once per row from a shared parse gives each row its own result`() {
        val counted = com.geoknoesis.kastor.rdf.SparqlFunction("urn:kastor:test:n", listOf(false)) { call ->
            call.select(SandboxedSelectQuery("SELECT (COUNT(*) AS ?n) WHERE { ?s ?p ?o }"), emptyMap()) { it.single().get("n") }
        }
        repository.editDefaultGraph().addTriples(Rdf.parse("<urn:b> <urn:p> 2 . <urn:c> <urn:p> 3 .", RdfFormat.TURTLE).getTriples())
        val rows = repository.withSelectRows(
            SandboxedSelectQuery("SELECT ?x WHERE { ?s <urn:p> ?o BIND (<urn:kastor:test:n>(?o) AS ?x) }", SparqlFunctionLibrary.of(listOf(counted))),
        ) { it.toList() }
        assertEquals(List(3) { TypedLiteral("3", XSD.integer) }, rows.map { it.get("x") })
    }

    @Test
    fun `an ordinary custom function call still works`() {
        val f = com.geoknoesis.kastor.rdf.SparqlFunction("urn:kastor:test:f", listOf(false)) { Iri("urn:out") }
        val rows = repository.withSelectRows(
            SandboxedSelectQuery("SELECT ?x WHERE { BIND (<urn:kastor:test:f>(1) AS ?x) }", SparqlFunctionLibrary.of(listOf(f))),
        ) { it.toList() }
        assertEquals(listOf(Iri("urn:out")), rows.map { it.get("x") })
    }
}
