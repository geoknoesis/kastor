package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Timeout
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.Syntax
import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.expr.aggregate.Accumulator
import org.apache.jena.sparql.expr.aggregate.AggregateRegistry
import org.apache.jena.sparql.function.FunctionBase1
import org.apache.jena.sparql.function.FunctionRegistry
import java.time.Duration

/**
 * A [SandboxedSelectQuery] is checked on Jena's own algebra before evaluation: SERVICE, ARQ's `CALL`, `java:` and
 * Jena (`afn:` / `apf:`) functions or property functions and custom aggregates are refused with
 * [IllegalArgumentException] wherever they occur, so no request is sent and no class is loaded. The unreachable
 * endpoint 127.0.0.1:1 would fail differently if a query were evaluated. At run time only standard and custom
 * functions are found ([RunTimeRegistries]).
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JenaQuerySandboxTest {

    private val repository: RdfRepository = JenaProvider().createRepository("memory", RdfConfig()).apply {
        editDefaultGraph().addTriples(Rdf.parse("<urn:a> <urn:p> 1 .", RdfFormat.TURTLE).getTriples())
    }

    @AfterEach fun close() = repository.close()

    private fun bound(sparql: String) =
        repository.withSelectRows(SandboxedSelectQuery(sparql), emptyMap(), Duration.ofSeconds(10)) { it.toList() }

    private fun plain(sparql: String) = repository.withSelectRows(SandboxedSelectQuery(sparql)) { it.toList() }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT * WHERE { SERVICE <http://127.0.0.1:1/s> { ?s ?p ?o } }",
        "SELECT * WHERE { SERVICE SILENT <http://127.0.0.1:1/s> { ?s ?p ?o } }",
        "SELECT * WHERE { ?s ?p ?o OPTIONAL { SERVICE <http://127.0.0.1:1/s> { ?s ?q ?r } } }",
        "SELECT * WHERE { ?s ?p ?o FILTER EXISTS { SERVICE <http://127.0.0.1:1/s> { ?s ?q ?r } } }",
        "SELECT * WHERE { ?s ?p ?o BIND (NOT EXISTS { SERVICE <http://127.0.0.1:1/s> { ?s ?q ?r } } AS ?x) }",
        "SELECT * WHERE { { SELECT ?s WHERE { SERVICE <http://127.0.0.1:1/s> { ?s ?p ?o } } } }",
        "SELECT * WHERE { BIND (<http://127.0.0.1:1/s> AS ?e) SERVICE ?e { ?s ?p ?o } }",
        "SELECT * WHERE { ?s ?p ?o FILTER (<java:org.example.F>(?o)) }",
        "SELECT * WHERE { ?s ?p ?o BIND (<java:org.example.F>(?o) AS ?x) }",
        "SELECT (<java:org.example.F>(?o) AS ?x) WHERE { ?s ?p ?o }",
        "SELECT * WHERE { ?s ?p ?o } ORDER BY <java:org.example.F>(?o)",
        "SELECT (SUM(<java:org.example.F>(?o)) AS ?x) WHERE { ?s ?p ?o }",
        "SELECT ?s WHERE { ?s ?p ?o } GROUP BY ?s HAVING (<java:org.example.F>(?s))",
        "SELECT * WHERE { ?s <java:org.example.P> ?o }",
        "SELECT ?g WHERE { ?s ?p ?o } GROUP BY (<java:org.example.F>(?s) AS ?g)",
        "SELECT (COUNT(DISTINCT <java:org.example.F>(?o)) AS ?x) WHERE { ?s ?p ?o }",
        "SELECT * WHERE { ?s ?p ?o OPTIONAL { ?s ?q ?r FILTER (<java:org.example.F>(?r)) } }",
        "SELECT * WHERE { { SELECT ?s WHERE { ?s ?p ?o } ORDER BY <java:org.example.F>(?o) LIMIT 1 } }",
    ])
    fun `service and java code are refused before evaluation`(sparql: String) {
        assertThrows(IllegalArgumentException::class.java) { bound(sparql) }
        assertThrows(IllegalArgumentException::class.java) { plain(sparql) }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT * WHERE { ?s ?p ?o FILTER (<http://jena.apache.org/ARQ/function#wait>(2147483647)) }",
        "SELECT * WHERE { ?s ?p ?o BIND (<http://jena.apache.org/ARQ/function#print>(?o) AS ?x) }",
        "SELECT (<http://jena.apache.org/ARQ/function#context>(\"x\") AS ?x) WHERE { ?s ?p ?o }",
        "SELECT * WHERE { ?s ?p ?o FILTER (<http://jena.hpl.hp.com/ARQ/function#wait>(1)) }",
        "SELECT * WHERE { ?s ?p ?o } ORDER BY <http://jena.apache.org/ARQ/function#wait>(1)",
        "SELECT * WHERE { <urn:a> <http://jena.apache.org/ARQ/property#splitIRI> (?ns ?local) }",
        "SELECT * WHERE { <urn:a> <http://jena.hpl.hp.com/ARQ/property#splitIRI> (?ns ?local) }",
    ])
    fun `jena extension functions and property functions are refused before evaluation`(sparql: String) {
        assertThrows(IllegalArgumentException::class.java) { bound(sparql) }
        assertThrows(IllegalArgumentException::class.java) { plain(sparql) }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT ?x WHERE { <urn:a> <urn:p> ?o BIND (CALL(<http://www.w3.org/2005/xpath-functions#upper-case>, \"a\") AS ?x) }",
        "SELECT ?x WHERE { <urn:a> <urn:p> ?o BIND (CALL(IRI(CONCAT(\"http://jena.apache.org/ARQ/\", \"function#wait\")), 60000) AS ?x) }",
        "SELECT ?x WHERE { <urn:a> <urn:p> ?o FILTER (CALL(IRI(STR(?o)))) }",
    ])
    fun `CALL, which computes a function IRI at run time, is refused`(sparql: String) {
        assertThrows(IllegalArgumentException::class.java) { bound(sparql) }
        assertThrows(IllegalArgumentException::class.java) { plain(sparql) }
        // The same text is a valid query outside the sandbox.
        repository.withSelectRows(SparqlSelectQuery(sparql.replace("60000", "1"))) { it.toList() }
    }

    @Test
    fun `a custom aggregate is refused`() {
        val iri = "urn:kastor:test:aggregate:" + java.util.UUID.randomUUID()
        AggregateRegistry.register(iri, { _, _ ->
            object : Accumulator {
                override fun accumulate(binding: org.apache.jena.sparql.engine.binding.Binding, env: org.apache.jena.sparql.function.FunctionEnv) {}
                override fun getValue(): NodeValue = NodeValue.nvZERO
            }
        }, NodeValue.nvZERO.asNode())
        val sparql = "SELECT (<$iri>(?o) AS ?x) WHERE { ?s ?p ?o }"
        // Outside the sandbox the aggregate runs: the check is what refuses it.
        assertEquals(1, repository.withSelectRows(SparqlSelectQuery(sparql)) { it.toList() }.size)
        try {
            assertThrows(IllegalArgumentException::class.java) { bound(sparql) }
        } finally {
            AggregateRegistry.unregister(iri)
        }
    }

    @Test
    fun `standard functions still evaluate in the sandbox`() {
        val sparql = """
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            PREFIX fn: <http://www.w3.org/2005/xpath-functions#>
            PREFIX math: <http://www.w3.org/2005/xpath-functions/math#>
            SELECT ?i ?u ?pi ?len WHERE {
              <urn:a> <urn:p> ?o
              BIND (xsd:integer("7") AS ?i) BIND (fn:upper-case("ab") AS ?u) BIND (math:pi() > 3 AS ?pi)
              BIND (STRLEN(CONCAT("a", STR(?o))) AS ?len)
            }
        """.trimIndent()
        val row = bound(sparql).single()
        assertEquals(TypedLiteral("7", XSD.integer), row.get("i"))
        assertEquals(TypedLiteral("AB", XSD.string), row.get("u"))
        assertEquals(TrueLiteral, row.get("pi"))
        assertEquals(TypedLiteral("2", XSD.integer), row.get("len"))
    }

    @Test
    fun `rdfs member is matched as data, not run as a property function`() {
        repository.editDefaultGraph().addTriples(Rdf.parse(
            "<urn:bag> <http://www.w3.org/1999/02/22-rdf-syntax-ns#_1> <urn:x> . " +
                "<urn:set> <http://www.w3.org/2000/01/rdf-schema#member> <urn:y> .",
            RdfFormat.TURTLE,
        ).getTriples())
        val sparql = "SELECT ?c ?m WHERE { ?c <http://www.w3.org/2000/01/rdf-schema#member> ?m }"
        assertEquals(listOf(Iri("urn:y")), plain(sparql).map { it.get("m") })
    }

    /**
     * The run-time registries alone, on queries that the static check would refuse (`CALL`): whatever function
     * IRI the query computes, only standard and custom functions are found, nothing is loaded and nothing is
     * registered globally.
     */
    @Nested
    inner class RunTimeRegistries {
        private val dataset = org.apache.jena.query.DatasetFactory.create().apply {
            defaultModel.add(defaultModel.createResource("urn:a"), defaultModel.createProperty("urn:p"), "1")
        }

        private fun restricted(sparql: String): List<org.apache.jena.rdf.model.RDFNode?> {
            val query = QueryFactory.create(sparql, Syntax.syntaxARQ)
            val builder = JenaQuerySandbox.restrict(
                QueryExecution.dataset(dataset).query(query), SparqlFunctionLibrary.EMPTY, 0, Long.MAX_VALUE, System::nanoTime,
            )
            return builder.build().use { exec -> exec.execSelect().asSequence().map { it.get("x") }.toList() }
        }

        private fun called(iri: String, argument: String = "?o") =
            "SELECT ?x WHERE { <urn:a> <urn:p> ?o BIND (CALL(IRI(CONCAT(${split(iri)})), $argument) AS ?x) }"

        /** [iri] as two string literals, so no constant IRI appears in the query. */
        private fun split(iri: String) = "\"${iri.substring(0, 5)}\", \"${iri.substring(5)}\""

        @Test
        fun `a computed java IRI cannot load a class`() {
            val probe = "java:" + SandboxProbeFunction::class.java.name
            val before = SandboxProbeFunction.instances.get()
            assertEquals(listOf(null), restricted(called(probe)))
            assertEquals(before, SandboxProbeFunction.instances.get())
            // The same query outside the sandbox does load the class: the probe would detect a regression.
            QueryExecution.dataset(dataset).query(QueryFactory.create(called(probe), Syntax.syntaxARQ)).build()
                .use { it.execSelect().forEachRemaining { } }
            assertTrue(SandboxProbeFunction.instances.get() > before)
        }

        @Test
        fun `a computed afn wait does not run, so the query ends at once`() {
            val wait = "http://jena.apache.org/ARQ/function#wait"
            val registered = FunctionRegistry.get().isRegistered(wait)
            val started = System.nanoTime()
            assertEquals(listOf(null), restricted(called(wait, "60000")))
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(30), "afn:wait ran")
            assertEquals(registered, FunctionRegistry.get().isRegistered(wait), "the sandbox registered afn:wait globally")
        }

        @Test
        fun `jena extensions and globally registered functions are not found`() {
            val global = "urn:kastor:test:global:" + java.util.UUID.randomUUID()
            FunctionRegistry.get().put(global) { _ -> Identity() }
            try {
                assertEquals(listOf(null), restricted(called(global)))
                assertEquals(listOf(null), restricted(called("http://jena.apache.org/ARQ/function#print")))
                assertEquals(listOf(null), restricted(called("http://jena.apache.org/ARQ/function#localname", "<urn:a>")))
            } finally {
                FunctionRegistry.get().remove(global)
            }
        }

        @Test
        fun `computed standard functions are found`() {
            assertEquals("A", restricted(called("http://www.w3.org/2005/xpath-functions#upper-case", "\"a\"")).single()?.asLiteral()?.string)
            assertEquals(7, restricted(called("http://www.w3.org/2001/XMLSchema#integer", "\"7\"")).single()?.asLiteral()?.int)
        }
    }

    private class Identity : FunctionBase1() {
        override fun exec(v: NodeValue): NodeValue = v
    }

    @Test
    fun `an ordinary sandboxed query is evaluated`() {
        assertEquals(1, bound("SELECT ?o WHERE { <urn:a> <urn:p> ?o }").size)
        // A java: IRI as data, rather than as a function or property function, is harmless.
        assertEquals(1, plain("SELECT ?o WHERE { <urn:a> <urn:p> ?o BIND (<java:org.example.F> AS ?iri) }").size)
    }
}
