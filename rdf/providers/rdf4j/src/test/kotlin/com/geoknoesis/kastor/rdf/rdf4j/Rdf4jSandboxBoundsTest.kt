package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException
import org.eclipse.rdf4j.query.QueryInterruptedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * The expressions of a sandboxed query on RDF4J are bounded ([Rdf4jEvaluationBounds]), as on Jena: a small query text
 * can neither compute a value past [com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimits] nor hold its worker past
 * the timeout. Each hostile query fails at once with [SandboxLimitException] in its cause chain, deterministically and
 * without a timeout. The timing test uses a generous margin (ten times the timeout).
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class Rdf4jSandboxBoundsTest {

    private val repository: RdfRepository = Rdf4jRepository.MemoryRepository().apply {
        editDefaultGraph().addTriples(Rdf.parse("<urn:a> <urn:p> 1, 2 .", RdfFormat.TURTLE).getTriples())
    }

    @AfterEach fun close() = repository.close()

    private val prefixes = "PREFIX fn: <http://www.w3.org/2005/xpath-functions#> PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>"

    private fun plain(sparql: String) = repository.withSelectRows(SandboxedSelectQuery("$prefixes $sparql")) { it.toList() }

    private fun timed(sparql: String, timeout: Duration = Duration.ofSeconds(30)) =
        repository.withSelectRows(SandboxedSelectQuery("$prefixes $sparql"), emptyMap(), timeout) { it.toList() }

    private fun assertLimited(sparql: String) {
        for (run in listOf(::plain, { s: String -> timed(s) })) {
            val error = assertThrows(Throwable::class.java) { run(sparql) }
            assertTrue(generateSequence(error) { it.cause }.any { it is SandboxLimitException }, "expected a sandbox limit for $sparql, got $error")
        }
    }

    private fun doublings(steps: Int, seed: String = "\"ab\"", step: (String) -> String = { "CONCAT($it, $it)" }) =
        "BIND ($seed AS ?x0) " + (1..steps).joinToString(" ") { "BIND (${step("?x${it - 1}")} AS ?x$it)" }

    /** A catastrophic regular expression: exponential on Java's matcher, so only its step budget ends it. */
    private val bomb = "REGEX(\"${"a".repeat(40)}!\", \"((a+)+)+$\")"

    @Test
    fun `a chain of doublings stops at the string limit`() {
        assertLimited("SELECT (STRLEN(?x40) AS ?n) { ${doublings(40)} }")
        assertLimited("SELECT ?n { ${doublings(40, step = { "fn:concat($it, $it)" })} BIND (STRLEN(?x40) AS ?n) }")
    }

    @Test
    fun `a chain of squarings stops at the integer limit`() {
        assertLimited("SELECT ?x30 { ${doublings(30, seed = "3", step = { "$it * $it" })} }")
    }

    @Test
    fun `amplifying string functions are checked before they compute`() {
        for (f in listOf("ENCODE_FOR_URI", "UCASE", "LCASE")) {
            assertLimited("SELECT (STRLEN($f(?x22)) AS ?n) { ${doublings(22)} }")
        }
        assertLimited("SELECT (STRLEN(REPLACE(?x16, \"^.*$\", \"${"$0".repeat(300)}\")) AS ?n) { ${doublings(16)} }")
    }

    @Test
    fun `a catastrophic regular expression ends at its step budget, without a timeout`() {
        assertLimited("SELECT ?m { BIND ($bomb AS ?m) }")
        assertLimited("SELECT ?m { BIND (REPLACE(\"${"a".repeat(40)}!\", \"((a+)+)+$\", \"x\") AS ?m) }")
    }

    @Test
    fun `group concat accumulates a bounded number of characters`() {
        val values = (1..64).joinToString(" ")
        assertLimited("SELECT (STRLEN(GROUP_CONCAT(?x20)) AS ?n) { ${doublings(20)} VALUES ?a { $values } VALUES ?b { $values } }")
    }

    @Test
    fun `a chain of triple terms that share their parts stops at the term limit`() {
        val triple = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#Statement>"
        assertLimited("SELECT ?t40 { ${doublings(40, seed = "<urn:a>", step = { "$triple($it, <urn:p>, $it)" })} }")
    }

    /**
     * RDF4J's join optimizer is exponential in the length of a chain of triple-term expressions (it plans them as joins),
     * so a query may have at most 16 of them; a chain within that bound is planned and evaluated at once. (A triple
     * term computed by `rdf:Statement` is bounded in size, see the chain test above.)
     */
    @Test
    fun `triple-term expressions are bounded in number`() {
        plain("SELECT ?t14 { ${doublings(14, seed = "<urn:a>", step = { "<<$it <urn:p> $it>>" })} }")
        val started = System.nanoTime()
        assertThrows(IllegalArgumentException::class.java) {
            plain("SELECT ?t40 { ${doublings(40, seed = "<urn:a>", step = { "<<$it <urn:p> $it>>" })} }")
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
    }

    @Test
    fun `group concat and concat of IRIs are bounded`() {
        val values = (1..64).joinToString(" ")
        assertLimited("SELECT (STRLEN(GROUP_CONCAT(?i)) AS ?n) { ${doublings(20)} BIND (IRI(CONCAT(\"urn:\", SUBSTR(?x20, 1, 4000000))) AS ?i) VALUES ?a { $values } VALUES ?b { $values } }")
        assertLimited("SELECT ?i { ${doublings(23)} BIND (IRI(CONCAT(\"urn:\", ?x23)) AS ?i) }")
    }

    @Test
    fun `property-path closures are bounded in number`() {
        val many = (1..70).joinToString("/") { "<urn:p>*" }
        assertThrows(IllegalArgumentException::class.java) { plain("SELECT * { <urn:a> $many ?o }") }
        assertEquals(3, plain("SELECT * { <urn:a> <urn:p>* ?o }").size)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT * { <urn:a> <urn:p> ?o FILTER (BOMB) }",
        "SELECT (BOMB AS ?x) {}",
        "SELECT * { <urn:a> <urn:p> ?o } ORDER BY (BOMB)",
        "SELECT * { <urn:a> <urn:p> ?o } ORDER BY (BOMB) LIMIT 1",
        "SELECT ?g { <urn:a> <urn:p> ?o } GROUP BY (BOMB AS ?g)",
        "SELECT ?o { <urn:a> <urn:p> ?o } GROUP BY ?o HAVING (BOMB)",
        "SELECT (COUNT(IF(BOMB, 1, 0)) AS ?s) { <urn:a> <urn:p> ?o }",
        "SELECT * { <urn:a> <urn:p> ?o FILTER EXISTS { BIND (BOMB AS ?y) FILTER (BOUND(?y)) } }",
        "SELECT * { <urn:a> <urn:p> ?o OPTIONAL { <urn:a> ?q ?r FILTER (BOMB) } }",
        "SELECT * { { SELECT (BOMB AS ?x) {} } }",
        "SELECT * { <urn:a> <urn:p> ?o FILTER (?o IN (BOMB, 2)) }",
        "SELECT * { <urn:a> <urn:p> ?o FILTER (?o = 3 || BOMB) }",
        "SELECT * { <urn:a> <urn:p> ?o FILTER (COALESCE(BOMB, true)) }",
    ])
    fun `every place of an expression is bounded`(sparql: String) = assertLimited(sparql.replace("BOMB", bomb))

    @Test
    fun `a sandboxed query stops near its timeout while it computes`() {
        val values = (1..2000).joinToString(" ")
        val sparql = "SELECT ?n { VALUES ?i { $values } ${doublings(19)} BIND (STRLEN(REPLACE(?x19, \"a\", \"c\")) + ?i AS ?n) }"
        val started = System.nanoTime()
        val error = assertThrows(Throwable::class.java) { timed(sparql, Duration.ofMillis(500)) }
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(generateSequence(error) { it.cause }.any { it is QueryInterruptedException }, "expected an interruption, got $error")
        assertTrue(elapsed < Duration.ofSeconds(5), "a 500 ms timeout took $elapsed")
    }

    /** A cross product of `VALUES` tables reads no statement and evaluates no expression; its rows check the deadline. */
    @Test
    fun `a cross product that computes nothing stops near its timeout`() {
        val values = (1..400).joinToString(" ")
        val sparql = "SELECT (COUNT(*) AS ?n) { VALUES ?a { $values } VALUES ?b { $values } VALUES ?c { $values } }"
        val started = System.nanoTime()
        val error = assertThrows(Throwable::class.java) { timed(sparql, Duration.ofMillis(500)) }
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(generateSequence(error) { it.cause }.any { it is QueryInterruptedException }, "expected a cancellation, got $error")
        assertTrue(elapsed < Duration.ofSeconds(5), "a 500 ms timeout took $elapsed")
    }

    @Test
    fun `standard functions keep their results`() {
        val sparql = """
            SELECT ?r1 ?r2 ?r3 ?m ?c ?u ?l ?e ?d ?s ?t ?g {
              BIND (REPLACE("abcb", "b", "x") AS ?r1) BIND (REPLACE("aBc"@en, "b", "[$0]", "i") AS ?r2)
              BIND (REPLACE("abcb", "(b)", "<$1>") AS ?r3) BIND (REGEX("ABC", "b", "i") AS ?m) BIND (CONCAT("a", "b", "c") AS ?c)
              BIND (UCASE("ab") AS ?u) BIND (LCASE("AB"@en) AS ?l) BIND (ENCODE_FOR_URI("a b") AS ?e) BIND (3 * 4 / 2 AS ?d)
              BIND (STRLEN(STR(xsd:decimal("1.50"))) AS ?s) BIND (xsd:integer("42") AS ?t) BIND (STRDT("7", xsd:integer) + 1 AS ?g)
            }
        """.trimIndent()
        val sandboxed = plain(sparql).single()
        val trusted = repository.withSelectRows(SparqlSelectQuery("$prefixes $sparql")) { it.toList() }.single()
        for (name in listOf("r1", "r2", "r3", "m", "c", "u", "l", "e", "d", "s", "t", "g")) {
            assertEquals(trusted.get(name), sandboxed.get(name), name)
        }
    }

    @Test
    fun `a malformed replacement is a SPARQL error, not a query failure`() {
        assertEquals(listOf(null), plain("SELECT ?r { BIND (REPLACE(\"abc\", \"b\", \"$\") AS ?r) }").map { it.get("r") })
        assertEquals(listOf(null), plain("SELECT ?r { BIND (REPLACE(\"abc\", \"b\", \"$2\") AS ?r) }").map { it.get("r") })
    }

    @Test
    fun `every allowed built-in has a cost and the costly ones are listed`() {
        val allowed = org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry.getInstance().keys
            .filter(Rdf4jSandboxFunctions::isStandard)
        // The functions whose cost grows faster than their arguments, or that copy or replace text, are classified.
        for (iri in listOf("concat", "upper-case", "lower-case", "encode-for-uri", "replace").map { "http://www.w3.org/2005/xpath-functions#$it" }) {
            assertTrue(iri in allowed, iri)
            assertTrue(Rdf4jCost.ofFunction(iri) != Rdf4jCost.GENERIC, iri)
        }
        assertEquals(Rdf4jCost.XSD_CAST, Rdf4jCost.ofFunction("http://www.w3.org/2001/XMLSchema#integer"))
        assertEquals(Rdf4jCost.STRDT, Rdf4jCost.ofFunction("STRDT"))
    }
}
