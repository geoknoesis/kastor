package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimits
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryCancelledException
import org.apache.jena.query.QueryExecution
import org.apache.jena.sparql.ARQConstants
import org.apache.jena.sparql.algebra.Algebra
import org.apache.jena.sparql.function.FunctionRegistry
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
 * The expressions of a sandboxed query on Jena are bounded ([JenaSandboxRewrite], [JenaCost]): a small query text can
 * neither compute a value past [SandboxLimits] nor hold its worker past the timeout. Each hostile query here would run
 * for minutes or exhaust the heap without the bounds (a probe measured `math:pow(2, 1000000000)` at 110 s and then
 * `OutOfMemoryError`); with them it fails at once with [SandboxLimitException], deterministically and **without a
 * timeout**. The timing tests use a generous margin (ten times the timeout).
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JenaSandboxBoundsTest {

    private val repository: RdfRepository = JenaRepository.MemoryRepository().apply {
        editDefaultGraph().addTriples(Rdf.parse("<urn:a> <urn:p> 1, 2 .", RdfFormat.TURTLE).getTriples())
    }

    @AfterEach fun close() = repository.close()

    private val prefixes = """
        PREFIX math: <http://www.w3.org/2005/xpath-functions/math#>
        PREFIX fn: <http://www.w3.org/2005/xpath-functions#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
    """.trimIndent()

    private fun plain(sparql: String) = repository.withSelectRows(SandboxedSelectQuery("$prefixes\n$sparql")) { it.toList() }

    private fun timed(sparql: String, timeout: Duration = Duration.ofSeconds(30)) =
        repository.withSelectRows(SandboxedSelectQuery("$prefixes\n$sparql"), emptyMap(), timeout) { it.toList() }

    private fun assertLimited(sparql: String) {
        for (run in listOf(::plain, { s: String -> timed(s) })) {
            val error = assertThrows(Throwable::class.java) { run(sparql) }
            assertTrue(
                generateSequence(error) { it.cause }.any { it is SandboxLimitException },
                "expected a sandbox limit for $sparql, got $error",
            )
        }
    }

    /** `BIND(CONCAT(?x0, ?x0) AS ?x1) ...`: [steps] doublings of [seed]. */
    private fun doublings(steps: Int, seed: String = "\"ab\"", step: (String) -> String = { "CONCAT($it, $it)" }) =
        "BIND ($seed AS ?x0) " + (1..steps).joinToString(" ") { "BIND (${step("?x${it - 1}")} AS ?x$it)" }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT ?ok { BIND (math:pow(7, 3000000) > 0 AS ?ok) }",
        "SELECT (STRLEN(STR(math:pow(2, 1000000000))) AS ?n) {}",
        "SELECT ?x { BIND (math:exp10(100000000) AS ?x) }",
        "SELECT ?x { BIND (<http://www.w3.org/ns/sparql#multiply>(math:pow(2, 60000), math:pow(2, 60000)) AS ?x) }",
        "SELECT ?x { BIND (fn:round-half-to-even(1.5, 2147483647) AS ?x) }",
    ])
    fun `powers and huge precisions are refused before they compute`(sparql: String) = assertLimited(sparql)

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
        // 2^23 chars, then a function that may multiply them: the expansion is over the limit, so nothing is built.
        for (f in listOf("ENCODE_FOR_URI", "UCASE", "LCASE", "fn:normalize-unicode", "fn:upper-case")) {
            assertLimited("SELECT (STRLEN($f(?x22)) AS ?n) { ${doublings(22)} }")
        }
        assertLimited("SELECT (STRLEN(REPLACE(?x16, \"^.*$\", \"${"$0".repeat(300)}\")) AS ?n) { ${doublings(16)} }")
    }

    @Test
    fun `a catastrophic regular expression runs in linear time, without a timeout`() {
        // Exponential on java.util.regex (round twelve stopped it at a step budget); linear on the RE2 engine.
        val text = "\"${"a".repeat(10_000)}!\""
        assertEquals(listOf(com.geoknoesis.kastor.rdf.FalseLiteral), plain("SELECT ?m { BIND (REGEX($text, \"((a+)+)+$\") AS ?m) }").map { it.get("m") })
        assertEquals(listOf(com.geoknoesis.kastor.rdf.FalseLiteral), plain("SELECT ?m { BIND (fn:matches($text, \"((a+)+)+$\") AS ?m) }").map { it.get("m") })
        assertEquals(1, plain("SELECT ?m { BIND (REPLACE($text, \"((a+)+)+$\", \"x\") AS ?m) }").size)
    }

    @Test
    fun `regular expressions the linear-time engine lacks fail the query, invalid ones are SPARQL errors`() {
        // Fail closed: a valid pattern the engine cannot honour must not become a silent non-match in a FILTER.
        // "\p{L}" x 80 compiled for 8 s in RE2/J's parser, which no deadline reaches: refused by its structural cost.
        for (pattern in listOf("(a)\\\\1", "a(?=b)", "a*+", "(?<!a)b", "a{1001}", "\\\\p{L}".repeat(80), "[\\\\p{L}-[\\\\p{Lu}]]".repeat(200))) {
            assertLimited("SELECT ?m { BIND (REGEX(\"ab\", \"$pattern\") AS ?m) }")
        }
        assertEquals(listOf(null), plain("SELECT ?m { BIND (REGEX(\"ab\", CONCAT(\"(\", \"a\")) AS ?m) }").map { it.get("m") })
        assertEquals(listOf(null), plain("SELECT ?m { BIND (REGEX(\"ab\", \"a\", CONCAT(\"u\")) AS ?m) }").map { it.get("m") })
    }

    @Test
    fun `group concat accumulates a bounded number of characters`() {
        val values = (1..64).joinToString(" ")
        assertLimited("SELECT (STRLEN(GROUP_CONCAT(?x20)) AS ?n) { ${doublings(20)} VALUES ?a { $values } VALUES ?b { $values } }")
    }

    @Test
    fun `a chain of triple terms that share their parts stops at the term limit`() {
        assertLimited("SELECT ?t40 { ${doublings(40, seed = "<urn:a>", step = { "TRIPLE($it, <urn:p>, $it)" })} }")
    }

    /** A triple-term expression with variables is refused (a chain of them doubles the term); TRIPLE() is bounded. */
    @Test
    fun `a triple-term expression with variables is refused, a constant one is not`() {
        val chain = doublings(30, seed = "<urn:a>", step = { "<<( $it <urn:p> $it )>>" })
        assertThrows(IllegalArgumentException::class.java) { plain("SELECT ?x30 { $chain }") }
        assertEquals(1, plain("SELECT ?t { BIND (<<( <urn:a> <urn:p> 1 )>> AS ?t) }").size)
        // The plan verification rejects such an expression too, should it ever pass the check.
        val op = Algebra.compile(org.apache.jena.query.QueryFactory.create("SELECT * { BIND (<<( ?s <urn:p> ?s )>> AS ?t) }"))
        assertThrows(IllegalStateException::class.java) {
            JenaSandboxBounds.verify(JenaSandboxRewrite(JenaSandboxBudget(Long.MAX_VALUE, System::nanoTime)).bound(op))
        }
    }

    /** GROUP_CONCAT counts the text it appends for every kind of term, IRIs too; IRI() results are checked. */
    @Test
    fun `group concat of IRIs and computed IRIs are bounded`() {
        val values = (1..64).joinToString(" ")
        assertLimited("SELECT (STRLEN(GROUP_CONCAT(?i)) AS ?n) { ${doublings(20)} BIND (IRI(CONCAT(\"urn:\", SUBSTR(?x20, 1, 4000000))) AS ?i) VALUES ?a { $values } VALUES ?b { $values } }")
        assertLimited("SELECT ?i { ${doublings(23)} BIND (IRI(CONCAT(\"urn:\", ?x23)) AS ?i) }")
    }

    @Test
    fun `a property path is bounded in size and depth`() {
        val wide = (1..100).joinToString("/") { "(<urn:p>|<urn:q>)" }
        assertThrows(IllegalArgumentException::class.java) { plain("SELECT * { <urn:a> $wide ?o }") }
        val deep = "(".repeat(40) + "<urn:p>" + ")*".repeat(40)
        assertThrows(IllegalArgumentException::class.java) { plain("SELECT * { <urn:a> $deep ?o }") }
        assertEquals(2, plain("SELECT * { <urn:a> (<urn:p>|<urn:q>)/^<urn:p> ?o }").size)
        // Jena lists the hidden variable of a path sequence (??P0) unbound under SELECT *: it is not a column.
        val trusted = repository.withSelectRows(com.geoknoesis.kastor.rdf.SparqlSelectQuery("SELECT * { <urn:a> <urn:p>/^<urn:p> ?o }")) { it.toList() }
        assertEquals(listOf(listOf("o"), listOf("o")), trusted.map { it.getVariableNames().toList() })
    }

    /** The bounds reach every place an expression can be: Jena's walker and transformer skip some of them. */
    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT * { <urn:a> <urn:p> ?o FILTER (math:pow(7, 3000000) > ?o) }",
        "SELECT (math:pow(7, 3000000) AS ?x) {}",
        "SELECT * { <urn:a> <urn:p> ?o } ORDER BY (math:pow(7, 3000000) + ?o)",
        "SELECT * { <urn:a> <urn:p> ?o } ORDER BY (math:pow(7, 3000000) + ?o) LIMIT 1",
        "SELECT ?g { <urn:a> <urn:p> ?o } GROUP BY (math:pow(7, 3000000) + ?o AS ?g)",
        "SELECT ?o { <urn:a> <urn:p> ?o } GROUP BY ?o HAVING (math:pow(7, 3000000) > ?o)",
        "SELECT (SUM(math:pow(7, 3000000) + ?o) AS ?s) { <urn:a> <urn:p> ?o }",
        "SELECT * { <urn:a> <urn:p> ?o FILTER EXISTS { BIND (math:pow(7, 3000000) AS ?y) } }",
        "SELECT * { <urn:a> <urn:p> ?o OPTIONAL { <urn:a> ?q ?r FILTER (math:pow(7, 3000000) > ?r) } }",
        "SELECT * { { SELECT (math:pow(7, 3000000) AS ?x) {} } }",
        "SELECT * { <urn:a> <urn:p> ?o FILTER (?o IN (math:pow(7, 3000000), 2)) }",
        "SELECT * { <urn:a> <urn:p> ?o FILTER (?o = 2 || math:pow(7, 3000000) > 0) }",
    ])
    fun `every place of an expression is bounded`(sparql: String) = assertLimited(sparql)

    @Test
    fun `a sandboxed query stops near its timeout while it computes`() {
        // Every row builds a 1 Mi-char string and replaces in it: seconds of bounded work, cut off by the timeout.
        val values = (1..2000).joinToString(" ")
        val sparql = "SELECT ?n { VALUES ?i { $values } ${doublings(19)} BIND (STRLEN(REPLACE(?x19, \"a\", \"c\")) + ?i AS ?n) }"
        val started = System.nanoTime()
        val error = assertThrows(Throwable::class.java) { timed(sparql, Duration.ofMillis(500)) }
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(generateSequence(error) { it.cause }.any { it is QueryCancelledException }, "expected a cancellation, got $error")
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
        assertTrue(generateSequence(error) { it.cause }.any { it is QueryCancelledException }, "expected a cancellation, got $error")
        assertTrue(elapsed < Duration.ofSeconds(5), "a 500 ms timeout took $elapsed")
    }

    @Test
    fun `standard functions keep their results`() {
        val sparql = """
            SELECT ?p ?r1 ?r2 ?r3 ?r4 ?m ?c ?u ?l ?e ?d ?s ?t ?g {
              BIND (math:pow(2, 10) AS ?p) BIND (REPLACE("abc", "", "x") AS ?r1) BIND (REPLACE("aBc"@en, "b", "[$0]", "i") AS ?r2)
              BIND (REPLACE("a.c", ".", "x", "q") AS ?r3) BIND (fn:replace("abcb", "(b)", "<$1>") AS ?r4)
              BIND (REGEX("ABC", "b", "i") AS ?m) BIND (CONCAT("a", "b"@en, "c") AS ?c)
              BIND (UCASE("ab") AS ?u) BIND (LCASE("AB"@en) AS ?l) BIND (ENCODE_FOR_URI("a b") AS ?e) BIND (3 * 4 / 2 AS ?d)
              BIND (STRLEN(STR(xsd:decimal("1.50"))) AS ?s) BIND (xsd:integer("42") AS ?t) BIND (STRDT("7", xsd:integer) + 1 AS ?g)
            }
        """.trimIndent()
        val sandboxed = plain(sparql).single()
        val trusted = repository.withSelectRows(com.geoknoesis.kastor.rdf.SparqlSelectQuery("$prefixes\n$sparql")) { it.toList() }.single()
        for (name in listOf("p", "r1", "r2", "r3", "r4", "m", "c", "u", "l", "e", "d", "s", "t", "g")) {
            assertEquals(trusted.get(name), sandboxed.get(name), name)
        }
        assertEquals(TypedLiteral("1024", XSD.integer), sandboxed.get("p"))
        assertEquals(TypedLiteral("xabc", XSD.string), sandboxed.get("r1"))
        assertEquals(com.geoknoesis.kastor.rdf.LangString("a[B]c", "en"), sandboxed.get("r2"))
    }

    @Test
    fun `a malformed replacement is a SPARQL error, not a query failure`() {
        assertEquals(listOf(null), plain("SELECT ?r { BIND (REPLACE(\"abc\", \"b\", \"$\") AS ?r) }").map { it.get("r") })
        assertEquals(listOf(null), plain("SELECT ?r { BIND (REPLACE(\"abc\", \"b\", \"\\\\x\") AS ?r) }").map { it.get("r") })
        // fn:replace: a reference to a missing group up to 9 is empty.
        assertEquals(listOf("ac"), plain("SELECT ?r { BIND (REPLACE(\"abc\", \"b\", \"$2\") AS ?r) }").map { it.get("r")?.let { t -> (t as com.geoknoesis.kastor.rdf.Literal).lexical } })
    }

    @Test
    fun `copying functions still work on a large literal of the data`() {
        val big = "x".repeat(SandboxLimits.MAX_STRING_LENGTH + 10)
        repository.editDefaultGraph().addTriples(listOf(com.geoknoesis.kastor.rdf.RdfTriple(
            com.geoknoesis.kastor.rdf.Iri("urn:big"), com.geoknoesis.kastor.rdf.Iri("urn:p"), TypedLiteral(big, XSD.string),
        )))
        val rows = plain("SELECT ?n ?c { <urn:big> <urn:p> ?v BIND (STRLEN(STR(?v)) AS ?n) BIND (CONTAINS(SUBSTR(?v, 2), \"xx\") AS ?c) }")
        assertEquals(TypedLiteral((big.length).toString(), XSD.integer), rows.single().get("n"))
    }

    @Test
    fun `every allowed function is registered, and every standard registration is reviewed`() {
        val registry = FunctionRegistry.get()
        JenaCost.FUNCTIONS.keys.forEach { assertTrue(registry.isRegistered(it), "not registered: $it") }
        val namespaces = listOf(
            "http://www.w3.org/2001/XMLSchema#", "http://www.w3.org/2005/xpath-functions", "http://www.w3.org/ns/sparql#",
        )
        val unreviewed = registry.keys().asSequence()
            .filter { iri -> namespaces.any(iri::startsWith) }
            .filter { it !in JenaCost.FUNCTIONS && it !in JenaCost.EXCLUDED }
            .toList()
        assertEquals(emptyList<String>(), unreviewed, "standard functions Jena registers that the sandbox has not reviewed")
    }

    @Test
    fun `excluded functions are unknown to a sandboxed query`() {
        assertEquals(listOf(null), plain("SELECT ?k { BIND (fn:collation-key(\"a\") AS ?k) }").map { it.get("k") })
        assertThrows(IllegalArgumentException::class.java) { plain("SELECT ?x { BIND (fn:apply(math:pi) AS ?x) }") }
    }

    @Test
    fun `a sandboxed execution whose plan was not bounded fails`() {
        val builder = JenaQuerySandbox.restrict(
            QueryExecution.dataset(DatasetFactory.createTxnMem()).query("SELECT * { BIND (1 AS ?x) }"),
            com.geoknoesis.kastor.rdf.SparqlFunctionLibrary.EMPTY, 0, Long.MAX_VALUE, System::nanoTime,
        )
        builder.build().use { exec ->
            // Not planned yet: the bounds have not been applied.
            assertThrows(IllegalStateException::class.java) { JenaQuerySandbox.requireBounded(exec) }
            exec.execSelect().forEachRemaining { }
            JenaQuerySandbox.requireBounded(exec)
            assertTrue(exec.context.get<Any>(ARQConstants.sysOptimizerFactory) is JenaSandboxRewrite)
        }
    }

    @Test
    fun `an unbounded expression in a plan fails verification`() {
        val op = Algebra.compile(org.apache.jena.query.QueryFactory.create("SELECT * { BIND (UCASE(\"a\") AS ?x) }"))
        assertThrows(IllegalStateException::class.java) { JenaSandboxBounds.verify(op) }
        JenaSandboxBounds.verify(JenaSandboxRewrite(JenaSandboxBudget(Long.MAX_VALUE, System::nanoTime)).bound(op))
    }
}
